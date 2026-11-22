from __future__ import annotations

from contextlib import closing
import json
import secrets
import shutil
import sqlite3
import subprocess
import time
from typing import Any, Literal

from fastapi import FastAPI, Depends, Header, Query, Request
from fastapi import File, Form, UploadFile
from fastapi.responses import JSONResponse, FileResponse
from pydantic import BaseModel, ConfigDict, Field

from .artifacts import available, create_bundle
from .catalog import DomainError, materialize_inline, resolve
from .config import Settings
from .db import initialize, connect, transaction
from .jobs import submit, detail, action, cached_audio, verified_file
from .presets import capabilities, create_preset, snapshot
from .narration import chapter_parts
from .voices import BUILTIN_PRESET_IDS, DEFAULT_PRESET_ID, NARRATOR_INSTRUCTION, display_instruction
from tts.common import digest, has_spoken_content
from .db import encoded
from tts.audio import atomic_bytes, media_duration
from tts.common import file_digest
import os
import uuid


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Selection(StrictModel):
    book_id: str
    expression: str = ""
    chapter_ids: list[str] | None = None


class JobRequest(StrictModel):
    book_id: str
    chapter_ids: list[str] = Field(min_length=1, max_length=2000)
    voice_preset_version_id: str = DEFAULT_PRESET_ID
    export: dict[str, Any] | None = None
    mode: Literal["reuse", "new-version"] = "reuse"
    inline_chapters: list[dict[str, Any]] | None = None


class PresetRequest(StrictModel):
    name: str
    speaker: str
    instruct: str
    export: dict[str, Any] = Field(default_factory=dict)


class Action(StrictModel):
    action: Literal["pause", "resume", "cancel", "retry-failed"]


class BundleRequest(StrictModel):
    artifact_ids: list[str] = Field(min_length=1, max_length=2000)


class PreencodedReference(StrictModel):
    name: str = Field(min_length=1,max_length=100)
    ref_text: str = Field(default='',max_length=3000)
    audio_base64: str = Field(min_length=1,max_length=4*1024*1024)
    codes_base64: str = Field(min_length=1,max_length=200000)
    audio_sha256: str = Field(pattern=r'^[a-f0-9]{64}$')
    codes_sha256: str = Field(pattern=r'^[a-f0-9]{64}$')
    duration_ms: int | None = None


