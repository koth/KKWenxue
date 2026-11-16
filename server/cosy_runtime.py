"""Bounded-memory loading and decoder adaptation for CosyVoice3."""
from __future__ import annotations

import gc
from pathlib import Path
from types import MethodType


def _decoder_forward_one_step(self, xs, masks, cache=None):
    """CosyVoice consumes decoder states, not the text vocabulary logits."""
    outputs = self.model.model(
        inputs_embeds=xs,
        attention_mask=masks[:, -1, :],
        output_hidden_states=False,
        return_dict=True,
        use_cache=True,
        past_key_values=cache,
    )
    return outputs.last_hidden_state, outputs.past_key_values


def enable_decoder_only(model):
    """Avoid the unused 151k-vocabulary projection in upstream Qwen2Encoder.

    The speech-token projection in CosyVoice remains unchanged. Restrict the
    adapter to the known encoder layout and leave the external checkout intact.
    """
    encoder = model.model.llm.llm
    if (encoder.__class__.__name__ != "Qwen2Encoder"
            or getattr(encoder.model.config, "model_type", None) != "qwen2"):
        raise RuntimeError("Unsupported CosyVoice decoder layout for decoder-only inference")
    encoder.forward_one_step = MethodType(_decoder_forward_one_step, encoder)


class _ReferenceTokenizer:
    """Release the large CPU encoder after extracting the cached voice."""
    def __init__(self, constructor, path, args, kwargs):
        self.constructor, self.path = constructor, path
        self.args, self.kwargs, self.session = args, kwargs, None

    def _get(self):
        if self.session is None:
            self.session = self.constructor(self.path, *self.args, **self.kwargs)
        return self.session

    def get_inputs(self):
        return self._get().get_inputs()

    def run(self, *args, **kwargs):
        try:
            return self._get().run(*args, **kwargs)
        finally:
            self.session = None
            gc.collect()


def _load_weights(model, torch, llm_path, flow_path, hift_path):
    # Upstream loads each checkpoint straight onto CUDA while its initialized
    # CPU module still exists. On Jetson both copies compete for the same RAM.
    # File-backed CPU tensors can be reclaimed; assign avoids another copy.
    for name, path in (("llm", llm_path), ("flow", flow_path), ("hift", hift_path)):
        weights = torch.load(path, map_location="cpu", weights_only=True, mmap=True)
        if name == "hift":
            weights = {k.replace("generator.", ""): v for k, v in weights.items()}
        module = getattr(model, name)
        module.load_state_dict(weights, strict=True, assign=True)
        del weights
        # Keep the vocoder's float32 weights, as in upstream. The language and
        # flow models run under the existing fp16 autocast inference contexts.
        if model.fp16 and name != "hift":
            module.half()
        module.to(model.device).eval()
        gc.collect()
        torch.cuda.empty_cache()


def load_cosyvoice(factory, torch, *, model_dir, low_memory=False):
    if not low_memory:
        return factory(model_dir=model_dir, fp16=True)

    from cosyvoice.cli.model import CosyVoice3Model
    import onnxruntime

    # Limit the override to construction of this model. No upstream source
    # files or global tensor defaults are modified. One GPU worker owns model
    # initialization, before it starts any inference threads.
    inherited = "load" not in CosyVoice3Model.__dict__
    original = CosyVoice3Model.load
    original_session = onnxruntime.InferenceSession
    def load(model, llm_path, flow_path, hift_path):
        return _load_weights(model, torch, llm_path, flow_path, hift_path)
    def session(path, *args, **kwargs):
        if Path(path).name == "speech_tokenizer_v3.onnx":
            # Reference extraction runs once per voice/instruction. Its CUDA
            # scratch allocations can exceed Orin's entire shared RAM even
            # though steady speech generation fits. CPU buffers are pageable.
            kwargs["providers"] = ["CPUExecutionProvider"]
            options = kwargs.get("sess_options")
            if options is not None:
                options.enable_cpu_mem_arena = False
                options.enable_mem_pattern = False
            return _ReferenceTokenizer(original_session, path, args, kwargs)
        return original_session(path, *args, **kwargs)
    CosyVoice3Model.load = load
    onnxruntime.InferenceSession = session
    try:
        return factory(model_dir=model_dir, fp16=True)
    finally:
        onnxruntime.InferenceSession = original_session
        if inherited:
            del CosyVoice3Model.load
        else:
            CosyVoice3Model.load = original
