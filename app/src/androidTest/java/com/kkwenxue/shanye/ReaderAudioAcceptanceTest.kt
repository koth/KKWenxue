package com.kkwenxue.shanye

import android.content.Intent
import android.os.SystemClock
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkwenxue.shanye.audio.AudioPlayback
import com.kkwenxue.shanye.data.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in smoke test with a real downloaded package, without embedding book audio in the APK. */
@RunWith(AndroidJUnit4::class)
class ReaderAudioAcceptanceTest {
    @Test fun real984PackageImportsPlaysSeeksAndResumesOffline() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val path=InstrumentationRegistry.getArguments().getString("audioBundle")
        assumeTrue("Pass -e audioBundle with a downloaded 984 ZIP for this acceptance test",path!=null)
        val context=instrumentation.targetContext
        val books=LocalBookRepository(context).load()
        Library.restoreImported(books)
        val library=AudioLibrary(context)
        library.importBundle(File(path!!).inputStream(),Library.books.toList())
        val item=library.load(Library.books.toList()).first { it.id=="2abb34e527bb4d828b53d3d3d4c5c383" }
        assertEquals(982,item.chapterIndex)
        assertEquals(83,item.cues.size)
        assertEquals(962040,item.durationMs)
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("open_audio",true))
        fun await(predicate:()->Boolean) {
            val until=SystemClock.uptimeMillis()+20000
            while (!predicate() && SystemClock.uptimeMillis()<until) SystemClock.sleep(100)
            assertTrue("Playback state did not arrive: ${AudioPlayback.error}",predicate())
        }
        fun nodesWithText(text:String):List<AccessibilityNodeInfo> {
            val queue=java.util.ArrayDeque<AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let {queue.add(it)}
            val found=mutableListOf<AccessibilityNodeInfo>()
            while(queue.isNotEmpty()) {
                val node=queue.removeFirst()
                if(node.text?.contains(text)==true || node.contentDescription?.contains(text)==true) found.add(node)
                repeat(node.childCount) { index -> node.getChild(index)?.let {queue.add(it)} }
            }
            return found
        }
        fun clickText(text:String):Boolean {
            for (node in nodesWithText(text)) {
                var candidate:AccessibilityNodeInfo?=node
                repeat(8) {
                    if(candidate?.isClickable==true && candidate?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) return true
                    candidate=candidate?.parent
                }
            }
            return false
        }
        await { clickText("播放听书") }
        await { AudioPlayback.playing && AudioPlayback.position>500 }
        instrumentation.runOnMainSync { AudioPlayback.command(context,"pause") }
        await { !AudioPlayback.playing }
        instrumentation.runOnMainSync { AudioPlayback.command(context,"seek",30000) }
        await { AudioPlayback.position in 29800..30200 }
        assertNotNull(item.cueAt(AudioPlayback.position))
        instrumentation.runOnMainSync { AudioPlayback.command(context,"resume") }
        await { AudioPlayback.playing && AudioPlayback.position>30500 }
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val backgroundPosition=AudioPlayback.position
        await { AudioPlayback.position>backgroundPosition+1200 }
        assertTrue(AudioPlayback.playing)
        val power=context.getSystemService(PowerManager::class.java)
        fun shell(command:String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {it.readBytes()}
        }
        if (power.isInteractive) shell("input keyevent KEYCODE_SLEEP")
        await { !power.isInteractive }
        val screenOffPosition=AudioPlayback.position
        await { AudioPlayback.position>screenOffPosition+1200 }
        assertTrue(AudioPlayback.playing)
        shell("input keyevent KEYCODE_WAKEUP")
        await { power.isInteractive }
        shell("wm dismiss-keyguard")
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("open_audio",true))
        instrumentation.runOnMainSync { AudioPlayback.command(context,"pause") }
        await { !AudioPlayback.playing }
        assertTrue(library.position(item)>30000)
        await { nodesWithText("定位原文").isNotEmpty() }
        SystemClock.sleep(700)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir,"reader-audio-player.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        assertTrue(clickText("定位原文"))
        SystemClock.sleep(900)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir,"reader-audio-text.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        instrumentation.runOnMainSync { AudioPlayback.command(context,"stop") }
        await { AudioPlayback.current==null }
        library.close()
    }
}
