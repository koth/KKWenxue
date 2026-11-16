# 听书跟踪原文自动翻页验收

交付：`dist/shanye-0.8.7-debug.apk`，versionCode 18。构建与 lint 通过。

这次修复把跟踪锚点从“当前语音段起点”改为“当前语音段内按播放进度估算的 UTF-16 原文位置”。一个语音段跨越多页时，播放进入后半段会切换到包含当前文字的下一页；高亮仍使用当前语音段，手动翻页仍会退出跟踪模式。

验证命令：

```sh
adb shell am instrument -w \\
  -e class com.kkwenxue.shanye.AudioLibraryInstrumentedTest,com.kkwenxue.shanye.AudioEntryInstrumentedTest,com.kkwenxue.shanye.AudioContinuationInstrumentedTest,com.kkwenxue.shanye.PageTurnInstrumentedTest,com.kkwenxue.shanye.ReaderInstrumentedTest \\
  com.kkwenxue.shanye.test/androidx.test.runner.AndroidJUnitRunner
```

结果：`Time: 59.926`，`OK (28 tests)`。覆盖长语音段的原文偏移插值、跨页定位、音频入口、锁屏续播、章节续播、分页、高亮、书库导入和阅读设置回归。
