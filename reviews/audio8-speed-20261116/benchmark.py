"""Paired, resident-process Audio8 benchmark; does not modify service jobs."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import subprocess
import time

p = argparse.ArgumentParser()
p.add_argument("--binary", required=True)
p.add_argument("--output", type=Path, required=True)
p.add_argument("--no-prefix-cache", action="store_true")
p.add_argument("--boundary-only", action="store_true")
args = p.parse_args()
args.output.mkdir(parents=True, exist_ok=False)
model = "/home/jetson/workspace/Audio8-TTS-Realistic-Air"
voice = "/home/jetson/workspace/shanye-tts-service/audio/audio8-voices/5116b2367b728ab371c56aa54fc63369a65d6b1edf5b234a669aa40282f99a33/voice.json"
texts = [
    "「清水组长，它..它还在后面！」",
    "「清水组长，它..它还在后面！」",
    "因为下雨的缘故，脚下的路泥泞坎坷，跑在最前面的清水苍介跌倒了两次，但又很快爬起来，咬著牙继续跑，这孩子鬼比他所想的更加难缠，死咬著他们不放，令他心惊的是，即便他们冲到了山脚下，孩子鬼依旧在追。",
    "如今的黑泽凛身上浮现出了十几道伤痕，这还不包括被圆规刺屁股，雨水接触到伤口疼的他直哆嗦。",
]
if args.boundary_only:
    texts = [texts[2]+texts[3]]
command = [args.binary, "--model", model, "--codec", model+"/codec-decoder-bf16.safetensors", "--server"]
if args.no_prefix_cache:
    command.append("--no-prefix-cache")
events = []
reports = []
began = time.monotonic()
with (args.output/"stderr.log").open("w") as err:
    proc = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=err, text=True)
    try:
        def receive(stage):
            for line in proc.stdout:
                event = json.loads(line)
                events.append(event)
                if event.get("stage") == "error":
                    raise RuntimeError(event)
                if event.get("stage") == stage:
                    return event
            raise RuntimeError("Native process ended unexpectedly")
        ready = receive("ready")
        startup = time.monotonic()-began
        for i, text in enumerate(texts):
            directory = args.output/str(i)
            # Duplicate short utterance has the same seed to check cache equivalence.
            seed = [103100043,103100043,103100042,103100045][i]
            request = dict(op="synthesize",text=text,voice=voice,output_dir=str(directory),
                           seed=seed,pause=0,split_sentences=False,profile_frames=5)
            syllables = len(re.findall(r"[\u3400-\u9fff]",text))
            request["minimum_seconds"] = syllables/8 if syllables>=32 else 0
            started = time.monotonic()
            proc.stdin.write(json.dumps(request,ensure_ascii=False)+"\n")
            proc.stdin.flush()
            receive("completed")
            report = json.loads((directory/"report.json").read_text())
            report["wall_seconds"] = time.monotonic()-started
            report["codes_sha256"] = hashlib.sha256((directory/"1-codes.npy").read_bytes()).hexdigest()
            reports.append(report)
            print(json.dumps({"index":i,"wall":report["wall_seconds"],"audio":report["audio_seconds"],
                              "generation":report["generation_seconds"],"rtf":report["wall_seconds"]/report["audio_seconds"]}),flush=True)
        measured = reports[1:] if len(reports)>1 else reports
        result = dict(binary=args.binary,ready=ready,startup_seconds=startup,reports=reports,
                      warm_wall=sum(r["wall_seconds"] for r in measured),
                      warm_audio=sum(r["audio_seconds"] for r in measured),events=events)
        result["warm_rtf"] = result["warm_wall"]/result["warm_audio"]
        (args.output/"benchmark.json").write_text(json.dumps(result,ensure_ascii=False,indent=2))
        print(json.dumps({k:v for k,v in result.items() if k.startswith("warm_")}),flush=True)
    finally:
        if proc.poll() is None:
            proc.stdin.write('{"op":"quit"}\n')
            proc.stdin.flush()
            try:
                proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
