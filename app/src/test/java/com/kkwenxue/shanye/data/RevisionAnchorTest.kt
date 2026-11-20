package com.kkwenxue.shanye.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RevisionAnchorTest {
    private val source = CatalogBook(42, "测试书", "作者", "文学", "", 0, 3, "", "", 100)
    private val first = "第1章 开头\n这是第一章的正文。\n\n"
    private val paragraph = "😀林远见到了一盏明亮的灯，远方的海浪拍打着石阶。他盼望故乡的旅客平安归来。"
    private val second = "第2章 回家\n$paragraph\n\n"
    private val third = "第3章 离别\n第二天清晨，他离开了老屋。\n"

    private fun book(revision: String, text: String) =
        BookImporter.parseCatalogText(source, text.toByteArray()).copy(activeRevision = revision)

    @Test fun appendAndReorderKeepReadingBookmarkCommentAndListeningAnchors() {
        val original = book("r1", first + second)
        val appended = book("r2", first + second + third)
        val reordered = book("r3", second + first + third)
        val oldChapter = original.chapters[1]
        val offset = oldChapter.text.indexOf("明亮")
        val reading = RevisionAnchor.capture(original, 1, offset)
        val bookmark = Bookmark(original.id, 1, offset, "明亮的灯", reading)
        val comment = SentenceComment(original.id, 1, offset, offset + 4, "明亮的灯", "我的评论",
            anchor = RevisionAnchor.capture(original, 1, offset, offset + 4))
        val listening = AudioReadingAnchor(original.id, oldChapter.id, offset, oldChapter.audioSourceRevision)
        for ((target, chapter) in listOf(appended to 1, reordered to 0)) {
            val expected = ReadingPosition(chapter, offset)
            assertEquals(expected, reading.locate(target))
            assertEquals(expected, bookmark.anchor!!.locate(target))
            assertEquals(expected, comment.anchor!!.locate(target, 4))
            assertEquals(expected, listening.positionIn(target))
            assertEquals(oldChapter.audioSourceRevision, target.chapters[chapter].audioSourceRevision)
        }
    }

    @Test fun uniqueContextMovesUtf16OffsetButDeletedOrAmbiguousTextStaysPending() {
        val original = book("r1", first + second)
        val onChapter = original.chapters[1]
        val offset = onChapter.text.indexOf("明亮")
        val anchor = RevisionAnchor.capture(original, 1, offset, offset + 4)
        val changed = book("r2", first + "第2章 回家\n新的开头。\n$paragraph")
        assertNotEquals(onChapter.audioSourceRevision, changed.chapters[1].audioSourceRevision)
        assertEquals(ReadingPosition(1, changed.chapters[1].text.indexOf("明亮")), anchor.locate(changed, 4))
        val oldAudio = AudioReadingAnchor(original.id, onChapter.id, offset, onChapter.audioSourceRevision)
        assertNull(oldAudio.positionIn(changed))
        assertNull(anchor.locate(book("r3", first + third)))
        assertNull(anchor.locate(book("r4", first + "第2章 回家\n$paragraph\n$paragraph")))
        // Old artifacts/checkpoints are valid again when that exact revision becomes active.
        assertEquals(ReadingPosition(1, offset), oldAudio.positionIn(original))
    }
}
