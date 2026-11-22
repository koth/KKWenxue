# 山页语音工作台

本地 React 页面与 Python 启动器只读加载本地阅读器 SQLite；提交选中章节后，经复用的 SSH 隧道发送到 FastAPI，GPU worker 合成，CPU 导出器生成音频。

正式合成使用 ZONOS3-Mini 的原生 C++ / GGML CUDA 引擎，提供 Haoran 预置音色与林川克隆音色，默认林川。成品默认 1.0 倍速、180ms 句间停顿。Web 与 App 均可选择已发布的音色。修改成品速度或停顿复用同一配置下的有效 FLAC，播放器倍速只影响播放。

新任务默认开启“统一成品音量”（`export.normalize_loudness=true`）：拼接与倍速处理后，CPU 使用 FFmpeg 两遍响度处理，目标为单声道 -18 LUFS、PCM 真峰值 -2 dBTP，并检查 AAC 成品不超过 -1 dBTP。manifest 记录处理版本、输入/输出实测值；不足 400ms 的极短内容使用有界峰值增益。关闭可导出原音量。该设置只改变导出缓存键，复用原始 FLAC，不修改参考音频、克隆提示或文本时间索引。旧任务与旧成品保持原配置，需新建复用任务导出均衡版本。

模型为 `Zyphra/ZONOS3-Mini`，权重版本固定为 `2921b8821772b0bd10a04456863d21abf9d2f283`，使用 PTQ 主干与 keep2 音频解码器。任务与缓存身份包含引擎版本、预编码音色哈希和内容校验策略。旧音频仍可播放，旧引擎任务需要重新派发。

正文沿用阅读器的简体规范化与分段。常驻 C++ 进程通过 loopback HTTP 接收任务，生成 44.1kHz WAV，适配层转为既有 24kHz FLAC；章节导出、原文与 UTF-16 cue 协议保持一致。管理员提前编码参考录音并发布缓存，正文合成直接读取参考码，不加载克隆编码模型。旧的语气指令不传入当前模型。

新任务使用 `zonos-native-laci-bounded160-punctuation-v2` 策略，继续启用中文 LACI 与短句处理。分段后、调用模型前的文本归一化会去掉书名号、引号和括号等装饰符号，保留其中正文；连续停顿符号压缩为一个，优先保留问号或感叹号，单独的省略号转为短停顿。小数点、数字分组及单词内的撇号等保留。阅读器的章节、句子 ID、原文与 UTF-16 cue 不变，SenseVoice 继续对归一化前的朗读文本校验。尝试记录中的 `native_text` 是实际发给模型的文本。

已排队的 `zonos-native-laci-bounded160-v1` 任务及其失败项重试仍使用原规则。两种策略的音频缓存不混用；如需用新规则重合成旧片段，请新建合成任务。

默认启用 `sensevoice-tolerant-coverage-v3` 宽容内容校验：CPU 常驻 SenseVoice INT8 识别整个原文片段，按读音与数字值粗查。个别错字、小幅漏字、数字或英文尾缀的识别差异直接通过并记录警告；仅明显漏掉较长内容、整段偏离或大量增词才拒绝。有效音频不再在引擎内部额外生成第二份，异常交给有限片段预算。已排队的 v2 配置也按宽容策略执行，并在 execution 中分别记录原请求策略和实际策略。校验器超时或缺失仍视为服务故障。历史任务快照及音频不覆盖；该策略优先保证连贯收听，不保证逐字准确。

校验使用独立 Python 3.11 环境 `/home/jetson/workspace/shanye-tts-service/.qa-venv`，依赖见 `server/requirements-qa.txt`。[SenseVoice INT8 固定模型](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/tree/6a65851692da9706cbddfac66ea9b96ebb1dee21) 的 `model.int8.onnx` 和 `tokens.txt` 位于 `/home/jetson/workspace/sensevoice-int8-20240717/`。配置 `SHANYE_SPEECH_VERIFY=1`、`SHANYE_SPEECH_VERIFY_PYTHON` 和 `SHANYE_SPEECH_VERIFY_MODEL`；CPU 识别进程由 GPU worker 按需启动并复用，无需另建 systemd unit。关闭校验仅用于隔离对照测试。

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
| ZONOS3 原生引擎与 GGUF 模型 | `/home/jetson/workspace/zonos3-mini` |
| 两种预编码音色 | `/home/jetson/workspace/shanye-tts-service/audio/zonos3-voices` |
| CPU 内容校验环境 | `/home/jetson/workspace/shanye-tts-service/.qa-venv` |

