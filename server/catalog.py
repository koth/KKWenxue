from __future__ import annotations

import re
import sqlite3
import time
from pathlib import Path
from contextlib import closing

from tts.common import (
    canonical_text,
    digest,
    utf16_len,
    chapter_number,
    sentence_spans,
    bounded_spans,
)
from .config import NORMALIZATION, SEGMENTATION
from .db import connect, transaction


class DomainError(Exception):
    def __init__(self, message, status=422, details=None):
        self.message, self.status, self.details = message, status, details
        super().__init__(message)


def materialize_inline(db, payload):
    """Materialize only chapters selected by the local workbench request.

    The server intentionally has no discovery/import scan. These rows are job
    inputs, identified by the local source IDs, and are never shown as the
    local Web catalogue.
    """
    if not isinstance(payload, list) or not payload or len(payload) > 2000:
        raise DomainError("inline_chapters 必须包含 1–2000 个本地章节")
    books = {}
    prepared = []
    for chapter in payload:
        if not isinstance(chapter, dict):
            raise DomainError("本地章节 payload 格式无效")
        text = chapter.get("canonical_text")
        if not isinstance(text, str) or not text.strip() or len(text) > 1_000_000:
            raise DomainError("本地章节正文为空或过大")
        expected = digest(
            f"{chapter.get('id')}:{digest(text)}:{NORMALIZATION}:{SEGMENTATION}"
        )
        if (
            chapter.get("source_sha256") != digest(text)
            or chapter.get("current_revision") != expected
        ):
            raise DomainError("本地章节原文哈希或版本不匹配")
        # Rebuild text normalization and anchors on the server.  Local Python
        # can show the library without carrying model or OpenCC dependencies.
        parts = segments_for(text, expected)
        if not parts:
            raise DomainError("本地章节没有可朗读文本")
        prepared.append((chapter, parts))
    with transaction(db):
        for chapter in payload:
            required = {
                "id",
                "book_id",
                "book_title",
                "chapter_index",
                "chapter_number",
                "title",
                "current_revision",
                "canonical_text",
                "source_sha256",
                "segments",
            }
            if not required <= set(chapter):
                raise DomainError("本地章节 payload 字段不完整")
            books[chapter["book_id"]] = chapter["book_title"]
        for book_id, title in books.items():
            db.execute(
                "INSERT INTO books(id,title,source_key) VALUES (?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title",
                (book_id, title, f"inline:{book_id}"),
            )
        for chapter, parts in prepared:
            chapter_id, revision = chapter["id"], chapter["current_revision"]
            db.execute(
                """INSERT INTO chapters(id,book_id,source_key,chapter_index,chapter_number,title,current_revision)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title,current_revision=excluded.current_revision""",
                (
                    chapter_id,
                    chapter["book_id"],
                    f"inline:{chapter_id}",
                    chapter["chapter_index"],
                    chapter["chapter_number"],
                    chapter["title"],
                    revision,
                ),
            )
            db.execute(
                "INSERT OR IGNORE INTO revisions VALUES (?,?,?,?,?,?,?)",
                (
                    revision,
                    chapter_id,
                    chapter["canonical_text"],
                    chapter["source_sha256"],
                    "reader-canonical+OpenCC-t2s-v1",
                    SEGMENTATION,
                    time.time(),
                ),
            )
            db.executemany(
                "INSERT OR IGNORE INTO segments VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", parts
            )


def segments_for(text, revision_id):
    from opencc import OpenCC

    convert = OpenCC("t2s").convert
    offsets = [0]
    for char in text:
        offsets.append(offsets[-1] + utf16_len(char))
    result = []
    for sentence_index, (a, b) in enumerate(sentence_spans(text)):
        for start, end in bounded_spans(text, a, b):
            original = text[start:end]
            spoken = re.sub(r"\s+", " ", convert(original)).strip()
            if not spoken:
                continue
            result.append(
                (
                    digest(f"{revision_id}:{offsets[start]}:{offsets[end]}"),
                    revision_id,
                    len(result),
                    sentence_index,
                    offsets[a],
                    offsets[b],
                    offsets[start],
                    offsets[end],
                    original,
                    spoken,
                    None,
                    0,
                )
            )
    return result


