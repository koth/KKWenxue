package com.kkwenxue.shanye

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkwenxue.shanye.audio.AudioPlayback
import com.kkwenxue.shanye.data.*
import com.kkwenxue.shanye.ui.AudioPlayerScreen
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real MediaPlayer completion, imported files, service timers, and player UI. */
@RunWith(AndroidJUnit4::class)
class AudioContinuationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val duration = 12000
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(60)
        }
        fail("$message: ${AudioPlayback.error}")
    }
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun hasText(text: String): Boolean {
        val nodes = java.util.ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(nodes::add)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.text?.toString() == text) return true
            repeat(node.childCount) { node.getChild(it)?.let(nodes::add) }
        }
        return false
    }
    private fun wave(): ByteArray {
        val samples = 8000 * duration / 1000
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(samples * 2)
            repeat(samples) { putShort((kotlin.math.sin(it * 2 * Math.PI * 220 / 8000) * 200).toInt().toShort()) }
        }.array()
    }
    private fun bundle(book: Book, chapters: List<Int>, alternateVoice: Boolean): ByteArray {
        val bytes = ByteArrayOutputStream()
        val sound = wave()
        val entries = JSONArray()
        ZipOutputStream(bytes).use { zip ->
            fun write(name: String, data: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
            fun chapter(index: Int, speaker: String) {
                val id = UUID.randomUUID().toString().replace("-", "")
                val chapter = book.chapters[index]
                val text = chapter.text
                val hash = AudioLibrary.sha256(text.toByteArray())
                val manifest = JSONObject().put("schema_version", 2).put("complete", true)
                    .put("offsets", "UTF-16 into Android Chapter.text").put("artifact_id", id)
                    .put("book_id", book.id).put("chapter_id", chapter.id).put("duration_ms", duration)
                    .put("source_sha256", hash).put("source_revision", AudioLibrary.sha256("${chapter.id}:$hash:reader-canonical+OpenCC-t2s-v1:reader-sentence-bounded160-v1".toByteArray()))
                    .put("audio_sha256", AudioLibrary.sha256(sound)).put("profile", JSONObject().put("speaker", speaker))
                    .put("cues", JSONArray().put(JSONObject().put("start_ms", 0).put("end_ms", duration)
                        .put("start_utf16", 0).put("end_utf16", text.length).put("original_text", text)))
                    .toString().toByteArray()
                write("$id.json", manifest); write("$id.m4a", sound)
                entries.put(JSONObject().put("artifact_id", id).put("chapter_id", chapter.id)
                    .put("audio", "$id.m4a").put("manifest", "$id.json")
                    .put("audio_sha256", AudioLibrary.sha256(sound)).put("manifest_sha256", AudioLibrary.sha256(manifest)))
            }
            chapters.forEach { chapter(it, "clone:continuation-original") }
            if (alternateVoice) chapter(1, "clone:continuation-other")
            write("catalogue.json", JSONObject().put("schema_version", 1).put("entries", entries).toString().toByteArray())
        }
        return bytes.toByteArray()
    }
    private fun withAudio(chapters: List<Int> = listOf(0, 1, 2), alternateVoice: Boolean = false,
        test: (ActivityScenario<MainActivity>, AudioLibrary, Book, List<AudioChapter>) -> Unit) {
        val id = "continuation-${UUID.randomUUID()}"
        val book = Book(id, "连续听书测试", "山页", "测试", "", "", 0,
            (0..2).map { Chapter("$id-$it", "第${it + 1}章：连续播放$it", "风停了。\n\n故事继续。") })
        val speed = AudioPlayback.speed
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            instrumentation.waitForIdleSync()
            SystemClock.sleep(700)
            main { Library.addImported(book) }
            AudioLibrary(context).use { library ->
                val archive = bundle(book, chapters, alternateVoice)
                library.importBundle(archive.inputStream(), listOf(book))
                val items = library.load(listOf(book))
                try { test(activity, library, book, items) }
                finally {
                    shell("input keyevent KEYCODE_WAKEUP")
                    shell("wm dismiss-keyguard")
                    main {
                        AudioPlayback.setSpeed(context, speed)
                        if (AudioPlayback.current != null) {
                            AudioPlayback.setSleepTimer(context, 0)
                            AudioPlayback.command(context, "stop")
                        }
                    }
                    await("Service must stop") { AudioPlayback.current == null }
                    library.writableDatabase.delete("artifacts", "book_id=?", arrayOf(book.id))
                    items.forEach { File(context.filesDir, "audiobooks/${it.id}").deleteRecursively() }
                    context.getSharedPreferences("shanye_audio", 0).edit().apply {
                        items.forEach { remove("position:${it.id}") }
                        book.chapters.forEach { remove("selected:${it.id}") }
                    }.commit()
                    main { Library.books.removeAll { it.id == book.id } }
                }
            }
        }
    }
    private fun start(item: AudioChapter, offset: Int = duration - 1200) {
        main { AudioPlayback.play(context, item, offset) }
        await("Chapter should start") { AudioPlayback.current?.id == item.id && AudioPlayback.playing }
    }

    @Test fun naturalCompletionContinuesFromZeroKeepsCloneSpeedTimerAndUpdatesReaderPlayer() =
        withAudio(alternateVoice = true) { activity, library, book, items ->
            val first = items.first { it.chapterIndex == 0 }
            val next = items.first { it.chapterIndex == 1 && it.voiceKey == first.voiceKey }
            val other = items.first { it.chapterIndex == 1 && it.voiceKey != first.voiceKey }
            // Both clone versions have the same visible label, but distinct reference IDs.
            assertEquals(next.voice, other.voice)
            library.select(other)
            library.savePosition(next, 7000)
            start(first, 0)
            main { AudioPlayback.setSpeed(context, 1.1f); AudioPlayback.setSleepTimer(context, 15) }
            activity.onActivity { host -> host.setContent { MaterialTheme {
                AudioPlayerScreen(book, library, items, {}, {}, initialChapterIndex = 0, onRead = { _, _ -> })
            } } }
            await("Reader player should display the first chapter") { hasText(first.title) }
            main { AudioPlayback.command(context, "seek", duration - 700) }
            await("Natural completion must start the next chapter") { AudioPlayback.current?.id == next.id && AudioPlayback.playing }
            assertTrue("Automatic continuation starts at zero, not the chapter's saved position", AudioPlayback.position < 3000)
            assertEquals(1.1f, AudioPlayback.speed, 0f)
            assertEquals(15, AudioPlayback.timerMode)
            assertTrue(AudioPlayback.timerRemainingMs in 880000L..900000L)
            assertEquals(duration, library.position(first))
            assertEquals(next.id, library.preferred(items, next.chapterId)?.id)
            await("Reader player title must follow automatic continuation") { hasText(next.title) }
            assertFalse(hasText(first.title))
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir, "audio-continuation-player.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            // Continue a second time without a new play request.
            main { AudioPlayback.command(context, "seek", duration - 700) }
            await("Consecutive completion must advance once more") { AudioPlayback.current?.chapterIndex == 2 && AudioPlayback.playing }
        }

    @Test fun completionContinuesWhileScreenIsLocked() = withAudio { _, _, _, items ->
        val first = items.first { it.chapterIndex == 0 }
        start(first, 0)
        shell("input keyevent KEYCODE_SLEEP")
        val power = context.getSystemService(PowerManager::class.java)
        await("Screen must lock") { !power.isInteractive }
        main { AudioPlayback.command(context, "seek", duration - 700) }
        await("The media service must continue in the background") { AudioPlayback.current?.chapterIndex == 1 && AudioPlayback.playing }
        assertFalse(power.isInteractive)
        val before = AudioPlayback.position
        await("Next chapter keeps advancing while locked") { AudioPlayback.position > before + 500 }
    }

    @Test fun chapterEndTimerStopsInsteadOfContinuing() = withAudio { _, _, _, items ->
        val first = items.first { it.chapterIndex == 0 }
        start(first, 0)
        main { AudioPlayback.setSleepTimer(context, -1); AudioPlayback.command(context, "seek", duration - 700) }
        await("End-of-chapter timer must stop at the boundary") { !AudioPlayback.playing && AudioPlayback.position == duration }
        SystemClock.sleep(600)
        assertEquals(first.id, AudioPlayback.current?.id)
        assertEquals(0, AudioPlayback.timerMode)
        assertFalse(AudioPlayback.preparing)
    }

    @Test fun missingNextChapterStopsAndDoesNotSkipToLaterImportedChapter() = withAudio(listOf(0, 2)) { _, _, _, items ->
        val first = items.first { it.chapterIndex == 0 }
        start(first)
        await("Missing chapter should stop with a clear message") { !AudioPlayback.playing && AudioPlayback.error?.contains("下一章暂无听书内容") == true }
        assertEquals(first.id, AudioPlayback.current?.id)
        assertEquals(duration, AudioPlayback.position)
    }

    @Test fun lastChapterStopsWithoutLooping() = withAudio { _, _, _, items ->
        val last = items.first { it.chapterIndex == 2 }
        start(last)
        await("Final chapter must stop") { !AudioPlayback.playing && AudioPlayback.position == duration }
        SystemClock.sleep(600)
        assertEquals(last.id, AudioPlayback.current?.id)
        assertNull(AudioPlayback.error)
    }

    @Test fun nextChapterFallsBackToSavedAvailableVersion() = withAudio(alternateVoice = true) { _, library, book, items ->
        val first = items.first { it.chapterIndex == 0 }.copy(voiceKey = "not-imported-here")
        val other = items.first { it.chapterIndex == 1 && it.voiceKey == "clone:continuation-other" }
        library.select(other)
        assertEquals(other.id, library.nextChapter(first, book)?.id)
        other.file.delete()
        assertEquals("clone:continuation-original", library.nextChapter(first, book)?.voiceKey)
    }
}
