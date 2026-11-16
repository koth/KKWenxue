## Context

服务器仅负责合成；本地 Web 持有书库并通过 SSH 代理获取产物。Android 没有服务器密钥，也无法直接使用 Mac 的 loopback 代理。现有听书 ZIP 含 catalogue.json、M4A、manifest 和哈希，适合作为首版离线传输协议。

## Decisions

- 直接导入现有 ZIP，不在 APK 内打包小说音频。SAF 只读选中文件，分块复制至临时目录，限制包大小、清单大小和项数。
- 先校验全部内容：schema、完整章节、书籍/章节 ID、原文 SHA-256、revision、逐句 UTF-16 原文切片、时间顺序、文件哈希及实际媒体时长；未知书籍、不同文本、试听和损坏包给出错误。
- 音频以不可变 artifact_id 保存，SQLite 单事务发布整包索引；同版本导入幂等，失败清理临时文件。模型、参考音频及生成环境不进入手机。
- 原文 SQLite 不迁移，音频使用 shanye_audio.db；音色版本分别保留，播放位置按 artifact_id 保存。
- 使用 Android MediaPlayer + MediaSession 的前台服务，支持锁屏/后台、焦点丢失暂停与耳机断开暂停；听书面板显示原文片段、播放控制和定位入口。
- 原文按 canonical UTF-16 cue 绘制高亮，简繁转换仍只改显示；启用跟随时自动定位播放句，手动翻页关闭跟随，避免打断主动阅读。
- Web 本地目录不含服务器 artifacts，须将任务结果按 chapter_id + revision_id 合并；preview 与完整章节分开，打包完整版本。

## Limitations

首版通过 ZIP 手动传输；不自动发现服务器成品，不自动连播下一章。最多 1,000 个版本、4 GB/包；导入需要额外临时磁盘空间。媒体服务不在进程被系统终止后自动开声，重开后可按保存位置继续。
