import hashlib
import json
import os
from pathlib import Path
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import zipfile

from server.catalog import import_sqlite
from server.cli import create_demo
from server.config import ENGINE, Settings
from server.voices import DEFAULT_PRESET_ID, DEFAULT_CLONE_ID
from server.tests.fixtures import install_test_voice
from server.db import initialize, connect
from server.remote_worker import (
    LeaseKeeper, LocalJobFailed, RelayClient, RelayConfig, RelayError, RemoteWorker,
)
from server.tests.test_service import TestEngine
from server.worker import Worker


class ClientTests(unittest.TestCase):
    def test_configuration_defaults_blank_worker_id_and_validates_url(self):
        with patch.dict(os.environ, {"SHANYE_RELAY_URL": "https://relay.example", "SHANYE_RELAY_TOKEN": "secret", "SHANYE_RELAY_WORKER_ID": ""}, clear=True):
            config = RelayConfig()
            config.require_ready()
            self.assertTrue(config.worker_id)
            config.url = "https://relay.example/?token=secret"
            with self.assertRaises(RelayError):
                config.require_ready()
            config.url = "ftp://relay.example"
            with self.assertRaises(RelayError):
                config.require_ready()

    def test_upload_streams_bytes_and_lease_headers_over_http(self):
        received = {}
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                received["path"] = self.path
                received["headers"] = self.headers
                received["data"] = self.rfile.read(int(self.headers["Content-Length"]))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"state":"completed"}')
            def log_message(self, *_):
                pass
        http = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=http.serve_forever, daemon=True)
        thread.start()
        try:
            config = RelayConfig()
            config.url = f"http://127.0.0.1:{http.server_port}"
            config.token, config.worker_id = "test-token", "gpu-1"
            client = RelayClient(config)
            with tempfile.TemporaryDirectory() as root:
                path = Path(root) / "bundle.zip"
                data = b"test audio bundle" * 180000
                path.write_bytes(data)
                guard = Mock()
                self.assertEqual("completed", client.upload("task-1", "lease-1", path, guard)["state"])
                self.assertGreater(guard.call_count, 2)
            self.assertEqual(data, received["data"])
            self.assertEqual("/v1/tasks/task-1/result", received["path"])
            self.assertEqual("lease-1", received["headers"]["X-Lease-Token"])
            self.assertEqual(hashlib.sha256(data).hexdigest(), received["headers"]["X-SHA256"])
        finally:
            http.shutdown()
            http.server_close()
            thread.join()

    def test_lease_is_renewed_during_long_operations_and_stale_owner_stops(self):
        relay = Mock()
        relay.config = SimpleNamespace(heartbeat_seconds=0.01, lease_seconds=30, request_timeout=1)
        with LeaseKeeper(relay, {"id":"t1", "lease_token":"old"}) as lease:
            deadline = time.monotonic() + 2
            while relay.heartbeat.call_count < 3 and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertGreaterEqual(relay.heartbeat.call_count, 3)
            relay.heartbeat.side_effect = RelayError("lost", 409)
            while not lease.lost and time.monotonic() < deadline:
                time.sleep(0.01)
            with self.assertRaises(RelayError):
                lease.check()


class AdapterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.settings = Settings(state=root/"state", audio=root/"audio", min_free_bytes=0, batch_size=8)
        install_test_voice(self.settings)
        source = root/"source.sqlite"
        create_demo(source)
        book_id = import_sqlite(self.settings, source, "studio-demo", "relay-test")["book_id"]
        db = connect(self.settings)
        self.chapters = [r[0] for r in db.execute("SELECT id FROM chapters WHERE book_id=?", (book_id,))]
        db.close()
        self.task = {"id":"11111111-1111-4111-8111-111111111111", "kind":"tts", "lease_token":"lease-1", "attempts":1,
                     "payload":{"request":{"book_id":book_id,"chapter_ids":self.chapters,
                                             "voice_preset_version_id":DEFAULT_PRESET_ID}}}
        self.relay = Mock()
        self.relay.config = SimpleNamespace(heartbeat_seconds=0.01, lease_seconds=300, request_timeout=1,
                                            direct_url="http://192.168.3.32:17871")
        self.worker = RemoteWorker(self.settings, self.relay)

    def test_failed_local_job_with_empty_error_list_is_reported(self):
        self.worker.local_detail = Mock(return_value={"state":"failed", "errors":[]})
        with self.assertRaisesRegex(LocalJobFailed, "failed"):
            self.worker.wait_for_local(Mock(), "local-id", "synthesis")

    def test_invalid_input_is_not_retried_or_submitted(self):
        self.relay.claim.return_value = {**self.task, "payload":{"request":{"bad":"input"}}}
        with self.assertLogs("shanye.remote_worker", level="ERROR"):
            self.worker.run_once()
        self.assertFalse(self.relay.fail.call_args.kwargs["retryable"])
        self.assertIsNone(self.worker.local_job("relay:" + self.task["id"]))

    def test_local_pipeline_publishes_without_upload_then_transfers_on_demand(self):
        engine = TestEngine()
        stop = threading.Event()
        worker_errors = []
        def run_local():
            try:
                gpu = Worker(self.settings, engine=engine)
                cpu = Worker(self.settings, role="cpu")
                while not stop.is_set():
                    gpu.tick()
                    cpu.tick()
                    stop.wait(0.01)
            except Exception as exc:
                worker_errors.append(exc)
        thread = threading.Thread(target=run_local, daemon=True)
        thread.start()
        uploaded = []
        def upload(task_id, token, path, check):
            check()
            with zipfile.ZipFile(path) as archive:
                catalogue = json.loads(archive.read("catalogue.json"))
                self.assertEqual(2, len(catalogue["entries"]))
                for entry in catalogue["entries"]:
                    manifest = json.loads(archive.read(entry["manifest"]))
                    self.assertTrue(manifest["complete"])
                    self.assertEqual(hashlib.sha256(archive.read(entry["audio"])).hexdigest(), entry["audio_sha256"])
            uploaded.append((task_id, token, path))
        self.relay.upload.side_effect = upload
        # A bounded timeout turns any pipeline deadlock into a test failure.
        timer = threading.Timer(20, lambda: setattr(self.worker, "stopping", True))
        timer.start()
        try:
            self.worker.handle(self.task)
            self.relay.upload.assert_not_called()
            published = self.relay.complete.call_args.args[2]
            self.assertTrue(published["direct"]["url"].startswith(self.relay.config.direct_url))
            self.assertGreater(published["bytes"], 100)
            calls = len(engine.calls)
            local_id = self.worker.local_job("relay:" + self.task["id"])
            self.worker.handle({**self.task,"lease_token":"lease-2","attempts":2})
            self.assertEqual(local_id, self.worker.local_job("relay:" + self.task["id"]))
            self.assertEqual(calls, len(engine.calls))
            self.relay.upload.assert_not_called()
            self.relay.claim_transfer.return_value = {"id":self.task["id"], "lease_token":"transfer-1"}
            self.worker.transfer_once()
            self.assertEqual(1, len(uploaded))
            self.assertEqual("transfer-1", uploaded[-1][1])
            db = connect(self.settings)
            db.execute("UPDATE bundles SET expires=?", (time.time()-1,))
            db.close()
            self.worker.handle({**self.task,"lease_token":"lease-3","attempts":3})
            self.assertEqual(calls, len(engine.calls))
            self.worker.transfer_once()
            self.assertNotEqual(uploaded[0][2], uploaded[-1][2])
            self.assertEqual(2, len(uploaded))
            self.assertFalse(worker_errors)
            self.assertGreater(self.relay.heartbeat.call_count, 1)
        finally:
            timer.cancel()
            stop.set()
            thread.join(timeout=5)

    def test_expired_transfer_fails_only_transfer_and_never_submits_tts(self):
        self.relay.claim_transfer.return_value = {"id":self.task["id"], "lease_token":"transfer-1"}
        with self.assertLogs("shanye.remote_worker", level="ERROR"):
            self.worker.transfer_once()
        self.relay.upload.assert_not_called()
        self.assertTrue(self.relay.fail.call_args.kwargs["transfer"])
        self.assertFalse(self.relay.fail.call_args.kwargs["retryable"])
        self.assertIsNone(self.worker.local_job("relay:" + self.task["id"]))


if __name__ == "__main__":
    unittest.main()
