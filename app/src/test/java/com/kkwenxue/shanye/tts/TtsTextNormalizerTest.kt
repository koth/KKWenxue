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

    @Test
    fun normalizesAsciiEllipsisToOneShortEllipsisMarker() {
        assertEquals("甲…乙", TtsTextNormalizer.normalize("甲...乙"))
    }

    @Test
    fun splitsNarrationAtNaturalPhraseBoundariesWithoutDroppingText() {
        val text = "他抬起头看向远处的山谷，然后慢慢地走了过去。"
        val chunks = TtsTextNormalizer.splitNarration(text)

        assertEquals(text, chunks.joinToString("") { text.substring(it.start, it.end) })
        assertEquals(listOf("他抬起头看向远处的山谷，", "然后慢慢地走了过去。"),
            chunks.map { text.substring(it.start, it.end) })
    }

    @Test
    fun keepsShortPhraseTogetherWhenThereIsNoNaturalBoundary() {
        val text = "他忽然停了下来。"
        val chunks = TtsTextNormalizer.splitNarration(text)

        assertEquals(listOf(text), chunks.map { text.substring(it.start, it.end) })
    }

    @Test
    fun keepsMediumUnpunctuatedNarrationTogether() {
        val text = "这是一段没有任何标点和连接词的连续旁白内容它应该保持在同一个合成片段中"
        val chunks = TtsTextNormalizer.splitNarration(text)

        assertEquals(text, chunks.joinToString("") { text.substring(it.start, it.end) })
        assertEquals(1, chunks.size)
    }
}
