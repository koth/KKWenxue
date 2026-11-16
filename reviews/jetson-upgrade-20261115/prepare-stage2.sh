#!/usr/bin/env bash
# Builds an offline OTA payload. Does not apply it or reboot the device.
set -euo pipefail
umask 077
upgrade_dir=/home/jetson/workspace/jetpack-upgrade-20261115
target_bsp="$upgrade_dir/r36.4.3/Linux_for_Tegra"
export BASE_BSP="$upgrade_dir/r35.5.0/Linux_for_Tegra"
test "$(id -u)" -eq 0
grep -q 'R35.*REVISION: 5.0' /etc/nv_tegra_release
test -f "$upgrade_dir/ota-sources-extracted"
test -f "$upgrade_dir/home-backup-verified"
cd "$target_bsp"
./apply_binaries.sh
tar -xjf "$upgrade_dir/downloads/ota_tools_r36.4.3_aarch64.tbz2" -C "$upgrade_dir/r36.4.3"
for binary in tegrarcm_v2 tegraparser_v2; do
    cp -a "bootloader/$binary" "bootloader/$binary.original-x86"
    tar -xOf "$upgrade_dir/downloads/aarch64_tegraflash_R36.4.3_aarch64.tbz2" \
        "Linux_for_Tegra/bootloader/$binary" > "bootloader/$binary"
    chmod 755 "bootloader/$binary"
done
temporary_password=$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')
./tools/l4t_create_default_user.sh -u jetson -n "$(hostname)" \
    -p "$temporary_password" --accept-license > "$upgrade_dir/create-offline-user.log" 2>&1
unset temporary_password
python3 "$upgrade_dir/customize-stage2.py"
./tools/ota_tools/version_upgrade/l4t_generate_ota_package.sh \
    --external-device nvme0n1 -S 24GiB jetson-orin-nano-devkit R35-5
date -u +%FT%TZ > "$upgrade_dir/stage2-payload-built"
