from __future__ import annotations

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
import json
from pathlib import Path
import secrets
import shlex
import socket
import subprocess
import threading
import urllib.parse
from urllib.parse import urlsplit, unquote
import webbrowser

from .library import LocalLibrary

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LIBRARY = ROOT / "app/src/main/assets/shanye.db"


class Transport:
    def __init__(
        self, host, port, remote_port, remote_state, token_file=None, direct=False
    ):
        self.host, self.port, self.remote_port = host, port, remote_port
        self.remote_state, self.direct = remote_state, direct
        self.token_file = token_file
        self.process = None
        self.stop = threading.Event()
        cm = Path.home() / ".ssh/cm"
        cm.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.ssh = [
            "ssh",
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=8",
            "-o",
            "ControlMaster=auto",
            "-o",
            f"ControlPath={cm}/%r@%h:%p",
            "-o",
            "ControlPersist=8h",
            "-o",
            "ServerAliveInterval=30",
            "-o",
            "ServerAliveCountMax=3",
        ]

    def credential(self):
        if self.token_file:
            token = self.token_file.read_text().strip()
        elif not self.direct:
            path = self.remote_state.rstrip("/") + "/api-token"
            token = subprocess.check_output(
                self.ssh + [self.host, "cat " + shlex.quote(path)],
                text=True,
                timeout=15,
            ).strip()
        else:
            raise RuntimeError("--direct requires --token-file")
        if not token or len(token) > 200:
            raise RuntimeError("Invalid service token")
        self.token = token

    def listening(self):
        try:
            with socket.create_connection(("127.0.0.1", self.port), timeout=0.3):
                return True
        except OSError:
            return False

    def verify(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=2)
        try:
            connection.request(
                "GET", "/api/v1/health", headers={"X-Service-Token": self.token}
            )
            response = connection.getresponse()
            body = json.loads(response.read())
            return (
                response.status == 200
                and body.get("initialization") == "explicit-import-only"
            )
        except (OSError, ValueError, http.client.HTTPException):
            return False
        finally:
            connection.close()

    def start(self):
        self.credential()
        if self.direct:
            return
        if self.listening():
            if not self.verify():
                raise RuntimeError(
                    f"Local port {self.port} belongs to an unverified service"
                )
            self.borrowed = True
        else:
            self.borrowed = False
            self.open_tunnel()
        self.thread = threading.Thread(target=self.monitor, daemon=True)
        self.thread.start()

    def open_tunnel(self):
        self.process = subprocess.Popen(
            self.ssh
            + [
                "-N",
                "-T",
                "-o",
                "ExitOnForwardFailure=yes",
                "-L",
                f"127.0.0.1:{self.port}:127.0.0.1:{self.remote_port}",
                self.host,
            ],
            stderr=subprocess.DEVNULL,
        )

    def monitor(self):
        delay = 1
        while not self.stop.wait(delay):
            if self.process and self.process.poll() is None:
                delay = 1
                continue
            if self.listening():
                # Never take over a forward that cannot be authenticated.
                delay = 2 if self.verify() else min(delay * 2, 30)
                continue
            self.open_tunnel()
            delay = min(delay * 2, 30)

    def close(self):
        self.stop.set()
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()


