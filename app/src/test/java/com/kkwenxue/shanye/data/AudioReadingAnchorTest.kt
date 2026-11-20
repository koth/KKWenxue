package com.kkwenxue.shanye.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioReadingAnchorTest {
    private val first = Chapter("first", "第一章", "开头。")
    private val latest = Chapter("latest", "后来", "现在听到这里。")
    private val book = Book("test-book", "测试", "", "", "", "", 0, listOf(latest, first))

    @Test fun resolvesCurrentChapterOrderAndClampsOffset() {
        val anchor = AudioReadingAnchor(book.id, latest.id, Int.MAX_VALUE)
        assertEquals(ReadingPosition(0, latest.text.length), anchor.positionIn(book))
        assertEquals(ReadingPosition(1, latest.text.length), anchor.positionIn(book.copy(chapters = listOf(first, latest))))
    }

    @Test fun doesNotApplyToAnUnrelatedBookOrMissingChapter() {
        val anchor = AudioReadingAnchor(book.id, latest.id, 3)
        assertNull(anchor.positionIn(book.copy(id = "another-book")))
        assertNull(anchor.positionIn(book.copy(chapters = listOf(first))))
    }
}
