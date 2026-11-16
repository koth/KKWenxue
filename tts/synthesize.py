#!/usr/bin/env python3
"""Resumable local audiobook synthesis with canonical Android UTF-16 anchors."""

from __future__ import annotations

import argparse
from contextlib import closing
import importlib.metadata
import json
import re
import shutil
import sqlite3
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
import sys

if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
DEFAULT_SOURCE = ROOT / "app/src/main/assets/shanye.db"
DEFAULT_OUTPUT = ROOT / "tts/output"
MODEL = "mlx-community/Qwen3-TTS-12Hz-1.7B-CustomVoice-4bit"
REVISION = "f35faf19b0cc2160865af64ecf0f22f83d335135"
INSTRUCTION = "用普通话，以沉稳、低沉、克制的男声朗读悬疑小说。语速自然，吐字清晰，叙述平静而有悬念，句末自然停顿，不要夸张表演，不要添加原文以外的内容。"
EXPORT_SPEED = 1.15
try:
    from .common import (
        digest,
        utf16_len,
        chapter_number,
        canonical_text,
        sentence_spans,
        bounded_spans,
        GPULock,
    )
except ImportError:  # Direct CLI invocation.
    from common import (
        digest,
        utf16_len,
        chapter_number,
        canonical_text,
        sentence_spans,
        bounded_spans,
        GPULock,
    )


def connect(output: Path) -> sqlite3.Connection:
    output.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(output / "tts.sqlite")
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys=ON")
    db.execute("PRAGMA journal_mode=WAL")
    db.executescript("""
        CREATE TABLE IF NOT EXISTS chapters (
            id TEXT PRIMARY KEY, book_id TEXT NOT NULL, book_title TEXT NOT NULL,
            chapter_index INTEGER NOT NULL, title TEXT NOT NULL,
            canonical_text TEXT NOT NULL, source_sha256 TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS segments (
            id TEXT PRIMARY KEY, chapter_id TEXT NOT NULL REFERENCES chapters(id),
            segment_index INTEGER NOT NULL, sentence_index INTEGER NOT NULL,
            sentence_start_utf16 INTEGER NOT NULL, sentence_end_utf16 INTEGER NOT NULL,
            start_utf16 INTEGER NOT NULL, end_utf16 INTEGER NOT NULL,
            original_text TEXT NOT NULL, spoken_text TEXT NOT NULL);
        CREATE INDEX IF NOT EXISTS segments_order ON segments(chapter_id, segment_index);
        CREATE TABLE IF NOT EXISTS profiles (
            id TEXT PRIMARY KEY, config_json TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS audio (
            profile_id TEXT NOT NULL REFERENCES profiles(id),
            segment_id TEXT NOT NULL REFERENCES segments(id),
            path TEXT, sample_rate INTEGER, samples INTEGER, duration_ms INTEGER,
            sha256 TEXT, elapsed_seconds REAL, status TEXT NOT NULL, error TEXT,
            PRIMARY KEY(profile_id,segment_id));
    """)
    if "chapter_number" not in {
        row[1] for row in db.execute("PRAGMA table_info(chapters)")
    }:
        db.execute("ALTER TABLE chapters ADD COLUMN chapter_number INTEGER")
    with db:
        db.executemany(
            "UPDATE chapters SET chapter_number=? WHERE id=?",
            [
                (chapter_number(row["title"], row["chapter_index"]), row["id"])
                for row in db.execute(
                    "SELECT id,title,chapter_index FROM chapters WHERE chapter_number IS NULL"
                ).fetchall()
            ],
        )
    return db


