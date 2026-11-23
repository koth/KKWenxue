package com.kkwenxue.shanye.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTextNormalizerTest {
    @Test
    fun removesDecorativeBracketsAndCollapsesPunctuationRuns() {
        assertEquals("山页里的故事！下一句…", TtsTextNormalizer.normalize("《山页》里的故事！！！！！【下一句……】"))
    }

    @Test
    fun keepsUsefulPauseCategories() {
        assertEquals("甲，乙；丙：丁。", TtsTextNormalizer.normalize("甲，，乙;;;丙::::丁。。。"))
    }
}
