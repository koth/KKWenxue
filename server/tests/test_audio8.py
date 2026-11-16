"""Exercise the native process protocol without requiring CUDA or model weights."""
from contextlib import closing
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

from server.audio8_runtime import Audio8Engine, minimum_audio_seconds
from server.config import ENGINE, REVISION, Settings
from server.db import connect
from server.presets import snapshot
from server.tests.fixtures import install_test_voice
from server.voices import DEFAULT_PRESET_ID
from server.worker import Worker
from tts.common import digest, file_digest


FAKE_NATIVE = '''#!{python}
import json, math, pathlib, sys, time, wave, struct
print(json.dumps({{"stage":"ready", "engine":{{"gpu":"test CUDA", "sample_rate":44100}}}}), flush=True)
for line in sys.stdin:
    req=json.loads(line)
    assert req["split_sentences"] is False
    if req["text"] == "error":
        print(json.dumps({{"stage":"error", "error":"invalid request"}}), flush=True)
        continue
    if req["text"] == "hang":
        time.sleep(10)
    directory=pathlib.Path(req["output_dir"])
    with wave.open(str(directory/"audio8.wav"), "wb") as output:
        output.setnchannels(1); output.setsampwidth(2); output.setframerate(44100)
        output.writeframes(b"".join(struct.pack("<h", int(3000*math.sin(i/10))) for i in range(22050)))
    report={{"state":"completed", "generation_seconds":.2, "decode_and_save_seconds":.1, "audio_seconds":.5}}
    (directory/"report.json").write_text(json.dumps(report))
    print(json.dumps({{"stage":"completed"}}), flush=True)
'''


class Audio8Tests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.settings = Settings(state=root/"state", audio=root/"audio")
        install_test_voice(self.settings)
        self.settings.audio8_binary.write_text(FAKE_NATIVE.format(python=sys.executable))
        with closing(connect(self.settings)) as db:
            self.config = snapshot(db, self.settings, DEFAULT_PRESET_ID, None)["generation"]
        key = digest(json.dumps([REVISION, self.config["ref_audio_sha256"], self.config["ref_text"]], ensure_ascii=False))
        voice_dir = self.settings.audio / "audio8-voices" / key
        voice_dir.mkdir(parents=True)
        np.save(voice_dir / "reference_codes.npy", np.zeros((10, 20), dtype="int64"))
        (voice_dir / "voice.json").write_text(json.dumps({
            "codes": "reference_codes.npy", "text": self.config["ref_text"],
            "reference_sha256": self.config["ref_audio_sha256"],
            "codes_sha256": file_digest(voice_dir / "reference_codes.npy"),
        }))
        self.engine = Audio8Engine(self.settings, {})
        self.addCleanup(self.engine.close)

    def row(self, text="测试原文。"):
        return {"id":"segment1", "chapter_index":2, "segment_index":3, "spoken_text":text}

    def test_native_process_is_reused_and_wav_is_resampled(self):
        self.assertIsInstance(Worker(self.settings).engine, Audio8Engine)
        result = self.engine.generate([self.row()], self.config)
        self.assertEqual((12000,), result[0].shape)
        self.assertTrue(np.isfinite(result[0]).all())
        pid = self.engine.process.pid
        result = self.engine.generate([self.row()], self.config)
        self.assertEqual(pid, self.engine.process.pid)
        self.assertEqual(200045, self.engine.last_execution["segment1"]["seed"])
        self.assertEqual([], list((self.settings.audio / ".audio8-work").iterdir()))

    def test_errors_and_timeouts_close_process_for_retry(self):
        result = self.engine.generate([self.row("error")], self.config)
        self.assertIsInstance(result[0], RuntimeError)
        self.assertIsNone(self.engine.process)
        self.settings.audio8_timeout = 1
        result = self.engine.generate([self.row("hang")], self.config)
        self.assertIsInstance(result[0], TimeoutError)
        self.assertIsNone(self.engine.process)
        self.assertEqual((12000,), self.engine.generate([self.row()], self.config)[0].shape)

    def test_cached_voice_avoids_hashing_and_still_detects_changed_files(self):
        with patch("server.audio8_runtime.file_digest", wraps=file_digest) as hashed:
            voice = self.engine._voice(self.config)
            self.assertEqual(2, hashed.call_count)
            self.assertEqual(voice, self.engine._voice(self.config))
            self.assertEqual(2, hashed.call_count)
            # A changed manifest must be read and validated again.
            voice.write_text(voice.read_text()+"\n")
            self.engine._voice(self.config)
            self.assertEqual(4, hashed.call_count)
            reference = self.settings.audio / self.config["ref_audio_path"]
            reference.write_bytes(b"changed recording")
            with self.assertRaisesRegex(RuntimeError, "校验"):
                self.engine._voice(self.config)

    def test_changed_codes_invalidate_voice_and_require_reencoding(self):
        voice = self.engine._voice(self.config)
        (voice.parent / "reference_codes.npy").write_bytes(b"invalid codes")
        with patch("server.audio8_runtime.subprocess.run", side_effect=RuntimeError("encoder invoked")) as encoder:
            with self.assertRaisesRegex(RuntimeError, "encoder invoked"):
                self.engine._voice(self.config)
            self.assertEqual(1, encoder.call_count)

    def test_premature_eos_is_not_accepted_as_a_complete_long_passage(self):
        text = "雨水落在泥泞的山路上，他咬着牙继续向前跑，身后的脚步声始终没有停下，令他心惊不已。"
        result = self.engine.generate([self.row(text)], self.config)
        self.assertIsInstance(result[0], RuntimeError)
        self.assertIn("提前结束", str(result[0]))
        self.assertEqual(0, minimum_audio_seconds("你好。"))
        self.assertEqual(0, minimum_audio_seconds("A long English passage should not count each letter as a syllable."))

    def test_legacy_config_missing_transcript_and_changed_audio_are_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "旧合成引擎"):
            self.engine.generate([], {"engine":"cosyvoice3-0.5b-cuda"})
        with self.assertRaisesRegex(RuntimeError, "参考文本"):
            self.engine.generate([], {**self.config, "ref_text":""})
        with self.assertRaisesRegex(RuntimeError, "校验"):
            self.engine.generate([], {**self.config, "ref_audio_sha256":"changed"})
        self.assertIsNone(self.engine.process)
        self.assertEqual(ENGINE, self.config["engine"])
        self.assertNotIn("instruct", self.config)


if __name__ == "__main__":
    unittest.main()
