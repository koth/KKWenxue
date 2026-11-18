"""Immutable, pre-encoded voices. Published paths are relative to the audio root."""
from pathlib import Path
import json

from tts.common import file_digest
from .catalog import DomainError


def voice_manifest(settings, speaker):
    try:
        data=json.loads((settings.zonos_voice_path/'voices.json').read_text())
        voice=next(v for v in data['voices'] if v['speaker']==speaker)
    except (OSError,ValueError,KeyError,StopIteration) as exc:
        raise DomainError('这个音色尚未完成 ZONOS3 预编码，请选择已发布音色',503) from exc
    for field,checksum in [('audio_path','audio_sha256'),('codes_path','codes_sha256')]:
        path=(settings.audio/voice[field]).resolve()
        if not path.is_relative_to(settings.zonos_voice_path.resolve()):
            raise DomainError('音色缓存路径无效',503)
        if not path.is_file() or file_digest(path)!=voice[checksum]:
            raise DomainError('音色缓存校验失败，请重新发布音色',503)
    return voice
