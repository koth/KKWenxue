package com.kkwenxue.shanye.reader

import android.graphics.Path
import android.text.Layout
import kotlin.math.max
import kotlin.math.min

/** Draw only the visible text, while keeping the original sentence/comment offsets intact. */
object SentenceHighlight {
    fun path(layout: Layout, start: Int, end: Int): Path {
        val result = Path()
        val text = layout.text
        val from = start.coerceIn(0, text.length)
        val until = end.coerceIn(from, text.length)
        if (from == until) return result
        val metrics = layout.paint.fontMetrics
        val selection = Path()
        val textLine = Path()
        for (line in layout.getLineForOffset(from)..layout.getLineForOffset(until - 1)) {
            var lineStart = max(from, layout.getLineStart(line))
            var lineEnd = min(until, layout.getLineEnd(line))
            // Newlines and paragraph indents belong to the stored anchor, not the highlight.
            while (lineStart < lineEnd && text[lineStart].isWhitespace()) lineStart++
            while (lineEnd > lineStart && text[lineEnd - 1].isWhitespace()) lineEnd--
            if (lineStart == lineEnd) continue
            selection.reset()
            layout.getSelectionPath(lineStart, lineEnd, selection)
            // The platform path includes line spacing and extends newlines to the margin.
            // Clip each line to its text width and font height, leaving the gaps clear.
            val baseline = layout.getLineBaseline(line).toFloat()
            textLine.reset()
            textLine.addRect(
                layout.getLineLeft(line), baseline + metrics.ascent,
                layout.getLineRight(line), baseline + metrics.descent, Path.Direction.CW
            )
            selection.op(textLine, Path.Op.INTERSECT)
            result.addPath(selection)
        }
        return result
    }
}
