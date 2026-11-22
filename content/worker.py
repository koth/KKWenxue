"""Fetch source TXT on the server, split chapters and upload ZIPs to relay."""
from __future__ import annotations

from contextlib import closing
import hashlib
import http.client
import json
import logging
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import sqlite3
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import zipfile
from collections import Counter

log = logging.getLogger("shanye.content")
logging.basicConfig(level=os.environ.get("SHANYE_CONTENT_LOG", "INFO"), format="%(asctime)s %(levelname)s %(message)s")

ROOT = Path(os.environ.get("SHANYE_CONTENT_ROOT", "~/workspace/shanye-content")).expanduser()
BOOKS = ROOT / "books"
PACKS = ROOT / "packs"
DB_PATH = ROOT / "content.db"
SOURCE = os.environ.get("SHANYE_CONTENT_SOURCE", "https://www.suduguu.com").rstrip("/")
SOURCE_TOKEN = os.environ.get("SHANYE_CONTENT_SOURCE_TOKEN", "")
RELAY_URL = os.environ.get("SHANYE_RELAY_URL", "").rstrip("/")
RELAY_TOKEN = os.environ.get("SHANYE_RELAY_TOKEN", "")
WORKER_ID = os.environ.get("SHANYE_RELAY_WORKER_ID", socket.gethostname())
LEASE_SECONDS = max(60, int(os.environ.get("SHANYE_RELAY_LEASE_SECONDS", "600")))
CLAIM_WAIT_SECONDS = max(1, min(20, int(os.environ.get("SHANYE_RELAY_CLAIM_WAIT_SECONDS", "20"))))
DIRECT_URL = os.environ.get("SHANYE_CONTENT_DIRECT_URL", "http://192.168.3.33:17873").rstrip("/")
DIRECT_BIND = os.environ.get("SHANYE_CONTENT_DIRECT_BIND", "0.0.0.0")
DIRECT_PORT = int(os.environ.get("SHANYE_CONTENT_DIRECT_PORT", "17873"))

CHAPTER_RE = re.compile(r"(?m)^[ \t　]*((?:第[0-9０-９一二三四五六七八九十百千万零〇两兩]{1,16}[章节節回卷部集篇][^\r\n]{0,100})|(?:序章|楔子|引子|前言|后记|後記|尾声|尾聲|番外)[^\r\n]{0,80})[ \t　]*$")
FOOTER_RE = re.compile(r"(?m)^[ \t　]*更多精彩小说")
HEADER_PREFIXES = ("作者：", "来源：", "网址：", "作者:", "来源:", "网址:")
MAX_TEXT_BYTES = 64 * 1024 * 1024
MAX_TEXT_PAGES = 100
TEXT_FORMAT = 2
UPDATE_SCHEMA = 2
MAX_UPDATE_CHAPTERS = 2000
MAX_UPDATE_PACKAGE = 64 * 1024 * 1024
MAX_CONTENT_CACHE = int(os.environ.get("SHANYE_CONTENT_CACHE_BYTES", str(64 * 1024**3)))


def check_storage(directory: Path, required: int):
    if shutil.disk_usage(directory).free < required + 16 * 1024 * 1024:
        raise RuntimeError("storage: 正文服务器空间不足")
    used = sum(p.stat().st_size for folder in {BOOKS, PACKS} for p in folder.iterdir() if p.is_file())
    if used + required > MAX_CONTENT_CACHE:
        raise RuntimeError("storage: 正文服务器缓存已达到上限")