def prepare(db: sqlite3.Connection, source: Path) -> None:
    from opencc import OpenCC

    converter = OpenCC("t2s")
    with closing(
        sqlite3.connect(source.resolve().as_uri() + "?mode=ro", uri=True)
    ) as library:
        library.row_factory = sqlite3.Row
        rows = library.execute("""SELECT c.*, b.title AS book_title FROM chapters c
            JOIN books b ON b.id=c.book_id ORDER BY c.book_id,c.chapter_index""").fetchall()
    with db:
        for row in rows:
            text = canonical_text(row["body"])
            source_hash = digest(text)
            existing = db.execute(
                "SELECT source_sha256 FROM chapters WHERE id=?", (row["id"],)
            ).fetchone()
            if existing:
                if existing[0] != source_hash:
                    raise ValueError(
                        f"Chapter text changed: {row['id']}. Use a new output directory for the new edition."
                    )
                continue
            db.execute(
                "INSERT INTO chapters (id,book_id,book_title,chapter_index,title,canonical_text,source_sha256,chapter_number) VALUES (?,?,?,?,?,?,?,?)",
                (
                    row["id"],
                    row["book_id"],
                    row["book_title"],
                    row["chapter_index"],
                    row["title"],
                    text,
                    source_hash,
                    chapter_number(row["title"], row["chapter_index"]),
                ),
            )
            prefix = [0]
            for char in text:
                prefix.append(prefix[-1] + utf16_len(char))
            segment_index = 0
            for sentence_index, (a, b) in enumerate(sentence_spans(text)):
                for start, end in bounded_spans(text, a, b):
                    original = text[start:end]
                    spoken = re.sub(r"\s+", " ", converter.convert(original)).strip()
                    if not spoken:
                        continue
                    identifier = digest(
                        f"{row['id']}:{source_hash}:{prefix[start]}:{prefix[end]}"
                    )[:32]
                    db.execute(
                        "INSERT INTO segments VALUES (?,?,?,?,?,?,?,?,?,?)",
                        (
                            identifier,
                            row["id"],
                            segment_index,
                            sentence_index,
                            prefix[a],
                            prefix[b],
                            prefix[start],
                            prefix[end],
                            original,
                            spoken,
                        ),
                    )
                    segment_index += 1
    print(
        json.dumps(
            {
                "chapters": db.execute("SELECT count(*) FROM chapters").fetchone()[0],
                "segments": db.execute("SELECT count(*) FROM segments").fetchone()[0],
            },
            ensure_ascii=False,
        ),
        flush=True,
    )


def profile(db: sqlite3.Connection, batch_size: int) -> tuple[str, dict]:
    config = dict(
        engine="qwen3-tts-mlx",
        model=MODEL,
        revision=REVISION,
        speaker="Uncle_Fu",
        language="Chinese",
        instruct=INSTRUCTION,
        temperature=0.7,
        max_tokens=1536,
        seed=42,
        batch_size=batch_size,
        mlx_audio=importlib.metadata.version("mlx-audio"),
        normalization="OpenCC t2s + whitespace v1",
        pause_ms=180,
    )
    encoded = json.dumps(config, ensure_ascii=False, sort_keys=True)
    identifier = digest(encoded)[:16]
    with db:
        db.execute("INSERT OR IGNORE INTO profiles VALUES (?,?)", (identifier, encoded))
    return identifier, config


def pending(db, output, profile_id, first, last):
    rows = db.execute(
        """SELECT s.*, c.chapter_index,c.chapter_number, a.status, a.path FROM segments s
        JOIN chapters c ON c.id=s.chapter_id
        LEFT JOIN audio a ON a.segment_id=s.id AND a.profile_id=?
        WHERE c.chapter_number BETWEEN ? AND ? ORDER BY c.book_id,c.chapter_index,s.segment_index""",
        (profile_id, first, last),
    ).fetchall()
    return [
        r
        for r in rows
        if r["status"] != "ready" or not r["path"] or not (output / r["path"]).is_file()
    ]


