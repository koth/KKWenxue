#!/usr/bin/env python3
"""Resumable Qwen3-TTS CUDA synthesis for the prepared novel database.

The database and export format are shared with synthesize.py so Android UTF-16
anchors stay identical between the local preview and the server run.
"""

from __future__ import annotations

import argparse
import importlib.metadata
import json
import os
import signal
import shutil
import sqlite3
import sys
import time
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from synthesize import (  # noqa: E402
    DEFAULT_OUTPUT,
    DEFAULT_SOURCE,
    EXPORT_SPEED,
    INSTRUCTION,
    connect,
    digest,
    export_chapters,
    pending,
    prepare,
)

MODEL = "Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice"
REVISION = "0c0e3051f131929182e2c023b9537f8b1c68adfe"
SAMPLE_RATE = 24000
STOP_REQUESTED = False


def on_signal(signum, _frame):
    global STOP_REQUESTED
    STOP_REQUESTED = True
    print(json.dumps({"event": "stop_requested", "signal": signum}), flush=True)


def profile_cuda(
    db: sqlite3.Connection,
    batch_size: int,
    attention: str,
    speed: float = EXPORT_SPEED,
    instruct: str = INSTRUCTION,
    speaker: str = "Uncle_Fu",
) -> tuple[str, dict]:
    config = dict(
        engine="qwen3-tts-cuda",
        model=MODEL,
        revision=REVISION,
        speaker=speaker,
        language="Chinese",
        instruct=instruct,
        dtype="bfloat16",
        attention=attention,
        temperature=0.7,
        top_k=50,
        top_p=1.0,
        repetition_penalty=1.05,
        max_new_tokens=1536,
        seed=42,
        batch_size=batch_size,
        pause_ms=180,
        normalization="OpenCC t2s + whitespace v1",
        export_speed=speed,
        torch=importlib.metadata.version("torch"),
        qwen_tts=importlib.metadata.version("qwen-tts"),
    )
    encoded = json.dumps(config, ensure_ascii=False, sort_keys=True)
    profile_id = digest(encoded)[:16]
    with db:
        db.execute("INSERT OR IGNORE INTO profiles VALUES (?,?)", (profile_id, encoded))
    return profile_id, config


def load_model(model_path: Path, attention: str):
    import torch
    from qwen_tts import Qwen3TTSModel

    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is not available")
    torch.backends.cuda.matmul.allow_tf32 = True
    torch.backends.cudnn.allow_tf32 = True
    torch.set_num_threads(min(8, os.cpu_count() or 1))
    print(
        json.dumps(
            {
                "event": "cuda",
                "device": torch.cuda.get_device_name(0),
                "cuda": torch.version.cuda,
                "torch": torch.__version__,
                "vram_gb": round(
                    torch.cuda.get_device_properties(0).total_memory / 2**30, 2
                ),
                "attention": attention,
            }
        ),
        flush=True,
    )
    return Qwen3TTSModel.from_pretrained(
        str(model_path),
        device_map="cuda:0",
        dtype=torch.bfloat16,
        attn_implementation=attention,
        local_files_only=True,
    )


def generate_batch(model, rows, config, torch):
    """Generate a batch, splitting on OOM or a batch-level model failure."""
    if not rows:
        return []
    seed = (
        config["seed"] + rows[0]["chapter_index"] * 100_000 + rows[0]["segment_index"]
    )
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)
    try:
        with torch.inference_mode():
            wavs, sample_rate = model.generate_custom_voice(
                text=[row["spoken_text"] for row in rows],
                language=[config["language"]] * len(rows),
                speaker=[config["speaker"]] * len(rows),
                instruct=[config["instruct"]] * len(rows),
                non_streaming_mode=True,
                temperature=config["temperature"],
                top_k=config["top_k"],
                top_p=config["top_p"],
                repetition_penalty=config["repetition_penalty"],
                max_new_tokens=config["max_new_tokens"],
            )
            torch.cuda.synchronize()
            if sample_rate != SAMPLE_RATE:
                raise ValueError(f"Unexpected model sample rate {sample_rate}")
            if len(wavs) != len(rows):
                raise ValueError(f"Expected {len(rows)} outputs, got {len(wavs)}")
            return wavs
    except Exception as exc:
        is_oom = (
            isinstance(exc, torch.cuda.OutOfMemoryError)
            or "out of memory" in str(exc).lower()
        )
        torch.cuda.empty_cache()
        if len(rows) > 1:
            middle = len(rows) // 2
            print(
                json.dumps(
                    {
                        "event": "split_batch",
                        "count": len(rows),
                        "reason": repr(exc),
                        "oom": is_oom,
                    }
                ),
                flush=True,
            )
            return generate_batch(model, rows[:middle], config, torch) + generate_batch(
                model, rows[middle:], config, torch
            )
        return [exc]