def db():
    ROOT.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    con.execute("""CREATE TABLE IF NOT EXISTS content (
        book_id INTEGER PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL,
        chapters INTEGER NOT NULL, chars INTEGER NOT NULL, sha256 TEXT NOT NULL,
        fetched_at INTEGER NOT NULL, latest_chapter_id INTEGER NOT NULL DEFAULT 0,
        path TEXT NOT NULL)""")
    con.execute("""CREATE TABLE IF NOT EXISTS results (
        task_id TEXT PRIMARY KEY, token TEXT NOT NULL, path TEXT NOT NULL,
        created_at INTEGER NOT NULL)""")
    con.execute("""CREATE TABLE IF NOT EXISTS catalog_snapshots (
        book_id INTEGER NOT NULL, catalog_revision TEXT NOT NULL, revision TEXT NOT NULL,
        PRIMARY KEY(book_id, catalog_revision))""")
    # A matching chapter watermark is insufficient for old, potentially
    # truncated TXT caches. Keep them on disk, but fetch anew when requested.
    if "text_format" not in {row["name"] for row in con.execute("PRAGMA table_info(content)")}:
        try:
            con.execute("ALTER TABLE content ADD COLUMN text_format INTEGER NOT NULL DEFAULT 1")
        except sqlite3.OperationalError:
            if "text_format" not in {row["name"] for row in con.execute("PRAGMA table_info(content)")}:
                raise
    if "snapshot_path" not in {row["name"] for row in con.execute("PRAGMA table_info(content)")}:
        con.execute("ALTER TABLE content ADD COLUMN snapshot_path TEXT")
    con.commit()
    return con


def decode(raw: bytes) -> str:
    try:
        return raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        return raw.decode("gb18030", "replace")


def fetch_page(book_id: int, page: int, limit: int) -> bytes:
    headers = {"User-Agent": "shanye-content/1.0", "Accept": "text/plain"}
    if SOURCE_TOKEN:
        headers["X-Relay-Token"] = SOURCE_TOKEN
    request = Request(f"{SOURCE}/txt/?id={book_id}&p={page}", headers=headers)
    with urlopen(request, timeout=45) as response:
        if response.status != 200:
            raise RuntimeError(f"正文 HTTP {response.status}")
        if response.headers.get_content_type() == "text/html":
            raise RuntimeError("正文接口返回了网页而不是文本")
        data = response.read(limit + 1)
    if len(data) > limit:
        raise RuntimeError("正文 TXT 超过大小限制")
    if re.match(rb"(?i)\s*(?:<!doctype|<html)", data.removeprefix(b"\xef\xbb\xbf")):
        raise RuntimeError("正文接口返回了网页而不是文本")
    return data


def fetch_page_with_retry(book_id: int, page: int, limit: int, heartbeat) -> bytes:
    for wait in (0, 1, 3):
        if wait:
            time.sleep(wait)
        heartbeat()
        try:
            return fetch_page(book_id, page, limit)
        except (OSError, http.client.HTTPException) as exc:
            if isinstance(exc, HTTPError) and exc.code not in (408, 429, 500, 502, 503, 504):
                raise RuntimeError(f"书源 TXT 第 {page} 页：HTTP {exc.code}") from exc
            last = exc
    raise RuntimeError(f"书源 TXT 第 {page} 页抓取失败：{last}") from last


def strip_export_page(text: str) -> tuple[str, bool]:
    text = text.replace("\r\n", "\n").replace("\r", "\n").strip()
    footer = FOOTER_RE.search(text)
    lines = (text[:footer.start()] if footer else text).splitlines()
    if len(lines) >= 2 and re.fullmatch(r"《[^》]+》", lines[0].strip()) and lines[1].strip().startswith(HEADER_PREFIXES):
        start = 1
        while start < len(lines) and lines[start].strip().startswith(HEADER_PREFIXES):
            start += 1
        lines = lines[start:]
    return "\n".join(lines).strip(), footer is not None


def split_chapters(text: str) -> list[tuple[str, str]]:
    matches = list(CHAPTER_RE.finditer(text))
    if not matches:
        raise RuntimeError("正文中没有识别到章节标题")
    items: list[tuple[str, str]] = []
    for index, match in enumerate(matches):
        title = re.sub(r"\s+", " ", match.group(1)).strip()
        body = text[match.end(): matches[index + 1].start() if index + 1 < len(matches) else len(text)]
        body = "\n".join(line.strip() for line in body.splitlines()).strip()
        if body:
            items.append((title, body))
    if not items:
        raise RuntimeError("正文章节为空")
    return items


