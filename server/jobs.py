from __future__ import annotations

import json
import time
import uuid

from tts.common import digest, file_digest, has_spoken_content, NON_SPOKEN_REASON
from .catalog import DomainError, materialize_inline, resolve
from .config import ENGINE
from .db import encoded, event, transaction
from .presets import snapshot

TERMINAL = {"succeeded", "partial_failed", "failed", "cancelled"}


def skipped_segment(part):
    return {
        "segment_id": part["id"],
        **{
            key: part[key]
            for key in (
                "sentence_index",
                "sentence_start_utf16",
                "sentence_end_utf16",
                "start_utf16",
                "end_utf16",
                "original_text",
                "spoken_text",
            )
        },
        "reason": NON_SPOKEN_REASON,
    }


def verified_file(settings, path, sha):
    candidate = (settings.audio / path).resolve()
    if not candidate.is_relative_to(settings.audio.resolve()):
        return False
    try:
        return candidate.is_file() and file_digest(candidate) == sha
    except OSError:
        return False


def cached_audio(db, settings, variant, segment_id, generation_id=None):
    rows = db.execute(
        """SELECT * FROM segment_audio WHERE variant_id=? AND segment_id=? AND ready=1
        AND (? IS NULL OR generation_id=?) ORDER BY created DESC""",
        (variant, segment_id, generation_id, generation_id),
    ).fetchall()
    for row in rows:
        if verified_file(settings, row["path"], row["sha256"]):
            return row
        db.execute("UPDATE segment_audio SET ready=0 WHERE id=?", (row["id"],))
    return None


