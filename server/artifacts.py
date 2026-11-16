from __future__ import annotations

import json
from pathlib import Path
import re
import time
import uuid
import zipfile

from tts.audio import atomic_bytes, render, LOUDNESS_VERSION
from tts.common import digest, file_digest, has_spoken_content
from .catalog import DomainError
from .db import encoded, transaction, event
from .jobs import verified_file, assert_owner, skipped_segment


def available(db, settings, identifier):
    row = db.execute(
        "SELECT * FROM artifacts WHERE id=? AND ready=1", (identifier,)
    ).fetchone()
    if not row:
        raise DomainError("产物未就绪或不存在", 404)
    if not verified_file(
        settings, row["audio_path"], row["audio_sha256"]
    ) or not verified_file(settings, row["manifest_path"], row["manifest_sha256"]):
        db.execute("UPDATE artifacts SET ready=0 WHERE id=?", (identifier,))
        raise DomainError("产物文件丢失或校验失败", 410)
    return row


def export_chapter(db, settings, job, revision_id):
    config = json.loads(job["config_json"])
    chapter = dict(
        db.execute(
            """SELECT c.*,r.id AS revision_id,r.source_sha256,b.title AS book_title
        FROM revisions r JOIN chapters c ON c.id=r.chapter_id JOIN books b ON b.id=c.book_id WHERE r.id=?""",
            (revision_id,),
        ).fetchone()
    )
    parts = db.execute(
        """SELECT ji.state,ji.audio_id,s.*,a.path,a.sha256,a.ready AS audio_ready
        FROM job_items ji JOIN segments s ON s.id=ji.segment_id LEFT JOIN segment_audio a ON a.id=ji.audio_id
        WHERE ji.job_id=? AND s.revision_id=? ORDER BY ji.position""",
        (job["id"], revision_id),
    ).fetchall()
    if not parts:
        raise DomainError("章节存在缺失、失败或损坏片段")
    for part in parts:
        if part["state"] == "skipped":
            if has_spoken_content(part["spoken_text"]):
                raise DomainError("朗读正文不能标记为跳过")
        elif (
            part["state"] != "ready"
            or not part["audio_ready"]
            or not verified_file(settings, part["path"], part["sha256"])
        ):
            raise DomainError("章节存在缺失、失败或损坏片段")
    skipped = [skipped_segment(p) for p in parts if p["state"] == "skipped"]
    parts = [p for p in parts if p["state"] == "ready"]
    if not parts:
        raise DomainError("章节没有需要朗读的正文")
    complete = job["kind"] != "preview"
    export_key = digest(
        encoded(
            {
                "clips": [(p["audio_id"], p["sha256"]) for p in parts],
                "export": config["export"],
                "index_version": 2,
                "encoder": "aac64k-" + LOUDNESS_VERSION
                if config["export"].get("normalize_loudness", False)
                else "aac64k-v1",
                "complete": complete,
                **({"skipped_segments": skipped} if skipped else {}),
            }
        )
    )
    for old in db.execute(
        "SELECT id FROM artifacts WHERE export_key=? AND ready=1", (export_key,)
    ).fetchall():
        try:
            available(db, settings, old["id"])
            return old["id"]
        except DomainError:
            pass
    identifier = uuid.uuid4().hex
    folder = Path(chapter["book_id"]) / job["variant_id"] / chapter["id"] / identifier
    target = settings.audio / folder / "chapter.m4a"
    clips = [
        {**dict(p), "segment_id": p["id"], "path": settings.audio / p["path"]}
        for p in parts
    ]
    timing = render(
        clips,
        target,
        config["export"]["speed"],
        config["export"]["pause_ms"],
        chapter["title"],
        chapter["book_title"],
        normalize_loudness=config["export"].get("normalize_loudness", False),
    )
    manifest = {
        "schema_version": 2,
        "artifact_id": identifier,
        "book_id": chapter["book_id"],
        "chapter_id": chapter["id"],
        "source_revision": revision_id,
        "source_sha256": chapter["source_sha256"],
        "title": chapter["title"],
        "chapter_number": chapter["chapter_number"],
        "chapter_index": chapter["chapter_index"],
        "offsets": "UTF-16 into Android Chapter.text",
        "complete": complete,
        "skipped_segments": skipped,
        "variant_id": job["variant_id"],
        "profile": config["generation"],
        "export": config["export"],
        "audio_file": "chapter.m4a",
        "speed": config["export"]["speed"],
        **timing,
    }
    manifest_path = target.with_suffix(".json")
    atomic_bytes(manifest_path, encoded(manifest).encode())
    with transaction(db):
        assert_owner(db, job)
        db.execute(
            "INSERT INTO artifacts VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            (
                identifier,
                revision_id,
                export_key,
                str(folder / target.name),
                str(folder / manifest_path.name),
                timing["audio_sha256"],
                file_digest(manifest_path),
                int(complete),
                timing["duration_ms"],
                1,
                time.time(),
            ),
        )
        event(
            db,
            job["id"],
            "artifact-ready",
            {"artifact_id": identifier, "revision_id": revision_id},
        )
    return identifier


