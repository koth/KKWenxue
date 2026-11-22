"""Validate and publish preencoded references without loading encoder weights."""
import base64
import fcntl
import io
import json
import time
import wave

import numpy as np

from tts.audio import atomic_bytes
from tts.common import digest, file_digest
from .catalog import DomainError
from .db import encoded, transaction
from .voices import NARRATOR_INSTRUCTION


def publish_reference(db, settings, payload):
    try:
        name=payload['name'].strip()
        transcript=payload.get('ref_text','').strip()
        if not name or len(name)>100 or len(transcript)>3000:
            raise ValueError('音色名称或逐字稿长度无效')
        audio=base64.b64decode(payload['audio_base64'],validate=True)
        codes=base64.b64decode(payload['codes_base64'],validate=True)
        if len(audio)>3*1024*1024 or len(codes)>128*1024:
            raise ValueError('参考音频或编码过大')
        if digest(audio)!=payload['audio_sha256'] or digest(codes)!=payload['codes_sha256']:
            raise ValueError('参考音频或编码校验失败')
        with wave.open(io.BytesIO(audio),'rb') as wav:
            duration=wav.getnframes()/wav.getframerate()
            if wav.getnchannels()!=1 or wav.getframerate()!=44100 or wav.getsampwidth()!=2 or not 3<=duration<=30:
                raise ValueError('参考音频须为 3–30 秒单声道 44.1kHz PCM WAV')
            if len(wav.readframes(wav.getnframes()))!=wav.getnframes()*2:
                raise ValueError('参考音频不完整')
        values=np.load(io.BytesIO(codes),allow_pickle=False)
        if not isinstance(values,np.ndarray) or values.ndim!=2 or values.shape[1]!=10 or not 1<=values.shape[0]<=500 or not np.issubdtype(values.dtype,np.integer) or values.min()<0 or values.max()>4095:
            raise ValueError('参考编码须为 [T,10] 且取值为 0–4095')
    except (KeyError,TypeError,ValueError,wave.Error,EOFError) as exc:
        raise DomainError(str(exc) or '参考音色格式无效') from exc
    identifier='upload-'+digest(encoded([name,payload['audio_sha256'],payload['codes_sha256'],transcript]))[:24]
    speaker='clone:'+identifier
    preset='clone-'+identifier
    root=settings.zonos_voice_path
    root.mkdir(parents=True,exist_ok=True)
    with (root/'.publish.lock').open('a+b') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        audio_path=root/(identifier+'.wav')
        codes_path=root/(identifier+'.codes.npy')
        for path,data,checksum in [(audio_path,audio,payload['audio_sha256']),(codes_path,codes,payload['codes_sha256'])]:
            if path.exists():
                if file_digest(path)!=checksum:
                    raise DomainError('已有音色文件校验失败，请联系管理员',503)
            else:
                atomic_bytes(path,data)
        manifest_path=root/'voices.json'
        manifest=json.loads(manifest_path.read_text()) if manifest_path.exists() else {'voices':[]}
        voice=dict(speaker=speaker,name=name,native_name=audio_path.name,
            audio_path=str(audio_path.relative_to(settings.audio)),audio_sha256=payload['audio_sha256'],
            codes_path=str(codes_path.relative_to(settings.audio)),codes_sha256=payload['codes_sha256'],
            transcript=transcript)
        voices=[v for v in manifest['voices'] if v['speaker']!=speaker]
        manifest['voices']=voices+[voice]
        with transaction(db):
            db.execute('INSERT OR IGNORE INTO voice_clone_refs VALUES (?,?,?,?,?,?,?,?)',
                (identifier,voice['audio_path'],transcript,0,voice['audio_sha256'],round(duration*1000),1,time.time()))
            db.execute('INSERT OR IGNORE INTO presets VALUES (?,?,?,?,?,?,?)',
                (preset,name,speaker,NARRATOR_INSTRUCTION,encoded(dict(speed=1.,pause_ms=180,format='m4a',normalize_loudness=True)),time.time(),1))
            atomic_bytes(manifest_path,json.dumps(manifest,ensure_ascii=False,indent=2).encode())
    return dict(id=preset,speaker=speaker,name=name,message=f'已创建音色「{name}」，可以选择章节试听。')
