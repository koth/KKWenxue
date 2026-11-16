"""Subprocess-only fault harness; production CLI never enables a fake engine."""

import os
import signal
import time

from server.config import Settings
from server.tests.test_service import TestEngine
from server.worker import Worker


def main():
    settings = Settings(min_free_bytes=0, lease_seconds=4)
    engine = TestEngine()
    engine.callback = lambda: time.sleep(
        float(os.environ.get("SHANYE_TEST_DELAY", "0"))
    )
    worker = Worker(settings, os.environ.get("SHANYE_TEST_ROLE", "gpu"), engine=engine)
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: worker.stopping.set())
    worker.run()


if __name__ == "__main__":
    main()
