# Android 0.7.1 听书播放器验收

- APK：`dist/shanye-0.7.1-debug.apk`，versionCode 10。
- `assembleDebug`、`assembleDebugAndroidTest`、`lintDebug` 通过，见 `android-build.txt`。
- Pixel 3a / Android 16 模拟器完整 22 项仪器测试通过，含实际第 984 章离线播放，见 `android-tests.txt`。
- 实测 2× 播放推进、暂停时切换倍速仍保持暂停、倍速偏好持久化、定时取消、锁屏继续播放后在一分钟定时到点暂停、本章结束清除定时。
- 竞争音频焦点测试通过：其他音频开始后小说仍持续播放，小说音量临时降低，焦点释放后恢复。
- 第 984 章实测导入、播放、暂停、拖动进度、后台及锁屏继续播放、回到播放器、定位原文与播放进度保存。
- 全屏播放器、倍速面板和定时面板在模拟器上检查，见 `player.png`、`speed.png`、`timer.png`；原文定位见 `text.png`。

锁屏本身不暂停播放。只有用户暂停、定时到点、章节播放完毕或音频焦点/耳机变化等播放事件会暂停。

APK SHA-256：见发布目录中的 `shasum -a 256`；本次构建为 0.7.1。
