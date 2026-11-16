"""Real stf inference on the migrated runtime, without editing the job queue."""
from contextlib import closing
import json
import time
from pathlib import Path
import resource
from server.config import Settings
from server.db import connect
from server.presets import snapshot
from server.voices import DEFAULT_PRESET_ID
from server.worker import CudaEngine
import soundfile as sf
import torch
import onnxruntime as ort

settings = Settings(batch_size=1)
with closing(connect(settings)) as db:
    config = snapshot(db, settings, DEFAULT_PRESET_ID, None)['generation']
state = {}
engine = CudaEngine(settings, state)
texts = ['夜色渐深，街道安静下来。他推开门，看见桌上放着一封信。', '窗外传来一阵脚步声。他停下手里的动作，仔细听了一会儿。']
results = []
for i, text in enumerate(texts):
    start = time.monotonic()
    value = engine.generate([{'id': f'jetson-smoke-{i}', 'chapter_index': 0, 'segment_index': i, 'spoken_text': text}], config)[0]
    if isinstance(value, BaseException):
        raise value
    duration = len(value) / 24000
    elapsed = time.monotonic() - start
    path = Path('/home/jetson/workspace/migration-20261115') / f'stf-smoke-{i}.wav'
    sf.write(path, value, 24000)
    results.append({'characters': len(text), 'audio_seconds': duration, 'wall_seconds': elapsed, 'rtf': elapsed / duration, 'path': str(path)})
    print(json.dumps({'segment': i, **results[-1]}, ensure_ascii=False), flush=True)
report = {'torch': torch.__version__, 'cuda': torch.version.cuda, 'gpu': torch.cuda.get_device_name(), 'onnxruntime': ort.__version__, 'providers': ort.get_available_providers(), 'status': state, 'peak_rss_kib': resource.getrusage(resource.RUSAGE_SELF).ru_maxrss, 'cuda_peak_allocated': torch.cuda.max_memory_allocated(), 'cuda_peak_reserved': torch.cuda.max_memory_reserved(), 'samples': results}
Path('/home/jetson/workspace/migration-20261115/inference-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps(report, ensure_ascii=False), flush=True)
