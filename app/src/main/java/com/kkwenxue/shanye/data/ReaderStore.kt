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
data class Bookmark(val bookId: String, val chapter: Int, val offset: Int, val quote: String) {
    val key get() = "$bookId:$chapter:$offset"
}
data class SentenceComment(
    val bookId: String,
    val chapter: Int,
    val start: Int,
    val end: Int,
    val quote: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis()
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
    private var lastReadBook by mutableStateOf(prefs.getString("lastBook", "mountain") ?: "mountain")
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
    private val positionTimes = mutableStateMapOf<String, Long>()
    val bookmarks = mutableStateListOf<Bookmark>()
    val comments = mutableStateListOf<SentenceComment>()

    init {
        Library.books.forEach { book ->
            positions[book.id] = ReadingPosition(
                prefs.getInt("${book.id}.chapter", 0).coerceIn(book.chapters.indices),
                prefs.getInt("${book.id}.offset", 0).coerceAtLeast(0)
            )
            positionTimes[book.id] = prefs.getLong("${book.id}.readAt", 0)
        }
        runCatching {
            val array = JSONArray(prefs.getString("bookmarks", "[]"))
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val book = Library.books.firstOrNull { it.id == item.getString("book") } ?: continue
                val chapter = item.getInt("chapter")
                if (chapter !in book.chapters.indices) continue
                bookmarks.add(Bookmark(book.id, chapter, item.getInt("offset"), item.getString("quote")))
            }
        }
        runCatching {
            val array = JSONArray(prefs.getString("comments", "[]"))
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val book = Library.books.firstOrNull { it.id == item.getString("book") } ?: continue
                val chapter = item.getInt("chapter")
                if (chapter !in book.chapters.indices) continue
                comments.add(SentenceComment(book.id, chapter, item.getInt("start"), item.getInt("end"), item.getString("quote"), item.getString("body"), item.optLong("createdAt")))
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
        positions[book.id] = position
        positionTimes[book.id] = now
        prefs.edit().putInt("${book.id}.chapter", position.chapter).putInt("${book.id}.offset", position.offset)
            .putLong("${book.id}.readAt", now).apply()
    }

    // Keep explicit text navigation independent of audio; the newer checkpoint wins on re-entry.
    internal fun listeningPosition(book: Book): ListeningCheckpoint? =
        latestListeningCheckpoint(book.id)?.takeIf { it.updatedAt > (positionTimes[book.id] ?: 0L) }

    fun resumePosition(book: Book): ReadingPosition {
        listeningPosition(book)?.let { checkpoint ->
            val index = book.chapters.indexOfFirst { it.id == checkpoint.chapterId }
            if (index >= 0) return ReadingPosition(index, checkpoint.textOffset.coerceIn(0, book.chapters[index].text.length))
        }
        return positions[book.id] ?: ReadingPosition()
    }

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
        if (bookmarks.any { it.key == value.key }) bookmarks.removeAll { it.key == value.key }
        else bookmarks.add(0, value)
        val array = JSONArray()
        bookmarks.forEach { array.put(JSONObject().put("book", it.bookId).put("chapter", it.chapter).put("offset", it.offset).put("quote", it.quote)) }
        prefs.edit().putString("bookmarks", array.toString()).apply()
    }
    fun commentFor(bookId: String, chapter: Int, sentence: SentenceRange): SentenceComment? =
        comments.firstOrNull { it.bookId == bookId && it.chapter == chapter && it.matches(sentence) }
    fun saveComment(bookId: String, chapter: Int, sentence: SentenceRange, body: String) {
        val value = SentenceComment(bookId, chapter, sentence.start, sentence.end, sentence.cleanText, body.trim())
        comments.removeAll { it.key == value.key }
        comments.add(0, value)
        val array = JSONArray()
        comments.forEach {
            array.put(JSONObject().put("book", it.bookId).put("chapter", it.chapter).put("start", it.start).put("end", it.end).put("quote", it.quote).put("body", it.body).put("createdAt", it.createdAt))
        }
        prefs.edit().putString("comments", array.toString()).apply()
    }
    fun progress(book: Book): Float {
        val pos = resumePosition(book)
        val total = book.chapters.sumOf { it.text.length }
        val done = book.chapters.take(pos.chapter).sumOf { it.text.length } + pos.offset
        return if (total == 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }
}
