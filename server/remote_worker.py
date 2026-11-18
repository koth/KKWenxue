"""Poll a generic Rust task relay and bridge supported TTS tasks into the local queue.

The relay only knows JSON tasks and result files.  This worker is the TTS-specific
adapter: it submits the frozen request to the existing local SQLite queue, waits
for the resident GPU/CPU workers, then publishes a scoped LAN address. ZIP uploads
only happen in a separate transfer loop after a client explicitly requests one.
"""

from __future__ import annotations

from contextlib import closing
import hashlib
import http.client
import json
import logging
import os
from pathlib import Path
import signal
import socket
import threading
import time
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

from pydantic import ValidationError

from .artifacts import create_bundle
from .app import JobRequest
from .catalog import DomainError
from .config import Settings
from .db import connect, initialize
from .jobs import TERMINAL, detail, submit, action
from .relay_results import ResultStore, ResultServer

log = logging.getLogger("shanye.remote_worker")


class RelayError(RuntimeError):
    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


class LocalJobFailed(RelayError):
    def __init__(self, message, retryable=False):
        super().__init__(message)
        self.retryable = retryable


class RelayConfig:
    def __init__(self):
        self.url = os.environ.get("SHANYE_RELAY_URL", "").rstrip("/")
        self.token = os.environ.get("SHANYE_RELAY_TOKEN", "")
        self.worker_id = os.environ.get("SHANYE_RELAY_WORKER_ID") or socket.gethostname()
        self.poll_seconds = max(1, int(os.environ.get("SHANYE_RELAY_POLL_SECONDS", "5")))
        self.lease_seconds = min(3600, max(30, int(os.environ.get("SHANYE_RELAY_LEASE_SECONDS", "300"))))
        self.heartbeat_seconds = min(self.lease_seconds / 3, max(1, int(os.environ.get("SHANYE_RELAY_HEARTBEAT_SECONDS", "15"))))
        self.request_timeout = max(5, int(os.environ.get("SHANYE_RELAY_REQUEST_TIMEOUT", "30")))
        self.direct_url = os.environ.get("SHANYE_RELAY_DIRECT_URL", "http://127.0.0.1:17871").rstrip("/")
        self.direct_host = os.environ.get("SHANYE_RELAY_DIRECT_HOST", "0.0.0.0")
        self.direct_port = int(os.environ.get("SHANYE_RELAY_DIRECT_PORT", "17871"))

    def require_ready(self):
        if not self.url or not self.token:
            raise RelayError("SHANYE_RELAY_URL 和 SHANYE_RELAY_TOKEN 必须设置")
        parsed = urlsplit(self.url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
            raise RelayError("SHANYE_RELAY_URL 必须是无凭据、查询或片段的 http(s) URL")
        direct = urlsplit(self.direct_url)
        if direct.scheme not in {"http", "https"} or not direct.hostname or direct.username or direct.query or direct.fragment:
            raise RelayError("SHANYE_RELAY_DIRECT_URL 必须是无凭据和参数的 http(s) URL")


class RelayClient:
    def __init__(self, config: RelayConfig):
        config.require_ready()
        self.config = config

    def _request(self, method: str, path: str, payload=None):
        body = None
        headers = {"X-Relay-Token": self.config.token, "Accept": "application/json"}
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
            headers["Content-Type"] = "application/json"
        request = Request(self.config.url + path, data=body, headers=headers, method=method)
        try:
            with urlopen(request, timeout=self.config.request_timeout) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else {}
        except HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:1000]
            raise RelayError(f"relay HTTP {exc.code}: {detail}", exc.code) from exc
        except (URLError, OSError, TimeoutError) as exc:
            raise RelayError(f"relay 请求失败: {exc}") from exc

    def claim(self):
        status, result = self._request(
            "POST",
            "/v1/tasks/claim",
            {
                "worker_id": self.config.worker_id,
                "kinds": ["tts"],
                "lease_seconds": self.config.lease_seconds,
            },
        )
        if status != 200:
            raise RelayError(f"relay claim 返回 {status}")
        return result.get("task")

    def heartbeat(self, task_id, lease_token, progress, transfer=False):
        return self._request(
            "POST",
            f"/v1/tasks/{task_id}/" + ("transfer/heartbeat" if transfer else "heartbeat"),
            {"worker_id": self.config.worker_id, "lease_token": lease_token,
             "lease_seconds": self.config.lease_seconds, "progress": progress},
        )[1]

    def fail(self, task_id, lease_token, error, retryable=True, transfer=False):
        return self._request(
            "POST",
            f"/v1/tasks/{task_id}/" + ("transfer/fail" if transfer else "fail"),
            {
                "worker_id": self.config.worker_id,
                "lease_token": lease_token,
                "error": str(error)[:4000],
                "retryable": retryable,
            },
        )[1]

    def complete(self, task_id, lease_token, result):
        return self._request("POST", f"/v1/tasks/{task_id}/complete", {
            "worker_id": self.config.worker_id, "lease_token": lease_token, **result,
        })[1]

    def claim_transfer(self):
        return self._request("POST", "/v1/transfers/claim", {
            "worker_id": self.config.worker_id, "lease_seconds": self.config.lease_seconds,
        })[1].get("task")

    def upload(self, task_id: str, lease_token: str, path: Path, check_lease=lambda: None):
        parsed = urlsplit(self.config.url + f"/v1/tasks/{task_id}/result")
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise RelayError("SHANYE_RELAY_URL 必须是 http 或 https URL")
        connection = (
            http.client.HTTPSConnection(parsed.hostname, parsed.port, timeout=self.config.request_timeout)
            if parsed.scheme == "https"
            else http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=self.config.request_timeout)
        )
        digest = hashlib.sha256()
        size = path.stat().st_size
        with path.open("rb") as source:
            while True:
                chunk = source.read(1024 * 1024)
                if not chunk:
                    break
                check_lease()
                digest.update(chunk)
        try:
            connection.putrequest("POST", (parsed.path or "/") + (f"?{parsed.query}" if parsed.query else ""))
            connection.putheader("X-Relay-Token", self.config.token)
            connection.putheader("X-Worker-Id", self.config.worker_id)
            connection.putheader("X-Lease-Token", lease_token)
            connection.putheader("X-Filename", path.name)
            connection.putheader("X-SHA256", digest.hexdigest())
            connection.putheader("Content-Type", "application/zip")
            connection.putheader("Content-Length", str(size))
            connection.endheaders()
            with path.open("rb") as source:
                while True:
                    chunk = source.read(1024 * 1024)
                    if not chunk:
                        break
                    check_lease()
                    connection.send(chunk)
            response = connection.getresponse()
            raw = response.read().decode("utf-8", "replace")
            if response.status < 200 or response.status >= 300:
                raise RelayError(f"relay 上传失败 HTTP {response.status}: {raw[:1000]}", response.status)
            return json.loads(raw) if raw else {}
        except (OSError, TimeoutError) as exc:
            raise RelayError(f"relay 上传失败: {exc}") from exc
        finally:
            connection.close()


