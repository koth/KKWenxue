package com.kkwenxue.shanye.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import com.kkwenxue.shanye.reader.PageTurnStyle
import com.kkwenxue.shanye.reader.SentenceRange

data class ReadingPosition(val chapter: Int = 0, val offset: Int = 0)
data class Bookmark(val bookId: String, val chapter: Int, val offset: Int, val quote: String, val anchor: RevisionAnchor? = null) {
    val key get() = "$bookId:$chapter:$offset"
}
data class SentenceComment(
    val bookId: String,
    val chapter: Int,
    val start: Int,
    val end: Int,
    val quote: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
    val anchor: RevisionAnchor? = null
) {
    val key get() = "$bookId:$chapter:$start:$end"
    fun matches(sentence: SentenceRange) = start == sentence.start && end == sentence.end
}

class ReaderStore(context: Context) {
    private val prefs = context.getSharedPreferences("shanye_reader", Context.MODE_PRIVATE)
    private val audioPrefs = AudioLibrary.playbackPreferences(context)
    private var listeningRevision by mutableIntStateOf(0)
    var theme by mutableIntStateOf(prefs.getInt("theme", 0)); private set
    var fontSize by mutableIntStateOf(prefs.getInt("fontSize", 20)); private set
    var spacious by mutableStateOf(prefs.getBoolean("spacious", true)); private set
    var serif by mutableStateOf(prefs.getBoolean("serif", true)); private set
    var pageTurnStyle by mutableStateOf(PageTurnStyle.entries.getOrElse(prefs.getInt("pageTurnStyle",0)) {PageTurnStyle.PAPER}); private set
    var pagesRead by mutableIntStateOf(prefs.getInt("pagesRead", 0)); private set
    private var lastReadBook by mutableStateOf(prefs.getString("lastBook", "") ?: "")
    private var lastOpenedAt by mutableLongStateOf(prefs.getLong("lastOpenedAt", 0))
    val lastBook: String
        get() {
            listeningRevision
            val listened = AudioLibrary.lastListenedBook(audioPrefs)
            return listened?.takeIf {
                (latestListeningCheckpoint(it)?.updatedAt ?: 0) > lastOpenedAt &&
                    Library.books.any { book -> book.id == it }
            } ?: lastReadBook
        }
    val positions = mutableStateMapOf<String, ReadingPosition>()
    private val positionAnchors = mutableStateMapOf<String, RevisionAnchor>()
    private val positionTimes = mutableStateMapOf<String, Long>()
    val bookmarks = mutableStateListOf<Bookmark>()
    val comments = mutableStateListOf<SentenceComment>()

