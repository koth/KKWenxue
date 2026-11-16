#!/usr/bin/env bash
set -euo pipefail
task_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ "$(uname -m)" != aarch64 ]]; then
  printf '%s\n' 'This helper is for native ARM64 Jetson builds. Use CMake directly for other hosts.' >&2
  exit 1
fi
: "${LIBTORCH_ROOT:?Set LIBTORCH_ROOT to the matching JetPack 6.2 ARM64 LibTorch/PyTorch native-library directory}"
cmake -S "$task_dir" -B "$task_dir/build" -DCMAKE_BUILD_TYPE=Release -DCMAKE_PREFIX_PATH="$LIBTORCH_ROOT"
# Limit compiler memory usage on an 8 GB shared-memory device.
cmake --build "$task_dir/build" --parallel "${AUDIO8_BUILD_JOBS:-1}"
