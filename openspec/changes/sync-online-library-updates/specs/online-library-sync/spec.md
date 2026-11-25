## ADDED Requirements

### Requirement: Stable remote book identity and local revision
本地书架中的在线书籍 SHALL 保存稳定的远程书籍编号、来源标识、最后同步的远程 revision 和最新章节标识；章节 SHALL 使用远程 chapter ID 或内容寻址 ID 作为稳定身份，禁止把展示用章号或本地列表下标当作跨版本唯一 ID。

#### Scenario: Import an online book for the first time
- **WHEN** 用户从在线目录导入一本书
- **THEN** 本地书籍保存远程 book ID、来源、远程最新章节标识、正文 revision 和每章稳定 ID，后续更新不创建同书的第二个无关联书籍

#### Scenario: Keep a legacy local import readable
- **WHEN** 本地存在没有远程绑定信息的旧 TXT/EPUB 导入书
- **THEN** 书籍继续离线可读，系统不得仅凭标题自动覆盖它；用户必须显式确认关联到在线书籍后才能进入自动更新

### Requirement: Lightweight update checking
目录服务 SHALL 提供只返回书籍元数据和版本信息的检查接口；App SHALL 在每次启动或恢复到前台时检查书架上的已绑定在线书籍，用户手动刷新也 MUST 能立即触发检查；检查阶段 MUST NOT 下载整本正文。

#### Scenario: Local book is up to date
- **WHEN** 远程 latest chapter ID、chapter count、revision 与本地记录一致
- **THEN** 书架显示“已是最新”，不创建正文下载任务

#### Scenario: Remote book has new chapters
- **WHEN** 远程版本高于本地版本
- **THEN** 书架显示新增章节数量和最新章节标题，并创建后续更新计划；检查接口本身不下载整本正文

#### Scenario: Check while offline
- **WHEN** 检查更新时网络不可用
- **THEN** 保留本地书籍和阅读功能，显示上次成功检查时间及可重试状态，不清空或降级本地正文

#### Scenario: Check all online books when the app starts
- **WHEN** App 启动或从后台恢复到前台
- **THEN** 同步协调器批量检查书架上的在线绑定书籍，限制并发和请求频率，不阻塞首页、书架或离线阅读

### Requirement: Explicit update plan
系统 SHALL 为每次更新生成可校验的 update plan，包含源书籍/本地书籍身份、前置 revision、目标 revision、增删改章节列表、包大小上限和 plan 过期时间；客户端 MUST 在下载前确认该计划仍匹配本地版本。

#### Scenario: Plan contains append-only chapters
- **WHEN** 远程仅新增章节且已有章节哈希未变化
- **THEN** 计划标记为 append-only，只包含新增章节和 manifest，客户端可展示“新增 N 章”

#### Scenario: Existing chapter changed or disappeared
- **WHEN** 远程已有章节的标题/内容哈希发生变化或章节被删除
- **THEN** 计划明确列出 affected chapters 和风险，默认要求用户确认，不得把它伪装成普通追加更新

#### Scenario: Local version changed during planning
- **WHEN** 计划生成后本地书籍已被其他更新任务修改
- **THEN** 下载或合并 MUST 拒绝旧计划并要求重新检查，不得覆盖较新的本地版本

### Requirement: Server-side incremental package and relay transfer
正文 SHALL 由服务端从书源抓取、分章、校验并生成增量包；App MUST 只从目录服务/relay 获得正文包，不得访问书源或正文 worker 的直连地址。增量包 SHALL 包含 manifest、前置/目标 revision、章节稳定 ID、标题、正文哈希、顺序和内容。

#### Scenario: Build a valid incremental package
- **WHEN** 服务端完成目标版本的正文抓取
- **THEN** 仅发布完整且哈希可验证的更新包，包内 manifest 能重建目标章节顺序，worker 失败或中断时不得发布半成品

#### Scenario: Relay upload is pending
- **WHEN** 正文已在服务端生成但 relay 尚未完成中转
- **THEN** API 返回可轮询的任务状态，App 显示“正在中转”，不把 HTTP 202 或状态 JSON 当作正文包导入

#### Scenario: Worker or source download fails
- **WHEN** 书源连接中断、章节解析失败或 worker 不可用
- **THEN** 任务进入可重试失败状态并返回用户可理解的阶段错误；现有本地版本保持可读

### Requirement: Atomic local merge and rollback
客户端 SHALL 在临时目录和数据库事务中校验并合并更新；只有 manifest、前置 revision、所有章节哈希、章节顺序和包哈希全部通过后才切换本地 active revision。合并前 MUST 保留至少一个可回滚的上一版本。

