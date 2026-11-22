package com.kkwenxue.shanye.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.BatteryManager
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import com.kkwenxue.shanye.R
import com.kkwenxue.shanye.data.Book
import com.kkwenxue.shanye.data.ChineseScript
import com.kkwenxue.shanye.data.ChineseScriptConverter
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PageInfo(val index:Int=0,val count:Int=1,val start:Int=0,val end:Int=0,val chapter:Int=0)

class ReadingView(context:Context):View(context) {
    private data class Destination(val chapter: Int, val index: Int, val pages: List<TextPage>)
    private val density=resources.displayMetrics.density
    private val scaledDensity=resources.displayMetrics.scaledDensity
    private val serifTypeface=resources.getFont(R.font.reader_serif)
    private val body=TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val label=TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val title=TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val statusPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val chapterNumberPattern=Regex("^第\\s*(\\d+)\\s*章")
    private val chapterHeadingPattern=Regex("^第\\s*\\d+\\s*章\\s*[：:]?\\s*")
    private val renderer=PageTurnRenderer()
    private val touchSlop=ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFling=dp(550f)
    private val chapterCache=mutableMapOf<Int,List<TextPage>>()
    private val sentenceCache=mutableMapOf<Int,List<SentenceRange>>()
    private val displayTextCache=mutableMapOf<Int,String>()
    private var book: Book?=null
    private var pages:List<TextPage> = emptyList()
    private var page=0
    private var fontSize=20
    private var spacious=true
    private var serif=true
    private var style=PageTurnStyle.PAPER
    private var script=ChineseScript.SIMPLIFIED
    private var chapterIndex=0
    private var anchor=0
    private var textColor=0xff282d29.toInt()
    private var mutedColor=0xff8b8e85.toInt()
    private var paperColor=0xfff7f4ed.toInt()
    private var downX=0f
    private var downY=0f
    private var touchY=0f
    private var dragging=false
    private var verticalGesture=false
    private var blockedGesture=false
    private var ignoreGesture=false
    private var velocity:VelocityTracker?=null
    private var animator:ValueAnimator?=null
    private var destination:Destination?=null
    private var direction=1
    private var progress=0f
    private var currentSurface:Bitmap?=null
    private var targetSurface:Bitmap?=null
    private var selectedSentence: SentenceRange?=null
    private var audioChapter=-1
    private var audioStart=0
    private var audioEnd=0
    private var pendingAudioFollow: Pair<Int, Int>? = null
    private var longPressTriggered=false
    private var longPressRunnable: Runnable?=null
    private val statusClockUpdater=object:Runnable {
        override fun run() {
            invalidate()
            postDelayed(this,30_000L)
        }
    }
    var onPosition: (PageInfo)->Unit = {}
    var onToggleMenu: ()->Unit = {}
    var onPageTurn: ()->Unit = {}
    var onPageTurned: (PageInfo)->Unit = {}
    var onTurnStarted: ()->Unit = {}
    var onBoundary: (String)->Unit = {}
    var onChapterRequested: (Int, Int)->Unit = { _, _ -> }
    var onSentenceSelected: (SentenceRange?)->Unit = {}
    private val firstTop get() = dp(196f)
    private val otherTop get() = dp(66f)
    private val bottom get() = height-dp(62f)
    private val margin get() = dp(28f)

    init {
        isClickable=true
        isFocusable=true
        contentDescription="小说正文。轻点中央打开菜单，轻点左右边缘翻页，也可左右拖动纸页，松手可完成或取消。"
    }

