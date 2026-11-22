package com.kkwenxue.shanye

import com.kkwenxue.shanye.data.Book
import com.kkwenxue.shanye.data.Chapter

/** Test APK only; production installs start with an empty shelf. */
internal object TestBooks {
    val reader=Book("reader-test-fixture","阅读测试书","测试作者","测试","","",0,
        (1..3).map { Chapter("reader-test-$it","第${it}章", "山风吹过树林，远处的灯火亮了。\n\n他停下脚步，听见溪水的声音。\n\n".repeat(80)) })
}
