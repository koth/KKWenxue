# 山页语音工作台

本地 React 页面与 Python 启动器只读加载本地阅读器 SQLite；提交选中章节后，经复用的 SSH 隧道发送到 FastAPI，GPU worker 合成，CPU 导出器生成音频。

正式合成使用 Audio8 Realistic Air 0.6B 的原生 C++ / LibTorch CUDA 引擎，默认参考音色为 `cwns.mp3`，跟随参考录音的音色与节奏。成品默认 1.0 倍速、180ms 句间停顿。Web 与 App 均可选择已保存的参考音色。修改成品速度或停顿复用同一配置下的有效 FLAC，播放器倍速只影响播放。

新任务默认开启“统一成品音量”（`export.normalize_loudness=true`）：拼接与倍速处理后，CPU 使用 FFmpeg 两遍响度处理，目标为单声道 -18 LUFS、PCM 真峰值 -2 dBTP，并检查 AAC 成品不超过 -1 dBTP。manifest 记录处理版本、输入/输出实测值；不足 400ms 的极短内容使用有界峰值增益。关闭可导出原音量。该设置只改变导出缓存键，复用原始 FLAC，不修改参考音频、克隆提示或文本时间索引。旧任务与旧成品保持原配置，需新建复用任务导出均衡版本。

模型为 `Edge0/Audio8-TTS-Realistic-Air`，版本固定为 `6bf7a980759d8022a66922ee975dab99229a2dd9`，使用 BF16 与 CUDA Graph。任务与缓存身份包含新引擎和参考文本，旧音频仍可播放，旧引擎任务需要重新派发。不会自动回退到 CosyVoice 或 Qwen。

正文沿用阅读器的简体规范化与分段。常驻 C++ 进程按 JSONL 接收任务，生成 44.1kHz WAV，适配层转为既有 24kHz FLAC；章节导出、原文与 UTF-16 cue 协议保持一致。参考音频需要匹配的逐字稿；首次使用时由短期 Python codec 进程编码，结果按模型版本、音频哈希与逐字稿缓存。正文合成不加载 Python AR 模型。Audio8 不使用原来的语气指令。

常驻进程还复用 GPU 参考码、参考文稿分词与参考前缀 KV；切换音色或参考内容变化时重建。当前推理版本为 `expanded-kv-v3`：压缩语音输出词表，并将慢模型 KV 预存为 math SDPA 实际使用的 FP32 展开格式，减少每帧的整块复制和转换。同文热机基准约由 2.15 降至 1.66 秒生成 1 秒音频，本轮测试编码与 PCM 一致，详见 [测量与验收](../reviews/audio8-kv-speed-20261116/README.md)。这与 App 播放倍速独立。较长中文结果明显过短时进入原有重试/拆分流程，不作为完整分段发布；时长检查不能代替内容验证。

## 本地启动

项目根目录运行：

```sh
cd web
npm ci
npm run build
cd ..
python3 -m local.launcher --open
```

页面是 `http://127.0.0.1:17861`，SSH 转发使用本地 `17860`。远程请求共享一个转发，底层 OpenSSH 共享控制连接。默认连接 `jetson@192.168.3.33`；密钥由 OpenSSH 处理，浏览器只拿短期本地会话凭据。启动器关闭不取消服务器任务。

端口冲突时显式设置 `--port`、`--tunnel-port`；远端端口可用 `--remote-port` 调整。已有隧道仅在持有服务凭据且健康校验匹配时复用，不终止无关进程。服务凭据通过 SSH 从远端 `--remote-state` 读取，不写进 URL。

纯本机开发可运行 `SHANYE_STATE=... SHANYE_AUDIO=... server/.venv/bin/python -m uvicorn server.app:app_factory --factory --host 127.0.0.1 --port 17860`，然后使用 `--direct --token-file /path/to/state/api-token` 启动代理。

## 服务器部署