    fun configure(book:Book,chapter:Int,requestedOffset:Int,fontSize:Int,spacious:Boolean,serif:Boolean,
                  ink:Int,muted:Int,paper:Int=paperColor,turnStyle:PageTurnStyle=style,script:ChineseScript=this.script) {
        val contentChanged = this.book?.chapters?.getOrNull(chapter)?.body != book.chapters.getOrNull(chapter)?.body
        val layoutChanged=contentChanged || this.book?.id!=book.id || this.fontSize!=fontSize || this.spacious!=spacious || this.serif!=serif || this.script!=script
        val chapterChanged=this.chapterIndex!=chapter
        if(layoutChanged || chapterChanged || textColor!=ink || paperColor!=paper || style!=turnStyle) {
            cancelTransition()
            clearSelection()
        }
        val savedAnchor=if(!contentChanged && !chapterChanged && this.book?.id==book.id && pages.isNotEmpty()) pages[page].start else requestedOffset
        this.book=book; chapterIndex=chapter; this.fontSize=fontSize; this.spacious=spacious; this.serif=serif
        textColor=ink; mutedColor=muted; paperColor=paper; style=turnStyle; this.script=script
        body.textSize=fontSize*scaledDensity
        body.typeface=if(serif) serifTypeface else Typeface.create("sans-serif",Typeface.NORMAL)
        body.letterSpacing=.015f
        body.color=ink
        if(layoutChanged) { chapterCache.clear(); sentenceCache.clear(); displayTextCache.clear() }
        if(layoutChanged || chapterChanged || pages.isEmpty()) {
            anchor=savedAnchor
            repaginate()
        }
        // Audio can start before AndroidView has completed its first layout. Keep the
        // requested cue and apply it as soon as the text pages exist.
        pendingAudioFollow?.let { (targetChapter, targetOffset) ->
            pendingAudioFollow = null
            followAudioTo(targetChapter, targetOffset)
        }
        invalidate()
    }

    fun goToOffset(offset:Int) {
        cancelTransition()
        anchor=offset
        if(pages.isNotEmpty()) {
            page=Pagination.pageAtOffset(pages,if(offset<0) Int.MAX_VALUE else offset)
            publish(); invalidate()
        }
    }

    /** Move the reader to the page containing the currently spoken cue. */
    fun followAudioTo(chapter: Int, offset: Int) {
        val currentBook = book
        if (currentBook == null || width <= 0 || height <= 0 || pages.isEmpty()) {
            pendingAudioFollow = chapter to offset
            return
        }
        val targetChapter = chapter.coerceIn(currentBook.chapters.indices)
        val targetPages = pagesForChapter(targetChapter)
        if (targetPages.isEmpty()) return
        cancelTransition()
        val targetPage = Pagination.pageAtOffset(targetPages, offset.coerceAtLeast(0))
        val changed = targetChapter != chapterIndex || targetPage != page
        chapterIndex = targetChapter
        pages = targetPages
        page = targetPage
        anchor = targetPages[targetPage].start
        if (changed) publish()
        invalidate()
    }

    fun turn(direction:Int) {
        if(animator!=null || dragging || pages.isEmpty()) return
        clearSelection()
        if(!prepareTurn(direction)) { notifyBoundary(direction); return }
        if(style==PageTurnStyle.INSTANT || !ValueAnimator.areAnimatorsEnabled()) finishTurn(true)
        else settle(true)
    }

    fun setAudioCue(chapter: Int, start: Int, end: Int) {
        if (audioChapter==chapter && audioStart==start && audioEnd==end) return
        audioChapter=chapter; audioStart=start; audioEnd=end
        invalidate()
    }

    private fun adjacent(direction:Int):Destination? {
        val currentBook=book ?: return null
        val next=page+direction
        if(next in pages.indices) return Destination(chapterIndex,next,pages)
        val nextChapter=chapterIndex+direction
        if(nextChapter !in currentBook.chapters.indices) return null
        val nextPages=pagesForChapter(nextChapter)
        if(nextPages.isEmpty()) return null
        return Destination(nextChapter,if(direction>0) 0 else nextPages.lastIndex,nextPages)
    }

    private fun prepareTurn(direction:Int):Boolean {
        val target=adjacent(direction) ?: return false
        this.direction=direction
        destination=target
        progress=0f
        touchY=if(dragging) downY else height*.72f
        if(style!=PageTurnStyle.INSTANT && ValueAnimator.areAnimatorsEnabled()) {
            currentSurface=surface(currentSurface,Destination(chapterIndex,page,pages))
            targetSurface=surface(targetSurface,target)
        }
        onTurnStarted()
        return true
    }

    private fun surface(previous:Bitmap?,scene:Destination):Bitmap {
        val bitmap=previous?.takeIf { it.width==width && it.height==height }
            ?: Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        drawPage(Canvas(bitmap),scene)
        return bitmap
    }

