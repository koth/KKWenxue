"""Reader-compatible text, locking and audio primitives shared by CLI and service."""

from __future__ import annotations

import fcntl
import hashlib
import os
import re
from pathlib import Path

TERMINAL = set("。！？!?…")
CLOSERS = set("”’」』》〉）)]}】〕")
NON_SPOKEN_REASON = "仅含空白、标点或符号，无需朗读"


def has_spoken_content(text: str) -> bool:
    """Keep even one letter/number; punctuation alone must never reach TTS."""
    return any(char.isalnum() for char in text)


def digest(value: str | bytes) -> str:
    return hashlib.sha256(
        value.encode("utf-8") if isinstance(value, str) else value
    ).hexdigest()


def file_digest(path: Path) -> str:
    sha = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def utf16_len(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


def canonical_text(body: str) -> str:
    lines = body.splitlines()
    indent = min((len(s) - len(s.lstrip()) for s in lines if s.strip()), default=0)
    if lines and not lines[0].strip():
        lines = lines[1:]
    if lines and not lines[-1].strip():
        lines = lines[:-1]
    source = "\n".join(s[indent:] for s in lines).strip()
    return "\n\n".join(
        "　　" + p.strip().replace("\n", "") for p in re.split(r"\n\s*\n", source)
    )


def chapter_number(title: str, index: int) -> int:
    match = re.match(r"第\s*(\d+)\s*章", title)
    return int(match.group(1)) if match else index + 1


def sentence_spans(text: str):
    start = index = 0
    while index < len(text):
        if text[index] in TERMINAL:
            index += 1
            while index < len(text) and text[index] in TERMINAL:
                index += 1
            while index < len(text) and text[index] in CLOSERS:
                index += 1
            yield start, index
            start = index
        else:
            index += 1
    if start < len(text):
        yield start, len(text)


def bounded_spans(text: str, start: int, end: int, maximum: int = 160):
    while end - start > maximum:
        cut = start + maximum
        candidates = [
            i + 1 for i in range(start + maximum // 2, cut) if text[i] in "，,；;：:\n"
        ]
        if candidates:
            cut = candidates[-1]
        yield start, cut
        start = cut
    if start < end:
        yield start, end


def gpu_lock_path() -> Path:
    return Path(
        os.environ.get("SHANYE_GPU_LOCK", "~/.local/state/shanye-tts/gpu.lock")
    ).expanduser()


class GPULock:
    def __init__(self, path: Path | None = None):
        self.path = path or gpu_lock_path()
        self.handle = None

    def __enter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.handle = self.path.open("a+")
        try:
            fcntl.flock(self.handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            self.handle.close()
            self.handle = None
            raise RuntimeError("GPU is occupied by another executor") from None
        return self

    def __exit__(self, *_):
        if self.handle:
            fcntl.flock(self.handle, fcntl.LOCK_UN)
            self.handle.close()
            self.handle = None


def validated_samples(result, segment_id: str):
    import numpy as np

    samples = np.asarray(result, dtype=np.float32).reshape(-1)
    duration = len(samples) / 24000
    if not np.isfinite(samples).all() or duration < 0.2 or duration > 120:
        raise ValueError(f"Invalid audio for {segment_id}: {duration:.2f}s")
    if float(np.max(np.abs(samples))) < 0.001:
        raise ValueError(f"Silent audio for {segment_id}")
    return samples
