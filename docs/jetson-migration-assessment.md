# Jetson 合成服务迁移评估

检查日期：2026-11-15。目标：`jetson@192.168.3.33:/home/jetson/workspace`。

## 结论

已完成整个家庭后端的迁移：TTS API、GPU 合成、CPU 导出、任务中转 worker、两个正文 worker、模型、stf 音色和数据库均在 Jetson 上运行并启用自启。旧 `192.168.3.32` 的业务服务已停用，随后执行关机，SSH 已离线。公网 Rust 中转和目录服务继续运行。

已通过真实 GPU 合成、两章 ZIP/原文时间轴校验、Mac 局域网下载和按需公网中转下载。完整证据、路径和内存适配见 [迁移验收](../reviews/jetson-server-migration-20261115/README.md)。本地工作台默认及当前连接均改为 `jetson@192.168.3.33`。设备仍保持 15W 档。

下面保留升级前的评估背景；其“待验证”描述已由上述验收取代。

## 迁移前的目标机器实测

| 项目 | 结果 |
| --- | --- |
| 硬件 | NVIDIA Orin Nano Developer Kit，ARM64 |
| 内存 | 系统报告 7.2 GiB，检查时约 5.9 GiB 可用；CPU/GPU 共享 |
| 交换空间 | 约 3.6 GiB，全部为 zram，仍消耗物理内存 |
| 目标磁盘 | NVMe 分区，约 53 GiB 可用；目录由 jetson 所有 |
| 系统 | Ubuntu 20.04.5，L4T 35.3.1，kernel 5.10.104-tegra |
| CUDA / cuDNN | 活跃 CUDA 11.4，cuDNN 8.6；另有 CUDA 11.7 目录 |
| Python | 默认 3.8.10；另有 3.9，未发现 3.10 |
| PyTorch | 1.12.0a0+2c916ef.nv22.3，编译 CUDA 11.4 |
| GPU 检查 | CUDA 可用，设备 Orin，计算能力 8.7；FP16 256×256 矩阵运算通过 |
| 推理依赖 | 未发现 torchaudio、onnxruntime、FastAPI |
| 运行工具 | Docker、FFmpeg、rsync、Git 可用 |
| 常驻服务 | 用户 systemd 运行，Linger=yes |
| 当前功耗档 | 15W，未调整 |

SSH 检查使用 ControlMaster/ControlPersist 复用连接。旧系统数据已按授权清空，新服务仍计划使用独立目录。

## 与当前服务的差异

当前 `192.168.3.32` 使用 RTX 3090、x86_64、Python 3.10、PyTorch 2.3.1+cu121 和 ONNX Runtime GPU 1.18。现有 Python 环境中的二进制不能复制到 ARM64 使用。

现有服务通过官方 `AutoModel(fp16=True)` 加载 CosyVoice3，逐段执行 `inference_instruct2`，并使用参考录音 stf。保留相同权重、参考音频、生成配置和文本时间轴协议即可延续 App 导入与原文同步，不需要改变按章节导出的方式。

### 软件栈

