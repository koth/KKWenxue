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
from .config import ENGINE, REVISION

log = logging.getLogger(__name__)


def minimum_audio_seconds(text):
    # At native 1x, a long Mandarin passage exceeding 8 syllables/second is
    # suspicious. This is a lower-bound check, not transcript verification.
    # Ignore Latin words/numbers rather than treating each letter as a syllable.
    syllables = len(re.findall(r"[\u3400-\u9fff]", text))
    return syllables / 8 if syllables >= 32 else 0


class Audio8Engine:
    def __init__(self, settings, status):
        self.settings, self.status = settings, status
        self.process = None
        self.events = None
        self.stderr = deque(maxlen=40)
        self.last_execution = {}
        self.engine_info = {}
        self.voices = OrderedDict()

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
            seed = config["seed"] + row["chapter_index"] * 100000 + row["segment_index"]
            try:
                self._start()
                with tempfile.TemporaryDirectory(dir=temporary_root) as temporary:
                    request = {"op": "synthesize", "text": row["spoken_text"], "voice": str(voice),
                               "output_dir": temporary, "seed": seed, "pause": 0, "split_sentences": False,
                               "temperature": config.get("temperature", 0.7),
                               "top_p": config.get("top_p", 0.9), "top_k": config.get("top_k", 50)}
                    request["minimum_seconds"] = minimum_audio_seconds(row["spoken_text"])
                    self.process.stdin.write(json.dumps(request, ensure_ascii=False) + "\n")
                    self.process.stdin.flush()
                    deadline = time.monotonic() + self.settings.audio8_timeout
                    while self._receive(deadline).get("stage") != "completed":
                        pass
                    directory = Path(temporary)
                    report = json.loads((directory / "report.json").read_text())
                    if report.get("state") != "completed":
                        raise RuntimeError("Audio8 未完成当前分段")
                    if report["audio_seconds"] < request["minimum_seconds"]:
                        raise RuntimeError("Audio8 音频过短，疑似提前结束，交由队列重试或拆分")
                    import numpy as np
                    output = subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-i", str(directory / "audio8.wav"),
                                             "-ac", "1", "-ar", "24000", "-f", "f32le", "-"],
                                            capture_output=True, check=True, timeout=30)
                    samples = np.frombuffer(output.stdout, dtype="<f4").copy()
                    self.last_execution[row["id"]] = {
                        "seed": seed, "batch_size": 1, "native": True, "engine": ENGINE,
                        "source_sample_rate": 44100, "stored_sample_rate": 24000,
                        "generation_seconds": report["generation_seconds"],
                        "decode_seconds": report["decode_and_save_seconds"],
                        "audio_seconds": report["audio_seconds"], "runtime": self.engine_info,
                        "reference_cache": report.get("reference_cache", {}),
                        "fragments": [{key: fragment[key] for key in
                                       ("prefill_seconds", "autoregressive_seconds", "frames") if key in fragment}
                                      for fragment in report.get("fragments", [])],
                    }
                    results.append(samples)
            except Exception as exc:
                self.close()
                self.status.update(model="failed", error=str(exc))
                results.append(exc)
        return results
