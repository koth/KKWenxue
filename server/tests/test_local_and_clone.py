import io
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest
import wave

from fastapi.testclient import TestClient

from local.library import LocalLibrary
from server.app import create_app
from server.cli import create_demo
from server.config import ENGINE, Settings
from server.voices import DEFAULT_PRESET_ID, DEFAULT_CLONE_ID
from server.tests.fixtures import install_test_voice
from server.db import connect
from server.presets import snapshot
from server.catalog import DomainError


class LocalAndCloneTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "reader.sqlite"
        create_demo(self.source)
        with sqlite3.connect(self.source) as db:
            db.execute(
                "UPDATE chapters SET body=? WHERE id=?",
                ("繁體文字😀。\n\n回家的腳步。", "rain-1"),
            )
        self.library = LocalLibrary(self.source)
        self.settings = Settings(
            state=self.root / "state",
            audio=self.root / "audio",
            min_free_bytes=0,
        )
        install_test_voice(self.settings)
        self.client = TestClient(create_app(self.settings))
        self.client.headers["X-Service-Token"] = self.settings.token_file.read_text()
        self.db = connect(self.settings)
        self.addCleanup(self.db.close)

    def test_local_selection_and_revision_leave_source_unchanged(self):
        before = self.source.read_bytes()
        self.assertEqual(2, self.library.books()[0]["chapters"])
        selected = self.library.resolve("studio-demo", "1")
        self.assertEqual(["rain-1"], selected["chapter_ids"])
        c = selected["chapters"][0]
        revision = self.library.revisions(c["current_revision"])
        for s in revision["segments"]:
            self.assertEqual(
                s["original_text"],
                revision["canonical_text"]
                .encode("utf-16-le")[s["start_utf16"] * 2 : s["end_utf16"] * 2]
                .decode("utf-16-le"),
            )
        self.assertEqual(before, self.source.read_bytes())
        with self.assertRaises(ValueError):
            self.library.resolve("studio-demo", "2-100000000000")

    def test_estimate_does_not_persist_book_and_submit_copies_selected_only(self):
        payload = {
            "book_id": "studio-demo",
            "chapter_ids": ["rain-1"],
            "inline_chapters": self.library.payload("studio-demo", ["rain-1"]),
        }
        # The local payload need not have OpenCC. The server rebuilds spoken text.
        payload["inline_chapters"][0]["segments"][0]["spoken_text"] = "untrusted"
        r = self.client.post("/api/v1/jobs/estimate", json=payload)
        self.assertEqual(200, r.status_code, r.text)
        self.assertEqual(0, self.db.execute("select count(*) from books").fetchone()[0])
        r = self.client.post(
            "/api/v1/jobs", json=payload, headers={"Idempotency-Key": "inline-one"}
        )
        self.assertEqual(202, r.status_code, r.text)
        self.assertEqual(
            ["rain-1"], [r[0] for r in self.db.execute("select id from chapters")]
        )
        spoken = self.db.execute(
            "select spoken_text from segments order by segment_index"
        ).fetchone()[0]
        self.assertIn("繁体文字", spoken)
        self.assertNotIn("untrusted", spoken)
        again = self.client.post(
            "/api/v1/jobs", json=payload, headers={"Idempotency-Key": "inline-one"}
        )
        self.assertEqual(r.json()["id"], again.json()["id"])

    def test_m4a_reference_is_decoded_and_unready_model_rejects_job_snapshot(self):
        self.settings.audio8_model_path = self.root / "no-model"
        wav = self.root / "ref.wav"
        with wave.open(str(wav), "wb") as f:
            f.setnchannels(1)
            f.setsampwidth(2)
            f.setframerate(24000)
            f.writeframes(b"\x00\x10" * 48000)
        m4a = self.root / "ref.m4a"
        subprocess.run(["ffmpeg", "-v", "error", "-i", str(wav), str(m4a)], check=True)
        upload = lambda data: self.client.post(
            "/api/v1/voice-clones",
            files={"reference": ("ref.m4a", io.BytesIO(m4a.read_bytes()), "audio/mp4")},
            data=data,
        )
        r = upload({})
        self.assertEqual(422, r.status_code, r.text)
        r = upload({"ref_text": "繁体文字，回家的脚步。"})
        self.assertEqual(201, r.status_code, r.text)
        self.assertFalse(r.json()["model_ready"])
        row = self.db.execute("select * from voice_clone_refs where id=?", (r.json()["reference_id"],)).fetchone()
        with wave.open(str(self.settings.audio / row["path"])) as decoded:
            self.assertEqual(24000, decoded.getframerate())
            self.assertEqual(1, decoded.getnchannels())
        with self.assertRaises(DomainError) as e:
            snapshot(self.db, self.settings, r.json()["id"], None)
        self.assertEqual(503, e.exception.status)
        self.assertEqual(0, self.db.execute("select count(*) from jobs").fetchone()[0])
        no_transcript = upload({"x_vector_only_mode": "true"})
        self.assertEqual(422, no_transcript.status_code, no_transcript.text)


if __name__ == "__main__":
    unittest.main()
