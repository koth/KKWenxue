"""Fetch novel text at home and expose LAN-first ZIP results.

The worker keeps the large source text on the home machine.  Relay completion
messages contain only a short manifest or ZIP capability; the App tries that
LAN URL first and requests a relay transfer only when the LAN path is not
reachable.
"""
from __future__ import annotations

import hashlib
import http.client
import io
import json
import logging
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
import zipfile

log = logging.getLogger("shanye.content")
logging.basicConfig(level=os.environ.get("SHANYE_CONTENT_LOG", "INFO"), format="%(asctime)s %(levelname)s %(message)s")

ROOT = Path(os.environ.get("SHANYE_CONTENT_ROOT", "~/workspace/shanye-content")).expanduser()
BOOKS = ROOT / "books"
PACKS = ROOT / "packs"
DB_PATH = ROOT / "content.db"
SOURCE = os.environ.get("SHANYE_CONTENT_SOURCE", "https://www.suduguu.com").rstrip("/")
RELAY_URL = os.environ.get("SHANYE_RELAY_URL", "").rstrip("/")
RELAY_TOKEN = os.environ.get("SHANYE_RELAY_TOKEN", "")
WORKER_ID = os.environ.get("SHANYE_RELAY_WORKER_ID", socket.gethostname())
LEASE_SECONDS = max(60, int(os.environ.get("SHANYE_RELAY_LEASE_SECONDS", "600")))
DIRECT_URL = os.environ.get("SHANYE_CONTENT_DIRECT_URL", "http://192.168.3.33:17873").rstrip("/")
DIRECT_BIND = os.environ.get("SHANYE_CONTENT_DIRECT_BIND", "0.0.0.0")
DIRECT_PORT = int(os.environ.get("SHANYE_CONTENT_DIRECT_PORT", "17873"))

CHAPTER_RE = re.compile(r"(?m)^\s*((?:第[0-9０-９一二三四五六七八九十百千万零〇两兩]{1,16}[章节節回卷部集篇](?:[^\r\n]{0,100})?)|(?:序章|楔子|引子|前言|后记|後記|尾声|尾聲|番外)(?:[^\r\n]{0,80})?)\s*$")


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
    con.commit()
    return con


def decode(raw: bytes) -> str:
    try:
        return raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        return raw.decode("gb18030", "replace")


def fetch_page(book_id: int, page: int) -> bytes:
    request = Request(f"{SOURCE}/txt/?id={book_id}&p={page}", headers={"User-Agent": "shanye-content/1.0", "Accept": "text/plain"})
    with urlopen(request, timeout=180) as response:
        if response.status != 200:
            raise RuntimeError(f"正文 HTTP {response.status}")
        data = response.read()
    if data.startswith(b"<!DOCTYPE") or data.startswith(b"<html"):
        raise RuntimeError("正文接口返回了网页而不是文本")
    return data


def split_chapters(text: str) -> list[tuple[str, str]]:
    # Remove the site footer and retain chapter headings exactly for App import.
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.split(r"\n\s*更多精彩小说", text, maxsplit=1)[0]
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


def fetch_book(book_id: int, title: str, author: str, latest_chapter_id: int) -> dict:
    pieces: list[str] = []
    for page in range(1, 1000):
        raw = fetch_page(book_id, page)
        text = decode(raw)
        # Empty/header-only pages are the site's end marker (about 165 bytes).
        if page > 1 and (len(raw) < 512 or len(text.strip()) < 100):
            break
        if not pieces and text.startswith("《"):
            # The first page carries a four-line export header; keep metadata in manifest.
            lines = text.splitlines()
            while lines and (not lines[0].strip() or lines[0].strip().startswith(("《", "作者", "来源", "网址"))):
                lines.pop(0)
            text = "\n".join(lines)
        pieces.append(text)
        if len(raw) < 512:
            break
    if not pieces:
        raise RuntimeError("没有下载到正文")
    text = "\n\n".join(pieces)
    chapters = split_chapters(text)
    canonical = "\n\n".join(f"{heading}\n{body}" for heading, body in chapters) + "\n"
    data = canonical.encode("utf-8")
    digest = hashlib.sha256(data).hexdigest()
    path = BOOKS / f"{book_id}.txt"
    tmp = path.with_suffix(".tmp")
    tmp.write_bytes(data)
    tmp.replace(path)
    con = db()
    con.execute("""INSERT INTO content(book_id,title,author,chapters,chars,sha256,fetched_at,latest_chapter_id,path)
        VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(book_id) DO UPDATE SET title=excluded.title,author=excluded.author,
        chapters=excluded.chapters,chars=excluded.chars,sha256=excluded.sha256,fetched_at=excluded.fetched_at,
        latest_chapter_id=excluded.latest_chapter_id,path=excluded.path""",
        (book_id, title or f"书籍 {book_id}", author or "未知作者", len(chapters), len(canonical), digest, int(time.time()), latest_chapter_id, str(path)))
    con.commit(); con.close()
    return {"bookId": book_id, "title": title, "author": author, "chapters": len(chapters), "chars": len(canonical), "sha256": digest, "path": str(path)}


