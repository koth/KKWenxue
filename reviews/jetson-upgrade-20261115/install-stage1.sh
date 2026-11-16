#!/usr/bin/env bash
set -euo pipefail
umask 077
upgrade_dir=/home/jetson/workspace/jetpack-upgrade-20261115
test "$(id -u)" -eq 0
grep -q 'R35.*REVISION: 3.1' /etc/nv_tegra_release
test -s "$upgrade_dir/system-config-before.tar.gz"
test -s "$upgrade_dir/partitions-before.sfdisk"
test "$(findmnt -no SOURCE /)" = /dev/nvme0n1p1
test "$(findmnt -no SOURCE /home/jetson/workspace)" = /dev/nvme0n1p15
test "$(df -B1 --output=avail / | tail -1)" -gt 5368709120
mapfile -t l4t_packages < <(dpkg-query -W -f='${db:Status-Abbrev} ${binary:Package}\n' 'nvidia-l4t-*' | awk '$1 == "ii" {print $2}')
test "${#l4t_packages[@]}" -gt 0
export DEBIAN_FRONTEND=noninteractive
apt-get \
  -o Dir::Etc::sourcelist="$upgrade_dir/apt-stage1.sources.list" \
  -o Dir::Etc::sourceparts=- \
  -o Dir::State::lists="$upgrade_dir/apt-lists" \
  -o Dir::Cache="$upgrade_dir/apt-cache" \
  -o Dpkg::Options::=--force-confnew \
  --no-download --no-remove --no-install-recommends -y \
  install "${l4t_packages[@]}"
dpkg --audit
date -u +%FT%TZ > "$upgrade_dir/stage1-installed"
printf 'STAGE1_INSTALL_COMPLETE: reboot and validate before continuing to JetPack 6.\n'
