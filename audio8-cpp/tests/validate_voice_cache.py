"""On-device cache invalidation and voice-switch regression check."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess

import numpy as np

p = argparse.ArgumentParser()
p.add_argument("--binary",required=True)
p.add_argument("--model",type=Path,required=True)
p.add_argument("--voice",type=Path,required=True)
p.add_argument("--alternate-voice",type=Path,required=True)
p.add_argument("--output",type=Path,required=True)
p.add_argument("--flag",action="append",default=[])
a = p.parse_args()
a.model, a.voice, a.alternate_voice, a.output = [path.resolve() for path in (a.model,a.voice,a.alternate_voice,a.output)]
a.output.mkdir(parents=True,exist_ok=False)
voice = json.loads(a.voice.read_text(encoding="utf-8"))
codes = Path(voice["codes"])
if not codes.is_absolute():
    codes = a.voice.parent/codes
shutil.copyfile(codes,a.output/"reference.npy")
voice["codes"] = str(a.output/"reference.npy")
mutable = a.output/"voice.json"
mutable.write_text(json.dumps(voice,ensure_ascii=False),encoding="utf-8")
checks = []
with (a.output/"stderr.log").open("w") as err:
    proc = subprocess.Popen([a.binary,"--model",str(a.model),"--codec",str(a.model/"codec-decoder-bf16.safetensors"),
                             "--server","--no-graphs",*a.flag],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=err,text=True,encoding="utf-8")
    try:
        for line in proc.stdout:
            if json.loads(line).get("stage")=="ready":
                break
        def check(path,hit):
            directory = a.output/str(len(checks))
            req = dict(text="雨水落在山路上。",voice=str(path),output_dir=str(directory),diagnostics=True,
                       split_sentences=False,seed=42)
            proc.stdin.write(json.dumps(req,ensure_ascii=False)+"\n");proc.stdin.flush()
            for line in proc.stdout:
                event = json.loads(line)
                if event.get("stage")=="error":
                    assert "Diagnostic stop" in event.get("error",""),event
                    break
            else:
                raise RuntimeError("Native process ended")
            report = json.loads((directory/"report.json").read_text(encoding="utf-8"))
            cache = report["reference_cache"]
            assert cache["voice_hit"] is hit and cache["prefix_hit"] is hit,cache
            assert not (directory/"audio8.wav").exists()
            checks.append(dict(voice=str(path),cache=cache))
        check(a.voice,False)
        check(a.voice,True)
        check(a.alternate_voice,False)
        check(a.voice,False)
        check(a.voice,True)
        check(mutable,False)
        check(mutable,True)
        voice["text"] += "。"
        mutable.write_text(json.dumps(voice,ensure_ascii=False),encoding="utf-8")
        check(mutable,False)
        check(mutable,True)
        data = np.load(a.output/"reference.npy")
        data[0,0] = (data[0,0]+1)%4096
        np.save(a.output/"reference.npy",data)
        check(mutable,False)
        (a.output/"validation.json").write_text(json.dumps(dict(checks=checks),ensure_ascii=False,indent=2),encoding="utf-8")
        print("Voice cache: 10 checks passed",flush=True)
    finally:
        if proc.poll() is None:
            proc.stdin.write('{"op":"quit"}\n');proc.stdin.flush();proc.wait(timeout=20)
