## ADDED Requirements

### Requirement: Verified offline audio package import
阅读器 SHALL 使用系统文件选择器导入工作台 ZIP，校验整章状态、章节与书籍 ID、原文 SHA-256/revision、UTF-16 原文切片、时间顺序、目录/音频哈希和媒体时长后，将文件和索引保存到本机。任一章节不合法时 MUST 不发布整包，重复导入同一版本 SHALL 幂等。

#### Scenario: Import a matching full chapter
- **WHEN** 用户选择与本地书库文本匹配的完整章节听书包
- **THEN** 对应章节显示可听，多个音频版本分别保留，离线可播放，重新打开 App 后仍存在

#### Scenario: Reject preview or damaged content
- **WHEN** 包含试听、原文版本不符、锚点不符、哈希或时长错误
- **THEN** 提示导入失败，不使任一新章节出现在可听目录中，也不覆盖已有有效版本

### Requirement: Offline media session and independent progress
阅读器 SHALL 支持播放/暂停、进度拖动、前后 15 秒、后台与锁屏媒体控制，并按音频 artifact_id 保存进度。音频焦点丢失与耳机断开 SHALL 暂停播放，停止或进程退出后不得自行开声。

#### Scenario: Continue while another app is foreground
- **WHEN** 已开始播放本机章节，用户切换到桌面或锁屏
- **THEN** 前台媒体服务继续播放并提供系统媒体控制，暂停后保存当前版本的播放进度

### Requirement: Original text synchronized playback
阅读器 SHALL 使用已校验 cue 对当前朗读原文绘制逐行高亮，提供按播放位置定位原文及从当前页开听；简繁显示转换不改变原文锚点。跟随启用时 SHALL 随播放定位，手动翻页 SHALL 关闭跟随。

#### Scenario: Seek to another sentence
- **WHEN** 用户拖动播放进度并定位原文
- **THEN** 打开对应章节及 UTF-16 范围，显示当前句，字幕与实际音频时间一致

### Requirement: Workbench full chapter package selection
工作台 SHALL 以 chapter_id 与当前原文 revision 关联任务产物和本地目录，显示完整音频状态，并只将明确选中的完整产物打包供阅读器导入。

#### Scenario: Local catalogue has no server artifact array
- **WHEN** 本地目录的 artifacts 为空但同章节同 revision 有成功任务产物
- **THEN** 仍显示可用音频且能够下载听书包，旧 revision 与试听不得冒充完整成品
