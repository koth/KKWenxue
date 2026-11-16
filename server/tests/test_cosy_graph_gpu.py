"""Numerical and cache-boundary checks, explicitly run on the GPU host."""
import os
from types import SimpleNamespace
import unittest
from unittest.mock import patch


@unittest.skipUnless(os.environ.get("SHANYE_GPU_RUNTIME_TEST") == "1", "requires explicit GPU runtime test")
class DecoderGraphGpuTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import torch
        from transformers import Qwen2Config, Qwen2ForCausalLM
        from server.cosy_graph import GraphDecoder

        cls.torch, cls.GraphDecoder = torch, GraphDecoder
        torch.manual_seed(17)
        config = Qwen2Config(vocab_size=64, hidden_size=64, intermediate_size=128,
                            num_hidden_layers=2, num_attention_heads=4,
                            num_key_value_heads=2, max_position_embeddings=256,
                            use_sliding_window=False, attention_dropout=0.)
        model = Qwen2ForCausalLM(config).cuda().half().eval()
        cls.encoder = SimpleNamespace(model=model)

    def compare_sequence(self, graph, prompt_length, steps):
        from server.cosy_runtime import _decoder_forward_one_step

        torch = self.torch
        cache, reference_cache, offset = None, None, 0
        with torch.inference_mode(), torch.autocast("cuda", dtype=torch.float16):
            for length in [prompt_length] + [1] * steps:
                xs = torch.randn(1, length, 64, device="cuda", dtype=torch.float16)
                offset += length
                masks = torch.ones(1, length, offset, device="cuda", dtype=torch.bool)
                actual, cache = graph(xs, masks, cache)
                expected, reference_cache = _decoder_forward_one_step(
                    self.encoder, xs, masks, reference_cache)
                torch.testing.assert_close(actual, expected, atol=.002, rtol=.02)

    def test_replay_cache_reset_and_capacity_fallback_match_eager(self):
        graph = self.GraphDecoder(self.encoder, max_length=8)
        self.compare_sequence(graph, 3, 10)
        self.assertEqual(1, graph.fallbacks)
        # A second prompt must not inherit any cached state from the first.
        self.compare_sequence(graph, 2, 9)
        self.assertEqual(2, graph.fallbacks)
        self.assertFalse(graph.disabled)

    def test_oversized_prompt_uses_unbounded_eager_path(self):
        graph = self.GraphDecoder(self.encoder, max_length=8)
        self.compare_sequence(graph, 10, 3)
        self.assertIsNone(graph.graph)

    def test_capture_failure_falls_back_before_filling_request_cache(self):
        graph = self.GraphDecoder(self.encoder, max_length=8)
        with patch.object(graph, "initialize", side_effect=RuntimeError("capture unavailable")):
            with self.assertLogs(level="ERROR"):
                self.compare_sequence(graph, 3, 2)
        self.assertTrue(graph.disabled)
