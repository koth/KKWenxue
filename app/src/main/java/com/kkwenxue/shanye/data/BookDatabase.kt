package com.kkwenxue.shanye.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** SQLite source of truth for imported books and their ordered chapters. */
class BookDatabase(private val context: Context, name: String = NAME) : SQLiteOpenHelper(context, name, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS books (id TEXT PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL, genre TEXT NOT NULL, subtitle TEXT NOT NULL, description TEXT NOT NULL, cover INTEGER NOT NULL, remote_source TEXT, remote_book_id INTEGER, catalog_book_id INTEGER, active_revision TEXT, remote_latest_chapter_id INTEGER NOT NULL DEFAULT 0, remote_latest_version TEXT, legacy_book_id TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chapters (id TEXT PRIMARY KEY, book_id TEXT NOT NULL, chapter_index INTEGER NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, body_sha256 TEXT, FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS chapters_book_index ON chapters(book_id, chapter_index)")
        createSyncTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) onCreate(db)
        if (oldVersion < 2) {
            addColumnIfMissing(db, "books", "remote_source TEXT")
            addColumnIfMissing(db, "books", "remote_book_id INTEGER")
            addColumnIfMissing(db, "books", "active_revision TEXT")
            addColumnIfMissing(db, "books", "remote_latest_chapter_id INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "books", "remote_latest_version TEXT")
            createSyncTables(db)
        }
        if (oldVersion < 3) {
            addColumnIfMissing(db, "online_bindings", "latest_title TEXT")
            addColumnIfMissing(db, "online_bindings", "latest_count INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "sync_jobs", "task_id TEXT")
        }
        if (oldVersion < 4) addColumnIfMissing(db, "books", "legacy_book_id TEXT")
        if (oldVersion < 5) {
            addColumnIfMissing(db, "online_bindings", "rollback_hold INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE online_bindings SET rollback_hold=1 WHERE sync_state='rolled_back'")
        }
        if (oldVersion < 6) createChapterCacheTable(db)
        if (oldVersion < 7) addChapterCacheProtectionColumn(db)
        if (oldVersion < 8) addColumnIfMissing(db, "chapters", "body_sha256 TEXT")
        if (oldVersion < 9) createProtectionTable(db)
        if (oldVersion < 10) addColumnIfMissing(db, "protected_chapters", "flags INTEGER NOT NULL DEFAULT 1")
        if (oldVersion < 11) {
            addColumnIfMissing(db, "books", "catalog_book_id INTEGER")
            db.execSQL("UPDATE books SET catalog_book_id=remote_book_id WHERE catalog_book_id IS NULL")
        }
        if (oldVersion < 12) migrateSourceScopedChapterCache(db)
    }

    fun save(book: Book, expectedRevision: String? = null, job: OnlineSyncJob? = null) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (expectedRevision != null) {
                val current = db.rawQuery(
                    "SELECT active_revision FROM books WHERE id=?",
                    arrayOf(book.id)
                ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else null }
                check(current == expectedRevision) { "本地书籍版本已变化，请重新检查更新" }
            }
            val previous = if (book.catalogBookId != null && !book.activeRevision.isNullOrBlank()) {
                db.rawQuery(
                    "SELECT active_revision FROM books WHERE id=? AND active_revision IS NOT NULL",
                    arrayOf(book.id)
                ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else null }
            } else null
            val previousBook = if (previous != null) load(book.id).firstOrNull() else null
            val previousChapters = if (previousBook != null && previous != book.activeRevision) previousBook.chapters else emptyList()
            if (book.catalogBookId != null && previousBook != null) {
                val next = book.chapters.associateBy { it.id }
                val sourceKey = book.catalogSourceKey.orEmpty()
                previousBook.chapters.forEach { old ->
                    val ref = next[old.id]
                    val cachedHash = latestChapterCache(book.id, old.id, sourceKey)?.sha256
                    if (ref == null || ref.title != old.title ||
                        (ref.bodySha256 != null && cachedHash != null && ref.bodySha256 != cachedHash)) {
                        db.delete(
                            "online_chapter_cache",
                            "book_id=? AND source_key=? AND chapter_id=?",
                            arrayOf(book.id, sourceKey, old.id)
                        )
                    }
                }
            }
            val values = bookValues(book)
            if (db.update("books", values, "id=?", arrayOf(book.id)) == 0) db.insertOrThrow("books", null, values)
            db.delete("chapters", "book_id = ?", arrayOf(book.id))
            book.chapters.forEachIndexed { index, chapter ->
                db.insertOrThrow("chapters", null, ContentValues().apply {
                    put("id", chapter.id); put("book_id", book.id); put("chapter_index", index)
                    put("title", chapter.title); put("body", if (book.catalogBookId != null) "" else chapter.body); put("body_sha256", chapter.bodySha256)
                })
            }
            if (book.catalogBookId != null) {
                val values = ContentValues().apply {
                    put("source", book.catalogSourceKey ?: book.remoteSource ?: "suduguu")
                    put("remote_book_id", book.catalogBookId)
                    put("remote_latest_chapter_id", book.remoteLatestChapterId)
                    put("active_revision", book.activeRevision)
                    put("last_sync_at", System.currentTimeMillis())
                    put("sync_state", if (job == null) "idle" else "completed")
                    put("rollback_hold", 0)
                    putNull("last_error")
                }
                if (db.update("online_bindings", values, "book_id=?", arrayOf(book.id)) == 0) {
                    values.put("book_id", book.id)
                    db.insertOrThrow("online_bindings", null, values)
                }
            }
            if (!previous.isNullOrBlank() && previous != book.activeRevision) {
                    val exists = db.rawQuery(
                        "SELECT 1 FROM book_revisions WHERE book_id=? AND revision=?",
                        arrayOf(book.id, previous)
                    ).use { it.moveToFirst() }
                    if (!exists) {
                        val revisionId = db.insertOrThrow("book_revisions", null, ContentValues().apply {
                            put("book_id", book.id); put("revision", previous)
                            put("created_at", System.currentTimeMillis())
                        })
                        previousChapters.forEachIndexed { index, chapter ->
                            db.insertOrThrow("revision_chapters", null, ContentValues().apply {
                                put("revision_row_id", revisionId)
                                put("chapter_id", chapter.id); put("chapter_index", index)
                                put("title", chapter.title); put("body", chapter.body)
                            })
                        }
                    }
                    trimRevisions(db, book.id)
            }
            if (job != null) writeSyncJob(db, job.copy(state = "completed", error = null))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun load(bookId: String? = null): List<Book> {
        val db = readableDatabase
        val result = mutableListOf<Book>()
        db.query("books", null, bookId?.let { "id=?" }, bookId?.let { arrayOf(it) }, null, null, "rowid ASC").use { books ->
            while (books.moveToNext()) {
                val id = books.getString(books.getColumnIndexOrThrow("id"))
                val chapters = mutableListOf<Chapter>()
                db.query("chapters", arrayOf("id", "title", "body", "body_sha256"), "book_id = ?", arrayOf(id), null, null, "chapter_index ASC").use { rows ->
                    while (rows.moveToNext()) chapters += Chapter(
                        rows.getString(0),
                        rows.getString(1),
                        if ((books.getLongOrNull("catalog_book_id")
                                ?: books.getLongOrNull("remote_book_id")) != null) "" else rows.getString(2),
                        rows.getString(3)
                    )
                }
                if (chapters.isNotEmpty()) result += Book(
                    id,
                    books.getString(books.getColumnIndexOrThrow("title")),
                    books.getString(books.getColumnIndexOrThrow("author")),
                    books.getString(books.getColumnIndexOrThrow("genre")),
                    books.getString(books.getColumnIndexOrThrow("subtitle")),
                    books.getString(books.getColumnIndexOrThrow("description")),
                    books.getInt(books.getColumnIndexOrThrow("cover")),
                    chapters,
                    books.getStringOrNull("remote_source"),
                    books.getLongOrNull("remote_book_id"),
                    books.getStringOrNull("active_revision"),
                    books.getLongOrNull("remote_latest_chapter_id") ?: 0L,
                    books.getStringOrNull("remote_latest_version"),
                    books.getStringOrNull("legacy_book_id"),
                    books.getLongOrNull("catalog_book_id") ?: books.getLongOrNull("remote_book_id")
                )
            }
        }
        return result
    }

    private fun bookValues(book: Book) = ContentValues().apply {
        put("id", book.id); put("title", book.title); put("author", book.author); put("genre", book.genre)
        put("subtitle", book.subtitle); put("description", book.description); put("cover", book.cover)
        put("remote_source", book.remoteSource); put("remote_book_id", book.catalogBookId)
        put("catalog_book_id", book.catalogBookId)
        put("active_revision", book.activeRevision); put("remote_latest_chapter_id", book.remoteLatestChapterId)
        put("remote_latest_version", book.remoteLatestVersion)
        put("legacy_book_id", book.legacyBookId)
    }

    internal fun saveOnlineImport(online: Book): Book {
        return load(online.id).singleOrNull() ?: online.also { save(it) }
    }

    internal fun linkLegacy(legacyId: String, online: Book): Book {
        val db = writableDatabase
        val old = load(legacyId).singleOrNull()
        require(old != null && old.isLegacyOnlineImport && online.id == "online:catalog:${online.catalogBookId}" &&
            online.catalogBookId != null && !online.activeRevision.isNullOrBlank()) { "旧版书籍或在线正文无效" }
        val existing = db.rawQuery(
            "SELECT 1 FROM books WHERE id=? OR legacy_book_id=? LIMIT 1", arrayOf(online.id, legacyId)
        ).use { it.moveToFirst() }
        check(!existing) { "这本书已经在书架或已关联在线书库" }
        return online.copy(legacyBookId = legacyId, cover = old.cover).also(::save)
    }

    fun rollback(bookId: String): Book? {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val current = load(bookId).firstOrNull() ?: return null
            val revision = db.rawQuery(
                "SELECT rowid,revision FROM book_revisions WHERE book_id=? AND revision<>? ORDER BY created_at DESC,rowid DESC LIMIT 1",
                arrayOf(bookId, current.activeRevision ?: return null)
            ).use { rows -> if (rows.moveToFirst()) rows.getLong(0) to rows.getString(1) else null }
                ?: return null
            val chapters = mutableListOf<Chapter>()
            db.rawQuery(
                "SELECT chapter_id,title,body FROM revision_chapters WHERE revision_row_id=? ORDER BY chapter_index",
                arrayOf(revision.first.toString())
            ).use { rows -> while (rows.moveToNext()) chapters += Chapter(rows.getString(0), rows.getString(1), rows.getString(2)) }
            if (chapters.isEmpty()) return null
            db.delete("chapters", "book_id=?", arrayOf(bookId))
            chapters.forEachIndexed { index, chapter ->
                db.insertOrThrow("chapters", null, ContentValues().apply {
                    put("id", chapter.id); put("book_id", bookId); put("chapter_index", index)
                    put("title", chapter.title); put("body", chapter.body)
                })
            }
            val subtitle = "${chapters.size} 章 · 在线同步"
            db.execSQL("UPDATE books SET active_revision=?,subtitle=? WHERE id=?", arrayOf(revision.second, subtitle, bookId))
            db.execSQL("UPDATE online_bindings SET active_revision=?,sync_state='rolled_back',rollback_hold=1 WHERE book_id=?", arrayOf(revision.second, bookId))
            db.setTransactionSuccessful()
            current.copy(chapters = chapters, activeRevision = revision.second, subtitle = subtitle)
        } finally { db.endTransaction() }
    }

    fun hasRollback(bookId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM book_revisions r JOIN books b ON b.id=r.book_id WHERE r.book_id=? AND r.revision<>b.active_revision LIMIT 1",
        arrayOf(bookId)
    ).use { it.moveToFirst() }

    internal fun recordSyncJob(job: OnlineSyncJob) = writeSyncJob(writableDatabase, job)

    private fun writeSyncJob(db: SQLiteDatabase, job: OnlineSyncJob) {
        check(db.insertWithOnConflict("sync_jobs", null, ContentValues().apply {
            put("job_key", "${job.bookId}:${job.fromRevision}:${job.target}")
            put("book_id", job.bookId); put("from_revision", job.fromRevision); put("to_revision", job.target)
            put("state", job.state); put("updated_at", System.currentTimeMillis()); put("error", job.error)
            put("task_id", job.taskId)
        }, SQLiteDatabase.CONFLICT_REPLACE)
            != -1L) { "无法保存同步任务" }
    }

    internal fun syncJob(book: Book, target: String): OnlineSyncJob? = readableDatabase.rawQuery(
        "SELECT state,task_id,error FROM sync_jobs WHERE job_key=?",
        arrayOf("${book.id}:${book.activeRevision}:$target")
    ).use { if (it.moveToFirst()) OnlineSyncJob(book.id, book.activeRevision.orEmpty(), target, it.getString(0), it.getString(1), it.getString(2)) else null }

    internal fun onlineStatus(bookId: String): OnlineSyncStatus = readableDatabase.rawQuery(
        "SELECT sync_state,last_error,last_checked_at,latest_title,latest_count FROM online_bindings WHERE book_id=?",
        arrayOf(bookId)
    ).use {
        if (it.moveToFirst()) OnlineSyncStatus(it.getString(0), it.getString(1), it.getLong(2), it.getString(3), it.getLong(4))
        else OnlineSyncStatus("idle")
    }

    internal fun rollbackHeld(bookId: String): Boolean = readableDatabase.rawQuery(
        "SELECT rollback_hold FROM online_bindings WHERE book_id=?", arrayOf(bookId)
    ).use { it.moveToFirst() && it.getInt(0) != 0 }

    internal fun markOnlineStatus(bookId: String, status: OnlineSyncStatus) {
        writableDatabase.update("online_bindings", ContentValues().apply {
            put("last_checked_at", status.checkedAt); put("sync_state", status.state); put("last_error", status.message)
            put("latest_title", status.latestTitle); put("latest_count", status.latestCount)
        }, "book_id=?", arrayOf(bookId))
    }

    private fun trimRevisions(db: SQLiteDatabase, bookId: String) {
        db.execSQL(
            "DELETE FROM revision_chapters WHERE revision_row_id IN " +
                "(SELECT rowid FROM book_revisions WHERE book_id=? AND rowid NOT IN " +
                "(SELECT rowid FROM book_revisions WHERE book_id=? ORDER BY created_at DESC,rowid DESC LIMIT 1))",
            arrayOf(bookId, bookId)
        )
        db.execSQL(
            "DELETE FROM book_revisions WHERE book_id=? AND rowid NOT IN " +
                "(SELECT rowid FROM book_revisions WHERE book_id=? ORDER BY created_at DESC,rowid DESC LIMIT 1)",
            arrayOf(bookId, bookId)
        )
    }

    private fun createSyncTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS online_bindings (book_id TEXT PRIMARY KEY, source TEXT NOT NULL, remote_book_id INTEGER NOT NULL, remote_latest_chapter_id INTEGER NOT NULL DEFAULT 0, active_revision TEXT, last_checked_at INTEGER NOT NULL DEFAULT 0, last_sync_at INTEGER NOT NULL DEFAULT 0, sync_state TEXT NOT NULL DEFAULT 'idle', last_error TEXT, latest_title TEXT, latest_count INTEGER NOT NULL DEFAULT 0, rollback_hold INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS book_revisions (book_id TEXT NOT NULL, revision TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(book_id, revision))")
        db.execSQL("CREATE TABLE IF NOT EXISTS revision_chapters (revision_row_id INTEGER NOT NULL, chapter_id TEXT NOT NULL, chapter_index INTEGER NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, PRIMARY KEY(revision_row_id, chapter_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_jobs (job_key TEXT PRIMARY KEY, book_id TEXT NOT NULL, from_revision TEXT, to_revision TEXT, state TEXT NOT NULL, updated_at INTEGER NOT NULL, error TEXT, task_id TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS sync_jobs_book ON sync_jobs(book_id, updated_at)")
        createChapterCacheTable(db)
    }

    private fun createProtectionTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS protected_chapters(book_id TEXT NOT NULL,source_key TEXT NOT NULL DEFAULT '',chapter_id TEXT NOT NULL,flags INTEGER NOT NULL DEFAULT 1,PRIMARY KEY(book_id,source_key,chapter_id))")
    }

    private fun createChapterCacheTable(db: SQLiteDatabase) {
        createProtectionTable(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS online_chapter_cache (book_id TEXT NOT NULL, source_key TEXT NOT NULL DEFAULT '', chapter_id TEXT NOT NULL, revision TEXT NOT NULL, path TEXT NOT NULL, bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, etag TEXT, state TEXT NOT NULL, protected INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, PRIMARY KEY(book_id, source_key, chapter_id, revision))")
        db.execSQL("CREATE INDEX IF NOT EXISTS online_chapter_cache_book ON online_chapter_cache(book_id, updated_at DESC)")
    }

    private fun migrateSourceScopedChapterCache(db: SQLiteDatabase) {
        val cacheExists = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='online_chapter_cache'",
            null
        ).use { it.moveToFirst() }
        if (cacheExists) {
            db.execSQL("ALTER TABLE online_chapter_cache RENAME TO online_chapter_cache_legacy_v11")
            db.execSQL("CREATE TABLE online_chapter_cache (book_id TEXT NOT NULL, source_key TEXT NOT NULL DEFAULT '', chapter_id TEXT NOT NULL, revision TEXT NOT NULL, path TEXT NOT NULL, bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, etag TEXT, state TEXT NOT NULL, protected INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, PRIMARY KEY(book_id, source_key, chapter_id, revision))")
            db.execSQL(
                "INSERT OR IGNORE INTO online_chapter_cache(book_id,source_key,chapter_id,revision,path,bytes,sha256,etag,state,protected,updated_at) " +
                    "SELECT c.book_id,CASE WHEN b.remote_source='catalog' THEN '' ELSE COALESCE(b.remote_source,'') END,c.chapter_id,c.revision,c.path,c.bytes,c.sha256,c.etag,c.state,c.protected,c.updated_at " +
                    "FROM online_chapter_cache_legacy_v11 c LEFT JOIN books b ON b.id=c.book_id"
            )
            db.execSQL("DROP TABLE online_chapter_cache_legacy_v11")
        } else {
            createChapterCacheTable(db)
        }
        val protectionExists = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='protected_chapters'",
            null
        ).use { it.moveToFirst() }
        if (protectionExists) {
            val hasSource = db.rawQuery("PRAGMA table_info(protected_chapters)", null).use { rows ->
                var found = false
                while (rows.moveToNext()) if (rows.getString(1) == "source_key") found = true
                found
            }
            if (!hasSource) {
                db.execSQL("ALTER TABLE protected_chapters RENAME TO protected_chapters_legacy_v11")
                db.execSQL("CREATE TABLE protected_chapters(book_id TEXT NOT NULL,source_key TEXT NOT NULL DEFAULT '',chapter_id TEXT NOT NULL,flags INTEGER NOT NULL DEFAULT 1,PRIMARY KEY(book_id,source_key,chapter_id))")
                db.execSQL(
                    "INSERT OR IGNORE INTO protected_chapters(book_id,source_key,chapter_id,flags) " +
                        "SELECT p.book_id,CASE WHEN b.remote_source='catalog' THEN '' ELSE COALESCE(b.remote_source,'') END,p.chapter_id,p.flags " +
                        "FROM protected_chapters_legacy_v11 p LEFT JOIN books b ON b.id=p.book_id"
                )
                db.execSQL("DROP TABLE protected_chapters_legacy_v11")
            }
        } else {
            createProtectionTable(db)
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS online_chapter_cache_book ON online_chapter_cache(book_id, updated_at DESC)")
    }

    private fun addChapterCacheProtectionColumn(db: SQLiteDatabase) {
        val exists = db.rawQuery("PRAGMA table_info(online_chapter_cache)", null).use { rows ->
            var found = false
            while (rows.moveToNext()) if (rows.getString(1) == "protected") found = true
            found
        }
        if (!exists) db.execSQL("ALTER TABLE online_chapter_cache ADD COLUMN protected INTEGER NOT NULL DEFAULT 0")
    }

    internal data class ChapterCache(val path: String, val bytes: Long, val sha256: String, val etag: String?)

    internal fun chapterCache(bookId: String, chapterId: String, revision: String, sourceKey: String = ""): ChapterCache? =
        readableDatabase.rawQuery(
            "SELECT path,bytes,sha256,etag FROM online_chapter_cache WHERE book_id=? AND source_key=? AND chapter_id=? AND revision=?",
            arrayOf(bookId, sourceKey, chapterId, revision)
        ).use { rows ->
            if (!rows.moveToFirst()) null else ChapterCache(rows.getString(0), rows.getLong(1), rows.getString(2), rows.getString(3).takeUnless { rows.isNull(3) })
        }

    internal fun latestChapterCache(bookId: String, chapterId: String, sourceKey: String = ""): ChapterCache? =
        readableDatabase.rawQuery(
            "SELECT path,bytes,sha256,etag FROM online_chapter_cache WHERE book_id=? AND source_key=? AND chapter_id=? ORDER BY updated_at DESC LIMIT 1",
            arrayOf(bookId, sourceKey, chapterId)
        ).use { rows ->
            if (!rows.moveToFirst()) null else ChapterCache(rows.getString(0), rows.getLong(1), rows.getString(2), rows.getString(3).takeUnless { rows.isNull(3) })
        }

    internal fun saveChapterCache(bookId: String, chapterId: String, revision: String, path: String, bytes: Long, sha256: String, etag: String?, sourceKey: String = "") {
        val db = writableDatabase
        val protected = db.rawQuery("SELECT 1 FROM protected_chapters WHERE book_id=? AND source_key=? AND chapter_id=?", arrayOf(bookId,sourceKey,chapterId)).use {it.moveToFirst()}
        db.insertWithOnConflict("online_chapter_cache", null, ContentValues().apply {
            put("book_id", bookId); put("source_key", sourceKey); put("chapter_id", chapterId); put("revision", revision)
            put("path", path); put("bytes", bytes); put("sha256", sha256); put("etag", etag)
            put("state", "ready"); put("protected", if (protected) 1 else 0); put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    internal fun deleteChapterCache(bookId: String, chapterId: String, sourceKey: String = "") {
        writableDatabase.delete("online_chapter_cache", "book_id=? AND source_key=? AND chapter_id=?", arrayOf(bookId, sourceKey, chapterId))
    }

    internal fun touchChapterCache(bookId: String, chapterId: String, revision: String, sourceKey: String = "") {
        writableDatabase.execSQL(
            "UPDATE online_chapter_cache SET updated_at=? WHERE book_id=? AND source_key=? AND chapter_id=? AND revision=?",
            arrayOf<Any>(System.currentTimeMillis(), bookId, sourceKey, chapterId, revision)
        )
    }

    internal fun deleteChapterCachePath(path: String) {
        writableDatabase.delete("online_chapter_cache", "path=?", arrayOf(path))
    }

    internal fun setChapterCacheProtected(bookId: String, chapterId: String, protected: Boolean, flag: Int=1, sourceKey: String = "") {
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO protected_chapters(book_id,source_key,chapter_id,flags) VALUES(?,?,?,0)",arrayOf(bookId,sourceKey,chapterId))
            db.execSQL("UPDATE protected_chapters SET flags=flags ${if(protected) "|" else "&"} ? WHERE book_id=? AND source_key=? AND chapter_id=?",arrayOf<Any>(if(protected)flag else flag.inv(),bookId,sourceKey,chapterId))
            db.delete("protected_chapters","book_id=? AND source_key=? AND chapter_id=? AND flags=0",arrayOf(bookId,sourceKey,chapterId))
            db.execSQL("UPDATE online_chapter_cache SET protected=EXISTS(SELECT 1 FROM protected_chapters p WHERE p.book_id=online_chapter_cache.book_id AND p.source_key=online_chapter_cache.source_key AND p.chapter_id=online_chapter_cache.chapter_id) WHERE book_id=? AND source_key=? AND chapter_id=?",arrayOf(bookId,sourceKey,chapterId))
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }

    internal fun protectedCachePaths(): Set<String> = readableDatabase.rawQuery(
        "SELECT path FROM online_chapter_cache WHERE protected=1", null
    ).use { rows ->
        buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String) {
        val name = column.substringBefore(' ')
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { rows ->
            var found = false
            while (rows.moveToNext()) if (rows.getString(1) == name) found = true
            found
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column")
    }

    private fun android.database.Cursor.getStringOrNull(name: String): String? =
        getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun android.database.Cursor.getLongOrNull(name: String): Long? =
        getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

    companion object {
        private const val NAME = "shanye.db"
        private const val VERSION = 12
    }
}