    init {
        Library.books.forEach { book ->
            val savedChapterId = prefs.getString("${book.id}.chapterId", null)
            val savedRevision = prefs.getString("${book.id}.revision", null)
            val savedChapter = savedChapterId?.let { id -> book.chapters.indexOfFirst { it.id == id } }
                ?.takeIf { it >= 0 } ?: prefs.getInt("${book.id}.chapter", 0)
            positions[book.id] = ReadingPosition(
                savedChapter.coerceIn(book.chapters.indices),
                prefs.getInt("${book.id}.offset", 0).coerceAtLeast(0)
            )
            positionTimes[book.id] = prefs.getLong("${book.id}.readAt", 0)
            val anchor = prefs.getString("${book.id}.anchor", null)?.let { raw ->
                runCatching { parseAnchor(JSONObject(raw)) }.getOrNull()
            } ?: savedChapterId?.let {
                RevisionAnchor(it, "", savedRevision, positions.getValue(book.id).offset, "", 0)
            }
            if (anchor != null) positionAnchors[book.id] = anchor
            else if (positionTimes.getValue(book.id) > 0 && savedChapter in book.chapters.indices) {
                positionAnchors[book.id] = RevisionAnchor.capture(book, savedChapter, positions.getValue(book.id).offset)
            }
        }
        runCatching {
            val array = JSONArray(prefs.getString("bookmarks", "[]"))
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val book = Library.books.firstOrNull { it.id == item.getString("book") } ?: continue
                val chapter = item.getInt("chapter")
                val offset = item.getInt("offset")
                val anchor = item.optJSONObject("anchor")?.let(::parseAnchor)
                    ?: chapter.takeIf { it in book.chapters.indices }?.let { RevisionAnchor.capture(book, it, offset) }
                bookmarks.add(Bookmark(book.id, chapter, offset, item.getString("quote"), anchor))
            }
        }
        runCatching {
            val array = JSONArray(prefs.getString("comments", "[]"))
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val book = Library.books.firstOrNull { it.id == item.getString("book") } ?: continue
                val chapter = item.getInt("chapter")
                val start = item.getInt("start")
                val end = item.getInt("end")
                val anchor = item.optJSONObject("anchor")?.let(::parseAnchor)
                    ?: chapter.takeIf { it in book.chapters.indices }?.let { RevisionAnchor.capture(book, it, start, end) }
                comments.add(SentenceComment(book.id, chapter, start, end, item.getString("quote"), item.getString("body"), item.optLong("createdAt"), anchor))
            }
        }
    }

    fun changeTheme(value: Int) { theme = value; prefs.edit().putInt("theme", value).apply() }
    fun changeFontSize(value: Int) { fontSize = value.coerceIn(16, 28); prefs.edit().putInt("fontSize", fontSize).apply() }
    fun changeSpacious(value: Boolean) { spacious = value; prefs.edit().putBoolean("spacious", value).apply() }
    fun changeSerif(value: Boolean) { serif = value; prefs.edit().putBoolean("serif", value).apply() }
    fun changePageTurnStyle(value: PageTurnStyle) { pageTurnStyle=value; prefs.edit().putInt("pageTurnStyle",value.ordinal).apply() }
    fun scriptFor(book: Book): ChineseScript {
        val saved = prefs.getInt("${book.id}.script", -1)
        return ChineseScript.entries.getOrNull(saved) ?: ChineseScriptConverter.detect(book.chapters.firstOrNull()?.text.orEmpty())
    }
    fun changeScript(bookId: String, value: ChineseScript) {
        prefs.edit().putInt("$bookId.script", value.ordinal).apply()
    }
    fun open(book: Book) {
        lastReadBook = book.id
        lastOpenedAt = System.currentTimeMillis()
        prefs.edit().putString("lastBook", book.id).putLong("lastOpenedAt", lastOpenedAt).apply()
    }
    fun savePosition(book: Book, chapter: Int, offset: Int) {
        val position = ReadingPosition(chapter.coerceIn(book.chapters.indices), offset.coerceAtLeast(0))
        val now = System.currentTimeMillis()
        val anchor = RevisionAnchor.capture(book, position.chapter, position.offset)
        positions[book.id] = position
        positionAnchors[book.id] = anchor
        positionTimes[book.id] = now
        prefs.edit().putInt("${book.id}.chapter", position.chapter)
            .putString("${book.id}.chapterId", book.chapters[position.chapter].id)
            .putString("${book.id}.revision", book.activeRevision)
            .putString("${book.id}.anchor", anchor.json().toString())
            .putInt("${book.id}.offset", position.offset)
            .putLong("${book.id}.readAt", now).apply()
    }

    // Keep explicit text navigation independent of audio; the newer checkpoint wins on re-entry.
    internal fun listeningPosition(book: Book): ListeningCheckpoint? =
        latestListeningCheckpoint(book.id)?.takeIf { isNewerThanReading(book, it) }

    internal fun positionAfterListening(book: Book, checkpoint: ListeningCheckpoint): ReadingPosition? =
        checkpoint.takeIf { isNewerThanReading(book, it) }?.anchor?.positionIn(book)

    private fun isNewerThanReading(book: Book, checkpoint: ListeningCheckpoint): Boolean =
        checkpoint.updatedAt > (positionTimes[book.id] ?: 0L)

    private fun resolveReading(book: Book): ReadingPosition? {
        val anchor = positionAnchors[book.id]
        if (anchor != null) return anchor.locate(book)
        return positions[book.id]?.takeIf { it.chapter in book.chapters.indices } ?: ReadingPosition()
    }

    fun needsRelocation(book: Book): Boolean {
        val listened = listeningPosition(book)
        return if (listened != null) listened.anchor.positionIn(book) == null else resolveReading(book) == null
    }

    fun resumePosition(book: Book): ReadingPosition =
        listeningPosition(book)?.anchor?.positionIn(book) ?: resolveReading(book) ?: ReadingPosition()

    fun positionFor(book: Book, bookmark: Bookmark): ReadingPosition? =
        bookmark.anchor?.locate(book) ?: if (bookmark.anchor == null && bookmark.chapter in book.chapters.indices)
            ReadingPosition(bookmark.chapter, bookmark.offset) else null

    private fun latestListeningCheckpoint(bookId: String): ListeningCheckpoint? {
        // Observe audio saves without making the service own the UI's ReaderStore.
        listeningRevision
        return AudioLibrary.listeningCheckpoint(audioPrefs, bookId)
    }

    internal fun observeListeningProgress(): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (AudioLibrary.isListeningPreference(key)) listeningRevision++
        }
        audioPrefs.registerOnSharedPreferenceChangeListener(listener)
        return { audioPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun countPage() { pagesRead++; prefs.edit().putInt("pagesRead", pagesRead).apply() }
    fun toggleBookmark(value: Bookmark) {
        if (bookmarks.any { it == value }) bookmarks.remove(value)
        else bookmarks.add(0, value)
        persistBookmarks()
    }
    private fun persistBookmarks() {
        val array = JSONArray()
        bookmarks.forEach { array.put(JSONObject().put("book", it.bookId).put("chapter", it.chapter).put("offset", it.offset)
            .put("quote", it.quote).put("anchor", it.anchor?.json())) }
        prefs.edit().putString("bookmarks", array.toString()).apply()
    }
    fun commentFor(book: Book, chapter: Int, sentence: SentenceRange): SentenceComment? =
        comments.firstOrNull {
            if (it.bookId != book.id || it.end - it.start != sentence.end - sentence.start) false
            else if (it.anchor == null) it.chapter == chapter && it.matches(sentence)
            else it.anchor.locate(book, it.end - it.start) == ReadingPosition(chapter, sentence.start)
        }
    fun commentFor(bookId: String, chapter: Int, sentence: SentenceRange): SentenceComment? =
        commentFor(Library.book(bookId), chapter, sentence)

    fun commentNeedsRelocation(book: Book, comment: SentenceComment): Boolean =
        comment.anchor?.let { it.locate(book, comment.end - comment.start) == null } ?: (comment.chapter !in book.chapters.indices)

    fun saveComment(bookId: String, chapter: Int, sentence: SentenceRange, body: String) {
        saveComment(Library.book(bookId), chapter, sentence, body)
    }
    fun saveComment(book: Book, chapter: Int, sentence: SentenceRange, body: String) {
        val value = SentenceComment(book.id, chapter, sentence.start, sentence.end, sentence.cleanText, body.trim(),
            anchor = RevisionAnchor.capture(book, chapter, sentence.start, sentence.end))
        commentFor(book, chapter, sentence)?.let { comments.remove(it) }
        comments.add(0, value)
        persistComments()
    }
    private fun persistComments() {
        val array = JSONArray()
        comments.forEach {
            array.put(JSONObject().put("book", it.bookId).put("chapter", it.chapter).put("start", it.start).put("end", it.end).put("quote", it.quote).put("body", it.body).put("createdAt", it.createdAt).put("anchor", it.anchor?.json()))
        }
        prefs.edit().putString("comments", array.toString()).apply()
    }
    internal fun copyLegacyAnchors(legacy: Book, online: Book) {
        require(online.legacyBookId == legacy.id)
        val pos = resumePosition(legacy)
        if (!needsRelocation(legacy) && ((positionTimes[legacy.id] ?: 0) > 0 || listeningPosition(legacy) != null)) {
            val from = RevisionAnchor.capture(legacy, pos.chapter, pos.offset)
            val relocated = migrateLegacy(legacy, online, from)
            if (relocated != null) {
                savePosition(online, relocated.chapter, relocated.offset)
            } else {
                positionAnchors[online.id] = from
                prefs.edit().putString("${online.id}.anchor", from.json().toString()).apply()
            }
        }
        val marks = bookmarks.filter { it.bookId == legacy.id }.map { mark ->
            val anchor = mark.anchor ?: if (mark.chapter in legacy.chapters.indices) {
                RevisionAnchor.capture(legacy, mark.chapter, mark.offset)
            } else return@map mark.copy(bookId = online.id)
            val mapped = migrateLegacy(legacy, online, anchor)
            if (mapped == null) mark.copy(bookId = online.id, anchor = anchor)
            else mark.copy(bookId = online.id, chapter = mapped.chapter, offset = mapped.offset,
                anchor = RevisionAnchor.capture(online, mapped.chapter, mapped.offset))
        }
        if (marks.isNotEmpty()) { bookmarks.addAll(marks); persistBookmarks() }
        val notes = comments.filter { it.bookId == legacy.id }.map { note ->
            val length = note.end - note.start
            val anchor = note.anchor ?: if (note.chapter in legacy.chapters.indices) {
                RevisionAnchor.capture(legacy, note.chapter, note.start, note.end)
            } else return@map note.copy(bookId = online.id)
            val mapped = migrateLegacy(legacy, online, anchor, length)
            if (mapped == null) note.copy(bookId = online.id, anchor = anchor)
            else note.copy(bookId = online.id, chapter = mapped.chapter, start = mapped.offset, end = mapped.offset + length,
                anchor = RevisionAnchor.capture(online, mapped.chapter, mapped.offset, mapped.offset + length))
        }
        if (notes.isNotEmpty()) { comments.addAll(notes); persistComments() }
    }

    private fun migrateLegacy(legacy: Book, online: Book, anchor: RevisionAnchor, length: Int = 0): ReadingPosition? {
        val source = legacy.chapters.firstOrNull { it.id == anchor.chapterId }
        val exact = online.chapters.indices.singleOrNull { i ->
            val candidate = online.chapters[i]
            source != null && candidate.title == source.title && candidate.body == source.body
        }
        return exact?.let { ReadingPosition(it, anchor.offset) } ?: anchor.locate(online, length)
    }
    fun progress(book: Book): Float {
        if (needsRelocation(book)) return 0f
        val pos = resumePosition(book)
        val total = book.chapters.sumOf { it.text.length }
        val done = book.chapters.take(pos.chapter).sumOf { it.text.length } + pos.offset
        return if (total == 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }
}

internal fun RevisionAnchor.json(): JSONObject = JSONObject().put("chapterId", chapterId).put("title", title)
    .put("revision", revision).put("offset", offset).put("context", context).put("withinContext", withinContext)

internal fun parseAnchor(json: JSONObject): RevisionAnchor = RevisionAnchor(json.getString("chapterId"),
    json.getString("title"), json.opt("revision") as? String, json.getInt("offset"),
    json.getString("context"), json.getInt("withinContext"))