#### Scenario: Apply an append-only update
- **WHEN** 下载包的前置 revision 等于本地 active revision 且所有新增章节合法
- **THEN** 一次事务加入新章节、更新版本元数据并切换 active revision；应用中断时旧版本仍完整可读

#### Scenario: Reject a mismatched package
- **WHEN** 包的 book ID、前置 revision、章节 ID、哈希或顺序校验失败
- **THEN** 更新被拒绝，临时文件被清理，现有书籍、书签、评论和阅读进度不发生变化

#### Scenario: Roll back a bad update
- **WHEN** 用户在更新后选择回滚或更新后校验发现不可恢复错误
- **THEN** 系统切回上一 active revision，并恢复与该 revision 匹配的章节目录，不删除远程绑定信息

### Requirement: Reading progress and annotation compatibility
更新 SHALL 以稳定 book ID、chapter ID 和 UTF-16 文本锚点保存阅读进度、听书 checkpoint、书签和评论；章节未变化时锚点 MUST 保持不变。章节内容变化时系统 MUST 尝试基于短文本上下文迁移，失败则标记待确认，不得静默跳到章节开头或其他章节。

#### Scenario: Append chapters without changing old text
- **WHEN** 更新只增加新章节
- **THEN** 当前阅读章节、页内 offset、听书 checkpoint、书签和评论保持原位置，书架进度分母更新为新总字数

#### Scenario: Change an existing chapter
- **WHEN** 更新包包含当前阅读章节的旧/新文本
- **THEN** 系统按旧锚点附近上下文尝试迁移并记录迁移结果；无法唯一匹配时向用户提示“原文已变更，请重新定位”

#### Scenario: Audio artifact belongs to an older revision
- **WHEN** 本地音频 manifest 的 source revision 不等于当前章节 revision
- **THEN** 旧音频仍可保留在历史版本中，但当前章节显示为不可自动匹配，不能被当作新正文的可听音频

### Requirement: Automatic update lifecycle and user controls
App SHALL 在默认开启的情况下自动处理纯新增章节更新，并提供暂停自动更新、取消等待中的任务、重试失败任务和手动检查入口；更新任务状态 SHALL 可在退出页面、重新打开 App 和网络恢复后恢复。自动更新不得改变当前阅读页面或自动播放音频。

#### Scenario: Automatically download append-only chapters
- **WHEN** 版本检查发现远程仅新增章节、已有章节哈希未变化且自动更新未暂停
- **THEN** App 自动创建幂等更新任务并下载、校验、合并新增章节，无需用户逐本确认，完成后展示新增章节摘要

#### Scenario: Hold a risky update for confirmation
- **WHEN** 更新计划包含已有章节修改、章节删除或章节重排
- **THEN** App 不得静默应用该正文变更，而是显示风险和受影响章节，等待用户明确确认或稍后处理

#### Scenario: Pause automatic updates
- **WHEN** 用户暂停自动更新或当前网络不符合下载策略
- **THEN** App 仍可完成版本检查并显示待更新状态，但不得自动下载正文；用户恢复策略后可继续或重试

#### Scenario: User postpones an update
- **WHEN** 用户点击“稍后更新”
- **THEN** 书籍保持旧版本可读，保存待更新标记，下一次检查可再次提示但不重复创建相同任务

#### Scenario: Resume after process death
- **WHEN** App 在下载或合并前被系统杀死后重新启动
- **THEN** App 从本地任务快照和服务端状态恢复；若任务已过期则可安全重建，不重复合并已应用的 revision

#### Scenario: Automatic update does not interrupt reading or playback
- **WHEN** 用户正在阅读或播放一本书时，后台自动更新完成
- **THEN** 当前页面、阅读位置、播放状态和当前音频队列保持不变；仅在用户主动操作后切换到新章节或重新生成音频

### Requirement: Update isolation and resource limits
同步 SHALL 使用书籍级幂等键和版本前置条件，限制单包大小、章节数和临时磁盘占用；同一本书的并发更新 MUST 合并为一个任务，不同书籍之间不得互相覆盖数据。

#### Scenario: Duplicate update requests
- **WHEN** 用户连续点击更新或多个入口同时请求同一版本
- **THEN** 服务端复用同一个 update plan/task，客户端只下载一次，最终只应用一次

#### Scenario: Package exceeds local limits
- **WHEN** 更新包超过 App 的大小、章节数或剩余空间限制
- **THEN** 更新在提交前失败并给出清晰提示，旧版本不受影响，临时文件被清理
