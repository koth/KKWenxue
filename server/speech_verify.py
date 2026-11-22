"""Resident CPU recognition process, isolated from the CUDA TTS environment."""
from __future__ import annotations

import argparse
from collections import deque
import json
from pathlib import Path
import queue
import subprocess
import sys
import tempfile
import threading
import time

from .speech_quality import VERIFICATION, compare_transcript


class SpeechVerifier:
    def __init__(self, settings):
        self.settings = settings
        self.process = None
        self.events = queue.Queue()
        self.errors = deque(maxlen=10)

    def close(self):
        process, self.process = self.process, None
        if process is None:
            return
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for pipe in (process.stdin, process.stdout, process.stderr):
            if pipe:
                pipe.close()

    def _receive(self):
        try:
            result = self.events.get(timeout=self.settings.speech_verify_timeout)
        except queue.Empty:
            self.close()
            raise TimeoutError('朗读内容校验超时，当前片段未发布') from None
        if result is None or result.get('error'):
            self.close()
            raise RuntimeError('朗读内容校验失败，当前片段未发布：' +
                               ((result or {}).get('error') or ''.join(self.errors)[-1000:]))
        return result

    def _start(self):
        if self.process is not None and self.process.poll() is None:
            return
        self.close()
        self.events = queue.Queue()
        self.errors.clear()
        self.process = subprocess.Popen([
            str(self.settings.speech_verify_python), '-m', 'server.speech_verify',
            '--model', str(self.settings.speech_verify_model),
            '--threads', str(self.settings.speech_verify_threads)],
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
            text=True,encoding='utf-8',bufsize=1)
        process, events = self.process, self.events

        def read():
            try:
                for line in process.stdout:
                    try:
                        result = json.loads(line)
                    except ValueError:
                        continue
                    if isinstance(result,dict):
                        events.put(result)
            finally:
                events.put(None)

        def errors():
            for line in process.stderr:
                self.errors.append(line)

        threading.Thread(target=read,daemon=True).start()
        threading.Thread(target=errors,daemon=True).start()
        if self._receive().get('stage') != 'ready':
            self.close()
            raise RuntimeError('朗读内容校验器未就绪')

    def verify(self, text, samples):
        import soundfile as sf
        self._start()
        root = self.settings.audio / '.speech-verify-work'
        root.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as temp:
            path = Path(temp)/'verify.wav'
            sf.write(path,samples,24000,subtype='PCM_16')
            self.process.stdin.write(json.dumps(dict(text=text,path=str(path)),ensure_ascii=False)+'\n')
            self.process.stdin.flush()
            result=self._receive()
            if (result.get('policy') != VERIFICATION or type(result.get('accepted')) is not bool
                    or not isinstance(result.get('gaps'),list)):
                self.close()
                raise RuntimeError('朗读内容校验报告无效，当前片段未发布')
            return result


def serve(model, threads):
    import numpy as np
    import sherpa_onnx
    recognizer = sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=str(model/'model.int8.onnx'),tokens=str(model/'tokens.txt'),
        num_threads=threads,language='zh',use_itn=False,provider='cpu')
    print(json.dumps(dict(stage='ready',policy=VERIFICATION)),flush=True)
    for line in sys.stdin:
        try:
            request=json.loads(line)
            started=time.perf_counter()
            decoded=subprocess.run(['ffmpeg','-v','error','-nostdin','-i',request['path'],
                '-ar','16000','-ac','1','-f','f32le','-'],capture_output=True,check=True,timeout=20)
            samples=np.frombuffer(decoded.stdout,dtype='<f4')
            stream=recognizer.create_stream()
            stream.accept_waveform(16000,samples)
            recognizer.decode_stream(stream)
            result=compare_transcript(request['text'],stream.result.text)
            tokens, timestamps = list(stream.result.tokens), list(stream.result.timestamps)
            if tokens and len(tokens) == len(timestamps):
                result['alignment'] = dict(tokens=tokens,timestamps=timestamps)
            result.update(seconds=time.perf_counter()-started,provider='cpu',model_revision='6a65851692da9706cbddfac66ea9b96ebb1dee21')
        except Exception as exc:
            result=dict(error=str(exc))
        print(json.dumps(result,ensure_ascii=False),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--model',type=Path,required=True)
    parser.add_argument('--threads',type=int,default=2)
    args=parser.parse_args()
    serve(args.model,args.threads)
