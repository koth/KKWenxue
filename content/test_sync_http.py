"""Opt-in integration fixture: real catalog, relay and worker, local TXT source."""
from contextlib import ExitStack, closing
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import parse_qs, urlsplit
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import unittest
import zipfile


def free_address():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return f"127.0.0.1:{listener.getsockname()[1]}"


def http(url, payload=None):
    request = Request(url, None if payload is None else json.dumps(payload).encode(),
                      headers={"Content-Type": "application/json", "X-Relay-Token": "fixture-secret"})
    with urlopen(request, timeout=5) as response:
        return response.status, response.read()


def wait_for(fn, timeout=25):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        result = fn()
        if result is not None:
            return result
        time.sleep(0.2)
    raise AssertionError("fixture timed out")


@unittest.skipUnless(os.environ.get("SHANYE_RUN_SYNC_HTTP_FIXTURE") == "1", "requires local server binaries")
class OnlineSyncHttpTest(unittest.TestCase):
    def test_old_import_and_incremental_package_go_through_relay(self):
        repo = Path(__file__).resolve().parents[1]
        extension = ".exe" if os.name == "nt" else ""
        catalog = repo / "catalog/target/debug" / f"shanye-catalog{extension}"
        relay = repo / "relay/target/debug" / f"shanye-task-relay{extension}"
        chapters = ["第1章 初到\n旧正文。\n旧正文的第二段。"]

        class Source(BaseHTTPRequestHandler):
            def do_GET(self):
                url = urlsplit(self.path)
                query = parse_qs(url.query)
                if url.path != "/txt/" or query.get("id") != ["7"]:
                    self.send_error(404)
                    return
                page = "\n\n".join(chapters) if query.get("p") == ["1"] else ""
                body = f"《测试书》\n作者：作者\n\n{page}\n\n更多精彩小说，请访问测试\n".encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        with tempfile.TemporaryDirectory() as temp, ExitStack() as stack:
            root = Path(temp)
            origin = stack.enter_context(ThreadingHTTPServer(("127.0.0.1", 0), Source))
            threading.Thread(target=origin.serve_forever, daemon=True).start()
            stack.callback(origin.shutdown)
            catalog_addr, relay_addr, worker_addr = free_address(), free_address(), free_address()
            _, worker_port = worker_addr.split(":")
            base = f"http://{catalog_addr}"
            db = root / "catalog.sqlite"
            env = {
                **os.environ, "SHANYE_CATALOG_BIND": catalog_addr, "SHANYE_CATALOG_DB": str(db),
                "SHANYE_RELAY_BIND": relay_addr, "SHANYE_RELAY_TOKEN": "fixture-secret",
                "SHANYE_RELAY_URL": f"http://{relay_addr}", "SHANYE_RELAY_DATABASE": str(root / "relay.sqlite"),
                "SHANYE_RELAY_TEMP_DIR": str(root / "relay-files"), "SHANYE_CONTENT_ROOT": str(root / "worker"),
                "SHANYE_CONTENT_SOURCE": f"http://127.0.0.1:{origin.server_port}",
                "SHANYE_CONTENT_DIRECT_URL": f"http://{worker_addr}", "SHANYE_CONTENT_DIRECT_BIND": "127.0.0.1",
                "SHANYE_CONTENT_DIRECT_PORT": worker_port,
                "SHANYE_RELAY_CLAIM_WAIT_SECONDS": "1",
            }

            def start(argv):
                process = subprocess.Popen(argv, cwd=repo, env=env, stdout=subprocess.DEVNULL)
                def stop():
                    if process.poll() is None:
                        process.terminate()
                    process.wait(timeout=5)
                stack.callback(stop)
                return process

            relay_process = start([relay])
            catalog_process = start([catalog, "serve"])
            start([sys.executable, "-B", "-m", "content.worker"])

            def health(url, process):
                self.assertIsNone(process.poll())
                try:
                    return http(url + "/healthz")
                except HTTPError:
                    raise
                except OSError:
                    return None

            wait_for(lambda: health(f"http://{relay_addr}", relay_process))
            wait_for(lambda: health(base, catalog_process))

            def catalog_post(path, payload):
                return json.loads(http(base + path, payload)[1])

            def fetch_status(task):
                def status():
                    current = json.loads(http(base + f"/v1/catalog/content/{task['taskId']}")[1])
                    if current["state"] in ("failed", "expired"):
                        self.fail(str(current))
                    return current if current.get("downloadUrl") else None
                return wait_for(status)

            def zip_bytes(task):
                seen = []
                def download():
                    status, data = http(base + task["downloadUrl"])
                    seen.append(status)
                    return data if status == 200 else None
                data = wait_for(download)
                self.assertEqual(seen[0], 202)
                self.assertEqual(task["sha256"], hashlib.sha256(data).hexdigest())
                return data

            with closing(sqlite3.connect(db)) as conn:
                conn.execute("INSERT INTO books(id,title,author,chapter_no,latest_chapter_id,latest_chapter) "
                             "VALUES (7,'测试书','作者',1,101,'第1章 初到')")
                conn.commit()
                book_path = "/v1/catalog/books/7"
                old_task = fetch_status(catalog_post(book_path + "/content", {}))
                with zipfile.ZipFile(io.BytesIO(zip_bytes(old_task))) as package:
                    old = json.loads(package.read("manifest.json"))
                    self.assertEqual(old["sha256"], hashlib.sha256(package.read("book.txt")).hexdigest())
                    self.assertEqual(old["chapters"], 1)

                chapters.append("第2章 去处\n这是新增的一章。")
                conn.execute("UPDATE books SET chapter_no=2,latest_chapter_id=102,latest_chapter='第2章 去处' WHERE id=7")
                conn.commit()
                version = json.loads(http(base + book_path + "/version")[1])
                self.assertNotEqual(old["catalogRevision"], version["remoteRevision"])
                body_hash = hashlib.sha256("旧正文。\n\n旧正文的第二段。".encode()).hexdigest()
                title = "第1章 初到"
                chapter_hash = hashlib.sha256(f"{title}\n{body_hash}".encode()).hexdigest()
                payload = {"fromRevision": old["version"], "targetRevision": version["remoteRevision"], "chapters": [
                    {"chapterId": f"online:catalog:7:chapter:sha256:{chapter_hash}:1",
                     "index": 0, "title": title, "bodySha256": body_hash}
                ]}
                accepted = catalog_post(book_path + "/update-plan", payload)
                self.assertEqual(accepted["taskId"], catalog_post(book_path + "/update-plan", payload)["taskId"])
                update = fetch_status(accepted)
                self.assertTrue(update["plan"]["appendOnly"])
                self.assertEqual(update["plan"]["addedTitles"], ["第2章 去处"])
                self.assertNotIn("direct", update)
                with zipfile.ZipFile(io.BytesIO(zip_bytes(update))) as package:
                    self.assertEqual(set(package.namelist()), {"manifest.json", "chapters/00001.txt"})
                    manifest = json.loads(package.read("manifest.json"))
                    self.assertEqual(manifest["chapters"][0]["chapterId"], payload["chapters"][0]["chapterId"])
                    self.assertEqual(manifest["fromRevision"], old["version"])
                    self.assertEqual(manifest["toRevision"], update["plan"]["toRevision"])
