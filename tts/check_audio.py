#!/usr/bin/env python3
"""Optional local ASR spot-check. CER flags are review hints, not listening scores."""

import argparse
import json
import re
import time
from pathlib import Path

from synthesize import DEFAULT_OUTPUT, connect

ASR_MODEL = "mlx-community/Qwen3-ASR-0.6B-4bit"
ASR_REVISION = "313d850181767edf09f00a9c289becca70e58cd0"


def distance(a, b):
    previous = list(range(len(b) + 1))
    for i, left in enumerate(a, 1):
        current = [i]
        for j, right in enumerate(b, 1):
            current.append(
                min(current[-1] + 1, previous[j] + 1, previous[j - 1] + (left != right))
            )
        previous = current
    return previous[-1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--first", type=int, default=1)
    parser.add_argument("--last", type=int, default=1)
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args()
    from huggingface_hub import snapshot_download
    from mlx_audio.stt import load
    from opencc import OpenCC

    normalizer = OpenCC("t2s")
    normalize = lambda text: re.sub(
        r"[^\w\u4e00-\u9fff]", "", normalizer.convert(text)
    ).lower()
    with connect(args.output) as db:
        rows = db.execute(
            """SELECT s.*,c.chapter_index,c.chapter_number,a.path,a.sha256,a.duration_ms FROM audio a
            JOIN segments s ON s.id=a.segment_id JOIN chapters c ON c.id=s.chapter_id
            WHERE a.profile_id=? AND a.status='ready' AND c.chapter_number BETWEEN ? AND ?
            ORDER BY c.chapter_index,s.segment_index""",
            (args.profile, args.first, args.last),
        ).fetchall()
    if args.limit:
        rows = rows[: args.limit]
    folder = args.output / "quality" / args.profile
    folder.mkdir(parents=True, exist_ok=True)
    output = folder / f"{args.first:04d}-{args.last:04d}.json"
    report = dict(
        asr_model=ASR_MODEL,
        revision=ASR_REVISION,
        note="ASR character errors include name/homophone mistakes; manually review flags.",
        segments=[],
    )
    model = load(snapshot_download(ASR_MODEL, revision=ASR_REVISION))
    for row in rows:
        started = time.monotonic()
        transcription = model.generate(
            str(args.output / row["path"]), language="Chinese", max_tokens=512
        ).text
        expected, actual = normalize(row["spoken_text"]), normalize(transcription)
        cer = distance(expected, actual) / max(1, len(expected))
        item = dict(
            segment_id=row["id"],
            chapter_number=row["chapter_number"],
            segment_number=row["segment_index"] + 1,
            audio_sha256=row["sha256"],
            expected=row["spoken_text"],
            transcription=transcription,
            character_error_rate=round(cer, 4),
            review=cer > 0.15,
            duration_ms=row["duration_ms"],
            elapsed_seconds=round(time.monotonic() - started, 2),
        )
        report["segments"].append(item)
        temporary = output.with_suffix(".partial")
        temporary.write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        temporary.replace(output)
        print(
            f"chapter {item['chapter_number']} segment {item['segment_number']}: CER {cer:.1%}; {transcription}",
            flush=True,
        )
    print(output, flush=True)


if __name__ == "__main__":
    main()
