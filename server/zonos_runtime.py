"""Resident native CUDA server, cached reference codes, and fail-closed ASR QA."""
from collections import deque
import io
import json
import os
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request

from tts.common import digest, file_digest, has_spoken_content
from .config import ENGINE
from .speech_quality import native_speech_text
from .speech_verify import SpeechVerifier

INFERENCE_REVISION='a50e1411-sm87-ptq-keep2-laci-v1'
SPEECH_PLAN='zonos-native-laci-bounded160-v1'
VERIFICATION='sensevoice-coverage-additions-v2'


def generation_seed(row,config):
    seed=config['seed']+row['chapter_index']*100000+row['segment_index']
    seed+=(row.get('attempt_number',1)-1)*1_000_003
    if row.get('depth',0):seed+=int(digest(row['id'])[:8],16)
    return seed % 2**32


def content_report(report):
    # Coverage-only QA tolerates insertions. A new engine must also reject
    # hallucinated phrases, especially after one-character Chinese utterances.
    additions=[d for d in report.get('differences',[])
               if len(d['actual_units'])-len(d['expected_units'])>=2]
    return {**report,'policy':VERIFICATION,'additions':additions,
            'accepted':report['accepted'] and not additions}


class ZonosEngine:
    def __init__(self,settings,status):
        self.settings,self.status=settings,status
        self.process=None
        self.voices={}
        self.stderr=deque(maxlen=50)
        self.last_execution={}
        self.verifier=SpeechVerifier(settings)

    @property
    def url(self):return 'http://127.0.0.1:'+str(self.settings.zonos_port)

    def _request(self,path,payload=None,timeout=None):
        data=None if payload is None else json.dumps(payload,ensure_ascii=False).encode()
        req=urllib.request.Request(self.url+path,data=data,headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=timeout or self.settings.zonos_timeout) as res:
            return res.read()

    def close(self):
        self.verifier.close()
        process,self.process=self.process,None
        self.voices={}
        if process is None:return
        if process.poll() is None:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
        if process.stderr:process.stderr.close()

    def _start(self):
        if self.process is not None and self.process.poll() is None:return
        self.close()
        if not self.settings.zonos_ready:raise RuntimeError('ZONOS3 原生引擎或模型不完整')
        # Never attach to an unrelated process occupying this private port.
        with socket.socket() as sock:
            # A stopped native server can leave accepted sockets in TIME_WAIT.
            # Permit that restart while still rejecting a live listener.
            sock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            sock.bind(('127.0.0.1',self.settings.zonos_port))
        self.status.update(model='loading',engine=ENGINE,gpu='loading')
        command=[str(self.settings.zonos_binary),str(self.settings.zonos_model_path/'backbone-ptq.gguf'),
            '--gpu','--zac-gpu','--batch','1','--zac-threads','1','--max','1000',
            '--host','127.0.0.1','--port',str(self.settings.zonos_port),
            '--tts-default-voices-dir',str(self.settings.zonos_voice_path),
            '--laci-heads','cmn='+str(self.settings.zonos_root/'zh-heads.json')]
        environment=dict(os.environ)
        environment['LD_LIBRARY_PATH']=str(self.settings.zonos_root/'lib')+os.pathsep+environment.get('LD_LIBRARY_PATH','')
        self.process=subprocess.Popen(command,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,
            text=True,encoding='utf-8',env=environment)
        process=self.process
        def read():
            for line in process.stderr:self.stderr.append(line.rstrip())
        threading.Thread(target=read,daemon=True).start()
        deadline=time.monotonic()+self.settings.zonos_timeout
        try:
            while time.monotonic()<deadline:
                if process.poll() is not None:raise RuntimeError('ZONOS3 启动失败：'+'\n'.join(self.stderr)[-2000:])
                try:
                    result=json.loads(self._request('/v2/speakers',timeout=2))
                    self.voices={v['original_name']:v for v in result['speakers']}
                    self.status.update(model='ready',engine=ENGINE,gpu='CUDA Orin')
                    return
                except urllib.error.URLError:time.sleep(.2)
            raise TimeoutError('ZONOS3 启动超时')
        except Exception:
            self.close();self.status.update(model='failed');raise

    def generate(self,rows,config):
        if config.get('engine')!=ENGINE or config.get('inference_revision')!=INFERENCE_REVISION:
            raise RuntimeError('旧合成配置已停用，请使用 ZONOS3 已发布音色创建任务')
        if config.get('speech_plan')!=SPEECH_PLAN:raise RuntimeError('未知的朗读策略')
        codes=(self.settings.audio/config['reference_codes_path']).resolve()
        if not codes.is_relative_to(self.settings.zonos_voice_path.resolve()) or file_digest(codes)!=config['reference_codes_sha256']:
            raise RuntimeError('参考语音缓存已变更，当前任务不能使用其他音色')
        self._start()
        voice=self.voices.get(config['native_voice_name'])
        if not voice or 'reference' not in voice.get('speaker_modes',[]):
            raise RuntimeError('原生服务未加载这个预编码音色')
        results=[]
        for row in rows:
            seed=generation_seed(row,config)
            execution=dict(seed=seed,engine=ENGINE,batch_size=1,native=True,
                inference_revision=INFERENCE_REVISION,reference_cache_hit=True,
                speech_plan=SPEECH_PLAN,parts=[],state='running',verification_checks=[])
            self.last_execution[row['id']]=execution
            try:
                if not has_spoken_content(row['spoken_text']):raise ValueError('当前分段没有可朗读文本')
                for attempt in range(2):
                    actual_seed=(seed+attempt*10_000_019) % 2**32
                    text=native_speech_text(row['spoken_text'])
                    payload=dict(text=text,language='cmn',stream=False,format='wav',
                        seed=actual_seed,speaker_embedding_id=voice['id'],speaker_mode='reference',
                        max_tokens=800,laci=True,short_text='auto',temperature=.75,topk=80,top_p=.69,
                        repetition_penalty=2.4,repetition_window=96,repetition_codebooks=6)
                    started=time.monotonic()
                    try:audio=self._request('/v2/generate',payload)
                    except Exception:
                        self.close();raise
                    import numpy as np
                    import soundfile as sf
                    raw,sr=sf.read(io.BytesIO(audio),dtype='float32')
                    if raw.ndim!=1 or sr!=44100 or len(raw)<4410 or not np.isfinite(raw).all():
                        raise ValueError('ZONOS3 返回的音频无效')
                    decoded=subprocess.run(['ffmpeg','-v','error','-nostdin','-i','pipe:0',
                        '-ac','1','-ar','24000','-f','f32le','pipe:1'],input=audio,
                        capture_output=True,check=True,timeout=30)
                    samples=np.frombuffer(decoded.stdout,dtype='<f4').copy()
                    part=dict(seed=actual_seed,state='completed',generation_seconds=time.monotonic()-started,
                        audio_seconds=len(samples)/24000)
                    execution['parts'].append(part)
                    if config.get('verification'):
                        if config['verification']!=VERIFICATION:raise RuntimeError('未知的内容校验策略')
                        began=time.monotonic();report=content_report(self.verifier.verify(row['spoken_text'],samples))
                        execution['verification_checks'].append(report)
                        part['verify_seconds']=time.monotonic()-began
                        if not report['accepted']:
                            part['state']='rejected-content'
                            if attempt==0:continue
                            raise ValueError('朗读内容校验未通过，疑似漏读或新增内容，当前片段未发布')
                        execution.update(content_verified=True,verification_policy=VERIFICATION)
                    execution.update(state='completed',audio_seconds=len(samples)/24000,
                        source_sample_rate=44100,stored_sample_rate=24000,
                        generation_seconds=sum(p['generation_seconds'] for p in execution['parts']))
                    results.append(samples)
                    break
            except Exception as exc:
                execution.update(state='failed',error=str(exc));results.append(exc)
        return results
