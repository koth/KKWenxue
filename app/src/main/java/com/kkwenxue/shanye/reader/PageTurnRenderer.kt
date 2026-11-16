package com.kkwenxue.shanye.reader

import android.graphics.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

enum class PageTurnStyle(val label: String) {
    PAPER("纸页"), SLIDE("平移"), INSTANT("无动画")
}

/** Draws two cached page surfaces; no text measurement or bitmap allocation per frame. */
class PageTurnRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val foldMatrix = Matrix()
    private val foreground = Path()
    private val back = Path()
    private val shadow = Path()

    fun draw(canvas: Canvas, current: Bitmap, target: Bitmap, direction: Int, progress: Float,
             style: PageTurnStyle, paper: Int, touchY: Float) {
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0f) { canvas.drawBitmap(current, 0f, 0f, null); return }
        if (p >= 1f) { canvas.drawBitmap(target, 0f, 0f, null); return }
        when (style) {
            PageTurnStyle.PAPER -> {
                if (direction > 0) paper(canvas, current, target, p, paper, touchY)
                else paper(canvas, target, current, 1f - p, paper, touchY)
            }
            PageTurnStyle.SLIDE -> {
                val w = current.width.toFloat()
                val dx = -direction * p * w
                canvas.drawBitmap(target, dx + direction * w, 0f, null)
                canvas.drawBitmap(current, dx, 0f, null)
                val seam = if (direction > 0) w + dx else dx
                paint.shader = LinearGradient(seam - w * .045f, 0f, seam + w * .045f, 0f,
                    intArrayOf(Color.TRANSPARENT, 0x22000000, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                canvas.drawRect(seam - w * .045f, 0f, seam + w * .045f, current.height.toFloat(), paint)
                paint.shader = null
            }
            PageTurnStyle.INSTANT -> canvas.drawBitmap(target, 0f, 0f, null)
        }
    }

    private fun paper(canvas: Canvas, sheet: Bitmap, underneath: Bitmap, progress: Float,
                      paper: Int, touchY: Float) {
        val w = sheet.width.toFloat()
        val h = sheet.height.toFloat()
        val envelope = sin(progress * Math.PI).toFloat()
        val lean = (touchY / h - .5f).coerceIn(-.5f, .5f) * w * .14f * envelope
        val top = w * (1f - progress) + lean
        val bottom = w * (1f - progress) - lean
        val slope = (top - bottom) / h
        val divisor = 1f + slope * slope
        val rectangle = listOf(PointF(0f, 0f), PointF(w, 0f), PointF(w, h), PointF(0f, h))
        path(foreground, clip(rectangle, slope, top, keepLeft = true))
        // Reflect the lifted portion through the fold to form the paper's back.
        foldMatrix.setValues(floatArrayOf(
            1f - 2f / divisor, -2f * slope / divisor, 2f * top / divisor,
            -2f * slope / divisor, 1f - 2f * slope * slope / divisor, 2f * slope * top / divisor,
            0f, 0f, 1f
        ))
        path(back, clip(rectangle, slope, top, keepLeft = false))
        back.transform(foldMatrix)
        canvas.drawBitmap(underneath, 0f, 0f, null)
        val shadowWidth = min(w * .10f, w * .10f * envelope + 2f)
        paint.shader = LinearGradient(top - shadowWidth * .25f, 0f, top + shadowWidth, 0f,
            intArrayOf(0x44000000, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        shadow.rewind()
        shadow.moveTo(top, 0f); shadow.lineTo(top + shadowWidth, 0f)
        shadow.lineTo(bottom + shadowWidth, h); shadow.lineTo(bottom, h); shadow.close()
        canvas.drawPath(shadow, paint)
        paint.shader = null
        canvas.save()
        canvas.clipPath(foreground)
        canvas.drawBitmap(sheet, 0f, 0f, null)
        canvas.restore()
        canvas.save()
        canvas.clipPath(back)
        canvas.drawColor(paper)
        paint.alpha = 20
        canvas.drawBitmap(sheet, foldMatrix, paint)
        paint.alpha = 255
        val foldWidth = min(w * .33f, w * progress)
        paint.shader = LinearGradient(top - foldWidth, 0f, top + 1f, 0f,
            intArrayOf(0x18000000, 0x08FFFFFF, 0x2A000000), floatArrayOf(0f, .74f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPaint(paint)
        paint.shader = null
        canvas.restore()
        paint.color = if (Color.luminance(paper) > .3f) 0x50FFFFFF else 0x20FFFFFF
        paint.strokeWidth = 1.2f
        canvas.drawLine(top, 0f, bottom, h, paint)
        paint.color = Color.BLACK
    }

    private fun path(path: Path, points: List<PointF>) {
        path.rewind()
        if (points.isEmpty()) return
        path.moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { path.lineTo(it.x, it.y) }
        path.close()
    }

    private fun clip(points: List<PointF>, slope: Float, intercept: Float, keepLeft: Boolean): List<PointF> {
        val result = ArrayList<PointF>(5)
        fun distance(point: PointF) = point.x + slope * point.y - intercept
        fun inside(value: Float) = if (keepLeft) value <= 0f else value >= 0f
        var previous = points.last()
        var previousDistance = distance(previous)
        for (point in points) {
            val value = distance(point)
            if (inside(value) != inside(previousDistance)) {
                val denominator = previousDistance - value
                if (abs(denominator) > .00001f) {
                    val t = previousDistance / denominator
                    result.add(PointF(previous.x + (point.x - previous.x) * t,
                        previous.y + (point.y - previous.y) * t))
                }
            }
            if (inside(value)) result.add(point)
            previous = point
            previousDistance = value
        }
        return result
    }
}
