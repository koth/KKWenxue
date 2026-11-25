## 1. 身份模型

使用三层身份。所有层都必须显式保存，禁止通过拼接展示字段临时推导。

```text
books
  book_id                 内部稳定 INTEGER ID，单独自增，永不复用
  canonical_title         规范书名
  canonical_author        规范作者
  match_state             confirmed | pending | split
  created_at / updated_at

catalog_sources
  source_key              稳定来源键，例如 suduguu、sudugu520
  display_name
  base_url
  adapter_version
  enabled
  priority
  last_success_at
  last_failure_at
  last_failure_code

book_sources
  book_source_id          内部绑定 ID
  book_id                 内部书籍 ID
  source_key
  external_book_id        外站 ID，按字符串保存，兼容数字和未来非数字 ID
  canonical_url
  source_title / source_author
  normalized_title / normalized_author
  binding_state            confirmed | candidate | disabled | stale | rejected
  is_preferred
  source_revision
  latest_external_chapter_id
  last_update_at
  last_checked_at
  UNIQUE(source_key, external_book_id)
  UNIQUE(book_id, source_key)

book_match_candidates
  candidate_id
  source_key
  external_book_id
  candidate_book_id
  score
  matched_rules
  state                    pending | confirmed | rejected
  created_at / resolved_at
```

`book_id` 是 App 与目录 API 使用的书籍身份。`external_book_id` 只在来源适配器和 `book_sources` 内使用。来源绑定不能把外站 ID 直接暴露为 App 的本地书籍 ID。

## 2. 作者和书名消歧

规范化流程固定为 Unicode NFKC、全角半角统一、大小写折叠、连续空白归一、常见装饰符号清理，并保留原始值。书名清理只移除明确的站点装饰前缀或后缀，例如分类标签和连载标记。不能删除可能属于正式书名的内容。

匹配分三层。

1. `normalized_title` 和 `normalized_author` 都完全相同，且当前内部候选唯一时，生成高置信度候选。
2. 书名完全相同、作者存在轻微规范化差异，或书名存在可解释的站点装饰差异时，生成中置信度候选。
3. 只命中书名、只命中作者、模糊相似或候选超过一个时，只生成待确认候选。

系统自动确认必须同时满足唯一候选、作者和书名达到高置信度、来源详情页可访问、目录至少包含一个有效章节、最新章节标题与更新时间字段可解析。任何条件不满足都保持 `candidate`，由管理端或后续用户确认流程处理。

候选表保存 `score`、命中规则、原始字段、规范字段、生成时间和处理人。确认或拒绝后不可被下一次抓取自动改写。源站改名或作者字段变化会生成新的审计事件，不能直接把绑定迁移到另一内部书籍。

一个来源记录可以同时对应多个 `book_match_candidates`，但最多只能有一个 `confirmed` 的 `book_sources` 绑定。这样可以保留“同名同作者”的候选证据，也不会因为候选表的写入顺序改变最终归属。

## 3. 章节身份

```text
book_chapters
  chapter_id              内部稳定章节 ID
  book_id
  ordinal                 当前规范顺序
  canonical_title
  chapter_state            active | removed | ambiguous

source_chapters
  book_source_id
  external_chapter_id     来源章节 ID
  chapter_id              可为空，待确认时为空
  ordinal
  source_title
  source_url
  body_sha256
  source_revision
  last_seen_at
  UNIQUE(book_source_id, external_chapter_id)
```

首次确认来源创建内部章节。新增来源时，优先使用规范化标题、章号、前后相邻标题和目录顺序进行匹配。只有唯一候选且上下文一致时才绑定到已有 `chapter_id`。同号不同标题、缺章、重排或多个候选都进入 `ambiguous`，保留来源章节并阻止自动覆盖。

正文缓存和 manifest 必须使用 `book_source_id + external_chapter_id + source_revision`。切换来源时可以继续使用同一个内部章节，但正文内容和哈希仍按来源版本隔离。不同来源的正文不在服务端自动拼接。

## 4. 来源同步架构

每个来源实现同一接口，至少包含列表页抓取、详情页解析、章节目录解析、章节正文解析、分页规则、重定向判定和错误分类。`suduguu` 与 `sudugu520` 使用不同的 `source_key`，即使页面结构暂时相同，也不共享外站 ID 或失败状态。

同步任务按来源分片运行。每个来源有独立的列表游标、详情队列、刷新队列、速率限制、重试次数和最近成功时间。来源任务完成后，先写入来源绑定和来源章节，再运行内部书籍匹配，最后生成书籍级汇总。一个来源失败不能让另一个来源的目录变旧或被删除。

