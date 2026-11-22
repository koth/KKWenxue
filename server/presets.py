from __future__ import annotations

import json
import time
import uuid

from .catalog import DomainError
from .config import ENGINE, MODEL, REVISION, NORMALIZATION, SEGMENTATION
from .db import encoded
from .voices import available_voices, DEFAULT_PRESET_ID, NARRATOR_INSTRUCTION, is_clone_speaker
from .zonos_runtime import SPEECH_PLAN, VERIFICATION, INFERENCE_REVISION
from .zonos_voices import voice_manifest
from .narration import VERSION as NARRATION_VERSION


def capabilities(settings):
    return dict(engine=ENGINE,model=MODEL,revision=REVISION,
        voices=available_voices(settings),default_preset_id=DEFAULT_PRESET_ID,
        languages=['Chinese'],instruction=False,voice_cloning=True,
        reference_upload=False,reference_text_required=False,preencoded_upload=True,
        speech_verification=VERIFICATION if settings.speech_verify else None,
        voice_clone_model=str(settings.zonos_model_path),voice_design=False,
        source='published-preencoded-voices',speed_range=[.75,1.5],pause_ms_range=[0,2000])


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
    if not is_clone_speaker(speaker) and speaker != "zonos:haoran":
        raise DomainError("请选择已发布的 ZONOS3 音色", details={"field": "speaker"})
    if (
        not name.strip()
        or len(name) > 100
        or not instruct.strip()
        or len(instruct) > 2000
    ):
        raise DomainError("名称须为 1–100 字符，指令须为 1–2000 字符")
    voice_manifest(settings, speaker)
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
    row=db.execute('SELECT * FROM presets WHERE id=?',(preset_id,)).fetchone()
    if not row:
        raise DomainError('预设版本不存在',details={'field':'voice_preset_version_id'})
    if not settings.zonos_ready:
        raise DomainError('服务器尚未安装完整的 ZONOS3 原生引擎和模型',503)
    voice=voice_manifest(settings,row['speaker'])
    clone_id=row['speaker'][6:] if row['speaker'].startswith('clone:') else None
    generation=dict(engine=ENGINE,model=MODEL,revision=REVISION,
        speaker=row['speaker'],voice_name=voice.get('name',row['name']),language='Chinese',inference_mode='reference-codes',
        inference_revision=INFERENCE_REVISION,speech_plan=SPEECH_PLAN,
        verification=VERIFICATION if settings.speech_verify else None,
        style_source='reference-audio',text_frontend=True,seed=42,dtype='gguf-ptq',
        temperature=.75,top_p=.69,top_k=80,laci=True,short_text='auto',
        native_voice_name=voice['native_name'],reference_codes_path=voice['codes_path'],
        reference_codes_sha256=voice['codes_sha256'],
        ref_audio_sha256=voice['audio_sha256'],ref_audio_path=voice['audio_path'],
        ref_text=voice.get('transcript',''),normalization=NORMALIZATION,segmentation=SEGMENTATION)
    if clone_id:generation['voice_clone_ref_id']=clone_id
    return dict(voice_preset_version_id=row['id'],preset_name=row['name'],generation=generation,
        narration=dict(version=NARRATION_VERSION,titles={}),
        export=validate_export(export if export is not None else json.loads(row['export_json'])))