def generate(db, output, first, last, limit, batch_size, speed=EXPORT_SPEED):
    import mlx.core as mx
    import numpy as np
    import soundfile as sf
    from huggingface_hub import snapshot_download
    from mlx_audio.tts.utils import load_model

    profile_id, config = profile(db, batch_size)
    queue = pending(db, output, profile_id, first, last)
    if limit:
        queue = queue[:limit]
    if not queue:
        print("No pending segments in requested chapters.", flush=True)
        return profile_id
    print(f"Profile {profile_id}: {len(queue)} segments; loading {MODEL}", flush=True)
    local_model = snapshot_download(MODEL, revision=REVISION)
    model = load_model(local_model)
    chapter_ends = {
        row["chapter_id"]: (i + 1, row["chapter_number"]) for i, row in enumerate(queue)
    }
    for offset in range(0, len(queue), batch_size):
        batch = queue[offset : offset + batch_size]
        mx.random.seed(
            config["seed"]
            + batch[0]["chapter_index"] * 100_000
            + batch[0]["segment_index"]
        )
        started = time.monotonic()
        try:
            # Single generation avoids batch padding overhead on a one-item tail.
            if len(batch) == 1:
                results = model.generate_custom_voice(
                    text=batch[0]["spoken_text"],
                    speaker=config["speaker"],
                    language=config["language"],
                    instruct=config["instruct"],
                    temperature=config["temperature"],
                    max_tokens=config["max_tokens"],
                )
            else:
                results = model.batch_generate(
                    texts=[r["spoken_text"] for r in batch],
                    voices=[config["speaker"]] * len(batch),
                    instructs=[config["instruct"]] * len(batch),
                    lang_code=config["language"],
                    temperature=config["temperature"],
                    max_tokens=config["max_tokens"],
                    stream=False,
                )
            completed = set()
            for result in results:
                index = getattr(result, "sequence_idx", 0)
                row = batch[index]
                samples = np.asarray(result.audio, dtype=np.float32).reshape(-1)
                sample_rate = int(result.sample_rate)
                duration = len(samples) / sample_rate
                if result.token_count >= config["max_tokens"]:
                    raise ValueError(
                        f"Audio hit the token limit for {row['id']}; split or retry this text."
                    )
                if not np.isfinite(samples).all() or duration < 0.2 or duration > 120:
                    raise ValueError(
                        f"Invalid audio duration/data for {row['id']}: {duration:.2f}s"
                    )
                if np.max(np.abs(samples)) < 0.001:
                    raise ValueError(f"Silent audio for {row['id']}")
                relative = (
                    Path("segments")
                    / profile_id
                    / row["chapter_id"]
                    / f"{row['segment_index']:05d}.flac"
                )
                path = output / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                temporary = path.with_suffix(".partial")
                sf.write(
                    temporary, samples, sample_rate, format="FLAC", subtype="PCM_16"
                )
                temporary.replace(path)
                elapsed = time.monotonic() - started
                with db:
                    db.execute(
                        """INSERT OR REPLACE INTO audio
                        (profile_id,segment_id,path,sample_rate,samples,duration_ms,sha256,elapsed_seconds,status,error)
                        VALUES (?,?,?,?,?,?,?,?,?,NULL)""",
                        (
                            profile_id,
                            row["id"],
                            str(relative),
                            sample_rate,
                            len(samples),
                            round(duration * 1000),
                            digest(path.read_bytes()),
                            elapsed / len(batch),
                            "ready",
                        ),
                    )
                completed.add(index)
                print(
                    f"[{offset + len(completed)}/{len(queue)}] chapter {row['chapter_number']} "
                    f"segment {row['segment_index'] + 1}: {duration:.2f}s audio, batch {elapsed:.1f}s",
                    flush=True,
                )
            if len(completed) != len(batch):
                raise RuntimeError(
                    "Model returned fewer audio results than requested; remaining segments stay pending."
                )
            batch_elapsed = time.monotonic() - started
            with db:
                db.executemany(
                    "UPDATE audio SET elapsed_seconds=? WHERE profile_id=? AND segment_id=?",
                    [
                        (batch_elapsed / len(batch), profile_id, row["id"])
                        for row in batch
                    ],
                )
            for end_position, number in chapter_ends.values():
                if offset < end_position <= offset + len(batch):
                    export_chapters(db, output, profile_id, number, number, speed=speed)
            mx.clear_cache()
        except Exception as exc:
            with db:
                for row in batch:
                    db.execute(
                        """INSERT OR IGNORE INTO audio (profile_id,segment_id,status,error)
                        VALUES (?,?,?,?)""",
                        (profile_id, row["id"], "failed", repr(exc)),
                    )
            raise
    return profile_id


