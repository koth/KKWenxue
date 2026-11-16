package com.kkwenxue.shanye.reader

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max

data class TextPage(val start: Int, val end: Int, val layout: StaticLayout)

/** Pixel-accurate Android text layout. Offsets always refer to UTF-16 in the source chapter. */
object Pagination {
    fun paginate(text: String, paint: TextPaint, width: Int, firstHeight: Int, otherHeight: Int, lineExtra: Float): List<TextPage> {
        if(width <= 0 || firstHeight <= 0 || otherHeight <= 0) return emptyList()
        val result = mutableListOf<TextPage>()
        var start=0
        while(start < text.length) {
            val remaining=text.substring(start)
            val full=layout(remaining,paint,width,lineExtra)
            val available=if(result.isEmpty()) firstHeight else otherHeight
            var lines=0
            while(lines<full.lineCount && full.getLineBottom(lines)<=available) lines++
            lines=max(1,lines)
            fun endFor(count:Int)=if(count>=full.lineCount) text.length else start+full.getLineStart(count)
            var end=endFor(lines)
            // A terminal newline can introduce an extra empty line in a newly laid-out slice.
            // High-quality line breaking may also rebalance the last paragraph after slicing.
            // Validate the actual rendered slice, while retaining all source offsets.
            var rendered=layout(text.substring(start,end).trimEnd('\n'),paint,width,lineExtra)
            while(rendered.height>available && lines>1) {
                lines--
                end=endFor(lines)
                rendered=layout(text.substring(start,end).trimEnd('\n'),paint,width,lineExtra)
            }
            result+=TextPage(start,end,rendered)
            start=end
        }
        return result
    }

    // Layout's API-23 constant is equivalent to LineBreaker's API-29 constant,
    // and keeps the same behavior on our API-26 minimum devices.
    @SuppressLint("WrongConstant")
    private fun layout(text:String,paint:TextPaint,width:Int,extra:Float): StaticLayout =
        StaticLayout.Builder.obtain(text,0,text.length,paint,width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(extra,1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    fun pageAtOffset(pages: List<TextPage>, offset:Int):Int = pages.indexOfLast { offset >= it.start }.coerceAtLeast(0)
}
