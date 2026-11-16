# Android 0.8.0 云端合成与任务导入验收

安装包为 `dist/shanye-0.8.0-debug.apk`，versionCode 11，SHA-256 见 `apk-sha256.txt`。连接配置为工作区 `dist/private/relay-connection.json`，包含私有接入凭据，未打进 APK、源码或验收日志。

## 已实现

- 「听书 → 合成与导入」支持本地选书、章号/范围解析、9 种固定音色及已有自定义/克隆预设 ID，派发选中章节的快照。
- 派发前保存持久 outbox；状态未知可用原幂等键核对，避免重复提交。
- 服务器任务分页、前台定时刷新、按任务 ID 查找；完成后下载、校验并调用现有整包导入器。导入状态持久化，重复导入不增加版本。
- 中转凭据用 Android Keystore 加密，设置与任务日志排除备份。保留手动 ZIP 导入和原播放器全部控制。
- Rust 中转服务新增通用 metadata 和轻量任务列表；列表不含章节正文或租约凭据，旧数据库自动迁移。

## 部署

公网服务为 `http://120.48.49.190:17870`，`shanye-task-relay.service` 已 enable/active。独立账号 `shanye-relay` 运行，SQLite 位于 `/var/lib/shanye-task-relay/relay.sqlite`，临时结果位于 `/var/lib/shanye-task-relay/tmp/`，TTL 24h，结果上限 4GiB。该地址当前用于 IP/HTTP 联调，域名和证书就绪后可改 HTTPS。

GPU 主机 `192.168.3.32` 上 `shanye-tts-relay-worker` 已 enable/active；原 API、GPU 和 CPU 服务保持运行。公网机内存较少，因此 Linux 二进制在 GPU 主机的 `/h/work/shanye-task-relay-build-20261114` 构建后部署。两个 SSH 主机均使用 ControlMaster 复用连接。见 `relay-service.txt`、`gpu-worker.txt`。

## 验证

- `assembleDebug`、`assembleDebugAndroidTest`、`lintDebug` 通过，见 `final-build.txt`。
- 完整 Android 回归无失败，XML 包含 28 个测试用例，其中 26 个通过，2 个显式外部素材/公网验收用例默认跳过，见 `android-regression.txt`。
- 新增 5 项仪器测试通过：真实章号与 Unicode 快照、状态未知后的原请求重试、HTTP 下载与整包导入、损坏内容/缺书拒绝、Keystore 加密和凭据重定向阻断。最终结果见 `final-instrumented.txt`。
- Rust 12 项测试通过，Clippy `-D warnings` 通过，覆盖分页、过滤、metadata 迁移及此前租约/上传逻辑。
- Python 服务 37 项回归通过，见 `python-tests.txt`。

## 真实公网与 GPU 验收

在 Android 仪器测试中显式派发一章原创短文，经公网 relay、GPU 轮询适配器、真实 Qwen GPU 合成、CPU 导出和打包、上传、手机校验导入。首轮约 21 秒，任务 ID `2c4f4ac3-fb1b-4270-8839-36c1fb158d65`，见 `public-gpu-acceptance.txt`；回归后的最终版本再次经过同一链路并复用有效缓存，约 13 秒，任务 ID `d4e86ef8-d7ce-4f18-9f2b-a58c377636b7`，见 `public-gpu-final.txt`。

验证了 Android cue 的 UTF-16 范围与原文一致、M4A 可播放、播放推进及句子更新。还在界面点选另一条未在本机标记导入的服务器任务的「下载并导入」，验证已有相同音频不会重复入库。公网健康接口无凭据返回 401，带凭据返回 200；列表未包含 payload。

- `dispatch.png`：派发页面。
- `tasks.png`：服务器任务与导入入口。
- `player-entry.png`：导入的真实 GPU 结果。
- `playing.png`：播放到后一句时的进度和文本。

未通过本轮验收自动派发整本小说或批量历史音频。原 Web 工作台的直接任务仍通过原手动 ZIP 入口导入，历史结果不会被自动上传。
