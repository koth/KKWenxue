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

    def test_only_preencoded_published_voices_can_be_selected(self):
        before=self.db.execute('SELECT count(*) FROM voice_clone_refs').fetchone()[0]
        response=self.client.post('/api/v1/voice-clones',
            files={'reference':('voice.m4a',io.BytesIO(b'unprepared audio'),'audio/mp4')})
        self.assertEqual(503,response.status_code)
        self.assertEqual(before,self.db.execute('SELECT count(*) FROM voice_clone_refs').fetchone()[0])
        caps=self.client.get('/api/v1/voices').json()
        self.assertFalse(caps['reference_upload'])
        self.settings.zonos_root=self.root/'not-installed'
        from server.voices import DEFAULT_PRESET_ID
        with self.assertRaises(DomainError) as error:
            snapshot(self.db,self.settings,DEFAULT_PRESET_ID,None)
        self.assertEqual(503,error.exception.status)
        self.assertEqual(0,self.db.execute('SELECT count(*) FROM jobs').fetchone()[0])


if __name__ == "__main__":
    unittest.main()
