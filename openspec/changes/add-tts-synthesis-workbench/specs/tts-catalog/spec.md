## ADDED Requirements

### Requirement: Empty initialization and explicit content import
系统 SHALL 能在没有小说或音频的情况下初始化并提供健康服务；导入内容 MUST 由操作者显式指定来源，首版提供 SQLite 导入入口。初始化不得扫描、上传、恢复已删除内容或创建旧合成任务。

#### Scenario: Start after server content cleanup
- **WHEN** 服务在仅保留模型和环境的服务器上首次启动
- **THEN** 服务器书籍及任务列表为空，健康接口正常，本地工作台仍可显示本机书库，且没有合成任务被创建

#### Scenario: Import an explicitly selected SQLite source
- **WHEN** 操作者指定有效 SQLite 快照和来源书籍执行导入
- **THEN** 系统验证来源并事务写入书籍、章节与片段，同一来源重复导入不产生重复目录

### Requirement: Stable chapter identity and source revisions
系统 SHALL 使用 book_id、跨书籍唯一的 chapter_id 和 source_revision 标识内容；章号及 chapter_index MUST 只用于检索与排序，文本更新不得使旧产物错误指向新原文。

#### Scenario: Duplicate chapter numbers
- **WHEN** 同一本书有两个第 1169 章，其他书也有相同章号
- **THEN** 各章具有不同 chapter_id，保留各自标题、物理顺序与原文，不互相覆盖

#### Scenario: Source text changes
- **WHEN** 已导入章节的来源文本发生变化并被再次显式导入
- **THEN** 新内容产生新 source_revision，旧任务和产物仍关联原 revision，旧音频不能作为新文本的有效缓存

### Requirement: Resolve chapter selections before submission
系统 SHALL 将指定书籍内的单章、连续范围、离散范围及勾选 ID 解析为有序 chapter_ids；非法、缺失或跨书籍输入 MUST 返回明确错误，单章同号歧义必须显式消除。

#### Scenario: Select a range containing duplicate numbers
- **WHEN** 用户对某本书输入包含同号章节的有效范围
- **THEN** 返回范围内全部源章节，按 chapter_index 排序去重，并显示章号、标题与同号顺序

#### Scenario: Resolve an ambiguous single number
- **WHEN** 用户输入单个章号且存在多个同号章节
- **THEN** 返回各候选 chapter_id，在用户明确选择前不创建合成任务

#### Scenario: Reject invalid selection
- **WHEN** 范围倒置、包含不存在的章号，或所选 ID 属于其他书籍
- **THEN** 指明无效项并拒绝整个选择，不静默省略后提交

### Requirement: Canonical text and UTF-16 anchors
系统 SHALL 保存与阅读器一致的规范原文，并用 UTF-16 半开区间标识父句和语音片段；original_text 与 spoken_text 分开存储，显示简繁转换不得改变锚点。

#### Scenario: Round trip a segment with Unicode characters
- **WHEN** 原文包含繁体、代理对字符、缩进和换行，且用户切换简繁显示
- **THEN** 每个片段仍能按对应原文 revision 的 UTF-16 范围取回 original_text，父句定位不变，spoken_text 可独立转换

### Requirement: Lossless sentence and segment lineage
系统 SHALL 按阅读器终止标点规则分句并对过长句拆段，记录规范化/分句版本；拆分后 MUST 保留父句和准确原文区间，不丢失或重复覆盖需朗读的文本。

#### Scenario: Split a long sentence for synthesis
- **WHEN** 一个父句超过单段长度限制，或异常输出需要进一步拆段
- **THEN** 子段顺序覆盖父句中的全部需朗读文本，边界不切开 UTF-16 代理对，原父句身份保持可追溯

### Requirement: Local catalogue and selected job inputs
工作台 SHALL 只读加载本地阅读器 SQLite，目录、范围解析和原文预览 MUST 在本机完成；正式提交时仅将选中章节发送到服务器，服务器重新校验哈希、分句和朗读转换。预估不得持久化本地书库。

#### Scenario: Browse a local novel with an empty server
- **WHEN** 本地数据库有《噩夢使徒》且服务器没有书籍
- **THEN** 工作台展示本地书籍和全部章节，服务器目录仍为空，不上传整本数据库

#### Scenario: Submit one chapter
- **WHEN** 用户从本地书库提交单章合成
- **THEN** 服务器仅保存该章节原文版本和片段作为任务输入，不复制其他章节；原文与 UTF-16 锚点可以往返验证

#### Scenario: Estimate selected local content
- **WHEN** 用户选择章节并查看复用及耗时预估
- **THEN** 临时计算预估，不向正式数据库写入该书或任务
