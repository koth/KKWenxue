# 局域网优先、按需中转验收

Android 0.8.1（versionCode 12），安装包 `dist/shanye-0.8.1-debug.apk`，校验值见 `apk-sha256.txt`。原私有连接配置继续有效。

## 流程

1. GPU 合成、导出、打包后，通过 `/complete` 只发布完成状态、局域网地址、单包访问凭据、字节数、SHA-256 和有效期，不上传 ZIP。
2. App 点「下载并导入」优先请求 `192.168.3.32:17871`；只发送单个结果的访问凭据，校验后调用原导入器。
3. 直连不可达才向公网 `/result/request` 申请中转。GPU 独立上传线程领取独立租约，上传原 ZIP，App 等候轻量 `/status` 后下载。合成队列继续处理后续任务。
4. 多客户端/重复请求共用同一个中转包；上传失败不重新合成。旧版已经上传的结果继续可下载。局域网数据校验错误、手机写入错误不会误触发中转。

该完成通知是任务状态与结果元数据发布，App 按前台轮询展示；没有新增 Android 系统推送。

## 验证

- Rust 16 项测试、全 target Clippy `-D warnings` 通过。覆盖完成通知不落 ZIP、重复请求与原节点领取、上传内容一致性、独立租约超时回收、旧租约拒绝、失败不重新合成、过期拒绝及此前接口；见 `rust-tests.txt`。
- Python 全部 39 项服务测试通过。覆盖真实本地队列生成可导入 ZIP 但不上传、按需上传、重领复用、过期打包恢复、LAN 认证/过期/路径隔离/持久恢复；见 `python-tests.txt`。
- Android 构建、测试包和 lint 通过，8 项仪器测试通过。新增直接导入时中转上传/下载计数均为零、LAN 503 后等待独立上传并复用中转结果、LAN 校验失败不触发付费转发；见 `android-build.txt`、`android-tests.txt`。
- 显式真实公网/GPU 验收 1 项通过，耗时 16.626 秒，使用原创短章和已有有效音频缓存；见 `public-gpu-acceptance.txt`、`live-assertions.txt`。

真实任务 `e38e834a-497a-4e85-8cf1-c9f4949101c5` 的完成通知 `relay_state=none`。App 直连导入并验证 UTF-16 cue 后，状态仍为 none，公网没有该 ZIP。随后测试显式请求中转，GPU 才上传 99,064 字节原包，App 下载后哈希一致，合成尝试次数保持 1。日志同时包含 “LAN result published, no ZIP uploaded” 与稍后的 “uploaded on demand”，见 `gpu-worker.txt`。

## 部署

- 公网 Rust `shanye-task-relay.service` 已更新并运行，沿用 `http://120.48.49.190:17870`、原凭据、数据库和旧结果；启动自动迁移新字段。迁移前 SQLite backup 与旧二进制分别保留在 `/var/lib/shanye-task-relay/pre-lan-first-20261114.sqlite` 和 `/opt/shanye-task-relay/shanye-task-relay.pre-lan-first-20261114`。
- GPU `shanye-tts-relay-worker` 已更新并运行，新增同进程 LAN 下载监听 `0.0.0.0:17871`，通知地址为 `http://192.168.3.32:17871`。既有 API/GPU/CPU 服务保持 active，模型和运行环境没有改动。
- 两台 SSH 均复用 ControlMaster。连接配置文件未进入安装包，临时仪器测试连接文件已经删除。

GPU 临时 ZIP 默认有效 24 小时；中转 ZIP 的 24 小时从上传成功起算。手机已导入的音频不受清理影响。App 等候上传超过 10 分钟会提示稍后重试，服务器上传继续执行；大包下载仍沿用现有前台操作与流式哈希校验。
