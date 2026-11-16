"""GPU integration check for compact projection and reference-prefix reuse."""
import argparse
import json
from pathlib import Path
import subprocess

import numpy as np


def diagnostic(binary, model, voice, output, flags):
    output.mkdir(parents=True)
    command = [binary, "--model", str(model), "--codec", str(model/"codec-decoder-bf16.safetensors"),
               "--server", "--no-graphs", *flags]
    with (output/"stderr.log").open("w") as err:
        proc = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=err, text=True)
        try:
            for line in proc.stdout:
                event = json.loads(line)
                if event.get("stage") == "ready":
                    break
                if event.get("stage") == "error":
                    raise RuntimeError(event)
            results = []
            # Two distinct suffixes check cached positions and changing text.
            for i, text in enumerate(["「清水组长，它..它还在后面！」", "雨水接触到伤口，疼得他直哆嗦。"]):
                directory = output/str(i)
                request = dict(op="synthesize", text=text, voice=str(voice), output_dir=str(directory),
                               seed=103100043, split_sentences=False, diagnostics=True)
                proc.stdin.write(json.dumps(request,ensure_ascii=False)+"\n")
                proc.stdin.flush()
                for line in proc.stdout:
                    event = json.loads(line)
                    if event.get("stage") == "error":
                        if "Diagnostic stop after first frame" not in event.get("error", ""):
                            raise RuntimeError(event)
                        break
                else:
                    raise RuntimeError("Process ended during diagnostic")
                results.append(dict(logits=np.fromfile(directory/"prefill-logits.raw",dtype="float32"),
                                    hidden=np.fromfile(directory/"prefill-hidden.raw",dtype="float32")))
            return results
        finally:
            if proc.poll() is None:
                proc.stdin.write('{"op":"quit"}\n')
                proc.stdin.flush()
                proc.wait(timeout=20)


def compare(actual, expected):
    delta = actual-expected
    p = np.exp((actual-actual.max())/.7);p /= p.sum()
    q = np.exp((expected-expected.max())/.7);q /= q.sum()
    return dict(max_absolute_error=float(abs(delta).max()), rmse=float(np.sqrt(np.mean(delta**2))),
                relative_rmse=float(np.sqrt(np.mean(delta**2))/max(np.sqrt(np.mean(expected**2)),1e-10)),
                probability_total_variation=float(abs(p-q).sum()/2),
                identical=bool(np.array_equal(actual,expected)))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--binary",required=True)
    p.add_argument("--model",type=Path,required=True)
    p.add_argument("--voice",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    args = p.parse_args()
    modes = {"full": ["--full-vocab","--no-prefix-cache"],
             "compact": ["--no-prefix-cache"], "cached": [], "legacy_kv": ["--legacy-kv"]}
    outputs = {name: diagnostic(args.binary,args.model,args.voice,args.output/name,flags)
               for name,flags in modes.items()}
    config = json.loads((args.model/"config.json").read_text())
    ids = [*range(config["semantic_begin_id"],config["semantic_end_id"]+1),config["eos_token_id"]]
    checks = []
    for i in range(2):
        full, compact, cached, legacy = [outputs[name][i] for name in modes]
        projection = compare(compact["logits"], full["logits"][ids])
        hidden = compare(compact["hidden"],full["hidden"])
        prefix = compare(cached["logits"],compact["logits"])
        kv = compare(cached["logits"],legacy["logits"])
        assert kv["identical"], kv
        assert np.array_equal(cached["hidden"],legacy["hidden"])
        # BF16 GEMM/SDPA kernel shapes may round differently. These tolerances
        # bound numerical drift, without claiming seeded waveform equivalence.
        assert hidden["identical"], hidden
        np.testing.assert_allclose(compact["logits"],full["logits"][ids],rtol=.01,atol=.125)
        # Splitting prefill changes BF16 GEMM/attention shapes. Per-element
        # relative errors around zero are not a useful criterion; bound global
        # drift and distribution change, then independently check spoken text.
        assert prefix["relative_rmse"] < .01, prefix
        assert prefix["max_absolute_error"] <= 1, prefix
        assert prefix["probability_total_variation"] < .1, prefix
        checks.append(dict(index=i,projection=projection,hidden=hidden,prefix=prefix,expanded_kv=kv))
    (args.output/"validation.json").write_text(json.dumps(dict(checks=checks),indent=2))
    print(json.dumps(dict(checks=checks),indent=2),flush=True)


if __name__ == "__main__":
    main()
