#!/usr/bin/env python3
"""Accelerate a slow official ModelScope LFS transfer with verified ranges."""

import argparse
import concurrent.futures
import hashlib
import json
import shutil
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path(__file__).with_name("cuda-model-manifest.json"),
    )
    parser.add_argument("--file", required=True)
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    item = next(f for f in manifest["files"] if f["rfilename"] == args.file)
    expected = item["lfs"]["sha256"]
    size = item["size"]
    target = args.output / args.file
    partial = target.with_suffix(target.suffix + ".partial")
    # Only read an existing transfer's prefix; its process can keep downloading.
    prefix = min(partial.stat().st_size, size) if partial.exists() else 0
    chunk = 32 * 1024 * 1024
    prefix = prefix // chunk * chunk
    work = args.output / ".ranges" / expected
    work.mkdir(parents=True, exist_ok=True)
    url = f"https://modelscope.cn/models/{manifest['repo']}/resolve/master/{args.file}?download=true"
    started = time.monotonic()

    def get(start):
        end = min(size, start + chunk) - 1
        path = work / f"{start:012d}.part"
        if not path.exists() or path.stat().st_size != end - start + 1:
            subprocess.run(
                [
                    "curl",
                    "-4",
                    "-fLsS",
                    "--retry",
                    "3",
                    "--connect-timeout",
                    "15",
                    "--max-time",
                    "240",
                    "--range",
                    f"{start}-{end}",
                    "-o",
                    str(path),
                    url,
                ],
                check=True,
            )
        if path.stat().st_size != end - start + 1:
            raise ValueError(f"Wrong range length: {start}")
        print(f"Range verified length {start}-{end}", flush=True)
        return path

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        paths = list(pool.map(get, range(prefix, size, chunk)))
    merged = work / "merged.safetensors"
    with merged.open("wb") as destination:
        if prefix:
            with partial.open("rb") as source:
                remaining = prefix
                while remaining:
                    block = source.read(min(8 * 1024 * 1024, remaining))
                    if not block:
                        raise ValueError("Original partial prefix disappeared")
                    destination.write(block)
                    remaining -= len(block)
        for path in paths:
            with path.open("rb") as source:
                shutil.copyfileobj(source, destination)
    checksum = hashlib.sha256()
    with merged.open("rb") as source:
        for block in iter(lambda: source.read(8 * 1024 * 1024), b""):
            checksum.update(block)
    if merged.stat().st_size != size or checksum.hexdigest() != expected:
        raise ValueError("Model checksum mismatch")
    merged.replace(target)
    print(
        json.dumps(
            dict(
                file=args.file,
                sha256=expected,
                seconds=round(time.monotonic() - started, 2),
                verified=True,
            )
        ),
        flush=True,
    )


if __name__ == "__main__":
    main()
