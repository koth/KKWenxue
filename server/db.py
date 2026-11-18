from __future__ import annotations

from contextlib import contextmanager, closing
import json
import secrets
import sqlite3
import time
from pathlib import Path

from .config import Settings
from .voices import DEFAULT_CLONE_ID, DEFAULT_PRESET_ID, DEFAULT_VOICE_NAME, NARRATOR_INSTRUCTION, HAORAN_PRESET_ID

SCHEMA = """
CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY);
INSERT OR IGNORE INTO schema_version VALUES (1);
CREATE TABLE IF NOT EXISTS books (id TEXT PRIMARY KEY, title TEXT NOT NULL, source_key TEXT UNIQUE NOT NULL);
CREATE TABLE IF NOT EXISTS chapters (
 id TEXT PRIMARY KEY, book_id TEXT NOT NULL REFERENCES books(id), source_key TEXT NOT NULL,
 chapter_index INTEGER NOT NULL, chapter_number INTEGER NOT NULL, title TEXT NOT NULL,
 current_revision TEXT NOT NULL, UNIQUE(book_id,source_key));
CREATE TABLE IF NOT EXISTS revisions (
 id TEXT PRIMARY KEY, chapter_id TEXT NOT NULL REFERENCES chapters(id), canonical_text TEXT NOT NULL,
 source_sha256 TEXT NOT NULL, normalization TEXT NOT NULL, segmentation TEXT NOT NULL, created REAL NOT NULL);
CREATE TABLE IF NOT EXISTS segments (
 id TEXT PRIMARY KEY, revision_id TEXT NOT NULL REFERENCES revisions(id), segment_index INTEGER NOT NULL,
 sentence_index INTEGER NOT NULL, sentence_start_utf16 INTEGER NOT NULL, sentence_end_utf16 INTEGER NOT NULL,
 start_utf16 INTEGER NOT NULL, end_utf16 INTEGER NOT NULL, original_text TEXT NOT NULL, spoken_text TEXT NOT NULL,
 parent_segment_id TEXT REFERENCES segments(id), depth INTEGER NOT NULL DEFAULT 0);
CREATE INDEX IF NOT EXISTS segment_order ON segments(revision_id,depth,segment_index);
CREATE TABLE IF NOT EXISTS presets (
 id TEXT PRIMARY KEY, name TEXT NOT NULL, speaker TEXT NOT NULL, instruct TEXT NOT NULL,
 export_json TEXT NOT NULL, created REAL NOT NULL, confirmed INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS jobs (
 id TEXT PRIMARY KEY, kind TEXT NOT NULL, request_json TEXT NOT NULL, request_hash TEXT NOT NULL,
 idempotency_key TEXT NOT NULL UNIQUE, config_json TEXT NOT NULL, variant_id TEXT NOT NULL,
 generation_id TEXT NOT NULL, state TEXT NOT NULL, desired TEXT NOT NULL DEFAULT 'run',
 phase TEXT NOT NULL DEFAULT 'queued', created REAL NOT NULL, updated REAL NOT NULL,
 owner TEXT, token TEXT, lease_until REAL, error TEXT, parent_job_id TEXT REFERENCES jobs(id));
CREATE INDEX IF NOT EXISTS job_queue ON jobs(state,created);
CREATE TABLE IF NOT EXISTS job_chapters (
 job_id TEXT NOT NULL REFERENCES jobs(id), revision_id TEXT NOT NULL REFERENCES revisions(id),
 position INTEGER NOT NULL, artifact_id TEXT, state TEXT NOT NULL DEFAULT 'queued', error TEXT,
 PRIMARY KEY(job_id,revision_id));
CREATE TABLE IF NOT EXISTS job_items (
 job_id TEXT NOT NULL REFERENCES jobs(id), segment_id TEXT NOT NULL REFERENCES segments(id),
 position INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'queued', audio_id TEXT,
 attempts INTEGER NOT NULL DEFAULT 0, error TEXT, cache_hit INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(job_id,segment_id));
CREATE TABLE IF NOT EXISTS attempts (
 id INTEGER PRIMARY KEY AUTOINCREMENT, job_id TEXT NOT NULL REFERENCES jobs(id),
 segment_id TEXT NOT NULL REFERENCES segments(id), number INTEGER NOT NULL, started REAL NOT NULL,
 elapsed REAL, seed INTEGER, runtime_json TEXT, error TEXT, UNIQUE(job_id,segment_id,number));
CREATE TABLE IF NOT EXISTS segment_audio (
 id TEXT PRIMARY KEY, variant_id TEXT NOT NULL, generation_id TEXT NOT NULL,
 segment_id TEXT NOT NULL REFERENCES segments(id), path TEXT NOT NULL, sha256 TEXT NOT NULL,
 samples INTEGER NOT NULL, sample_rate INTEGER NOT NULL, elapsed REAL NOT NULL,
 provenance_json TEXT NOT NULL, ready INTEGER NOT NULL, created REAL NOT NULL);
CREATE INDEX IF NOT EXISTS audio_cache ON segment_audio(variant_id,segment_id,ready,created);
CREATE TABLE IF NOT EXISTS artifacts (
 id TEXT PRIMARY KEY, revision_id TEXT NOT NULL REFERENCES revisions(id), export_key TEXT NOT NULL,
 audio_path TEXT NOT NULL, manifest_path TEXT NOT NULL, audio_sha256 TEXT NOT NULL,
 manifest_sha256 TEXT NOT NULL, complete INTEGER NOT NULL, duration_ms INTEGER NOT NULL,
 ready INTEGER NOT NULL, created REAL NOT NULL);
CREATE INDEX IF NOT EXISTS export_cache ON artifacts(export_key,ready);
CREATE TABLE IF NOT EXISTS bundles (
 job_id TEXT PRIMARY KEY REFERENCES jobs(id), path TEXT, sha256 TEXT, expires REAL);
CREATE TABLE IF NOT EXISTS events (
 seq INTEGER PRIMARY KEY AUTOINCREMENT, job_id TEXT NOT NULL REFERENCES jobs(id),
 created REAL NOT NULL, event TEXT NOT NULL, data_json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS runtime (key TEXT PRIMARY KEY, value_json TEXT NOT NULL, heartbeat REAL NOT NULL);
CREATE TABLE IF NOT EXISTS voice_clone_refs (
 id TEXT PRIMARY KEY, path TEXT NOT NULL, ref_text TEXT, x_vector_only INTEGER NOT NULL,
 sha256 TEXT NOT NULL, duration_ms INTEGER NOT NULL, ready INTEGER NOT NULL, created REAL NOT NULL
);
"""