class LocalServer(ThreadingHTTPServer):
    daemon_threads = True


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        pass  # Do not put credentials or media query strings into logs.

    def reply(self, status, body, content_type="application/json"):
        payload = (
            body
            if isinstance(body, bytes)
            else json.dumps(body, ensure_ascii=False).encode()
        )
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(payload)

    def handle_request(self):
        port = self.server.server_address[1]
        host = self.headers.get("Host", "")
        if host not in {f"127.0.0.1:{port}", f"localhost:{port}"}:
            return self.reply(403, {"error": {"message": "Invalid local Host"}})
        origin = self.headers.get("Origin")
        if origin and origin != "http://" + host:
            return self.reply(403, {"error": {"message": "Invalid Origin"}})
        write = self.command == "POST"
        if write and (
            origin != "http://" + host
            or not secrets.compare_digest(
                self.headers.get("X-Local-Session", ""), self.server.session
            )
            or self.headers.get("Content-Type", "").split(";")[0]
            not in {"application/json", "multipart/form-data"}
        ):
            return self.reply(403, {"error": {"message": "Invalid local session"}})
        path = urlsplit(self.path).path
        if path == "/session" and self.command == "GET":
            return self.reply(
                200,
                {"session": self.server.session, "server": self.server.transport.host},
            )
        if path == "/api/v1/books" and self.command == "GET":
            try:
                return self.reply(200, {"items": self.server.library.books()})
            except Exception as exc:
                return self.reply(503, {"error": {"message": str(exc)}})
        if (
            path.startswith("/api/v1/books/")
            and path.endswith("/chapters")
            and self.command == "GET"
        ):
            identifier = unquote(path.split("/")[4])
            query = urllib.parse.parse_qs(urlsplit(self.path).query)
            try:
                return self.reply(
                    200,
                    self.server.library.chapters(
                        identifier,
                        query.get("q", [""])[0],
                        int(query.get("offset", [0])[0]),
                        int(query.get("limit", [100])[0]),
                    ),
                )
            except Exception as exc:
                return self.reply(404, {"error": {"message": str(exc)}})
        if path.startswith("/api/v1/revisions/") and self.command == "GET":
            try:
                return self.reply(
                    200, self.server.library.revisions(unquote(path.rsplit("/", 1)[-1]))
                )
            except KeyError:
                pass  # Historical server artifacts may reference older revisions.
            except Exception as exc:
                return self.reply(404, {"error": {"message": str(exc)}})
        if path == "/api/v1/chapter-selections/resolve" and self.command == "POST":
            try:
                length = int(self.headers.get("Content-Length", "0"))
                payload = json.loads(self.rfile.read(length))
                result = self.server.library.resolve(
                    payload["book_id"],
                    payload.get("expression", ""),
                    payload.get("chapter_ids"),
                )
                for chapter in result["chapters"]:
                    chapter["artifacts"] = []
                return self.reply(200, result)
            except Exception as exc:
                return self.reply(422, {"error": {"message": str(exc)}})
        if path.startswith("/api/v1/") or path == "/openapi.json":
            return self.proxy(write)
        if write:
            return self.reply(404, {"error": {"message": "Not found"}})
        relative = unquote(path).lstrip("/") or "index.html"
        file = (self.server.dist / relative).resolve()
        if not file.is_relative_to(self.server.dist.resolve()):
            return self.reply(403, {"error": {"message": "Invalid path"}})
        if not file.is_file():
            return self.reply(404, {"error": {"message": "Build web/ first"}})
        import mimetypes

        return self.reply(
            200,
            file.read_bytes(),
            mimetypes.guess_type(file.name)[0] or "application/octet-stream",
        )

    def proxy(self, write):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 0 or length > 64 * 1024 * 1024:
                return self.reply(413, {"error": {"message": "Request too large"}})
            body = self.rfile.read(length) if write else None
            content_type = self.headers.get("Content-Type", "")
            if (
                write
                and content_type.startswith("application/json")
                and urlsplit(self.path).path
                in {"/api/v1/jobs", "/api/v1/previews", "/api/v1/jobs/estimate"}
            ):
                payload = json.loads(body or b"{}")
                if payload.get("book_id") and payload.get("chapter_ids"):
                    # Requests for a local book carry only the selected chapters.
                    # Keep ordinary remote-book requests compatible with the
                    # direct API used by tests and existing clients.
                    try:
                        payload["inline_chapters"] = self.server.library.payload(
                            payload["book_id"], payload["chapter_ids"]
                        )
                    except KeyError:
                        pass
                    except ValueError as exc:
                        return self.reply(422, {"error": {"message": str(exc)}})
                    else:
                        body = json.dumps(payload, ensure_ascii=False).encode()
            connection = http.client.HTTPConnection(
                "127.0.0.1", self.server.transport.port, timeout=30
            )
            headers = {
                k: v
                for k, v in self.headers.items()
                if k.lower()
                in {
                    "range",
                    "if-range",
                    "if-none-match",
                    "content-type",
                    "idempotency-key",
                }
            }
            headers["X-Service-Token"] = self.server.transport.token
            connection.request(self.command, self.path, body=body, headers=headers)
            response = connection.getresponse()
        except FileNotFoundError as exc:
            return self.reply(503, {"error": {"message": str(exc)}})
        except (OSError, ValueError, http.client.HTTPException):
            return self.reply(
                502,
                {
                    "error": {
                        "message": "服务器暂时无法连接，正在重连；已提交任务继续运行"
                    }
                },
            )
        try:
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() in {
                    "content-type",
                    "content-length",
                    "content-range",
                    "accept-ranges",
                    "etag",
                    "last-modified",
                    "content-disposition",
                    "cache-control",
                }:
                    self.send_header(key, value)
            self.send_header("Connection", "close")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            while chunk := response.read(65536):
                self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            connection.close()
            self.close_connection = True

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()


def make_server(
    transport, port=17861, dist=ROOT / "web/dist", library_path=DEFAULT_LIBRARY
):
    server = LocalServer(("127.0.0.1", port), Handler)
    server.transport, server.dist, server.session = (
        transport,
        dist,
        secrets.token_urlsafe(32),
    )
    server.library = LocalLibrary(library_path)
    return server


def main():
    parser = argparse.ArgumentParser(description="山页本地语音工作台")
    parser.add_argument("--host", default="jetson@192.168.3.33")
    parser.add_argument("--port", type=int, default=17861)
    parser.add_argument("--tunnel-port", type=int, default=17860)
    parser.add_argument("--remote-port", type=int, default=17860)
    parser.add_argument("--remote-state", default="/home/jetson/.local/share/shanye-tts")
    parser.add_argument("--token-file", type=Path)
    parser.add_argument(
        "--direct", action="store_true", help="Connect a local API for development"
    )
    parser.add_argument("--open", action="store_true")
    parser.add_argument("--library", type=Path, default=DEFAULT_LIBRARY)
    args = parser.parse_args()
    transport = Transport(
        args.host,
        args.tunnel_port,
        args.remote_port,
        args.remote_state,
        args.token_file,
        args.direct,
    )
    # Bind UI first; do not create a tunnel if the UI port is occupied.
    server = make_server(transport, args.port, library_path=args.library)
    try:
        transport.start()
        url = f"http://127.0.0.1:{server.server_address[1]}"
        print(f"山页语音工作台：{url}", flush=True)
        if args.open:
            webbrowser.open(url)
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        transport.close()


if __name__ == "__main__":
    main()
