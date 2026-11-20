import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from content import worker


def export(chapters=""):
    return f"《测试书》\r\n作者：作者\r\n来源：测试\r\n网址：http://example.test\r\n\r\n{chapters}\r\n\r\n更多精彩小说，请访问测试\r\n".encode()


class ContentWorkerTests(unittest.TestCase):
    def test_txt_pages_are_split_before_footer_removal_can_truncate_book(self):
        with patch.object(worker, "fetch_page", side_effect=[
            export("第1章开场\r\n甲。"),
            export("第2章续篇\r\n乙。\r\n\r\n第3章：最后一页\r\n丙。"),
            export(),
        ]) as fetch:
            self.assertEqual([
                ("第1章开场", "甲。"), ("第2章续篇", "乙。"), ("第3章：最后一页", "丙。"),
            ], worker.download_chapters(1, lambda: None))
        self.assertEqual([1, 2, 3], [call.args[1] for call in fetch.call_args_list])

    def test_fetch_retries_resets_but_does_not_import_an_unexpected_or_repeated_page(self):
        first = export("第1章 正文\r\n第一段。")
        with patch.object(worker, "fetch_page", side_effect=[ConnectionResetError(), first, export()]) as fetch, \
                patch.object(worker.time, "sleep"):
            self.assertEqual(1, len(worker.download_chapters(1, lambda: None)))
        self.assertEqual([1, 1, 2], [call.args[1] for call in fetch.call_args_list])
        for bad in (first, b"", b"invalid response"):
            with patch.object(worker, "fetch_page", side_effect=[first, bad]):
                with self.assertRaisesRegex(RuntimeError, "第 2 页"):
                    worker.download_chapters(1, lambda: None)

    def test_serve_packs_the_requested_snapshot_even_if_book_was_updated(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with patch.multiple(worker, ROOT=root, BOOKS=root, PACKS=root, DB_PATH=root / "content.db"):
                with patch.object(worker, "download_chapters", return_value=[("第1章 开头", "旧正文。")]):
                    old = worker.fetch_book(1, "测试书", "作者", 10, lambda: None)
                snapshot = root / "fetch.json"
                snapshot.write_text(json.dumps(old), encoding="utf-8")
                with patch.object(worker, "download_chapters", return_value=[("第1章 开头", "新正文。")]):
                    worker.fetch_book(1, "测试书", "作者", 11, lambda: None)
                with patch.object(worker, "registered_path", return_value=snapshot):
                    path, manifest = worker.make_pack(1, "", "fetch-task")
                with zipfile.ZipFile(path) as package:
                    text = package.read("book.txt")
                    self.assertIn("旧正文。", text.decode())
                    self.assertEqual(old["sha256"], hashlib.sha256(text).hexdigest())
                    self.assertEqual(manifest, json.loads(package.read("manifest.json")))

    def test_update_pack_contains_only_new_append_chapters(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with patch.multiple(worker, ROOT=root, BOOKS=root, PACKS=root, DB_PATH=root / "content.db"):
                with patch.object(worker, "download_chapters", return_value=[
                    ("第1章 开头", "旧正文。\n第二段。"),
                ]):
                    old = worker.fetch_book(1, "测试书", "作者", 10, lambda: None)
                local = old["chapterManifest"]
                self.assertEqual(local[0]["bodySha256"], hashlib.sha256("旧正文。\n\n第二段。".encode()).hexdigest())
                with patch.object(worker, "download_chapters", return_value=[
                    ("第1章 开头", "旧正文。\n第二段。"),
                    ("第2章 新章", "新增正文。"),
                ]):
                    path, manifest = worker.make_update_pack(1, {
                        "bookId": 1, "title": "测试书", "author": "作者",
                        "latestChapterId": 11, "fromRevision": old["revision"],
                        "targetRevision": "remote-v2", "chapters": local,
                    }, lambda: None)
                self.assertTrue(manifest["appendOnly"])
                self.assertEqual(manifest["changedChapterCount"], 1)
                self.assertEqual(manifest["remoteRevision"], "remote-v2")
                with zipfile.ZipFile(path) as package:
                    names = set(package.namelist())
                    self.assertIn("manifest.json", names)
                    self.assertIn("chapters/00001.txt", names)
                    self.assertNotIn("chapters/00000.txt", names)

    def test_update_pack_marks_rewrites_and_reuses_the_same_package(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with patch.multiple(worker, ROOT=root, BOOKS=root, PACKS=root, DB_PATH=root / "content.db"):
                with patch.object(worker, "download_chapters", return_value=[
                    ("第1章 开头", "旧正文。"), ("第2章 删除", "将被删除。"),
                ]):
                    old = worker.fetch_book(1, "测试书", "作者", 10, lambda: None)
                local = old["chapterManifest"]
                with patch.object(worker, "download_chapters", return_value=[
                    ("第1章 开头", "改写正文。"),
                ]):
                    payload = {
                        "bookId": 1, "title": "测试书", "author": "作者",
                        "latestChapterId": 11, "fromRevision": old["revision"],
                        "targetRevision": "remote-v2", "chapters": local,
                    }
                    first_path, first_manifest = worker.make_update_pack(1, payload, lambda: None)
                    second_path, second_manifest = worker.make_update_pack(1, payload, lambda: None)
                self.assertFalse(first_manifest["appendOnly"])
                self.assertEqual(first_manifest["deletedChapterIds"], [item["chapterId"] for item in local])
                self.assertEqual(first_manifest["modifiedTitles"], ["第1章 开头"])
                self.assertEqual(first_manifest["deletedTitles"], ["第2章 删除"])
                self.assertEqual(first_manifest, second_manifest)
                self.assertEqual(first_path, second_path)

    def test_insert_preserves_existing_chapter_identity_and_rejects_wrong_base(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with patch.multiple(worker, ROOT=root, BOOKS=root, PACKS=root, DB_PATH=root / "content.db"):
                original = [("第1章 开头", "甲。"), ("第3章 后续", "丙。")]
                with patch.object(worker, "download_chapters", return_value=original):
                    old = worker.fetch_book(1, "测试书", "作者", 10, lambda: None)
                payload = {"fromRevision": old["revision"], "targetRevision": "remote-v2",
                           "latestChapterId": 11, "chapters": old["chapterManifest"]}
                with patch.object(worker, "download_chapters", return_value=[
                    original[0], ("第2章 插入", "乙。"), original[1]
                ]):
                    _, manifest = worker.make_update_pack(1, payload, lambda: None)
                self.assertFalse(manifest["appendOnly"])
                self.assertEqual(manifest["addedTitles"], ["第2章 插入"])
                self.assertEqual(manifest["chapters"][2]["chapterId"], old["chapterManifest"][1]["chapterId"])
                payload["chapters"][0]["bodySha256"] = "0" * 64
                with self.assertRaisesRegex(RuntimeError, "invalid_base"):
                    worker.make_update_pack(1, payload, lambda: None)