def encoded(value) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    )


def connect(settings: Settings):
    db = sqlite3.connect(
        settings.db, timeout=10, isolation_level=None, check_same_thread=False
    )
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys=ON")
    db.execute("PRAGMA busy_timeout=10000")
    return db


@contextmanager
def transaction(db):
    db.execute("BEGIN IMMEDIATE")
    try:
        yield db
        db.commit()
    except BaseException:
        db.rollback()
        raise


def initialize(settings: Settings):
    settings.prepare()
    with closing(connect(settings)) as db:
        db.execute("PRAGMA journal_mode=WAL")
        db.executescript(SCHEMA)
        if db.execute("SELECT max(version) FROM schema_version").fetchone()[0] != 1:
            raise RuntimeError("Unsupported service schema")
        db.execute('INSERT OR IGNORE INTO presets VALUES (?,?,?,?,?,?,?)',
            (HAORAN_PRESET_ID,'Haoran','zonos:haoran',NARRATOR_INSTRUCTION,
             encoded(dict(speed=1.0,pause_ms=180,format='m4a')),time.time(),1))
        # Keep the approved reference's stable ID shared by Android and web.
        reference = db.execute(
            "SELECT 1 FROM voice_clone_refs WHERE id=? AND ready=1",
            (DEFAULT_CLONE_ID,),
        ).fetchone()
        if reference:
            db.execute(
                "INSERT OR IGNORE INTO presets VALUES (?,?,?,?,?,?,?)",
                (
                    DEFAULT_PRESET_ID,
                    "参考音色 · " + DEFAULT_VOICE_NAME,
                    "clone:" + DEFAULT_CLONE_ID,
                    NARRATOR_INSTRUCTION,
                    encoded({"speed": 1.0, "pause_ms": 180, "format": "m4a"}),
                    time.time(),
                    1,
                ),
            )
            # The old row used the generic clone label.  Existing jobs retain
            # their frozen config, while new jobs get the neutral narrator
            # instruction used for the CosyVoice audition.
            db.execute(
                "UPDATE presets SET instruct=? WHERE id=? AND instruct IN ('参考音频克隆','')",
                (NARRATOR_INSTRUCTION, DEFAULT_PRESET_ID),
            )
            db.execute("UPDATE presets SET confirmed=1 WHERE id=?", (DEFAULT_PRESET_ID,))
    if not settings.token_file.exists():
        try:
            with open(
                settings.token_file,
                "x",
                opener=lambda p, f: __import__("os").open(p, f, 0o600),
            ) as handle:
                handle.write(secrets.token_urlsafe(32))
        except FileExistsError:
            pass


def backup(settings: Settings, target: Path):
    if target.exists():
        raise ValueError("Backup destination already exists")
    target.parent.mkdir(parents=True, exist_ok=True)
    with connect(settings) as source, sqlite3.connect(target) as dest:
        source.backup(dest)
        if dest.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise RuntimeError("Backup integrity check failed")


def event(db, job_id, name, data=None):
    db.execute(
        "INSERT INTO events(job_id,created,event,data_json) VALUES (?,?,?,?)",
        (job_id, time.time(), name, encoded(data or {})),
    )
