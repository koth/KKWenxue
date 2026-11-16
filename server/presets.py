from __future__ import annotations

import json
import time
import uuid

from .catalog import DomainError
from .config import ENGINE, MODEL, REVISION, NORMALIZATION, SEGMENTATION
from .db import encoded
from .voices import available_voices, DEFAULT_PRESET_ID, NARRATOR_INSTRUCTION, is_clone_speaker


def capabilities(settings):
    config = settings.audio8_model_path / "config.json"
    voices = available_voices(settings)
    default_preset = DEFAULT_PRESET_ID
    return {
        "engine": ENGINE,
        "model": MODEL,
        "revision": REVISION,
        "voices": [{**voice, "confirmed": False} for voice in voices],
        "default_preset_id": default_preset,
        "languages": ["Chinese"],
        "instruction": False,
        "voice_cloning": settings.audio8_ready,
        "reference_text_required": True,
        "voice_clone_model": str(settings.audio8_model_path),
        "voice_design": False,
        "source": "installed-model-config"
        if config.is_file()
        else "pinned-model-manifest",
        "speed_range": [0.75, 1.5],
        "pause_ms_range": [0, 2000],
    }


def validate_export(value):
    if set(value) - {"speed", "pause_ms", "format", "normalize_loudness"}:
        raise DomainError("未知导出参数")
    speed = value.get("speed", 1.0)
    pause = value.get("pause_ms", 180)
    if (
        isinstance(speed, bool)
        or not isinstance(speed, (float, int))
        or not 0.75 <= speed <= 1.5
    ):
        raise DomainError("speed 必须在 0.75–1.5 之间", details={"field": "speed"})
    if type(pause) is not int or not 0 <= pause <= 2000:
        raise DomainError("pause_ms 必须为 0–2000 整数", details={"field": "pause_ms"})
    if value.get("format", "m4a") != "m4a":
        raise DomainError("首版仅支持 m4a 成品")
    normalize = value.get("normalize_loudness", True)
    if type(normalize) is not bool:
        raise DomainError(
            "normalize_loudness 必须为布尔值", details={"field": "normalize_loudness"}
        )
    return {
        "speed": float(speed),
        "pause_ms": pause,
        "format": "m4a",
        "normalize_loudness": normalize,
    }


def create_preset(db, settings, name, speaker, instruct, export):
    if not is_clone_speaker(speaker):
        raise DomainError("Audio8 使用参考音频音色", details={"field": "speaker"})
    if (
        not name.strip()
        or len(name) > 100
        or not instruct.strip()
        or len(instruct) > 2000
    ):
        raise DomainError("名称须为 1–100 字符，指令须为 1–2000 字符")
    identifier = uuid.uuid4().hex
    db.execute(
        "INSERT INTO presets VALUES (?,?,?,?,?,?,0)",
        (
            identifier,
            name.strip(),
            speaker,
            instruct.strip(),
            encoded(validate_export(export)),
            time.time(),
        ),
    )
    return identifier


def snapshot(db, settings, preset_id, export):
    row = db.execute("SELECT * FROM presets WHERE id=?", (preset_id,)).fetchone()
    if not row:
        raise DomainError(
            "预设版本不存在", details={"field": "voice_preset_version_id"}
        )
    clone_id = row["speaker"][6:] if row["speaker"].startswith("clone:") else None
    if clone_id:
        ref = db.execute(
            "SELECT * FROM voice_clone_refs WHERE id=? AND ready=1", (clone_id,)
        ).fetchone()
        if not ref:
            raise DomainError("参考音频不存在或尚未校验")
        if not settings.audio8_ready:
            raise DomainError("服务器尚未安装完整的 Audio8 C++ 引擎和模型", 503)
        if not (ref["ref_text"] or "").strip():
            raise DomainError("Audio8 音色需要与参考音频逐字对应的文本，请补充参考文本", details={"field": "ref_text"})
    else:
        raise DomainError("旧固定音色已停用，请选择参考音频音色")
    generation = dict(
        engine=ENGINE,
        model=MODEL,
        revision=REVISION,
        speaker=row["speaker"],
        language="Chinese",
        inference_mode="reference-codes",
        inference_revision="expanded-kv-v3",
        style_source="reference-audio",
        text_frontend=False,
        seed=42,
        dtype="bfloat16",
        temperature=0.7,
        top_p=0.9,
        top_k=50,
        normalization=NORMALIZATION,
        segmentation=SEGMENTATION,
    )
    if clone_id:
        generation.update(
            model=MODEL,
            revision=REVISION,
            voice_clone_ref_id=clone_id,
            ref_audio_sha256=ref["sha256"],
            ref_text=ref["ref_text"],
            ref_audio_path=ref["path"],
        )
    return {
        "voice_preset_version_id": row["id"],
        "preset_name": row["name"],
        "generation": generation,
        "export": validate_export(
            export if export is not None else json.loads(row["export_json"])
        ),
    }
