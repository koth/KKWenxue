from contextlib import closing
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from synthesize import (
    bounded_spans,
    canonical_text,
    connect,
    digest,
    export_chapters,
    pending,
    prepare,
    retry_segments,
    sentence_spans,
    utf16_len,
)


class TextAlignmentTests(unittest.TestCase):
    def test_canonical_text_matches_reader_paragraphs(self):
        body = "\n    第一行\n    接着写。\n\n    第二段！\n"
        self.assertEqual("　　第一行接着写。\n\n　　第二段！", canonical_text(body))
        self.assertEqual("　　甲。\n\n　　乙。", canonical_text("甲。\n\n乙。"))

    def test_sentence_boundaries_match_android_including_quotes_and_whitespace(self):
        text = "　　「真的！？！」\n\n　　他笑了，继续走。尾句没有标点"
        spans = list(sentence_spans(text))
        self.assertEqual(
            ["　　「真的！？！」", "\n\n　　他笑了，继续走。", "尾句没有标点"],
            [text[a:b] for a, b in spans],
        )
        self.assertEqual(text, "".join(text[a:b] for a, b in spans))

    def test_long_sentences_keep_every_character_and_prefer_a_pause(self):
        text = "甲" * 90 + "，" + "乙" * 100 + "。"
        spans = list(bounded_spans(text, 0, len(text)))
        self.assertEqual(91, spans[0][1])
        self.assertEqual(text, "".join(text[a:b] for a, b in spans))
        self.assertTrue(all(b - a <= 160 for a, b in spans))

    def test_preparation_is_idempotent_and_utf16_anchors_round_trip(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.sqlite"
            library = sqlite3.connect(source)
            library.executescript(
                "CREATE TABLE books(id TEXT,title TEXT); CREATE TABLE chapters(id TEXT,book_id TEXT,chapter_index INTEGER,title TEXT,body TEXT);"
            )
            library.execute("INSERT INTO books VALUES ('b','测试书')")
            library.execute(
                "INSERT INTO chapters VALUES ('c','b',0,'第一章',?)",
                ("門外😨有人！\n\n「別開門。」",),
            )
            library.commit()
            with closing(connect(root / "output")) as db, db:
                prepare(db, source)
                before = db.execute("SELECT count(*) FROM segments").fetchone()[0]
                prepare(db, source)
                self.assertEqual(
                    before, db.execute("SELECT count(*) FROM segments").fetchone()[0]
                )
                text = db.execute("SELECT canonical_text FROM chapters").fetchone()[0]
                encoded = text.encode("utf-16-le")
                for row in db.execute("SELECT * FROM segments"):
                    actual = encoded[
                        2 * row["start_utf16"] : 2 * row["end_utf16"]
                    ].decode("utf-16-le")
                    self.assertEqual(actual, row["original_text"])
                first = db.execute(
                    "SELECT * FROM segments ORDER BY segment_index LIMIT 1"
                ).fetchone()
                self.assertEqual("门外😨有人！", first["spoken_text"])
                self.assertEqual(utf16_len("　　門外😨有人！"), first["end_utf16"])
                library.execute("UPDATE chapters SET body='changed'")
                library.commit()
                with self.assertRaisesRegex(ValueError, "Chapter text changed"):
                    prepare(db, source)
            library.close()

    def test_audio_export_marks_partial_and_keeps_exact_cue_times(self):
        import numpy as np
        import soundfile as sf

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.sqlite"
            with closing(sqlite3.connect(source)) as library, library:
                library.executescript(
                    "CREATE TABLE books(id TEXT,title TEXT); CREATE TABLE chapters(id TEXT,book_id TEXT,chapter_index INTEGER,title TEXT,body TEXT);"
                )
                library.execute("INSERT INTO books VALUES ('b','测试书')")
                library.execute(
                    "INSERT INTO chapters VALUES ('c','b',0,'第一章','第一句。第二句！')"
                )
            output = root / "output"
            with closing(connect(output)) as db, db:
                prepare(db, source)
                db.execute(
                    "INSERT INTO profiles VALUES ('test',?)",
                    (json.dumps(dict(pause_ms=180)),),
                )
                rows = db.execute(
                    "SELECT * FROM segments ORDER BY segment_index"
                ).fetchall()

                def add_audio(row, count):
                    relative = f"{row['id']}.flac"
                    sf.write(output / relative, np.full(count, 0.1), 24_000)
                    db.execute(
                        "INSERT INTO audio(profile_id,segment_id,path,sample_rate,samples,sha256,status) VALUES ('test',?,?,?,?,?, 'ready')",
                        (
                            row["id"],
                            relative,
                            24_000,
                            count,
                            digest((output / relative).read_bytes()),
                        ),
                    )

                add_audio(rows[0], 6000)
                export_chapters(db, output, "test", 1, 1, speed=1.0)
                self.assertFalse((output / "chapters/test/0001.m4a").exists())
                export_chapters(db, output, "test", 1, 1, partial=True, speed=1.0)
                preview = json.loads(
                    (output / "chapters/test/0001.preview.json").read_text()
                )
                self.assertFalse(preview["complete"])
                self.assertEqual(250, preview["duration_ms"])
                self.assertEqual(1, len(pending(db, output, "test", 1, 1)))

                add_audio(rows[1], 12000)
                export_chapters(db, output, "test", 1, 1, speed=1.0)
                manifest = json.loads((output / "chapters/test/0001.json").read_text())
                self.assertTrue(manifest["complete"])
                self.assertEqual(
                    [(0, 250), (430, 930)],
                    [(c["start_ms"], c["end_ms"]) for c in manifest["cues"]],
                )
                self.assertEqual(0, len(pending(db, output, "test", 1, 1)))
                export_chapters(db, output, "test", 1, 1, speed=1.15)
                faster = json.loads((output / "chapters/test/0001.json").read_text())
                self.assertEqual(1.15, faster["speed"])
                self.assertLess(faster["duration_ms"], manifest["duration_ms"])
                self.assertEqual(
                    180, faster["cues"][1]["start_ms"] - faster["cues"][0]["end_ms"]
                )
                self.assertEqual(6000, sf.info(output / f"{rows[0]['id']}.flac").frames)
                retry_segments(db, output, "test", 1, [2])
                self.assertEqual(
                    [rows[1]["id"]],
                    [r["id"] for r in pending(db, output, "test", 1, 1)],
                )
                self.assertEqual(
                    1, len(list((output / "quality/test/rejected").glob("*.flac")))
                )
                db.execute(
                    "UPDATE audio SET status='ready' WHERE segment_id=?",
                    (rows[1]["id"],),
                )
                (output / f"{rows[0]['id']}.flac").unlink()
                self.assertEqual(1, len(pending(db, output, "test", 1, 1)))

    def test_book_chapter_numbers_and_duplicate_titles_preserve_all_content(self):
        import numpy as np
        import soundfile as sf

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.sqlite"
            with closing(sqlite3.connect(source)) as library, library:
                library.executescript(
                    "CREATE TABLE books(id TEXT,title TEXT); CREATE TABLE chapters(id TEXT,book_id TEXT,chapter_index INTEGER,title TEXT,body TEXT);"
                )
                library.execute("INSERT INTO books VALUES ('b','测试书')")
                library.executemany(
                    "INSERT INTO chapters VALUES (?,'b',?,?,?)",
                    [
                        ("c850", 848, "第850章 鬼霧瓶", "甲。"),
                        ("c851", 849, "第851章 黑佛母", "乙。"),
                        ("c1169a", 1167, "第1169章 過夜", "丙。"),
                        ("c1169b", 1168, "第1169章 ：夜探文化宮", "丁。"),
                    ],
                )
            output = root / "output"
            with closing(connect(output)) as db, db:
                prepare(db, source)
                db.execute(
                    "INSERT INTO profiles VALUES ('test',?)",
                    (json.dumps(dict(pause_ms=180)),),
                )
                self.assertEqual(
                    ["c850"],
                    [r["chapter_id"] for r in pending(db, output, "test", 850, 850)],
                )
                self.assertEqual(
                    ["c1169a", "c1169b"],
                    [r["chapter_id"] for r in pending(db, output, "test", 1169, 1169)],
                )
                for row in pending(db, output, "test", 850, 1169):
                    path = f"{row['id']}.flac"
                    sf.write(output / path, np.full(6000, 0.1), 24000)
                    db.execute(
                        "INSERT INTO audio(profile_id,segment_id,path,sample_rate,samples,status) VALUES ('test',?,?,24000,6000,'ready')",
                        (row["id"], path),
                    )
                export_chapters(db, output, "test", 850, 850, speed=1)
                manifest = json.loads((output / "chapters/test/0850.json").read_text())
                self.assertEqual(
                    (850, 848), (manifest["chapter_number"], manifest["chapter_index"])
                )
                export_chapters(db, output, "test", 1169, 1169, speed=1)
                manifests = [
                    json.loads(p.read_text())
                    for p in sorted((output / "chapters/test").glob("1169*.json"))
                ]
                self.assertEqual(
                    ["c1169a", "c1169b"], [m["chapter_id"] for m in manifests]
                )
                self.assertTrue((output / "chapters/test/1169-part1.m4a").exists())
                self.assertTrue((output / "chapters/test/1169-part2.m4a").exists())


if __name__ == "__main__":
    unittest.main()
