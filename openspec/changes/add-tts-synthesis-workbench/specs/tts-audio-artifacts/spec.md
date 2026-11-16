## ADDED Requirements

### Requirement: Verified synthesis cache and explicit regeneration
系统 SHALL 仅复用原文版本、朗读文本、规范化/分句版本、模型 revision、声音指令及有效生成参数均匹配且文件校验通过的片段；强制重合成 MUST 创建独立生成实例。

#### Scenario: Reuse preview audio for a chapter
- **WHEN** 整章任务使用与试听相同的生成配置和原文版本
- **THEN** 已校验试听片段被复用，只为缺失片段调用 GPU

#### Scenario: Change voice or detect damaged audio
- **WHEN** speaker、完整指令或文本 revision 改变，或命中片段的文件哈希不匹配
- **THEN** 不使用该片段作为有效缓存，需生成匹配的新实例，旧可用版本不被覆盖

### Requirement: CPU only export variants
系统 SHALL 以有序片段实例及哈希、成品速度、停顿、编码与索引版本标识章节导出；仅改变导出配置且原片段完整时 MUST 使用 CPU 生成新版本，无 GPU 推理。

#### Scenario: Reexport at another speed
- **WHEN** 完整章节从 1.0 倍导出改为 1.15 倍且原始 FLAC 均有效
- **THEN** 复用 FLAC，生成新 M4A 和重算的 JSON，GPU 调用数为零

### Requirement: Atomic immutable chapter publication
系统 SHALL 仅在所需片段全部有效、音频与 JSON 均通过校验后发布完整章节；产物 MUST 使用不可变 artifact_id 和路径，文件先校验后发布数据库记录。

#### Scenario: A chapter has missing audio
- **WHEN** 所选章节仍有未成功片段
- **THEN** 不发布 complete=true 的章节，试听或不完整范围必须单独标识

#### Scenario: A chapter ends with a punctuation-only segment
- **WHEN** 原文片段仅包含空白、标点或符号，例如章末的 `>`
- **THEN** 该片段明确标记 skipped，不调用模型、不消耗重试，也不阻塞其余正文完成后的整章导出；任务单独显示跳过数量，manifest 保留该片段原文与 UTF-16 范围，朗读文本的音频和定位保持不变
- **AND** 单字、数字和包含文字的短句仍必须合成成功，不能因为太短或失败而跳过；旧失败任务重试时复用已有有效正文音频；全章无朗读内容时跳过该章，不伪造空音频

#### Scenario: Regenerate while an old version plays
- **WHEN** 用户重做片段并生成新章节成品
- **THEN** 新版本获得新 artifact_id，旧链接仍返回旧音频及其配套 JSON

#### Scenario: Crash before publishing files
- **WHEN** 导出进程在写入音频或 JSON 后、发布记录前崩溃
- **THEN** 未发布文件不成为可播放成品或有效缓存，恢复流程可识别并对账

### Requirement: Consistent exported playback loudness
系统 SHALL 对新任务默认启用可关闭的成品响度均衡，在 CPU 端拼接与倍速之后执行两遍处理，目标单声道 -18 LUFS、PCM 真峰值 -2 dBTP，并验证 AAC 成品真峰值不超过 -1 dBTP。manifest MUST 记录处理版本及实测响度；处理不得改动原始 FLAC、参考音频、音色生成配置或 cue 时间轴。极短内容无法测量整体响度时使用有界峰值增益，不增加静音以凑时长。

#### Scenario: A cloned narrator produces quiet audio
- **WHEN** 用户启用统一成品音量并以相同音色和文本重新导出已有片段
- **THEN** 复用有效 FLAC，只创建新的 CPU 导出任务与不可变成品；调整成品响度，不触发新的 GPU 合成，旧成品和文字索引保持有效

#### Scenario: Export without loudness processing
- **WHEN** 用户明确关闭统一成品音量
- **THEN** 保留原音量；该导出设置有独立缓存身份，不复用响度处理后的成品冒充原音量

### Requirement: Text linked sample derived timelines
系统 SHALL 在 manifest 保存书籍、章节、原文 revision/hash、生成/导出版本、完整性、父句及片段 UTF-16 范围；cue 时间 MUST 基于倍速处理后的实际 PCM 样本数与停顿计算。

#### Scenario: Locate a sentence in an exported chapter
- **WHEN** 用户点击跨多个语音片段的原句
- **THEN** 跳至该父句首个 cue，播放时按原文范围高亮，停顿不会提前高亮下一句

#### Scenario: Verify a changed speed timeline
- **WHEN** 章节以不同倍速和停顿重新导出
- **THEN** cue 仍有序且在成品时长内，最终时间与实际 PCM 时长一致，并在已声明编码容差内匹配媒体时长

### Requirement: End to end range streaming by artifact identity
系统 SHALL 通过受控 artifact_id 提供媒体和 manifest，API 与本地代理 MUST 支持并透传 HTTP Range、206/416、Content-Range、Accept-Ranges、Content-Length 和 ETag，不允许任意文件路径访问。

#### Scenario: Seek through the local proxy
- **WHEN** 浏览器经本地代理请求有效音频字节范围
- **THEN** 收到正确 206 和范围数据，可拖动播放，无需先载入整章

#### Scenario: Reject invalid ranges and paths
- **WHEN** 请求超出文件范围，或试图以文件路径替代合法 artifact_id
- **THEN** 分别返回范围错误或受控访问错误，不读取任意服务器文件

### Requirement: Durable bounded memory download bundles
系统 SHALL 将批量 ZIP 作为可查询的 CPU 任务，按明确选定的可用产物打包 M4A、配套 JSON、目录和哈希；MUST 流式写入及下载，重名文件不得覆盖，临时包过期不得删除其音频来源。

#### Scenario: Download duplicate numbered chapters
- **WHEN** 用户打包包含两个同号章节或不同版本的选择
- **THEN** ZIP 中文件名唯一，目录清楚映射 chapter_id 与 artifact_id，各 JSON 与音频版本一致

#### Scenario: Bundle contains an unavailable artifact
- **WHEN** 所选产物中存在缺失或未发布项
- **THEN** 返回明确缺失清单，不将缺少文件的包报告为完整成功

### Requirement: Explicit validated historical artifact import
系统 SHALL 仅在操作者显式指定时导入历史产物，并校验原文映射、配置与文件；仅有 M4A/JSON 时 MUST 只登记可验证的导出产物，不伪造 FLAC 缓存或自动生成缺失片段。

#### Scenario: Import a downloaded chapter without raw clips
- **WHEN** 操作者显式导入匹配原文与元数据的 M4A 和 JSON，但没有逐段 FLAC
- **THEN** 该章节可作为导出产物使用，原始片段仍显示缺失；改变成品速度时不声称可以复用不存在的 FLAC
