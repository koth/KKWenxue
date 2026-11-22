package com.kkwenxue.shanye

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkwenxue.shanye.data.Library
import com.kkwenxue.shanye.reader.PageInfo
import com.kkwenxue.shanye.reader.PageTurnStyle
import com.kkwenxue.shanye.reader.ReadingView
import com.kkwenxue.shanye.reader.SentenceRange
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageTurnInstrumentedTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val book=TestBooks.reader
    private val paper=0xfff7f4ed.toInt()

    private inner class ReaderHarness(val activity:Activity, val view:ReadingView) {
        val position=AtomicReference(PageInfo())
        val forwardTurns=AtomicInteger()
        val boundaries=AtomicInteger()
        private val downTime=SystemClock.uptimeMillis()
        fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
        fun idle()=instrumentation.waitForIdleSync()
        fun settle() {SystemClock.sleep(420); idle()}
        fun touch(action:Int,x:Float,time:Long,y:Float=.75f) = main {
            val event=MotionEvent.obtain(downTime,downTime+time,action,view.width*x,view.height*y,0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        fun bitmap():Bitmap {
            lateinit var bitmap:Bitmap
            main {bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))}
            return bitmap
        }
        fun capture(name:String) {
            val bitmap=bitmap()
            val directory=File(activity.getExternalFilesDir(null),"turn-preview").apply {mkdirs()}
            File(directory,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }

    private fun withReader(style:PageTurnStyle=PageTurnStyle.PAPER, block:ReaderHarness.()->Unit) {
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var harness:ReaderHarness
        instrumentation.runOnMainSync {
            val view=ReadingView(activity)
            harness=ReaderHarness(activity,view)
            view.onPosition={harness.position.set(it)}
            view.onPageTurn={harness.forwardTurns.incrementAndGet()}
            view.onBoundary={harness.boundaries.incrementAndGet()}
            activity.setContentView(view)
            view.configure(book,0,0,20,true,true,0xff282d29.toInt(),0xff8b8e85.toInt(),paper,style)
        }
        harness.idle()
        try {harness.block()} finally {instrumentation.runOnMainSync {activity.finish()}; harness.idle()}
    }

    @Test fun paperDragRendersIntermediateFramesAndCommitsExactlyOnce()=withReader {
        val original=bitmap()
        capture("paper-00.png")
        touch(MotionEvent.ACTION_DOWN,.95f,0)
        for(frame in 1..16) {
            touch(MotionEvent.ACTION_MOVE,.95f-frame*.053f,frame*35L)
            assertEquals("No progress commit while finger is down",0,position.get().index)
            capture("paper-%02d.png".format(frame))
        }
        val dragged=bitmap()
        assertFalse("Dragging must visibly change the page",original.sameAs(dragged))
        original.recycle(); dragged.recycle()
        touch(MotionEvent.ACTION_UP,.10f,600)
        settle()
        assertEquals(1,position.get().index)
        assertEquals(1,forwardTurns.get())
        capture("paper-17.png")
    }

    @Test fun shortDragAndSystemCancelReturnToTheSamePage()=withReader {
        val original=bitmap()
        touch(MotionEvent.ACTION_DOWN,.8f,0)
        touch(MotionEvent.ACTION_MOVE,.67f,200)
        touch(MotionEvent.ACTION_UP,.67f,800)
        settle()
        assertEquals(0,position.get().index)
        val afterShort=bitmap()
        assertTrue("Short slow drag springs back",original.sameAs(afterShort))
        afterShort.recycle()
        touch(MotionEvent.ACTION_DOWN,.9f,1000)
        touch(MotionEvent.ACTION_MOVE,.3f,1300)
        touch(MotionEvent.ACTION_CANCEL,.3f,1400)
        settle()
        val afterCancel=bitmap()
        assertTrue("OS cancellation must restore the page",original.sameAs(afterCancel))
        assertEquals(0,forwardTurns.get())
        original.recycle(); afterCancel.recycle()
    }

    @Test fun rapidTapsCannotSkipPagesAndBackwardTurnDoesNotIncrementForwardCount()=withReader {
        main {view.turn(1); view.turn(1); view.turn(1)}
        assertEquals("Position updates only after the animation",0,position.get().index)
        settle()
        assertEquals(1,position.get().index)
        assertEquals(1,forwardTurns.get())
        main {view.turn(-1)}
        settle()
        assertEquals(0,position.get().index)
        assertEquals(1,forwardTurns.get())
    }

    @Test fun animatedTurnsCrossChaptersInBothDirections()=withReader {
        main {view.goToOffset(-1)}
        idle()
        val last=position.get()
        main {view.turn(1)}
        settle()
        assertEquals(1,position.get().chapter)
        assertEquals(0,position.get().index)
        main {view.turn(-1)}
        settle()
        assertEquals(last,position.get())
        assertEquals(1,forwardTurns.get())
    }

    @Test fun slideModeAlsoTracksTheFingerAndSettles()=withReader(PageTurnStyle.SLIDE) {
        val original=bitmap()
        touch(MotionEvent.ACTION_DOWN,.9f,0)
        touch(MotionEvent.ACTION_MOVE,.45f,250)
        val midway=bitmap()
        assertFalse(original.sameAs(midway))
        capture("slide-midpoint.png")
        touch(MotionEvent.ACTION_UP,.45f,600)
        settle()
        assertEquals(1,position.get().index)
        original.recycle(); midway.recycle()
    }

    @Test fun boundariesAndChangingAppearanceDoNotCommitAnUnfinishedTurn()=withReader {
        main {view.turn(-1)}
        idle()
        assertEquals(1,boundaries.get())
        main {
            view.turn(1)
            view.configure(book,0,0,20,true,true,0xffd1d3c8.toInt(),0xff848c80.toInt(),0xff202622.toInt(),PageTurnStyle.PAPER)
        }
        settle()
        assertEquals(0,position.get().index)
        assertEquals(0,forwardTurns.get())
        val night=bitmap()
        assertEquals(0xff202622.toInt(),night.getPixel(0,0))
        night.recycle()
    }

    @Test fun instantModeCommitsWithoutWaitingForAnAnimation()=withReader(PageTurnStyle.INSTANT) {
        main {view.turn(1)}
        idle()
        assertEquals(1,position.get().index)
        assertEquals(1,forwardTurns.get())
    }

    @Test fun audioTrackingMovesToThePageContainingTheSpokenCue()=withReader(PageTurnStyle.INSTANT) {
        val firstPageEnd=position.get().end
        assertEquals(0,position.get().index)
        main {view.followAudioTo(0,firstPageEnd+1)}
        idle()
        assertTrue("Audio tracking should advance when the cue leaves the visible page",position.get().index>0)
        main {view.followAudioTo(1,0)}
        idle()
        assertEquals(1,position.get().chapter)
        assertEquals(0,position.get().index)
    }

    @Test fun longPressSelectsOneChineseSentenceWithoutTurningThePage()=withReader {
        val selected=AtomicReference<SentenceRange?>()
        main { view.onSentenceSelected={selected.set(it)} }
        touch(MotionEvent.ACTION_DOWN,.52f,0,.34f)
        SystemClock.sleep(620)
        assertNotNull("Long press should select the sentence under the finger",selected.get())
        assertTrue("Selection should end with Chinese terminal punctuation",selected.get()!!.cleanText.endsWith("。") || selected.get()!!.cleanText.endsWith("！") || selected.get()!!.cleanText.endsWith("？"))
        touch(MotionEvent.ACTION_UP,.52f,700,.34f)
        settle()
        assertEquals(0,position.get().index)
        assertEquals(0,forwardTurns.get())
    }
}
