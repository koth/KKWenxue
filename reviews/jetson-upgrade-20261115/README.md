# Jetson 升级验收（2026-11-15）

已通过 USB Recovery 重刷 JetPack 6.2 / L4T 36.4.3，并清空旧系统数据。尚未迁移正式 CosyVoice 服务。

## 实测结果

| 检查 | 结果 |
| --- | --- |
| 刷写 | NVMe、QSPI 均成功，设备自动重启 |
| 启动固件 | 36.4.3，capsule 状态 0，A/B 槽均 normal |
| 内核 / Python | 5.15.148-tegra / 3.10.12 |
| 无显示器登录 | 公钥 SSH 经 USB 成功，sudo 成功 |
| 默认启动 | multi-user.target；SSH、NetworkManager 已启用；失败单元 0 |
| 根分区 | 233 GiB，总可用约 214 GiB |
| GPU | CUDA 驱动 12060，Orin，实际 PTX 内核读回 42，PASS |
| 有线 | 用户断开 USB、换线并重启后，192.168.3.33 公钥 SSH 登录通过 |
| 外网 | NVIDIA 软件源 HTTPS 返回 200 |
| Wi-Fi | Intel 8265 被 PCIe 识别，当前内核缺 iwlwifi，尚未恢复 |

## 构建和日志

构建主机：`emotts`（`koth@192.168.3.32`），通过 SSH ControlMaster 复用连接。

- 正确 BSP：`/mnt/jetson-flash-20261115/r36.4.3/Linux_for_Tegra`。
- ext4 后端文件：`/h/work/jetson-flash-20261115/linux-build.ext4`。
- 生成日志：`/h/work/jetson-flash-20261115/ext4-generate.log`。
- 新系统镜像 SSH 预检：`/h/work/jetson-flash-20261115/headless-ssh-preflight.log`。
- 最终刷写 initrd 的实际 SSH 登录预检：`/h/work/jetson-flash-20261115/final-initrd-ssh-preflight.log`。
- 刷写日志：`/h/work/jetson-flash-20261115/ext4-flash.log`。
- NVIDIA 实例日志：BSP 下 `initrdlog/flash_1-7_0_20261115-201713.log`。
- GPU 测试程序：Jetson 上 `/home/jetson/workspace/gpu-smoke.py`。
- 本地保存的刷写成功日志：[flash-success.log](flash-success.log)。
- 本地保存的有线无显示器验收：[headless-lan-check.txt](headless-lan-check.txt)。

旧 NTFS 构建丢失 Linux 权限，不能复用其产物。新的 ext4 构建已经在写入前实际验证两套 SSH 登录，刷写时日志也确认 `SSH ready`。

## 无显示器访问

新系统已预置当前 Mac 的 SSH 公钥。USB 连接 x86 服务器时，可从 Mac 经跳板访问：

```sh
ssh -J emotts jetson@192.168.55.1
```

有线连接名 `shanye-wired`，接口 `enP8p1s0`，地址 `192.168.3.33/24`，网关 `192.168.3.1`。已使用 `ssh jetson@192.168.3.33` 实际登录。系统记录本次启动时间 `2026-11-15 12:27:55 UTC`；用户确认这是换线时主动重启的结果，因此未额外重启。当前 Mac 的 192.168.3.33 主机密钥条目已根据验证过的设备会话更新。

有线登录和重启后的自动联网已通过。后续已完成 ARM64 环境、CosyVoice 全模型 GPU 合成及全部后端服务迁移，旧机已关机，详见 [后端迁移验收](../jetson-server-migration-20261115/README.md)。
