package com.kkwenxue.shanye.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaMetadataRetriever
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioCue(val startMs: Int, val endMs: Int, val start: Int, val end: Int, val text: String)

internal data class AudioReadingAnchor(
    val bookId: String, val chapterId: String, val textOffset: Int, val revision: String? = null,
    val migration: RevisionAnchor? = null
) {
    fun positionIn(book: Book): ReadingPosition? {
        if (book.id != bookId) return null
        val index = book.chapters.indexOfFirst { it.id == chapterId }
        if (index < 0) return migration?.locate(book)
        if (book.remoteBookId != null && !chapterId.contains(":chapter:sha256:") &&
            revision != book.chapters[index].audioSourceRevision) return migration?.locate(book)
        return ReadingPosition(index, textOffset.coerceIn(0, book.chapters[index].text.length))
    }
}

internal data class ListeningCheckpoint(
    val artifactId: String, val anchor: AudioReadingAnchor, val updatedAt: Long
)

data class AudioChapter(
    val id: String, val bookId: String, val chapterId: String, val chapterIndex: Int,
    val title: String, val voice: String, val durationMs: Int, val cues: List<AudioCue>, val file: File,
    val voiceKey: String = voice,
    val sourceRevision: String? = null
) {
    fun cueAt(position: Int): AudioCue? = cues.lastOrNull { it.startMs <= position }
        ?.takeIf { position < it.endMs }
    /** Approximate the spoken UTF-16 offset inside a cue, including cues that span pages. */
    fun offsetAt(position: Int): Int? {
        val cue = cues.firstOrNull { position < it.endMs && position >= it.startMs }
            ?: cues.lastOrNull { it.startMs <= position }
            ?: cues.firstOrNull()
            ?: return null
        val duration = (cue.endMs - cue.startMs).coerceAtLeast(1)
        val fraction = ((position - cue.startMs).toFloat() / duration).coerceIn(0f, 1f)
        return (cue.start + ((cue.end - cue.start) * fraction).toInt()).coerceIn(cue.start, cue.end)
    }
    fun anchorAt(position: Int): Int = (cues.lastOrNull { it.startMs <= position } ?: cues.firstOrNull())?.start ?: 0
    fun positionFor(offset: Int): Int = (cues.firstOrNull { it.end > offset } ?: cues.lastOrNull())?.startMs ?: 0
    internal fun readingAnchor(offset: Int): AudioReadingAnchor {
        val cue = cues.firstOrNull { offset in it.start..it.end && it.text.length == it.end - it.start }
        val migration = cue?.let {
            RevisionAnchor.fromText(chapterId, title, sourceRevision, it.text, offset - it.start, baseOffset = it.start)
        }
        return AudioReadingAnchor(bookId, chapterId, offset, sourceRevision, migration)
    }
}