def submit(db, settings, request, key, kind="synthesis", parent=None, frozen=None):
    if not key or len(key) > 200:
        raise DomainError("需要 1–200 字符的 Idempotency-Key")
    request_hash = digest(
        encoded({"kind": kind, "request": request, "parent": parent and parent["id"]})
    )
    existing = db.execute(
        "SELECT * FROM jobs WHERE idempotency_key=?", (key,)
    ).fetchone()
    if existing:
        if existing["request_hash"] != request_hash:
            raise DomainError("幂等键已用于不同请求", 409)
        return existing["id"]
    config = frozen or snapshot(
        db, settings, request["voice_preset_version_id"], request.get("export")
    )
    if config["generation"].get("engine") != ENGINE:
        raise DomainError("旧合成引擎已停用，请选择 Audio8 参考音色重新创建任务", 409)
    if request.get("inline_chapters"):
        if {c.get("id") for c in request["inline_chapters"]} != set(
            request["chapter_ids"]
        ) or any(
            c.get("book_id") != request["book_id"] for c in request["inline_chapters"]
        ):
            raise DomainError("本地章节 payload 必须与选中的书籍和章节完全一致")
        materialize_inline(db, request["inline_chapters"])
    selected = resolve(db, request["book_id"], chapter_ids=request["chapter_ids"])
    chapters = selected["chapters"]
    if kind == "preview" and len(chapters) != 1:
        raise DomainError("试听请选择一个章节")
    variant = digest(encoded(config["generation"]))
    identifier = uuid.uuid4().hex
    generation = (
        parent["generation_id"]
        if parent
        else (uuid.uuid4().hex if request.get("mode") == "new-version" else "reuse")
    )
    chapter_data = []
    frozen_revisions = None
    if parent:
        frozen_revisions = {
            r["chapter_id"]: r["revision_id"]
            for r in db.execute(
                """SELECT jc.revision_id,r.chapter_id
            FROM job_chapters jc JOIN revisions r ON r.id=jc.revision_id WHERE jc.job_id=?""",
                (parent["id"],),
            )
        }
    for chapter in chapters:
        revision = (
            frozen_revisions[chapter["id"]]
            if frozen_revisions
            else chapter["current_revision"]
        )
        parts = db.execute(
            "SELECT * FROM segments WHERE revision_id=? AND depth=0 ORDER BY segment_index",
            (revision,),
        ).fetchall()
        if kind == "preview":
            chosen, chars = [], 0
            for part in parts:
                chosen.append(part)
                if has_spoken_content(part["spoken_text"]):
                    chars += len(part["spoken_text"])
                if (
                    chars >= 160
                ):  # Approximation only; final duration comes from actual audio.
                    break
            parts = chosen
        prepared_parts = [
            (
                part,
                cached_audio(
                    db,
                    settings,
                    variant,
                    part["id"],
                    None if generation == "reuse" else generation,
                )
                if has_spoken_content(part["spoken_text"])
                else None,
            )
            for part in parts
        ]
        chapter_data.append((revision, prepared_parts))
    with transaction(db):
        # Repeat under the writer lock to close concurrent submit races.
        existing = db.execute(
            "SELECT id,request_hash FROM jobs WHERE idempotency_key=?", (key,)
        ).fetchone()
        if existing:
            if existing["request_hash"] != request_hash:
                raise DomainError("幂等键已用于不同请求", 409)
            return existing["id"]
        now = time.time()
        db.execute(
            """INSERT INTO jobs(id,kind,request_json,request_hash,idempotency_key,config_json,variant_id,
            generation_id,state,created,updated,parent_job_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                identifier,
                kind,
                encoded(request),
                request_hash,
                key,
                encoded(config),
                variant,
                generation,
                "queued",
                now,
                now,
                parent["id"] if parent else None,
            ),
        )
        position = 0
        for index, (revision, parts) in enumerate(chapter_data):
            db.execute(
                "INSERT INTO job_chapters(job_id,revision_id,position) VALUES (?,?,?)",
                (identifier, revision, index),
            )
            for part, audio in parts:
                db.execute(
                    """INSERT INTO job_items(job_id,segment_id,position,state,audio_id,cache_hit)
                    VALUES (?,?,?,?,?,?)""",
                    (
                        identifier,
                        part["id"],
                        position,
                        "skipped"
                        if not has_spoken_content(part["spoken_text"])
                        else "ready"
                        if audio
                        else "queued",
                        audio["id"] if audio else None,
                        int(audio is not None),
                    ),
                )
                position += 1
        if not db.execute(
            "SELECT 1 FROM job_items WHERE job_id=? AND state='queued'", (identifier,)
        ).fetchone():
            db.execute(
                "UPDATE jobs SET state='exporting',phase='exporting' WHERE id=?",
                (identifier,),
            )
        event(db, identifier, "accepted", {"segments": position, "kind": kind})
    return identifier


def detail(db, identifier, after=0):
    row = db.execute("SELECT * FROM jobs WHERE id=?", (identifier,)).fetchone()
    if not row:
        raise DomainError("任务不存在", 404)
    result = dict(row)
    for name in ("request", "config"):
        result[name] = json.loads(result.pop(name + "_json"))
    result.pop("idempotency_key", None)
    result.pop("request_hash", None)
    result.pop("token", None)
    result["counts"] = dict(
        db.execute(
            """SELECT count(*) AS total,
        coalesce(sum(state='ready'),0) AS completed,coalesce(sum(state='failed'),0) AS failed,
        coalesce(sum(state='skipped'),0) AS skipped,
        coalesce(sum(cache_hit),0) AS reused FROM job_items WHERE job_id=?""",
            (identifier,),
        ).fetchone()
    )
    result["skipped_segments"] = [
        skipped_segment(r)
        for r in db.execute(
            """SELECT s.* FROM job_items ji JOIN segments s ON s.id=ji.segment_id
            WHERE ji.job_id=? AND ji.state='skipped' ORDER BY ji.position""",
            (identifier,),
        )
    ]
    result["chapters"] = [
        dict(r)
        for r in db.execute(
            """SELECT jc.*,r.chapter_id,c.title,c.chapter_number,c.chapter_index
        FROM job_chapters jc JOIN revisions r ON r.id=jc.revision_id JOIN chapters c ON c.id=r.chapter_id
        WHERE jc.job_id=? ORDER BY jc.position""",
            (identifier,),
        )
    ]
    result["errors"] = [
        dict(r)
        for r in db.execute(
            """SELECT ji.segment_id,s.original_text,s.start_utf16,s.end_utf16,
        s.revision_id,ji.attempts,ji.error FROM job_items ji JOIN segments s ON s.id=ji.segment_id
        WHERE ji.job_id=? AND ji.error IS NOT NULL ORDER BY ji.position LIMIT 100""",
            (identifier,),
        )
    ]
    events = [
        dict(r)
        for r in db.execute(
            "SELECT seq,created,event,data_json FROM events WHERE job_id=? AND seq>? ORDER BY seq LIMIT 100",
            (identifier, after),
        )
    ]
    for item in events:
        item["data"] = json.loads(item.pop("data_json"))
    result["events"] = events
    return result


def action(db, settings, identifier, operation, key=None):
    row = db.execute("SELECT * FROM jobs WHERE id=?", (identifier,)).fetchone()
    if not row:
        raise DomainError("任务不存在", 404)
    if operation == "retry-failed":
        if row["state"] not in {"failed", "partial_failed"} or row["kind"] == "bundle":
            raise DomainError("仅失败的合成任务可重试", 409)
        return submit(
            db,
            settings,
            json.loads(row["request_json"]),
            key,
            row["kind"],
            row,
            json.loads(row["config_json"]),
        )
    with transaction(db):
        row = db.execute("SELECT * FROM jobs WHERE id=?", (identifier,)).fetchone()
        if operation == "pause":
            if row["state"] in TERMINAL or row["desired"] == "cancel":
                raise DomainError("终态或取消中的任务不能暂停", 409)
            state = "pausing" if row["owner"] else "paused"
            db.execute(
                "UPDATE jobs SET desired='pause',state=?,updated=? WHERE id=?",
                (state, time.time(), identifier),
            )
        elif operation == "resume":
            if (
                row["state"] in {"queued", "running", "exporting"}
                and row["desired"] == "run"
            ):
                return identifier
            if row["state"] not in {"paused", "queued"}:
                raise DomainError("任务尚未暂停", 409)
            state = "exporting" if row["phase"] == "exporting" else "queued"
            db.execute(
                "UPDATE jobs SET desired='run',state=?,error=NULL,updated=? WHERE id=?",
                (state, time.time(), identifier),
            )
        elif operation == "cancel":
            if row["state"] in TERMINAL and row["state"] != "cancelled":
                raise DomainError("终态任务不能取消", 409)
            state = "cancelling" if row["owner"] else "cancelled"
            db.execute(
                "UPDATE jobs SET desired='cancel',state=?,updated=? WHERE id=?",
                (state, time.time(), identifier),
            )
        else:
            raise DomainError("未知控制操作")
        event(db, identifier, operation)
    return identifier


def recover(db):
    now = time.time()
    with transaction(db):
        for row in db.execute(
            "SELECT * FROM jobs WHERE owner IS NOT NULL AND lease_until<?", (now,)
        ).fetchall():
            state = {"pause": "paused", "cancel": "cancelled"}.get(
                row["desired"], "exporting" if row["phase"] == "exporting" else "queued"
            )
            db.execute(
                "UPDATE jobs SET owner=NULL,token=NULL,lease_until=NULL,state=?,updated=? WHERE id=?",
                (state, now, row["id"]),
            )
            event(db, row["id"], "lease-recovered")


def claim(db, settings, owner, role, preview_streak=0):
    recover(db)
    with transaction(db):
        if role == "cpu":
            row = db.execute("""SELECT * FROM jobs WHERE owner IS NULL AND desired='run'
                AND (state='exporting' OR (kind='bundle' AND state='queued')) ORDER BY created LIMIT 1""").fetchone()
        else:
            order = (
                "CASE WHEN kind='preview' THEN 0 ELSE 1 END"
                if preview_streak < 3
                else "CASE WHEN kind='preview' THEN 1 ELSE 0 END"
            )
            row = db.execute(f"""SELECT * FROM jobs WHERE owner IS NULL AND desired='run' AND kind IN ('synthesis','preview')
                AND state IN ('queued','running') ORDER BY {order},created LIMIT 1""").fetchone()
        if not row:
            return None
        token = uuid.uuid4().hex
        phase = "exporting" if role == "cpu" else "synthesis"
        db.execute(
            "UPDATE jobs SET owner=?,token=?,lease_until=?,state=?,phase=?,updated=? WHERE id=?",
            (
                owner,
                token,
                time.time() + settings.lease_seconds,
                phase if role == "cpu" else "running",
                phase,
                time.time(),
                row["id"],
            ),
        )
        return dict(
            db.execute("SELECT * FROM jobs WHERE id=?", (row["id"],)).fetchone()
        )


def assert_owner(db, job):
    row = db.execute(
        "SELECT token,lease_until FROM jobs WHERE id=?", (job["id"],)
    ).fetchone()
    if (
        not row
        or row["token"] != job["token"]
        or (row["lease_until"] or 0) < time.time()
    ):
        raise DomainError("工作租约已过期", 409)


def release(db, job, state=None, error=None):
    with transaction(db):
        assert_owner(db, job)
        current = db.execute(
            "SELECT desired,phase FROM jobs WHERE id=?", (job["id"],)
        ).fetchone()
        target = {"pause": "paused", "cancel": "cancelled"}.get(
            current["desired"], state or "queued"
        )
        db.execute(
            "UPDATE jobs SET state=?,owner=NULL,token=NULL,lease_until=NULL,error=?,updated=? WHERE id=?",
            (target, error, time.time(), job["id"]),
        )
        event(db, job["id"], target, {"error": error} if error else {})
