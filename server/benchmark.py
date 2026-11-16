"""Explicit, isolated GPU acceptance run using original studio-demo text only."""

from contextlib import closing
import json
import os
import threading
import time

from tts.common import GPULock
from .catalog import import_sqlite
from .cli import create_demo
from .config import Settings
from .db import initialize, connect
from .jobs import submit, detail
from .worker import Worker
from .voices import DEFAULT_PRESET_ID


def main():
    settings = Settings()
    if not os.environ.get("SHANYE_BENCHMARK_CONFIRM") == "studio-demo-only":
        raise SystemExit(
            "Set SHANYE_BENCHMARK_CONFIRM=studio-demo-only and isolated SHANYE_STATE / SHANYE_AUDIO"
        )
    initialize(settings)
    source = settings.state / "studio-demo.sqlite"
    if not source.exists():
        create_demo(source)
    book = import_sqlite(settings, source, "studio-demo", "explicit-gpu-acceptance")[
        "book_id"
    ]
    gpu = Worker(settings)
    cpu = Worker(settings, "cpu")
    heartbeat = threading.Thread(target=gpu.heartbeat, daemon=True)
    heartbeat.start()
    cpu_heartbeat = threading.Thread(target=cpu.heartbeat, daemon=True)
    cpu_heartbeat.start()
    try:
        with GPULock(), closing(connect(settings)) as db:
            ids = [
                r[0]
                for r in db.execute(
                    "SELECT id FROM chapters WHERE book_id=? ORDER BY chapter_index",
                    (book,),
                )
            ]
            request = dict(
                book_id=book,
                chapter_ids=[ids[0]],
                voice_preset_version_id=os.environ.get("SHANYE_BENCHMARK_PRESET", DEFAULT_PRESET_ID),
            )
            normal = submit(
                db, settings, {**request, "chapter_ids": ids}, "benchmark-bulk"
            )
            started = time.monotonic()
            gpu.tick()
            cold_seconds = time.monotonic() - started
            preview = submit(db, settings, request, "benchmark-preview", "preview")
            timeline = []
            while True:
                a = gpu.tick()
                b = cpu.tick()
                timeline.append(
                    {
                        "at": round(time.monotonic() - started, 3),
                        "bulk": detail(db, normal)["state"],
                        "preview": detail(db, preview)["state"],
                    }
                )
                if not a and not b:
                    break
            warm_job = submit(
                db,
                settings,
                {**request, "chapter_ids": [ids[1]], "mode": "new-version"},
                "benchmark-warm",
            )
            warm_start = time.monotonic()
            while gpu.tick():
                cpu.tick()
            while cpu.tick():
                pass
            warm_seconds = time.monotonic() - warm_start
            fast = submit(
                db,
                settings,
                {**request, "export": {"speed": 1.15, "pause_ms": 180}},
                "benchmark-cpu-speed",
            )
            before = db.execute("SELECT count(*) FROM attempts").fetchone()[0]
            cpu_start = time.monotonic()
            while cpu.tick():
                pass
            cpu_seconds = time.monotonic() - cpu_start
            after = db.execute("SELECT count(*) FROM attempts").fetchone()[0]
            assert before == after, "Speed-only export triggered generation"
            jobs = {
                key: detail(db, value)
                for key, value in [
                    ("bulk", normal),
                    ("preview", preview),
                    ("warm", warm_job),
                    ("speed_only", fast),
                ]
            }
            assert all(j["state"] == "succeeded" for j in jobs.values()), jobs
            timing = dict(
                db.execute(
                    "SELECT count(*) AS clips,sum(samples)/24000.0 AS audio_seconds,sum(elapsed) AS synthesis_seconds FROM segment_audio WHERE ready=1 AND elapsed>0"
                ).fetchone()
            )
            report = {
                "source": "original studio-demo, explicit isolated acceptance",
                "model_load_and_first_batch_seconds": round(cold_seconds, 3),
                "warm_job_seconds": round(warm_seconds, 3),
                "cpu_reexport_seconds": round(cpu_seconds, 3),
                "speed_only_gpu_calls": after - before,
                "timeline": timeline,
                "measurements": timing,
                "jobs": {
                    k: {
                        "id": v["id"],
                        "state": v["state"],
                        "counts": v["counts"],
                        "artifacts": [c["artifact_id"] for c in v["chapters"]],
                    }
                    for k, v in jobs.items()
                },
            }
            target = settings.state / "benchmark-report.json"
            target.write_text(json.dumps(report, ensure_ascii=False, indent=2))
            print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    finally:
        gpu.stopping.set()
        cpu.stopping.set()
        gpu.heartbeat_stop.set()
        cpu.heartbeat_stop.set()
        heartbeat.join(timeout=3)
        cpu_heartbeat.join(timeout=3)


if __name__ == "__main__":
    main()