正式引擎为 ZONOS3-Mini C++/GGML CUDA，使用 `backbone-ptq.gguf` 和 `zac-keep2.gguf`。GPU worker 与 API/CPU 共用轻量 Python 3.11 环境，不依赖 Python torch、Transformers 或 Audio8 GPU 环境。原生进程由 GPU worker 拉起并常驻，通过 loopback HTTP 调用；中文 `cmn`，reference 模式，启用中文 LACI 对齐纠错与短句处理。所有新任务的引擎、权重版本、编码音色哈希和校验策略均冻结到快照，旧引擎缓存不会混用。

配置入口见 `server/deploy/service.env.example`：`SHANYE_ZONOS_ROOT`、`SHANYE_ZONOS_PORT`、`SHANYE_ZONOS_TIMEOUT` 和 `SHANYE_SPEECH_VERIFY*`。内置音色为 **Haoran**（预置）和 **林川**（默认）；Android 0.9.7 对应同一组稳定 ID。Web 工作台可另外上传新参考录音创建音色，见下方说明。原部署验收见 [记录](../reviews/zonos3-production-20261118/README.md)。

原生引擎重启时，私有端口若尚未释放，会先等待至多 65 秒（不超过 `SHANYE_ZONOS_TIMEOUT`），再开始实际生成，避免瞬时端口占用耗尽排队片段的尝试次数。不连接或终止占用端口的其他进程。宽容校验与队列恢复验收见 [记录](../reviews/tts-tolerant-qa-20261118/README.md)。

首次部署从 `server/deploy/service.env.example` 建立私有配置，再初始化：

```sh
set -a
. /home/jetson/workspace/shanye-tts-service/service.env
set +a
cd /home/jetson/workspace/shanye-tts-service/app
../.api-venv/bin/python -m server.cli init
../.api-venv/bin/python -m server.cli check-storage
```

初始化创建服务库和权限为 0600 的服务凭据，安装 Haoran 预设，并在林川参考记录存在时补齐默认预设。新服务器还需发布预编码音色文件和 voices.json；没有缓存时不能提交任务。书籍和任务须显式导入。元数据放本机 Linux 文件系统，模型和大文件放 `/home/jetson/workspace`。`check-storage` 实测临时文件 flush 和同目录替换，不能代替硬件断电保证。

复制 `server/deploy/shanye-tts-*.service` 到 `~/.config/systemd/user/`，核对路径后：

```sh
systemctl --user daemon-reload
systemctl --user enable --now shanye-tts-api shanye-tts-cpu shanye-tts-gpu
systemctl --user status shanye-tts-api shanye-tts-cpu shanye-tts-gpu
```

两个正文下载 worker 同机运行，代码和缓存位于 `/home/jetson/workspace/shanye-content`，分别读取私有的 `content.env` / `content-2.env`，监听 17873 / 17874；模板为 `shanye-content.service` 和 `shanye-content-2.service`。公网 Rust 中转及目录服务仍在 `120.48.49.190`。

用户 linger 已启用，服务可以在 SSH 断开后继续；新机器需确认 `loginctl show-user "$USER" -p Linger`。API 仅监听服务器 loopback。GPU worker 空闲时不加载权重，首次任务加载后常驻；API 重启不重载模型。

## 本地书库

直接运行 `python3 -m local.launcher --open`，在左侧「从服务器拉取」选择书籍。可填写 `984,986-988` 等测试章号，留空拉取服务器已经保存的全部章节（每次最多 2000 章、64 MB 原文）。GPU 服务保存的是已导入或合成过的原文，页面显示其实际章节数，目录可能不连续。原文通过现有 SSH 隧道拉取，校验章节 ID、版本和 SHA-256 后一次事务保存；重复拉取合并章节，失败保留本地原文，不自动派发合成。

下载书库默认位于仓库 `.workbench/server-library.sqlite`，可用 `--downloads /path/to/library.sqlite` 更换。`--library /absolute/path/shanye.db` 改为可选，用于只读浏览含 `body` 正文的阅读器 SQLite（`books`、`chapters`）；下载内容与该文件分开保存，不需要复制服务端整个数据库或手工转换表结构。

命令行操作：

```sh
python3 -m local.pull_book                         # 列出服务器已有书籍和章节数
python3 -m local.pull_book --book-id BOOK_ID       # 拉取已有章节
python3 -m local.pull_book --book-id BOOK_ID --chapters 984-986
```

