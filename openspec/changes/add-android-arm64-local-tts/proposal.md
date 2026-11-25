## Why

当前 App 的“听书”主要依赖服务器合成。章节没有现成音频时，用户必须等待服务器任务完成，网络连接和服务器排队也会影响首次播放。现有 ZONOS3 Mini 推理核心已经包含 Android AArch64 CPU 路径，模型也可以拆成可下载的模型包，适合把一部分合成能力放进 App。

本变更把 Android ARM64 本地合成定义为一个可选初始化的本地运行时。APK 只内置 JNI、运行时和小型预编码声线代码，首次进入 App 时提示用户是否下载约 1.1GB 模型。用户确认后从 Hugging Face 镜像下载，下载前不阻塞书架、阅读器或服务器合成。首版支持 `说书人一`、`说书人二`、`林川` 和 `Haoran`，其中 `说书人一` 作为 App 默认声线。用户上传参考音频的本地克隆属于后续能力。

## What Changes

- 为 `arm64-v8a` 构建 ZONOS3 C++ 核心和 JNI 封装，启用 KleidiAI、CPU repack 及运行时 CPU 特性选择。
- 新增强制模型初始化页，首次启动必须下载并校验模型，完成前不进入书架。
- 从 `hf-mirror.com/Zyphra/ZONOS3-Mini` 下载 `backbone-ptq.gguf` 与 `zac-keep2.gguf`，支持断点续传、SHA-256 校验、原子发布和取消恢复。
- 预编码声线代码保留在 APK asset 中，体积很小，不随大模型下载。
- 用户点击没有现成音频的章节时，App 可以直接启动本地分段合成，首段 PCM 到达后通过 `AudioTrack` 开始播放。
- 保存本地片段缓存与章节 manifest，记录原文 revision、声线、模型 revision、引擎版本、章节名 cue、停顿参数和 UTF-16 定位信息。
- 长时间生成使用前台播放服务和持久后台队列，支持取消、进程重启后恢复和只重试失败片段。
- 保留服务器合成入口作为明确的远程能力。设备不满足本地运行条件时，App 展示原因并允许用户选择远程合成。

## Capabilities

### New Capabilities

- `android-arm64-inference`: Android AArch64 原生推理核心、运行时 CPU 优化和性能基线。
- `android-local-tts-model-pack`: 模型包、预编码声线资源的下载、校验、存储和生命周期管理。
- `android-local-tts-runtime`: Kotlin/JNI 调用、单模型驻留、分段生成、取消和失败恢复。
- `android-local-tts-playback`: 首段流式播放、章节缓存、章节名/正文 cue 和阅读器集成。

### Modified Capabilities

- `reader-audio-playback`: 听书入口增加本地缺失音频的生成路径，继续复用现有章节身份、原文锚点和服务器产物。

## Impact

- Android 构建增加 NDK、CMake、JNI 和 `arm64-v8a` 原生库，`minSdk` 与现有 Android 版本保持兼容性评估。
- App 私有存储增加约 1.1GB 模型副本、预编码声线和本地片段缓存，下载前提示所需空间。
- 服务器保留模型包清单、分块下载和声线代码发布接口，供开发包、资源更新和 relay 代理使用。
- 阅读器播放链路增加本地生成状态和错误状态，UI 需要区分模型准备、生成、播放、缓存和失败重试。
- 新产品尚无用户数据迁移要求。首次版本不向 APK 内置书籍或整本书正文。
