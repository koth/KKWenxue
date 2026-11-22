"""Local reader catalogue with a separate, explicitly downloaded test library."""

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
    def __init__(self, path: Path, downloads: Path | None = None):
        self.path = path.expanduser().resolve()
        self.downloads = (downloads or path).expanduser().resolve()
        self._revision_chapters = {}

    @staticmethod
    def initialize_downloads(path):
        path = Path(path).expanduser().resolve()
        path.parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(path, timeout=10) as db:
            books={r[1] for r in db.execute("PRAGMA table_info(books)")}
            chapters={r[1] for r in db.execute("PRAGMA table_info(chapters)")}
            if (books and books!={"id","title"}) or (chapters and chapters!={"id","book_id","chapter_index","title","body","chapter_number"}):
                raise ValueError("下载书库需要使用独立 SQLite 文件，不能覆盖阅读器数据库")
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS books(id TEXT PRIMARY KEY,title TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS chapters(id TEXT PRIMARY KEY,book_id TEXT NOT NULL,
                    chapter_index INTEGER NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,chapter_number INTEGER NOT NULL);
                CREATE INDEX IF NOT EXISTS chapters_book ON chapters(book_id,chapter_index);
            """)
        return path

    def import_chapters(self, book, chapters):
        """Verify the entire selection before committing; never edit the reader DB."""
        if not chapters or len(chapters) > 2000:
            raise ValueError("每次可拉取 1–2000 个章节，请填写测试范围")
        prepared = []
        seen = set()
        for row in chapters:
            text = row["canonical_text"]
            if (row["book_id"] != book["id"] or row["id"] in seen or
                not isinstance(text,str) or not text.strip() or len(text)>1_000_000 or
                canonical_text(text) != text or digest(text) != row["source_sha256"] or
                self._revision(row,text) != row["current_revision"]):
                raise ValueError("服务器章节身份、原文或版本校验失败，本地书库未改动")
            seen.add(row["id"])
            prepared.append((row["id"],book["id"],row["chapter_index"],row["title"],text,row["chapter_number"]))
        self.initialize_downloads(self.downloads)
        with sqlite3.connect(self.downloads,timeout=10) as db:
            db.execute("BEGIN IMMEDIATE")
            for row in prepared:
                owner=db.execute("SELECT book_id FROM chapters WHERE id=?",(row[0],)).fetchone()
                if owner and owner[0]!=book["id"]:
                    raise ValueError("章节 ID 与本地其他书籍冲突，本地书库未改动")
            db.execute("INSERT INTO books VALUES(?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title",(book["id"],book["title"]))
            db.executemany("""INSERT INTO chapters VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET
                chapter_index=excluded.chapter_index,title=excluded.title,body=excluded.body,chapter_number=excluded.chapter_number""",prepared)
        return {"book_id":book["id"],"title":book["title"],"imported":len(prepared)}

    @staticmethod
    def _revision(row, text):
        return digest(
            f"{row['id']}:{digest(text)}:reader-canonical+OpenCC-t2s-v1:reader-sentence-bounded160-v1"
        )

    def _db(self):
        if not self.path.is_file():
            raise FileNotFoundError(f"本地书库不存在: {self.path}")
        if self.path == self.downloads or not self.downloads.is_file():
            db = sqlite3.connect(self.path.as_uri() + "?mode=ro", uri=True)
        else:
            db=sqlite3.connect(":memory:",uri=True)
            db.execute("ATTACH DATABASE ? AS reader",(self.path.as_uri()+"?mode=ro",))
            db.execute("ATTACH DATABASE ? AS downloaded",(self.downloads.as_uri()+"?mode=ro",))
            db.executescript("""
                CREATE TEMP VIEW books AS SELECT id,title FROM downloaded.books UNION ALL
                    SELECT id,title FROM reader.books b WHERE NOT EXISTS(SELECT 1 FROM downloaded.books d WHERE d.id=b.id);
                CREATE TEMP VIEW chapters AS SELECT id,book_id,chapter_index,title,body,chapter_number FROM downloaded.chapters UNION ALL
                    SELECT id,book_id,chapter_index,title,body,NULL AS chapter_number FROM reader.chapters c
                    WHERE NOT EXISTS(SELECT 1 FROM downloaded.chapters d WHERE d.id=c.id);
            """)
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
            "chapter_number": row["chapter_number"] if "chapter_number" in row.keys() and row["chapter_number"] is not None else chapter_number(row["title"], row["chapter_index"]),
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
            number_column = "chapter_number" if any(
                field[1] == "chapter_number" for field in db.execute("PRAGMA table_info(chapters)")
            ) else "NULL AS chapter_number"
            headings = [
                dict(r) | {"number": r["chapter_number"] if "chapter_number" in r.keys() and r["chapter_number"] is not None else chapter_number(r["title"], r["chapter_index"])}
                for r in db.execute(
                    f"SELECT id,title,chapter_index,{number_column} FROM chapters WHERE book_id=?",
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
                | {"chapter_number": row["chapter_number"] if "chapter_number" in row.keys() and row["chapter_number"] is not None else chapter_number(row["title"], row["chapter_index"])}
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
