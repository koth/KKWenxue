from __future__ import annotations

import argparse
from contextlib import closing
import json
from pathlib import Path
import shutil
import sqlite3
import tempfile
import time
import uuid

from tts.audio import atomic_bytes, media_duration
from tts.common import digest, file_digest, has_spoken_content
from .catalog import import_sqlite, DomainError
from .config import Settings
from .db import initialize, backup, connect, transaction, encoded
from .jobs import verified_file, skipped_segment
from .presets import snapshot, create_preset


def create_demo(path: Path):
    """Original test material; never discovers or uploads a user's book."""
    if path.exists():
        raise ValueError("Demo destination exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    with closing(sqlite3.connect(path)) as db:
        db.executescript("""CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL);
            CREATE TABLE chapters(id TEXT PRIMARY KEY,book_id TEXT NOT NULL,chapter_index INTEGER NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL);""")
        db.execute("INSERT INTO books VALUES ('studio-demo','山页试音 · 雨后小镇')")
        db.executemany(
            "INSERT INTO chapters VALUES (?,?,?,?,?)",
            [
                (
                    "rain-1",
                    "studio-demo",
                    0,
                    "第1章 雨后",
                    "雨停了，窗台上还留着几滴水。林舟推开书店的门，闻到旧纸张和木头的气息。\n\n老人抬起头，平静地说：“你要找的那本书，就在最里面。”",
                ),
                (
                    "rain-2",
                    "studio-demo",
                    1,
                    "第2章 灯下",
                    "夜色慢慢落下来。街角的灯亮了，照着青石路上的浅浅水痕。\n\n他把书翻到第一页，听见屋外又响起了细雨声。",
                ),
            ],
        )
        db.commit()


def import_artifact(
    settings,
    audio: Path,
    manifest_path: Path,
    chapter_id: str,
    raw_db=None,
    raw_root=None,
):
    """Explicit trust boundary: validate source/cues/config, then copy immutable files."""
    from .artifacts import available

    manifest = json.loads(manifest_path.read_text())
    if not manifest.get("complete") or file_digest(audio) != manifest.get(
        "audio_sha256"
    ):
        raise DomainError("仅可导入哈希匹配的完整导出")
    with closing(connect(settings)) as db:
        revision = db.execute(
            "SELECT r.*,c.book_id FROM chapters c JOIN revisions r ON r.id=c.current_revision WHERE c.id=?",
            (chapter_id,),
        ).fetchone()
        if not revision or revision["source_sha256"] != manifest.get("source_sha256"):
            raise DomainError("章节原文与 manifest 不匹配")
        parts = [
            dict(r)
            for r in db.execute(
                "SELECT * FROM segments WHERE revision_id=? AND depth=0 ORDER BY segment_index",
                (revision["id"],),
            )
        ]
        # Older manifests may have audio even for symbols. New manifests must
        # account for every omitted original range explicitly and truthfully.
        skipped = manifest.get("skipped_segments", [])
        if skipped:
            omitted = [p for p in parts if not has_spoken_content(p["spoken_text"])]
            if len(omitted) != len(skipped):
                raise DomainError("manifest 跳过片段数量不匹配")
            for part, item in zip(omitted, skipped):
                expected = skipped_segment(part)
                if any(
                    item.get(k) != v for k, v in expected.items() if k != "segment_id"
                ):
                    raise DomainError("manifest 跳过片段原文或位置不匹配")
                item["segment_id"] = part["id"]
            parts = [p for p in parts if has_spoken_content(p["spoken_text"])]
        cues = manifest.get("cues", [])
        if len(parts) != len(cues):
            raise DomainError("manifest 片段数量不匹配")
        previous_end = 0
        for part, cue in zip(parts, cues):
            for field in (
                "start_utf16",
                "end_utf16",
                "sentence_start_utf16",
                "sentence_end_utf16",
                "original_text",
                "spoken_text",
            ):
                if part[field] != cue.get(field):
                    raise DomainError(f"cue {field} 不匹配")
            if not (
                previous_end
                <= cue["start_ms"]
                < cue["end_ms"]
                <= manifest["duration_ms"]
            ):
                raise DomainError("cue 时间轴无效")
            previous_end = cue["end_ms"]
        profile = manifest.get("profile", {})
        audio8_reference = (profile.get("engine") == "audio8-realistic-air-cpp"
                            and profile.get("voice_clone_ref_id") and profile.get("ref_audio_sha256")
                            and profile.get("ref_text") and profile.get("inference_mode") == "reference-codes")
        if not profile.get("speaker") or not (profile.get("instruct") or audio8_reference):
            raise DomainError("缺少可验证的声音配置")
        identifier = uuid.uuid4().hex
        relative = Path("imports") / identifier
        target = settings.audio / relative
        target.mkdir(parents=True)
        shutil.copyfile(audio, target / "chapter.m4a")
        # Validate encoded media before exposing it as a playable artifact.
        duration = media_duration(audio)
        if abs(duration * 1000 - manifest["duration_ms"]) > 150:
            raise DomainError("媒体时长与 manifest 不匹配")
        for cue, part in zip(cues, parts):
            cue["segment_id"] = part["id"]
        manifest.update(
            schema_version=2,
            artifact_id=identifier,
            chapter_id=chapter_id,
            book_id=revision["book_id"],
            source_revision=revision["id"],
            imported=True,
            audio_file="chapter.m4a",
        )
        atomic_bytes(target / "chapter.json", encoded(manifest).encode())
        raw_rows = []
        if raw_db or raw_root:
            if not raw_db or not raw_root:
                raise DomainError("FLAC 导入须同时指定 --raw-db 和 --raw-root")
            with closing(
                sqlite3.connect(raw_db.resolve().as_uri() + "?mode=ro", uri=True)
            ) as old:
                old.row_factory = sqlite3.Row
                profiles = [
                    (r["id"], json.loads(r["config_json"]))
                    for r in old.execute("SELECT * FROM profiles")
                ]
                matches = [p for p, config in profiles if config == profile]
                if len(matches) != 1:
                    raise DomainError("旧 profile 元数据不唯一或不匹配")
                if profile.get("normalization") != "OpenCC t2s + whitespace v1":
                    raise DomainError("无法确认历史规范化规则")
                preset = create_preset(
                    db,
                    settings,
                    "历史导入 · " + profile["speaker"],
                    profile["speaker"],
                    profile["instruct"],
                    {
                        "speed": manifest.get("speed", 1),
                        "pause_ms": profile.get("pause_ms", 180),
                    },
                )
                config = snapshot(db, settings, preset, None)["generation"]
                if any(
                    profile.get(k) != config[k]
                    for k in (
                        "engine",
                        "model",
                        "revision",
                        "language",
                        "speaker",
                        "instruct",
                        "temperature",
                        "top_k",
                        "top_p",
                        "repetition_penalty",
                        "max_new_tokens",
                        "seed",
                        "dtype",
                        "attention",
                    )
                ):
                    raise DomainError(
                        "历史生成参数与服务配置不兼容，不能注册 FLAC 缓存"
                    )
                variant = digest(encoded(config))
                for part, original_cue in zip(
                    parts, json.loads(manifest_path.read_text())["cues"]
                ):
                    row = old.execute(
                        "SELECT * FROM audio WHERE profile_id=? AND segment_id=? AND status='ready'",
                        (matches[0], original_cue["segment_id"]),
                    ).fetchone()
                    if not row:
                        raise DomainError("历史 FLAC 记录缺失")
                    path = (raw_root / row["path"]).resolve()
                    if (
                        not path.is_relative_to(raw_root.resolve())
                        or file_digest(path) != row["sha256"]
                    ):
                        raise DomainError("历史 FLAC 路径或哈希无效")
                    import soundfile as sf
                    from tts.common import validated_samples

                    samples, rate = sf.read(path, dtype="float32")
                    validated_samples(samples, part["id"])
                    if rate != 24000 or len(samples) != row["samples"]:
                        raise DomainError("历史 FLAC 格式无效")
                    clip_id = uuid.uuid4().hex
                    clip_path = relative / f"{clip_id}.flac"
                    shutil.copyfile(path, settings.audio / clip_path)
                    raw_rows.append(
                        (
                            clip_id,
                            variant,
                            "historical-import",
                            part["id"],
                            str(clip_path),
                            row["sha256"],
                            row["samples"],
                            rate,
                            row["elapsed_seconds"] or 0,
                            encoded({"imported": True}),
                            1,
                            time.time(),
                        )
                    )
        with transaction(db):
            db.executemany(
                "INSERT INTO segment_audio VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", raw_rows
            )
            db.execute(
                "INSERT INTO artifacts VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                (
                    identifier,
                    revision["id"],
                    digest(encoded(manifest)),
                    str(relative / "chapter.m4a"),
                    str(relative / "chapter.json"),
                    file_digest(target / "chapter.m4a"),
                    file_digest(target / "chapter.json"),
                    1,
                    manifest["duration_ms"],
                    1,
                    time.time(),
                ),
            )
        available(db, settings, identifier)
        return {"artifact_id": identifier, "raw_clips_imported": len(raw_rows)}