def retry_segments(db, output, profile_id, chapter_number, indices):
    for index in indices:
        row = db.execute(
            """SELECT a.*,s.id FROM segments s JOIN chapters c ON s.chapter_id=c.id
            LEFT JOIN audio a ON s.id=a.segment_id AND a.profile_id=?
            WHERE c.chapter_number=? AND s.segment_index=?""",
            (profile_id, chapter_number, index - 1),
        ).fetchone()
        if not row:
            raise ValueError(f"No segment {index} in chapter {chapter_number}")
        if row["path"] and (output / row["path"]).is_file():
            rejected = (
                output
                / "quality"
                / profile_id
                / "rejected"
                / f"{row['id']}-{row['sha256'][:8]}.flac"
            )
            rejected.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(output / row["path"], rejected)
        with db:
            db.execute(
                "UPDATE audio SET status='retry',error='Requested retry after audio review' WHERE profile_id=? AND segment_id=?",
                (profile_id, row["id"]),
            )


def export_chapters(
    db, output, profile_id, first, last, partial=False, limit=0, speed=EXPORT_SPEED
):

    found = db.execute(
        "SELECT config_json FROM profiles WHERE id=?", (profile_id,)
    ).fetchone()
    if not found:
        raise ValueError(f"Unknown profile: {profile_id}")
    config = json.loads(found[0])
    for chapter in db.execute(
        "SELECT * FROM chapters WHERE chapter_number BETWEEN ? AND ? ORDER BY chapter_index",
        (first, last),
    ).fetchall():
        rows = db.execute(
            """SELECT s.*, a.path, a.status, a.sample_rate, a.samples FROM segments s
            LEFT JOIN audio a ON s.id=a.segment_id AND a.profile_id=?
            WHERE s.chapter_id=? ORDER BY s.segment_index""",
            (profile_id, chapter["id"]),
        ).fetchall()
        available = []
        for row in rows:
            if row["status"] != "ready" or not (output / row["path"]).is_file():
                break  # Partial exports always contain a contiguous prefix.
            available.append(row)
            if limit and len(available) >= limit:
                break
        complete = len(available) == len(rows)
        if not available or (not complete and not partial):
            continue
        folder = output / "chapters" / profile_id
        folder.mkdir(parents=True, exist_ok=True)
        same_number = db.execute(
            "SELECT id FROM chapters WHERE book_id=? AND chapter_number=? ORDER BY chapter_index",
            (chapter["book_id"], chapter["chapter_number"]),
        ).fetchall()
        part = next(
            i for i, row in enumerate(same_number, 1) if row["id"] == chapter["id"]
        )
        stem = (
            f"{chapter['chapter_number']:04d}"
            + (f"-part{part}" if len(same_number) > 1 else "")
            + ("" if complete else ".preview")
        )
        target = folder / f"{stem}.m4a"
        manifest_path = folder / f"{stem}.json"
        try:
            from .audio import render
        except ImportError:
            from tts.audio import render
        clips = [
            {**dict(row), "segment_id": row["id"], "path": output / row["path"]}
            for row in available
        ]
        timing = render(
            clips,
            target,
            speed,
            config["pause_ms"],
            chapter["title"],
            chapter["book_title"],
        )
        sample_rate, cues = timing["sample_rate"], timing["cues"]
        manifest = dict(
            schema_version=1,
            book_id=chapter["book_id"],
            chapter_id=chapter["id"],
            chapter_number=chapter["chapter_number"],
            chapter_index=chapter["chapter_index"],
            title=chapter["title"],
            source_sha256=chapter["source_sha256"],
            offsets="UTF-16 into Android Chapter.text",
            audio_file=target.name,
            audio_sha256=digest(target.read_bytes()),
            sample_rate=sample_rate,
            speed=speed,
            speed_method="per-segment pitch-preserving atempo",
            duration_ms=timing["duration_ms"],
            complete=complete,
            profile=config,
            cues=cues,
        )
        temporary = manifest_path.with_suffix(".partial")
        temporary.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        temporary.replace(manifest_path)
        print(
            f"Exported {target}: {manifest['duration_ms'] / 1000:.1f}s, {len(cues)} cues, complete={complete}",
            flush=True,
        )