    private fun settle(complete:Boolean) {
        if(destination==null) return
        if(style==PageTurnStyle.INSTANT || !ValueAnimator.areAnimatorsEnabled()) {finishTurn(complete); return}
        val goal=if(complete) 1f else 0f
        val animation=ValueAnimator.ofFloat(progress,goal)
        animator=animation
        animation.duration=(120+200*abs(goal-progress)).toLong()
        animation.interpolator=PathInterpolator(.22f,.75f,.25f,1f)
        animation.addUpdateListener {progress=it.animatedValue as Float; invalidate()}
        animation.addListener(object:AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation:Animator) {
                if(animator===animation) {animator=null; finishTurn(complete)}
            }
        })
        animation.start()
    }

    private fun finishTurn(complete:Boolean) {
        val target=destination
        destination=null
        progress=0f
        if(complete && target!=null) {
            chapterIndex=target.chapter; pages=target.pages; page=target.index; anchor=pages[page].start
            publish(turned=true)
            if(direction>0) onPageTurn()
        }
        invalidate()
    }

    private fun cancelTransition() {
        cancelSentenceLongPress()
        animator?.apply {removeAllListeners(); removeAllUpdateListeners(); cancel()}
        animator=null
        destination=null
        progress=0f
        dragging=false
        velocity?.recycle(); velocity=null
    }

    private fun notifyBoundary(direction:Int) {
        val next = chapterIndex + direction
        if (next in (book?.chapters?.indices ?: IntRange.EMPTY)) onChapterRequested(next, if (direction < 0) -1 else 0)
        else onBoundary(if(direction<0) "已经是第一页了" else "这本故事读完了")
    }

    private fun pagesForChapter(chapter:Int):List<TextPage> {
        if(width<=0 || height<=0 || book?.chapters?.getOrNull(chapter)?.body.isNullOrBlank()) return emptyList()
        return chapterCache.getOrPut(chapter) {
            Pagination.paginate(textForChapter(chapter),body,(width-margin*2).toInt(),
                (bottom-firstTop).toInt(),(bottom-otherTop).toInt(),body.textSize*if(spacious) .37f else .14f)
        }
    }

    private fun sentencesForChapter(chapter:Int): List<SentenceRange> {
        val source = textForChapter(chapter)
        return sentenceCache.getOrPut(chapter) { SentenceSegmentation.split(source) }
    }

    private fun textForChapter(chapter: Int): String {
        val source = book?.chapters?.getOrNull(chapter)?.text ?: return ""
        return displayTextCache.getOrPut(chapter) { ChineseScriptConverter.convert(source, script) }
    }

    fun clearSelection() {
        if (selectedSentence != null) {
            selectedSentence = null
            onSentenceSelected(null)
            invalidate()
        }
    }

    private fun selectSentenceAt(x: Float, y: Float) {
        val p = pages.getOrNull(page) ?: return
        val contentTop = if (page == 0) firstTop else otherTop
        val localY = (y - contentTop).toInt()
        if (x < margin || x > width - margin || localY < 0 || localY > p.layout.height) return
        val line = p.layout.getLineForVertical(localY).coerceIn(0, p.layout.lineCount - 1)
        val localOffset = p.layout.getOffsetForHorizontal(line, (x - margin).coerceAtLeast(0f))
        val globalOffset = (p.start + localOffset).coerceIn(p.start, max(p.start, p.end - 1))
        val selected = SentenceSegmentation.findAt(sentencesForChapter(chapterIndex), globalOffset) ?: return
        selectedSentence = selected
        onSentenceSelected(selected)
        invalidate()
    }

    private fun cancelSentenceLongPress() {
        longPressRunnable?.let { removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun repaginate() {
        if(book==null || width<=0 || height<=0) return
        pages=pagesForChapter(chapterIndex)
        page=Pagination.pageAtOffset(pages,if(anchor<0) Int.MAX_VALUE else anchor).coerceAtMost((pages.size-1).coerceAtLeast(0))
        publish()
    }

    private fun publish(turned: Boolean = false) {
        val p=pages.getOrNull(page) ?: return
        val info=PageInfo(page,pages.size,p.start,p.end,chapterIndex)
        if (turned) onPageTurned(info)
        val callback=onPosition
        // A detached view can post an old page after the player has already
        // handed it a new chapter. Don't publish positions that are no longer visible.
        post { if (chapterIndex==info.chapter && page==info.index && pages.getOrNull(page)===p) callback(info) }
    }

    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        if(pages.isNotEmpty()) anchor=pages[page].start
        cancelTransition()
        chapterCache.clear()
        currentSurface=null; targetSurface=null
        repaginate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(statusClockUpdater)
        cancelTransition()
        currentSurface=null; targetSurface=null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(statusClockUpdater)
        post(statusClockUpdater)
    }

    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val current=currentSurface
        val target=targetSurface
        if(destination!=null && current!=null && target!=null && style!=PageTurnStyle.INSTANT) {
            renderer.draw(canvas,current,target,direction,progress,style,paperColor,touchY)
        } else if(pages.isNotEmpty()) drawPage(canvas,Destination(chapterIndex,page,pages))
    }

    private fun drawPage(canvas:Canvas,scene:Destination) {
        canvas.drawColor(paperColor)
        val currentBook=book ?: return
        val p=scene.pages.getOrNull(scene.index) ?: return
        val chapterTitle=currentBook.chapters[scene.chapter].title
        label.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        label.textAlign=Paint.Align.LEFT
        label.color=mutedColor; label.textSize=dp(10f); label.letterSpacing=.07f
        canvas.drawText(fitText(label,currentBook.title,width-2*margin-dp(80f),dp(10f),dp(10f)),margin,dp(31f),label)
        label.textAlign=Paint.Align.RIGHT
        canvas.drawText("${scene.chapter+1} / ${currentBook.chapters.size}",width-margin,dp(31f),label)
        label.textAlign=Paint.Align.LEFT
        if(scene.index==0) {
            label.color=0xffa35c43.toInt(); label.textSize=dp(10f); label.letterSpacing=.2f
            val number=chapterNumberPattern.find(chapterTitle)?.groupValues?.get(1) ?: "${scene.chapter+1}"
            canvas.drawText("第 $number 章",margin,dp(94f),label)
            title.typeface=serifTypeface; title.color=textColor
            val heading=chapterTitle.replace(chapterHeadingPattern,"").ifBlank {chapterTitle}
            canvas.drawText(fitText(title,heading,width-2*margin,dp(27f),dp(15f)),margin,dp(143f),title)
            label.color=mutedColor; label.strokeWidth=dp(1f)
            canvas.drawLine(margin,dp(170f),margin+dp(25f),dp(170f),label)
        }
        canvas.save()
        canvas.translate(margin,if(scene.index==0) firstTop else otherTop)
        if (scene.chapter==audioChapter && audioEnd>audioStart) {
            val from=max(audioStart,p.start)-p.start
            val until=min(audioEnd,p.end)-p.start
            if (until>from) {
                selectionPaint.color=if (paperColor==0xff202622.toInt()) 0x445E8872 else 0x22819A73
                canvas.drawPath(SentenceHighlight.path(p.layout,from,until),selectionPaint)
            }
        }
        selectedSentence?.let { selected ->
            val selectionStart = max(selected.start, p.start) - p.start
            val selectionEnd = min(selected.end - p.start, p.layout.text.length)
            if (selectionEnd > selectionStart) {
                val path = SentenceHighlight.path(p.layout, selectionStart, selectionEnd)
                selectionPaint.color = if (paperColor == 0xff202622.toInt()) 0x665E8872 else 0x55A84E36
                canvas.drawPath(path, selectionPaint)
            }
        }
        p.layout.draw(canvas)
        canvas.restore()
        label.color=mutedColor; label.textSize=dp(9f); label.letterSpacing=.02f
        val statusBaseline=height-dp(25f)
        canvas.drawText(fitText(label,chapterTitle,(width*.42f).coerceAtLeast(dp(90f)),dp(9f),dp(9f)),margin,statusBaseline,label)
        label.textAlign=Paint.Align.RIGHT
        canvas.drawText("${scene.index+1} / ${scene.pages.size}",width-margin,statusBaseline,label)
        label.textAlign=Paint.Align.LEFT
        drawStatus(canvas,statusBaseline)
    }

    private fun drawStatus(canvas:Canvas,baseline:Float) {
        label.textAlign=Paint.Align.LEFT
        label.textSize=dp(9f)
        label.letterSpacing=0f
        label.color=mutedColor
        val time=DateFormat.format("HH:mm",Date()).toString()
        val battery=readBatteryPercent()
        val batteryText="${battery}%"
        val gap=dp(9f)
        val iconWidth=dp(16f)
        val iconHeight=dp(8f)
        val iconGap=dp(4f)
        val total=label.measureText(time)+gap+iconWidth+iconGap+label.measureText(batteryText)
        var x=(width-total)/2f
        canvas.drawText(time,x,baseline,label)
        x+=label.measureText(time)+gap
        val top=baseline-iconHeight+dp(1f)
        statusPaint.color=mutedColor
        statusPaint.style=Paint.Style.STROKE
        statusPaint.strokeWidth=dp(1f)
        canvas.drawRoundRect(x,top,x+iconWidth,top+iconHeight,dp(2f),dp(2f),statusPaint)
        canvas.drawRect(x+iconWidth,top+dp(2.5f),x+iconWidth+dp(2f),top+iconHeight-dp(2.5f),statusPaint)
        statusPaint.style=Paint.Style.FILL
        val fillWidth=(iconWidth-dp(3f))*battery/100f
        if(fillWidth>0f) canvas.drawRoundRect(x+dp(1.5f),top+dp(1.5f),x+dp(1.5f)+fillWidth,top+iconHeight-dp(1.5f),dp(1f),dp(1f),statusPaint)
        canvas.drawText(batteryText,x+iconWidth+iconGap,baseline,label)
    }

    private fun readBatteryPercent():Int {
        val manager=context.getSystemService(BatteryManager::class.java)
        val value=manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return value.coerceIn(0,100)
    }

    private fun moveGesture(x:Float,y:Float) {
        if(verticalGesture || blockedGesture) return
        val dx=x-downX; val dy=y-downY
        if(!dragging) {
            if(abs(dy)>touchSlop && abs(dy)>abs(dx)) {verticalGesture=true; return}
            if(abs(dx)<=touchSlop || abs(dx)<=abs(dy)) return
            dragging=true
            if(!prepareTurn(if(dx<0) 1 else -1)) {blockedGesture=true; dragging=false; return}
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        progress=(-direction*dx/width).coerceIn(0f,1f)
        touchY=y.coerceIn(0f,height.toFloat())
        invalidate()
    }

    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignoreGesture=animator!=null
                if(ignoreGesture) return true
                downX=event.x; downY=event.y
                dragging=false; verticalGesture=false; blockedGesture=false
                longPressTriggered=false
                cancelSentenceLongPress()
                val runnable = Runnable {
                    if (!dragging && !verticalGesture && !blockedGesture && animator == null) {
                        selectSentenceAt(downX, downY)
                        longPressTriggered = selectedSentence != null
                    }
                }
                longPressRunnable = runnable
                postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
                velocity?.recycle()
                velocity=VelocityTracker.obtain().also {it.addMovement(event)}
            }
            MotionEvent.ACTION_MOVE -> if(!ignoreGesture) {
                velocity?.addMovement(event)
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) cancelSentenceLongPress()
                moveGesture(event.x,event.y)
            }
            MotionEvent.ACTION_UP -> {
                if(ignoreGesture) {ignoreGesture=false; return true}
                cancelSentenceLongPress()
                velocity?.addMovement(event)
                moveGesture(event.x,event.y)
                if (longPressTriggered) {
                    longPressTriggered = false
                } else if(dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val vx=velocity?.xVelocity ?: 0f
                    val complete=if(abs(vx)>minimumFling) -direction*vx>0f else progress>=.28f
                    dragging=false
                    settle(complete)
                } else if(blockedGesture) notifyBoundary(if(event.x<downX) 1 else -1)
                else if(!verticalGesture && abs(event.x-downX)<touchSlop && abs(event.y-downY)<touchSlop) {
                    performClick()
                    when {event.x<width*.23f -> turn(-1); event.x>width*.77f -> turn(1); else -> onToggleMenu()}
                }
                velocity?.recycle(); velocity=null
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                cancelSentenceLongPress()
                if(dragging) {dragging=false; settle(false)}
                velocity?.recycle(); velocity=null
                ignoreGesture=event.actionMasked==MotionEvent.ACTION_POINTER_DOWN
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
    override fun performClick():Boolean {super.performClick(); return true}
    private fun dp(value:Float)=value*density

    /** Web-novel chapter titles are long: shrink to fit, then ellipsize, so nothing is clipped. */
    private fun fitText(paint:TextPaint,text:String,maxWidth:Float,maxSize:Float,minSize:Float):String {
        if(maxWidth<=0f) return text
        paint.textSize=maxSize
        val wide=paint.measureText(text)
        if(wide<=maxWidth) return text
        paint.textSize=max(minSize,maxSize*maxWidth/wide)
        return if(paint.measureText(text)<=maxWidth) text
        else TextUtils.ellipsize(text,paint,maxWidth,TextUtils.TruncateAt.END).toString()
    }
}