def content_row(book_id: int):
    con = db(); row = con.execute("SELECT * FROM content WHERE book_id=?", (book_id,)).fetchone(); con.close(); return row


def make_pack(book_id: int, version: str) -> tuple[Path, dict]:
    row = content_row(book_id)
    if not row or not Path(row["path"]).is_file():
        raise RuntimeError(f"书籍 {book_id} 正文尚未抓取")
    text_path = Path(row["path"])
    manifest = {"bookId": book_id, "title": row["title"], "author": row["author"], "chapters": row["chapters"], "chars": row["chars"], "sha256": row["sha256"], "version": version}
    path = PACKS / f"{book_id}-{row['sha256'][:12]}.zip"
    if not path.is_file():
        tmp = path.with_suffix(".tmp")
        with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
            z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, separators=(",", ":")))
            z.write(text_path, "book.txt")
        tmp.replace(path)
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
        return self.request("POST", "/v1/tasks/claim", {"worker_id": WORKER_ID, "kinds": ["content.fetch", "content.serve"], "lease_seconds": LEASE_SECONDS, "wait_seconds": 20})[1].get("task")
    def heartbeat(self, task, phase):
        return self.request("POST", f"/v1/tasks/{task['id']}/heartbeat", {"worker_id": WORKER_ID, "lease_token": task["lease_token"], "lease_seconds": LEASE_SECONDS, "progress": {"phase": phase}})
    def complete(self, task, filename, content_type, path, direct_token):
        data = path.read_bytes(); digest = hashlib.sha256(data).hexdigest()
        payload = {"worker_id": WORKER_ID, "lease_token": task["lease_token"], "filename": filename, "content_type": content_type, "sha256": digest, "bytes": len(data), "direct": {"url": f"{DIRECT_URL}/v1/content/{task['id']}", "token": direct_token, "expires_at": int(time.time()) + 86400}}
        return self.request("POST", f"/v1/tasks/{task['id']}/complete", payload)
    def fail(self, task, error):
        return self.request("POST", f"/v1/tasks/{task['id']}/fail", {"worker_id": WORKER_ID, "lease_token": task["lease_token"], "error": str(error)[:4000], "retryable": True})
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
        if row and int(row["latest_chapter_id"]) == int(payload.get("latestChapterId") or 0) and Path(row["path"]).is_file():
            result = {"bookId": book_id, "title": row["title"], "author": row["author"], "chapters": row["chapters"], "chars": row["chars"], "sha256": row["sha256"]}
        else:
            relay.heartbeat(task, "fetch")
            result = fetch_book(book_id, payload.get("title", ""), payload.get("author", ""), int(payload.get("latestChapterId") or 0))
        manifest = ROOT / f"manifest-{book_id}-{task['id']}.json"; manifest.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
        token = direct.register(task["id"], manifest); relay.complete(task, manifest.name, "application/json", manifest, token)
    elif kind == "content.serve":
        book_id = int(payload["bookId"]); path, manifest = make_pack(book_id, str(payload.get("version", "")))
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
                # transfer result is a serve task; derive the same pack from its payload.
                payload = transfer.get("payload") or {}; path, _ = make_pack(int(payload["bookId"]), str(payload.get("version", "")))
                relay.upload_transfer(transfer, path); continue
            task = relay.claim()
            if not task: continue
            try: handle(relay, direct, task)
            except Exception as exc:
                log.exception("task %s failed", task.get("id"));
                try: relay.fail(task, exc)
                except Exception: log.exception("could not report failure")
        except Exception:
            log.exception("relay poll failed"); time.sleep(5)


if __name__ == "__main__": main()
