"""Persistent JSONL bridge to the native Audio8 CUDA engine."""
from __future__ import annotations

from collections import OrderedDict, deque
import json
import logging
from pathlib import Path
import queue
import re
import subprocess
import tempfile
import threading
import time

from tts.common import digest, file_digest
# Historical adapter kept only to inspect old runs. The production worker uses
# ZonosEngine; these IDs must never follow the active engine's constants.
ENGINE='audio8-realistic-air-cpp'
REVISION='6bf7a980759d8022a66922ee975dab99229a2dd9'
from .speech_plan import SPEECH_PLAN, speech_fragments
from .speech_quality import VERIFICATION, native_speech_text
from .speech_verify import SpeechVerifier

log = logging.getLogger(__name__)


class IncompleteSpeechError(RuntimeError):
    """A request completed without enough speech to publish safely."""


def minimum_audio_seconds(text, minimum_syllables=32, maximum_rate=8):
    # At native 1x, a long Mandarin passage exceeding 8 syllables/second is
    # suspicious. This is a lower-bound check, not transcript verification.
    # Ignore Latin words/numbers rather than treating each letter as a syllable.
    syllables = len(re.findall(r"[\u3400-\u9fff]", text))
    return syllables / maximum_rate if syllables >= minimum_syllables else 0


def generation_seed(row, config):
    """Keep the original first pass, but explore a new sample on every retry."""
    seed = config["seed"] + row["chapter_index"] * 100000 + row["segment_index"]
    seed += (row.get("attempt_number", 1) - 1) * 1_000_003
    if row.get("depth", 0):
        seed += int(digest(row["id"])[:8], 16)
    return seed


