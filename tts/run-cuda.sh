#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="$TASK_ROOT/bin:$PATH"
export HF_HOME="$TASK_ROOT/.cache/huggingface"
export XDG_CACHE_HOME="$TASK_ROOT/.cache"
export TMPDIR="$TASK_ROOT/.tmp"
export HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1
export TOKENIZERS_PARALLELISM=false PYTHONUNBUFFERED=1
export PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True
mkdir -p "$TASK_ROOT/logs" "$TASK_ROOT/output" "$TMPDIR"
printf '%s\n' "$$" > "$TASK_ROOT/logs/synthesis.pid"
exec "$TASK_ROOT/.cuda-venv/bin/python" -u "$TASK_ROOT/tts/synthesize_cuda.py" run \
  --source "$TASK_ROOT/data/shanye.db" --output "$TASK_ROOT/output" \
  --model-path "$TASK_ROOT/models/Qwen3-TTS-12Hz-1.7B-CustomVoice" \
  --first "${1:-912}" --last "${2:-1281}" --batch-size "${3:-8}" \
  --speed 1.0 --speaker Dylan --instruct-file "$TASK_ROOT/tts/styles/bright-neutral-zh.txt"
