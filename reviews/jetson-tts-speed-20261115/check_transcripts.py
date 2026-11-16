"""Independent CPU ASR check of baseline and accelerated speech."""
from pathlib import Path
import json
import re
import torch
import whisper
from opencc import OpenCC

root = Path(__file__).resolve().parent
torch.set_num_threads(2)
model = whisper.load_model("tiny", device="cpu")
canonical = OpenCC("t2s")
def normalize(text):
    return re.sub(r"[^\u4e00-\u9fff0-9a-z]", "", canonical.convert(text).lower())
def distance(a, b):
    row = list(range(len(b) + 1))
    for i, left in enumerate(a, 1):
        current = [i]
        for j, right in enumerate(b, 1):
            current.append(min(current[-1] + 1, row[j] + 1, row[j-1] + (left != right)))
        row = current
    return row[-1]
expected = {i["index"]: i["text"] for i in json.loads((root / "graph-benchmark.json").read_text())}
results = []
for filename, index in [("baseline-0.wav", 0), ("cuda-graph-0.wav", 0),
                        ("baseline-1.wav", 1), ("cuda-graph-1.wav", 1),
                        ("cuda-graph-long-2.wav", 2)]:
    result = model.transcribe(str(root / filename), language="zh", fp16=False,
                              temperature=0, beam_size=5, condition_on_previous_text=False)
    reference, actual = normalize(expected[index]), normalize(result["text"])
    item = dict(file=filename, expected=expected[index], transcript=result["text"],
                character_error_rate=distance(reference, actual) / len(reference))
    results.append(item)
    (root / "asr-check.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
    print(json.dumps(item, ensure_ascii=False), flush=True)
