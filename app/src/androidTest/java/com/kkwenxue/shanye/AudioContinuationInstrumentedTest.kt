package com.kkwenxue.shanye

import android.graphics.Bitmap
import android.graphics.Rect
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
import com.kkwenxue.shanye.ui.ReaderScreen
import com.kkwenxue.shanye.ui.audioTime
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
    private fun findNode(label: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val nodes = java.util.ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(nodes::add)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (label(node)) return node
            repeat(node.childCount) { node.getChild(it)?.let(nodes::add) }
        }
        return null
    }
    private fun hasText(text: String) = findNode { it.text?.toString() == text } != null
    private fun click(label: String): Boolean {
        var node = findNode { it.text?.toString() == label || it.contentDescription?.toString() == label }
        while (node != null && !node.isClickable) node = node.parent
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }
    private fun showReaderMenu() {
        val bounds = Rect()
        await("Original text must be visible") {
            val reader = findNode { it.contentDescription?.startsWith("小说正文。") == true }
            reader?.getBoundsInScreen(bounds)
            reader != null && !bounds.isEmpty
        }
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        await("Reader menu must open") { hasText("目录") }
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
            (0..2).map { Chapter("$id-$it", "第${it + 1}章：连续播放$it", "风停了。\n\n故事继续。") },
            remoteSource = "catalog", remoteBookId = 1L, activeRevision = "fixture-v1", remoteLatestVersion = "fixture-v1")
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
                        remove("listening:${book.id}")
                        if (AudioLibrary.lastListenedBook(AudioLibrary.playbackPreferences(context)) == book.id) remove("lastListenedBook")
                    }.commit()
                    context.getSharedPreferences("shanye_reader", 0).edit()
                        .remove("${book.id}.chapter").remove("${book.id}.offset").remove("${book.id}.readAt").commit()
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
            var resumeChapter = -1
            main { resumeChapter = ReaderStore(context).resumePosition(book).chapter }
            assertEquals("New chapter must be checkpointed without waiting for the ticker", 2, resumeChapter)
        }

    private fun returnsToListeningPosition(stopBeforeReturn: Boolean) =
        withAudio { activity, library, book, items ->
            val first = items.single { it.chapterIndex == 0 }
            val last = items.single { it.chapterIndex == 2 }
            start(first, 0)
            val store = ReaderStore(context)
            activity.onActivity { host ->
                host.setContent { MaterialTheme { ReaderScreen(book, store, library, items, {}) {} } }
            }

            showReaderMenu()
            await("Text contents should open") { click("目录") }
            await("Choosing the text chapter disables automatic following") { click(first.title) }
            await("The text position was saved") { store.positions[book.id] == ReadingPosition(0, 0) }
            showReaderMenu()
            await("Open the reader's player") { click("听书") }
            await("Player must be visible") { findNode { it.contentDescription?.toString() == "收起播放器" } != null }

            repeat(2) { index ->
                main { AudioPlayback.command(context, "seek", duration - 700) }
                await("Audio must automatically advance to chapter ${index + 2}") {
                    AudioPlayback.current?.chapterIndex == index + 1 && AudioPlayback.playing
                }
            }
            main { AudioPlayback.command(context, "seek", 5000) }
            await("Player should be several seconds into the latest chapter") { AudioPlayback.position >= 5600 }

            if (stopBeforeReturn) {
                main { AudioPlayback.command(context, "stop") }
                await("Playback must be stopped while the player is still open") { AudioPlayback.current == null }
                activity.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            } else {
                await("Collapse the player while it is still running") { click("收起播放器") }
            }
            showReaderMenu()
            assertTrue("Returning should display the last chapter heard, not the original text page", hasText(last.title))
            assertEquals("The automatic handoff must not overwrite manual progress", ReadingPosition(0, 0), store.positions[book.id])
            val saved = library.position(last)
            assertTrue("Returning must checkpoint the latest audio, even between ticker saves", saved >= 5600)
            assertEquals(ReadingPosition(2, last.offsetAt(saved)!!), ReaderStore(context).resumePosition(book))
        }

    @Test fun collapsingPlayerJumpsToLatestChapterAfterManualTextNavigation() = returnsToListeningPosition(false)

    @Test fun backFromStoppedPlayerUsesLatestPersistedChapter() = returnsToListeningPosition(true)

    @Test fun completionContinuesWhileScreenIsLocked() = withAudio { _, library, book, items ->
        val first = items.first { it.chapterIndex == 0 }
        val next = items.first { it.chapterIndex == 1 }
        start(first, 0)
        shell("input keyevent KEYCODE_SLEEP")
        val power = context.getSystemService(PowerManager::class.java)
        await("Screen must lock") { !power.isInteractive }
        main { AudioPlayback.command(context, "seek", duration - 700) }
        await("The media service must continue in the background") { AudioPlayback.current?.chapterIndex == 1 && AudioPlayback.playing }
        assertFalse(power.isInteractive)
        val before = AudioPlayback.position
        await("Next chapter keeps advancing while locked") { AudioPlayback.position > before + 500 }
        await("Playback must checkpoint progress without a reader open") { library.position(next) >= 2500 }
        assertEquals(1, ReaderStore(context).resumePosition(book).chapter)
    }

    @Test fun stoppedPlaybackRestoresLatestChapterAndTextOffsetWithoutAutoplay() =
        withAudio(listOf(0, 1)) { activity, library, book, items ->
            val first = items.single { it.chapterIndex == 0 }
            val second = items.single { it.chapterIndex == 1 }
            start(first, 2000)
            start(second, 7200)
            main { AudioPlayback.command(context, "pause") }
            await("Pause should checkpoint the later chapter") { !AudioPlayback.playing && library.position(second) >= 7000 }
            main { AudioPlayback.command(context, "stop") }
            await("Media service should be gone before reopening") { AudioPlayback.current == null }
            val saved = library.position(second)
            assertTrue("Other chapters retain their own time", library.position(first) in 1900 until saved)

            // The underlying home screen should already show updated progress, without recreation.
            await("Continue reading should show the chapter last heard") { hasText("第2章 · ${second.title}") }
            val restored = ReaderStore(context)
            assertEquals(book.id, restored.lastBook)
            assertEquals(ReadingPosition(1, second.offsetAt(saved)!!), restored.resumePosition(book))
            activity.recreate()
            activity.onActivity { host -> host.setContent { MaterialTheme {
                AudioPlayerScreen(null, library, items, {}, {}, onRead = { _, _ -> })
            } } }
            await("The cold player must display the later chapter at its saved time") {
                hasText(second.title) && hasText(audioTime(saved))
            }
            assertNull("Opening the player must not start playback", AudioPlayback.current)
            await("Resume should be offered") { click("播放听书") }
            await("Resume must use the saved chapter and time") {
                AudioPlayback.current?.id == second.id && AudioPlayback.playing && AudioPlayback.position >= saved
            }
        }

    @Test fun stoppingPausedAudioDoesNotSupersedeNewerManualReading() = withAudio { _, library, book, items ->
        val later = items.single { it.chapterIndex == 1 }
        start(later, 6700)
        main { AudioPlayback.command(context, "pause") }
        await("Audio must pause and save before manual navigation") { !AudioPlayback.playing && library.position(later) >= 6500 }
        main { ReaderStore(context).savePosition(book, 2, 4) }
        main { AudioPlayback.command(context, "stop") }
        await("Paused audio must stop") { AudioPlayback.current == null }
        assertEquals(ReadingPosition(2, 4), ReaderStore(context).resumePosition(book))
        assertEquals(later.id, library.lastPlayed(items)?.id)
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