def download_chapters(book_id: int, heartbeat) -> list[tuple[str, str]]:
    chapters = []
    seen: set[bytes] = set()
    remaining = MAX_TEXT_BYTES
    for page in range(1, MAX_TEXT_PAGES + 1):
        raw = fetch_page_with_retry(book_id, page, remaining, heartbeat)
        remaining -= len(raw)
        text, footer = strip_export_page(decode(raw))
        if not text and footer and chapters:
            return chapters
        digest = hashlib.sha256(text.encode("utf-8")).digest()
        if not text or digest in seen:
            raise RuntimeError(f"书源 TXT 第 {page} 页为空或重复，未保存不完整的正文")
        seen.add(digest)
        try:
            chapters.extend(split_chapters(text))
        except RuntimeError as exc:
            raise RuntimeError(f"书源 TXT 第 {page} 页：{exc}") from exc
    raise RuntimeError("书源 TXT 页数超过限制，未保存不完整的正文")


def fetch_book(book_id: int, title: str, author: str, latest_chapter_id: int, heartbeat, catalog_revision: str = "") -> dict:
    chapters = download_chapters(book_id, heartbeat)
    canonical = "\n\n".join(f"{heading}\n{body}" for heading, body in chapters) + "\n"
    data = canonical.encode("utf-8")
    if len(canonical.encode("utf-16-le")) // 2 > 12_000_000 or len(data) > MAX_TEXT_BYTES:
        raise RuntimeError("正文超出整本导入的大小限制")
    digest = hashlib.sha256(data).hexdigest()
    path = BOOKS / f"{book_id}-{digest}.txt"
    snapshot_path = BOOKS / f"{book_id}-{digest}.snapshot-v2.json"
    if not path.is_file() or not snapshot_path.is_file():
        check_storage(BOOKS, len(data) * 3 + len(chapters) * 512)
    if not path.is_file():
        with tempfile.NamedTemporaryFile(dir=BOOKS, suffix=".tmp", delete=False) as tmp:
            temporary = Path(tmp.name)
            try:
                tmp.write(data)
                tmp.close()
                temporary.replace(path)
            finally:
                temporary.unlink(missing_ok=True)
    chapter_entries = []
    occurrences = Counter()
    for index, (heading, body) in enumerate(chapters):
        body_hash = hashlib.sha256(normalize_sync_text(body).encode("utf-8")).hexdigest()
        identity = hashlib.sha256(f"{heading}\n{body_hash}".encode("utf-8")).hexdigest()
        occurrences[identity] += 1
        chapter_entries.append({
            "chapterId": f"online:catalog:{book_id}:chapter:sha256:{identity}:{occurrences[identity]}",
            "index": index, "title": heading, "body": body, "bodySha256": body_hash,
        })
    snapshot = {
        "schema_version": UPDATE_SCHEMA,
        "bookId": book_id,
        "title": title or f"书籍 {book_id}",
        "author": author or "未知作者",
        "revision": digest,
        "chars": len(canonical),
        "chapters": chapter_entries,
    }
    if not snapshot_path.is_file():
        with tempfile.NamedTemporaryFile(dir=BOOKS, suffix=".snapshot.tmp", delete=False) as tmp:
            temporary_snapshot = Path(tmp.name)
            try:
                tmp.write(json.dumps(snapshot, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
                tmp.close()
                temporary_snapshot.replace(snapshot_path)
            finally:
                temporary_snapshot.unlink(missing_ok=True)
    con = db()
    con.execute("""INSERT INTO content(book_id,title,author,chapters,chars,sha256,fetched_at,latest_chapter_id,path,text_format,snapshot_path)
        VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(book_id) DO UPDATE SET title=excluded.title,author=excluded.author,
        chapters=excluded.chapters,chars=excluded.chars,sha256=excluded.sha256,fetched_at=excluded.fetched_at,
        latest_chapter_id=excluded.latest_chapter_id,path=excluded.path,text_format=excluded.text_format,
        snapshot_path=excluded.snapshot_path
        WHERE excluded.latest_chapter_id >= content.latest_chapter_id""",
        (book_id, title or f"书籍 {book_id}", author or "未知作者", len(chapters), len(canonical), digest, int(time.time()), latest_chapter_id, str(path), TEXT_FORMAT, str(snapshot_path)))
    if catalog_revision:
        con.execute("INSERT OR IGNORE INTO catalog_snapshots VALUES(?,?,?)", (book_id, catalog_revision, digest))
    con.commit(); con.close()
    return {"bookId": book_id, "title": title, "author": author, "chapters": len(chapters), "chars": len(canonical), "sha256": digest, "path": str(path),
            "revision": digest, "latestChapterId": latest_chapter_id, "snapshotPath": str(snapshot_path),
            "catalogRevision": catalog_revision,
            "chapterManifest": [{key: item[key] for key in ("chapterId", "index", "title", "bodySha256")} for item in snapshot["chapters"]]}


def content_row(book_id: int):
    con = db(); row = con.execute("SELECT * FROM content WHERE book_id=?", (book_id,)).fetchone(); con.close(); return row


def known_revision(book_id: int, catalog_revision: str) -> str | None:
    if not catalog_revision:
        return None
    with closing(db()) as con:
        row = con.execute("SELECT revision FROM catalog_snapshots WHERE book_id=? AND catalog_revision=?",
                          (book_id, catalog_revision)).fetchone()
    return row["revision"] if row else None


def normalize_sync_text(text: str) -> str:
    raw = text.replace("\r\n", "\n").replace("\r", "\n").replace("\uFFFC", "")
    # Match the Android TXT parser: every non-empty export line is a paragraph.
    lines = (line.strip().strip("\ufeff") for line in raw.split("\n"))
    return "\n\n".join(line for line in lines if line.strip())


def load_snapshot(book_id: int, revision: str | None = None) -> dict | None:
    if revision is None:
        row = content_row(book_id)
        if not row:
            return None
        revision = row["sha256"]
    if not re.fullmatch(r"[0-9a-f]{64}", revision):
        return None
    path = BOOKS / f"{book_id}-{revision}.snapshot-v2.json"
    if not path.is_file():
        return None
    snapshot = json.loads(path.read_text(encoding="utf-8"))
    if snapshot.get("revision") != revision or snapshot.get("bookId") != book_id:
        raise RuntimeError("正文快照身份不匹配")
    return snapshot


def make_update_pack(book_id: int, payload: dict, heartbeat) -> tuple[Path, dict]:
    requested_remote_revision = str(payload.get("targetRevision") or "")
    local = payload.get("chapters") or []
    base = str(payload.get("fromRevision") or "")
    previous = load_snapshot(book_id, base)
    if previous is not None:
        # Positional IDs from the prototype are accepted only if the entire base
        # manifest matches; they are never assigned to new chapters.
        comparable = ("title", "bodySha256")
        expected = previous["chapters"]
        if len(local) != len(expected) or any(
            any(actual.get(k) != old.get(k) for k in comparable)
            or actual["chapterId"] not in (old["chapterId"], f"online:catalog:{book_id}:chapter:{i}")
            for i, (actual, old) in enumerate(zip(local, expected))
        ):
            raise RuntimeError("invalid_base: 前置版本与已知正文快照不匹配")
    row = content_row(book_id)
    target_revision = known_revision(book_id, requested_remote_revision)
    target = load_snapshot(book_id, target_revision) if target_revision else None
    if not target:
        try:
            fetched = fetch_book(
                book_id,
                str(payload.get("title") or (row["title"] if row else "")),
                str(payload.get("author") or (row["author"] if row else "")),
                int(payload.get("latestChapterId") or 0),
                heartbeat,
                requested_remote_revision,
            )
        except Exception as exc:
            raise RuntimeError(f"fetch: {exc}") from exc
        target = load_snapshot(book_id, fetched["revision"])
    if not target:
        raise RuntimeError("目标正文快照不可用")
    target_revision = target["revision"]
    if len(local) > MAX_UPDATE_CHAPTERS or len(target.get("chapters", [])) > MAX_UPDATE_CHAPTERS:
        raise RuntimeError("章节数量超过更新限制")
    local_by_id = {str(item["chapterId"]): item for item in local}
    target_chapters = target["chapters"]
    changed = []
    target_ids = {item["chapterId"] for item in target_chapters}
    missing = [item for item in local if item["chapterId"] not in target_ids]
    old_by_title = Counter(item["title"] for item in missing)
    new_by_title = Counter(item["title"] for item in target_chapters if item["chapterId"] not in local_by_id)
    replaced_titles = {title for title, count in old_by_title.items() if count == new_by_title[title] == 1}
    deleted = [item["chapterId"] for item in missing]
    for item in target_chapters:
        old = local_by_id.get(str(item["chapterId"]))
        if old is None or old.get("bodySha256") != item["bodySha256"] or old.get("title") != item["title"]:
            changed.append(item)
    shared_local = [str(item["chapterId"]) for item in local]
    shared_target = [str(item["chapterId"]) for item in target_chapters if str(item["chapterId"]) in local_by_id]
    reordered = shared_local != shared_target
    append_only = not deleted and not reordered and all(
        str(item["chapterId"]) not in local_by_id or
        (local_by_id[str(item["chapterId"])].get("bodySha256") == item["bodySha256"] and
         local_by_id[str(item["chapterId"])].get("title") == item["title"])
        for item in target_chapters
    ) and len(target_chapters) >= len(local)
    prefix = [str(item["chapterId"]) for item in target_chapters[:len(local)]]
    append_only = append_only and prefix == shared_local
    package_manifest = {
        "schema_version": UPDATE_SCHEMA,
        "kind": "content.update",
        "bookId": book_id,
        "fromRevision": str(payload.get("fromRevision") or ""),
        "toRevision": target_revision,
        "remoteRevision": requested_remote_revision,
        "latestChapterId": int(payload.get("latestChapterId") or 0),
        "chapters": [],
        "deletedChapterIds": deleted,
        "appendOnly": append_only,
        "changedChapterCount": len(changed),
        "addedTitles": [item["title"] for item in changed if item["chapterId"] not in local_by_id and item["title"] not in replaced_titles],
        "modifiedTitles": [item["title"] for item in changed if item["chapterId"] in local_by_id or item["title"] in replaced_titles],
        "deletedTitles": [item["title"] for item in missing if item["title"] not in replaced_titles],
        "reordered": reordered or (not deleted and not append_only),
    }
    for item in target_chapters:
        entry = {key: item[key] for key in ("chapterId", "index", "title", "bodySha256")}
        if item in changed:
            entry["bodyEntry"] = f"chapters/{item['index']:05d}.txt"
        package_manifest["chapters"].append(entry)
    key = hashlib.sha256(json.dumps(package_manifest, sort_keys=True, ensure_ascii=False).encode("utf-8")).hexdigest()
    path = PACKS / f"{book_id}-{key}.update.zip"
    if not path.is_file():
        check_storage(PACKS, sum(len(item["body"].encode()) for item in changed) + 2 * 1024 * 1024)
        with tempfile.NamedTemporaryFile(dir=PACKS, suffix=".update.tmp", delete=False) as tmp:
            temporary = Path(tmp.name)
        try:
            with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
                z.writestr("manifest.json", json.dumps(package_manifest, ensure_ascii=False, separators=(",", ":")))
                for item in changed:
                    z.writestr(f"chapters/{item['index']:05d}.txt", item["body"])
            if temporary.stat().st_size > MAX_UPDATE_PACKAGE:
                raise RuntimeError("正文更新包超过大小限制")
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)
    return path, package_manifest


def registered_path(task_id: str) -> Path | None:
    with closing(db()) as con:
        row = con.execute("SELECT path FROM results WHERE task_id=?", (task_id,)).fetchone()
    return Path(row["path"]) if row else None


def make_pack(book_id: int, version: str, fetch_task_id: str | None = None) -> tuple[Path, dict]:
    row = content_row(book_id)
    if fetch_task_id:
        original = registered_path(fetch_task_id)
        if not original or not original.is_file():
            raise RuntimeError(f"书籍 {book_id} 的正文快照已不可用")
        snapshot = json.loads(original.read_text(encoding="utf-8"))
        digest = snapshot.get("sha256", "")
        if snapshot.get("bookId") != book_id or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise RuntimeError("抓取任务正文快照无效")
        text_path = BOOKS / f"{book_id}-{digest}.txt"
        if not text_path.is_file() and row and row["sha256"] == digest:
            text_path = Path(row["path"])  # Old fetch manifests without content-addressed filenames.
        version = digest
    else:
        if not row:
            raise RuntimeError(f"书籍 {book_id} 正文尚未抓取")
        snapshot, text_path = row, Path(row["path"])
    if not text_path.is_file():
        raise RuntimeError(f"书籍 {book_id} 正文尚未抓取")
    manifest = {"bookId": book_id, **{key: snapshot[key] for key in ("title", "author", "chapters", "chars", "sha256")}, "version": version}
    remote = snapshot.get("catalogRevision") if fetch_task_id else None
    if remote:
        manifest["catalogRevision"] = remote
        manifest["latestChapterId"] = snapshot.get("latestChapterId", 0)
    variant = "-" + hashlib.sha256(remote.encode()).hexdigest()[:20] if remote else ""
    path = PACKS / f"{book_id}-{manifest['sha256']}{variant}.zip"
    if not path.is_file():
        with tempfile.NamedTemporaryFile(dir=PACKS, suffix=".tmp", delete=False) as tmp:
            temporary = Path(tmp.name)
        try:
            with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
                z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, separators=(",", ":")))
                z.write(text_path, "book.txt")
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)
    return path, manifest


