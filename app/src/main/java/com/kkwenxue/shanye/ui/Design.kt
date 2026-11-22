package com.kkwenxue.shanye.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkwenxue.shanye.R
import com.kkwenxue.shanye.data.Book

val Paper = Color(0xFFF7F4ED)
val Ink = Color(0xFF282D29)
val Muted = Color(0xFF8B8E85)
val Accent = Color(0xFFA84E36)
val Rule = Color(0xFFE7E4DB)
val Serif = FontFamily(Font(R.font.reader_serif))

data class ReaderColors(val paper: Color, val ink: Color, val muted: Color, val panel: Color)
fun readerColors(theme: Int) = when (theme) {
    1 -> ReaderColors(Color(0xFF202622), Color(0xFFD1D3C8), Color(0xFF848C80), Color(0xFF2C342E))
    2 -> ReaderColors(Color(0xFFE6EBE0), Color(0xFF303D32), Color(0xFF788475), Color(0xFFF1F4EC))
    else -> ReaderColors(Paper, Ink, Muted, Color(0xFFFFFCF6))
}

@Composable
fun Glyph(name: String, tint: Color = Ink, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        withTransform({ scale(this@Canvas.size.width / 24f, this@Canvas.size.height / 24f, Offset.Zero) }) {
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x1, y1), Offset(x2, y2), 1.6f, StrokeCap.Round)
            fun path(vararg points: Pair<Float, Float>, close: Boolean = false) {
                val p = Path().apply { moveTo(points[0].first, points[0].second); points.drop(1).forEach { lineTo(it.first, it.second) }; if (close) close() }
                drawPath(p, tint, style = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            when (name) {
                "home" -> { path(3f to 10f, 12f to 3f, 21f to 10f); path(5f to 9f, 5f to 21f, 10f to 21f, 10f to 15f, 14f to 15f, 14f to 21f, 19f to 21f, 19f to 9f) }
                "book" -> { path(3f to 5f, 8f to 5f, 12f to 7f, 16f to 5f, 21f to 5f, 21f to 20f, 16f to 20f, 12f to 22f, 8f to 20f, 3f to 20f, close = true); line(12f,7f,12f,21f) }
                "user" -> { drawCircle(tint,4f,Offset(12f,7f),style=Stroke(1.6f)); drawArc(tint,180f,180f,false,Offset(4f,14f),Size(16f,14f),style=Stroke(1.6f)) }
                "search" -> { drawCircle(tint,7f,Offset(10f,10f),style=Stroke(1.6f)); line(15f,15f,21f,21f) }
                "arrow" -> { line(4f,12f,20f,12f); path(14f to 6f,20f to 12f,14f to 18f) }
                "back" -> path(15f to 5f,8f to 12f,15f to 19f)
                "next" -> path(9f to 5f,16f to 12f,9f to 19f)
                "collapse" -> path(5f to 9f,12f to 16f,19f to 9f)
                "previousChapter" -> { line(5f,5f,5f,19f); val p=Path().apply { moveTo(19f,5f); lineTo(8f,12f); lineTo(19f,19f); close() }; drawPath(p,tint) }
                "nextChapter" -> { line(19f,5f,19f,19f); val p=Path().apply { moveTo(5f,5f); lineTo(16f,12f); lineTo(5f,19f); close() }; drawPath(p,tint) }
                "playerPlay" -> { val p=Path().apply { moveTo(8f,4f); lineTo(21f,12f); lineTo(8f,20f); close() }; drawPath(p,tint) }
                "playerPause" -> { drawRoundRect(tint,Offset(5f,4f),Size(4f,16f),androidx.compose.ui.geometry.CornerRadius(1.5f)); drawRoundRect(tint,Offset(15f,4f),Size(4f,16f),androidx.compose.ui.geometry.CornerRadius(1.5f)) }
                "speed" -> { drawArc(tint,145f,250f,false,Offset(3f,3f),Size(18f,18f),style=Stroke(1.7f)); path(12f to 14f,17f to 7f,10f to 12f,12f to 14f); drawCircle(tint,1.4f,Offset(12f,13f)) }
                "back15" -> { drawArc(tint,35f,285f,false,Offset(4f,4f),Size(16f,16f),style=Stroke(1.7f)); path(4f to 5f,4f to 11f,9f to 8f) }
                "next15" -> { drawArc(tint,120f,285f,false,Offset(4f,4f),Size(16f,16f),style=Stroke(1.7f)); path(20f to 5f,20f to 11f,15f to 8f) }
                "play" -> path(8f to 4f,20f to 12f,8f to 20f,close=true)
                "pause" -> { line(8f,5f,8f,19f); line(16f,5f,16f,19f) }
                "audio" -> { drawArc(tint,180f,180f,false,Offset(4f,3f),Size(16f,16f),style=Stroke(1.6f)); path(4f to 11f,4f to 20f,8f to 20f,8f to 12f,4f to 12f); path(20f to 11f,20f to 20f,16f to 20f,16f to 12f,20f to 12f) }
                "close" -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
                "bookmark", "bookmarked" -> {
                    val p = Path().apply { moveTo(6f,3f); lineTo(18f,3f); lineTo(18f,21f); lineTo(12f,17f); lineTo(6f,21f); close() }
                    drawPath(p,tint,style=if(name=="bookmarked") androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.6f,join=StrokeJoin.Round))
                }
                "comment" -> {
                    val bubble = Path().apply { moveTo(4f,5f); lineTo(20f,5f); lineTo(20f,16f); lineTo(12f,16f); lineTo(7f,20f); lineTo(7f,16f); lineTo(4f,16f); close() }
                    drawPath(bubble,tint,style=Stroke(1.6f,join=StrokeJoin.Round))
                    drawCircle(tint,1f,Offset(8f,10.5f)); drawCircle(tint,1f,Offset(12f,10.5f)); drawCircle(tint,1f,Offset(16f,10.5f))
                }
                "list" -> { for(y in listOf(6f,12f,18f)) { line(8f,y,21f,y); drawCircle(tint,1f,Offset(3f,y)) } }
                "timer" -> { drawCircle(tint,8f,Offset(12f,13f),style=Stroke(1.7f)); line(12f,13f,12f,8f); line(12f,13f,15f,15f); line(9f,3f,15f,3f); line(12f,3f,12f,5f) }
                "more" -> { drawCircle(tint,1.8f,Offset(5f,12f)); drawCircle(tint,1.8f,Offset(12f,12f)); drawCircle(tint,1.8f,Offset(19f,12f)) }
                "sun" -> { drawCircle(tint,4f,Offset(12f,12f),style=Stroke(1.5f)); for(i in 0..7) { val a=i*Math.PI/4; line((12+7*kotlin.math.cos(a)).toFloat(),(12+7*kotlin.math.sin(a)).toFloat(),(12+10*kotlin.math.cos(a)).toFloat(),(12+10*kotlin.math.sin(a)).toFloat()) } }
                "moon" -> { val p=Path().apply { moveTo(19f,15f); cubicTo(10f,19f,5f,10f,9f,3f); cubicTo(-1f,7f,3f,23f,15f,21f); cubicTo(18f,20f,20f,17f,19f,15f) }; drawPath(p,tint,style=Stroke(1.6f)) }
                "check" -> path(5f to 12f,10f to 17f,20f to 6f)
                "mountain" -> { path(2f to 19f,9f to 5f,14f to 14f,18f to 9f,23f to 19f); line(5f,22f,21f,22f) }
                "minus" -> line(5f,12f,19f,12f)
                "plus" -> { line(5f,12f,19f,12f); line(12f,5f,12f,19f) }
                "download" -> { line(12f,3f,12f,16f); path(7f to 11f,12f to 16f,17f to 11f); path(4f to 17f,4f to 21f,20f to 21f,20f to 17f) }
                "refresh" -> { drawArc(tint,35f,285f,false,Offset(4f,4f),Size(16f,16f),style=Stroke(1.7f)); path(18f to 4f,20f to 10f,14f to 8f) }
                "leaf" -> { val p = Path().apply { moveTo(5f,20f); cubicTo(1f,8f,9f,4f,21f,3f); cubicTo(20f,18f,13f,22f,5f,20f) }; drawPath(p,tint,style=Stroke(1.5f)); line(5f,20f,16f,9f) }
            }
        }
    }
}