def create_app(settings: Settings):
    initialize(settings)
    app = FastAPI(
        title="山页语音工作台", version="1.0.0", docs_url=None, redoc_url=None
    )
    token = settings.token_file.read_text().strip()
    local_tts_manifest_cache: dict[str, Any] = {}

    def local_tts_manifest() -> dict[str, Any]:
        model_files = [settings.zonos_model_path / "backbone-ptq.gguf", settings.zonos_model_path / "zac-keep2.gguf"]
        entries: list[dict[str, Any]] = []
        signatures: list[str] = []
        for path in model_files:
            if not path.is_file():
                continue
            digest_value = file_digest(path)
            signatures.append(f"{path.name}:{path.stat().st_size}:{digest_value}")
            entries.append({"name": path.name, "path": path.name, "bytes": path.stat().st_size, "sha256": digest_value})
        voices: list[dict[str, Any]] = []
        voices_file = settings.zonos_voice_path / "voices.json"
        try:
            published = json.loads(voices_file.read_text()).get("voices", [])
        except (OSError, ValueError, AttributeError):
            published = []
        for voice in published:
            relative = voice.get("codes_path")
            if not isinstance(relative, str) or not relative.startswith("zonos3-voices/"):
                continue
            path = settings.audio / relative
            if not path.is_file():
                continue
            digest_value = file_digest(path)
            speaker = str(voice.get("speaker", ""))
            safe_id = ("clone-" + speaker.removeprefix("clone:")) if speaker.startswith("clone:") else (
                "zonos3-haoran-v1" if speaker == "zonos:haoran" else speaker.replace("/", "_"))
            voices.append({"id": safe_id, "speaker": speaker,
                           "name": voice.get("name", safe_id), "codes": relative,
                           "sha256": digest_value})
            entries.append({"name": f"voice:{safe_id}", "path": relative, "bytes": path.stat().st_size,
                            "sha256": digest_value})
            signatures.append(f"{relative}:{path.stat().st_size}:{digest_value}")
        signature = digest(encoded(signatures))
        cached = local_tts_manifest_cache.get("value")
        if cached is not None and local_tts_manifest_cache.get("signature") == signature:
            return cached
        result = {"pack_revision": signature, "engine_revision": "zonos3-mini-ggml-cpu-arm64-v1",
                  "abi": ["arm64-v8a"], "minimum_free_bytes": 256 * 1024 * 1024,
                  "files": entries, "voices": voices}
        local_tts_manifest_cache["signature"] = signature
        local_tts_manifest_cache["value"] = result
        return result

    @app.middleware("http")
    async def authenticate(request: Request, call_next):
        if not secrets.compare_digest(
            request.headers.get("x-service-token", ""), token
        ):
            return JSONResponse(
                {"error": {"message": "需要有效服务凭据"}}, status_code=401
            )
        if request.url.path=='/api/v1/voice-clones/preencoded' and int(request.headers.get('content-length','0'))>5*1024*1024:
            return JSONResponse({'error':{'message':'参考音色数据过大'}},status_code=413)
        return await call_next(request)

    @app.exception_handler(DomainError)
    async def domain_error(_request, exc):
        return JSONResponse(
            {"error": {"message": exc.message, "details": exc.details}},
            status_code=exc.status,
        )

    def database():
        with closing(connect(settings)) as db:
            yield db

    @app.get("/api/v1/health")
    def health(db: Any = Depends(database)):
        runtime = {}
        for row in db.execute("SELECT * FROM runtime"):
            runtime[row["key"]] = {
                **json.loads(row["value_json"]),
                "heartbeat": row["heartbeat"],
                "alive": time.time() - row["heartbeat"] < 10,
            }
        disks = [
            {
                "kind": name,
                "free_bytes": shutil.disk_usage(path).free,
                "total_bytes": shutil.disk_usage(path).total,
            }
            for name, path in (("metadata", settings.state), ("audio", settings.audio))
        ]
        return {
            "api": "ready",
            "workers": runtime,
            "disks": disks,
            "min_free_bytes": settings.min_free_bytes,
            "can_accept_work": all(
                x["free_bytes"] >= settings.min_free_bytes for x in disks
            ),
            "book_count": db.execute("SELECT count(*) FROM books").fetchone()[0],
            "models": {"voice_clone_ready": settings.clone_model_ready},
            "initialization": "explicit-import-only",
        }

    @app.get("/api/v1/books")
    def books(db: Any = Depends(database)):
        return {
            "items": [
                dict(r)
                for r in db.execute(
                    "SELECT b.*,count(c.id) AS chapters FROM books b LEFT JOIN chapters c ON c.book_id=b.id GROUP BY b.id ORDER BY b.title"
                )
            ]
        }

    @app.get("/api/v1/books/{identifier}/chapters")
    def chapters(
        identifier: str,
        db: Any = Depends(database),
        q: str = "",
        offset: int = Query(0, ge=0),
        limit: int = Query(100, ge=1, le=500),
    ):
        where = "c.book_id=? AND (?='' OR c.title LIKE ? OR CAST(c.chapter_number AS TEXT)=?)"
        args = (identifier, q, f"%{q}%", q)
        rows = db.execute(
            f"""SELECT c.*,length(r.canonical_text) AS characters,
            (SELECT count(*) FROM segments s WHERE s.revision_id=r.id AND s.depth=0 AND s.sentence_index>=0) AS segments,
            (SELECT count(*) FROM chapters d WHERE d.book_id=c.book_id AND d.chapter_number=c.chapter_number
              AND (d.chapter_index<c.chapter_index OR (d.chapter_index=c.chapter_index AND d.id<=c.id))) AS same_number_order
            FROM chapters c JOIN revisions r ON r.id=c.current_revision WHERE {where} ORDER BY c.chapter_index,c.id LIMIT ? OFFSET ?""",
            args + (limit, offset),
        ).fetchall()
        result = []
        for row in rows:
            item = dict(row)
            item["artifacts"] = [
                dict(a)
                for a in db.execute(
                    "SELECT id,duration_ms,complete,created FROM artifacts WHERE revision_id=? AND ready=1 ORDER BY created DESC",
                    (row["current_revision"],),
                )
            ]
            result.append(item)
        return {
            "items": result,
            "total": db.execute(
                f"SELECT count(*) FROM chapters c WHERE {where}", args
            ).fetchone()[0],
        }

    @app.get("/api/v1/revisions/{identifier}")
    def revision(identifier: str, db: Any = Depends(database)):
        row = db.execute("SELECT * FROM revisions WHERE id=?", (identifier,)).fetchone()
        if not row:
            raise DomainError("原文版本不存在", 404)
        return {
            **dict(row),
            "segments": [
                dict(s)
                for s in db.execute(
                    "SELECT * FROM segments WHERE revision_id=? AND depth=0 AND sentence_index>=0 ORDER BY segment_index",
                    (identifier,),
                )
            ],
        }

    @app.post("/api/v1/chapter-selections/resolve")
    def selections(body: Selection, db: Any = Depends(database)):
        result = resolve(db, body.book_id, body.expression, body.chapter_ids)
        for chapter in result["chapters"]:
            chapter["artifacts"] = [
                dict(r)
                for r in db.execute(
                    "SELECT id,complete,created FROM artifacts WHERE revision_id=? AND ready=1 ORDER BY created DESC",
                    (chapter["current_revision"],),
                )
            ]
        return result

    @app.get("/api/v1/voices")
    def voices():
        return capabilities(settings)

    @app.get("/api/v1/local-tts/manifest")
    def local_tts_manifest_route():
        return local_tts_manifest()

    @app.get("/api/v1/local-tts/files/{file_path:path}")
    def local_tts_file(file_path: str):
        manifest = local_tts_manifest()
        entry = next((item for item in manifest["files"] if item["path"] == file_path), None)
        if entry is None:
            raise DomainError("本地模型文件不存在", 404)
        target = (settings.zonos_model_path / file_path if "/" not in file_path
                  else settings.audio / file_path).resolve()
        allowed_roots = (settings.zonos_model_path.resolve(), settings.audio.resolve())
        if not target.is_relative_to(allowed_roots[0]) and not target.is_relative_to(allowed_roots[1]):
            raise DomainError("本地模型文件路径无效", 403)
        if not target.is_file() or target.stat().st_size != entry["bytes"]:
            raise DomainError("本地模型文件尚未就绪", 503)
        return FileResponse(target, media_type="application/octet-stream",
                            headers={"ETag": '"' + entry["sha256"] + '"', "Accept-Ranges": "bytes"})

    @app.post("/api/v1/voice-clones", status_code=201)
    async def voice_clone(
        reference: UploadFile = File(...),
        ref_text: str = Form(""),
        x_vector_only_mode: bool = Form(False),
        db: Any = Depends(database),
    ):
        raise DomainError('请通过本地语音工作台上传参考录音，由本机完成 ZONOS3 编码后创建音色',503)

    @app.post('/api/v1/voice-clones/preencoded',status_code=201)
    def preencoded_voice(body: PreencodedReference,db: Any = Depends(database)):
        from .voice_publish import publish_reference
        return publish_reference(db,settings,body.model_dump())

    @app.get("/api/v1/presets")
    def presets(db: Any = Depends(database)):
        items = []
        try:
            published={v['speaker'] for v in json.loads((settings.zonos_voice_path/'voices.json').read_text())['voices']}
        except (OSError,ValueError,KeyError):
            published=set()
        for row in db.execute(
            "SELECT * FROM presets ORDER BY confirmed DESC,created DESC"
        ):
            if row['speaker'] not in published:
                continue
            item = dict(row)
            item["instruct"] = display_instruction(item["instruct"])
            item["export"] = json.loads(item.pop("export_json"))
            item["builtin"] = item["id"] in BUILTIN_PRESET_IDS
            item["voice"] = {
                "label": item["name"],
                "description": "ZONOS3 · 预置男声" if item["builtin"] else "ZONOS3 · 预先克隆",
                "native_language": "中文",
                "preset_id": item["id"],
                "speaker": item["speaker"],
            }
            items.append(item)
        return {"items": items}

    @app.post("/api/v1/presets", status_code=201)
    def add_preset(body: PresetRequest, db: Any = Depends(database)):
        with transaction(db):
            identifier = create_preset(db, settings, **body.model_dump())
        return {"id": identifier}

    @app.post("/api/v1/jobs/estimate")
    def estimate(body: JobRequest, db: Any = Depends(database)):
        if body.inline_chapters:
            # Estimates never add a local book to the persisted server inputs.
            with closing(
                sqlite3.connect(":memory:", isolation_level=None)
            ) as transient:
                transient.row_factory = sqlite3.Row
                db.backup(transient)
                materialize_inline(transient, body.inline_chapters)
                return estimate(
                    body.model_copy(update={"inline_chapters": None}), transient
                )
        selected = resolve(db, body.book_id, chapter_ids=body.chapter_ids)
        config = snapshot(db, settings, body.voice_preset_version_id, body.export)
        variant = digest(encoded(config["generation"]))
        total = reused = chars = skipped = 0
        for chapter in selected["chapters"]:
            for part in chapter_parts(db, chapter["current_revision"], chapter["title"]):
                total += 1
                if not has_spoken_content(part["spoken_text"]):
                    skipped += 1
                    continue
                chars += len(part["spoken_text"])
                if body.mode == "reuse" and cached_audio(
                    db, settings, variant, part["id"]
                ):
                    reused += 1
        measurements = db.execute("""SELECT count(*) AS n,sum(elapsed) AS elapsed,sum(samples)/24000.0 AS audio_seconds
            FROM (SELECT elapsed,samples FROM segment_audio WHERE ready=1 AND elapsed>0 ORDER BY created DESC LIMIT 100)""").fetchone()
        seconds = None
        if measurements["n"] >= 5:
            seconds = (
                (total - reused - skipped) * measurements["elapsed"] / measurements["n"]
            )
        return {
            "segments": total,
            "reused": reused,
            "missing": total - reused - skipped,
            "skipped": skipped,
            "characters": chars,
            "estimated_seconds": seconds,
            "estimate_source": "recent-segment-measurements"
            if seconds is not None
            else None,
            "estimated_bytes": int(chars * 0.3 * 24000 * 2 + chars * 0.3 * 8000),
            "space_estimate_source": "conservative-0.3-seconds-per-character-pcm-and-aac",
            "guaranteed": False,
        }

    @app.post("/api/v1/jobs", status_code=202)
    def create_job(
        body: JobRequest, db: Any = Depends(database), idempotency_key: str = Header()
    ):
        return {"id": submit(db, settings, body.model_dump(), idempotency_key)}

    @app.post("/api/v1/previews", status_code=202)
    def preview(
        body: JobRequest, db: Any = Depends(database), idempotency_key: str = Header()
    ):
        return {
            "id": submit(db, settings, body.model_dump(), idempotency_key, "preview")
        }

    @app.get("/api/v1/jobs")
    def list_jobs(
        db: Any = Depends(database),
        offset: int = Query(0, ge=0),
        limit: int = Query(30, ge=1, le=100),
    ):
        ids = [
            r[0]
            for r in db.execute(
                "SELECT id FROM jobs ORDER BY created DESC LIMIT ? OFFSET ?",
                (limit, offset),
            )
        ]
        return {
            "items": [detail(db, i) for i in ids],
            "total": db.execute("SELECT count(*) FROM jobs").fetchone()[0],
        }

    @app.get("/api/v1/jobs/{identifier}")
    def job(identifier: str, db: Any = Depends(database), after: int = Query(0, ge=0)):
        return detail(db, identifier, after)

    @app.post("/api/v1/jobs/{identifier}/actions")
    def control(
        identifier: str,
        body: Action,
        db: Any = Depends(database),
        idempotency_key: str | None = Header(None),
    ):
        return {"id": action(db, settings, identifier, body.action, idempotency_key)}

    @app.get("/api/v1/artifacts/{identifier}/manifest")
    def manifest(identifier: str, db: Any = Depends(database)):
        row = available(db, settings, identifier)
        return FileResponse(
            settings.audio / row["manifest_path"],
            media_type="application/json",
            filename=f"{identifier}.json",
        )

    @app.get("/api/v1/artifacts/{identifier}/audio")
    def audio(identifier: str, db: Any = Depends(database), download: bool = False):
        row = available(db, settings, identifier)
        return FileResponse(
            settings.audio / row["audio_path"],
            media_type="audio/mp4",
            filename=f"{identifier}.m4a" if download else None,
            headers={"ETag": '"' + row["audio_sha256"] + '"'},
        )

    @app.post("/api/v1/download-bundles", status_code=202)
    def bundle(
        body: BundleRequest,
        db: Any = Depends(database),
        idempotency_key: str = Header(),
    ):
        return {"id": create_bundle(db, settings, body.artifact_ids, idempotency_key)}

    @app.get("/api/v1/download-bundles/{identifier}")
    def bundle_status(identifier: str, db: Any = Depends(database)):
        row = db.execute(
            "SELECT * FROM bundles WHERE job_id=?", (identifier,)
        ).fetchone()
        if not row:
            raise DomainError("下载包不存在", 404)
        return {
            **detail(db, identifier),
            "bundle": {k: row[k] for k in ("sha256", "expires")},
            "download_ready": bool(row["path"] and (row["expires"] or 0) > time.time()),
        }

    @app.get("/api/v1/download-bundles/{identifier}/file")
    def bundle_file(identifier: str, db: Any = Depends(database)):
        row = db.execute(
            "SELECT * FROM bundles WHERE job_id=?", (identifier,)
        ).fetchone()
        if not row or not row["path"] or (row["expires"] or 0) < time.time():
            raise DomainError("下载包未就绪或已过期", 410)
        if not verified_file(settings, row["path"], row["sha256"]):
            raise DomainError("下载包校验失败", 410)
        return FileResponse(
            settings.audio / row["path"],
            media_type="application/zip",
            filename=f"shanye-{identifier}.zip",
        )

    return app


def app_factory():
    return create_app(Settings())