def import_sqlite(settings, source: Path, book_id: str, namespace: str):
    if not source.is_file() or not namespace.strip():
        raise DomainError("需要存在的 SQLite 来源和稳定 namespace")
    with closing(
        sqlite3.connect(source.resolve().as_uri() + "?mode=ro", uri=True)
    ) as src:
        src.row_factory = sqlite3.Row
        src.execute("BEGIN")
        try:
            book = src.execute("SELECT * FROM books WHERE id=?", (book_id,)).fetchone()
            rows = src.execute(
                "SELECT * FROM chapters WHERE book_id=? ORDER BY chapter_index",
                (book_id,),
            ).fetchall()
        except sqlite3.DatabaseError as exc:
            raise DomainError(
                "来源必须是阅读器 SQLite（books / chapters）", details=str(exc)
            ) from exc
    if not book or not rows:
        raise DomainError("来源没有这本书或章节为空")
    service_book = digest(f"{namespace}:book:{book_id}")[:32]
    prepared = []
    for row in rows:
        chapter_id = digest(f"{service_book}:chapter:{row['id']}")[:32]
        text = canonical_text(row["body"])
        revision = digest(f"{chapter_id}:{digest(text)}:{NORMALIZATION}:{SEGMENTATION}")
        parts = segments_for(text, revision)
        if not parts:
            raise DomainError("章节无可朗读内容", details=row["id"])
        prepared.append((row, chapter_id, text, revision, parts))
    with closing(connect(settings)) as db, transaction(db):
        db.execute(
            "INSERT INTO books VALUES (?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title",
            (service_book, book["title"], f"{namespace}:{book_id}"),
        )
        for row, chapter_id, text, revision, parts in prepared:
            db.execute(
                """INSERT INTO chapters VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET
                chapter_index=excluded.chapter_index,chapter_number=excluded.chapter_number,
                title=excluded.title,current_revision=excluded.current_revision""",
                (
                    chapter_id,
                    service_book,
                    row["id"],
                    row["chapter_index"],
                    chapter_number(row["title"], row["chapter_index"]),
                    row["title"],
                    revision,
                ),
            )
            db.execute(
                "INSERT OR IGNORE INTO revisions VALUES (?,?,?,?,?,?,?)",
                (
                    revision,
                    chapter_id,
                    text,
                    digest(text),
                    NORMALIZATION,
                    SEGMENTATION,
                    time.time(),
                ),
            )
            db.executemany(
                "INSERT OR IGNORE INTO segments VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", parts
            )
    return {
        "book_id": service_book,
        "title": book["title"],
        "chapters": len(prepared),
        "segments": sum(len(p[-1]) for p in prepared),
    }


def resolve(db, book_id, expression="", chapter_ids=None):
    rows = [
        dict(r)
        for r in db.execute(
            "SELECT * FROM chapters WHERE book_id=? ORDER BY chapter_index,id",
            (book_id,),
        )
    ]
    if not rows:
        raise DomainError("书籍不存在或没有章节", 404)
    selected, ambiguous, errors = set(), [], []
    if chapter_ids is not None:
        available = {r["id"] for r in rows}
        if not chapter_ids or any(x not in available for x in chapter_ids):
            raise DomainError("章节 ID 为空、不存在或属于其他书籍")
        selected.update(chapter_ids)
    else:
        if not expression.strip():
            raise DomainError("请选择章节或输入章号")
        for term in re.split(r"[,，\s]+", expression.strip()):
            match = re.fullmatch(r"(\d+)(?:\s*-\s*(\d+))?", term)
            if not match:
                errors.append(term)
                continue
            first = int(match[1])
            last = int(match[2] or first)
            if last < first or last - first > 10000:
                errors.append(term)
                continue
            found = [r for r in rows if first <= r["chapter_number"] <= last]
            missing = sorted(
                set(range(first, last + 1)) - {r["chapter_number"] for r in found}
            )
            if missing:
                errors.append({"term": term, "missing": missing})
            if not match[2] and len(found) > 1:
                ambiguous.append({"number": first, "candidates": found})
            else:
                selected.update(r["id"] for r in found)
    if errors:
        raise DomainError("章节选择含无效项", details=errors)
    return {
        "chapter_ids": [r["id"] for r in rows if r["id"] in selected],
        "chapters": [r for r in rows if r["id"] in selected],
        "ambiguous": ambiguous,
    }