浏览目录、搜索、范围解析和原文预览均在本机完成。开发前端 `http://127.0.0.1:5173` 已代理 `/session`、`/api` 到启动器 `17861`，启动器仍负责持有服务凭据和本地写入校验。

只有提交试听/合成时才发送选中章节的原文及版本信息，服务器重新校验哈希和 UTF-16 分段，保存任务所需快照。预估在临时数据库计算，不向正式数据库导入书籍。工作台的日常书库来自本地缓存；仅在用户点击服务器拉取入口时读取服务端书目与选定原文。服务端保存任务、片段缓存、成品及其对应原文版本。

网页支持 `914,916-919`、目录勾选与标题搜索；章号与物理顺序分别保存，同号单章需明确选择，范围包含所有同号源章节。

管理员可选的测试/迁移入口：

```sh
../.api-venv/bin/python -m server.cli import-book \
  --source /explicit/path/shanye.db --book-id BOOK_ID --namespace my-library
```

此命令只用于显式测试或迁移，日常工作台无需传输整个 SQLite。先用 SQLite 查询来源 `SELECT id,title FROM books` 获取 ID。`namespace` 表示稳定来源身份，后续重复导入使用同值；内容变化产生新 revision，旧产物继续指向旧文本。导入不自动创建合成任务。

## 用参考音频克隆音色

Web 工作台支持使用新 MP3/M4A/WAV 录音创建音色：展开「声音设置 → 用参考音频创建 ZONOS3 音色」，选择 3–30 秒干净单人录音，点击创建。逐字稿可选。首次由本机编码，随后将录音和参考码发布到服务器；新音色按文件名显示在旁白预设中，后续任务复用缓存。林川、Haoran 和已经生成的任务继续使用原有快照。

本机编码器默认位于仓库 `tmp/zonos3-mini`：`build-metal/zac-encoder-cli`、`clone-models/zac-encoder.gguf`、`clone-models/ssl-teacher.gguf`。可通过 `SHANYE_ZONOS_ENCODER_ROOT` 指定该目录。编码结果按原音频 SHA-256 缓存在 `.workbench/reference-voices/`。仅当本机编码器可用、服务器支持预编码音色发布时，工作台展示上传入口；不需要 Jetson 加载 5.6 GB 的编码模型。

服务器的 `/api/v1/voice-clones/preencoded` 使用既有服务凭据，校验 WAV 参数、时长、参考码形状/取值及两个文件的 SHA-256，发布不可变音色与预设。原生常驻进程在下一个合成批次需要新音色时重新加载；不会中断正在生成的批次。上传失败不创建可选择的空预设。原始 MP3 上传由本地启动器处理，服务端原始上传接口保留明确提示。

一次性准备林川：使用官方 `zac-encoder-cli`，载入 `zac-encoder.gguf` 和 `ssl-teacher.gguf`，将干净单人录音编码成 `LinChuan.codes.npy`（普通 `[T,10]` 编码）；可额外用 `spk-encoder-cli` 保存 `LinChuan.embedding.npy`。编码完成后只将录音、编码和哈希清单发布到服务端。正式合成走 reference 模式，优先复用同名 `.codes.npy`，无需部署约 5.6GB 的克隆编码模型。它是音色参考编码，不是微调模型。

同目录文件结构：`Haoran.mp3` / `Haoran.codes.npy`、`LinChuan.mp3` / `LinChuan.codes.npy` 及 `voices.json`；工作台上传的新录音使用 `upload-<内容身份>.wav` 与同名 `.codes.npy`。清单冻结 speaker、native_name、相对于音频根目录的 audio_path/codes_path 和各自 SHA-256。可选的 `.embedding.npy` 不会额外显示为音色。参考转写留在清单供审计；ZONOS3 的 reference 模式不需要将逐字稿输入生成模型。

逐段生成后使用常驻 CPU ASR 粗查明显的大段漏读和增词；小幅差异放行并留记录，严重异常才有限重试，缺段不能发布成完整章节。ASR 容忍同音字，不能替代人名、多音字的人工听评。音色、权重或策略变化须发布新版本，不覆盖既有任务快照。

## 任务与结果