class Audio8Engine:
    def __init__(self, settings, status):
        self.settings, self.status = settings, status
        self.process = None
        self.events = None
        self.stderr = deque(maxlen=40)
        self.last_execution = {}
        self.engine_info = {}
        self.voices = OrderedDict()
        self.verifier = SpeechVerifier(settings)

    @staticmethod
    def _signature(path):
        info = path.stat()
        return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns

    def _remember_voice(self, key, voice, reference, codes):
        self.voices[key] = (voice, [(path, self._signature(path)) for path in (reference, voice, codes)])
        self.voices.move_to_end(key)
        while len(self.voices) > 8:
            self.voices.popitem(last=False)
        return voice

    def close(self):
        self.verifier.close()
        process, self.process = self.process, None
        if process is None:
            return
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for pipe in (process.stdin, process.stdout, process.stderr):
            if pipe:
                pipe.close()

    def _receive(self, deadline):
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Audio8 合成超时，进程已重启以便安全重试")
            try:
                event = self.events.get(timeout=min(remaining, 1))
            except queue.Empty:
                if self.process.poll() is not None:
                    raise RuntimeError("Audio8 进程退出: " + "".join(self.stderr)[-3000:])
                continue
            if event is None:
                raise RuntimeError("Audio8 输出已关闭: " + "".join(self.stderr)[-3000:])
            if event.get("stage") == "error":
                raise RuntimeError(event.get("error", "Audio8 synthesis failed"))
            return event

    def _start(self):
        if self.process is not None and self.process.poll() is None:
            return
        self.close()
        if not self.settings.audio8_ready:
            raise RuntimeError("Audio8 C++ 引擎或模型文件不完整")
        self.status.update(model="loading", gpu="loading", engine=ENGINE)
        command = [str(self.settings.audio8_binary), "--model", str(self.settings.audio8_model_path),
                   "--codec", str(self.settings.audio8_codec_path), "--server"]
        if not self.settings.audio8_cuda_graph:
            command.append("--no-graphs")
        if self.settings.audio8_fused_kernels:
            command.append("--fused-kernels")
        self.events = queue.Queue()
        self.stderr.clear()
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True, encoding="utf-8", bufsize=1)
        process, events = self.process, self.events

        def stdout():
            try:
                for line in process.stdout:
                    try:
                        item = json.loads(line)
                        if isinstance(item, dict):
                            events.put(item)
                    except ValueError:
                        log.debug("Audio8 stdout: %s", line.rstrip())
            finally:
                events.put(None)

        def stderr():
            for line in process.stderr:
                self.stderr.append(line)

        threading.Thread(target=stdout, daemon=True).start()
        threading.Thread(target=stderr, daemon=True).start()
        deadline = time.monotonic() + self.settings.audio8_timeout
        try:
            while True:
                event = self._receive(deadline)
                if event.get("stage") == "ready":
                    self.engine_info = event.get("engine", {})
                    self.status.update(model="ready", gpu=self.engine_info.get("gpu", "CUDA"), engine=ENGINE)
                    self.status.pop("error", None)
                    return
        except Exception:
            self.close()
            raise

    def _voice(self, config):
        reference = self.settings.audio / config["ref_audio_path"]
        transcript = (config.get("ref_text") or "").strip()
        if not transcript:
            raise RuntimeError("Audio8 音色需要与参考音频逐字对应的文本，请补充参考文本")
        key = digest(json.dumps([REVISION, config["ref_audio_sha256"], transcript], ensure_ascii=False))
        cached = self.voices.get(key)
        if cached:
            voice, signatures = cached
            try:
                if signatures[0][0] == reference and all(self._signature(path) == signature for path, signature in signatures):
                    self.voices.move_to_end(key)
                    return voice
            except OSError:
                pass
            del self.voices[key]
        if not reference.is_file() or file_digest(reference) != config["ref_audio_sha256"]:
            raise RuntimeError("参考音频缺失或校验失败")
        directory = self.settings.audio / "audio8-voices" / key
        voice = directory / "voice.json"
        if voice.is_file():
            try:
                saved = json.loads(voice.read_text())
                codes = directory / "reference_codes.npy"
                if (saved["text"] == transcript and saved["reference_sha256"] == config["ref_audio_sha256"]
                        and codes.is_file() and file_digest(codes) == saved["codes_sha256"]):
                    return self._remember_voice(key, voice, reference, codes)
            except (ValueError, KeyError):
                pass
        # Free the native model before the one-time encoder uses the GPU. The
        # existing worker GPU lock covers both processes, including this step.
        self.close()
        directory.mkdir(parents=True, exist_ok=True)
        text_file = directory / "reference.txt"
        text_file.write_text(transcript, encoding="utf-8")
        self.status.update(model="preparing-voice", engine=ENGINE)
        result = subprocess.run([self.settings.audio8_python, "-m", "server.audio8_prepare", "voice",
                                 "--model", str(self.settings.audio8_model_path), "--output", str(directory),
                                 "--reference", str(reference), "--text-file", str(text_file)],
                                capture_output=True, text=True, timeout=self.settings.audio8_timeout)
        if result.returncode or not voice.is_file():
            raise RuntimeError("Audio8 参考音色编码失败: " + result.stderr[-3000:])
        return self._remember_voice(key, voice, reference, directory / "reference_codes.npy")

    def generate(self, rows, config):
        if config.get("engine") != ENGINE:
            raise RuntimeError("旧合成引擎已停用，请重新创建 Audio8 任务")
        if not config.get("voice_clone_ref_id"):
            raise RuntimeError("Audio8 任务必须指定参考音色")
        self.last_execution = {}
        try:
            voice = self._voice(config)
            self._start()
        except Exception as exc:
            self.status.update(model="failed", error=str(exc))
            raise
        results = []
        temporary_root = self.settings.audio / ".audio8-work"
        temporary_root.mkdir(parents=True, exist_ok=True)
        for row in rows:
            seed = generation_seed(row, config)
            policy = config.get("speech_plan")
            if policy not in (None, 'clauses32-v1', SPEECH_PLAN):
                raise RuntimeError("未知的 Audio8 朗读分段策略")
            if config.get('verification') not in (None, VERIFICATION):
                raise RuntimeError('未知的朗读内容校验策略')
            texts = speech_fragments(row["spoken_text"]) if policy else [row["spoken_text"]]
            execution = {
                "seed": seed, "batch_size": 1, "native": True, "engine": ENGINE,
                "minimum_audio_seconds": minimum_audio_seconds(row["spoken_text"]),
                "speech_plan": policy, "parts": [], "runtime": self.engine_info,
            }
            self.last_execution[row["id"]] = execution
            arrays = []
            try:
                if not texts:
                    raise RuntimeError("当前分段没有可朗读文本")
                for index, text in enumerate(texts):
                    arrays.append(self._generate_part(text, voice, config, seed, index, len(texts), execution, temporary_root))
                if config.get('verification'):
                    self._verify_and_repair(row['spoken_text'], texts, arrays, voice, config, seed, execution, temporary_root)
                if execution["output_audio_seconds"] < execution["minimum_audio_seconds"]:
                    raise RuntimeError("合成音频总时长过短，疑似提前结束")
                import numpy as np
                results.append(np.concatenate(arrays))
                execution.update(state="completed", audio_seconds=execution["output_audio_seconds"])
                self.status.pop("last_generation_error", None)
            except Exception as exc:
                execution.update(state="failed", error=str(exc))
                results.append(exc)
        return results

    def _generate_part(self, text, voice, config, seed, index, count, execution, temporary_root, reason='initial'):
        policy = config.get('speech_plan')
        for attempt in range(1, 3 if policy else 2):
            actual = dict(seed=seed+index+(attempt-1)*10_000_019,text=text,part_index=index,
                          attempt=attempt,reason=reason,
                          minimum_audio_seconds=max(.2,minimum_audio_seconds(text,1,6.5)) if policy else minimum_audio_seconds(text))
            actual['native_text'] = native_speech_text(text) if policy == SPEECH_PLAN else text
            end = actual['native_text'].rstrip().rstrip('”’」』》〉）)]}】〕')
            if policy and re.search(r'[\u3400-\u9fff]',text) and end and end[-1].isalnum():
                actual['native_text'] += '，' if index+1<count else '。'
            execution['parts'].append(actual)
            try:
                return self._generate_fragment(text,voice,config,actual,temporary_root)
            except IncompleteSpeechError:
                if not policy or attempt==2:
                    raise
            finally:
                self._aggregate_execution(execution)

    def _verify_and_repair(self, text, texts, arrays, voice, config, seed, execution, temporary_root):
        import numpy as np
        checks = execution['verification_checks'] = []
        spans, cursor = [], 0
        for part in texts:
            spans.append((cursor,cursor+len(part)))
            cursor += len(part)
        assert ''.join(texts)==text, 'Synthesis source coverage mismatch'
        for round in range(2):
            report = self.verifier.verify(text,np.concatenate(arrays))
            checks.append(dict(scope='segment',round=round,**report))
            if report['accepted']:
                execution.update(content_verified=True,verification_policy=VERIFICATION)
                return
            suspect = {i for i,(start,end) in enumerate(spans) for gap in report['gaps']
                       if gap['start']<end and gap['end']>start}
            repair=[]
            # Short omissions can be recognition mistakes at a join. Confirm
            # against the isolated source phrase before spending synthesis work.
            for index in sorted(suspect):
                isolated=self.verifier.verify(texts[index],arrays[index])
                checks.append(dict(scope='phrase',round=round,part_index=index,**isolated))
                if not isolated['accepted']:
                    repair.append(index)
            if suspect and not repair:
                execution.update(content_verified=True,verification_policy=VERIFICATION,
                                 verification_basis='isolated-confirmation')
                return
            if round==1 or not repair:
                missing='、'.join(g['text'] for g in report['gaps'])[:100]
                raise IncompleteSpeechError('朗读内容校验未通过，疑似漏读：'+missing)
            for index in repair:
                for part in execution['parts']:
                    if part['part_index']==index and part.get('state')=='completed':
                        part['state']='rejected-content'
                arrays[index]=self._generate_part(texts[index],voice,config,seed+1_000_000_007,
                    index,len(texts),execution,temporary_root,reason='content-repair')
                execution['content_retries']=execution.get('content_retries',0)+1

    def _generate_fragment(self, text, voice, config, actual, temporary_root):
        try:
            self._start()
            with tempfile.TemporaryDirectory(dir=temporary_root) as temporary:
                directory = Path(temporary)
                request = {"op": "synthesize", "text": actual.get("native_text", text), "voice": str(voice),
                           "output_dir": temporary, "seed": actual["seed"], "pause": 0, "split_sentences": False,
                           "temperature": config.get("temperature", 0.7),
                           "top_p": config.get("top_p", 0.9), "top_k": config.get("top_k", 50),
                           "minimum_seconds": actual["minimum_audio_seconds"]}
                try:
                    self.process.stdin.write(json.dumps(request, ensure_ascii=False) + "\n")
                    self.process.stdin.flush()
                    deadline = time.monotonic() + self.settings.audio8_timeout
                    while self._receive(deadline).get("stage") != "completed":
                        pass
                    report = json.loads((directory / "report.json").read_text())
                    if report.get("state") != "completed":
                        raise RuntimeError("Audio8 未完成当前分段")
                    if report["audio_seconds"] < request["minimum_seconds"]:
                        raise RuntimeError("Complete audio is below requested minimum duration")
                    import numpy as np
                    output = subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-i", str(directory / "audio8.wav"),
                                             "-ac", "1", "-ar", "24000", "-f", "f32le", "-"],
                                            capture_output=True, check=True, timeout=30)
                    return np.frombuffer(output.stdout, dtype="<f4").copy()
                finally:
                    self._record_report(actual, directory)
        except Exception as exc:
            short = "Complete audio is below requested minimum duration" in str(exc)
            # A completed native request rejected only for duration has already
            # cleaned up its state. Keep the model and reference cache resident.
            reusable = short and actual.get("state") == "failed" and self.process is not None and self.process.poll() is None
            if short:
                duration, minimum = actual.get("audio_seconds"), actual["minimum_audio_seconds"]
                detail = f"实际 {duration:.2f} 秒，最低预计 {minimum:.2f} 秒" if duration is not None else f"最低预计 {minimum:.2f} 秒"
                exc = IncompleteSpeechError(f"合成疑似提前结束（{detail}），交由队列重试或拆分")
            actual.update(state="failed", error=str(exc), process_reused=reusable)
            if reusable:
                self.status.update(model="ready", last_generation_error=str(exc))
            else:
                self.close()
                self.status.update(model="failed", error=str(exc))
            raise exc

    @staticmethod
    def _aggregate_execution(execution):
        parts = execution["parts"]
        execution.update(source_sample_rate=44100, stored_sample_rate=24000,
                         reference_cache=parts[-1].get("reference_cache", {}),
                         runtime=parts[-1].get("runtime", execution["runtime"]),
                         fragments=[fragment for part in parts for fragment in part.get("fragments", [])])
        for key in ("generation_seconds", "decode_seconds", "audio_seconds"):
            known = [part[key] for part in parts if key in part]
            if known:
                execution[key] = sum(known)
        execution["output_audio_seconds"] = sum(part.get("audio_seconds", 0) for part in parts if part.get("state") == "completed")
        execution["part_retries"] = sum(part["attempt"] > 1 for part in parts)

    def _record_report(self, actual, directory):
        try:
            report = json.loads((directory / "report.json").read_text())
        except (OSError, ValueError):
            return
        fragments = report.get("fragments", [])
        actual.update(
            source_sample_rate=44100, stored_sample_rate=24000,
            state=report.get("state"), runtime=self.engine_info,
            reference_cache=report.get("reference_cache", {}),
            fragments=[{key: fragment[key] for key in
                        ("prefill_seconds", "autoregressive_seconds", "frames", "audio_seconds") if key in fragment}
                       for fragment in fragments],
        )
        for target, source in (("generation_seconds", "generation_seconds"),
                               ("decode_seconds", "decode_and_save_seconds"),
                               ("audio_seconds", "audio_seconds")):
            if source in report:
                actual[target] = report[source]
            elif fragments and all(source in fragment for fragment in fragments):
                actual[target] = sum(fragment[source] for fragment in fragments)
