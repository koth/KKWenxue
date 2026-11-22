import json
import os
from pathlib import Path
import sqlite3
import subprocess
import time
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from fastapi.testclient import TestClient
import numpy as np

from server.app import create_app
from server.artifacts import available, create_bundle
from server.catalog import DomainError, import_sqlite, resolve
from server.cli import create_demo, import_artifact, audit
from server.config import ENGINE, Settings
from server.voices import DEFAULT_PRESET_ID, DEFAULT_CLONE_ID, HAORAN_PRESET_ID
from server.tests.fixtures import install_test_voice
from server.db import initialize, connect, backup
from server.jobs import submit, detail, action, claim, release, recover, assert_owner
from server.presets import create_preset, snapshot
from server.worker import Worker, recovery_split_position
from server.zonos_runtime import LEGACY_SPEECH_PLAN, SPEECH_PLAN, generation_seed
from tts.common import GPULock, has_spoken_content, utf16_len


class TestEngine:
    __test__ = False

    def __init__(self):
        self.calls = []
        self.invalid = set()
        self.callback = None

    def generate(self, rows, _config):
        self.calls.append([r["id"] for r in rows])
        if self.callback:
            self.callback()
        return [
            np.zeros(24000)
            if r["id"] in self.invalid
            else 0.1 * np.sin(np.arange(max(6000, len(r["spoken_text"]) * 300)) * 0.08)
            for r in rows
        ]


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.settings = Settings(
            state=self.root / "state",
            audio=self.root / "audio",
            min_free_bytes=0,
            batch_size=8,
        )
        install_test_voice(self.settings)
        self.db = connect(self.settings)
        self.addCleanup(self.db.close)
        self.source = self.root / "demo.sqlite"
        create_demo(self.source)
        self.book = import_sqlite(self.settings, self.source, "studio-demo", "test")[
            "book_id"
        ]
        self.chapters = [
            dict(r)
            for r in self.db.execute("SELECT * FROM chapters ORDER BY chapter_index")
        ]
        self.engine = TestEngine()
        self.gpu = Worker(self.settings, engine=self.engine)
        self.cpu = Worker(self.settings, role="cpu")

    def request(self, ids=None, **kw):
        return dict(
            book_id=self.book,
            chapter_ids=ids or [c["id"] for c in self.chapters],
            voice_preset_version_id=DEFAULT_PRESET_ID,
            **kw,
        )

    def finish(self):
        for _ in range(40):
            progress = self.gpu.tick()
            progress = self.cpu.tick() or progress
            if not progress:
                return
        self.fail("Queue did not finish within bounded ticks")

    def successful(self):
        job = submit(self.db, self.settings, self.request(), "first")
        self.finish()
        result = detail(self.db, job)
        self.assertEqual("succeeded", result["state"], result)
        return result

    def test_empty_boot_has_no_content_or_tasks_and_no_torch(self):
        other = Settings(state=self.root / "empty", audio=self.root / "empty-audio")
        client = TestClient(create_app(other))
        client.headers["X-Service-Token"] = other.token_file.read_text()
        self.assertEqual([], client.get("/api/v1/books").json()["items"])
        self.assertEqual([], client.get("/api/v1/jobs").json()["items"])
        self.assertEqual("ready", client.get("/api/v1/health").json()["api"])
        self.assertNotIn("torch", __import__("sys").modules)

    def test_idempotent_import_and_revision_retention(self):
        old = self.chapters[0]["current_revision"]
        import_sqlite(self.settings, self.source, "studio-demo", "test")
        self.assertEqual(
            2, self.db.execute("SELECT count(*) FROM chapters").fetchone()[0]
        )
        with sqlite3.connect(self.source) as src:
            src.execute(
                "UPDATE chapters SET body='繁體😀。\\n\\n新的文字！' WHERE id='rain-1'"
            )
        import_sqlite(self.settings, self.source, "studio-demo", "test")
        new = self.db.execute(
            "SELECT current_revision FROM chapters WHERE id=?",
            (self.chapters[0]["id"],),
        ).fetchone()[0]
        self.assertNotEqual(old, new)
        self.assertIsNotNone(
            self.db.execute("SELECT * FROM revisions WHERE id=?", (old,)).fetchone()
        )
        for s in self.db.execute(
            "SELECT s.*,r.canonical_text FROM segments s JOIN revisions r ON r.id=s.revision_id WHERE s.revision_id=?",
            (new,),
        ):
            text = (
                s["canonical_text"]
                .encode("utf-16-le")[s["start_utf16"] * 2 : s["end_utf16"] * 2]
                .decode("utf-16-le")
            )
            self.assertEqual(text, s["original_text"])

    def test_duplicate_numbers_cross_book_and_selection_errors(self):
        with sqlite3.connect(self.source) as src:
            src.execute(
                "INSERT INTO chapters VALUES ('extra','studio-demo',2,'第2章 另一个灯下','另一段故事。')"
            )
        import_sqlite(self.settings, self.source, "studio-demo", "test")
        ambiguous = resolve(self.db, self.book, "2")
        self.assertEqual(2, len(ambiguous["ambiguous"][0]["candidates"]))
        self.assertEqual(3, len(resolve(self.db, self.book, "1-2")["chapter_ids"]))
        other = import_sqlite(self.settings, self.source, "studio-demo", "other")[
            "book_id"
        ]
        foreign = self.db.execute(
            "SELECT id FROM chapters WHERE book_id=?", (other,)
        ).fetchone()[0]
        for kwargs in [
            dict(expression="2-1"),
            dict(expression="1-3"),
            dict(expression="oops"),
            dict(chapter_ids=[foreign]),
        ]:
            with self.assertRaises(DomainError):
                resolve(self.db, self.book, **kwargs)

    def test_submission_idempotency_and_immutable_voice_snapshot(self):
        job = submit(self.db, self.settings, self.request(), "same")
        self.assertEqual(job, submit(self.db, self.settings, self.request(), "same"))
        with self.assertRaises(DomainError):
            submit(self.db, self.settings, self.request(mode="new-version"), "same")
        preset = create_preset(
            self.db, self.settings, "新版", "clone:" + DEFAULT_CLONE_ID, "平静地朗读。", {}
        )
        new = submit(
            self.db,
            self.settings,
            {**self.request(), "voice_preset_version_id": preset},
            "new",
        )
        self.assertEqual(
            "clone:" + DEFAULT_CLONE_ID, detail(self.db, job)["config"]["generation"]["speaker"]
        )
        self.assertEqual(
            "clone:" + DEFAULT_CLONE_ID, detail(self.db, new)["config"]["generation"]["speaker"]
        )

    def test_cosyvoice_default_and_legacy_presets_remain_read_only(self):
        saved = create_preset(
            self.db, self.settings, "我的旁白", "clone:" + DEFAULT_CLONE_ID, "平静地讲述。", {}
        )
        self.db.execute("INSERT INTO presets VALUES (?,?,?,?,?,?,?)",
                        ("legacy", "旧固定音色", "Dylan", "朗读。", "{}", time.time(), 0))
        original = {row["id"]: dict(row) for row in self.db.execute("SELECT * FROM presets")}
        old_job = submit(self.db, self.settings, self.request(), "before-reinit")
        frozen = detail(self.db, old_job)["config"]
        initialize(self.settings)
        initialize(self.settings)
        self.assertEqual(original, {row["id"]: dict(row) for row in self.db.execute("SELECT * FROM presets")})
        self.assertEqual(frozen, detail(self.db, old_job)["config"])
        client = TestClient(create_app(self.settings))
        client.headers["X-Service-Token"] = self.settings.token_file.read_text()
        presets = client.get("/api/v1/presets").json()["items"]
        self.assertEqual({DEFAULT_PRESET_ID, HAORAN_PRESET_ID, saved}, {p["id"] for p in presets})
        self.assertTrue(all("<|endofprompt|>" not in p["instruct"] for p in presets))
        caps = client.get("/api/v1/voices").json()
        self.assertEqual(ENGINE, caps["engine"])
        self.assertEqual(['zonos:haoran'],[v['speaker'] for v in caps['voices']])
        self.assertEqual(DEFAULT_PRESET_ID, caps["default_preset_id"])
        self.assertTrue(caps["voice_cloning"])
        request = self.request([self.chapters[0]["id"]])
        request.pop("voice_preset_version_id")
        response = client.post("/api/v1/jobs", json=request, headers={"Idempotency-Key": "new-default"})
        self.assertEqual(202, response.status_code, response.text)
        new = detail(self.db, response.json()["id"])
        self.assertEqual(ENGINE, new["config"]["generation"]["engine"])
        self.assertEqual("reference-codes", new["config"]["generation"]["inference_mode"])
        self.assertFalse(caps["instruction"])
        self.assertFalse(caps["reference_text_required"])
        self.assertEqual("clone:" + DEFAULT_CLONE_ID, new["config"]["generation"]["speaker"])
        self.assertEqual(1.0, new["config"]["export"]["speed"])
        for speaker in ("Dylan", "Uncle_Fu"):
            with self.assertRaises(DomainError):
                create_preset(self.db, self.settings, "旧音色", speaker, "朗读。", {})
        with self.assertRaises(DomainError):
            submit(self.db, self.settings, {**self.request(), "voice_preset_version_id": "legacy"}, "legacy-request")
        legacy_config = {**frozen, "generation": {**frozen["generation"], "engine": "qwen3-tts-cuda"}}
        with self.assertRaises(DomainError):
            submit(self.db, self.settings, self.request(), "legacy-frozen", frozen=legacy_config)

    def test_cache_preview_full_cpu_speed_and_immutable_versions(self):
        preview = submit(
            self.db,
            self.settings,
            self.request([self.chapters[0]["id"]]),
            "preview",
            "preview",
        )
        self.finish()
        preview_artifact = detail(self.db, preview)["chapters"][0]["artifact_id"]
        self.assertFalse(
            available(self.db, self.settings, preview_artifact)["complete"]
        )
        full = self.successful()
        self.assertGreater(full["counts"]["reused"], 0)
        calls = len(self.engine.calls)
        fast = submit(
            self.db,
            self.settings,
            self.request(export={"speed": 1.15, "pause_ms": 100}),
            "fast",
        )
        self.finish()
        result = detail(self.db, fast)
        self.assertEqual("succeeded", result["state"], result)
        self.assertEqual(calls, len(self.engine.calls))
        a = available(self.db, self.settings, result["chapters"][0]["artifact_id"])
        m = json.loads((self.settings.audio / a["manifest_path"]).read_text())
        self.assertLessEqual(m["cues"][-1]["end_ms"], m["duration_ms"])
        self.assertNotEqual(full["chapters"][0]["artifact_id"], a["id"])
        old = available(self.db, self.settings, full["chapters"][0]["artifact_id"])
        before = old["audio_sha256"]
        submit(self.db, self.settings, self.request(mode="new-version"), "force")
        self.finish()
        self.assertEqual(
            before, available(self.db, self.settings, old["id"])["audio_sha256"]
        )

    def test_loudness_reexport_reuses_audio_and_retains_original_artifact(self):
        original = submit(
            self.db,
            self.settings,
            self.request(
                [self.chapters[0]["id"]], export={"normalize_loudness": False}
            ),
            "raw-volume",
        )
        self.finish()
        old = detail(self.db, original)
        artifact = available(self.db, self.settings, old["chapters"][0]["artifact_id"])
        sha = artifact["audio_sha256"]
        calls = len(self.engine.calls)
        normal = submit(
            self.db,
            self.settings,
            self.request([self.chapters[0]["id"]], export={"normalize_loudness": True}),
            "normal-volume",
        )
        self.finish()
        new = detail(self.db, normal)
        self.assertEqual("succeeded", new["state"], new)
        self.assertEqual(old["variant_id"], new["variant_id"])
        self.assertEqual(calls, len(self.engine.calls))
        self.assertEqual(new["counts"]["completed"], new["counts"]["reused"])
        updated = available(self.db, self.settings, new["chapters"][0]["artifact_id"])
        self.assertNotEqual(artifact["id"], updated["id"])
        self.assertEqual(
            sha, available(self.db, self.settings, artifact["id"])["audio_sha256"]
        )
        before = json.loads(
            (self.settings.audio / artifact["manifest_path"]).read_text()
        )
        after = json.loads((self.settings.audio / updated["manifest_path"]).read_text())
        self.assertEqual(before["cues"], after["cues"])
        self.assertEqual(before["source_revision"], after["source_revision"])
        self.assertTrue(after["export"]["normalize_loudness"])
        self.assertLessEqual(after["loudness"]["output_true_peak_db"], -1)
        with self.assertRaises(DomainError):
            submit(
                self.db,
                self.settings,
                self.request(export={"normalize_loudness": "yes"}),
                "bad-loudness",
            )

    def test_punctuation_plan_version_keeps_caches_separate(self):
        request = self.request([self.chapters[0]["id"]])
        with patch("server.presets.SPEECH_PLAN", LEGACY_SPEECH_PLAN):
            legacy = submit(self.db, self.settings, request, "legacy-punctuation")
            self.assertTrue(self.gpu.tick())
            repeated = submit(self.db, self.settings, request, "reuse-legacy")
        old = detail(self.db, legacy)
        self.assertGreater(old["counts"]["completed"], 0, old)
        self.assertGreater(detail(self.db, repeated)["counts"]["reused"], 0)

        current = submit(self.db, self.settings, request, "new-punctuation")
        new = detail(self.db, current)
        self.assertEqual(SPEECH_PLAN, new["config"]["generation"]["speech_plan"])
        self.assertEqual(LEGACY_SPEECH_PLAN, old["config"]["generation"]["speech_plan"])
        self.assertNotEqual(old["variant_id"], new["variant_id"])
        self.assertEqual(0, new["counts"]["reused"])

    def test_invalid_segment_does_not_discard_good_outputs(self):
        parts = self.db.execute(
            "SELECT id FROM segments WHERE revision_id=? ORDER BY segment_index",
            (self.chapters[0]["current_revision"],),
        ).fetchall()
        self.engine.invalid = {parts[0]["id"]}
        job = submit(
            self.db, self.settings, self.request([self.chapters[0]["id"]]), "bad"
        )
        self.gpu.tick()
        d = detail(self.db, job)
        self.assertEqual(d["counts"]["total"] - 1, d["counts"]["completed"])
        self.engine.invalid = set()
        self.finish()
        self.assertEqual("succeeded", detail(self.db, job)["state"])

    def symbol_chapter(self, body="嗯。啊！A1……\n\n　　>"):
        with sqlite3.connect(self.source) as src:
            src.execute("UPDATE chapters SET body=? WHERE id='rain-1'", (body,))
        import_sqlite(self.settings, self.source, "studio-demo", "test")
        return self.db.execute(
            "SELECT * FROM chapters WHERE id=?", (self.chapters[0]["id"],)
        ).fetchone()

    def test_non_spoken_classification_preserves_short_meaningful_text(self):
        for text in ("　>\n", "……", "？！", "***", "—", "😀✨", "\t "):
            with self.subTest(text=text):
                self.assertFalse(has_spoken_content(text))
        for text in ("嗯", "啊！", "1", "A", "β", "あ", "〇", "你好 >"):
            with self.subTest(text=text):
                self.assertTrue(has_spoken_content(text))

    def test_symbol_skip_exports_complete_chapter_and_repairs_legacy_retry(self):
        chapter = self.symbol_chapter()
        request = self.request([chapter["id"]])
        client = TestClient(create_app(self.settings))
        client.headers["X-Service-Token"] = self.settings.token_file.read_text()
        estimate = client.post("/api/v1/jobs/estimate", json=request).json()
        self.assertEqual(1, estimate["skipped"])
        self.assertEqual(estimate["segments"] - 1, estimate["missing"])
        job = submit(self.db, self.settings, request, "symbol")
        marker = self.db.execute(
            "SELECT * FROM segments WHERE revision_id=? ORDER BY segment_index DESC LIMIT 1",
            (chapter["current_revision"],),
        ).fetchone()
        self.finish()
        result = detail(self.db, job)
        self.assertEqual("succeeded", result["state"], result)
        self.assertEqual(1, result["counts"]["skipped"])
        self.assertEqual([], result["errors"])
        self.assertNotIn(
            marker["id"], [s for batch in self.engine.calls for s in batch]
        )
        self.assertEqual(
            0,
            self.db.execute(
                "SELECT count(*) FROM attempts WHERE segment_id=?", (marker["id"],)
            ).fetchone()[0],
        )
        artifact = available(
            self.db, self.settings, result["chapters"][0]["artifact_id"]
        )
        manifest = json.loads(
            (self.settings.audio / artifact["manifest_path"]).read_text()
        )
        self.assertTrue(manifest["complete"])
        self.assertEqual(result["counts"]["completed"], len(manifest["cues"]) + len(manifest["title_cues"]))
        self.assertEqual(
            marker["original_text"], manifest["skipped_segments"][0]["original_text"]
        )
        self.assertEqual(
            marker["start_utf16"], manifest["skipped_segments"][0]["start_utf16"]
        )
        self.assertEqual(
            marker["end_utf16"], manifest["skipped_segments"][0]["end_utf16"]
        )
        imported = import_artifact(
            self.settings,
            self.settings.audio / artifact["audio_path"],
            self.settings.audio / artifact["manifest_path"],
            chapter["id"],
        )
        self.assertTrue(
            available(self.db, self.settings, imported["artifact_id"])["complete"]
        )

        # Reproduce the old terminal failure; retry must reuse every real clip.
        self.db.execute("UPDATE jobs SET state='partial_failed' WHERE id=?", (job,))
        self.db.execute(
            "UPDATE job_chapters SET state='failed',artifact_id=NULL WHERE job_id=?",
            (job,),
        )
        self.db.execute(
            "UPDATE job_items SET state='failed',attempts=3,error=? WHERE job_id=? AND segment_id=?",
            ("Segment too short to split safely", job, marker["id"]),
        )
        calls = len(self.engine.calls)
        retry = action(self.db, self.settings, job, "retry-failed", "symbol-retry")
        self.finish()
        fixed = detail(self.db, retry)
        self.assertEqual("succeeded", fixed["state"], fixed)
        self.assertEqual([], fixed["errors"])
        self.assertEqual(1, fixed["counts"]["skipped"])
        self.assertEqual(fixed["counts"]["completed"], fixed["counts"]["reused"])
        self.assertEqual(calls, len(self.engine.calls))
        self.assertEqual("partial_failed", detail(self.db, job)["state"])

    def test_old_queued_symbols_skip_before_retry_and_empty_chapter_has_no_fake_audio(
        self,
    ):
        chapter = self.symbol_chapter(">")
        job = submit(
            self.db, self.settings, self.request([chapter["id"]]), "only-symbol"
        )
        # Simulate an older API leaving the item queued for the GPU worker.
        self.db.execute(
            "UPDATE job_items SET state='queued',attempts=3,error='old failure' WHERE job_id=?",
            (job,),
        )
        self.db.execute(
            "UPDATE jobs SET state='queued',phase='synthesis' WHERE id=?", (job,)
        )
        self.finish()
        result = detail(self.db, job)
        self.assertEqual("succeeded", result["state"], result)
        self.assertEqual(1, result["counts"]["skipped"])
        self.assertEqual("skipped", result["chapters"][0]["state"])
        self.assertIsNone(result["chapters"][0]["artifact_id"])
        self.assertEqual([], self.engine.calls)
        self.assertEqual([], result["errors"])
        self.assertEqual(
            0, self.db.execute("SELECT count(*) FROM artifacts").fetchone()[0]
        )

    def test_single_character_speech_failure_is_not_skipped(self):
        chapter = self.symbol_chapter("嗯。")
        self.engine.generate = lambda rows, c: [np.zeros(24000) for _ in rows]
        job = submit(
            self.db, self.settings, self.request([chapter["id"]]), "short-spoken"
        )
        self.finish()
        result = detail(self.db, job)
        self.assertEqual("failed", result["state"], result)
        self.assertEqual(0, result["counts"]["skipped"])
        body_errors = [error for error in result["errors"] if '嗯' in error['original_text']]
        self.assertEqual(1, len(body_errors))
        self.assertEqual(2, body_errors[0]["attempts"])

    def test_bounded_split_and_failure_retry(self):
        job = submit(
            self.db, self.settings, self.request([self.chapters[0]["id"]]), "bad-all"
        )
        original = self.engine.generate
        self.engine.generate = lambda rows, c: [np.zeros(24000) for _ in rows]
        self.finish()
        failed = detail(self.db, job)
        self.assertIn(failed["state"], {"failed", "partial_failed"})
        self.assertLessEqual(
            self.db.execute("SELECT max(number) FROM attempts").fetchone()[0], 3
        )
        self.assertEqual(
            0, self.db.execute("SELECT count(*) FROM artifacts").fetchone()[0]
        )
        self.engine.generate = original
        retry = action(self.db, self.settings, job, "retry-failed", "retry")
        self.finish()
        self.assertEqual("succeeded", detail(self.db, retry)["state"])
        self.assertEqual(failed["state"], detail(self.db, job)["state"])

    def test_retry_and_split_use_distinct_seeds_and_keep_source_anchors(self):
        # Force the full passage to fail, then let each split child recover on
        # its second attempt. Verify queue-to-engine seeds and failure telemetry.
        chapter = self.symbol_chapter("😀第一段文字在这里，后面的叙述还在继续，最后说完这句话。")
        generate = self.engine.generate
        seen = []

        def retrying(rows, config):
            results = generate(rows, config)
            self.engine.last_execution = {}
            for i, row in enumerate(rows):
                seen.append(dict(row))
                self.engine.last_execution[row["id"]] = {"seed": generation_seed(row, config), "audio_seconds": .5}
                if not row["depth"] or row["attempt_number"] == 1:
                    results[i] = RuntimeError("premature EOS")
            return results

        self.engine.generate = retrying
        job = submit(self.db, self.settings, self.request([chapter["id"]]), "split-seeds")
        self.finish()
        self.assertEqual("succeeded", detail(self.db, job)["state"])
        parent = next(row for row in seen if row["depth"] == 0 and row["sentence_index"] >= 0)
        attempts = list(self.db.execute("SELECT * FROM attempts WHERE job_id=? AND segment_id=? ORDER BY number", (job, parent["id"])))
        self.assertEqual([1, 2], [a["number"] for a in attempts])
        self.assertEqual(2, len({a["seed"] for a in attempts}))
        children = list(self.db.execute("SELECT * FROM segments WHERE parent_segment_id=? ORDER BY start_utf16", (parent["id"],)))
        self.assertEqual(2, len(children))
        self.assertTrue(children[0]["original_text"].endswith("，"))
        self.assertEqual(parent["original_text"], "".join(c["original_text"] for c in children))
        for child in children:
            start = child["start_utf16"] - parent["start_utf16"]
            end = child["end_utf16"] - parent["start_utf16"]
            self.assertEqual(child["original_text"], parent["original_text"].encode("utf-16-le")[start*2:end*2].decode("utf-16-le"))
            child_attempts = list(self.db.execute("SELECT * FROM attempts WHERE job_id=? AND segment_id=? ORDER BY number", (job, child["id"])))
            self.assertEqual([1, 2], [a["number"] for a in child_attempts])
            self.assertNotEqual(child_attempts[0]["seed"], child_attempts[1]["seed"])
            self.assertEqual(.5, json.loads(child_attempts[0]["runtime_json"])["audio_seconds"])
        self.assertEqual(children[0]["end_utf16"], children[1]["start_utf16"])
        self.assertEqual(utf16_len(parent["original_text"]), children[-1]["end_utf16"] - children[0]["start_utf16"])
        with self.assertRaisesRegex(ValueError, "too short"):
            recovery_split_position("　　嗯。")

    def test_split_failure_is_bounded_keeps_valid_sibling_and_releases_later_jobs(self):
        chapter = self.symbol_chapter("第一段文字一直延续，后来的人走进房间，接着讲述那些往事，最后把故事慢慢说完。")
        generate = self.engine.generate
        seen = []
        first_child = None
        def recovery(rows, config):
            nonlocal first_child
            output = generate(rows, config)
            for i, row in enumerate(rows):
                if row["sentence_index"] < 0:
                    output[i] = ValueError("内容校验未通过")
                    continue  # The following budget assertions apply to one body segment.
                seen.append(dict(row))
                if row["depth"] == 1 and first_child is None:
                    first_child = row["id"]
                if row["depth"] == 0 or (row["depth"] == 1 and row["id"] != first_child):
                    output[i] = ValueError("内容校验未通过")
            return output
        self.engine.generate = recovery
        job = submit(self.db, self.settings, self.request([chapter["id"]]), "nested-split")
        self.finish()
        self.assertEqual("failed", detail(self.db, job)["state"])
        self.assertEqual(1, sum(r["id"] == first_child for r in seen))
        self.assertEqual(1,max(r["depth"] for r in seen))
        self.assertLessEqual(len(seen),6)
        parent = next(r for r in seen if r["depth"] == 0)
        children = self.db.execute("SELECT * FROM segments WHERE parent_segment_id=? ORDER BY start_utf16",(parent["id"],)).fetchall()
        self.assertEqual(parent["original_text"], "".join(c["original_text"] for c in children))
        self.assertEqual(children[0]["end_utf16"],children[1]["start_utf16"])
        self.assertIsNotNone(self.db.execute("SELECT id FROM segment_audio WHERE segment_id=? AND ready=1",(first_child,)).fetchone())
        self.engine.generate=generate
        later=submit(self.db,self.settings,self.request([self.chapters[0]['id']]),'later-chapter')
        self.finish()
        self.assertEqual('succeeded',detail(self.db,later)['state'])

    def test_retry_of_failed_job_uses_new_seeds_and_only_regenerates_failed_content(self):
        chapter = self.symbol_chapter("第一句话说完了。第二句话也说完了。")
        generate = self.engine.generate
        self.engine.generate = lambda rows,c: [ValueError("内容校验未通过") if "第二" in r["original_text"] else generate([r],c)[0] for r in rows]
        job = submit(self.db,self.settings,self.request([chapter["id"]]),"retry-seeds")
        self.finish()
        failed = detail(self.db,job)
        self.assertEqual("partial_failed",failed["state"])
        self.assertGreater(failed["errors"][0]["split_attempts"],0)
        self.assertGreater(failed["errors"][0]["attempts_total"],3)
        seen=[]
        def recovery(rows,c):
            seen.extend(dict(r) for r in rows)
            return generate(rows,c)
        self.engine.generate=recovery
        retry=action(self.db,self.settings,job,"retry-failed","retry-new-seeds")
        self.finish()
        self.assertEqual("succeeded",detail(self.db,retry)["state"])
        self.assertTrue(all("第一" not in r["original_text"] for r in seen))
        for row in seen:
            self.assertEqual(1,row["retry_round"])
            self.assertNotEqual(generation_seed(row,failed["config"]["generation"]),
                                generation_seed({**row,"retry_round":0},failed["config"]["generation"]))

    def test_pause_cancel_and_recovery_fencing(self):
        job = submit(self.db, self.settings, self.request(), "control")
        self.engine.callback = lambda: action(self.db, self.settings, job, "pause")
        self.gpu.tick()
        self.engine.callback = None
        self.assertEqual("paused", detail(self.db, job)["state"])
        self.assertFalse(self.gpu.tick())
        action(self.db, self.settings, job, "resume")
        self.finish()
        self.assertEqual("succeeded", detail(self.db, job)["state"])
        other = submit(
            self.db, self.settings, self.request(mode="new-version"), "fence"
        )
        old = claim(self.db, self.settings, "old", "gpu")
        self.db.execute("UPDATE jobs SET lease_until=0 WHERE id=?", (other,))
        recover(self.db)
        with self.assertRaises(DomainError):
            assert_owner(self.db, old)
        action(self.db, self.settings, other, "cancel")
        recover(self.db)
        self.assertEqual("cancelled", detail(self.db, other)["state"])

    def test_preview_fairness_and_disk_pressure(self):
        normal = submit(self.db, self.settings, self.request(), "normal")
        previews = [
            submit(
                self.db,
                self.settings,
                self.request([self.chapters[0]["id"]]),
                f"p{i}",
                "preview",
            )
            for i in range(4)
        ]
        for i in range(3):
            row = claim(self.db, self.settings, "test", "gpu", i)
            self.assertEqual(previews[i], row["id"])
            release(self.db, row, "succeeded")
        row = claim(self.db, self.settings, "test", "gpu", 3)
        self.assertEqual(normal, row["id"])
        release(self.db, row, "queued")
        with patch.object(self.gpu, "enough_space", return_value=False):
            self.gpu.tick()
        self.assertEqual("paused", detail(self.db, previews[-1])["state"])
        self.assertEqual(0, len(self.engine.calls))

    def test_http_range_auth_validation_and_bundle(self):
        result = self.successful()
        artifact = result["chapters"][0]["artifact_id"]
        client = TestClient(create_app(self.settings))
        self.assertEqual(401, client.get("/api/v1/health").status_code)
        client.headers["X-Service-Token"] = self.settings.token_file.read_text()
        response = client.get(
            f"/api/v1/artifacts/{artifact}/audio", headers={"Range": "bytes=0-31"}
        )
        self.assertEqual(206, response.status_code, response.text)
        self.assertEqual(32, len(response.content))
        self.assertIn("content-range", response.headers)
        self.assertEqual(
            416,
            client.get(
                f"/api/v1/artifacts/{artifact}/audio",
                headers={"Range": "bytes=999999999-"},
            ).status_code,
        )
        self.assertEqual(
            422,
            client.post(
                "/api/v1/jobs",
                json=self.request(export={"speed": 9}),
                headers={"Idempotency-Key": "invalid"},
            ).status_code,
        )
        self.assertEqual(
            422,
            client.post(
                "/api/v1/jobs",
                json={**self.request(), "voice_cloning": True},
                headers={"Idempotency-Key": "unsupported"},
            ).status_code,
        )
        bundle = create_bundle(
            self.db,
            self.settings,
            [c["artifact_id"] for c in result["chapters"]],
            "zip",
        )
        self.finish()
        b = self.db.execute(
            "SELECT * FROM bundles WHERE job_id=?", (bundle,)
        ).fetchone()
        with zipfile.ZipFile(self.settings.audio / b["path"]) as archive:
            self.assertEqual(5, len(archive.namelist()))
            self.assertEqual(
                2, len(json.loads(archive.read("catalogue.json"))["entries"])
            )

    def test_missing_files_audit_and_historical_export_only_import(self):
        result = self.successful()
        a = available(self.db, self.settings, result["chapters"][0]["artifact_id"])
        imported = import_artifact(
            self.settings,
            self.settings.audio / a["audio_path"],
            self.settings.audio / a["manifest_path"],
            self.chapters[0]["id"],
        )
        self.assertEqual(0, imported["raw_clips_imported"])
        before = self.db.execute("SELECT count(*) FROM segment_audio").fetchone()[0]
        available(self.db, self.settings, imported["artifact_id"])
        self.assertEqual(
            before, self.db.execute("SELECT count(*) FROM segment_audio").fetchone()[0]
        )
        (self.settings.audio / a["audio_path"]).unlink()
        with self.assertRaises(DomainError):
            available(self.db, self.settings, a["id"])
        self.assertEqual("ok", audit(self.settings)["integrity"])

    def test_gpu_lock_and_consistent_backup(self):
        lock = self.root / "gpu.lock"
        with GPULock(lock):
            with self.assertRaises(RuntimeError):
                with GPULock(lock):
                    pass
        destination = self.root / "backup.sqlite"
        backup(self.settings, destination)
        with sqlite3.connect(destination) as copied:
            self.assertEqual(
                2, copied.execute("SELECT count(*) FROM chapters").fetchone()[0]
            )

    def test_long_unicode_sentence_and_successful_split_lineage(self):
        with sqlite3.connect(self.source) as src:
            src.execute(
                "UPDATE chapters SET body=? WHERE id='rain-1'",
                ("繁體😀，" * 80 + "完。",),
            )
        import_sqlite(self.settings, self.source, "studio-demo", "test")
        chapter = self.db.execute(
            "SELECT * FROM chapters WHERE id=?", (self.chapters[0]["id"],)
        ).fetchone()
        revision = self.db.execute(
            "SELECT * FROM revisions WHERE id=?", (chapter["current_revision"],)
        ).fetchone()
        parts = list(
            self.db.execute(
                "SELECT * FROM segments WHERE revision_id=? AND depth=0 ORDER BY segment_index",
                (revision["id"],),
            )
        )
        self.assertGreater(len(parts), 1)
        self.assertEqual(
            revision["canonical_text"], "".join(p["original_text"] for p in parts)
        )
        self.engine.invalid = {parts[0]["id"]}
        job = submit(
            self.db, self.settings, self.request([chapter["id"]]), "split-success"
        )
        self.finish()
        self.assertEqual("succeeded", detail(self.db, job)["state"])
        children = list(
            self.db.execute(
                "SELECT * FROM segments WHERE parent_segment_id=? ORDER BY start_utf16",
                (parts[0]["id"],),
            )
        )
        self.assertEqual(2, len(children))
        self.assertEqual(
            parts[0]["original_text"], "".join(c["original_text"] for c in children)
        )
        self.assertEqual(children[0]["end_utf16"], children[1]["start_utf16"])

    def test_restarted_worker_repairs_missing_checkpoint_file(self):
        job = submit(self.db, self.settings, self.request(), "repair-checkpoint")
        self.gpu.tick()
        audio = self.db.execute("SELECT * FROM segment_audio LIMIT 1").fetchone()
        (self.settings.audio / audio["path"]).unlink()
        restarted = Worker(self.settings, engine=self.engine)
        for _ in range(30):
            a = restarted.tick()
            b = self.cpu.tick()
            if not a and not b:
                break
        self.assertEqual("succeeded", detail(self.db, job)["state"])
        self.assertEqual(
            0,
            self.db.execute(
                "SELECT ready FROM segment_audio WHERE id=?", (audio["id"],)
            ).fetchone()[0],
        )

    def test_ffmpeg_only_probe_fallback(self):
        from tts.audio import media_duration

        result = self.successful()
        a = available(self.db, self.settings, result["chapters"][0]["artifact_id"])
        with patch("tts.audio.shutil.which", return_value=None):
            self.assertAlmostEqual(
                a["duration_ms"] / 1000,
                media_duration(self.settings.audio / a["audio_path"]),
                delta=0.15,
            )

    def test_killed_process_releases_lock_and_job_recovers(self):
        job = submit(self.db, self.settings, self.request(), "process-crash")
        env = {
            **os.environ,
            "SHANYE_STATE": str(self.settings.state),
            "SHANYE_AUDIO": str(self.settings.audio),
            "SHANYE_GPU_LOCK": str(self.root / "process-gpu.lock"),
            "SHANYE_TEST_DELAY": "60",
        }
        process = subprocess.Popen(
            [sys.executable, "-m", "server.tests.worker_harness"],
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.addCleanup(lambda: process.poll() is None and process.kill())
        for _ in range(80):
            row = self.db.execute(
                "SELECT owner FROM jobs WHERE id=?", (job,)
            ).fetchone()
            if row["owner"]:
                break
            time.sleep(0.05)
        self.assertIsNotNone(row["owner"])
        process.kill()
        process.wait(timeout=5)
        self.db.execute("UPDATE jobs SET lease_until=0 WHERE id=?", (job,))
        env["SHANYE_TEST_DELAY"] = "0"
        restarted = subprocess.Popen(
            [sys.executable, "-m", "server.tests.worker_harness"],
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.addCleanup(lambda: restarted.poll() is None and restarted.kill())
        try:
            for _ in range(150):
                self.cpu.tick()
                if detail(self.db, job)["state"] == "succeeded":
                    break
                time.sleep(0.05)
            self.assertEqual("succeeded", detail(self.db, job)["state"])
        finally:
            restarted.terminate()
            restarted.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
