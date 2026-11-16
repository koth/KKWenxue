#!/usr/bin/env python3
"""Download official Qwen weights via ModelScope, verify against pinned HF objects."""

import argparse
import concurrent.futures
import hashlib
import json
import shutil
import subprocess
from pathlib import Path


def verified(path, item):
    if not path.is_file() or path.stat().st_size != item["size"]:
        return False
    lfs = item.get("lfs")
    checksum = hashlib.sha256() if lfs else hashlib.sha1()
    if not lfs:
        checksum.update(f"blob {item['size']}\0".encode())
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(8 * 1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest() == (lfs["sha256"] if lfs else item["blobId"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path(__file__).with_name("cuda-model-manifest.json"),
    )
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    args.output.mkdir(parents=True, exist_ok=True)

    def download(item):
        name = item["rfilename"]
        if name in {".gitattributes", "README.md"}:
            return
        target = args.output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if verified(target, item):
            print(f"Verified cached {name}", flush=True)
            return
        temporary = target.with_suffix(target.suffix + ".partial")
        url = f"https://modelscope.cn/models/{manifest['repo']}/resolve/master/{name}"
        # The server image has an older curl without --retry-all-errors.
        curl = [
            "curl",
            "-4",
            "-fL",
            "-sS",
            "--retry",
            "4",
            "--connect-timeout",
            "15",
            "--speed-limit",
            "1024",
            "--speed-time",
            "90",
        ]
        # ModelScope may reject a range request for a new object. Resume only
        # when a non-empty partial file exists; a fresh download is plain GET.
        if temporary.is_file() and temporary.stat().st_size:
            curl += ["-C", "-"]
        curl += ["-o", str(temporary), url]
        try:
            subprocess.run(curl, check=True)
        except subprocess.CalledProcessError:
            # A stale partial can also be non-resumable. Retry that file once
            # from byte zero without deleting any unrelated model file.
            if temporary.is_file() and temporary.stat().st_size:
                temporary.unlink()
                subprocess.run(
                    [part for part in curl if part not in ["-C", "-"]], check=True
                )
            else:
                raise
        if not verified(temporary, item):
            raise ValueError(
                f"ModelScope file differs from pinned Hugging Face revision: {name}"
            )
        temporary.replace(target)
        print(f"Verified {name}: {target.stat().st_size / 1e6:.1f} MB", flush=True)

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(download, manifest["files"]))
    shutil.copy2(args.manifest, args.output / "download-manifest.json")
    print("All model files verified; revision", manifest["revision"], flush=True)


if __name__ == "__main__":
    main()
