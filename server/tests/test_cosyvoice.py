"""Engine boundary tests; GPU inference is checked separately on the server."""
from contextlib import closing, nullcontext
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import Mock

import numpy as np

from server.config import Settings
from server.db import connect
from server.presets import snapshot
from server.tests.fixtures import install_test_voice
from server.voices import DEFAULT_PRESET_ID
from server.worker import CudaEngine


class CosyVoiceTests(unittest.TestCase):
    def test_retired_engine_is_rejected_before_loading_gpu_dependencies(self):
        engine = CudaEngine(Settings(), {})
        with self.assertRaisesRegex(RuntimeError, "旧 Qwen"):
            engine.generate([], {"engine": "qwen3-tts-cuda"})
        self.assertIsNone(engine.model)

    def test_cached_voice_uses_style_prompt_and_refreshes_on_instruction_change(self):
        with tempfile.TemporaryDirectory() as root:
            settings = Settings(state=Path(root)/"state", audio=Path(root)/"audio")
            install_test_voice(settings)
            with closing(connect(settings)) as db:
                config = snapshot(db, settings, DEFAULT_PRESET_ID, None)["generation"]
            config["instruct"] = "平静地讲述。<|endofprompt|>"
            speech = Mock()
            speech.detach.return_value.float.return_value.cpu.return_value.numpy.return_value = np.ones((1, 6000)) * .1
            model = Mock()
            model.inference_instruct2.side_effect = lambda *_a, **_kw: iter([{"tts_speech": speech}])
            torch = SimpleNamespace(manual_seed=Mock(), inference_mode=nullcontext,
                                    cuda=SimpleNamespace(manual_seed_all=Mock(), synchronize=Mock(), empty_cache=Mock()))
            engine = CudaEngine(settings, {})
            engine.model = model
            rows = [{"id": "s1", "chapter_index": 0, "segment_index": 0, "spoken_text": "这是测试。"}]
            for _ in range(2):
                result = engine._batch(rows, config, torch)
                self.assertEqual((6000,), result[0].shape)
            self.assertEqual(1, model.add_zero_shot_spk.call_count)
            self.assertEqual(config["instruct"], model.add_zero_shot_spk.call_args.args[0])
            self.assertNotEqual(config["ref_text"], model.add_zero_shot_spk.call_args.args[0])
            engine._batch(rows, {**config, "instruct": "新的语气<|endofprompt|>"}, torch)
            self.assertEqual(2, model.add_zero_shot_spk.call_count)