class LeaseKeeper:
    """Keep ownership while local work, file hashing and streaming are in progress."""

    def __init__(self, relay, task, transfer=False):
        self.relay, self.task = relay, task
        self.transfer = transfer
        self.progress = {"phase": "accepted"}
        self.stopped = threading.Event()
        self.lost = None
        self.confirmed = time.monotonic()

    def __enter__(self):
        self.heartbeat()
        self.confirmed = time.monotonic()
        self.thread = threading.Thread(target=self.run, daemon=True, name="relay-heartbeat")
        self.thread.start()
        return self

    def run(self):
        while not self.stopped.wait(self.relay.config.heartbeat_seconds):
            try:
                self.heartbeat()
                self.confirmed = time.monotonic()
            except RelayError as exc:
                if exc.status in {401, 403, 404, 409} or time.monotonic() - self.confirmed >= self.relay.config.lease_seconds * 0.8:
                    self.lost = exc
                    return
                log.warning("relay heartbeat temporarily unavailable: %s", exc)
            except Exception as exc:
                self.lost = exc
                return

    def check(self):
        if self.lost:
            raise RelayError(f"relay 租约已丢失: {self.lost}", 409)
        if time.monotonic() - self.confirmed >= self.relay.config.lease_seconds * 0.8:
            raise RelayError("relay 租约续约超时", 409)

    def heartbeat(self):
        return self.relay.heartbeat(self.task["id"], self.task["lease_token"], self.progress, transfer=self.transfer)

    def __exit__(self, *_):
        self.stopped.set()
        self.thread.join(timeout=self.relay.config.request_timeout + 1)


