# Jetson 后端迁移验收

2026-11-15。生产后端已从 `koth@192.168.3.32` 完整切换到
`jetson@192.168.3.33:/home/jetson/workspace`。旧机六个业务服务已停止、禁用自启，
随后通过 sudo 执行关机；SSH 已离线。旧机关闭后，新后端和两种下载路径再次通过验证。
公网 Rust 任务中转、目录 API 和目录更新定时器继续在 `120.48.49.190` 运行。

## 运行环境

- Orin Nano 8GB，JetPack 6.2 / L4T 36.4.3，CUDA 12.6、cuDNN 9.3，保持 15W 档。
- API / CPU / relay / 正文 worker：独立 Python 3.11.18 venv。
- GPU：独立 Python 3.10.12 venv，ARM64 Torch / torchaudio 2.8.0，ONNX Runtime GPU 1.23.0。
- 两个 Python 环境 `uv pip check` 均通过。GPU 完整锁文件含 wheel SHA-256：
  [requirements-jetson-gpu-lock.txt](../../server/deploy/requirements-jetson-gpu-lock.txt)。
- CUDA 系统包：`cuda-libraries-12-6`、`cuda-cupti-12-6`、`libcudnn9-cuda-12`；另有 FFmpeg、libsndfile、OpenBLAS。
- 16GiB NVMe swap：`/var/swap/shanye-tts.swap`，优先级 0、fstab 持久化；原 zram 优先级 5。
- SSH、公钥、有线静态地址、用户 linger 和六个服务的自启已启用。
  沿用用户换线后已验证的启动网络，没有为本次服务切换额外重启设备。
- journald 改为持久化，磁盘日志上限 100MiB，运行时上限 50MiB。

## 已迁移内容

| 内容 | Jetson 位置 |
| --- | --- |
| TTS API、GPU、CPU 导出、中转 worker | `~/workspace/shanye-tts-service/app` |
| API venv / GPU venv | `~/workspace/shanye-tts-service/.api-venv` / `.gpu-venv` |
| 私有服务配置 | `~/workspace/shanye-tts-service/service.env` |
| 服务数据库、原 API 凭据、结果凭据 | `~/.local/share/shanye-tts` |
| 既有音频、片段缓存、参考录音 | `~/workspace/shanye-tts-service/audio` |
| CosyVoice 代码、完整 0.5B 权重 | `~/workspace/CosyVoice` |
| 两个正文 worker、缓存及数据库 | `~/workspace/shanye-content` |

保留源库的 70 条历史任务（63 条成功）、36 份已就绪音频产物、5 份参考录音、
音色版本 ID、worker ID，以及 200 本正文缓存。数据在旧 worker 停止后做最终一致性快照，
所有 SQLite integrity_check 通过；36 份音频/manifest 和全部参考录音逐一校验哈希通过。
迁移模型时解引用了指向源机器缓存目录的 tokenizer 符号链接。

音色默认仍为 stf。两个正文 worker 各自使用 `content.env` / `content-2.env`，
共享原正文库，保留原来的 worker ID。API 凭据已与源机器逐字节比对，未写入本文或验收日志。
服务元数据和参考录音没有重新初始化。

## 8GB 内存适配

上游 `AutoModel(fp16=True)` 仍会把完整检查点加载到 CUDA，且参考音频 tokenizer 的
CUDA 临时分配较大。直接照搬以及仅优化检查点的两次测试均触发系统 OOM，不能作为可用部署。

正式配置设置 `SHANYE_COSYVOICE_LOW_MEMORY=1`，使用
[cosy_runtime.py](../../server/cosy_runtime.py)：

1. 检查点通过 CPU mmap 逐个加载，assign 避免复制已初始化的参数。
2. LLM 和 flow 权重在转入 GPU 前使用 FP16，vocoder 保留 FP32。
3. 参考音色 tokenizer 在 CPU 上按需创建，提取后释放；缓存的音色特征供后续段落复用。
4. 正文仍在 GPU 合成。没有修改外部 CosyVoice 源文件，没有启用 Qwen TTS 链路。

