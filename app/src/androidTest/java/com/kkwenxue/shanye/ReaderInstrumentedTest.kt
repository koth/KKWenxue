package com.kkwenxue.shanye

import android.text.TextPaint
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkwenxue.shanye.data.*
import com.kkwenxue.shanye.reader.Pagination
import com.kkwenxue.shanye.reader.PageTurnStyle
import com.kkwenxue.shanye.reader.SentenceSegmentation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun paginationDoesNotLoseTextOrOverflowAcrossSizes() {
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface=context.resources.getFont(R.font.reader_serif) }
        TestBooks.reader.chapters.forEach { chapter ->
            for(fontSize in listOf(16,20,28)) for(width in listOf(264,337)) for(extra in listOf(.14f,.37f)) {
                paint.textSize=fontSize.toFloat()
                val pages=Pagination.paginate(chapter.text,paint,width,330,460,fontSize*extra)
                assertTrue("Has pages",pages.isNotEmpty())
                assertEquals(chapter.text,pages.joinToString("") { chapter.text.substring(it.start,it.end) })
                pages.forEachIndexed { index,page ->
                    assertTrue("Non-empty page",page.end>page.start)
                    assertTrue("Page fits: ${chapter.id}, $fontSize, $width, $extra, page $index, height ${page.layout.height}",page.layout.height<=if(index==0) 330 else 460)
                    assertEquals("Semantic offset locates original page",index,Pagination.pageAtOffset(pages,page.start))
                    assertEquals("Last character locates same page",index,Pagination.pageAtOffset(pages,page.end-1))
                }
            }
        }
    }

    @Test fun fontChangesKeepTheReadingAnchorOnTheNewPage() {
        val text=TestBooks.reader.chapters.first().text
        val paint=TextPaint().apply { textSize=20f; typeface=context.resources.getFont(R.font.reader_serif) }
        val original=Pagination.paginate(text,paint,337,330,460,7f)
        val anchor=original[2].start
        paint.textSize=28f
        val changed=Pagination.paginate(text,paint,264,330,460,10f)
        val page=changed[Pagination.pageAtOffset(changed,anchor)]
        assertTrue(anchor>=page.start && anchor<page.end)
    }

    @Test fun unicodePagesNeverSplitASurrogatePair() {
        val text="　　海🌊与风，人与远方。".repeat(120)
        val paint=TextPaint().apply {textSize=24f}
        val pages=Pagination.paginate(text,paint,123,160,230,6f)
        pages.dropLast(1).forEach { assertFalse(Character.isLowSurrogate(text[it.end])) }
        assertEquals(text,pages.joinToString("") {text.substring(it.start,it.end)})
    }

    @Test fun readingPreferencesAndBookmarksSurviveStoreRecreation() {
        val prefs=context.getSharedPreferences("shanye_reader",0)
        val previous=HashMap(prefs.all)
        val book=TestBooks.reader
        InstrumentationRegistry.getInstrumentation().runOnMainSync { Library.books.add(book) }
        try {
            prefs.edit().clear().commit()
            ReaderStore(context).apply {
                savePosition(book,1,123)
                changeTheme(1); changeFontSize(25); changeSpacious(false); changeSerif(false)
                changePageTurnStyle(PageTurnStyle.SLIDE)
                changeScript(book.id, ChineseScript.TRADITIONAL)
                saveComment(book.id, 1, SentenceSegmentation.split(book.chapters[1].text).first(), "这一句让我停下来。")
                toggleBookmark(Bookmark(book.id,1,123,"一段故事"))
            }
            val restored=ReaderStore(context)
            assertEquals(ReadingPosition(1,123),restored.positions[book.id])
            assertEquals(1,restored.theme); assertEquals(25,restored.fontSize)
            assertFalse(restored.spacious); assertFalse(restored.serif)
            assertEquals(PageTurnStyle.SLIDE,restored.pageTurnStyle)
            assertEquals(ChineseScript.TRADITIONAL,restored.scriptFor(book))
            assertEquals("这一句让我停下来。",restored.comments.single().body)
            assertEquals("一段故事",restored.bookmarks.single().quote)
            restored.toggleBookmark(restored.bookmarks.single())
            assertTrue(ReaderStore(context).bookmarks.isEmpty())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { Library.books.removeAll { it.id==book.id } }
            val edit=prefs.edit().clear()
            previous.forEach { (key,value) -> when(value) {
                is String -> edit.putString(key,value)
                is Int -> edit.putInt(key,value)
                is Boolean -> edit.putBoolean(key,value)
                is Long -> edit.putLong(key,value)
                is Float -> edit.putFloat(key,value)
            } }
            edit.commit()
        }
    }

    @Test fun ChineseSentenceSegmentationKeepsQuotesAndLeavesCommasInside() {
        val sentences=SentenceSegmentation.split("她说：“等我一下！”然后又问：你听见了吗？真的……我听见了。")
        assertEquals(4,sentences.size)
        assertEquals("她说：“等我一下！”",sentences[0].cleanText)
        assertEquals("然后又问：你听见了吗？",sentences[1].cleanText)
        assertEquals("真的……",sentences[2].cleanText)
        assertEquals("我听见了。",sentences[3].cleanText)
        val commaSentence=SentenceSegmentation.split("雨很大，但是他没有停下。雨衣在肩上发出沙沙声。")
        assertEquals(2,commaSentence.size)
        assertEquals("雨很大，但是他没有停下。",commaSentence.first().cleanText)
    }

    @Test fun chineseScriptRuleConversionKeepsTheSameOffsetsAndDetectsBothScripts() {
        val simplified = "这是一个测试，里面有发现、发明和后来的故事。"
        val traditional = ChineseScriptConverter.convert(simplified, ChineseScript.TRADITIONAL)
        assertEquals("這是一個測試，裡面有發現、發明和後來的故事。", traditional)
        assertEquals(simplified, ChineseScriptConverter.convert(traditional, ChineseScript.SIMPLIFIED))
        assertEquals(simplified.length, traditional.length)
        assertEquals(ChineseScript.SIMPLIFIED, ChineseScriptConverter.detect(simplified))
        assertEquals(ChineseScript.TRADITIONAL, ChineseScriptConverter.detect(traditional))
    }
}
