package com.kkwenxue.shanye

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.kkwenxue.shanye.data.AudioLibrary
import com.kkwenxue.shanye.data.Book
import com.kkwenxue.shanye.data.Chapter
import com.kkwenxue.shanye.data.ReaderStore
import com.kkwenxue.shanye.reader.ReadingView
import com.kkwenxue.shanye.ui.ReaderScreen
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real navigation and window insets, including dialog and Activity focus changes. */
@RunWith(AndroidJUnit4::class)
class ReaderSystemBarsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        var passed = condition()
        while (!passed && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            passed = condition()
        }
        if (!passed) {
            val context = instrumentation.targetContext
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.filesDir,"navigation-test-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                }
            }
            val labels=mutableListOf<String>()
            fun collect(node: AccessibilityNodeInfo) {
                node.text?.toString()?.let {labels.add(it)}
                repeat(node.childCount) {node.getChild(it)?.let(::collect)}
            }
            instrumentation.uiAutomation.rootInActiveWindow?.let(::collect)
            throw AssertionError("$label: $labels")
        }
    }

    private fun click(label: String): Boolean {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let { queue.add(it) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) {
                var candidate: AccessibilityNodeInfo? = node
                repeat(8) {
                    if (candidate?.isClickable == true && candidate?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
                    candidate = candidate?.parent
                }
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let { queue.add(it) } }
        }
        return false
    }

    private fun mainBoolean(block: () -> Boolean): Boolean {
        var result = false
        instrumentation.runOnMainSync { result = block() }
        return result
    }
    private fun hasText(text: String): Boolean {
        val queue=java.util.ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let {queue.add(it)}
        while (queue.isNotEmpty()) {
            val node=queue.removeFirst()
            if (node.text?.toString() == text) return true
            repeat(node.childCount) {node.getChild(it)?.let {child -> queue.add(child)}}
        }
        return false
    }
    private fun scrollForm() {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let { queue.add(it) }
        while (queue.isNotEmpty()) {
            val node=queue.removeFirst()
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return
            repeat(node.childCount) {index -> node.getChild(index)?.let {queue.add(it)} }
        }
    }

    private fun bars(activity: MainActivity, visible: Boolean): Boolean = mainBoolean {
        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
        insets != null && activity.hasWindowFocus() &&
            insets.isVisible(WindowInsetsCompat.Type.statusBars()) == visible &&
            insets.isVisible(WindowInsetsCompat.Type.navigationBars()) == visible &&
            (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) == !visible
    }

    private fun tapReaderCenter(activity: MainActivity) {
        var x = 0f; var y = 0f
        instrumentation.runOnMainSync {
            x = activity.window.decorView.width / 2f
            y = activity.window.decorView.height / 2f
        }
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0).also {
            instrumentation.sendPointerSync(it); it.recycle()
        }
        MotionEvent.obtain(time, time + 60, MotionEvent.ACTION_UP, x, y, 0).also {
            instrumentation.sendPointerSync(it); it.recycle()
        }
    }

    @Test fun clearingSentenceSelectionByButtonOrPageTurnKeepsReaderUsable() {
        val context = instrumentation.targetContext
        val book = Book("selection-test", "选句测试", "", "", "", "", 0,
            listOf(Chapter("selection-test-1", "第一章", "这是用来验证取消选择的句子。\n\n".repeat(180))))
        fun findReader(view: View): ReadingView? {
            if (view is ReadingView) return view
            if (view is ViewGroup) repeat(view.childCount) { findReader(view.getChildAt(it))?.let { return it } }
            return null
        }
        AudioLibrary(context).use { library ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var activity: MainActivity
                scenario.onActivity { host ->
                    activity = host
                    val store = ReaderStore(context)
                    host.setContent { MaterialTheme { ReaderScreen(book, store, library, emptyList(), {}, {}) } }
                }
                lateinit var reader: ReadingView
                await("Reader is laid out") {
                    mainBoolean {
                        val view = findReader(activity.window.decorView)
                        if (view != null && view.width > 0 && view.height > 0) {
                            reader = view
                            true
                        } else false
                    }
                }
                fun press(xFraction: Float, duration: Long) {
                    val down = SystemClock.uptimeMillis()
                    fun send(action: Int) = scenario.onActivity {
                        val y = 206f * reader.resources.displayMetrics.density
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, reader.width * xFraction, y, 0)
                        try { reader.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                    send(MotionEvent.ACTION_DOWN)
                    if (duration > 0) SystemClock.sleep(duration)
                    send(MotionEvent.ACTION_UP)
                }
                fun selectSentence() {
                    press(.5f, ViewConfiguration.getLongPressTimeout().toLong() + 150)
                    await("Long press shows sentence actions") { hasText("已选中一句") }
                }

                selectSentence()
                await("Cancel selection is clickable") { click("取消选择") }
                await("Cancel clears the sentence actions") { !hasText("已选中一句") }
                selectSentence()
                press(.92f, 0)
                await("Turning the page clears the sentence actions") { !hasText("已选中一句") }
                await("Reader menu still works after deselection") {
                    if (hasText("目录")) true
                    else {
                        // A tap while the page is still animating is intentionally ignored.
                        press(.5f, 0)
                        instrumentation.waitForIdleSync()
                        hasText("目录")
                    }
                }
            }
        }
    }

    @Test fun reenterReaderAndReturnFromOverlaysAndBackgroundKeepsBarsHidden() {
        val context = instrumentation.targetContext
        val launch = Intent(context, MainActivity::class.java)
        context.startActivity(Intent(launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        lateinit var activity: MainActivity
        await("Main activity ready") { mainBoolean {
            val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull()
            if (resumed != null) activity = resumed
            resumed != null
        } }
        repeat(3) {
            await("Home shows bars") { bars(activity, true) }
            await("Open reader") { click("继续阅读") }
            await("Reader hides both bars on entry $it") { bars(activity, false) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        await("Home after repeated exits") { bars(activity, true) }
        await("Reopen reader") { click("继续阅读") }
        await("Reader hides bars again") { bars(activity, false) }

        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("Activity loses focus in background") { mainBoolean { !activity.hasWindowFocus() } }
        context.startActivity(Intent(launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("Reader restores immersive mode on resume") { bars(activity, false) }

        tapReaderCenter(activity)
        await("Open chapter dialog") { click("目录") }
        await("Chapter dialog takes focus") { mainBoolean { !activity.hasWindowFocus() } }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("Reader restores bars after chapter dialog") { bars(activity, false) }

        await("Open player from reader") { click("听书") }
        await("Player shows bars and allows screen sleep") { bars(activity, true) }
        await("Open synthesis") { click("合成与导入") }
        await("Open the current ZONOS3 voice menu") {
            if (click("稍后")) false
            else if (click("林川 · 克隆音色")) true
            else { scrollForm();false }
        }
        await("Haoran and Linchuan are both offered") { hasText("Haoran · 中文男声") && hasText("林川 · 克隆音色") }
        assertTrue("Old voices must be absent",!hasText("默认 · cwns.mp3") && !hasText("xsbbn.mp3"))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(context.filesDir,"voice-menu-0.9.9.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("Synthesis shows system bars") { bars(activity,true) }
        // Connection settings load asynchronously and may open a dialog.
        await("Close synthesis after any connection dialog") {
            if (click("稍后")) false else click("返回听书")
        }
        await("Player retains bars") { bars(activity, true) }
        await("Close player") { click("收起播放器") }
        await("Reader hides bars after player") { bars(activity, false) }
        await("Exit reader through toolbar") { click("返回书房") }
        await("Home restores normal bars") { bars(activity, true) }
        await("Enter reader one last time") { click("继续阅读") }
        await("Final reader entry is immersive") { bars(activity, false) }
    }
}
