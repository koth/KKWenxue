"""Offline codec export and reference encoding. Speech synthesis stays in C++.

Only this short-lived preparation process imports torch; the API and resident
Python queue worker do not. No main autoregressive Python model is loaded.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace

from tts.audio import atomic_bytes
from tts.common import file_digest


def load_codec(model_path, device):
    import torch

    source = Path(model_path)
    spec = importlib.util.spec_from_file_location("audio8_codec", source / "modeling_arktts_codec.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    config = SimpleNamespace(**json.loads((source / "config.json").read_text()))
    torch.set_num_threads(4)
    codec = module.ArkttsCodec(config)
    state = torch.load(source / "codec.pth", map_location="cpu", weights_only=True, mmap=True)
    state = state.get("state_dict", state)
    if any("generator." in key for key in state):
        state = {key.replace("generator.", ""): value for key, value in state.items() if "generator." in key}
    state = {key: value for key, value in state.items() if not key.endswith(("freqs_cis", "causal_mask"))}
    codec.load_state_dict(state, strict=True)
    del state
    return codec.eval().to(device=device, dtype=torch.bfloat16 if device == "cuda" else torch.float32)


def decoder_weight(name):
    return (name.startswith(("decoder.", "quantizer.upsample.", "quantizer.post_module."))
            or (name.startswith(("quantizer.semantic_quantizer.quantizers.", "quantizer.quantizer.quantizers."))
                and (".out_proj." in name or name.endswith(".codebook.weight"))))


def export_decoder(codec, output, validation_codes=None):
    import numpy as np
    import torch
    from safetensors.torch import save_file

    device = next(codec.parameters()).device
    codes = (torch.from_numpy(np.load(validation_codes)).long().unsqueeze(0).to(device)
             if validation_codes else torch.zeros((1, 10, 32), dtype=torch.long, device=device))
    with torch.inference_mode():
        baseline = codec.decode(codes).float()
    weight_error = 0.0
    for name, module in list(codec.named_modules()):
        if not decoder_weight(name + ".weight"):
            continue
        if hasattr(module, "parametrizations") and "weight" in module.parametrizations:
            before = module.weight.detach().clone()
            torch.nn.utils.parametrize.remove_parametrizations(module, "weight", leave_parametrized=True)
            weight_error = max(weight_error, float((before - module.weight.detach()).abs().max()))
        if hasattr(module, "weight_g") and hasattr(module, "weight_v"):
            before = module.weight.detach().clone()
            torch.nn.utils.remove_weight_norm(module)
            weight_error = max(weight_error, float((before - module.weight.detach()).abs().max()))
    with torch.inference_mode():
        converted = codec.decode(codes).float()
    maximum_error = float((baseline - converted).abs().max())
    rmse = float((baseline - converted).square().mean().sqrt())
    signal_rms = float(baseline.square().mean().sqrt())
    # BF16 cuDNN can choose a different convolution accumulation order after
    # removing weight-norm hooks. Exact weights are the conversion invariant;
    # waveform differences are reported separately, including low-energy cases.
    if weight_error != 0 or baseline.shape != converted.shape or not torch.isfinite(converted).all():
        raise ValueError(f"Codec materialization validation failed: weights={weight_error}, rmse={rmse}, signal={signal_rms}")
    state = {key: value.detach().cpu().contiguous() for key, value in codec.state_dict().items()
             if decoder_weight(key)}
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(".pending")
    save_file(state, str(temporary), metadata={"source": "Edge0/Audio8-TTS-Realistic-Air", "format_version": "1"})
    temporary.replace(output)
    print(json.dumps({"decoder": str(output), "tensors": len(state), "bytes": output.stat().st_size,
                      "materialization_weight_max_error": weight_error,
                      "materialization_wave_max_error": maximum_error,
                      "materialization_wave_rmse": rmse, "baseline_rms": signal_rms}), flush=True)


def encode_voice(codec, reference, text, output, device):
    import numpy as np
    import torch

    if not text.strip():
        raise ValueError("Audio8 音色需要与参考音频逐字对应的文本")
    reference, output = Path(reference), Path(output)
    decoded = subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-i", str(reference),
                              "-ac", "1", "-ar", "44100", "-f", "f32le", "-"],
                             capture_output=True, check=True, timeout=30)
    audio = np.frombuffer(decoded.stdout, dtype="<f4").copy()
    if not 44100 <= len(audio) <= 30 * 44100 or not np.isfinite(audio).all():
        raise ValueError("参考音频须为 1–30 秒的有效音频")
    with torch.inference_mode():
        values = torch.from_numpy(audio).reshape(1, 1, -1).to(device=device, dtype=next(codec.parameters()).dtype)
        codes, lengths = codec.encode(values)
        codes = codes[0, :, :int(lengths[0])].long().cpu().numpy()
    if codes.shape[0] != 10 or codes.min() < 0 or codes.max() > 4095:
        raise ValueError("Invalid Audio8 reference codes")
    output.mkdir(parents=True, exist_ok=True)
    temporary = output / "reference_codes.pending.npy"
    np.save(temporary, codes)
    temporary.replace(output / "reference_codes.npy")
    voice = {"codes": "reference_codes.npy", "text": text.strip(),
             "reference_sha256": file_digest(reference), "codes_sha256": file_digest(output / "reference_codes.npy"),
             "sample_rate": 44100, "encoder": "audio8-codec-bf16-v1"}
    atomic_bytes(output / "voice.json", json.dumps(voice, ensure_ascii=False, indent=2).encode())
    print(json.dumps({"voice": str(output / "voice.json"), "frames": codes.shape[1]}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["decoder", "voice"])
    parser.add_argument("--model", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--reference")
    parser.add_argument("--text-file")
    parser.add_argument("--validation-codes")
    parser.add_argument("--device", choices=["cpu", "cuda"], default="cuda")
    args = parser.parse_args()
    codec = load_codec(args.model, args.device)
    if args.operation == "decoder":
        export_decoder(codec, args.output, args.validation_codes)
    else:
        if not args.reference or not args.text_file:
            parser.error("voice requires --reference and --text-file")
        encode_voice(codec, args.reference, Path(args.text_file).read_text(encoding="utf-8"), args.output, args.device)


if __name__ == "__main__":
    main()