def audit(settings):
    with closing(connect(settings)) as db:
        corrupt_audio = [
            r["id"]
            for r in db.execute("SELECT * FROM segment_audio WHERE ready=1").fetchall()
            if not verified_file(settings, r["path"], r["sha256"])
        ]
        corrupt_exports = [
            r["id"]
            for r in db.execute("SELECT * FROM artifacts WHERE ready=1").fetchall()
            if not verified_file(settings, r["audio_path"], r["audio_sha256"])
            or not verified_file(settings, r["manifest_path"], r["manifest_sha256"])
        ]
        with transaction(db):
            db.executemany(
                "UPDATE segment_audio SET ready=0 WHERE id=?",
                [(i,) for i in corrupt_audio],
            )
            db.executemany(
                "UPDATE artifacts SET ready=0 WHERE id=?",
                [(i,) for i in corrupt_exports],
            )
        known = {
            str((settings.audio / r[0]).resolve())
            for table, col in (
                ("segment_audio", "path"),
                ("artifacts", "audio_path"),
                ("artifacts", "manifest_path"),
                ("bundles", "path"),
            )
            for r in db.execute(f"SELECT {col} FROM {table} WHERE {col} IS NOT NULL")
        }
        orphan = [
            str(p.relative_to(settings.audio))
            for p in settings.audio.rglob("*")
            if p.is_file() and str(p.resolve()) not in known
        ]
        return {
            "integrity": db.execute("PRAGMA integrity_check").fetchone()[0],
            "invalid_audio": corrupt_audio,
            "invalid_artifacts": corrupt_exports,
            "unpublished_files": orphan,
        }


