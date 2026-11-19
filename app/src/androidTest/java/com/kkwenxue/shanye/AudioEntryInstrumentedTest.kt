package com.kkwenxue.shanye

import android.os.SystemClock
import android.app.NotificationManager
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkwenxue.shanye.audio.AudioPlayback
import com.kkwenxue.shanye.data.*
import com.kkwenxue.shanye.ui.AudioPlayerScreen
import com.kkwenxue.shanye.ui.ReaderScreen
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Exercise the real player UI with an earlier chapter already playing in the media service. */
@RunWith(AndroidJUnit4::class)
class AudioEntryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val book = Book("audio-entry-test", "听书入口测试书", "山页", "测试", "", "", 0, listOf(
        Chapter("audio-entry-101", "第101章：先前的章节", "第一句。\n\n第二句。"),
        Chapter("audio-entry-102", "第102章：正在阅读", "第一句。\n\n第二句。"),
        Chapter("audio-entry-103", "第103章：尚未合成", "第一句。\n\n第二句。")))

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(80)
        }
        fail(message)
    }
    private fun capture(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
    private fun nodes(text: String): List<AccessibilityNodeInfo> {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
        val found = mutableListOf<AccessibilityNodeInfo>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) found.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
        }
        return found
    }
    private fun click(text: String): Boolean {
        for (node in nodes(text)) {
            var candidate: AccessibilityNodeInfo? = node
            repeat(8) {
                if (candidate?.isClickable == true && candidate?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
                candidate = candidate?.parent
            }
        }
        return false
    }
    private fun audioFile(): File {
        val samples = 8000 * 30
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(samples * 2)
            repeat(samples) { putShort((kotlin.math.sin(it * 2 * Math.PI * 220 / 8000) * 200).toInt().toShort()) }
        }.array()
        return File(context.cacheDir, "audio-entry-test.wav").apply { writeBytes(bytes) }
    }
    private fun item(index: Int, id: Char, file: File): AudioChapter {
        val chapter = book.chapters[index]
        val split = chapter.text.indexOf("第二句")
        return AudioChapter(id.toString().repeat(32), book.id, chapter.id, index, chapter.title, "测试音色", 30000,
            listOf(AudioCue(0,15000,0,split,chapter.text.substring(0,split)),
                AudioCue(15000,30000,split,chapter.text.length,chapter.text.substring(split))), file)
    }
    private fun withPlayer(test: (ActivityScenario<MainActivity>, AudioLibrary, List<AudioChapter>) -> Unit) {
        val file = audioFile()
        AudioLibrary(context).use { library ->
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                instrumentation.runOnMainSync { Library.addImported(book) }
                val items = listOf(item(0,'a',file),item(1,'b',file),item(1,'c',file))
                try {
                    library.select(items[2])
                    instrumentation.runOnMainSync { AudioPlayback.play(context, items[0], 0) }
                    await("Earlier chapter should start playing") { AudioPlayback.playing && AudioPlayback.current?.id == items[0].id }
                    test(activity, library, items)
                } finally {
                    instrumentation.runOnMainSync { AudioPlayback.command(context,"stop") }
                    await("Playback should stop") { AudioPlayback.current == null }
                    instrumentation.runOnMainSync { Library.books.removeAll { it.id == book.id } }
                    context.getSharedPreferences("shanye_audio",0).edit().apply {
                        book.chapters.forEach { remove("selected:${it.id}") }
                        items.forEach { remove("position:${it.id}") }
                        remove("listening:${book.id}")
                        if (AudioLibrary.lastListenedBook(AudioLibrary.playbackPreferences(context)) == book.id) remove("lastListenedBook")
                    }.commit()
                    context.getSharedPreferences("shanye_reader",0).edit()
                        .remove("${book.id}.chapter").remove("${book.id}.offset").remove("${book.id}.readAt").commit()
                }
            }
        }
        file.delete()
    }
    private fun render(activity: ActivityScenario<MainActivity>, library: AudioLibrary, items: List<AudioChapter>, chapter: Int?) {
        val entry = UUID.randomUUID().toString()
        activity.onActivity { host -> host.setContent {
            key(entry) { MaterialTheme {
                AudioPlayerScreen(if (chapter == null) null else book, library, items, {}, {},
                    initialChapterIndex=chapter, initialTextOffset=book.chapters[1].text.indexOf("第二句"), onRead={_,_ ->})
            } }
        } }
    }

    @Test fun readerChapterWinsOverEarlierPlaybackAndUsesItsPreferredVoiceAndTextAnchor() = withPlayer { activity, library, items ->
        render(activity, library, items, 1)
        await("Player must show the requested reading chapter") { nodes(book.chapters[1].title).isNotEmpty() }
        assertTrue(nodes(book.chapters[0].title).isEmpty())
        await("Opening another chapter must pause earlier playback") { !AudioPlayback.playing }
        capture("audio-entry-current.png")
        await("Requested chapter must have a play action") { click("播放听书") }
        await("Play must select the requested chapter and saved voice") { AudioPlayback.playing && AudioPlayback.current?.id == items[2].id }
        assertTrue("Playback should map to the current reading text, not the earlier chapter's position", AudioPlayback.position in 15000..18000)
    }

    @Test fun missingReaderChapterShowsEmptyStateAndSynthesisPrefillsThatChapter() = withPlayer { activity, library, items ->
        render(activity, library, items, 2)
        await("Missing requested chapter must show its own empty state") { nodes("本章暂无听书内容").isNotEmpty() }
        assertTrue(nodes(book.chapters[2].title).isNotEmpty())
        assertTrue(nodes(book.chapters[0].title).isEmpty())
        assertTrue(nodes("播放听书").isEmpty())
        await("Earlier playback should pause even when this chapter has no audio") { !AudioPlayback.playing }
        capture("audio-entry-missing.png")
        await("Synthesis action should open") { click("去合成本章") }
        await("Synthesis should show the same book") {
            click("稍后") // Dismiss optional first-run connection settings without changing credentials.
            nodes(book.title).isNotEmpty()
        }
        await("Synthesis should prefill the missing chapter's real number") { nodes("103").isNotEmpty() }
        capture("audio-entry-synthesis.png")
    }

    @Test fun generalPlayerEntryStillKeepsTheActiveSession() = withPlayer { activity, library, items ->
        render(activity, library, items, null)
        await("General player entry should keep its current chapter") { nodes(book.chapters[0].title).isNotEmpty() }
        assertTrue(AudioPlayback.playing)
        assertEquals(items[0].id, AudioPlayback.current?.id)
    }

    private fun closeReaderAudio(paused: Boolean) = withPlayer { activity, library, items ->
        instrumentation.runOnMainSync {
            AudioPlayback.command(context,"seek",5000)
            AudioPlayback.setSleepTimer(context,15)
            if (paused) AudioPlayback.command(context,"pause")
        }
        await("Playback must be ready for reader exit") {
            AudioPlayback.position >= 5000 && AudioPlayback.playing == !paused && AudioPlayback.timerMode == 15
        }
        lateinit var store: ReaderStore
        activity.onActivity { host ->
            store=ReaderStore(context)
            host.setContent { MaterialTheme { ReaderScreen(book,store,library,items,{}, {}) } }
        }
        await("Reader bar should offer an exit action") { nodes("退出听书").isNotEmpty() }
        assertTrue(nodes(if (paused) "继续听书" else "暂停听书").isNotEmpty())
        val readingPosition=store.positions[book.id]
        if (!paused) capture("reader-audio-close-before.png")
        assertTrue("Exit action must be clickable",click("退出听书"))
        await("Exit must stop the service and remove the bar") {
            AudioPlayback.current == null && nodes("退出听书").isEmpty()
        }
        assertFalse(AudioPlayback.playing)
        assertFalse(AudioPlayback.preparing)
        assertEquals(0,AudioPlayback.timerMode)
        assertEquals(0L,AudioPlayback.timerRemainingMs)
        assertTrue("Playback progress is retained",library.position(items[0]) >= 5000)
        assertTrue("Exiting does not delete the audio",items[0].file.isFile)
        assertTrue("The reader remains visible",nodes("小说正文。轻点中央打开菜单，轻点左右边缘翻页，也可左右拖动纸页，松手可完成或取消。").isNotEmpty())
        assertEquals("Closing should keep the current page",readingPosition,store.positions[book.id])
        await("Playback notification should be removed") {
            context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 201 }
        }
        if (!paused) capture("reader-audio-close-after.png")
    }

    @Test fun readerCloseStopsPlayingAudioAndKeepsProgressAndPage() = closeReaderAudio(false)
    @Test fun readerCloseAlsoExitsPausedListening() = closeReaderAudio(true)
}
