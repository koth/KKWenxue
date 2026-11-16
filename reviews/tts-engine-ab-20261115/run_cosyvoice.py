"""Isolated CosyVoice 3 audition; does not modify the production TTS service."""
import argparse
import hashlib
import json
import logging
from pathlib import Path
import sys
import time

parser = argparse.ArgumentParser()
parser.add_argument("--repo", default="/h/work/CosyVoice")
parser.add_argument("--model", default="/h/work/CosyVoice/pretrained_models/Fun-CosyVoice3-0.5B")
parser.add_argument("--input", type=Path, default=Path(__file__).with_name("input.json"))
parser.add_argument("--output", type=Path, default=Path(__file__).parent)
parser.add_argument("--mode", choices=["clone", "neutral", "both"], default="both")
parser.add_argument("--builtin-normalizer", action="store_true", help="Use built-in splitting for already canonical Chinese text.")
args = parser.parse_args()
sys.path[:0] = [args.repo, str(Path(args.repo) / "third_party/Matcha-TTS")]

import numpy as np
import soundfile as sf
import torch
from cosyvoice.cli.cosyvoice import AutoModel

logging.getLogger().setLevel(logging.INFO)
# This test has canonical Chinese text with no numbers or abbreviations.
# The built-in path keeps natural sentence grouping without fetching all
# optional multilingual WeText resources during the first audition.
if args.builtin_normalizer:
    sys.modules["wetext"] = None
case = json.loads(args.input.read_text())
reference = Path("/h/work/shanye-tts-service/audio") / case["reference"]
assert hashlib.sha256(reference.read_bytes()).hexdigest() == case["reference_sha256"]
assert torch.cuda.is_available(), "The audition requires the GPU."
args.output.mkdir(parents=True, exist_ok=True)
started = time.perf_counter()
model = AutoModel(model_dir=args.model, fp16=True)
torch.cuda.synchronize()
loaded_seconds = time.perf_counter() - started
stats = {
    "model": "FunAudioLLM/Fun-CosyVoice3-0.5B-2512",
    "gpu": torch.cuda.get_device_name(),
    "torch": torch.__version__,
    "load_seconds": loaded_seconds,
    "text_chars": len(case["text"]),
    "speed": 1.0,
    "fp16": True,
    "normalizer": "builtin" if args.builtin_normalizer else "default",
    "reference_sha256": case["reference_sha256"],
    "results": [],
}
prompt = "You are a helpful assistant.<|endofprompt|>" + case["ref_text"]
instruction = "You are a helpful assistant. 用标准普通话，以平静自然的语气讲述故事，语速适中。<|endofprompt|>"
for mode in (["clone", "neutral"] if args.mode == "both" else [args.mode]):
    torch.manual_seed(1986)
    torch.cuda.manual_seed_all(1986)
    started = time.perf_counter()
    if mode == "clone":
        model.add_zero_shot_spk(prompt, str(reference), "audition-stf")
        stream = model.inference_zero_shot(
            case["text"], "", "", zero_shot_spk_id="audition-stf",
            stream=False, speed=1.0,
        )
    else:
        stream = model.inference_instruct2(
            case["text"], instruction, str(reference), stream=False, speed=1.0,
        )
    chunks = []
    chunk_lengths = []
    for item in stream:
        speech = item["tts_speech"].detach().float().cpu().numpy().reshape(-1)
        assert len(speech) and np.isfinite(speech).all(), "Invalid synthesis output"
        chunks.append(speech)
        chunk_lengths.append(len(speech) / model.sample_rate)
    torch.cuda.synchronize()
    elapsed = time.perf_counter() - started
    if not chunks:
        raise RuntimeError("No speech was generated")
    speech = np.concatenate(chunks)
    path = args.output / f"cosyvoice3-0.5b-{mode}-raw.wav"
    sf.write(path, speech, model.sample_rate, subtype="PCM_16")
    duration = len(speech) / model.sample_rate
    result = {
        "mode": mode,
        "path": str(path),
        "generation_seconds": elapsed,
        "audio_seconds": duration,
        "real_time_factor": elapsed / duration,
        "chunks_seconds": chunk_lengths,
        "sample_rate": model.sample_rate,
        "peak": float(np.max(np.abs(speech))),
        "rms": float(np.sqrt(np.mean(speech ** 2))),
        "gpu_peak_allocated_mib": torch.cuda.max_memory_allocated() / 2**20,
        "instruction": instruction if mode == "neutral" else "reference clone",
    }
    stats["results"].append(result)
    (args.output / "metrics.json").write_text(json.dumps(stats, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False), flush=True)
