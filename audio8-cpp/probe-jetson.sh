#!/usr/bin/env bash
# Read-only device inventory; no power modes or clocks are changed.
set -euo pipefail
printf 'Architecture: '; uname -m
if [[ -r /proc/device-tree/model ]]; then printf 'Module: '; tr -d '\000' </proc/device-tree/model; printf '\n'; fi
if [[ -r /etc/nv_tegra_release ]]; then cat /etc/nv_tegra_release; fi
if command -v nvcc >/dev/null; then nvcc --version; elif [[ -x /usr/local/cuda/bin/nvcc ]]; then /usr/local/cuda/bin/nvcc --version; fi
free -m
if command -v nvpmodel >/dev/null; then nvpmodel -q || true; fi
if command -v tegrastats >/dev/null; then timeout 3s tegrastats --interval 1000 || true; fi