class DirectServer:
    def __init__(self):
        self.lock = threading.Lock()
        self.records: dict[str, tuple[str, Path]] = {}
        self.httpd = ThreadingHTTPServer((DIRECT_BIND, DIRECT_PORT), self.handler())

    def handler(self):
        owner = self
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                match = re.fullmatch(r"/v1/content/([A-Za-z0-9-]+)", self.path)
                if not match:
                    self.send_error(404); return
                task_id = match.group(1)
                with owner.lock:
                    record = owner.records.get(task_id)
                if not record:
                    con = db(); row = con.execute("SELECT token,path FROM results WHERE task_id=?", (task_id,)).fetchone(); con.close()
                    record = (row["token"], Path(row["path"])) if row else None
                if not record or self.headers.get("X-Result-Token") != record[0] or not record[1].is_file():
                    self.send_error(404); return
                data = record[1].read_bytes()
                self.send_response(200); self.send_header("Content-Type", "application/zip" if record[1].suffix == ".zip" else "application/json")
                self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
            def log_message(self, fmt, *args): log.debug("direct: " + fmt, *args)
        return Handler

    def register(self, task_id: str, path: Path) -> str:
        token = secrets.token_urlsafe(32)
        with self.lock: self.records[task_id] = (token, path)
        con = db(); con.execute("INSERT OR REPLACE INTO results(task_id,token,path,created_at) VALUES(?,?,?,?)", (task_id, token, str(path), int(time.time()))); con.commit(); con.close()
        return token

    def start(self): threading.Thread(target=self.httpd.serve_forever, daemon=True, name="content-direct").start()


