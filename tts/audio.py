"""Bounded-memory audio rendering; no model dependencies."""

from __future__ import annotations

import os
import json
import math
import re
import shutil
from pathlib import Path
import subprocess
import tempfile

from .common import file_digest

LOUDNESS_VERSION = "ebu-r128-two-pass-v1"
TARGET_LUFS = -18.0
TRUE_PEAK_DB = -2.0


def measure_loudness(path: Path):
    """Measure decoded audio, including AAC overshoots, using FFmpeg loudnorm."""
    result = subprocess.run(
        [
            "ffmpeg",
            "-hide_banner",
            "-nostdin",
            "-nostats",
            "-i",
            str(path),
            "-af",
            f"loudnorm=I={TARGET_LUFS}:TP={TRUE_PEAK_DB}:LRA=11:print_format=json",
            "-f",
            "null",
            "-",
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    match = re.search(r'\{\s*"input_i".*?\}', result.stderr, re.S)
    if not match:
        raise ValueError("FFmpeg did not return loudness measurements")
    raw = json.loads(match.group())
    measured = {}
    for key in ("input_i", "input_tp", "input_lra", "input_thresh", "target_offset"):
        value = float(raw[key])
        measured[key] = value if math.isfinite(value) else None
    return measured


def normalize_audio(source: Path, target: Path, expected_frames: int):
    """Normalize the assembled chapter; preserve clip dynamics and cue samples."""
    import soundfile as sf

    measured = measure_loudness(source)
    if measured["input_i"] is None:
        # EBU integration needs 400ms. A valid single-character chapter can be
        # shorter; use bounded peak gain rather than failing or adding silence.
        if measured["input_tp"] is None:
            raise ValueError("Cannot normalize silent audio")
        gain = min(18.0, TRUE_PEAK_DB - measured["input_tp"])
        effect = f"volume={gain}dB"
        mode = "short-audio-peak-gain"
    else:
        effect = (
            f"loudnorm=I={TARGET_LUFS}:TP={TRUE_PEAK_DB}:LRA={max(11.0, measured['input_lra'])}"
            f":measured_I={measured['input_i']}:measured_TP={measured['input_tp']}"
            f":measured_LRA={measured['input_lra']}:measured_thresh={measured['input_thresh']}"
            f":offset={measured['target_offset']}:linear=true"
        )
        mode = "two-pass-loudnorm"
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-nostdin",
            "-y",
            "-i",
            str(source),
            "-af",
            effect,
            "-ar",
            "24000",
            "-ac",
            "1",
            "-c:a",
            "pcm_s16le",
            str(target),
        ],
        check=True,
    )
    info = sf.info(target)
    if info.frames != expected_frames or info.samplerate != 24000:
        raise ValueError("Loudness processing changed the cue sample timeline")
    return {
        "version": LOUDNESS_VERSION,
        "mode": mode,
        "target_lufs": TARGET_LUFS,
        "true_peak_limit_db": TRUE_PEAK_DB,
        "input_lufs": measured["input_i"],
        "input_true_peak_db": measured["input_tp"],
    }


def media_duration(path: Path) -> float:
    if shutil.which("ffprobe"):
        return float(
            subprocess.check_output(
                [
                    "ffprobe",
                    "-v",
                    "error",
                    "-show_entries",
                    "format=duration",
                    "-of",
                    "default=noprint_wrappers=1:nokey=1",
                    str(path),
                ],
                text=True,
            )
        )
    # Existing GPU environment ships a standalone ffmpeg, without ffprobe.
    result = subprocess.run(
        ["ffmpeg", "-hide_banner", "-i", str(path), "-f", "ffmetadata", "-"],
        text=True,
        capture_output=True,
        check=True,
    )
    match = re.search(r"Duration: (\d+):(\d+):(\d+\.\d+)", result.stderr)
    if not match:
        raise ValueError("Unable to read encoded media duration")
    return int(match[1]) * 3600 + int(match[2]) * 60 + float(match[3])


def atomic_bytes(path: Path, payload: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        dir=path.parent, prefix=".pending-", delete=False
    ) as handle:
        temporary = Path(handle.name)
        handle.write(payload)
        handle.flush()
        os.fsync(handle.fileno())
    temporary.replace(path)


def write_flac(path: Path, samples):
    import soundfile as sf

    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".pending")
    sf.write(temporary, samples, 24000, format="FLAC", subtype="PCM_16")
    with temporary.open("rb") as handle:
        os.fsync(handle.fileno())
    temporary.replace(path)


def encode_aac(source, target, title, album, gain_db=0.0):
    args = ["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", str(source)]
    if gain_db:
        args += ["-af", f"volume={gain_db}dB"]
    subprocess.run(
        args
        + [
            "-c:a",
            "aac",
            "-b:a",
            "64k",
            "-movflags",
            "+faststart",
            "-metadata",
            f"title={title}",
            "-metadata",
            f"album={album}",
            str(target),
        ],
        check=True,
    )


