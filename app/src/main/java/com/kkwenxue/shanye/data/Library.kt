package com.kkwenxue.shanye.data

import androidx.compose.runtime.mutableStateListOf

data class Chapter(val id: String, val title: String, val body: String, val bodySha256: String? = null) {
    val text: String by lazy { if(body.isBlank()) "" else body.trimIndent().trim().split(Regex("\n\\s*\n"))
        .joinToString("\n\n") { "　　" + it.trim().replace("\n", "") }
    }
    internal val audioSourceRevision: String by lazy {
        syncSha256("$id:${syncSha256(text)}:reader-canonical+OpenCC-t2s-v1:reader-sentence-bounded160-v1")
    }
}

data class Book(
    val id: String, val title: String, val author: String, val genre: String,
    val subtitle: String, val description: String, val cover: Int, val chapters: List<Chapter>
    , val remoteSource: String? = null
    , val remoteBookId: Long? = null
    , val activeRevision: String? = null
    , val remoteLatestChapterId: Long = 0L
    , val remoteLatestVersion: String? = null
    , val legacyBookId: String? = null
)

internal val Book.isLegacyOnlineImport: Boolean
    get() = remoteBookId == null && id.startsWith("local-") && description.startsWith("从在线书库导入")

/** The reading shelf is populated exclusively by online catalog imports. */
object Library {
    val books = mutableStateListOf<Book>()

    fun loadForBackground(context: android.content.Context): List<Book> =
        BookDatabase(context).use { it.load() }

    fun restoreImported(imported: List<Book>) {
        books.clear()
        books.addAll(imported)
    }

    fun addImported(book: Book) {
        val index = books.indexOfFirst { it.id == book.id }
        if (index >= 0) books[index] = book else books.add(book)
    }

    fun book(id: String): Book = books.firstOrNull { it.id == id }
        ?: error("书籍尚未从在线书库导入")
}