class Relay:
    def __init__(self):
        if not RELAY_URL or not RELAY_TOKEN: raise RuntimeError("SHANYE_RELAY_URL/TOKEN 未配置")
    def request(self, method: str, path: str, payload=None):
        headers = {"X-Relay-Token": RELAY_TOKEN, "Accept": "application/json"}
        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(); headers["Content-Type"] = "application/json"
        req = Request(RELAY_URL + path, data=data, headers=headers, method=method)
        try:
            with urlopen(req, timeout=45) as response:
                raw = response.read(); return response.status, json.loads(raw) if raw else {}
        except HTTPError as exc:
            raise RuntimeError(f"relay HTTP {exc.code}: {exc.read()[:500]!r}") from exc
    def claim(self):
        return self.request("POST", "/v1/tasks/claim", {"worker_id": WORKER_ID, "kinds": ["content.fetch", "content.serve", "content.update"], "lease_seconds": LEASE_SECONDS, "wait_seconds": CLAIM_WAIT_SECONDS})[1].get("task")
    def heartbeat(self, task, phase):
        return self.request("POST", f"/v1/tasks/{task['id']}/heartbeat", {"worker_id": WORKER_ID, "lease_token": task["lease_token"], "lease_seconds": LEASE_SECONDS, "progress": {"phase": phase}})
    def complete(self, task, filename, content_type, path, direct_token, progress=None):
        data = path.read_bytes(); digest = hashlib.sha256(data).hexdigest()
        payload = {"worker_id": WORKER_ID, "lease_token": task["lease_token"], "filename": filename, "content_type": content_type, "sha256": digest, "bytes": len(data), "direct": {"url": f"{DIRECT_URL}/v1/content/{task['id']}", "token": direct_token, "expires_at": int(time.time()) + 86400}}
        if progress is not None:
            payload["progress"] = progress
        return self.request("POST", f"/v1/tasks/{task['id']}/complete", payload)
    def fail(self, task, error):
        return self.request("POST", f"/v1/tasks/{task['id']}/fail", {"worker_id": WORKER_ID, "lease_token": task["lease_token"], "error": str(error)[:4000], "retryable": not str(error).startswith("invalid_base:")})
    def claim_transfer(self):
        return self.request("POST", "/v1/transfers/claim", {"worker_id": WORKER_ID, "lease_seconds": LEASE_SECONDS})[1].get("task")
    def upload_transfer(self, task, path: Path):
        data = path.read_bytes(); digest = hashlib.sha256(data).hexdigest(); parsed = urlsplit(RELAY_URL)
        conn = http.client.HTTPConnection(parsed.hostname, parsed.port or 80, timeout=180) if parsed.scheme == "http" else http.client.HTTPSConnection(parsed.hostname, parsed.port or 443, timeout=180)
        try:
            conn.request("POST", f"/v1/tasks/{task['id']}/result", data, {"X-Relay-Token": RELAY_TOKEN, "X-Worker-Id": WORKER_ID, "X-Lease-Token": task["lease_token"], "X-Filename": path.name, "X-SHA256": digest, "Content-Type": "application/zip", "Content-Length": str(len(data))})
            response = conn.getresponse(); raw = response.read()
            if response.status // 100 != 2: raise RuntimeError(f"relay 上传 HTTP {response.status}: {raw[:500]!r}")
        finally: conn.close()


