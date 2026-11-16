"""Single-request CUDA graph decoding for the resident CosyVoice3 worker.

Only the one-token transformer step is captured. Prompt processing, speech
sampling, flow and vocoder stay on their original paths. The cache is reset at
each segment and falls back to a dynamic cache when its bound is reached.
"""
import logging
import torch
from transformers.cache_utils import DynamicCache, StaticCache


class GraphDecoder:
    def __init__(self, encoder, max_length=2048):
        self.encoder = encoder
        self.decoder = encoder.model.model
        self.maximum = max_length
        self.graph = None
        self.offset = 0
        self.disabled = False
        self.fallbacks = 0

    def initialize(self, xs):
        self.cache = StaticCache(self.decoder.config, 1, self.maximum,
                                 device=xs.device, dtype=xs.dtype)
        self.input = torch.zeros_like(xs[:, :1])
        self.position = torch.zeros((1,), device=xs.device, dtype=torch.long)
        self.mask = torch.full((1, 1, 1, self.maximum), torch.finfo(xs.dtype).min,
                               device=xs.device, dtype=xs.dtype)
        self.mask[..., 0] = 0
        # Capture must use a non-default stream. Keep the adapter usable both
        # in CosyVoice's LLM thread and in standalone inference checks.
        current = torch.cuda.current_stream()
        capture_stream = torch.cuda.Stream(device=xs.device)
        capture_stream.wait_stream(current)
        with torch.cuda.stream(capture_stream):
            for _ in range(3):
                self.decode()
            capture_stream.synchronize()
            self.graph = torch.cuda.CUDAGraph()
            with torch.cuda.graph(self.graph, stream=capture_stream):
                self.output = self.decode()
        current.wait_stream(capture_stream)
        self.cache.reset()
        logging.info("Captured CosyVoice one-token CUDA graph (KV capacity %d)", self.maximum)

    def decode(self):
        return self.decoder(inputs_embeds=self.input, attention_mask=self.mask,
                            position_ids=self.position.unsqueeze(0),
                            cache_position=self.position, past_key_values=self.cache,
                            use_cache=True, output_hidden_states=False, return_dict=True).last_hidden_state

    def __call__(self, xs, masks, cache=None):
        if self.disabled:
            return self.eager(xs, masks, cache)
        if cache is not None and self.graph is None:
            return self.eager(xs, masks, cache)
        if cache is None and (xs.shape[0] != 1 or xs.shape[1] >= self.maximum
                              or not masks[:, -1, :].all().item()):
            return self.eager(xs, masks)
        if self.graph is None:
            try:
                self.initialize(xs)
            except RuntimeError:
                # No request cache has been filled yet. A failed capture can
                # therefore use the original decoder without losing a segment.
                logging.exception("CUDA graph capture unavailable; using eager decoder")
                self.disabled = True
                self.graph = None
                self.cache = None
                torch.cuda.empty_cache()
                return self.eager(xs, masks, cache)
        if cache is None:
            self.cache.reset()
            self.offset = xs.shape[1]
            if self.offset >= self.maximum:
                return self.eager(xs, masks)
            positions = torch.arange(self.offset, device=xs.device)
            outputs = self.decoder(inputs_embeds=xs, attention_mask=masks[:, -1, :],
                                   cache_position=positions, position_ids=positions.unsqueeze(0),
                                   past_key_values=self.cache, use_cache=True,
                                   output_hidden_states=False, return_dict=True)
            self.mask.fill_(torch.finfo(xs.dtype).min)
            self.mask[..., :self.offset] = 0
            return outputs.last_hidden_state, self.cache
        if cache is self.cache and xs.shape[1] == 1 and self.offset < self.maximum:
            self.input.copy_(xs)
            self.position.fill_(self.offset)
            self.mask[..., self.offset] = 0
            self.graph.replay()
            self.offset += 1
            return self.output, self.cache
        if cache is self.cache:
            self.fallbacks += 1
            dynamic = DynamicCache()
            for i, (key, value) in enumerate(zip(cache.key_cache, cache.value_cache)):
                dynamic.update(key[:, :, :self.offset].clone(), value[:, :, :self.offset].clone(), i)
            cache = dynamic
        return self.eager(xs, masks, cache)

    def eager(self, xs, masks, cache=None):
        outputs = self.decoder(inputs_embeds=xs, attention_mask=masks[:, -1, :],
                               past_key_values=cache, use_cache=True,
                               output_hidden_states=False, return_dict=True)
        return outputs.last_hidden_state, outputs.past_key_values


def enable_cuda_graph(model, max_length=2048):
    encoder = model.model.llm.llm
    if (encoder.__class__.__name__ != "Qwen2Encoder"
            or encoder.model.config.model_type != "qwen2"
            or encoder.model.config._attn_implementation != "sdpa"):
        raise RuntimeError("Unsupported CosyVoice decoder layout for CUDA graph")
    decoder = GraphDecoder(encoder, max_length=max_length)
    encoder.forward_one_step = decoder
    return decoder
