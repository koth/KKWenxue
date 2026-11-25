## 1. 协议与远程版本模型

- [x] 1.1 定义在线书籍 binding、正文 revision、章节 manifest、update plan 和 sync job 的 JSON/SQLite 数据模型，明确 schema_version、哈希、过期时间和幂等键。
- [x] 1.2 扩展 catalog API，提供书籍版本检查接口，返回 latest chapter ID、章节数、远程 revision、更新时间和 manifest 摘要。
- [x] 1.3 扩展 catalog API，提供基于本地 revision 的 update-plan 接口，区分新增、修改、删除和需要确认的章节。
- [x] 1.4 为 catalog API 增加前置 revision 校验、任务过期、重复请求复用和阶段化错误响应；补充 Rust 单元测试和真实 HTTP fixture。

## 2. 服务端正文快照与增量包

- [x] 2.1 修改正文 worker 的内容库，使正文快照按 revision/内容哈希保存，并为章节生成稳定 remote chapter ID、标题、顺序和正文哈希。
- [x] 2.2 实现从旧快照到目标快照的增量 diff，覆盖 append-only、新章节、改章、删章和章节重排。
- [x] 2.3 实现增量 ZIP manifest 与章节包生成，使用临时文件和原子 rename，限制包大小、章节数和磁盘占用。
- [x] 2.4 接入 relay 正文任务和中转流程，确保 content update 任务可幂等创建、轮询、失败重试和过期重建。
- [x] 2.5 为 worker 增加真实/模拟测试：断点失败不发布半包、重复任务复用、章节哈希校验、增量包可重建目标版本。

## 3. Android 本地版本存储

- [x] 3.1 升级 `BookDatabase` schema，新增在线 binding、active revision、revision history、revision chapters 和 sync jobs 表，并保证旧数据库可迁移。
- [x] 3.2 将在线首次导入改为稳定 `online:<source>:<remoteBookId>` 书籍身份，保存远程版本和章节哈希；保留本地 TXT/EPUB 的现有 content ID 行为。
- [x] 3.3 实现旧在线导入书籍的显式关联流程，要求用户确认目录结果，不自动按标题覆盖或删除旧书。
- [x] 3.4 实现 revision 快照保留、最近版本回滚、临时文件清理和空间配额检查。
- [x] 3.5 增加本地存储迁移、重启恢复和回滚单元测试，验证升级失败时原书仍可读。

## 4. Android 更新客户端

- [x] 4.1 扩展 `CatalogClient`，实现版本检查、update-plan 创建、任务状态轮询、HTTP 202 中转等待和增量包下载。
- [x] 4.2 实现客户端 manifest/包校验：书籍身份、from/to revision、章节稳定 ID、顺序、正文哈希、整体 SHA-256、大小和过期时间。
- [x] 4.3 实现 SQLite 事务合并：校验通过后写入新 revision、切换 active revision、记录 sync job 完成；中途失败不得改动 active revision。
- [x] 4.4 为同一本书增加客户端并发锁和幂等恢复，支持 App 重启后继续查询、过期后重建以及已应用 revision 不重复应用。
- [x] 4.5 增加更新状态错误映射，区分离线、书源下载失败、打包失败、中转中、下载失败、校验失败和空间不足。
- [x] 4.6 实现启动/前台恢复同步协调器：批量检查在线书籍、限制 2～4 本并发、短时去重，并在网络恢复后继续任务。

## 5. 阅读进度、书签与听书兼容

- [x] 5.1 改造 `ReaderStore` 和进度存储，使阅读位置同时绑定稳定 book ID、chapter ID、revision 和 UTF-16 offset。
- [x] 5.2 实现 append-only 更新的进度/书签/评论/听书 checkpoint 保留，以及总章节数和进度百分比重算。
- [x] 5.3 实现改章时基于旧 offset 附近上下文的唯一锚点迁移，无法匹配时生成待确认状态，不静默跳章。
- [x] 5.4 让 `AudioLibrary` 按 source revision 判断音频是否匹配当前正文，旧 artifact 保留但不显示为当前章节可用音频。
- [x] 5.5 增加跨 revision 回归测试：新增章节、改章、删除章节、阅读进度、书签评论、听书 checkpoint 和旧音频失配。

## 6. 书架与更新交互

- [x] 6.1 在书架展示在线绑定、上次检查时间、最新章节和“已是最新/有更新/检查失败”状态。
- [x] 6.2 增加 App 启动/恢复前台自动检查和手动刷新，不因检查失败阻塞离线阅读；同一生命周期内避免重复请求。
- [x] 6.3 实现纯新增章节的默认自动下载、校验、合并和完成摘要；改章/删章/重排进入风险确认，不静默覆盖正文。
- [x] 6.4 增加自动更新暂停、网络策略、取消等待、失败重试、任务恢复和进度展示。
- [x] 6.5 增加更新计划风险确认页，展示新增/变更/删除章节、预计大小和“确认更新/稍后”操作。
- [x] 6.6 增加旧本关联在线书籍和更新后回滚入口，并覆盖 Compose UI 状态测试。

## 7. 部署与兼容发布

- [ ] 7.1 在测试环境部署 catalog、relay 和正文 worker 的新协议，验证真实在线书籍的版本检查、增量下载和中转。
- [x] 7.2 保留旧整本正文导入接口和旧 manifest 的只读兼容路径，确保旧 App/旧书籍不会被新服务破坏。
- [x] 7.3 增加服务端监控字段：任务阶段、失败原因、revision、包大小、重试次数和中转状态，避免把正文失败误报为 TTS 失败。
- [ ] 7.4 完成 Android 单元测试、目录/relay/worker 测试、启动自动更新、多书并发、进程被杀恢复和真实书籍验收。
- [x] 7.5 完成 release APK 构建；记录自动更新、回滚和兼容路径验证结果。
