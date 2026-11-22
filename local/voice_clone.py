"""Encode new MP3/WAV references locally and publish immutable server presets."""
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import wave
import base64

from tts.common import digest,file_digest

ROOT=Path(__file__).resolve().parents[1]


class LocalVoiceEncoder:
    def __init__(self,root=None,cache=None):
        self.root=Path(root or os.environ.get('SHANYE_ZONOS_ENCODER_ROOT',ROOT/'tmp/zonos3-mini')).expanduser()
        self.cache=Path(cache or ROOT/'.workbench/reference-voices')
        self.binary=self.root/'build-metal/zac-encoder-cli'
        self.encoder=self.root/'clone-models/zac-encoder.gguf'
        self.teacher=self.root/'clone-models/ssl-teacher.gguf'

    @property
    def ready(self):
        return all(p.is_file() for p in (self.binary,self.encoder,self.teacher)) and shutil.which('ffmpeg') is not None

    def prepare(self,data,filename,transcript=''):
        if not self.ready:
            raise ValueError('本机缺少 ZONOS3 参考音频编码器，请配置 SHANYE_ZONOS_ENCODER_ROOT')
        if not data or len(data)>20*1024*1024 or len(transcript)>3000:
            raise ValueError('参考音频须小于 20 MB，逐字稿最多 3000 字符')
        directory=self.cache/digest(data)
        directory.mkdir(parents=True,exist_ok=True)
        audio=directory/'reference.wav'
        codes=directory/'reference.codes.npy'
        metadata=directory/'encoded.json'
        with (directory/'.encode.lock').open('a+b') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX)
            existing=json.loads(metadata.read_text()) if metadata.exists() else None
            if not (existing and audio.is_file() and codes.is_file() and
                file_digest(audio)==existing['audio_sha256'] and file_digest(codes)==existing['codes_sha256']):
                original=directory/'input.media'
                original.write_bytes(data)
                try:
                    subprocess.run(['ffmpeg','-v','error','-nostdin','-y','-i',str(original),'-t','31','-ac','1','-ar','44100','-c:a','pcm_s16le',str(audio)],
                        check=True,capture_output=True,timeout=30)
                    with wave.open(str(audio),'rb') as wav:
                        duration=wav.getnframes()/wav.getframerate()
                        frames=wav.readframes(wav.getnframes())
                    if not 3<=duration<=30 or not any(frames):
                        raise ValueError('请选择 3–30 秒、非静音的单人参考录音')
                    result=subprocess.run([str(self.binary),str(self.encoder),str(self.teacher),'--audio',str(audio),str(codes),'--gpu'],
                        capture_output=True,timeout=240)
                    if result.returncode or not codes.is_file():
                        raise ValueError('参考音频编码失败，请重试或更换录音')
                except subprocess.CalledProcessError as exc:
                    raise ValueError('参考音频无法解码，请选择有效 MP3、WAV 或 M4A') from exc
                except subprocess.TimeoutExpired as exc:
                    raise ValueError('参考音频编码超时，原有音色仍可使用') from exc
                existing=dict(audio_sha256=file_digest(audio),codes_sha256=file_digest(codes),duration_ms=round(duration*1000))
                metadata.write_text(json.dumps(existing))
            name=Path(filename).stem.strip()[:100] or '新参考音色'
            return dict(name=name,ref_text=transcript.strip(),**existing,
                audio_base64=base64.b64encode(audio.read_bytes()).decode(),codes_base64=base64.b64encode(codes.read_bytes()).decode())
