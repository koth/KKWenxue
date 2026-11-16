## ADDED Requirements

### Requirement: Expose actual engine capabilities
系统 SHALL 仅展示已配置模型实际支持的音色、语言及指令能力；不支持的声音克隆或 VoiceDesign 参数 MUST 在提交前被拒绝。

#### Scenario: List CustomVoice voices
- **WHEN** 工作台连接当前 Qwen3-TTS CustomVoice 引擎
- **THEN** 返回实际可用的固定音色与指令能力，未试听音色不会标成已确认，自然语言描述不会被当成新建嗓音

### Requirement: Ready to use narration presets
系统 SHALL 为当前模型支持的固定 speaker 提供独立、可直接选用的讲述预设，展示中文音色说明及原生语言；完整模型提供 9 种独立嗓音。默认选中福叔（Uncle_Fu），采用克制自然的有声小说讲述指令，成品速度为 1.0，句间停顿为 180ms。新预设 MUST 不覆盖旧 Dylan、自定义及克隆预设或历史任务配置。

#### Scenario: Open voice settings for the first time
- **WHEN** 用户首次打开工作台且尚未选择其他预设
- **THEN** 默认选中福叔的 `narrator-uncle-fu-v1` 预设，显示 1.0 倍速和 180ms 停顿，并允许先试听；内置讲述预设与用户保存或克隆预设分组展示

#### Scenario: Switch to a different narrator
- **WHEN** 用户选择 Aiden、Ryan、Serena 等另一内置讲述预设
- **THEN** 切换到该预设实际对应的独立 speaker 及讲述指令，可直接提交试听，不必另存一份才能使用；跨语种音色展示其原生语言，效果以普通话试听为准

### Requirement: Immutable preset versions and task snapshots
系统 SHALL 为预设保存不可变版本，任务必须引用提交时的版本与配置快照；编辑预设 MUST 创建新版本而不改变已提交任务。

#### Scenario: Edit a preset while a job runs
- **WHEN** 用户修改正在运行任务所用预设的语气或音色并保存
- **THEN** 新设置获得新版本，原任务继续使用原配置，后续提交可选择新版本

### Requirement: Validate all synthesis and export settings
系统 SHALL 在接收任务前校验模型、音色、预设版本及所有参数；首版成品速度仅支持 0.75–1.5，句间停顿仅支持 0–2000 的整数毫秒，非法设置不得进入队列。

#### Scenario: Reject unsupported settings
- **WHEN** 用户提交不存在的 speaker、无效版本、负停顿或范围外的成品速度
- **THEN** 返回字段级错误，不创建任务，也不以默认值悄悄替换

### Requirement: Separate generation export and playback settings
系统 SHALL 将声音生成配置、成品导出配置和浏览器播放速度分开；修改导出速度或停顿不得改变生成变体，修改播放速度不得新建服务任务。

#### Scenario: Change only export speed
- **WHEN** 用户保持文本、音色和指令不变，将成品速度从 1.0 改为 1.15
- **THEN** 已验证 FLAC 仍可复用，系统仅请求 CPU 导出新的成品和时间索引

#### Scenario: Change browser playback speed
- **WHEN** 用户调整播放器倍速
- **THEN** 仅当前播放速率变化，预设、任务和服务端音频版本均不改变

### Requirement: Reference audio voice cloning
系统 SHALL 接受用户选择的 M4A/WAV/MP3/FLAC/AAC 参考音频并解码为本地 PCM，限制文件为 50MiB、时长为 1–30 秒；使用 Qwen3-TTS Base 创建不可变克隆预设，保存参考音频哈希、逐字稿和模式。未安装完整 Base 模型时 MUST 拒绝克隆合成，不影响固定音色任务。

#### Scenario: Upload an M4A with a matching transcript
- **WHEN** 用户上传有效的短 M4A 并填写一致的逐字稿
- **THEN** 服务校验及解码音频，创建可选择的克隆预设，任务快照引用对应参考文件与 Base revision；用户可先试听，节奏效果以实际试听为准

#### Scenario: Use speaker embedding only
- **WHEN** 用户明确选择只提取音色并省略逐字稿
- **THEN** 使用 x-vector-only 模式，界面解释其节奏模仿较弱；未明确选择该模式的空逐字稿请求被拒绝

#### Scenario: Clone model is missing or downloading
- **WHEN** 参考音频已保存但完整 Base 模型尚未就绪
- **THEN** 清楚展示模型未就绪，拒绝创建克隆合成任务，不将固定音色模型误用于克隆