迁移前系统对应 JetPack 5.1.1；现已升级至 JetPack 6.2。后续需选择匹配的 NVIDIA ARM64 PyTorch、torchaudio 和 ONNX Runtime。JetPack 5.x 升至 6.x 涉及启动固件兼容要求，本次通过 USB Recovery 完成，而非复制旧 Python 环境。参考 [NVIDIA JetPack 6.2](https://developer.nvidia.com/embedded/jetpack-sdk-62) 和 [Jetson PyTorch 兼容表](https://docs.nvidia.com/deeplearning/frameworks/install-pytorch-jetson-platform-release-notes/pytorch-jetson-rel.html)。

不能按桌面 CUDA 的 pip 锁文件直接安装，也不能把驱动级 CUDA 测试通过当成完整 CosyVoice 已能运行。

### 内存与速度

当前 3090 服务曾观察到约 4.7 GiB 常驻 GPU 显存、约 6.8 GiB 合成期间显存占用。这些是源机器上的观察值，不是 Jetson 的内存测量，也不是模型的理论最低需求。

Jetson 的系统、Python、模型加载和推理共用 8GB 内存。GPU 小运算通过不代表完整模型能够加载；加载权重的临时副本也可能造成峰值超限。实测发现上游 `fp16=True` 使用 autocast，但检查点加载及常驻权重并不因此全部变为 FP16。直接迁移触发 OOM 后，已加入 CPU mmap 逐个加载、LLM/flow 半精度权重和 CPU 参考编码器按需释放；vocoder 保留 FP32，正文仍在 GPU 合成。不能只凭 FP16 开关判断内存需求。

源机器第 984 章的 83 段原始音频合计 887.72 秒，GPU 单元耗时合计 456.11 秒（含首次加载），只代表 RTX 3090。Jetson 最新验收中，127 字片段生成 27.12 秒音频耗时 77.22 秒，RTF 约 2.85；短样本不代表全书速度。源结果见 [正式合成验收](../reviews/cosyvoice-production-20261115/README.md)。

### 存储

源机器当前模型目录约 5.5 GiB，另有约 925 MiB 的 tokenizer 文件由目录外符号链接引用，音频约 652 MiB，状态约 7.2 MiB。重刷后目标根分区约 233 GiB，可用约 214 GiB。迁移模型时必须处理外部符号链接，不能只同步模型目录后认为文件已齐全。

## 建议迁移步骤

1. 在 `/home/jetson/workspace/shanye-tts-service` 下建立独立的代码、环境、模型、状态和音频目录；重建 ARM64 环境。
2. 先离线测试模型加载、同一 stf 参考的短句和第 984 章。记录加载/推理内存、耗时、失败重试、温度及是否使用磁盘交换；检查音色、完整音频和 UTF-16 cue。
3. 接入 API、GPU worker 和 CPU 导出器，验证暂停恢复、断开 SSH 后常驻、ZIP 校验和 App 导入。
4. 验证通过后再安排正式切换：等待源机器工作单元落盘、停止领取外网任务，使用 SQLite backup 接口备份服务库，并同步参考音频和已有产物。
5. 保留默认 stf 的预设 ID、音频哈希和配置。迁移 `relay-results.sqlite` 等结果记录并核对旧下载地址的处理，避免已完成任务无法再按需中转。
6. 将局域网发布地址改为 `http://192.168.3.33:17871`，调整 systemd 路径及本地启动器的 SSH 主机和远端状态目录。核对后启用新中转适配器，避免两台机器以同一 worker ID 同时领取任务。
7. 验证 App 派发、局域网下载、按需公网中转和旧任务查询，再结束源服务。公网 Rust 中转服务仍位于原外网机器，无需随 GPU 主机一起搬迁。

若整章测试出现无法消除的内存不足，可让 Jetson 承担 API/调度而将 GPU 合成留在原机器；这只是备选部署方式，不等于完成 GPU 服务迁移。

## 2026-11-15 系统升级与无显示器验收

原地升级先到达 L4T 35.5.0，但固件 capsule 未成功更新启动固件。随后用户将 Jetson 与 `192.168.3.32` 的 x86 Ubuntu 主机通过 USB 连接，并授权清空旧数据，改用 NVIDIA 官方 Recovery 刷写流程。

初次在 NTFS 工作目录构建丢失了 Linux 文件权限，临时系统的 SSH 私钥也受到影响。最终使用 ext4 循环镜像重新解包、安装 BSP 和构建，刷写前分别实际登录验证新系统镜像及最终 initrd 的 SSH。

有效构建目录在主机 `192.168.3.32` 的 `/mnt/jetson-flash-20261115/r36.4.3/Linux_for_Tegra`，ext4 后端文件为 `/h/work/jetson-flash-20261115/linux-build.ext4`。不要使用旧的 NTFS 目录中生成的镜像。官方流程见 [Flashing Support](https://docs.nvidia.com/jetson/archives/r36.4/DeveloperGuide/SD/FlashingSupport.html)。

已验证：

- NVMe 系统盘和 QSPI 均刷写成功，设备自动重启。
- Ubuntu 22.04；L4T 36.4.3；内核 `5.15.148-tegra`；Python 3.10.12。
- `nvbootctrl` 报告启动固件 36.4.3，capsule 状态 0，A/B 槽均 normal。
- 无显示器、公钥 SSH 登录 `jetson` 成功，sudo 可用，工作目录 `/home/jetson/workspace` 可写。
- 默认 `multi-user.target`，SSH 和 NetworkManager 已启用；无失败的 systemd 单元。
- 根分区约 233 GiB，可用约 214 GiB。
- CUDA 驱动报告 12.6、设备 Orin；实际启动 PTX 内核、同步并读回结果 42，计算通过。

有线接口 `enP8p1s0` 已配置持久化连接 `shanye-wired`：`192.168.3.33/24`、网关 `192.168.3.1`、DNS `192.168.3.1` 和 `223.5.5.5`，插线自动连接。用户换线并重启后，已通过 `ssh jetson@192.168.3.33` 实际登录，系统记录本次启动时间为 `2026-11-15 12:27:55 UTC`。已从经过验证的 SSH 会话读取新主机公钥，更新当前 Mac 上仅针对 `192.168.3.33` 的 known_hosts 条目。USB 登录也已验证，可在服务器上经 `192.168.55.1` 访问；Mac 可经 `ssh -J emotts jetson@192.168.55.1` 转接。

Intel 8265 由 PCIe 正常识别，但官方当前内核未开启 `CONFIG_IWLWIFI`，因此尚未恢复 Wi-Fi。预置的 `shanye-lan` 无线配置已暂停自动连接，避免将来无线与有线同时使用同一静态地址。

验收与日志索引见 [升级记录](../reviews/jetson-upgrade-20261115/README.md)。驱动级 GPU 验证不包含 PyTorch、完整 CUDA Toolkit 或 CosyVoice 正式合成测试。