正式 worker 验收期间无重启，切换后无内核 OOM。最终长段落验收使用了上述全部适配，
其中参考编码器释放逻辑也实际执行。早期单段报告在加入懒加载释放前取得，不能当作最终常驻内存测量。

## 实际合成与交付

先用默认 stf 合成两段原创短句，GPU 生成通过。热段 27 字生成 4.84 秒音频耗时 17.69 秒，
冷段包含加载和参考特征提取，耗时更长。见 [原始报告](inference-report.json)。

随后通过真实公网任务总线派发两章原创验收文本，正式 GPU worker 合成、CPU worker
导出，自动打包并发布完成通知；未主动请求中转前，TTS 结果 `relay_state=none`。

- TTS task：`3e97b6e6-b96b-49d3-b3ba-90720eae8829`。
- 3 个朗读片段均首次成功；单独的 `>` 标为 skipped，未尝试合成。
- 127 字连续长句生成 27.12 秒音频，耗时 77.22 秒，RTF 约 2.85。
- 整个任务约 155.65 秒，含冷启动及导出；两章成品共 33.38 秒。
- ZIP 为 280,360 字节，SHA-256 `121da6d970aa4455127e447156623e6720eade72e5f6ebbf2e4d4232c8219726`。
- Mac 从 `192.168.3.33:17871` 实际下载，验证两章 catalogue、M4A / manifest 哈希、complete 标志、UTF-16 原文范围和 cue 时间边界。
- 客户端主动请求中转后，Jetson 自动上传；Mac 从公网下载，字节与局域网下载完全一致。

同时派发一条已缓存正文的 `content.serve`：

- Task：`5bfb46bc-e01e-473e-bb00-128f4468eb4b`。
- 下载 500 章正文 ZIP，827,272 字节，book.txt 哈希通过。
- 局域网与按需公网中转下载均通过，内容逐字节一致。

源码级验证通过 10 项 GPU 加载边界、失败恢复、CPU 参考编码器释放和本地代理测试。
复用实际 GPU 环境进行验收，没有使用 fake engine 代替 GPU 推理。
以上是短任务实测，并未在 Jetson 上合成整本小说，不能据此承诺全书耗时。

随后已上线逐 token CUDA Graph 加速，热机基准约快 1.6 倍，见
[速度优化与后续正式任务验证](../jetson-tts-speed-20261115/README.md)。上面的耗时保留为迁移当天原始基线。

报告：[局域网下载](lan-download-report.json)、[TTS 中转](tts-relay-report.json)、
[正文中转](content-relay-report.json)、[正式片段耗时](production-timings.json)。

## 切换与运维

六个 user units 均为 enabled / active：

```sh
systemctl --user status shanye-tts-api shanye-tts-cpu shanye-tts-gpu \
  shanye-tts-relay-worker shanye-content shanye-content-2
journalctl --user -u shanye-tts-gpu -f
```

API 仅在 loopback `17860` 监听；TTS 结果在 LAN `17871`，两个正文通道在 `17873/17874`。
本地 `http://127.0.0.1:17861` 启动器已重启并连接 Jetson，本地书库仍为《噩夢使徒》1,281 章。

App 已更新到 0.9.3（versionCode 23），补齐 Jetson 当前 8 个有效参考音色 ID，包括 xspb.mp3 和 cwssr.mp3；构建包见 `dist/shanye-0.9.3-debug.apk`。
工作台默认主机和远端状态目录也已更新，浏览器刷新可取得新本地会话。

公网数据库中 216 条既有结果的直连 URL 已从 `.32` 改为 `.33`，其他结果字段、凭据、
worker ID 保持原值。修改前一致性备份：公网 `/var/lib/shanye-task-relay/relay-before-jetson-20261115.sqlite`。
App 继续使用原公网中转地址，无需更换接入凭据或重装。

安装期间使用过的源机器临时代理转发已撤销，SSH 迁移 key 已从目标 authorized_keys 移除，
源端临时私钥已删除。关机后的下载与 API 验证证明新部署不依赖该代理或旧服务。
旧机器原始数据保留，可重新开机用于人工回退；切换后的新任务和产物应先备份，
不能直接把旧快照覆盖回新库。Jetson 最终源快照在 `~/workspace/migration-20261115/final-snapshot`。
