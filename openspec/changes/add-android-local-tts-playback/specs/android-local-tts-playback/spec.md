## ADDED Requirements

### Requirement: First-chunk streaming playback

本地 job SHALL 在首个完整 PCM chunk 通过采样率、声道、有限值和时长校验后写入 `AudioTrack`，后续片段可以在播放期间继续生成。

#### Scenario: Start listening before the chapter is complete

- **WHEN** 首个标题或正文片段校验成功，而后续片段仍在生成
- **THEN** 播放器开始播放已发布的连续前缀，并显示后续生成状态，不等待整章完成

#### Scenario: PCM validation fails

- **WHEN** native 回调产生采样率错误、非有限值或超出时长边界的 PCM
- **THEN** 该片段被拒绝并进入失败重试流程，播放器不写入损坏的音频

### Requirement: Chapter title and text cues

章节 manifest SHALL 为章节名和正文保存有序 cue，cue 使用当前 source revision 的 UTF-16 范围和实际 PCM 样本数计算。章节名与正文之间的停顿、空格和标点规则 MUST 记录版本。

#### Scenario: Read a chapter title before the first sentence

- **WHEN** 用户从章节开头播放本地音频
- **THEN** 播放器先播放章节名 cue，再按正文 cue 播放内容，章节名可以在阅读器中被定位

#### Scenario: Keep punctuation pauses bounded

- **WHEN** 朗读文本包含空格、逗号、句号或连续标点
- **THEN** 运行时按已版本化的停顿规则生成有界时间轴，不把空格全部合并成零停顿，也不插入超过规则上限的长静音

### Requirement: Atomic local cache publication

本地片段文件和 manifest MUST 先写入临时路径并通过校验，再以不可变 local artifact ID 原子发布。章节只有在所需片段全部 ready 后才能标记完整。

#### Scenario: Publish a completed segment

- **WHEN** 一个片段的 PCM、哈希和 cue 校验均通过
- **THEN** 该片段成为可播放缓存，manifest 引用其不可变 ID，已有章节产物不被覆盖

#### Scenario: App stops during publication

- **WHEN** App 在临时文件写入后、ready 记录发布前退出
- **THEN** 下次恢复时该文件不被当作有效音频，清理或重新生成不会影响其他已发布片段

### Requirement: Reader integration and remote fallback

阅读器 SHALL 优先使用匹配的服务器产物或本地缓存，缺失时展示本地生成状态。设备能力、模型或本地性能不满足条件时，页面 MUST 提供服务器合成入口并保留明确错误原因。

#### Scenario: Choose server synthesis after a local failure

- **WHEN** 本地模型加载失败或设备连续低于 RTF 门槛
- **THEN** 播放器停止扩展本地队列，已完成本地片段仍可访问，用户可以提交服务器合成任务

#### Scenario: Resume playback after reconnecting

- **WHEN** 网络断开但本地模型和缓存可用
- **THEN** 已发布本地片段继续播放，网络恢复后不会重复提交同一 local generation ID