def storage_check(settings):
    results = []
    for folder in (settings.state, settings.audio):
        with tempfile.TemporaryDirectory(dir=folder, prefix=".storage-check-") as temp:
            a = Path(temp) / "a"
            b = Path(temp) / "b"
            atomic_bytes(a, b"first")
            atomic_bytes(b, b"second")
            a.replace(b)
            if b.read_bytes() != b"first":
                raise RuntimeError("Atomic replacement check failed")
            results.append(
                {
                    "directory": str(folder),
                    "atomic_replace": True,
                    "free_bytes": shutil.disk_usage(folder).free,
                }
            )
    return results


def main():
    parser = argparse.ArgumentParser(description="山页 TTS 显式管理命令")
    subs = parser.add_subparsers(dest="command", required=True)
    subs.add_parser("init")
    subs.add_parser("audit")
    subs.add_parser("check-storage")
    p = subs.add_parser("import-book")
    p.add_argument("--source", type=Path, required=True)
    p.add_argument("--book-id", required=True)
    p.add_argument("--namespace", required=True)
    p = subs.add_parser("create-demo")
    p.add_argument("--output", type=Path, required=True)
    p = subs.add_parser("backup")
    p.add_argument("--output", type=Path, required=True)
    p = subs.add_parser("import-artifact")
    p.add_argument("--audio", type=Path, required=True)
    p.add_argument("--manifest", type=Path, required=True)
    p.add_argument("--chapter-id", required=True)
    p.add_argument("--raw-db", type=Path)
    p.add_argument("--raw-root", type=Path)
    args = parser.parse_args()
    settings = Settings()
    initialize(settings)
    if args.command == "init":
        result = {
            "state": str(settings.state),
            "audio": str(settings.audio),
            "initialized": True,
        }
    elif args.command == "import-book":
        result = import_sqlite(settings, args.source, args.book_id, args.namespace)
    elif args.command == "create-demo":
        create_demo(args.output)
        result = {"source": str(args.output), "book_id": "studio-demo"}
    elif args.command == "backup":
        backup(settings, args.output)
        result = {"backup": str(args.output)}
    elif args.command == "audit":
        result = audit(settings)
    elif args.command == "check-storage":
        result = storage_check(settings)
    else:
        result = import_artifact(
            settings,
            args.audio,
            args.manifest,
            args.chapter_id,
            args.raw_db,
            args.raw_root,
        )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
