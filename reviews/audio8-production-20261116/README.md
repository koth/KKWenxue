# Audio8 C++ 正式后端与 cwns 音色

2026-11-16，在 Jetson Orin Nano 8GB / JetPack 6.2 / CUDA 12.6 上完成原生编译、实际合成和正式服务切换。默认音色为用户提供的 `cwns.mp3`，逐字稿按用户原文保存；默认预设为 `clone-a63c22269e594284805a0ffaadb784a1`。

## 部署结果

- 正式引擎：`audio8-realistic-air-cpp`，模型 `Edge0/Audio8-TTS-Realistic-Air`，固定版本 `6bf7a980759d8022a66922ee975dab99229a2dd9`，BF16 / CUDA Graph。
- 二进制：`/home/jetson/workspace/audio8-cpp/build/audio8-tts`；模型：`/home/jetson/workspace/Audio8-TTS-Realistic-Air`。
- API、GPU worker、CPU 导出器、中转 worker 均 active，GPU worker 最后为 ready / idle，重启计数 0。见 [最终状态](final-health.json)。
- 保留原任务、音频、音色预设与 App 包协议；9 个参考录音已预编码。缺少的 5 份参考文本通过本地 ASR 起草，并校正明显同音字；sound.m4a 的人名对照本地第 1008 章。来源与修订见 [参考文本](reference-transcripts.json)。新默认 cwns 使用用户提供的逐字稿。
- 原 CosyVoice 模型和环境保留，正式任务不调用 CosyVoice 或 Qwen TTS，也不自动回退。正文由常驻 C++ 进程生成；Python 仅负责现有队列、一次性参考编码和导出调度。

## 音频、任务与原文验证

[cwns 原创试音](audio8-cwns-audition.m4a) 经完整队列生成，4/4 段成功，成品 22.831 秒，完成 FLAC、M4A、响度处理、UTF-16 cue 和 ZIP 检查。总耗时 69.31 秒，包含首次参考编码、模型启动与导出，不能当作热机速度。见 [整包报告](cwns-e2e-report.json) 和 [内容识别检查](cwns-audio-check.json)。隔离试音小书的 ZIP 为 [audio8-cwns-import.zip](audio8-cwns-import.zip)，它不属于正式小说原文。

正式第 1033 章试听任务 `1af05a06fb5e4b65a1c6c9d7b0dfb90c` 已完成，4/4 段、0 重试，成品 42.708 秒，已进入工作台任务列表。试听保留 `complete:false`，不能作为整章导入。

- [第 1033 章试听](1033-audio8-cwns-preview.m4a)
- [原文与时间轴](1033-audio8-cwns-preview.json)
- [任务结果](production-report.json)、[执行记录](production-attempts-v2.json)、[内容识别检查](production-asr.json)

实际小说验证发现原生分句器将对白末尾 `」` 拆成纯符号片段，使完整句合成失败。已修复连续句末标点与闭合引号的分组，纯符号不独立送模型；外层队列使用 `split_sentences:false`，直接合成已经绑定 UTF-16 原文范围的服务片段。修复后的同章验证无失败；最初试验任务保留记录并已取消。

独立 ASR 对原创样本识别与文本一致，小说试听未发现漏句或重复；专名存在同音字差异。ASR 不能代替人工音色、韵律和发音评价。

## 速度与数值边界

第 1033 章的 4 段累计合成耗时 101.35 秒，对应 42.17 秒原始音频，RTF 为 2.40；包含首次模型加载的首段，详情见 [指标](production-metrics.json)。此前 stf 热机样本 12.26 秒音频耗时 28.61 秒。当前不能宣称此迁移带来提速；不同引擎、音色和文本的测量也不是严格配对基准。

codec 导出 214 个张量、260,785,578 字节。物化 weight norm 前后权重最大差为 0；真实 stf 参考码的 BF16 波形最大差 0.0048828、RMSE 0.0003605、参考 RMS 0.0278973。波形未达到逐采样一致，不能将权重一致等同于输出数值完全一致。见 [转换报告](codec-conversion.json)。

## 检查与客户端

- 服务测试 53 项通过，其中 3 项原 CosyVoice GPU 专项按本地环境跳过；新的原生进程协议测试覆盖复用、超时/错误后的重启、参考校验和 44.1kHz → 24kHz 转换。
- Jetson 原生分句测试覆盖 6 组对白/标点场景，通过；真实 GPU 队列、整包与线上小说试听通过。
- Web 2 项测试、TypeScript 检查和生产构建通过。
- Android `assembleDebug` 成功；`testDebugUnitTest` 为 NO-SOURCE，本次没有宣称新增 Android 单元或设备测试。0.9.4 / versionCode 24 已加入 cwns 并设为默认，包为 [shanye-0.9.4-debug.apk](../../dist/shanye-0.9.4-debug.apk)。现有 App 可继续播放旧音频；选择新默认音色需要更新 App。Web 刷新后读取新默认项。

## 备份

Jetson `/home/jetson/workspace/audio8-migration-20261116/` 保存 `original-server/`、权限 0600 的 `service.env.before-audio8`、切换前 SQLite backup 和隔离验收目录。回退应先停止/排空新任务，再恢复原 server 与私有配置；不要直接覆盖已有新任务的数据库。模型和旧环境未删除。