def handle(relay: Relay, direct: DirectServer, task: dict):
    payload = task.get("payload") or {}; kind = task.get("kind")
    if kind == "content.fetch":
        book_id = int(payload["bookId"]); row = content_row(book_id)
        remote = str(payload.get("catalogRevision") or "")
        cached_revision = known_revision(book_id, remote)
        if cached_revision and (BOOKS / f"{book_id}-{cached_revision}.txt").is_file() and (cached := load_snapshot(book_id, cached_revision)):
            result = {"bookId": book_id, "title": payload.get("title") or cached["title"], "author": payload.get("author") or cached["author"],
                      "chapters": len(cached["chapters"]), "chars": cached.get("chars", 0),
                      "sha256": cached_revision, "catalogRevision": remote, "latestChapterId": int(payload.get("latestChapterId") or 0)}
        elif not remote and row and row["text_format"] == TEXT_FORMAT and int(row["latest_chapter_id"]) == int(payload.get("latestChapterId") or 0) and Path(row["path"]).is_file():
            result = {"bookId": book_id, "title": row["title"], "author": row["author"], "chapters": row["chapters"], "chars": row["chars"], "sha256": row["sha256"]}
        else:
            next_heartbeat = 0.0

            def heartbeat():
                nonlocal next_heartbeat
                if time.monotonic() >= next_heartbeat:
                    relay.heartbeat(task, "fetch-txt")
                    next_heartbeat = time.monotonic() + min(45, LEASE_SECONDS / 3)

            result = fetch_book(book_id, payload.get("title", ""), payload.get("author", ""), int(payload.get("latestChapterId") or 0), heartbeat, remote)
        manifest = ROOT / f"manifest-{book_id}-{task['id']}.json"; manifest.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
        token = direct.register(task["id"], manifest); relay.complete(task, manifest.name, "application/json", manifest, token)
    elif kind == "content.update":
        book_id = int(payload["bookId"])
        next_heartbeat = 0.0

        def heartbeat():
            nonlocal next_heartbeat
            if time.monotonic() >= next_heartbeat:
                relay.heartbeat(task, "fetch-txt")
                next_heartbeat = time.monotonic() + min(45, LEASE_SECONDS / 3)

        path, manifest = make_update_pack(book_id, payload, heartbeat)
        token = direct.register(task["id"], path)
        plan = {key: manifest[key] for key in ("bookId", "fromRevision", "toRevision", "remoteRevision",
                                                "appendOnly", "addedTitles", "modifiedTitles", "deletedTitles", "reordered")}
        relay.complete(task, path.name, "application/zip", path, token, {"phase": "packaged", "plan": plan})
        log.info("kind=%s stage=packaged task=%s book=%s from_revision=%s to_revision=%s bytes=%s attempt=%s relay_state=none",
                 kind, task["id"], book_id, plan["fromRevision"], plan["toRevision"], path.stat().st_size, task.get("attempts"))
    elif kind == "content.serve":
        book_id = int(payload["bookId"])
        path, _ = make_pack(book_id, str(payload.get("version", "")), payload.get("fetchTaskId"))
        token = direct.register(task["id"], path); relay.complete(task, path.name, "application/zip", path, token)
    else:
        raise RuntimeError(f"不支持的任务类型 {kind}")