class RemoteWorker:
    def __init__(self, settings: Settings, relay: RelayClient):
        self.settings = settings
        self.relay = relay
        self.stopping = False
        self.results = ResultStore(settings)

    def local_detail(self, identifier):
        with closing(connect(self.settings)) as db:
            return detail(db, identifier)

    def local_job(self, key):
        with closing(connect(self.settings)) as db:
            row = db.execute("SELECT id FROM jobs WHERE idempotency_key=?", (key,)).fetchone()
            return row[0] if row else None

    def submit_local(self, task):
        payload = task.get("payload") or {}
        if not isinstance(payload, dict):
            raise DomainError("tts payload 必须是 JSON 对象")
        if payload.get("job_kind", "synthesis") != "synthesis":
            raise DomainError("relay 的 tts 任务只接受完整章节合成")
        request = JobRequest.model_validate(payload.get("request", payload)).model_dump()
        key = f"relay:{task['id']}"
        local_id = self.local_job(key)
        if not local_id:
            retry_of = payload.get("retry_of")
            if retry_of:
                try:
                    import uuid
                    uuid.UUID(retry_of)
                except (ValueError, TypeError, AttributeError):
                    raise DomainError("重试任务 ID 无效") from None
                parent_id = self.local_job(f"relay:{retry_of}")
                if not parent_id:
                    raise DomainError("找不到原合成任务，请重新派发所需章节", 404)
                with closing(connect(self.settings)) as db:
                    # Follow automatic/local retries so a repeated manual retry reuses the latest work.
                    while child := db.execute("SELECT id FROM jobs WHERE parent_job_id=? ORDER BY created DESC LIMIT 1", (parent_id,)).fetchone():
                        parent_id = child[0]
                    parent = detail(db, parent_id)
                    if request != parent["request"]:
                        raise DomainError("重试必须沿用原任务的文本、音色和参数", 409)
                    if parent["state"] in {"failed", "partial_failed"}:
                        local_id = action(db, self.settings, parent_id, "retry-failed", key)
                    elif parent["state"] != "cancelled":
                        # Already recovering/completed: wait for/reuse it, never duplicate synthesis.
                        local_id = submit(db, self.settings, request, key, "synthesis",
                                          db.execute("SELECT * FROM jobs WHERE id=?", (parent_id,)).fetchone(), parent["config"])
                    else:
                        raise DomainError("原任务已取消，请重新派发", 409)
            else:
                with closing(connect(self.settings)) as db:
                    local_id = submit(db, self.settings, request, key, "synthesis")
        # Relay redelivery must resume a fresh failed-only local retry rather than replaying
        # the same terminal job. Keys are stable across worker restarts and lease loss.
        for attempt in range(2, min(20, int(task.get("attempts", 1))) + 1):
            retry_key = f"relay-retry:{task['id']}:{attempt}"
            existing = self.local_job(retry_key)
            if existing:
                local_id = existing
                continue
            current = self.local_detail(local_id)
            if not self.retryable_synthesis(current):
                break
            with closing(connect(self.settings)) as db:
                local_id = action(db, self.settings, local_id, "retry-failed", retry_key)
        return local_id

    @staticmethod
    def retryable_synthesis(job):
        if job["state"] not in {"failed", "partial_failed"} or job.get("counts", {}).get("failed", 0) < 1:
            return False
        # Content has already exhausted the bounded segment/split budget. Replaying
        # whole relay rounds here starves newer chapters; leave explicit retry available.
        messages=[job.get("error") or ""]+[item.get("error") or "" for item in job.get("errors",[])]
        return not any("内容校验" in message or "Split child exhausted" in message for message in messages)

    def ensure_bundle(self, remote_id, job, attempt=1):
        key = f"relay-bundle:{remote_id}"
        existing = self.local_job(key)
        if existing:
            state = self.local_detail(existing)["state"]
            if state not in TERMINAL or self.bundle_path(existing):
                return existing
            # A relay retry can outlive the local temporary bundle's TTL.
            key += f":{attempt}"
        artifact_ids = [chapter["artifact_id"] for chapter in job["chapters"] if chapter.get("artifact_id")]
        if not artifact_ids:
            raise RelayError("TTS 任务没有可打包的完整章节产物")
        with closing(connect(self.settings)) as db:
            return create_bundle(db, self.settings, artifact_ids, key)

    def bundle_path(self, bundle_id):
        with closing(connect(self.settings)) as db:
            row = db.execute("SELECT path,sha256,expires FROM bundles WHERE job_id=?", (bundle_id,)).fetchone()
            if not row or not row["path"]:
                return None
            path = self.settings.audio / row["path"]
            if not path.is_file() or (row["expires"] or 0) < time.time():
                return None
            return path

    def wait_for_local(self, lease, local_id, phase):
        while not self.stopping:
            lease.check()
            current = self.local_detail(local_id)
            lease.progress = {"phase": phase, "local_job_id": local_id, "state": current["state"],
                              "counts": current.get("counts", {}), "retrying": bool(current.get("parent_job_id"))}
            state = current["state"]
            if state in TERMINAL:
                if state != "succeeded":
                    errors = current.get("errors") or []
                    message = current.get("error") or (errors[0].get("error") if errors else state)
                    raise LocalJobFailed(f"本地 {phase} 失败: {message}",
                                         retryable=phase == "synthesis" and self.retryable_synthesis(current))
                return current
            time.sleep(min(self.relay.config.heartbeat_seconds, 3))
        raise RelayError("worker 正在停止")

    def handle(self, task):
        remote_id = task.get("id")
        if not remote_id:
            raise RelayError("relay task 缺少 id")
        with LeaseKeeper(self.relay, task) as lease:
            local_id = self.submit_local(task)
            synthesis = self.wait_for_local(lease, local_id, "synthesis")
            bundle_id = self.ensure_bundle(remote_id, synthesis, task.get("attempts", 1))
            self.wait_for_local(lease, bundle_id, "bundle")
            path = self.bundle_path(bundle_id)
            if not path:
                raise RelayError("本地 ZIP 已过期或文件不存在")
            with closing(connect(self.settings)) as db:
                expires = db.execute("SELECT expires FROM bundles WHERE job_id=?", (bundle_id,)).fetchone()[0]
            result = self.results.publish(remote_id, path, expires, self.relay.config.direct_url)
            lease.progress = {"phase": "publish", "local_job_id": bundle_id, "bytes": path.stat().st_size}
            lease.check()
            self.relay.complete(remote_id, task["lease_token"], result)
            log.info("relay task %s completed; LAN result published, no ZIP uploaded", remote_id)

    def transfer_once(self):
        task = self.relay.claim_transfer()
        if not task:
            return False
        try:
            with LeaseKeeper(self.relay, task, transfer=True) as lease:
                record = self.results.get(task["id"])
                path = self.results.path(record)
                if not path:
                    raise LocalJobFailed("本地结果已过期或文件不可用，请重新合成")
                lease.progress = {"phase": "upload", "bytes": record["bytes"]}
                self.relay.upload(task["id"], task["lease_token"], path, lease.check)
                log.info("relay task %s uploaded on demand", task["id"])
        except Exception as exc:
            log.exception("relay transfer %s failed", task.get("id"))
            try:
                self.relay.fail(task["id"], task["lease_token"], exc,
                                retryable=not isinstance(exc, LocalJobFailed), transfer=True)
            except Exception:
                log.exception("could not report transfer failure")
        return True

    def transfer_loop(self):
        while not self.stopping:
            try:
                if self.transfer_once():
                    continue
            except RelayError:
                log.exception("relay transfer poll failed")
            # Interruptible short waits let the HTTP listener and worker shut down promptly.
            for _ in range(self.relay.config.poll_seconds * 10):
                if self.stopping:
                    return
                time.sleep(0.1)

    def run_once(self):
        task = self.relay.claim()
        if not task:
            return False
        try:
            self.handle(task)
        except Exception as exc:
            log.exception("relay task %s failed", task.get("id"))
            try:
                self.relay.fail(task.get("id", ""), task.get("lease_token", ""), exc,
                                retryable=exc.retryable if isinstance(exc, LocalJobFailed) else
                                not isinstance(exc, (DomainError, ValidationError)))
            except Exception:
                log.exception("could not report relay task failure")
        return True

    def run(self):
        initialize(self.settings)
        while not self.stopping:
            try:
                if not self.run_once():
                    time.sleep(self.relay.config.poll_seconds)
            except RelayError:
                log.exception("relay poll failed")
                time.sleep(min(self.relay.config.poll_seconds * 4, 60))


def main():
    logging.basicConfig(level=os.environ.get("SHANYE_LOG_LEVEL", "INFO"))
    settings = Settings()
    relay = RelayClient(RelayConfig())
    worker = RemoteWorker(settings, relay)
    def stop(*_):
        worker.stopping = True
    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    server = ResultServer(worker.results, relay.config.direct_host, relay.config.direct_port)
    server.start()
    transfer = threading.Thread(target=worker.transfer_loop, name="relay-result-transfer", daemon=True)
    transfer.start()
    try:
        worker.run()
    finally:
        worker.stopping = True
        server.close()
        transfer.join(timeout=relay.config.request_timeout + 2)


if __name__ == "__main__":
    main()
