"""Compare full native outputs across sampling graph reuse and recapture."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def run(binary, model, voice, output, flags):
    output.mkdir(parents=True)
    cases = [
        {},
        dict(top_k=20, top_p=.85, temperature=.8),
        dict(top_k=20, top_p=.85, temperature=.8, seed=43),
        dict(text='雨水落在山路上。天色渐渐暗了下来。', split_sentences=True),
        {},
    ]
    reports, events = [], []
    command = [str(binary), '--model', str(model), '--codec',
               str(model/'codec-decoder-bf16.safetensors'), '--server', *flags]
    with (output/'stderr.log').open('w', encoding='utf-8') as err:
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
                        return
                raise RuntimeError('Native process ended; see stderr.log')
            receive('ready')
            for index, options in enumerate(cases):
                directory = output/str(index)
                request = dict(text='雨水落在山路上。', seed=42, pause=.18,
                               split_sentences=False, voice=str(voice), output_dir=str(directory))
                request.update(options)
                proc.stdin.write(json.dumps(request, ensure_ascii=False)+'\n')
                proc.stdin.flush()
                receive('completed')
                report = json.loads((directory/'report.json').read_text(encoding='utf-8'))
                files = sorted(directory.glob('*-codes.npy'))+[directory/'audio8.wav']
                digests = {file.name: hashlib.sha256(file.read_bytes()).hexdigest() for file in files}
                assert report['reference_cache']['prefix_hit'] is (index > 0)
                reports.append(dict(index=index, hashes=digests, fragments=len(report['fragments'])))
            captures = sum(event.get('stage') == 'cuda_graphs_ready' for event in events)
            assert captures == 3, captures
            assert reports[0]['hashes'] == reports[-1]['hashes']
            return dict(reports=reports, graph_captures=captures)
        finally:
            if proc.poll() is None:
                try:
                    proc.stdin.write('{"op":"quit"}\n')
                    proc.stdin.flush()
                    proc.wait(timeout=20)
                except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
                    proc.kill()
                    proc.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--baseline-binary', type=Path)
    parser.add_argument('--model', type=Path, required=True)
    parser.add_argument('--voice', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    binary, model, voice = args.binary.resolve(), args.model.resolve(), args.voice.resolve()
    baseline = run((args.baseline_binary or binary).resolve(), model, voice, args.output/'baseline', [])
    fused = run(binary, model, voice, args.output/'fused', ['--fused-kernels'])
    assert baseline == fused, (baseline, fused)
    result = dict(passed=True, baseline=baseline, fused=fused)
    (args.output/'validation.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Sampling reuse/recapture: 5 requests, 6 fragments; codes and PCM identical.', flush=True)


if __name__ == '__main__':
    main()