当前后端为 `jetson@192.168.3.33`（JetPack 6.2，15W 档）。完整切换及实测见 [迁移验收](../reviews/jetson-server-migration-20261115/README.md)。

当前使用：

| 用途 | 位置 |
| --- | --- |
| 服务代码 | `/home/jetson/workspace/shanye-tts-service/app` |
| 独立 API/CPU 环境 | `/home/jetson/workspace/shanye-tts-service/.api-venv` |
| 正式服务库 / 凭据 | `/home/jetson/.local/share/shanye-tts` |
| 正式音频 | `/home/jetson/workspace/shanye-tts-service/audio` |
| 环境配置 | `/home/jetson/workspace/shanye-tts-service/service.env` |
| Audio8 原生引擎 | `/home/jetson/workspace/audio8-cpp/build/audio8-tts` |
| Audio8 模型和 codec | `/home/jetson/workspace/Audio8-TTS-Realistic-Air` |
| GPU 环境 | `/home/jetson/workspace/shanye-tts-service/.gpu-venv` |

API/CPU 环境使用 Python 3.11 和 `server/requirements-lock.txt`。原生引擎在 Jetson 本机以 C++17、ARM64 LibTorch 和 CUDA 12.6 编译；构建方法见 `audio8-cpp/README.md`。参考编码/部署转换使用现有 Python 3.10 GPU 环境中的 torch、NumPy、safetensors，不需要加载 Transformers AR 模型。

下载固定版本模型后，一次性导出 C++ codec：

```sh
python -m server.audio8_prepare decoder \
  --model /home/jetson/workspace/Audio8-TTS-Realistic-Air \
  --output /home/jetson/workspace/Audio8-TTS-Realistic-Air/codec-decoder-bf16.safetensors
```

转换检查权重物化前后完全一致，并单独报告 BF16 波形舍入误差。服务配置 `SHANYE_AUDIO8_BINARY`、`SHANYE_AUDIO8_MODEL_PATH`、`SHANYE_AUDIO8_CODEC_PATH`、`SHANYE_AUDIO8_PYTHON`、`SHANYE_AUDIO8_CUDA_GRAPH=1`。参考编码与正文生成共用 worker 的 GPU 锁；新参考编码前释放常驻进程，编码后重新加载。原 CosyVoice 环境和模型保留用于回退，正式 worker 不调用它们。迁移和实测见 [Audio8 验收](../reviews/audio8-production-20261116/README.md)。

首次部署从 `server/deploy/service.env.example` 建立私有配置，再初始化：

```sh
set -a
. /home/jetson/workspace/shanye-tts-service/service.env
set +a
cd /home/jetson/workspace/shanye-tts-service/app
../.api-venv/bin/python -m server.cli init
../.api-venv/bin/python -m server.cli check-storage
```

初始化只创建服务库和权限为 0600 的服务凭据，已有默认 cwns 参考录音时补齐默认预设；书籍和任务保持为空。新服务器先通过音色上传接口安装参考音频。元数据放本机 Linux 文件系统，模型和大文件放 `/home/jetson/workspace`。`check-storage` 实测临时文件 flush 和同目录替换，不能代替硬件断电保证。

复制 `server/deploy/shanye-tts-*.service` 到 `~/.config/systemd/user/`，核对路径后：

```sh
systemctl --user daemon-reload
systemctl --user enable --now shanye-tts-api shanye-tts-cpu shanye-tts-gpu
systemctl --user status shanye-tts-api shanye-tts-cpu shanye-tts-gpu
```

两个正文下载 worker 同机运行，代码和缓存位于 `/home/jetson/workspace/shanye-content`，分别读取私有的 `content.env` / `content-2.env`，监听 17873 / 17874；模板为 `shanye-content.service` 和 `shanye-content-2.service`。公网 Rust 中转及目录服务仍在 `120.48.49.190`。

用户 linger 已启用，服务可以在 SSH 断开后继续；新机器需确认 `loginctl show-user "$USER" -p Linger`。API 仅监听服务器 loopback。GPU worker 空闲时不加载权重，首次任务加载后常驻；API 重启不重载模型。

