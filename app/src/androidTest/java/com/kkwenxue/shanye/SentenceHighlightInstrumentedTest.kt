package com.kkwenxue.shanye

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkwenxue.shanye.reader.Pagination
import com.kkwenxue.shanye.reader.SentenceHighlight
import com.kkwenxue.shanye.reader.SentenceSegmentation
import kotlin.math.ceil
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SentenceHighlightInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun region(path: Path, width: Int, height: Int) = Region().apply {
        setPath(path, Region(0, 0, width, height))
    }
    private fun assertClear(selection: Region, rect: Rect) {
        if (rect.isEmpty) return
        val overlap = Region(selection)
        overlap.op(rect, Region.Op.INTERSECT)
        assertTrue("Highlight must leave $rect clear", overlap.isEmpty)
    }

    @Test fun highlightExcludesPreviousParagraphBlankLinesIndentAndFollowingSentence() {
        val text = "上一段的末尾。\n\n　　镇子不大，从车站走到老屋，要经过一条河、两座桥和一家永远不肯换招牌的面馆。他在桥上停了下来。"
        val sentence = SentenceSegmentation.split(text)[1]
        val firstCharacter = text.indexOf('镇')
        for (size in listOf(16f, 20f, 28f)) for (extra in listOf(.14f, .37f)) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                typeface = context.resources.getFont(R.font.reader_serif)
            }
            val layout = Pagination.paginate(text, paint, 280, 1000, 1000, size * extra).single().layout
            val selection = region(SentenceHighlight.path(layout, sentence.start, sentence.end), layout.width, layout.height)
            assertFalse(selection.isEmpty)
            val firstLine = layout.getLineForOffset(firstCharacter)
            assertClear(selection, Rect(0, 0, layout.width, layout.getLineTop(firstLine)))
            val indentEnd = layout.getPrimaryHorizontal(firstCharacter).toInt()
            assertClear(selection, Rect(0, layout.getLineTop(firstLine), indentEnd - 1, layout.getLineBottom(firstLine)))
            val lastLine = layout.getLineForOffset(sentence.end - 1)
            val endX = layout.getPrimaryHorizontal(sentence.end).toInt()
            if (layout.getLineForOffset(sentence.end) == lastLine) {
                assertClear(selection, Rect(endX + 1, layout.getLineTop(lastLine), layout.width, layout.getLineBottom(lastLine)))
            }
            // A wrapped sentence must still cover its words, with clear space below every line.
            for (line in firstLine..lastLine) {
                val baseline = layout.getLineBaseline(line)
                val gapTop = ceil(baseline + paint.fontMetrics.descent).toInt()
                assertClear(selection, Rect(0, gapTop, layout.width, layout.getLineBottom(line)))
            }
            val wordY = (layout.getLineBaseline(firstLine) + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2).toInt()
            assertTrue("The selected words remain highlighted", selection.contains(indentEnd + (size / 2).toInt(), wordY))
        }
    }

    @Test fun highlightClipsSentenceAcrossPagesAndIgnoresTrailingNewlines() {
        val text = "　　" + "沿着这条路走下去，".repeat(30) + "终会抵达。\n\n　　下一段。"
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f }
        val sentence = SentenceSegmentation.split(text).first()
        val pages = Pagination.paginate(text, paint, 180, 150, 150, 9f)
        assertTrue(pages.size > 1)
        pages.filter { it.start < sentence.end }.forEach { page ->
            val selection = region(
                SentenceHighlight.path(page.layout, sentence.start - page.start, sentence.end - page.start),
                page.layout.width, page.layout.height
            )
            assertFalse("Visible sentence fragment stays selected on each page", selection.isEmpty)
        }
        val blank = Pagination.paginate("\n\n　　", paint, 180, 150, 150, 9f).first().layout
        assertTrue(SentenceHighlight.path(blank, 0, 100).isEmpty)
    }
}