def quiet_edge(samples, rate=24000, tail=False):
    """Count contiguous low-energy edge frames without touching audible speech."""
    import numpy as np
    frame=max(1,rate//200)
    blocks=samples[:len(samples)//frame*frame].reshape(-1,frame)
    if not len(blocks): return 0
    rms=np.sqrt(np.mean(blocks**2,axis=1))
    threshold=max(.00003,min(.0003,float(np.percentile(rms,95))*.02))
    count=0
    for level in (rms[::-1] if tail else rms):
        if level>=threshold: break
        count+=frame
    return min(count,len(samples)//2)  # Never erase an entire low-energy clip.


def render(
    clips, target: Path, speed, pause_ms, title, album, normalize_loudness=False,
    natural_title_pause=False,
):
    """clips contain path and original anchor metadata; returns sample-derived cues."""
    import numpy as np
    import soundfile as sf

    target.parent.mkdir(parents=True, exist_ok=True)
    rate, cursor, cues, title_cues = 24000, 0, [], []
    previous_tail = 0
    fields = (
        "segment_id",
        "sentence_index",
        "sentence_start_utf16",
        "sentence_end_utf16",
        "start_utf16",
        "end_utf16",
        "original_text",
        "spoken_text",
    )
    with tempfile.TemporaryDirectory(dir=target.parent, prefix=".render-") as temp:
        work = Path(temp)
        wav = work / "chapter.wav"
        with sf.SoundFile(
            wav, "w", samplerate=rate, channels=1, subtype="PCM_16"
        ) as audio:
            for index, clip in enumerate(clips):
                source = clip["path"]
                if speed != 1.0:
                    source = work / "stretched.wav"
                    subprocess.run(
                        [
                            "ffmpeg",
                            "-v",
                            "error",
                            "-y",
                            "-i",
                            str(clip["path"]),
                            "-af",
                            f"atempo={speed}",
                            "-c:a",
                            "pcm_s16le",
                            str(source),
                        ],
                        check=True,
                    )
                samples, actual_rate = sf.read(source, dtype="float32")
                if (
                    actual_rate != rate
                    or samples.ndim != 1
                    or not np.isfinite(samples).all()
                ):
                    raise ValueError("Invalid clip sample format")
                if index:
                    after_heading = (clips[index-1].get("kind") == "chapter_title"
                                     and clip.get("kind") != "chapter_title")
                    if after_heading and natural_title_pause:
                        target_gap=round(max(350,pause_ms)*rate/1000)
                        head=quiet_edge(samples,rate)
                        excess=max(0,previous_tail+head-target_gap)
                        trim_head=min(head,excess)
                        samples=samples[trim_head:];head-=trim_head;excess-=trim_head
                        trim_tail=min(previous_tail,excess)
                        if trim_tail:
                            cursor-=trim_tail;audio.seek(cursor)
                            title_cues[-1]['end_ms']=round(cursor*1000/rate)
                        gap=max(0,target_gap-(previous_tail-trim_tail)-head)
                    else:
                        gap = round((max(650, pause_ms) if after_heading else pause_ms) * rate / 1000)
                    audio.write(np.zeros(gap, dtype=np.float32))
                    cursor += gap
                start = cursor
                audio.write(samples)
                cursor += len(samples)
                previous_tail=quiet_edge(samples,rate,tail=True) if natural_title_pause else 0
                (title_cues if clip.get("kind") == "chapter_title" else cues).append(
                    {
                        **{k: clip[k] for k in fields},
                        "audio_id": clip.get("audio_id"),
                        "start_ms": round(start * 1000 / rate),
                        "end_ms": round(cursor * 1000 / rate),
                    }
                )
            audio.truncate(cursor)
        loudness = None
        if normalize_loudness:
            normalized = work / "normalized.wav"
            loudness = normalize_audio(wav, normalized, cursor)
            wav = normalized
        encoded = work / "encoded.m4a"
        gain_db = 0.0
        for _ in range(3):
            encode_aac(wav, encoded, title, album, gain_db)
            if loudness is None:
                break
            measured = measure_loudness(encoded)
            # AAC may overshoot its PCM source. Correct from the WAV, without
            # another lossy generation, then remeasure the actual playback file.
            if measured["input_tp"] is None:
                raise ValueError("Normalized AAC has no measurable signal")
            if measured["input_tp"] <= -1.0:
                loudness.update(
                    output_lufs=measured["input_i"],
                    output_true_peak_db=measured["input_tp"],
                    codec_peak_correction_db=gain_db,
                )
                break
            gain_db -= measured["input_tp"] + 1.5
        else:
            raise ValueError("Normalized AAC exceeds the -1 dBTP playback ceiling")
        measured = media_duration(encoded)
        duration = round(cursor * 1000 / rate)
        if abs(measured * 1000 - duration) > 150:
            raise ValueError(
                "Encoded media duration differs from PCM by more than 150ms"
            )
        with encoded.open("rb") as handle:
            os.fsync(handle.fileno())
        encoded.replace(target)
    return {
        "cues": cues,
        "sample_rate": rate,
        "duration_ms": duration,
        "audio_sha256": file_digest(target),
        "encoder_tolerance_ms": 150,
        **({"title_cues": title_cues} if title_cues else {}),
        **({"loudness": loudness} if loudness is not None else {}),
    }
