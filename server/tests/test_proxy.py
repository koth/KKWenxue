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
        cls.proxy = make_server(cls.transport, 0, dist, library_path=source, downloads_path=root/"downloads.sqlite")
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
        self.assertIn("studio-demo", [b["id"] for b in json.loads(raw)["items"]])
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

    def test_server_pull_preserves_identity_and_source_is_read_only(self):
        source=self.proxy.library.path.read_bytes()
        jobs=self.db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0]
        status,_,raw=self.request_http("/api/v1/server-library/books")
        self.assertEqual(200,status,raw)
        self.assertIn(self.book,[b["id"] for b in json.loads(raw)["items"]])
        _,_,raw=self.request_http("/session")
        headers={"Content-Type":"application/json","Origin":f"http://127.0.0.1:{self.port}","X-Local-Session":json.loads(raw)["session"]}
        body=json.dumps({"book_id":self.book,"expression":"1"})
        self.assertEqual(403,self.request_http("/api/v1/server-library/pull","POST",body)[0])
        status,_,raw=self.request_http("/api/v1/server-library/pull","POST",body,headers)
        self.assertEqual(200,status,raw)
        self.assertEqual(1,json.loads(raw)["imported"])
        selected=self.proxy.library.resolve(self.book,"1")["chapters"].pop()
        server=self.db.execute("SELECT * FROM revisions WHERE id=?",(selected["current_revision"],)).fetchone()
        self.assertIsNotNone(server)
        self.assertEqual(server["chapter_id"],selected["id"])
        self.assertEqual(server["canonical_text"],selected["canonical_text"])
        self.assertEqual(server["source_sha256"],selected["source_sha256"])
        # Repeating a whole-book pull merges without duplicate IDs or jobs.
        body=json.dumps({"book_id":self.book,"expression":""})
        for _ in range(2):
            status,_,raw=self.request_http("/api/v1/server-library/pull","POST",body,headers)
            self.assertEqual(200,status,raw)
            self.assertEqual(2,json.loads(raw)["imported"])
        self.assertEqual(2,self.proxy.library.chapters(self.book)["total"])
        self.assertEqual(source,self.proxy.library.path.read_bytes())
        self.assertEqual(jobs,self.db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0])

    def test_server_pull_hash_failure_keeps_last_valid_local_book(self):
        from unittest.mock import patch
        remote=self.proxy.remote_library
        original=remote.request
        def corrupt(path,body=None):
            value=original(path,body)
            if path.startswith("/api/v1/revisions/"):
                value["source_sha256"]="0"*64
            return value
        remote.pull(self.proxy.library,self.book)
        before=self.proxy.library.payload(self.book,self.ids)
        with patch.object(remote,"request",side_effect=corrupt):
            with self.assertRaisesRegex(ValueError,"校验失败"):
                remote.pull(self.proxy.library,self.book)
        self.assertEqual(before,self.proxy.library.payload(self.book,self.ids))

    def test_reference_upload_encodes_locally_before_publishing(self):
        from unittest.mock import patch
        from server.tests.test_voice_upload import reference_payload
        _,_,raw=self.request_http('/session')
        headers={'Content-Type':'multipart/form-data; boundary=voice-boundary',
            'Origin':f'http://127.0.0.1:{self.port}','X-Local-Session':json.loads(raw)['session']}
        body=('--voice-boundary\r\nContent-Disposition: form-data; name="reference"; filename="new.mp3"\r\nContent-Type: audio/mpeg\r\n\r\nmp3-data\r\n'
              '--voice-boundary\r\nContent-Disposition: form-data; name="ref_text"\r\n\r\n这是新的参考录音。\r\n--voice-boundary--\r\n').encode()
        with patch.object(self.proxy.voice_encoder,'prepare',return_value=reference_payload()) as prepare:
            status,_,raw=self.request_http('/api/v1/voice-clones','POST',body,headers)
            self.assertEqual(201,status,raw)
            prepare.assert_called_once_with(b'mp3-data','new.mp3','这是新的参考录音。')
            self.assertTrue(json.loads(raw)['id'].startswith('clone-upload-'))

    def test_workbench_starts_without_a_reader_database(self):
        path=Path(self.temp.name)/"fresh-downloads.sqlite"
        server=make_server(self.transport,0,self.proxy.dist,downloads_path=path)
        try:
            self.assertEqual([],server.library.books())
            result=server.remote_library.pull(server.library,self.book,"1")
            self.assertEqual(1,result["imported"])
            self.assertEqual(1,server.library.books()[0]["chapters"])
        finally:
            server.server_close()
        with self.assertRaisesRegex(ValueError,"不能覆盖"):
            make_server(self.transport,0,self.proxy.dist,library_path=self.proxy.library.path,downloads_path=self.proxy.library.path)


if __name__ == "__main__":
    unittest.main()
