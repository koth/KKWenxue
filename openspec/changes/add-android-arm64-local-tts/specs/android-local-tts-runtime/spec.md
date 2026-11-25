## ADDED Requirements

### Requirement: Local synthesis for a missing chapter artifact

当章节没有与当前原文 revision、声线、模型和引擎匹配的有效音频时，App SHALL 能在模型 ready 且设备通过能力检查的情况下创建本地分段合成 job。

#### Scenario: Listen to a chapter without audio

- **WHEN** 用户点击没有现成音频的章节的“听”，且本地模型与声线代码已就绪
- **THEN** App 创建本地 job，先生成当前章节的标题与首个正文片段，并把生成状态展示在播放器中

#### Scenario: Reuse an existing valid artifact

- **WHEN** 当前章节存在原文 revision、声线、模型、引擎和哈希均匹配的有效音频
- **THEN** App 直接播放已有产物，不重复启动本地推理

### Requirement: Single resident model and bounded queue

本地运行时 SHALL 采用单模型驻留和单生成队列。每个 job MUST 按章节片段顺序执行，不能同时启动多个模型实例或让不同 job 争抢同一 native context。

#### Scenario: Queue two chapters

- **WHEN** 用户连续请求两个没有音频的章节
- **THEN** 第一个 job 先获得模型上下文，第二个 job 排队显示顺序，两个 job 的片段和 manifest 不互相覆盖

#### Scenario: Cancel the current job

- **WHEN** 用户取消正在生成的章节
- **THEN** 运行时在当前 native 调用返回后停止后续片段，已经校验发布的片段继续保留并可播放

### Requirement: Stable local generation identity

每个本地片段 SHALL 保存 `book_id`、`chapter_id`、`source_revision`、segment 范围、spoken text、voice code、model revision、engine revision 和生成参数哈希。任一身份字段变化 MUST 产生新的 local generation ID。

#### Scenario: Change the voice

- **WHEN** 用户把章节声线从 `说书人一` 改为 `说书人二`
- **THEN** 新 job 使用新的生成身份并保留旧片段，播放器不会把旧声线标记为新结果

#### Scenario: Update the chapter text

- **WHEN** 在线章节的 source revision 更新
- **THEN** 新 revision 的片段进入新的缓存命名空间，旧 revision 的音频只作为历史产物读取

### Requirement: Bounded retries and resumable failure

运行时 SHALL 对单个片段的可重试错误设置上限，并在失败时记录阶段、错误分类和尝试次数。进程重启后 MUST 校验并复用已发布片段，只重新调度缺失或损坏片段。

#### Scenario: Retry a transient inference error

- **WHEN** 某片段发生被标记为可重试的 native 错误
- **THEN** App 在上限内重试该片段并更新事件，超过上限后将片段标记为需要处理，不把章节标为 complete

#### Scenario: Recover after process death

- **WHEN** App 在三个片段中完成两个后被系统回收，再次打开同一章节
- **THEN** App 校验并播放已完成片段，恢复队列只生成剩余片段
