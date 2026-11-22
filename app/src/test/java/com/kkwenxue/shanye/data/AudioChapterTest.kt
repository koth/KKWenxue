package com.kkwenxue.shanye.data

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AudioChapterTest {
    @Test fun chapterStartIncludesHeadingWhileBodySeeksKeepTheirCueTimes() {
        val item = AudioChapter(
            "audio", "book", "chapter", 0, "第1章 标题", "测试音色", 6000,
            listOf(AudioCue(2000, 4000, 0, 10, "第一句正文。"),
                   AudioCue(4200, 6000, 10, 20, "第二句正文。")),
            File("unused.m4a")
        )
        assertEquals(0, item.positionFor(0))
        assertEquals(2000, item.positionFor(5))
        assertEquals(4200, item.positionFor(10))
        assertNull(item.cueAt(1000))
        assertEquals(0, item.offsetAt(1000))
        assertEquals(0, item.anchorAt(1000))
    }
}