@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier) {
    // Online books do not carry a remote image in the manifest.  Give each
    // title a stable editorial cover instead of showing the same placeholder
    // art for every book.  The palette is derived from the stable book id, so
    // it does not change when the manifest is refreshed or the app restarts.
    val style = remember(book.id) { (book.id.hashCode() and Int.MAX_VALUE) % 5 }
    val palettes = listOf(
        Triple(Color(0xFF243A42), Color(0xFFB7D0C1), Color(0xFFF3E4C2)),
        Triple(Color(0xFF4A2F3B), Color(0xFFD99B83), Color(0xFFF5DCC8)),
        Triple(Color(0xFF2F3B2F), Color(0xFFB9C98C), Color(0xFFF0E8C8)),
        Triple(Color(0xFF3B3553), Color(0xFFAAB3E4), Color(0xFFF0E9D9)),
        Triple(Color(0xFF8A4E32), Color(0xFFE6B27F), Color(0xFFFFE2B8))
    )
    val (base, accent, light) = palettes[style]
    BoxWithConstraints(modifier.clip(RoundedCornerShape(3.dp)).background(base)) {
        val coverWidth = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val w=size.width; val h=size.height
            drawRect(Brush.verticalGradient(listOf(base.lighten(.12f),base,Color.Black.copy(.18f))),size=Size(w,h))
            // Fine grain and a restrained geometric motif keep the cover from
            // looking like a random generated poster at thumbnail size.
            for (i in 0..10) {
                val x=w*(i/10f)
                drawLine(light.copy(alpha=.055f),Offset(x,0f),Offset(x+w*.16f,h),1.2f)
            }
            when (style % 3) {
                0 -> {
                    drawCircle(accent.copy(alpha=.9f),w*.16f,Offset(w*.76f,h*.39f))
                    val mountain=Path().apply { moveTo(0f,h*.77f); lineTo(w*.34f,h*.49f); lineTo(w*.55f,h*.66f); lineTo(w*.74f,h*.43f); lineTo(w,h*.75f); lineTo(w,h); lineTo(0f,h); close() }
                    drawPath(mountain,Color.Black.copy(alpha=.24f))
                    drawLine(light.copy(alpha=.7f),Offset(w*.12f,h*.82f),Offset(w*.88f,h*.82f),1.5f)
                }
                1 -> {
                    drawCircle(accent.copy(alpha=.85f),w*.27f,Offset(w*.78f,h*.67f))
                    drawArc(light.copy(alpha=.65f),190f,145f,false,Offset(w*.05f,h*.31f),Size(w*.9f,h*.55f),style=Stroke(1.5f))
                    drawArc(light.copy(alpha=.28f),190f,145f,false,Offset(w*.14f,h*.39f),Size(w*.72f,h*.42f),style=Stroke(1f))
                }
                else -> {
                    for (i in 0..3) {
                        val p=Path().apply { moveTo(0f,h*(.78f-i*.08f)); cubicTo(w*.22f,h*(.57f-i*.08f),w*.63f,h*(.93f-i*.08f),w,h*(.66f-i*.08f)); lineTo(w,h); lineTo(0f,h); close() }
                        drawPath(p,accent.copy(alpha=.12f+i*.035f))
                    }
                    drawOval(light.copy(alpha=.75f),Offset(w*.48f,h*.31f),Size(w*.35f,h*.08f))
                }
            }
            drawRect(light.copy(alpha=.65f),topLeft=Offset(w*.12f,h*.14f),size=Size(w*.76f,1.2f))
            drawRect(light.copy(alpha=.28f),topLeft=Offset(w*.12f,h*.86f),size=Size(w*.76f,1f))
        }
        Column(Modifier.fillMaxSize().padding(horizontal=coverWidth*.16f,vertical=coverWidth*.2f)) {
            Text("山页 · 在线书库",fontSize=(coverWidth.value*.052f).sp,letterSpacing=1.2.sp,color=light.copy(.72f))
            Spacer(Modifier.weight(1f))
            Text(book.title, fontFamily=Serif,fontWeight=FontWeight.Medium,
                fontSize=(coverWidth.value*if(book.title.length>8) .13f else .18f).sp,
                lineHeight=(coverWidth.value*.22f).sp,letterSpacing=(coverWidth.value*.018f).sp,
                color=light,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(coverWidth*.055f))
            Text(book.author.ifBlank { "未知作者" },fontSize=(coverWidth.value*.058f).sp,letterSpacing=.6.sp,color=light.copy(.8f),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.weight(.55f))
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.width(coverWidth*.2f).height(2.dp).background(accent))
                Text(" FICTION",Modifier.padding(start=5.dp),fontSize=(coverWidth.value*.043f).sp,letterSpacing=1.sp,color=light.copy(.65f))
            }
        }
    }
}

private fun Color.lighten(amount: Float) = Color(
    (red + (1f - red) * amount).coerceIn(0f, 1f),
    (green + (1f - green) * amount).coerceIn(0f, 1f),
    (blue + (1f - blue) * amount).coerceIn(0f, 1f), alpha
)
