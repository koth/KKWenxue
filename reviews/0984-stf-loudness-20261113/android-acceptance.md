# Android 听书接入验收

2026-11-13，山页 0.6.0（versionCode 8），Android 16 / API 36 ARM64 模拟器。

## 产物

- APK：`../../dist/shanye-0.6.0-debug.apk`，33,678,643 字节。
- 听书包：`../../dist/shanye-emeng-984-audio.zip`，8,262,140 字节。
- 文件 SHA-256：见 `android-delivery.json`。
- 包由正式服务 download-bundles 任务 `1020391e7aa64083bdd067c25409d8e3` 生成，沿用现有 ZIP 协议，没有为 Android 改写音频或时间轴。
- 984 章 artifact `2abb34e527bb4d828b53d3d3d4c5c383`，本地章节索引 982，83 个 cue，962,040 ms；原文 SHA-256 为 `5c5f31fd7392423a6c7df3982e130efd406a1d816fe872b90b822b05dde7e720`。

## 验证结果

- Android assembleDebug、assembleDebugAndroidTest、lintDebug 通过；静态检查仍有既有依赖更新及风格建议，不存在 lint error。
- 21 项仪器测试全部通过，包含既有阅读器回归、3 项导入器测试及真实 984 章验收。输出见 `android-tests.txt`。
- 导入器覆盖 Unicode UTF-16、音频/清单校验、文本版本不匹配、重复导入、已存文件损坏后重新导入修复、独立版本/位置持久化、混入试听或错误原文时整包失败。
- 实际点击 UI 中的 984 章开听，验证播放位置前进；暂停、跳转至 30 秒、继续；进入桌面与熄屏后位置仍前进，唤醒后暂停，验证保存的位置；点击「定位原文」进入正确章节并显示匹配高亮。
- 截图 `android-player.png`、`android-text.png` 已目视检查：播放器、章节版本、进度、原文定位、高亮和底部时间/电量均可见。
- 工作台产物关联单测通过，Web TypeScript 检查与生产构建通过；OpenSpec `add-reader-audio-playback` strict 校验通过。

## 使用

将听书 ZIP 传到手机，打开「听书 → 导入听书包」。导入后目录显示「可听」，可保留多个版本；也可从当前阅读页开听。首版只手动导入，无自动同步或下一章自动连播。Android 不包含服务器凭据，也不需要直接连 GPU 服务。

已在模拟器验证；实体手机、蓝牙耳机断连和电话中断尚未做硬件验收。前台媒体服务已实现音频焦点及耳机断开暂停处理。进程被终止后不自动开声，重新选择章节可从保存位置继续。
