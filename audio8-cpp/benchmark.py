"""Repeatable resident-process benchmark on any supported Audio8 host."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--model', type=Path, required=True)
    parser.add_argument('--voice', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repeat', type=int, default=2)
    parser.add_argument('--flag', action='append', default=[])
    args = parser.parse_args()
    if args.repeat < 1:
        parser.error('--repeat must be positive')
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    cases = [
        ('「清水组长，它..它还在后面！」', 103100043),
        ('因为下雨的缘故，脚下的路泥泞坎坷。', 111138),
        ('如今的黑泽凛身上浮现出了十几道伤痕，这还不包括被圆规刺屁股，雨水接触到伤口疼的他直哆嗦。', 103100045),
    ]
    model = args.model.resolve()
    command = [str(args.binary.resolve()), '--model', str(model), '--codec',
               str(model/'codec-decoder-bf16.safetensors'), '--server', *args.flag]
    reports, events = [], []
    began = time.perf_counter()
    with (args.output/'stderr.log').open('w', encoding='utf-8') as err:
        proc = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=err, text=True, encoding='utf-8')
        try:
            def receive(stage):
                for line in proc.stdout:
                    event = json.loads(line)
                    events.append(event)
                    if event.get('stage') == 'error':
                        raise RuntimeError(event)
                    if event.get('stage') == stage:
                        return event
                raise RuntimeError(f'Native process ended with {proc.poll()}; see stderr.log')

            ready = receive('ready')
            startup = time.perf_counter()-began
            # Warm every case once, including its codec shape, before measuring.
            # Keep warmup outputs to check cache/graph equivalence as well.
            for i, (text, seed) in enumerate(cases+cases*args.repeat):
                directory = args.output/str(i)
                request = dict(op='synthesize', text=text, seed=seed, pause=0,
                               split_sentences=False, profile_frames=5,
                               voice=str(args.voice.resolve()), output_dir=str(directory))
                syllables = sum('\u3400' <= c <= '\u9fff' for c in text)
                request['minimum_seconds'] = syllables/8 if syllables >= 32 else 0
                start = time.perf_counter()
                proc.stdin.write(json.dumps(request, ensure_ascii=False)+'\n')
                proc.stdin.flush()
                receive('completed')
                elapsed = time.perf_counter()-start
                report = json.loads((directory/'report.json').read_text(encoding='utf-8'))
                report['wall_seconds'] = elapsed
                report['codes_sha256'] = hashlib.sha256((directory/'1-codes.npy').read_bytes()).hexdigest()
                report['pcm_sha256'] = hashlib.sha256((directory/'audio8.wav').read_bytes()).hexdigest()
                reports.append(report)
                print(json.dumps(dict(index=i, wall=elapsed, audio=report['audio_seconds'],
                                      rtf=elapsed/report['audio_seconds'])), flush=True)
            warm = reports[len(cases):]
            result = dict(binary=str(args.binary.resolve()), command=command, ready=ready,
                          startup_seconds=startup, warmup_requests=len(cases), reports=reports, events=events,
                          warm_wall=sum(r['wall_seconds'] for r in warm),
                          warm_audio=sum(r['audio_seconds'] for r in warm))
            result['warm_rtf'] = result['warm_wall']/result['warm_audio']
            (args.output/'benchmark.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
            print(json.dumps({k: v for k, v in result.items() if k.startswith('warm_')}), flush=True)
        finally:
            if proc.poll() is None:
                try:
                    proc.stdin.write('{"op":"quit"}\n')
                    proc.stdin.flush()
                    proc.wait(timeout=20)
                except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
                    proc.kill()
                    proc.wait()


if __name__ == '__main__':
    main()