- 试听由完整语音片段组成，目标约 30–60 秒，显示真实时长。当前 GPU 批次完成后插队；连续 3 个试听批次后让等待的普通任务执行一批。
- 暂停/取消等待当前 GPU 批次或 CPU 导出单元落盘，过渡状态会保留。暂停后显式继续，取消保留有效缓存。
- 每段最多初次加 1 次重试；失败分支必要时最多切一层，每个子段最多尝试 2 次，过短片段停止继续切分。每个原片段最多 6 次原生生成调用，不再叠加引擎内部重试或内容失败的中转整轮重试。成功子段和同批有效段独立保存复用，缺段不会发布为完整章节。错误显示父段与子段总尝试次数、具体校验原因及原文/识别结果。
- 仅含空白、标点或符号的片段（如章末 `>`）标为 `skipped`，不调用模型或消耗重试；单字、数字及混合正文仍需有效音频。进度与预估单独计数，manifest 的 `skipped_segments` 保留原文和 UTF-16 范围，正文 cue 不移位。全章无朗读内容时标记章节跳过，不生成空音频。
- `retry-failed` 创建关联新任务，快照原配置并复用有效结果；历史终态不覆盖。关联重试轮次使用不同种子，避免重复生成同一份坏音频；语音内容校验继续保留。
- 新任务使用 `zonos-native-laci-bounded160-pauses-v3`：中文章号与标题空格间插入 500ms 实际停顿，逗号/顿号 250ms、冒号 300ms、分号 400ms。英文单词、千分位和时间保持完整。新生成缓存与旧策略分开；已有音频需重新合成才包含新停顿，App 无需更新。
- 新任务使用同一音色先朗读章节名，再停顿至少 650ms 朗读正文。章节名独立生成、校验和缓存，正文缓存继续复用；标题已是正文首段时不重复播报。manifest 的 `title_cues` 描述标题时间轴，`cues` 仍只包含正文 UTF-16 锚点，音频时长包含标题。标题快照与任务一同冻结；旧任务和旧听书包保留原样，重新派发即可补标题并导出新版本。
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
      "voice_preset_version_id": "clone-linchuan-zonos3-v1",
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

需要现有 GPU 和 CPU worker 同时运行；适配器不直接加载模型。未配置外网地址时不要启用该 unit。每次领取都有独立租约凭据，适配器在排队、合成、打包、计算哈希和上传期间持续续租。断线重新领取后，同一 GPU 服务库按远端任务 ID 复用本地任务，避免重复生成。网络等临时故障按中转服务预算重试（App 默认 3 轮）；片段内容校验耗尽本地预算后直接报告终态，不自动重跑整轮，释放后续任务，用户仍可手动重试。重新领取时按远端轮次创建幂等的本地失败项重试，沿用原音色、配置和 generation，复用成功片段，而不是再次查询同一个终态失败任务。无效请求、取消和纯导出失败不自动重排语音合成。App 手动重试使用新的跟踪任务和原远端任务 ID `payload.retry_of`，在本地关联冻结快照；文本或音色参数被更改会拒绝。停止适配器不取消已经提交到本地队列的工作。

成品先保留在 GPU 主机，默认不会上传 ZIP。稳定的 worker ID 用于重启后领取先前结果的中转请求，不能每次启动换一个 ID。局域网服务与轮询适配器同进程，使用持久的 `relay-results.sqlite` 记录单包访问凭据；不开放 GPU 管理 API，不向手机分享管理 token。局域网和公网下载都验证 SHA-256、长度及有效期；按需中转上传使用独立租约和最多 3 次尝试，不改变已完成的合成状态。上传不会删除已确认音频；公网包上传完成后默认保留 24 小时。此功能独立于现有本地工作台和 SSH 连接。

## 历史音频导入

### 在 Android 阅读器中听合成结果

山页 0.9.10 支持「听书 → 合成与导入」直接派发到通用任务中转服务，完成后由持久后台任务自动下载并导入，优先局域网直连、不可达才申请中转上传；两条下载路径支持 Range 断点续传，网络错误自动重试。App 保存独立中转凭据，不保存 GPU 管理凭据；连接配置、派发快照与任务日志排除备份，凭据由 Android Keystore 加密。操作步骤见 [App 云端合成](../docs/android-cloud-synthesis.md)。

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

真实 GPU 验收使用原创试音小书与独立目录，先协调正式队列并取得 GPU 锁；通过已发布的预编码音色跑完整合成、内容校验、M4A 导出及 App ZIP 校验。可复核脚本与结果见 [ZONOS3 上线验收](../reviews/zonos3-production-20261118/README.md)。旧 `server.benchmark` 的即时上传流程属于历史引擎工具，不适用于当前预编码音色发布方式。

API 由 FastAPI 生成 OpenAPI；通过本地代理读取 `/openapi.json`。契约快照为 `server/openapi.json`。需要将此服务暴露到公网或引入多人权限时，另行设计鉴权和部署。