/** Import the workbench's ZIP contract; GPU service credentials never reach the phone. */
class AudioLibrary(private val context: Context) : SQLiteOpenHelper(context, "shanye_audio.db", null, 1) {
    private val root = File(context.filesDir, "audiobooks").apply { mkdirs() }
    private val prefs = playbackPreferences(context)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE artifacts (id TEXT PRIMARY KEY, book_id TEXT NOT NULL, chapter_id TEXT NOT NULL, manifest TEXT NOT NULL, imported_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX artifacts_chapter ON artifacts(book_id, chapter_id, imported_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun load(books: List<Book>): List<AudioChapter> {
        val result = mutableListOf<AudioChapter>()
        readableDatabase.rawQuery("SELECT manifest FROM artifacts ORDER BY imported_at DESC, rowid DESC", null).use { rows ->
            while (rows.moveToNext()) runCatching {
                val manifest = JSONObject(rows.getString(0))
                val item = parse(manifest, books)
                if (item.file.isFile) result.add(item)
            }
        }
        return result
    }

    fun preferred(items: List<AudioChapter>, chapterId: String): AudioChapter? {
        val saved = prefs.getString("selected:$chapterId", null)
        return items.firstOrNull { it.chapterId == chapterId && it.id == saved }
            ?: items.firstOrNull { it.chapterId == chapterId }
    }
    fun select(item: AudioChapter) { prefs.edit().putString("selected:${item.chapterId}", item.id).apply() }
    fun position(item: AudioChapter): Int = prefs.getInt("position:${item.id}", 0).coerceIn(0, item.durationMs)
    fun savePosition(item: AudioChapter, position: Int) {
        prefs.edit().putInt("position:${item.id}", position.coerceIn(0, item.durationMs)).apply()
    }

    /** Only the service promotes an actual playback position to the resume checkpoint. */
    internal fun savePlaybackPosition(item: AudioChapter, position: Int) {
        val offset = position.coerceIn(0, item.durationMs)
        val edit = prefs.edit().putInt("position:${item.id}", offset)
        item.offsetAt(offset)?.let { textOffset ->
            val anchor = item.readingAnchor(textOffset)
            edit.putString(LAST_LISTENED_BOOK, item.bookId).putString("$LISTENING_PREFIX${item.bookId}",
                JSONObject().put("artifact", item.id).put("chapter", item.chapterId)
                    .put("offset", textOffset).put("revision", item.sourceRevision)
                    .put("anchor", anchor.migration?.json())
                    .put("updatedAt", System.currentTimeMillis()).toString())
        }
        edit.apply()
    }

    internal fun latestPlaybackCheckpoint(bookId: String? = null): ListeningCheckpoint? =
        (bookId ?: lastListenedBook(prefs))?.let { listeningCheckpoint(prefs, it) }

    fun lastPlayed(items: List<AudioChapter>): AudioChapter? {
        val checkpoint = latestPlaybackCheckpoint() ?: return null
        return items.firstOrNull {
            it.id == checkpoint.artifactId && it.bookId == checkpoint.anchor.bookId && it.chapterId == checkpoint.anchor.chapterId
        }
    }

    /** Only the next chapter in the text catalogue may continue automatically. */
    fun nextChapter(current: AudioChapter, book: Book): AudioChapter? {
        if (current.bookId != book.id) return null
        val index = book.chapters.indexOfFirst { it.id == current.chapterId }
        if (index < 0) return null
        val next = book.chapters.getOrNull(index + 1) ?: return null
        val versions = mutableListOf<AudioChapter>()
        readableDatabase.rawQuery(
            "SELECT manifest FROM artifacts WHERE book_id=? AND chapter_id=? ORDER BY imported_at DESC, rowid DESC",
            arrayOf(book.id, next.id)
        ).use { rows ->
            while (rows.moveToNext()) runCatching {
                parse(JSONObject(rows.getString(0)), listOf(book))
            }.getOrNull()?.takeIf { it.file.isFile }?.let(versions::add)
        }
        // Clone labels all read '克隆音色'; compare the actual speaker/ref ID.
        return preferred(versions.filter { it.voiceKey == current.voiceKey }, next.id)
            ?: preferred(versions, next.id)
    }

    /** Validate the entire package before committing. Re-import is idempotent. */
    fun importBundle(input: InputStream, books: List<Book>): Int = synchronized(importLock) {
        importBundleLocked(input, books)
    }
    private fun importBundleLocked(input: InputStream, books: List<Book>): Int {
        val staging = File(context.cacheDir, "audio-import-${UUID.randomUUID()}").apply { mkdirs() }
        val moved = mutableListOf<File>()
        try {
            val archiveFile = File(staging, "bundle.zip")
            input.use { source -> archiveFile.outputStream().use { copyLimited(source, it, MAX_PACKAGE) } }
            val manifests = mutableListOf<String>()
            ZipFile(archiveFile).use { zip ->
                require(zip.size() <= 5000) { "听书包文件数过多，请分批导入" }
                val catalogueEntry = zip.getEntry("catalogue.json") ?: error("请选择工作台下载的听书 ZIP 包（包含 catalogue.json）")
                val catalogueText=zip.getInputStream(catalogueEntry).use { readJson(it) }
                val catalogue = JSONObject(catalogueText)
                require(catalogue.getInt("schema_version") == 1) { "暂不支持这个听书包版本" }
                val entries = catalogue.getJSONArray("entries")
                require(entries.length() in 1..1000) { "听书包应包含 1–1000 个章节版本" }
                val ids = mutableSetOf<String>()
                var expanded = catalogueText.toByteArray().size.toLong()
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    val manifestName = entry.getString("manifest")
                    val audioName = entry.getString("audio")
                    require(safeName(manifestName) && safeName(audioName) && manifestName != audioName) { "听书包文件名无效" }
                    val raw = zip.getInputStream(zip.getEntry(manifestName) ?: error("听书包缺少章节清单")).use { readJson(it) }
                    expanded += raw.toByteArray().size
                    require(expanded<=MAX_PACKAGE) { "听书包过大，请分批导入" }
                    require(sha256(raw.toByteArray()) == entry.getString("manifest_sha256")) { "章节清单校验失败" }
                    val manifest = JSONObject(raw)
                    val item = parse(manifest, books)
                    require(ids.add(item.id)) { "听书包包含重复版本" }
                    require(entry.getString("artifact_id") == item.id && entry.getString("chapter_id") == item.chapterId) { "目录与章节不一致" }
                    require(entry.getString("audio_sha256") == manifest.getString("audio_sha256")) { "目录与音频校验不一致" }
                    val folder = File(staging, item.id).apply { mkdirs() }
                    val audio = File(folder, "chapter.m4a")
                    zip.getInputStream(zip.getEntry(audioName) ?: error("听书包缺少音频")).use { source ->
                        audio.outputStream().use { expanded += copyLimited(source, it, MAX_PACKAGE - expanded) }
                    }
                    require(sha256(audio) == manifest.getString("audio_sha256")) { "${item.title} 音频校验失败，请重新下载" }
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(audio.path)
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
                        require(abs(duration - item.durationMs) <= 250) { "${item.title} 音频时长与原文定位不一致" }
                    } finally { retriever.release() }
                    File(folder, "chapter.json").writeText(raw)
                    manifests.add(item.id)
                }
            }
            val db = writableDatabase
            db.beginTransaction()
            var added = 0
            try {
                for (id in manifests) {
                    val manifest=JSONObject(File(staging,"$id/chapter.json").readText())
                    val target = File(root, id)
                    val existing = db.rawQuery("SELECT manifest FROM artifacts WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
                    if (existing != null) {
                        require(JSONObject(existing).toString() == manifest.toString()) { "同一音频版本的内容发生变化" }
                        val previousAudio=File(target,"chapter.m4a")
                        if (previousAudio.isFile && sha256(previousAudio)==manifest.getString("audio_sha256")) continue
                    }
                    // A directory without a DB row can only be a leftover from an interrupted import.
                    if (target.exists()) target.deleteRecursively()
                    require(File(staging, id).renameTo(target)) { "保存音频失败，请检查存储空间" }
                    moved.add(target)
                    db.insertWithOnConflict("artifacts", null, ContentValues().apply {
                        put("id", id); put("book_id", manifest.getString("book_id")); put("chapter_id", manifest.getString("chapter_id"))
                        put("manifest", manifest.toString()); put("imported_at", System.currentTimeMillis())
                    }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "保存听书目录失败" } }
                    added++
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            moved.clear()
            synchronized(importChanges) { importChanges.value += 1 }
            return added
        } finally {
            moved.forEach { it.deleteRecursively() }
            staging.deleteRecursively()
        }
    }

    private fun parse(manifest: JSONObject, books: List<Book>): AudioChapter {
        require(manifest.getInt("schema_version") == 2 && manifest.getBoolean("complete")) { "只能导入完整章节，试听音频不能作为整章导入" }
        require(manifest.getString("offsets") == "UTF-16 into Android Chapter.text") { "不支持的原文定位格式" }
        val id = manifest.getString("artifact_id")
        require(id.matches(Regex("[a-f0-9]{32}"))) { "音频版本编号无效" }
        val book = books.firstOrNull { it.id == manifest.getString("book_id") } ?: error("本机没有这本书，请先导入相同的小说文本")
        val index = book.chapters.indexOfFirst { it.id == manifest.getString("chapter_id") }
        require(index >= 0) { "本机没有对应章节，请检查小说版本" }
        val chapter = book.chapters[index]
        val sourceHash = sha256(chapter.text.toByteArray())
        require(manifest.getString("source_sha256") == sourceHash) { "${chapter.title} 原文版本不同，无法匹配音频" }
        require(manifest.getString("source_revision") == chapter.audioSourceRevision) { "章节修订校验失败" }
        val duration = manifest.getInt("duration_ms")
        require(duration > 0) { "音频时长无效" }
        val cues = mutableListOf<AudioCue>()
        val source = manifest.getJSONArray("cues")
        var previousMs = 0
        var previousEnd = 0
        for (i in 0 until source.length()) {
            val cue = source.getJSONObject(i)
            val start = cue.getInt("start_utf16")
            val end = cue.getInt("end_utf16")
            val startMs = cue.getInt("start_ms")
            val endMs = cue.getInt("end_ms")
            require(start >= previousEnd && end > start && end <= chapter.text.length &&
                startMs >= previousMs && endMs > startMs && endMs <= duration &&
                chapter.text.substring(start, end) == cue.getString("original_text")) { "${chapter.title} 音频与原文定位不一致" }
            cues.add(AudioCue(startMs, endMs, start, end, cue.getString("original_text")))
            previousMs = endMs
            previousEnd = end
        }
        require(cues.isNotEmpty()) { "章节没有可播放的语音" }
        val profile = manifest.getJSONObject("profile")
        val speaker = profile.getString("speaker")
        val voice = profile.optString("voice_name").takeIf { it.isNotBlank() } ?: when (speaker) {
            "zonos:haoran" -> "Haoran"
            "clone:linchuan-zonos3-v1" -> "林川"
            else -> if (speaker.startsWith("clone:")) "克隆音色" else speaker.replace('_', ' ')
        }
        return AudioChapter(
            id, book.id, chapter.id, index, chapter.title, voice, duration, cues,
            File(root, "$id/chapter.m4a"), speaker, manifest.getString("source_revision")
        )
    }

    companion object {
        private const val LAST_LISTENED_BOOK = "lastListenedBook"
        private const val LISTENING_PREFIX = "listening:"
        internal fun playbackPreferences(context: Context): SharedPreferences =
            context.getSharedPreferences("shanye_audio", Context.MODE_PRIVATE)
        internal fun isListeningPreference(key: String?): Boolean =
            key == null || key == LAST_LISTENED_BOOK || key.startsWith(LISTENING_PREFIX)
        internal fun lastListenedBook(prefs: SharedPreferences): String? = prefs.getString(LAST_LISTENED_BOOK, null)
        internal fun listeningCheckpoint(prefs: SharedPreferences, bookId: String): ListeningCheckpoint? =
            prefs.getString("$LISTENING_PREFIX$bookId", null)?.let { raw ->
                runCatching {
                    val value = JSONObject(raw)
                    ListeningCheckpoint(value.getString("artifact"),
                        AudioReadingAnchor(bookId, value.getString("chapter"), value.getInt("offset"),
                            value.opt("revision") as? String, value.optJSONObject("anchor")?.let(::parseAnchor)),
                        value.getLong("updatedAt"))
                }.getOrNull()
            }
        private val importLock = Any()
        private val importChanges = MutableStateFlow(0L)
        val changes = importChanges.asStateFlow()
        private const val MAX_PACKAGE = 4L * 1024 * 1024 * 1024
        private fun safeName(name: String) = name.isNotBlank() && !name.startsWith('/') && '\\' !in name && name.split('/').none { it == ".." || it == "." }
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { source -> val buffer = ByteArray(65536); while (true) { val n = source.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun readJson(input: InputStream): String {
            val output = java.io.ByteArrayOutputStream()
            copyLimited(input, output, 8L * 1024 * 1024)
            return output.toString("UTF-8")
        }
        private fun copyLimited(input: InputStream, output: java.io.OutputStream, limit: Long): Long {
            val buffer = ByteArray(65536)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                require(total <= limit) { "听书包过大，请分批导入（每包最多 4 GB）" }
                output.write(buffer, 0, n)
            }
            return total
        }
    }
}