## 本地书库

启动器默认读取 `app/src/main/assets/shanye.db`，当前包含《噩夢使徒》1,281 章。也可用 `--library /absolute/path/shanye.db` 指定其他阅读器 SQLite（`books`、`chapters`，正文为 `body`）。数据库只读打开；浏览目录、搜索、范围解析和原文预览均在本机完成。服务器没有整本书也能正常使用。

只有提交试听/合成时才发送选中章节的原文及版本信息，服务器重新校验哈希和 UTF-16 分段，保存任务所需快照。预估在临时数据库计算，不向正式数据库导入书籍。浏览器中的书库始终来自本地；服务端保存任务、片段缓存、成品及其对应原文版本。

网页支持 `914,916-919`、目录勾选与标题搜索；章号与物理顺序分别保存，同号单章需明确选择，范围包含所有同号源章节。

管理员可选的测试/迁移入口：

```sh
../.api-venv/bin/python -m server.cli import-book \
  --source /explicit/path/shanye.db --book-id BOOK_ID --namespace my-library
```

此命令只用于显式测试或迁移，日常工作台无需传输整个 SQLite。先用 SQLite 查询来源 `SELECT id,title FROM books` 获取 ID。`namespace` 表示稳定来源身份，后续重复导入使用同值；内容变化产生新 revision，旧产物继续指向旧文本。导入不自动创建合成任务。

## 用参考音频克隆音色

声音设置中展开“用参考音频创建 Audio8 音色”，选择 3–30 秒干净的单人录音（接口接受 1–30 秒，最多 50MiB），支持 M4A、WAV、MP3、FLAC、AAC。逐字稿必填且必须与录音内容一致；无文本模式明确拒绝。上传后得到独立音色预设，选章后先试听。音色及节奏跟随参考，成品倍速和句间停顿仍可调整。

上传经同源代理/SSH；服务器解码为单声道 WAV 并保存哈希。首次使用由 GPU worker 将其编码为 Audio8 的十组参考码，后续复用编码缓存并校验文件哈希。完整引擎/模型未就绪时可以保存参考，但合成会明确拒绝。

## 任务与结果

- 试听由完整语音片段组成，目标约 30–60 秒，显示真实时长。当前 GPU 批次完成后插队；连续 3 个试听批次后让等待的普通任务执行一批。
- 暂停/取消等待当前 GPU 批次或 CPU 导出单元落盘，过渡状态会保留。暂停后显式继续，取消保留有效缓存。
- 每段最多初次加 2 次重试；必要时最多拆一层，子段各有有限预算。同批有效段独立提交，缺段不会发布为完整章节。
- 仅含空白、标点或符号的片段（如章末 `>`）标为 `skipped`，不调用模型或消耗重试；单字、数字及混合正文仍需有效音频。进度与预估单独计数，manifest 的 `skipped_segments` 保留原文和 UTF-16 范围，正文 cue 不移位。全章无朗读内容时标记章节跳过，不生成空音频。
- `retry-failed` 创建关联新任务，快照原配置并复用有效结果；历史终态不覆盖。
- 新版本有独立音频及 JSON 地址。UTF-16 cue 对应原文 revision，点击句子从首个 cue 播放，停顿时不提前高亮下一句。
- 单章 M4A、JSON 和 ZIP 经同源代理流式传输，支持 Range。ZIP 含目录与哈希，文件名含身份后缀，同号章节不覆盖。
- 浏览器决定保存位置，界面“下载已发起”不表示已经写到某个目录。
- 任一工作盘低于 5GiB 时在检查点暂停，释放空间后手动继续；已确认音频不自动删除，临时 ZIP 默认 24 小时过期。
- 提交状态未知时点击“核对提交”，复用原幂等键；不要为了重连再建一份任务。

## 外网任务中转