def write_progress(output: Path, state: dict):
    target = output / "progress.json"
    temporary = target.with_suffix(".partial")
    temporary.write_text(
        json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    temporary.replace(target)


def chapter_ready(db, profile_id: str, chapter_id: str, output: Path) -> bool:
    rows = db.execute(
        """SELECT a.path,a.status FROM segments s
        LEFT JOIN audio a ON a.segment_id=s.id AND a.profile_id=?
        WHERE s.chapter_id=? ORDER BY s.segment_index""",
        (profile_id, chapter_id),
    ).fetchall()
    return bool(rows) and all(
        row["status"] == "ready" and row["path"] and (output / row["path"]).is_file()
        for row in rows
    )


def mark_failed(db, profile_id: str, rows, error: str):
    with db:
        for row in rows:
            db.execute(
                """INSERT INTO audio
                (profile_id,segment_id,status,error) VALUES (?,?,?,?)
                ON CONFLICT(profile_id,segment_id) DO UPDATE SET status=excluded.status,error=excluded.error
                WHERE audio.status != 'ready'""",
                (profile_id, row["id"], "failed", error),
            )


from common import validated_samples, GPULock


def run(
    db,
    output: Path,
    model_path: Path,
    first: int,
    last: int,
    batch_size: int,
    attention: str,
    speed: float,
    limit: int = 0,
    instruct: str = INSTRUCTION,
    speaker: str = "Uncle_Fu",
):
    import soundfile as sf
    import torch

    profile_id, config = profile_cuda(
        db, batch_size, attention, speed, instruct, speaker
    )
    rows = pending(db, output, profile_id, first, last)
    if limit:
        rows = rows[:limit]
    total = len(rows)
    if not total:
        print(
            json.dumps({"event": "nothing_pending", "profile": profile_id}), flush=True
        )
        return profile_id
    model = load_model(model_path, attention)
    started = time.monotonic()
    audio_seconds = 0.0
    completed = failed = consecutive_failures = 0
    state = dict(
        profile_id=profile_id,
        first=first,
        last=last,
        total=total,
        completed=0,
        failed=0,
        audio_seconds=0.0,
        started_unix=time.time(),
        model=MODEL,
        revision=REVISION,
        batch_size=batch_size,
        speed=speed,
    )
    write_progress(output, state)
    print(json.dumps({"event": "start", **state}, ensure_ascii=False), flush=True)
    for offset in range(0, total, batch_size):
        if STOP_REQUESTED:
            break
        batch = rows[offset : offset + batch_size]
        if shutil.disk_usage(output).free < 5 * 2**30:
            state.update(status="disk_space_low")
            write_progress(output, state)
            raise RuntimeError("Less than 5 GiB free; checkpoints preserved")
        batch_started = time.monotonic()
        torch.cuda.reset_peak_memory_stats()
        try:
            results = generate_batch(model, batch, config, torch)
            if len(results) != len(batch):
                raise RuntimeError(
                    f"Model returned {len(results)} results for {len(batch)} inputs"
                )
            # Commit each valid result independently; one invalid waveform cannot discard its peers.
            for row, result in zip(batch, results):
                try:
                    if isinstance(result, Exception):
                        raise result
                    samples = validated_samples(result, row["id"])
                except Exception as validation_error:
                    failed += 1
                    mark_failed(db, profile_id, [row], repr(validation_error))
                    print(
                        json.dumps(
                            {
                                "event": "segment_failed",
                                "segment_id": row["id"],
                                "error": repr(validation_error),
                            }
                        ),
                        flush=True,
                    )
                    continue
                sample_rate = SAMPLE_RATE
                duration = len(samples) / sample_rate
                relative = (
                    Path("segments")
                    / profile_id
                    / row["chapter_id"]
                    / f"{row['segment_index']:05d}.flac"
                )
                target = output / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                temporary = target.with_suffix(".partial")
                sf.write(
                    temporary, samples, sample_rate, format="FLAC", subtype="PCM_16"
                )
                temporary.replace(target)
                with db:
                    db.execute(
                        """INSERT OR REPLACE INTO audio
                        (profile_id,segment_id,path,sample_rate,samples,duration_ms,sha256,elapsed_seconds,status,error)
                        VALUES (?,?,?,?,?,?,?,?,?,NULL)""",
                        (
                            profile_id,
                            row["id"],
                            str(relative),
                            sample_rate,
                            len(samples),
                            round(duration * 1000),
                            digest(target.read_bytes()),
                            0.0,
                            "ready",
                        ),
                    )
                completed += 1
                audio_seconds += duration
            elapsed = time.monotonic() - batch_started
            with db:
                db.executemany(
                    "UPDATE audio SET elapsed_seconds=? WHERE profile_id=? AND segment_id=?",
                    [(elapsed / len(batch), profile_id, row["id"]) for row in batch],
                )
            # Export only a chapter that is actually complete, avoiding repeated large encodes.
            for chapter_id in {row["chapter_id"] for row in batch}:
                if chapter_ready(db, profile_id, chapter_id, output):
                    number = db.execute(
                        "SELECT chapter_number FROM chapters WHERE id=?", (chapter_id,)
                    ).fetchone()[0]
                    export_chapters(db, output, profile_id, number, number, speed=speed)
            if torch.cuda.is_available():
                peak = torch.cuda.max_memory_allocated() / 2**30
                torch.cuda.reset_peak_memory_stats()
            else:
                peak = 0
            state.update(
                completed=completed,
                failed=failed,
                audio_seconds=round(audio_seconds, 3),
                elapsed_seconds=round(time.monotonic() - started, 2),
                realtime_factor=round(
                    audio_seconds / max(time.monotonic() - started, 1e-6), 3
                ),
                peak_vram_gb=round(peak, 3),
            )
            write_progress(output, state)
            consecutive_failures = 0
            print(
                json.dumps(
                    {
                        "event": "batch",
                        "offset": offset,
                        "count": len(batch),
                        "batch_seconds": round(elapsed, 2),
                        "audio_seconds": round(
                            sum(len(np.asarray(x).reshape(-1)) for x in results)
                            / SAMPLE_RATE,
                            2,
                        ),
                        "completed": completed,
                        "failed": failed,
                        "peak_vram_gb": round(peak, 3),
                    },
                    ensure_ascii=False,
                ),
                flush=True,
            )
        except Exception as exc:
            failed += len(batch)
            consecutive_failures += 1
            mark_failed(db, profile_id, batch, repr(exc))
            state.update(
                completed=completed,
                failed=failed,
                elapsed_seconds=round(time.monotonic() - started, 2),
            )
            write_progress(output, state)
            print(
                json.dumps(
                    {
                        "event": "batch_failed",
                        "offset": offset,
                        "count": len(batch),
                        "error": repr(exc),
                    },
                    ensure_ascii=False,
                ),
                flush=True,
            )
            # Continue the durable run; failed rows remain visible and can be retried later.
            if consecutive_failures >= 3:
                state.update(status="failed", error="Three consecutive batches failed")
                write_progress(output, state)
                raise RuntimeError(
                    "Three consecutive batches failed; fix cause before resuming"
                ) from exc
        if STOP_REQUESTED:
            break
    # Completed chapters were exported above. Export only any unfinished tail.
    for row in db.execute(
        "SELECT id,chapter_number FROM chapters WHERE chapter_number BETWEEN ? AND ?",
        (first, last),
    ).fetchall():
        if not chapter_ready(db, profile_id, row["id"], output):
            export_chapters(
                db,
                output,
                profile_id,
                row["chapter_number"],
                row["chapter_number"],
                partial=True,
                speed=speed,
            )
    state.update(
        completed=completed,
        failed=failed,
        stopped=STOP_REQUESTED,
        status="stopped"
        if STOP_REQUESTED
        else ("complete" if not failed else "needs_retry"),
        elapsed_seconds=round(time.monotonic() - started, 2),
        realtime_factor=round(audio_seconds / max(time.monotonic() - started, 1e-6), 3),
    )
    write_progress(output, state)
    print(json.dumps({"event": "finished", **state}, ensure_ascii=False), flush=True)
    return profile_id


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare", "run", "status"])
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--model-path", type=Path, required=False)
    parser.add_argument("--first", type=int, default=850)
    parser.add_argument("--last", type=int, default=1281)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument(
        "--attention", default="sdpa", choices=["sdpa", "flash_attention_2"]
    )
    parser.add_argument("--speed", type=float, default=EXPORT_SPEED)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument(
        "--instruct-file",
        type=Path,
        help="UTF-8 narration style; changing it creates a separate profile",
    )
    parser.add_argument(
        "--speaker",
        default="Uncle_Fu",
        help="Qwen CustomVoice preset; stored in the profile",
    )
    args = parser.parse_args()
    instruct = (
        args.instruct_file.read_text(encoding="utf-8").strip()
        if args.instruct_file
        else INSTRUCTION
    )
    if not instruct:
        parser.error("--instruct-file must contain a narration instruction")
    args.output.mkdir(parents=True, exist_ok=True)
    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)
    with connect(args.output) as db:
        if args.command == "prepare":
            prepare(db, args.source)
            return
        if args.command == "status":
            row = db.execute(
                "SELECT count(*) AS chapters FROM chapters WHERE chapter_number BETWEEN ? AND ?",
                (args.first, args.last),
            ).fetchone()
            print(dict(row))
            return
        if not args.model_path:
            parser.error("--model-path is required for run")
        import fcntl

        with GPULock(), (args.output / ".synthesis.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            run(
                db,
                args.output,
                args.model_path,
                args.first,
                args.last,
                args.batch_size,
                args.attention,
                args.speed,
                args.limit,
                instruct,
                args.speaker,
            )


if __name__ == "__main__":
    main()
