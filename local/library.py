"""Read-only local novel catalogue used by the Web workbench.

The browser never receives a server catalogue. Selected chapter payloads are
copied to the synthesis request only when the user submits a job.
"""

from __future__ import annotations

from contextlib import closing
import re
import sqlite3
from pathlib import Path

from tts.common import (
    canonical_text,
    chapter_number,
    digest,
    utf16_len,
    sentence_spans,
    bounded_spans,
)


class LocalLibrary:
    def __init__(self, path: Path):
        self.path = path.expanduser().resolve()
        self._revision_chapters = {}

    @staticmethod
    def _revision(row, text):
        return digest(
            f"{row['id']}:{digest(text)}:reader-canonical+OpenCC-t2s-v1:reader-sentence-bounded160-v1"
        )

    def _db(self):
        if not self.path.is_file():
            raise FileNotFoundError(f"本地书库不存在: {self.path}")
        db = sqlite3.connect(self.path.as_uri() + "?mode=ro", uri=True)
        db.row_factory = sqlite3.Row
        return db

    def _book(self, db, book_id):
        row = db.execute("SELECT id,title FROM books WHERE id=?", (book_id,)).fetchone()
        if not row:
            raise KeyError("书籍不存在")
        return row

    def _chapter(self, row, book_title):
        text = canonical_text(row["body"])
        revision = self._revision(row, text)
        self._revision_chapters[revision] = row["id"]
        offsets = [0]
        for char in text:
            offsets.append(offsets[-1] + utf16_len(char))
        segments = []
        for sentence_index, (a, b) in enumerate(sentence_spans(text)):
            for start, end in bounded_spans(text, a, b):
                original = text[start:end]
                # The server applies OpenCC after receipt.  Keep local payloads
                # deterministic and independent of installed Python packages.
                spoken = re.sub(r"\s+", " ", original).strip()
                if not spoken:
                    continue
                segments.append(
                    {
                        "id": digest(f"{revision}:{offsets[start]}:{offsets[end]}"),
                        "revision_id": revision,
                        "segment_index": len(segments),
                        "sentence_index": sentence_index,
                        "sentence_start_utf16": offsets[a],
                        "sentence_end_utf16": offsets[b],
                        "start_utf16": offsets[start],
                        "end_utf16": offsets[end],
                        "original_text": original,
                        "spoken_text": spoken,
                    }
                )
        return {
            "id": row["id"],
            "book_id": row["book_id"],
            "book_title": book_title,
            "chapter_index": row["chapter_index"],
            "chapter_number": chapter_number(row["title"], row["chapter_index"]),
            "title": row["title"],
            "current_revision": revision,
            "canonical_text": text,
            "source_sha256": digest(text),
            "segments": segments,
        }

    def books(self):
        with closing(self._db()) as db:
            return [
                {
                    **dict(row),
                    "chapters": db.execute(
                        "SELECT count(*) FROM chapters WHERE book_id=?", (row["id"],)
                    ).fetchone()[0],
                }
                for row in db.execute("SELECT id,title FROM books ORDER BY title")
            ]

    def chapters(self, book_id, query="", offset=0, limit=100):
        if offset < 0 or not 1 <= limit <= 500:
            raise ValueError("目录分页参数无效")
        with closing(self._db()) as db:
            book = self._book(db, book_id)
            where = "book_id=? AND (?='' OR title LIKE ? OR CAST(chapter_index+1 AS TEXT)=?)"
            args = (book_id, query, f"%{query}%", query)
            rows = db.execute(
                f"SELECT * FROM chapters WHERE {where} ORDER BY chapter_index,id LIMIT ? OFFSET ?",
                args + (limit, offset),
            ).fetchall()
            items = []
            for row in rows:
                item = self._chapter(row, book["title"])
                items.append(
                    {
                        k: item[k]
                        for k in (
                            "id",
                            "title",
                            "chapter_number",
                            "chapter_index",
                            "current_revision",
                        )
                    }
                    | {
                        "characters": len(item["canonical_text"]),
                        "segments": len(item["segments"]),
                        "same_number_order": 1,
                        "artifacts": [],
                    }
                )
            headings = [
                dict(r) | {"number": chapter_number(r["title"], r["chapter_index"])}
                for r in db.execute(
                    "SELECT id,title,chapter_index FROM chapters WHERE book_id=?",
                    (book_id,),
                )
            ]
            for item in items:
                item["same_number_order"] = 1 + sum(
                    x["number"] == item["chapter_number"]
                    and (x["chapter_index"], x["id"])
                    < (item["chapter_index"], item["id"])
                    for x in headings
                )
            total = db.execute(
                f"SELECT count(*) FROM chapters WHERE {where}", args
            ).fetchone()[0]
            return {"items": items, "total": total}

    def resolve(self, book_id, expression="", chapter_ids=None):
        with closing(self._db()) as db:
            book = self._book(db, book_id)
            rows = [
                dict(row)
                | {"chapter_number": chapter_number(row["title"], row["chapter_index"])}
                for row in db.execute(
                    "SELECT * FROM chapters WHERE book_id=? ORDER BY chapter_index,id",
                    (book_id,),
                )
            ]
        selected, ambiguous, errors = set(), [], []
        if chapter_ids is not None:
            available = {r["id"] for r in rows}
            if not chapter_ids or any(x not in available for x in chapter_ids):
                raise ValueError("章节 ID 为空、不存在或属于其他书籍")
            selected.update(chapter_ids)
        else:
            if not expression.strip():
                raise ValueError("请选择章节或输入章号")
            for term in re.split(r"[,，\s]+", expression.strip()):
                match = re.fullmatch(r"(\d+)(?:\s*-\s*(\d+))?", term)
                if not match:
                    errors.append(term)
                    continue
                first, last = int(match[1]), int(match[2] or match[1])
                if last < first or last - first > 10000:
                    errors.append(term)
                    continue
                found = [r for r in rows if first <= r["chapter_number"] <= last]
                missing = sorted(
                    set(range(first, last + 1)) - {r["chapter_number"] for r in found}
                )
                if last < first or missing:
                    errors.append({"term": term, "missing": missing})
                    continue
                if not match[2] and len(found) > 1:
                    ambiguous.append(
                        {
                            "number": first,
                            "candidates": [
                                {
                                    k: r[k]
                                    for k in (
                                        "id",
                                        "title",
                                        "chapter_index",
                                        "chapter_number",
                                    )
                                }
                                for r in found
                            ],
                        }
                    )
                else:
                    selected.update(r["id"] for r in found)
        if errors:
            raise ValueError(f"章节选择含无效项: {errors}")
        chosen = [self._chapter(r, book["title"]) for r in rows if r["id"] in selected]
        return {
            "chapter_ids": [r["id"] for r in chosen],
            "chapters": chosen,
            "ambiguous": ambiguous,
        }

    def revisions(self, revision_id):
        with closing(self._db()) as db:
            row = db.execute(
                "SELECT * FROM chapters WHERE id=?",
                (self._revision_chapters.get(revision_id),),
            ).fetchone()
            if not row:
                for candidate in db.execute("SELECT * FROM chapters"):
                    if (
                        self._revision(candidate, canonical_text(candidate["body"]))
                        == revision_id
                    ):
                        row = candidate
                        break
                if row is None:
                    raise KeyError("原文版本不存在")
            item = self._chapter(row, self._book(db, row["book_id"])["title"])
            if item["current_revision"] != revision_id:
                raise KeyError("原文版本不存在")
            return {
                "id": revision_id,
                "canonical_text": item["canonical_text"],
                "segments": item["segments"],
                "source_sha256": item["source_sha256"],
            }

    def payload(self, book_id, chapter_ids):
        with closing(self._db()) as db:
            book = self._book(db, book_id)
            wanted = set(chapter_ids)
            rows = [
                self._chapter(row, book["title"])
                for row in db.execute(
                    "SELECT * FROM chapters WHERE book_id=? ORDER BY chapter_index,id",
                    (book_id,),
                )
                if row["id"] in wanted
            ]
        if len(rows) != len(wanted):
            raise ValueError("提交的章节不属于本地书库")
        return rows
