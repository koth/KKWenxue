"""Matched real-GPU tests; never changes or submits synthesis jobs."""
from contextlib import closing
from pathlib import Path
import functools
import json
import sys
import time

import numpy as np
import soundfile as sf
import torch

from server.config import Settings
from server.db import connect
from server.worker import CudaEngine
from tts.common import GPULock
from server.cosy_runtime import enable_decoder_only

root = Path(__file__).resolve().parent
report_path = root / "benchmark.json"
settings = Settings(batch_size=1, cosyvoice_decoder_only=False, cosyvoice_cuda_graph=False)
with closing(connect(settings)) as db:
    config = json.loads(db.execute(
        "SELECT config_json FROM jobs WHERE id=?",
        ("f79270933946451f97ba766c9b58e27b",),
    ).fetchone()[0])["generation"]

texts = [
    "窗外传来一阵脚步声。他停下手里的动作，仔细听了一会儿。",
    "夜色渐深，街道安静下来。他推开门，看见桌上放着一封信，信封上没有写名字，只有一个熟悉的地址。",
]
engine = CudaEngine(settings, {})
results, timings, outputs = [], {}, {}

def profile(owner, method, name):
    original = getattr(owner, method)
    @functools.wraps(original)
    def call(*args, **kwargs):
        torch.cuda.synchronize()
        start = time.perf_counter()
        value = original(*args, **kwargs)
        torch.cuda.synchronize()
        timings[name] = timings.get(name, 0) + time.perf_counter() - start
        return value
    setattr(owner, method, call)

def generate(mode, index, text):
    timings.clear()
    start = time.perf_counter()
    value = engine.generate([{
        "id": "speed-check", "chapter_index": 0,
        "segment_index": index, "spoken_text": text,
    }], config)[0]
    if isinstance(value, BaseException):
        raise value
    elapsed = time.perf_counter() - start
    duration = len(value) / 24000
    sf.write(root / f"{mode}-{index}.wav", value, 24000, subtype="FLOAT")
    item = dict(mode=mode, index=index, text=text, wall_seconds=elapsed,
                audio_seconds=duration, rtf=elapsed / duration, stages=dict(timings),
                threads=torch.get_num_threads(),
                cuda_peak_allocated=torch.cuda.max_memory_allocated(),
                cuda_peak_reserved=torch.cuda.max_memory_reserved())
    if index in outputs:
        other = outputs[index]
        item["same_samples"] = bool(np.array_equal(value, other))
        if len(value) == len(other):
            item["max_sample_error"] = float(np.abs(value - other).max())
    else:
        outputs[index] = value.copy()
    results.append(item)
    report_path.write_text(json.dumps(results, ensure_ascii=False, indent=2))
    print(json.dumps(item, ensure_ascii=False), flush=True)

with GPULock():
    generate("warmup", 99, "夜色渐深，街道安静下来。")
    internal = engine.model.model
    profile(internal, "llm_job", "llm")
    profile(internal.flow, "inference", "flow")
    profile(internal.hift, "inference", "vocoder")
    print(json.dumps({"attention": internal.llm.llm.model.config._attn_implementation}), flush=True)
    for i, text in enumerate(texts):
        generate("baseline", i, text)
    enable_decoder_only(engine.model)
    for i, text in enumerate(texts):
        generate("decoder-only", i, text)
    torch.set_num_threads(1)
    for i, text in enumerate(texts):
        generate("decoder-only-1thread", i, text)