外网侧是独立的 [Rust 通用任务中转服务](../relay/README.md)，不运行 TTS 模型，也不读取小说库。GPU 主机上的 `server.remote_worker` 主动轮询 `kind=tts`，将章节请求写入现有本地队列；GPU worker 合成、CPU worker 导出并打包。完成时只发布元数据、校验信息和局域网下载地址，ZIP 留在 GPU 主机。App 优先局域网直连，不可达时才申请中转；独立上传线程随后领取中转请求并上传 ZIP，不阻塞后续合成。公网访问仍由 GPU 主动发起，局域网客户端另需可访问 GPU 的 17871 端口。

TTS payload 使用现有 `JobRequest` 格式，例如：

```json
{
  "kind": "tts",
  "dedupe_key": "my-library-book-984-voice-v1",
  "priority": 0,
  "max_attempts": 3,
  "payload": {
    "request": {
      "book_id": "本地书籍 ID",
      "chapter_ids": ["选中章节 ID"],
      "voice_preset_version_id": "clone-9ef173d881f74a49888715b1ed92b24d",
      "mode": "reuse",
      "inline_chapters": []
    }
  }
}
```

提交方应填入真实的选中章节快照 `inline_chapters`（与工作台现有提交内容一致，包含原文、revision 和哈希），或确认这些章节已导入 GPU 侧服务库后省略该字段。音色预设 ID 必须已存在于 GPU 服务中，克隆预设及参考音频仍在 GPU 主机维护。relay 不解析或转换这些内容。仅接收完整章节合成，最终 ZIP 沿用 App 的 `catalogue.json` 和章节 manifest 格式。

在 GPU 主机私有的 `service.env` 中设置：

```sh
SHANYE_RELAY_URL=https://your-relay.example
SHANYE_RELAY_TOKEN=与外网中转服务一致的私有凭据
SHANYE_RELAY_WORKER_ID=gpu-1
SHANYE_RELAY_POLL_SECONDS=5
SHANYE_RELAY_LEASE_SECONDS=300
SHANYE_RELAY_HEARTBEAT_SECONDS=15
SHANYE_RELAY_REQUEST_TIMEOUT=30
SHANYE_RELAY_DIRECT_URL=http://192.168.3.33:17871
SHANYE_RELAY_DIRECT_HOST=0.0.0.0
SHANYE_RELAY_DIRECT_PORT=17871
```

核对路径后安装 `server/deploy/shanye-tts-relay-worker.service` 到用户 systemd 目录，并执行：

```sh
systemctl --user daemon-reload
systemctl --user enable --now shanye-tts-relay-worker
journalctl --user -u shanye-tts-relay-worker -f
```

需要现有 GPU 和 CPU worker 同时运行；适配器不直接加载模型。未配置外网地址时不要启用该 unit。每次领取都有独立租约凭据，适配器在排队、合成、打包、计算哈希和上传期间持续续租。断线重新领取后，同一 GPU 服务库按远端任务 ID 复用本地任务，避免重复生成。网络故障按中转服务预算重试；无效请求或已失败的本地合成会报告为不可重试，不会反复提交同一个失败任务。停止适配器不取消已经提交到本地队列的工作。

成品先保留在 GPU 主机，默认不会上传 ZIP。稳定的 worker ID 用于重启后领取先前结果的中转请求，不能每次启动换一个 ID。局域网服务与轮询适配器同进程，使用持久的 `relay-results.sqlite` 记录单包访问凭据；不开放 GPU 管理 API，不向手机分享管理 token。局域网和公网下载都验证 SHA-256、长度及有效期；按需中转上传使用独立租约和最多 3 次尝试，不改变已完成的合成状态。上传不会删除已确认音频；公网包上传完成后默认保留 24 小时。此功能独立于现有本地工作台和 SSH 连接。

## 历史音频导入

### 在 Android 阅读器中听合成结果

