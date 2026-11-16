# 听书自动续播验收

交付：`dist/shanye-0.8.6-debug.apk`，versionCode 17。构建、测试 APK 编译与 lint 通过，输出见 `build.txt`。

在 Android API 36 模拟器上手动安装 APK 后运行以下仪器测试（不卸载应用、不清除书库）：

```sh
adb shell am instrument -w \
  -e class com.kkwenxue.shanye.AudioContinuationInstrumentedTest,com.kkwenxue.shanye.AudioEntryInstrumentedTest,com.kkwenxue.shanye.AudioLibraryInstrumentedTest \
  com.kkwenxue.shanye.test/androidx.test.runner.AndroidJUnitRunner
```

执行结果：`Time: 37.366`，`OK (12 tests)`。

新增 6 项真实导入/MediaPlayer 验证：

- 自然播完后从下一章 0ms 开始，连续完成两次；不采用下一章旧的播放进度。
- 优先沿用相同克隆参考 ID（即使两个版本均显示“克隆音色”），保留 1.1×倍速、分钟定时的截止时间；播放器标题和进度同步切章。
- 屏幕锁定期间仍由后台服务切换下一章并继续播放。
- “本章结束”定时停止，不自动续播。
- 紧邻下一章未导入时停止并提示，不跳过到更后的已导入章节。
- 全书最后一章停止，不循环；没有同音色时采用已选的可用版本，忽略缺失文件。

另外通过 3 项阅读入口回归与 3 项音频库导入回归。

`player.png` 为自然完成第一章后，播放器自动显示第二章、1.1×与原定时剩余时间的截图。测试用临时书籍、导入音频和进度已清理，未提交真实小说合成任务。
