from __future__ import annotations

import argparse
from contextlib import closing
import importlib.metadata
import json
import logging
import shutil
import signal
import threading
import time
import uuid

from tts.common import (
    GPULock,
    digest,
    file_digest,
    utf16_len,
    validated_samples,
    has_spoken_content,
    NON_SPOKEN_REASON,
)
from tts.audio import write_flac
from .artifacts import export_chapter, build_bundle, expire_bundles
from .catalog import DomainError
from .config import ENGINE, Settings
from .audio8_runtime import Audio8Engine
from .cosy_runtime import enable_decoder_only, load_cosyvoice
from .db import connect, transaction, encoded, event, initialize
from .jobs import claim, assert_owner, cached_audio, release, verified_file

log = logging.getLogger(__name__)


class CudaEngine:
    def __init__(self, settings, status):
        self.settings, self.status, self.model = settings, status, None
        self.last_execution = {}
        self.clone_key = None
        self.clone_spk_id = None

    def generate(self, rows, config):
        if config.get("engine") != "cosyvoice3-0.5b-cuda":
            raise RuntimeError("旧 Qwen 合成配置已停用，请重新创建 CosyVoice3 任务")
        if not config.get("voice_clone_ref_id"):
            raise RuntimeError("CosyVoice3 任务必须指定参考音色")
        import torch

        if self.model is None:
            self.status.update(model="loading", gpu="loading")
            try:
                import sys

                repo = str(self.settings.cosyvoice_repo)
                matcha = str(self.settings.cosyvoice_repo / "third_party/Matcha-TTS")
                if repo not in sys.path:
                    sys.path.insert(0, repo)
                if matcha not in sys.path:
                    sys.path.insert(1, matcha)
                from cosyvoice.cli.cosyvoice import AutoModel

                if not self.settings.cosyvoice_ready:
                    raise RuntimeError("CosyVoice3 0.5B 模型文件不完整")
                self.model = load_cosyvoice(
                    AutoModel,
                    torch,
                    model_dir=str(self.settings.cosyvoice_model_path),
                    low_memory=self.settings.cosyvoice_low_memory,
                )
                if self.settings.cosyvoice_decoder_only:
                    enable_decoder_only(self.model)
                if self.settings.cosyvoice_cuda_graph:
                    from .cosy_graph import enable_cuda_graph

                    enable_cuda_graph(self.model)
                if self.model.sample_rate != 24000:
                    raise RuntimeError("CosyVoice3 输出采样率必须为 24000 Hz")
                self.status.update(model="ready", gpu=torch.cuda.get_device_name(0))
            except Exception as exc:
                self.model = None
                self.status.update(model="failed", error=str(exc))
                raise
        return self._batch(rows, config, torch)

    def _batch(self, rows, config, torch):
        seed = (
            config["seed"]
            + rows[0]["chapter_index"] * 100000
            + rows[0]["segment_index"]
        )
        for row in rows:
            self.last_execution[row["id"]] = {
                "seed": seed, "batch_size": len(rows),
                "decoder_only": self.settings.cosyvoice_decoder_only,
                "cuda_graph": self.settings.cosyvoice_cuda_graph,
            }
        torch.manual_seed(seed)
        torch.cuda.manual_seed_all(seed)
        try:
            import numpy as np

            ref_path = self.settings.audio / config["ref_audio_path"]
            if not ref_path.is_file():
                raise RuntimeError("Reference audio is missing")
            clone_key = (
                config["voice_clone_ref_id"],
                config["ref_audio_sha256"],
                config["instruct"],
            )
            if self.clone_key != clone_key:
                if file_digest(ref_path) != config["ref_audio_sha256"]:
                    raise RuntimeError("Reference audio checksum mismatch")
                # Cached instruct2 inputs retain prompt_text. Cache the style
                # instruction, not the reference transcript, or the instruction
                # passed to inference_instruct2 would silently be ignored.
                self.clone_spk_id = "service-" + config["voice_clone_ref_id"]
                self.model.add_zero_shot_spk(config["instruct"], str(ref_path), self.clone_spk_id)
                self.clone_key = clone_key
            output = []
            with torch.inference_mode():
                for row in rows:
                    pieces = []
                    stream = self.model.inference_instruct2(
                        row["spoken_text"],
                        config["instruct"],
                        "",
                        zero_shot_spk_id=self.clone_spk_id,
                        stream=False,
                        speed=1.0,
                        text_frontend=config["text_frontend"],
                    )
                    for item in stream:
                        speech = item["tts_speech"].detach().float().cpu().numpy().reshape(-1)
                        if len(speech) and np.isfinite(speech).all():
                            pieces.append(speech)
                    if not pieces:
                        raise RuntimeError("CosyVoice3 returned empty audio")
                    output.append(np.concatenate(pieces))
            torch.cuda.synchronize()
            return output
        except Exception as exc:
            torch.cuda.empty_cache()
            if len(rows) == 1:
                return [exc]
            middle = len(rows) // 2
            return self._batch(rows[:middle], config, torch) + self._batch(
                rows[middle:], config, torch
            )


