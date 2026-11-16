import http.client
import json
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor

import uvicorn

from local.launcher import Transport, make_server
from server.app import create_app
from server.catalog import import_sqlite
from server.cli import create_demo
from server.config import ENGINE, Settings
from server.voices import DEFAULT_PRESET_ID, DEFAULT_CLONE_ID
from server.tests.fixtures import install_test_voice
from server.db import connect, initialize
from server.jobs import submit, detail
from server.worker import Worker
from server.tests.test_service import TestEngine


class ProxyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        root = Path(cls.temp.name)
        cls.settings = Settings(
            state=root / "state", audio=root / "audio", min_free_bytes=0
        )
        install_test_voice(cls.settings)
        source = root / "demo.sqlite"
        create_demo(source)
        cls.book = import_sqlite(cls.settings, source, "studio-demo", "proxy")[
            "book_id"
        ]
        cls.db = connect(cls.settings)
        cls.ids = [
            r[0]
            for r in cls.db.execute("SELECT id FROM chapters ORDER BY chapter_index")
        ]
        cls.request = dict(
            book_id=cls.book,
            chapter_ids=cls.ids,
            voice_preset_version_id=DEFAULT_PRESET_ID,
        )
        job = submit(cls.db, cls.settings, cls.request, "proxy-initial")
        gpu = Worker(cls.settings, engine=TestEngine())
        cpu = Worker(cls.settings, role="cpu")
        for _ in range(10):
            a = gpu.tick()
            b = cpu.tick()
            if not a and not b:
                break
        cls.artifact = detail(cls.db, job)["chapters"][0]["artifact_id"]
        sock = socket.socket()
        sock.bind(("127.0.0.1", 0))
        sock.listen(32)
        cls.api_port = sock.getsockname()[1]
        cls.backend = uvicorn.Server(
            uvicorn.Config(
                create_app(cls.settings), log_level="error", access_log=False
            )
        )
        cls.backend_thread = threading.Thread(
            target=lambda: cls.backend.run(sockets=[sock]), daemon=True
        )
        cls.backend_thread.start()
        for _ in range(100):
            if cls.backend.started:
                break
            time.sleep(0.02)
        cls.transport = Transport(
            "test", cls.api_port, cls.api_port, "unused", cls.settings.token_file, True
        )
        cls.transport.start()
        dist = root / "dist"
        dist.mkdir()
        (dist / "index.html").write_text("<html>studio</html>")
        cls.proxy = make_server(cls.transport, 0, dist, library_path=source)
        cls.port = cls.proxy.server_address[1]
        cls.proxy_thread = threading.Thread(target=cls.proxy.serve_forever, daemon=True)
        cls.proxy_thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.proxy.shutdown()
        cls.proxy.server_close()
        cls.proxy_thread.join(timeout=3)
        cls.backend.should_exit = True
        cls.backend_thread.join(timeout=5)
        cls.transport.close()
        cls.db.close()
        cls.temp.cleanup()

    def request_http(self, path, method="GET", body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            conn.request(method, path, body=body, headers=headers or {})
            response = conn.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            conn.close()

    def test_proxy_range_stream_and_no_long_lived_credentials_in_session(self):
        status, headers, data = self.request_http(
            f"/api/v1/artifacts/{self.artifact}/audio", headers={"Range": "bytes=0-47"}
        )
        self.assertEqual(206, status)
        self.assertEqual(48, len(data))
        self.assertIn("content-range", {k.lower(): v for k, v in headers.items()})
        status, _, _ = self.request_http(
            f"/api/v1/artifacts/{self.artifact}/audio",
            headers={"Range": "bytes=99999999-"},
        )
        self.assertEqual(416, status)
        _, _, session = self.request_http("/session")
        self.assertNotIn(self.settings.token_file.read_text(), session.decode())
        self.assertEqual(200, self.request_http("/api/v1/health")[0])

    def test_foreign_origin_rejected_and_valid_idempotent_write(self):
        _, _, raw = self.request_http("/session")
        session = json.loads(raw)["session"]
        body = json.dumps(self.request)
        common = {
            "Content-Type": "application/json",
            "X-Local-Session": session,
            "Idempotency-Key": "proxy-write",
        }
        invalid = {**common, "Origin": "https://foreign.example"}
        self.assertEqual(
            403, self.request_http("/api/v1/jobs", "POST", body, invalid)[0]
        )
        valid = {**common, "Origin": f"http://127.0.0.1:{self.port}"}
        status, _, response = self.request_http("/api/v1/jobs", "POST", body, valid)
        self.assertEqual(202, status, response)
        again = self.request_http("/api/v1/jobs", "POST", body, valid)
        self.assertEqual(json.loads(response)["id"], json.loads(again[2])["id"])
        self.assertEqual(
            403,
            self.request_http("/api/v1/health", headers={"Host": "foreign.example"})[0],
        )

    def test_concurrent_request_dependencies_use_independent_connections(self):
        with ThreadPoolExecutor(max_workers=12) as pool:
            statuses = list(
                pool.map(lambda _: self.request_http("/api/v1/health")[0], range(40))
            )
        self.assertEqual([200] * 40, statuses)

    def test_local_catalogue_and_inline_estimate_do_not_import_whole_book(self):
        status, _, raw = self.request_http("/api/v1/books")
        self.assertEqual(200, status)
        self.assertEqual("studio-demo", json.loads(raw)["items"][0]["id"])
        before = self.db.execute("select count(*) from chapters").fetchone()[0]
        _, _, raw = self.request_http("/session")
        headers = {
            "Content-Type": "application/json",
            "Origin": f"http://127.0.0.1:{self.port}",
            "X-Local-Session": json.loads(raw)["session"],
        }
        body = json.dumps({"book_id": "studio-demo", "chapter_ids": ["rain-1"]})
        status, _, raw = self.request_http(
            "/api/v1/jobs/estimate", "POST", body, headers
        )
        self.assertEqual(200, status, raw)
        self.assertGreater(json.loads(raw)["segments"], 0)
        self.assertEqual(
            before, self.db.execute("select count(*) from chapters").fetchone()[0]
        )


if __name__ == "__main__":
    unittest.main()
