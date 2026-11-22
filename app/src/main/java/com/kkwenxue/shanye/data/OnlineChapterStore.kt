package com.kkwenxue.shanye.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Two network slots, with one reserved for interactive reading. */
internal object ChapterRequests {
    private val all = Semaphore(2)
    private val background = Semaphore(1)
    private val foreground = AtomicInteger()
    suspend fun <T> run(interactive: Boolean, block: suspend () -> T): T {
        if (interactive) {
            foreground.incrementAndGet()
            try { return all.withPermit { block() } } finally { foreground.decrementAndGet() }
        }
        return background.withPermit {
            while (foreground.get() > 0) delay(10)
            all.withPermit { block() }
        }
    }
}

/** Verified file bodies live outside Book. A manifest row alone never counts as a body cache. */
class OnlineChapterStore(context: Context, private val maxCacheBytes: Long = 512L * 1024 * 1024) {
    private val context = context.applicationContext
    private val root = File(this.context.filesDir, "online-chapters").apply { mkdirs() }

    suspend fun load(book: Book, chapterIndex: Int, client: CatalogClient = CatalogClient(), interactive: Boolean = true): Book {
        val chapter = book.chapters.getOrNull(chapterIndex) ?: error("章节不在目录中")
        if (book.remoteBookId == null || chapter.body.isNotBlank()) return book
        val key = syncSha256("${book.id}:${chapter.id}")
        val body = locks.getOrPut(key) { Mutex() }.withLock {
            val local = runInterruptible(Dispatchers.IO) { readCached(book, chapter, key) }
            local ?: ChapterRequests.run(interactive) {
                runInterruptible(Dispatchers.IO) {
                    val sourceId = chapter.id.substringAfter(":source:", "").toLongOrNull() ?: error("章节身份无效")
                    val content = client.chapter(book.remoteBookId, sourceId, revision = book.activeRevision) ?: error("章节正文未返回")
                    require(content.bookId == book.remoteBookId && content.sourceId == sourceId && content.chapterId == chapter.id) { "章节身份不匹配" }
                    require(content.revision == book.activeRevision) { "章节目录版本已变化，请返回书架刷新" }
                    require(content.bytes in 1..(2L * 1024 * 1024) && content.body.toByteArray(Charsets.UTF_8).size.toLong() == content.bytes) { "章节大小校验失败" }
                    require(syncSha256(content.body) == content.sha256 && (chapter.bodySha256 == null || chapter.bodySha256 == content.sha256)) { "章节正文校验失败" }
                    val file = File(root, "$key.txt")
                    val temp = File(root, "$key-${UUID.randomUUID()}.tmp")
                    try {
                        temp.outputStream().use { it.write(content.body.toByteArray(Charsets.UTF_8)); it.fd.sync() }
                        check(temp.renameTo(file)) { "无法保存章节缓存" }
                        BookDatabase(context).use { db ->
                            // A concurrent manifest update may have invalidated this request.
                            val current = db.load(book.id).firstOrNull()
                            require(current == null || current.activeRevision == book.activeRevision) { "章节目录已更新，请重新打开" }
                            db.saveChapterCache(book.id, chapter.id, book.activeRevision.orEmpty(), file.name, content.bytes, content.sha256, content.etag)
                        }
                        trimCache(setOf(file.name))
                    } finally { temp.delete() }
                    content.body
                }
            }
        }
        return book.copy(chapters = book.chapters.mapIndexed { index, item ->
            if (index == chapterIndex) item.copy(body = body) else item
        })
    }

    private fun readCached(book: Book, chapter: Chapter, key: String): String? {
        val file = File(root, "$key.txt")
        return BookDatabase(context).use { db ->
            val cache = db.latestChapterCache(book.id, chapter.id) ?: return@use null
            if (!file.isFile) { db.deleteChapterCache(book.id, chapter.id); return@use null }
            val bytes = file.readBytes()
            val valid = bytes.size.toLong() == cache.bytes && bytes.size <= 2 * 1024 * 1024 &&
                syncSha256(bytes.toString(Charsets.UTF_8)) == cache.sha256 &&
                (chapter.bodySha256 == null || chapter.bodySha256 == cache.sha256)
            if (!valid) {
                file.delete(); db.deleteChapterCache(book.id, chapter.id); return@use null
            }
            file.setLastModified(System.currentTimeMillis())
            db.touchChapterCache(book.id, chapter.id, book.activeRevision.orEmpty())
            bytes.toString(Charsets.UTF_8)
        }
    }

    fun cached(book: Book,index: Int): Chapter? {
        val chapter=book.chapters.getOrNull(index) ?: return null
        if(chapter.body.isNotBlank())return chapter
        val body=readCached(book,chapter,syncSha256("${book.id}:${chapter.id}")) ?: return null
        return chapter.copy(body=body)
    }

    fun invalidate(bookId: String, chapterId: String) {
        BookDatabase(context).use { it.deleteChapterCache(bookId, chapterId) }
        File(root, syncSha256("$bookId:$chapterId") + ".txt").delete()
    }
    fun protect(bookId: String, chapterId: String, flag: Int=1) = BookDatabase(context).use { it.setChapterCacheProtected(bookId, chapterId, true,flag) }
    fun unprotect(bookId: String, chapterId: String, flag: Int=1) = BookDatabase(context).use { it.setChapterCacheProtected(bookId, chapterId, false,flag) }

    internal fun trimCache(keep: Set<String> = emptySet()) = synchronized(trimLock) {
        val files = root.listFiles { file -> file.isFile && file.extension == "txt" }.orEmpty()
        var bytes = files.sumOf { it.length() }
        BookDatabase(context).use { db ->
            val protected = db.protectedCachePaths() + keep
            for (file in files.sortedBy { it.lastModified() }) {
                if (bytes <= maxCacheBytes) break
                if (file.name in protected) continue
                val size = file.length()
                if (file.delete()) { bytes -= size; db.deleteChapterCachePath(file.name) }
            }
        }
    }
    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val trimLock = Any()
    }
}
