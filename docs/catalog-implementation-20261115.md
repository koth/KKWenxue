# 目录正文接入完成记录

本轮完成交接文档中的 M3/M4/M5：

- App 书架搜索新增「全网」模式，300ms 防抖调用目录 API；展示书名、作者、分类、字数、更新时间和最新章。详情弹窗可发起正文导入，轮询任务完成后解压并写入本地 SQLite 书库。
- catalog 新增正文任务路由。`content.fetch` 由家里 worker 抓取 `/txt/`、按章节切片落盘；完成后自动建立 `content.serve` 打包任务。
- 传输采用 LAN-first：worker 完成通知中带局域网直连 URL 和 capability token；App 直连失败才调用 `/download`，catalog 再向 relay 申请中转。
- 新增 `/h/work/shanye-content` worker 和 `shanye-content.service`，不改动现有 TTS 服务或模型环境。
- catalog 新增 `preheat` 子命令，已从 `/paihang/` 解析并以每秒不超过 2 个请求创建前 200 本 `content.fetch` 任务。

验证：

- `cargo test --manifest-path catalog/Cargo.toml`：5 passed。
- `cargo test --manifest-path relay/Cargo.toml`：18 passed。
- 真实书籍 7407：正文 2,094,633 bytes、332 章，生成 ZIP 800,699 bytes；LAN capability 下载和 App debug APK 编译通过。
- Android `BookDatabaseInstrumentedTest`、`PageTurnInstrumentedTest#audioTrackingMovesToThePageContainingTheSpokenCue`：2 passed。

当前预热任务由家里 systemd 后台持续消费；目录和任务总线均已重启并 active。