对源站响应做硬校验。HTTP 3xx 到 Google 或无关域名、404、空 HTML、详情页书名/作者缺失、目录为空、章节 ID 与详情页不一致，都标记为来源错误。错误响应只更新 `book_sources.last_failure_*` 和审计表，不更新书名、章节和版本。

来源优先级按以下顺序计算。用户显式选择、绑定确认状态、最近成功时间、目录新鲜度、连续失败次数。服务端返回推荐来源和备选来源，App 可以显式切换。来源切换只切换 `preferred_book_source_id`，不复制或重建内部书籍。

## 5. API 边界

新接口使用内部 `book_id`。

```text
GET  /v1/catalog/search?q=...
GET  /v1/catalog/books/{book_id}
GET  /v1/catalog/books/{book_id}/sources
POST /v1/catalog/books/resolve
GET  /v1/catalog/books/{book_id}/manifest?source={source_key}
GET  /v1/catalog/books/{book_id}/chapters/{chapter_id}?source={source_key}
GET  /v1/catalog/books/{book_id}/version?source={source_key}
POST /v1/catalog/books/{book_id}/update-plan?source={source_key}
```

搜索结果返回 `bookId`、规范书名、规范作者、来源摘要、推荐来源和待确认标记。来源摘要包含 `sourceKey`、外站 ID、来源标题、来源作者、状态、更新时间和目录可用性。App 保存 `bookId` 和可选的 `sourceKey`，不保存外站数字 ID 作为书籍主键。

`POST /resolve` 接受来源、外站 ID、书名和作者，返回已绑定书籍、候选列表或 `ambiguous`。当候选不唯一时返回可比较字段和匹配原因，不能返回一个猜测结果。

旧接口 `/v1/catalog/books/{external_id}` 只在迁移窗口内保留，并且必须要求 `source` 查询参数。没有来源参数时返回结构化 `source_required`，不再默认猜测当前源站。旧响应增加 `bookId` 和 `sourceKey`，方便旧 App 完成过渡。

## 6. 数据库迁移

现有 `books.id` 被视为旧来源下的外站 ID。迁移时为每一行分配新的内部 `book_id`，把旧 ID 写入 `book_sources(source_key='suduguu', external_book_id=...)`，章节迁移到内部 `book_chapters` 和 `source_chapters`。所有正文缓存、刷新请求、预取任务和内容生成记录通过内部 `book_id` 迁移。

迁移过程使用临时表、行数校验、唯一性校验和事务提交。发现相同规范作者/书名的旧行时不自动合并，先创建独立内部书籍并记录候选。迁移完成后保留旧外站 ID 索引，防止旧任务无法审计。API 切换前执行双读校验，确认内部 ID、来源绑定、章节数和最新章节一致。

Android 的 `remoteBookId` 需要改名为内部 `catalogBookId`。`remoteSource` 保存来源键。章节 ID 改为内部 `chapter_id`，正文请求同时传 `sourceKey`。数据库字段迁移期间接受旧字段读取，但新写入必须使用内部 ID 和来源键。

## 7. 一致性和安全边界

- `(source_key, external_book_id)` 永远只能对应一个内部书籍。发现第二个候选时停止自动写入，进入冲突表。
- 一个内部书籍可以绑定多个来源，但同一来源只能有一个确认绑定。
- 书名和作者匹配只用于候选生成，不作为正文请求的授权条件。
- 所有正文、manifest、更新计划都校验内部书籍、来源绑定和章节映射三者一致。
- 删除或禁用来源时保留绑定和历史版本，禁止级联删除内部书籍或阅读进度。
- 审计记录保存来源、外站 ID、内部 ID、匹配规则、操作者、前后版本和错误原因。

## 8. 验收策略

使用以下固定场景验收。

- `suduguu/203` 与 `sudugu520/407` 的作者和书名相同，最终绑定到一个内部书籍，两个来源外站 ID 均可见。
- `suduguu/203` 与新源同名但作者不同，保持两个内部书籍。
- 两个来源外站 ID 都为 `407`，生成两个来源绑定，不发生覆盖。
- 新源 `/407/` 返回 Google 重定向时，只标记 `sudugu520` 不可用，旧源仍可读。
- 新源目录少一章或章节标题重复时，来源 manifest 进入待确认，旧的确认 manifest 保持可用。
- 切换来源后，当前内部书籍、阅读进度和书签保持不变，正文缓存按来源隔离。
- 同步重试、进程重启和数据库迁移后，来源绑定不重复、内部 ID 不变化、任务可恢复。