def create_bundle(db, settings, identifiers, key):
    if not key or len(key) > 200 or not identifiers or len(identifiers) > 2000:
        raise DomainError("需要有效幂等键及 1–2000 个产物")
    identifiers = list(dict.fromkeys(identifiers))
    request = {"artifact_ids": identifiers}
    hashed = digest(encoded(request))
    previous = db.execute(
        "SELECT id,request_hash FROM jobs WHERE idempotency_key=?", (key,)
    ).fetchone()
    if previous:
        if previous["request_hash"] != hashed:
            raise DomainError("幂等键冲突", 409)
        return previous["id"]
    missing = []
    for identifier in identifiers:
        try:
            available(db, settings, identifier)
        except DomainError:
            missing.append(identifier)
    if missing:
        raise DomainError("打包选择中存在不可用产物", details=missing)
    identifier = uuid.uuid4().hex
    with transaction(db):
        previous = db.execute(
            "SELECT id,request_hash FROM jobs WHERE idempotency_key=?", (key,)
        ).fetchone()
        if previous:
            if previous["request_hash"] != hashed:
                raise DomainError("幂等键冲突", 409)
            return previous["id"]
        now = time.time()
        db.execute(
            """INSERT INTO jobs(id,kind,request_json,request_hash,idempotency_key,config_json,variant_id,generation_id,
            state,created,updated) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            (
                identifier,
                "bundle",
                encoded(request),
                hashed,
                key,
                "{}",
                "",
                "",
                "queued",
                now,
                now,
            ),
        )
        db.execute("INSERT INTO bundles(job_id) VALUES (?)", (identifier,))
        event(db, identifier, "accepted")
    return identifier


def build_bundle(db, settings, job):
    identifiers = json.loads(job["request_json"])["artifact_ids"]
    relative = Path("bundles") / f"{job['id']}.zip"
    target = settings.audio / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(".pending")
    entries = []
    with zipfile.ZipFile(
        temporary, "w", compression=zipfile.ZIP_STORED, allowZip64=True
    ) as archive:
        for identifier in identifiers:
            artifact = available(db, settings, identifier)
            manifest = json.loads(
                (settings.audio / artifact["manifest_path"]).read_text()
            )
            title = re.sub(r"[\\/:*?\"<>|\x00-\x1f]", "_", manifest["title"])[:80]
            stem = f"{title}-{identifier}"
            archive.write(settings.audio / artifact["audio_path"], stem + ".m4a")
            archive.write(settings.audio / artifact["manifest_path"], stem + ".json")
            entries.append(
                {
                    "artifact_id": identifier,
                    "chapter_id": manifest["chapter_id"],
                    "title": manifest["title"],
                    "audio": stem + ".m4a",
                    "manifest": stem + ".json",
                    "audio_sha256": artifact["audio_sha256"],
                    "manifest_sha256": artifact["manifest_sha256"],
                }
            )
        archive.writestr(
            "catalogue.json", encoded({"schema_version": 1, "entries": entries})
        )
    with temporary.open("rb") as handle:
        __import__("os").fsync(handle.fileno())
    temporary.replace(target)
    sha = file_digest(target)
    with transaction(db):
        assert_owner(db, job)
        db.execute(
            "UPDATE bundles SET path=?,sha256=?,expires=? WHERE job_id=?",
            (str(relative), sha, time.time() + settings.bundle_ttl, job["id"]),
        )


def expire_bundles(db, settings):
    for row in db.execute(
        "SELECT * FROM bundles WHERE expires<? AND path IS NOT NULL", (time.time(),)
    ).fetchall():
        path = (settings.audio / row["path"]).resolve()
        if path.is_relative_to(settings.audio.resolve() / "bundles"):
            path.unlink(missing_ok=True)
            db.execute("UPDATE bundles SET path=NULL WHERE job_id=?", (row["job_id"],))
