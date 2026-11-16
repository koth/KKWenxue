from contextlib import closing
import contextlib
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import numpy as np

from synthesize import INSTRUCTION, connect
from synthesize_cuda import generate_batch, mark_failed, profile_cuda, validated_samples


class CudaWorkerTests(unittest.TestCase):
    def test_custom_style_is_a_separate_profile_and_preserves_original(self):
        with tempfile.TemporaryDirectory() as directory:
            with closing(connect(Path(directory))) as db, db:
                with patch(
                    "synthesize_cuda.importlib.metadata.version", return_value="test"
                ):
                    original_id, original = profile_cuda(db, 8, "sdpa")
                    neutral_id, neutral = profile_cuda(
                        db, 8, "sdpa", instruct="用中性平稳的语气朗读。"
                    )
                    self.assertNotEqual(original_id, neutral_id)
                    self.assertEqual(INSTRUCTION, original["instruct"])
                    self.assertEqual("用中性平稳的语气朗读。", neutral["instruct"])
                    self.assertEqual(original_id, profile_cuda(db, 8, "sdpa")[0])
                    self.assertEqual(
                        neutral_id,
                        profile_cuda(db, 8, "sdpa", instruct=neutral["instruct"])[0],
                    )
                    bright_id, bright = profile_cuda(
                        db,
                        8,
                        "sdpa",
                        speed=1.0,
                        instruct=neutral["instruct"],
                        speaker="Dylan",
                    )
                    self.assertNotIn(bright_id, [original_id, neutral_id])
                    self.assertEqual("Dylan", bright["speaker"])
                    self.assertEqual(1.0, bright["export_speed"])
                    self.assertEqual(
                        3, db.execute("SELECT count(*) FROM profiles").fetchone()[0]
                    )

    def test_batch_unpacks_official_waveforms_and_splits_oom_without_reordering(self):
        class OOM(RuntimeError):
            pass

        calls = []

        class Model:
            def generate_custom_voice(self, **kwargs):
                texts = kwargs["text"]
                calls.append(texts)
                if len(texts) > 1:
                    raise OOM("out of memory")
                return [np.array([int(texts[0])], dtype=np.float32)], 24000

        torch = SimpleNamespace(
            manual_seed=lambda _: None,
            inference_mode=contextlib.nullcontext,
            cuda=SimpleNamespace(
                OutOfMemoryError=OOM,
                empty_cache=lambda: None,
                manual_seed_all=lambda _: None,
                synchronize=lambda: None,
            ),
        )
        rows = [
            dict(chapter_index=848, segment_index=i, spoken_text=str(i))
            for i in range(3)
        ]
        config = dict(
            seed=42,
            language="Chinese",
            speaker="Uncle_Fu",
            instruct="test",
            temperature=0.7,
            top_k=50,
            top_p=1.0,
            repetition_penalty=1.05,
            max_new_tokens=1536,
        )
        outputs = generate_batch(Model(), rows, config, torch)
        self.assertEqual([0, 1, 2], [x.item() for x in outputs])
        self.assertEqual(["0", "1", "2"], calls[0])

    def test_rejects_silence_and_preserves_committed_audio_on_failure(self):
        with self.assertRaisesRegex(ValueError, "Silent"):
            validated_samples(np.zeros(24000), "silent")
        with self.assertRaisesRegex(ValueError, "Invalid"):
            validated_samples(np.array([np.nan]), "invalid")
        with tempfile.TemporaryDirectory() as directory:
            with closing(connect(Path(directory))) as db, db:
                db.execute(
                    "INSERT INTO chapters VALUES ('c','b','Book',848,'第850章','甲。','sha',850)"
                )
                db.execute(
                    "INSERT INTO segments VALUES ('s','c',0,0,0,2,0,2,'甲。','甲。')"
                )
                db.execute("INSERT INTO profiles VALUES ('p','{}')")
                db.execute(
                    "INSERT INTO audio(profile_id,segment_id,path,status) VALUES ('p','s','kept.flac','ready')"
                )
                mark_failed(db, "p", [dict(id="s")], "later batch failed")
                self.assertEqual(
                    ("ready", "kept.flac"),
                    tuple(db.execute("SELECT status,path FROM audio").fetchone()),
                )


if __name__ == "__main__":
    unittest.main()