def main():
    for path in (BOOKS, PACKS): path.mkdir(parents=True, exist_ok=True)
    relay = Relay(); direct = DirectServer(); direct.start(); log.info("content worker ready: root=%s direct=%s", ROOT, DIRECT_URL)
    while True:
        try:
            transfer = relay.claim_transfer()
            if transfer:
                # transfer claim intentionally returns only the lease capability;
                # fetch the task payload separately before locating the local pack.
                _, full = relay.request("GET", f"/v1/tasks/{transfer['id']}")
                transfer.update({"kind": full.get("kind"), "payload": full.get("payload")})
                plan = (full.get("progress") or {}).get("plan", {})
                try:
                    if transfer.get("kind") not in ("content.serve", "content.update"):
                        raise RuntimeError("仅正文 ZIP 支持正文中转")
                    path = registered_path(transfer["id"])
                    if not path or not path.is_file():
                        raise RuntimeError("原正文包已不存在，请重新发起导入")
                    relay.upload_transfer(transfer, path)
                    log.info("kind=%s stage=transfer task=%s to_revision=%s bytes=%s attempt=%s relay_state=ready",
                             transfer["kind"], transfer["id"], plan.get("toRevision"), path.stat().st_size,
                             (full.get("result") or {}).get("relay_attempts"))
                except Exception as exc:
                    log.exception("kind=%s stage=transfer task=%s to_revision=%s attempt=%s relay_state=failed reason=%s",
                                  transfer.get("kind"), transfer["id"], plan.get("toRevision"),
                                  (full.get("result") or {}).get("relay_attempts"), type(exc).__name__)
                    relay.request("POST", f"/v1/tasks/{transfer['id']}/transfer/fail",
                                  {"worker_id": WORKER_ID, "lease_token": transfer["lease_token"],
                                   "error": str(exc)[:1000], "retryable": isinstance(exc, OSError)})
                continue
            task = relay.claim()
            if not task: continue
            try: handle(relay, direct, task)
            except Exception as exc:
                cause = str(exc).split(":", 1)[0]
                if cause not in ("fetch", "invalid_base", "storage"):
                    cause = type(exc).__name__
                log.exception("kind=%s stage=execution task=%s target_revision=%s attempt=%s reason=%s",
                              task.get("kind"), task.get("id"), (task.get("payload") or {}).get("targetRevision"),
                              task.get("attempts"), cause)
                try: relay.fail(task, exc)
                except Exception: log.exception("could not report failure")
        except Exception:
            log.exception("relay poll failed"); time.sleep(5)


if __name__ == "__main__": main()