def status(db):
    print(
        "Source:",
        dict(
            db.execute(
                "SELECT count(*) AS chapters,sum(length(canonical_text)) AS characters FROM chapters"
            ).fetchone()
        ),
    )
    print("Segments:", db.execute("SELECT count(*) FROM segments").fetchone()[0])
    for row in db.execute("""SELECT profile_id,status,count(*) AS segments,
        round(sum(duration_ms)/1000.0,1) AS audio_seconds,round(sum(elapsed_seconds),1) AS synthesis_seconds
        FROM audio GROUP BY profile_id,status"""):
        print(dict(row))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare", "generate", "export", "status"])
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--first", type=int, default=1, help="First chapter, one-based")
    parser.add_argument("--last", type=int, default=1, help="Last chapter, inclusive")
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="Maximum new segments; 0 means all requested chapters",
    )
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument(
        "--speed",
        type=float,
        default=EXPORT_SPEED,
        help="Pitch-preserving export speed; default 1.15",
    )
    parser.add_argument("--profile", help="Profile ID for export")
    parser.add_argument(
        "--partial",
        action="store_true",
        help="Export an explicitly marked incomplete preview",
    )
    parser.add_argument(
        "--retry-segment",
        type=int,
        action="append",
        default=[],
        help="Regenerate a one-based segment after audio review; use a single chapter range",
    )
    args = parser.parse_args()
    if not 1 <= args.first <= args.last or args.batch_size < 1 or args.limit < 0:
        parser.error("Invalid chapter range, batch size or limit")
    if not 0.5 <= args.speed <= 2.0:
        parser.error("--speed must be between 0.5 and 2.0")
    if args.retry_segment and (args.first != args.last or min(args.retry_segment) < 1):
        parser.error(
            "--retry-segment requires one chapter and positive segment numbers"
        )
    with connect(args.output) as db:
        if args.command == "prepare":
            prepare(db, args.source)
        elif args.command == "generate":
            # fcntl releases this local process lock on interruption/crash.
            import fcntl

            with GPULock(), (args.output / ".synthesis.lock").open("w") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                if args.retry_segment:
                    profile_id, _ = profile(db, args.batch_size)
                    retry_segments(
                        db, args.output, profile_id, args.first, args.retry_segment
                    )
                profile_id = generate(
                    db,
                    args.output,
                    args.first,
                    args.last,
                    args.limit,
                    args.batch_size,
                    args.speed,
                )
                export_chapters(
                    db,
                    args.output,
                    profile_id,
                    args.first,
                    args.last,
                    partial=True,
                    speed=args.speed,
                )
        elif args.command == "export":
            if not args.profile:
                parser.error("--profile is required for export")
            export_chapters(
                db,
                args.output,
                args.profile,
                args.first,
                args.last,
                args.partial,
                args.limit,
                args.speed,
            )
        else:
            status(db)


if __name__ == "__main__":
    main()
