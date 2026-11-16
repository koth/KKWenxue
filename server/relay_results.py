"""Scoped LAN downloads for published relay results. No service/admin token is shared."""
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import secrets
import sqlite3
import threading
import time
import uuid

from tts.common import file_digest


class ResultStore:
    def __init__(self, settings):
        self.settings = settings
        self.database = settings.state / "relay-results.sqlite"
        settings.prepare()
        with closing(self.connect()) as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("""CREATE TABLE IF NOT EXISTS results (
                id TEXT PRIMARY KEY, path TEXT NOT NULL, token TEXT NOT NULL,
                bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, expires INTEGER NOT NULL)""")

    def connect(self):
        db = sqlite3.connect(self.database, timeout=10, isolation_level=None)
        db.row_factory = sqlite3.Row
        return db

    def get(self, identifier):
        with closing(self.connect()) as db:
            row = db.execute("SELECT * FROM results WHERE id=?", (identifier,)).fetchone()
        return dict(row) if row else None

    def path(self, record):
        if not record or record["expires"] <= time.time():
            return None
        path = Path(record["path"]).resolve()
        if not path.is_relative_to(self.settings.audio.resolve()) or not path.is_file():
            return None
        if path.stat().st_size != record["bytes"]:
            return None
        return path

    def publish(self, identifier, path, expires, base_url):
        # Stable per-task capability survives worker restarts and uncertain completion POSTs.
        uuid.UUID(identifier)
        old = self.get(identifier)
        token = old["token"] if old else secrets.token_urlsafe(32)
        record = {"id": identifier, "path": str(path.resolve()), "token": token,
                  "bytes": path.stat().st_size, "sha256": file_digest(path), "expires": int(expires)}
        if not self.path(record):
            raise ValueError("本地结果不可用或已过期")
        with closing(self.connect()) as db:
            db.execute("INSERT OR REPLACE INTO results VALUES (?,?,?,?,?,?)",
                       tuple(record[k] for k in ("id", "path", "token", "bytes", "sha256", "expires")))
        return {"filename": path.name, "content_type": "application/zip", "bytes": record["bytes"],
                "sha256": record["sha256"], "direct": {"url": base_url.rstrip('/') + f"/v1/results/{identifier}",
                                                          "token": token, "expires_at": record["expires"]}}


class ResultServer:
    def __init__(self, store, host="0.0.0.0", port=17871):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                prefix = "/v1/results/"
                identifier = self.path[len(prefix):] if self.path.startswith(prefix) else ""
                record = store.get(identifier)
                if not record or not secrets.compare_digest(self.headers.get("X-Result-Token", ""), record["token"]):
                    self.send_error(401)
                    return
                path = store.path(record)
                if not path:
                    self.send_error(410)
                    return
                try:
                    with path.open("rb") as source:
                        self.send_response(200)
                        self.send_header("Content-Type", "application/zip")
                        self.send_header("Content-Length", str(record["bytes"]))
                        self.send_header("X-SHA256", record["sha256"])
                        self.send_header("Cache-Control", "no-store")
                        self.end_headers()
                        while block := source.read(256 * 1024):
                            self.wfile.write(block)
                except (OSError, TimeoutError):
                    pass

            def setup(self):
                super().setup()
                self.connection.settimeout(30)

            def log_message(self, *_):
                pass  # No capability tokens or download identifiers in access logs.

        self.http = ThreadingHTTPServer((host, port), Handler)
        self.thread = threading.Thread(target=self.http.serve_forever, name="relay-lan-results", daemon=True)

    def start(self):
        self.thread.start()

    def close(self):
        self.http.shutdown()
        self.http.server_close()
        self.thread.join(timeout=5)