山页 0.8.1 支持「听书 → 合成与导入」直接派发到通用任务中转服务，完成后从「服务器任务」下载并导入，优先局域网直连、不可达才申请中转上传。App 保存独立中转凭据，不保存 GPU 管理凭据；连接配置、派发快照与任务日志排除备份，凭据由 Android Keystore 加密。操作步骤见 [App 云端合成](../docs/android-cloud-synthesis.md)。

原有手动导入继续可用：在工作台选择完整成品版本，点「打包听书包（App 导入）」并从任务队列下载 ZIP；传到手机后，在「听书 → 导入听书包」中选取。也可从阅读菜单进入。首次导入须已拥有同 ID、同章节文本的本地书籍。原文 revision/hash、UTF-16 cue、目录/音频哈希及媒体时长校验通过后，音频进入 App 私有存储和独立 SQLite 索引，目录显示「可听」。可保留多个版本，各自记忆进度，离线播放与定位原文；后台和锁屏使用系统媒体会话。

ZIP 沿用 `catalogue.json` schema 1 与 chapter manifest schema 2，不需要重新合成。仅允许 complete=true 的整章，每包最多 1,000 个版本、4 GB；任一项校验失败，整包不入库。原文简繁显示转换不影响时间轴。工作台按章节 ID 与当前原文 revision 关联任务产物，试听不显示为完整成品。

需要先导入匹配原文，再显式导入完整 M4A/JSON：

```sh
../.api-venv/bin/python -m server.cli import-artifact \
  --audio /chosen/chapter.m4a --manifest /chosen/chapter.json --chapter-id SERVICE_CHAPTER_ID
```

校验原文哈希、cue 范围/文本、音频哈希和容器时长。仅 M4A/JSON 登记为导出，不能变成 FLAC 缓存。若另有旧 `tts.sqlite` 和原始片段根目录，可增加 `--raw-db ... --raw-root ...`，逐段校验来源 profile、生成配置、规范化规则、样本与哈希后注册。不会自动寻找历史备份。

## 备份、审计与停止

```sh
../.api-venv/bin/python -m server.cli backup --output /new/path/service-backup.sqlite
../.api-venv/bin/python -m server.cli audit
systemctl --user stop shanye-tts-gpu shanye-tts-cpu shanye-tts-api
```

备份使用 SQLite backup API，正在运行时不直接复制数据库文件。`audit` 把丢失/损坏记录标成不可用，报告未发布文件，不删除已确认音频或孤立文件。worker 恢复时校验已提交检查点，过期租约不能继续发布；暂停/取消状态不会因重启改变。

部署前备份服务库和音频，停机时让当前单元落盘。旧 CLI 仅作历史工具保留，正式服务没有旧引擎回退。若使用不遵守本 GPU 锁的外部推理程序，需要先协调停止。

## 验证

```sh
uv venv --python 3.12 server/.venv
uv pip sync --python server/.venv/bin/python server/requirements-dev-lock.txt
server/.venv/bin/python -m unittest discover -s server/tests -v
tts/.venv/bin/python -m unittest discover -s tts -p 'test_*.py' -v
cd web && npm ci && npm run build
```

真实 GPU 验收使用原创试音小书与独立目录，显式运行 `python -m server.benchmark` 前必须设置 `SHANYE_BENCHMARK_CONFIRM=studio-demo-only`、独立的 `SHANYE_STATE` 与 `SHANYE_AUDIO`，并确保正式 GPU worker 已停止。先在隔离 API 上传参考音色，将返回的版本 ID 填入 `SHANYE_BENCHMARK_PRESET`。它不读取正式书库或自动复制正式参考录音。Audio8 正式验证记录见 [切换验收](../reviews/audio8-production-20261116/README.md)，旧引擎历史记录见 [验收报告](../docs/tts-workbench-acceptance/README.md)。

API 由 FastAPI 生成 OpenAPI；通过本地代理读取 `/openapi.json`。契约快照为 `server/openapi.json`。需要将此服务暴露到公网或引入多人权限时，另行设计鉴权和部署。