class Worker:
    def __init__(self, settings, role="gpu", engine=None):
        self.settings, self.role = settings, role
        self.owner = f"{role}-{uuid.uuid4().hex}"
        self.status = {
            "state": "starting",
            "model": "unloaded" if role == "gpu" else "not-applicable",
            "gpu": "unknown",
        }
        self.engine = engine or Audio8Engine(settings, self.status)
        self.job = None
        self.streak = 0
        self.verified_jobs = set()
        self.stopping = threading.Event()
        self.heartbeat_stop = threading.Event()

    def pulse(self):
        with closing(connect(self.settings)) as db:
            now = time.time()
            if self.job:
                db.execute(
                    "UPDATE jobs SET lease_until=? WHERE id=? AND token=?",
                    (
                        now + self.settings.lease_seconds,
                        self.job["id"],
                        self.job["token"],
                    ),
                )
            db.execute(
                "INSERT INTO runtime VALUES (?,?,?) ON CONFLICT(key) DO UPDATE SET value_json=excluded.value_json,heartbeat=excluded.heartbeat",
                (
                    self.role,
                    encoded(
                        {
                            **self.status,
                            "owner": self.owner,
                            "job_id": self.job and self.job["id"],
                        }
                    ),
                    now,
                ),
            )

    def heartbeat(self):
        while not self.heartbeat_stop.wait(2):
            try:
                self.pulse()
            except Exception:
                log.exception("Worker heartbeat failed")

    def enough_space(self):
        return all(
            shutil.disk_usage(p).free >= self.settings.min_free_bytes
            for p in (self.settings.state, self.settings.audio)
        )

    def store_audio(self, db, job, segment, samples, elapsed, provenance):
        import soundfile as sf

        identifier = uuid.uuid4().hex
        relative = f"segments/{job['variant_id']}/{segment['id']}/{identifier}.flac"
        target = self.settings.audio / relative
        write_flac(target, samples)
        info = sf.info(target)
        if info.frames != len(samples) or info.samplerate != 24000:
            raise ValueError("FLAC round trip mismatch")
        sha = file_digest(target)
        with transaction(db):
            assert_owner(db, job)
            db.execute(
                "INSERT INTO segment_audio VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    identifier,
                    job["variant_id"],
                    job["generation_id"],
                    segment["id"],
                    relative,
                    sha,
                    len(samples),
                    24000,
                    elapsed,
                    encoded(provenance),
                    1,
                    time.time(),
                ),
            )
        return identifier

    def begin_attempt(self, db, job, part, runtime):
        with transaction(db):
            assert_owner(db, job)
            count = db.execute(
                "SELECT count(*) FROM attempts WHERE job_id=? AND segment_id=?",
                (job["id"], part["id"]),
            ).fetchone()[0]
            number = count + 1
            config = json.loads(job["config_json"])["generation"]
            seed = (
                config["seed"] + part["chapter_index"] * 100000 + part["segment_index"]
            )
            db.execute(
                "INSERT INTO attempts(job_id,segment_id,number,started,seed,runtime_json) VALUES (?,?,?,?,?,?)",
                (job["id"], part["id"], number, time.time(), seed, encoded(runtime)),
            )
            db.execute(
                "UPDATE job_items SET attempts=? WHERE job_id=? AND segment_id=?",
                (number, job["id"], part["id"]),
            )
        return number

    def finish_attempt(self, db, job, part, number, elapsed, error=None):
        with transaction(db):
            assert_owner(db, job)
            db.execute(
                "UPDATE attempts SET elapsed=?,error=? WHERE job_id=? AND segment_id=? AND number=?",
                (elapsed, error, job["id"], part["id"], number),
            )

    def split_fallback(self, db, job, part, config):
        """One bounded split level, with durable child attempts and reusable valid children."""
        from opencc import OpenCC
        import numpy as np
        import soundfile as sf

        text = part["original_text"]
        if len(text.strip()) < 4:
            raise ValueError("Segment too short to split safely")
        middle = len(text) // 2
        children, ids, arrays = [], [], []
        for start, end in ((0, middle), (middle, len(text))):
            original = text[start:end]
            child = dict(part)
            child.update(
                id=digest(f"{part['id']}:split:{start}:{end}"),
                original_text=original,
                spoken_text=OpenCC("t2s").convert(original).strip(),
                start_utf16=part["start_utf16"] + utf16_len(text[:start]),
                end_utf16=part["start_utf16"] + utf16_len(text[:end]),
                depth=1,
            )
            if not has_spoken_content(child["spoken_text"]):
                continue
            db.execute(
                """INSERT OR IGNORE INTO segments VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                tuple(
                    child[k]
                    for k in (
                        "id",
                        "revision_id",
                        "segment_index",
                        "sentence_index",
                        "sentence_start_utf16",
                        "sentence_end_utf16",
                        "start_utf16",
                        "end_utf16",
                        "original_text",
                        "spoken_text",
                    )
                )
                + (part["id"], 1),
            )
            audio = cached_audio(
                db,
                self.settings,
                job["variant_id"],
                child["id"],
                None if job["generation_id"] == "reuse" else job["generation_id"],
            )
            count = db.execute(
                "SELECT count(*) FROM attempts WHERE job_id=? AND segment_id=?",
                (job["id"], child["id"]),
            ).fetchone()[0]
            while not audio and count < 3:
                number = self.begin_attempt(
                    db, job, child, {"batch_size": 1, "split_parent": part["id"]}
                )
                started = time.monotonic()
                try:
                    output = self.engine.generate([child], config)[0]
                    if isinstance(output, Exception):
                        raise output
                    samples = validated_samples(output, child["id"])
                    identifier = self.store_audio(
                        db,
                        job,
                        child,
                        samples,
                        time.monotonic() - started,
                        {"split_parent": part["id"]},
                    )
                    audio = db.execute(
                        "SELECT * FROM segment_audio WHERE id=?", (identifier,)
                    ).fetchone()
                    self.finish_attempt(
                        db, job, child, number, time.monotonic() - started
                    )
                except Exception as exc:
                    self.finish_attempt(
                        db, job, child, number, time.monotonic() - started, str(exc)
                    )
                count += 1
            if not audio:
                raise ValueError(
                    f"Split child exhausted retries: {child['original_text']}"
                )
            arrays.append(
                sf.read(self.settings.audio / audio["path"], dtype="float32")[0]
            )
            ids.append(audio["id"])
            children.append(
                {
                    k: child[k]
                    for k in ("id", "start_utf16", "end_utf16", "original_text")
                }
            )
        if not arrays:
            raise ValueError("No useful bounded split")
        samples = np.concatenate(arrays)
        return self.store_audio(
            db, job, part, samples, 0, {"derived_from": ids, "children": children}
        )

    def gpu_unit(self, db, job):
        config = json.loads(job["config_json"])["generation"]
        parts = [
            dict(r)
            for r in db.execute(
                """SELECT s.*,c.chapter_index,ji.attempts FROM job_items ji
            JOIN segments s ON s.id=ji.segment_id JOIN revisions r ON r.id=s.revision_id
            JOIN chapters c ON c.id=r.chapter_id WHERE ji.job_id=? AND ji.state='queued'
            ORDER BY ji.position LIMIT ?""",
                (job["id"], self.settings.batch_size),
            )
        ]
        pending, numbers = [], []
        for part in parts:
            audio = cached_audio(
                db,
                self.settings,
                job["variant_id"],
                part["id"],
                None if job["generation_id"] == "reuse" else job["generation_id"],
            )
            if audio:
                with transaction(db):
                    assert_owner(db, job)
                    db.execute(
                        "UPDATE job_items SET state='ready',audio_id=?,cache_hit=1,error=NULL WHERE job_id=? AND segment_id=?",
                        (audio["id"], job["id"], part["id"]),
                    )
                continue
            count = db.execute(
                "SELECT count(*) FROM attempts WHERE job_id=? AND segment_id=?",
                (job["id"], part["id"]),
            ).fetchone()[0]
            if count >= 3:
                try:
                    identifier = self.split_fallback(db, job, part, config)
                    self.commit_item(db, job, part, identifier)
                except Exception as exc:
                    self.fail_item(db, job, part, str(exc), terminal=True)
                continue
            pending.append(part)
        if pending:
            packages = {
                p: importlib.metadata.version(p) for p in ("numpy", "soundfile")
            }
            if isinstance(self.engine, CudaEngine):
                packages.update(
                    {
                        p: importlib.metadata.version(p)
                        for p in ("torch", "torchaudio", "onnxruntime-gpu", "transformers")
                    }
                )
            runtime = {
                "batch_size": len(pending),
                "engine": type(self.engine).__name__,
                "packages": packages,
            }
            numbers = [self.begin_attempt(db, job, part, runtime) for part in pending]
            started = time.monotonic()
            try:
                results = self.engine.generate(pending, config)
                if len(results) != len(pending):
                    raise ValueError("Model result count mismatch")
            except Exception as exc:
                results = [exc] * len(pending)
            elapsed = (time.monotonic() - started) / len(pending)
            for part, number, result in zip(pending, numbers, results):
                actual = getattr(self.engine, "last_execution", {}).get(part["id"])
                if actual:
                    with transaction(db):
                        assert_owner(db, job)
                        db.execute(
                            "UPDATE attempts SET seed=?,runtime_json=? WHERE job_id=? AND segment_id=? AND number=?",
                            (
                                actual["seed"],
                                encoded({**runtime, **actual}),
                                job["id"],
                                part["id"],
                                number,
                            ),
                        )
                try:
                    if isinstance(result, Exception):
                        raise result
                    samples = validated_samples(result, part["id"])
                    identifier = self.store_audio(
                        db,
                        job,
                        part,
                        samples,
                        elapsed,
                        {"attempt": number, "batch_size": len(pending)},
                    )
                    self.commit_item(db, job, part, identifier)
                    self.finish_attempt(db, job, part, number, elapsed)
                except DomainError:
                    raise
                except Exception as exc:
                    self.finish_attempt(db, job, part, number, elapsed, str(exc))
                    self.fail_item(db, job, part, str(exc))
        pending_count = db.execute(
            "SELECT count(*) FROM job_items WHERE job_id=? AND state='queued'",
            (job["id"],),
        ).fetchone()[0]
        if not pending_count:
            db.execute(
                "UPDATE jobs SET phase='exporting' WHERE id=? AND token=?",
                (job["id"], job["token"]),
            )
        release(db, job, "queued" if pending_count else "exporting")

    def commit_item(self, db, job, part, identifier):
        with transaction(db):
            assert_owner(db, job)
            db.execute(
                "UPDATE job_items SET state='ready',audio_id=?,error=NULL WHERE job_id=? AND segment_id=?",
                (identifier, job["id"], part["id"]),
            )

    def fail_item(self, db, job, part, error, terminal=False):
        with transaction(db):
            assert_owner(db, job)
            db.execute(
                "UPDATE job_items SET state=?,error=? WHERE job_id=? AND segment_id=?",
                ("failed" if terminal else "queued", error, job["id"], part["id"]),
            )
            event(
                db,
                job["id"],
                "segment-failed" if terminal else "segment-retry",
                {"segment_id": part["id"], "error": error},
            )

    def cpu_unit(self, db, job):
        if job["kind"] == "bundle":
            build_bundle(db, self.settings, job)
            release(db, job, "succeeded")
            return
        chapter = db.execute(
            "SELECT * FROM job_chapters WHERE job_id=? AND state='queued' ORDER BY position LIMIT 1",
            (job["id"],),
        ).fetchone()
        if chapter:
            try:
                counts = db.execute(
                    """SELECT count(*) AS total,sum(ji.state!='skipped') AS audible
                    FROM job_items ji JOIN segments s ON s.id=ji.segment_id
                    WHERE ji.job_id=? AND s.revision_id=?""",
                    (job["id"], chapter["revision_id"]),
                ).fetchone()
                artifact = None
                if not counts["total"] or counts["audible"]:
                    artifact = export_chapter(
                        db, self.settings, job, chapter["revision_id"]
                    )
                with transaction(db):
                    assert_owner(db, job)
                    db.execute(
                        "UPDATE job_chapters SET state=?,artifact_id=? WHERE job_id=? AND revision_id=?",
                        (
                            "succeeded" if artifact else "skipped",
                            artifact,
                            job["id"],
                            chapter["revision_id"],
                        ),
                    )
                    if not artifact:
                        event(
                            db,
                            job["id"],
                            "chapter-skipped",
                            {
                                "revision_id": chapter["revision_id"],
                                "reason": NON_SPOKEN_REASON,
                            },
                        )
            except DomainError as exc:
                if exc.status == 409:
                    raise
                with transaction(db):
                    assert_owner(db, job)
                    db.execute(
                        "UPDATE job_chapters SET state='failed',error=? WHERE job_id=? AND revision_id=?",
                        (str(exc), job["id"], chapter["revision_id"]),
                    )
            except Exception as exc:
                with transaction(db):
                    assert_owner(db, job)
                    db.execute(
                        "UPDATE job_chapters SET state='failed',error=? WHERE job_id=? AND revision_id=?",
                        (str(exc), job["id"], chapter["revision_id"]),
                    )
        remaining = db.execute(
            "SELECT count(*) FROM job_chapters WHERE job_id=? AND state='queued'",
            (job["id"],),
        ).fetchone()[0]
        failed = db.execute(
            "SELECT count(*) FROM job_chapters WHERE job_id=? AND state='failed'",
            (job["id"],),
        ).fetchone()[0]
        ready = db.execute(
            "SELECT count(*) FROM job_items WHERE job_id=? AND state='ready'",
            (job["id"],),
        ).fetchone()[0]
        release(
            db,
            job,
            "exporting"
            if remaining
            else ("partial_failed" if ready else "failed")
            if failed
            else "succeeded",
        )

    def validate_checkpoint(self, db, job):
        if job["id"] in self.verified_jobs or job["kind"] == "bundle":
            return False
        # Also normalize jobs submitted by an older API, including exhausted
        # punctuation-only items, before checking audio or spending any retries.
        skipped = [
            r["id"]
            for r in db.execute(
                """SELECT s.id,s.spoken_text FROM job_items ji JOIN segments s ON s.id=ji.segment_id
            WHERE ji.job_id=? AND ji.state!='skipped'""",
                (job["id"],),
            )
            if not has_spoken_content(r["spoken_text"])
        ]
        if skipped:
            with transaction(db):
                assert_owner(db, job)
                db.executemany(
                    """UPDATE job_items SET state='skipped',audio_id=NULL,cache_hit=0,error=NULL
                    WHERE job_id=? AND segment_id=?""",
                    [(job["id"], identifier) for identifier in skipped],
                )
                event(
                    db,
                    job["id"],
                    "segments-skipped",
                    {
                        "segment_ids": skipped,
                        "reason": NON_SPOKEN_REASON,
                    },
                )
        invalid = []
        for row in db.execute(
            """SELECT ji.segment_id,a.id,a.path,a.sha256 FROM job_items ji
            LEFT JOIN segment_audio a ON a.id=ji.audio_id WHERE ji.job_id=? AND ji.state='ready'""",
            (job["id"],),
        ).fetchall():
            if not row["path"] or not verified_file(
                self.settings, row["path"], row["sha256"]
            ):
                invalid.append(row)
        if invalid:
            with transaction(db):
                assert_owner(db, job)
                for row in invalid:
                    db.execute(
                        "UPDATE segment_audio SET ready=0 WHERE id=?", (row["id"],)
                    )
                    db.execute(
                        "UPDATE job_items SET state='queued',audio_id=NULL,error='checkpoint file unavailable' WHERE job_id=? AND segment_id=?",
                        (job["id"], row["segment_id"]),
                    )
                db.execute(
                    "UPDATE jobs SET phase='synthesis' WHERE id=? AND token=?",
                    (job["id"], job["token"]),
                )
                event(db, job["id"], "checkpoint-repaired", {"missing": len(invalid)})
        self.verified_jobs.add(job["id"])
        return bool(invalid)

    def tick(self):
        with closing(connect(self.settings)) as db:
            if self.role == "cpu":
                expire_bundles(db, self.settings)
            self.job = claim(db, self.settings, self.owner, self.role, self.streak)
            if not self.job:
                self.status["state"] = "idle"
                self.pulse()
                return False
            job = self.job
            self.status["state"] = job["phase"]
            self.pulse()
            try:
                repaired = self.validate_checkpoint(db, job)
                if repaired and self.role == "cpu":
                    release(db, job, "queued")
                elif not self.enough_space():
                    db.execute(
                        "UPDATE jobs SET desired='pause' WHERE id=? AND token=?",
                        (job["id"], job["token"]),
                    )
                    release(db, job, error="disk_space_low: less than 5GiB free")
                elif self.role == "gpu":
                    self.gpu_unit(db, job)
                    self.streak = self.streak + 1 if job["kind"] == "preview" else 0
                else:
                    self.cpu_unit(db, job)
            except Exception as exc:
                log.exception("Work unit failed")
                try:
                    release(db, job, "failed", str(exc))
                except DomainError:
                    pass
            finally:
                self.job = None
                self.pulse()
            return True

    def run(self):
        initialize(self.settings)
        heartbeat = threading.Thread(target=self.heartbeat, daemon=True)
        heartbeat.start()
        try:
            if self.role == "gpu":
                while not self.stopping.is_set():
                    try:
                        with GPULock():
                            self.loop()
                        break
                    except RuntimeError as exc:
                        if "occupied" not in str(exc):
                            raise
                        self.status.update(state="external-gpu-owner", error=str(exc))
                        self.pulse()
                        self.stopping.wait(3)
            else:
                # A local flock also prevents two CPU exporters from competing.
                with GPULock(self.settings.state / "cpu.lock"):
                    self.loop()
        finally:
            self.stopping.set()
            if hasattr(self.engine, "close"):
                self.engine.close()
            self.heartbeat_stop.set()
            heartbeat.join(timeout=3)
            self.status["state"] = "stopped"
            self.pulse()

    def loop(self):
        while not self.stopping.is_set():
            if not self.tick():
                self.stopping.wait(1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--role", choices=["gpu", "cpu"], default="gpu")
    parser.add_argument("--batch-size", type=int, default=4)
    args = parser.parse_args()
    if not 1 <= args.batch_size <= 16:
        parser.error("batch-size must be 1–16")
    settings = Settings(batch_size=args.batch_size)
    worker = Worker(settings, args.role)
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: worker.stopping.set())
    logging.basicConfig(level=logging.INFO)
    worker.run()


if __name__ == "__main__":
    main()
