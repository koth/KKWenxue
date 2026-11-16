package com.kkwenxue.shanye.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.FileOutputStream

/** SQLite source of truth for imported books and their ordered chapters. */
class BookDatabase(private val context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {
    init { copySeedDatabaseIfNeeded() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS books (id TEXT PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL, genre TEXT NOT NULL, subtitle TEXT NOT NULL, description TEXT NOT NULL, cover INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chapters (id TEXT PRIMARY KEY, book_id TEXT NOT NULL, chapter_index INTEGER NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS chapters_book_index ON chapters(book_id, chapter_index)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) onCreate(db)
    }

    fun save(book: Book) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("books", null, bookValues(book), SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("chapters", "book_id = ?", arrayOf(book.id))
            book.chapters.forEachIndexed { index, chapter ->
                db.insert("chapters", null, ContentValues().apply {
                    put("id", chapter.id); put("book_id", book.id); put("chapter_index", index)
                    put("title", chapter.title); put("body", chapter.body)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun load(): List<Book> {
        val db = readableDatabase
        val result = mutableListOf<Book>()
        db.query("books", null, null, null, null, null, "rowid ASC").use { books ->
            while (books.moveToNext()) {
                val id = books.getString(books.getColumnIndexOrThrow("id"))
                val chapters = mutableListOf<Chapter>()
                db.query("chapters", arrayOf("id", "title", "body"), "book_id = ?", arrayOf(id), null, null, "chapter_index ASC").use { rows ->
                    while (rows.moveToNext()) chapters += Chapter(rows.getString(0), rows.getString(1), rows.getString(2))
                }
                if (chapters.isNotEmpty()) result += Book(
                    id,
                    books.getString(books.getColumnIndexOrThrow("title")),
                    books.getString(books.getColumnIndexOrThrow("author")),
                    books.getString(books.getColumnIndexOrThrow("genre")),
                    books.getString(books.getColumnIndexOrThrow("subtitle")),
                    books.getString(books.getColumnIndexOrThrow("description")),
                    books.getInt(books.getColumnIndexOrThrow("cover")),
                    chapters
                )
            }
        }
        return result
    }

    private fun bookValues(book: Book) = ContentValues().apply {
        put("id", book.id); put("title", book.title); put("author", book.author); put("genre", book.genre)
        put("subtitle", book.subtitle); put("description", book.description); put("cover", book.cover)
    }

    private fun copySeedDatabaseIfNeeded() {
        val target = context.getDatabasePath(NAME)
        if (target.exists()) return
        val seed = runCatching { context.assets.open("shanye.db") }.getOrNull() ?: return
        target.parentFile?.mkdirs()
        seed.use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
    }

    companion object {
        private const val NAME = "shanye.db"
        private const val VERSION = 1
    }
}
