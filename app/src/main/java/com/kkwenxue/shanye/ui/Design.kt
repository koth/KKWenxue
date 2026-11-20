package com.kkwenxue.shanye.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val base = listOf(Color(0xFFAA583E),Color(0xFF263F47),Color(0xFFE3D6BC),Color(0xFF72847B))[book.cover]
    val light = if(book.cover == 2) Color(0xFF4D5947) else Color(0xFFF4E9D2)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(3.dp)).background(base)) {
        val coverWidth = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val w=size.width; val h=size.height
            drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(.14f),Color.Transparent,Color.White.copy(.05f),Color.Transparent)),size=Size(w*.12f,h))
            when(book.cover) {
                0 -> {
                    drawCircle(Color(0xFFE6BF8E).copy(.8f),w*.13f,Offset(w*.77f,h*.51f))
                    val back=Path().apply { moveTo(0f,h*.77f); lineTo(w*.35f,h*.47f); lineTo(w*.72f,h*.82f); lineTo(w,h*.65f); lineTo(w,h); lineTo(0f,h); close() }
                    drawPath(back,Color(0xFF7C493C))
                    val front=Path().apply { moveTo(0f,h*.85f); cubicTo(w*.4f,h*.6f,w*.5f,h*.96f,w,h*.78f); lineTo(w,h); lineTo(0f,h); close() }
                    drawPath(front,Color(0xFF3B4E46))
                    for(i in 0..6) drawLine(Color(0xFFD6BC98).copy(.25f),Offset(w*.15f,h*(.87f+i*.014f)),Offset(w*.65f,h*(.87f+i*.014f)),.6f)
                }
                1 -> {
                    drawCircle(Color(0xFFE7DABC),w*.15f,Offset(w*.73f,h*.45f))
                    drawRect(Color(0xFF1C3038),Offset(w*.17f,h*.64f),Size(w*.68f,h*.36f))
                    drawRect(Color(0xFFBCA575),Offset(w*.43f,h*.76f),Size(w*.13f,h*.1f))
                    for(i in 0..9) drawLine(Color.White.copy(.08f),Offset(w*i/8f,0f),Offset(w*(i/8f-.18f),h),.7f)
                }
                2 -> {
                    val p=Path().apply { moveTo(w*.48f,h*.9f); cubicTo(w*.28f,h*.78f,w*.3f,h*.6f,w*.7f,h*.46f); cubicTo(w*.75f,h*.75f,w*.68f,h*.82f,w*.48f,h*.9f) }
                    drawPath(p,Color(0xFFA15C39)); drawLine(Color(0xFF58644D),Offset(w*.42f,h*.95f),Offset(w*.65f,h*.54f),w*.009f)
                    drawCircle(Color(0xFFFBF5E5).copy(.45f),w*.32f,Offset(w*.9f,h*.75f))
                }
                3 -> {
                    for(i in 0..2) {
                        val p=Path().apply { moveTo(0f,h*(.8f-i*.13f)); cubicTo(w*.3f,h*(.5f-i*.13f),w*.65f,h*(1f-i*.13f),w,h*(.7f-i*.13f)); lineTo(w,h); lineTo(0f,h); close() }
                        drawPath(p,listOf(Color(0xFFAAB5A6),Color(0xFF899C8A),Color(0xFF4B675E))[i].copy(.7f))
                    }
                    drawOval(Color(0xFFECE9D9).copy(.8f),Offset(w*.4f,h*.46f),Size(w*.5f,h*.06f))
                }
            }
            drawRect(Color.White.copy(.1f),topLeft=Offset(0f,0f),size=Size(w,1.5f))
        }
        Column(Modifier.padding(start=coverWidth*.13f,top=coverWidth*.16f)) {
            Text(book.title.chunked(2).joinToString("\n"),fontFamily=Serif,fontWeight=FontWeight.Medium,fontSize=(coverWidth.value*if(book.title.length>4) .175f else .21f).sp,lineHeight=(coverWidth.value*if(book.title.length>4) .26f else .31f).sp,letterSpacing=(coverWidth.value*.035f).sp,color=light)
            Spacer(Modifier.height(coverWidth*.065f))
            Text(book.author+"  / 著",fontSize=(coverWidth.value*.065f).sp,letterSpacing=1.sp,color=light.copy(.8f))
        }
        Text("SHANYE  ·  FICTION",Modifier.align(Alignment.BottomCenter).padding(bottom=coverWidth*.09f),fontSize=(coverWidth.value*.048f).sp,letterSpacing=1.sp,color=light.copy(.65f))
    }
}
