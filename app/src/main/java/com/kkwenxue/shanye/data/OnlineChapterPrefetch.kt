package com.kkwenxue.shanye.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runInterruptible
import java.time.Duration
import java.util.concurrent.TimeUnit

internal data class CacheJob(val id: String, val bookId: String, val scope: String, val state: String, val total: Int, val completed: Int, val failed: List<Pair<String,String>>)
internal data class CacheItem(val jobId: String, val bookId: String, val chapterId: String, val scope: String, val attempts: Int)

/** Stable chapter IDs and per-item checkpoints are persisted; WorkManager input stays under 10KB. */
internal class ChapterQueue(context: Context) : SQLiteOpenHelper(context, "online-cache-jobs.sqlite", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY,book_id TEXT NOT NULL,scope TEXT NOT NULL,state TEXT NOT NULL,updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE items(job_id TEXT NOT NULL,chapter_id TEXT NOT NULL,priority INTEGER NOT NULL,state TEXT NOT NULL DEFAULT 'queued',attempts INTEGER NOT NULL DEFAULT 0,next_at INTEGER NOT NULL DEFAULT 0,error TEXT,ordinal INTEGER NOT NULL,PRIMARY KEY(job_id,chapter_id))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun enqueue(book: Book, indices: List<Int>, scope: String): String {
        val id = "${book.id}:$scope"
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO jobs(id,book_id,scope,state,updated_at) VALUES(?,?,?,'running',?)", arrayOf<Any>(id,book.id,scope,System.currentTimeMillis()))
            if (scope == "adjacent") db.delete("items", "job_id=?", arrayOf(id))
            db.execSQL("UPDATE jobs SET state='running',updated_at=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(),id))
            val priority = when(scope) { "adjacent" -> 70; "range" -> 30; else -> 10 }
            indices.distinct().filter { it in book.chapters.indices }.forEachIndexed { order, index ->
                db.insertWithOnConflict("items", null, ContentValues().apply {
                    put("job_id",id); put("chapter_id",book.chapters[index].id); put("priority",if(scope=="adjacent" && order==2)40 else priority); put("ordinal",order)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if(scope!="adjacent")db.execSQL("UPDATE items SET state='queued' WHERE job_id=? AND chapter_id=? AND state='completed'",arrayOf(id,book.chapters[index].id))
            }
            db.execSQL("UPDATE items SET state='queued',attempts=0,next_at=0,error=NULL WHERE job_id=? AND state='failed'", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed(); return id
    }
    fun next(now: Long = System.currentTimeMillis()): CacheItem? = readableDatabase.rawQuery(
        "SELECT i.job_id,j.book_id,i.chapter_id,j.scope,i.attempts FROM items i JOIN jobs j ON j.id=i.job_id WHERE j.state='running' AND i.state='queued' AND i.next_at<=? ORDER BY i.priority DESC,j.updated_at DESC,i.ordinal LIMIT 1", arrayOf(now.toString())
    ).use { r -> if(!r.moveToFirst()) null else CacheItem(r.getString(0),r.getString(1),r.getString(2),r.getString(3),r.getInt(4)) }
    fun hasPending(): Boolean = readableDatabase.rawQuery("SELECT 1 FROM items i JOIN jobs j ON j.id=i.job_id WHERE j.state='running' AND i.state='queued' LIMIT 1", null).use { it.moveToFirst() }
    fun nextDelay(): Long? = readableDatabase.rawQuery("SELECT MIN(i.next_at) FROM items i JOIN jobs j ON j.id=i.job_id WHERE j.state='running' AND i.state='queued'",null).use { r ->
        if(!r.moveToFirst() || r.isNull(0))null else (r.getLong(0)-System.currentTimeMillis()).coerceAtLeast(0)
    }
    fun finish(item: CacheItem, error: String? = null, retryable: Boolean = true) {
        val attempts = item.attempts + if(error==null)0 else 1
        val state = if(error==null)"completed" else if(retryable && attempts<3)"queued" else "failed"
        writableDatabase.execSQL("UPDATE items SET state=?,attempts=?,next_at=?,error=? WHERE job_id=? AND chapter_id=?", arrayOf<Any?>(state,attempts,if(state=="queued")System.currentTimeMillis()+10000L*(1L shl attempts) else 0L,error,item.jobId,item.chapterId))
        changed()
    }
    fun pause(book: Book) { writableDatabase.execSQL("UPDATE jobs SET state='paused' WHERE book_id=?",arrayOf(book.id)); changed() }
    fun resume(book: Book) {
        writableDatabase.execSQL("UPDATE jobs SET state='running' WHERE book_id=?",arrayOf(book.id))
        writableDatabase.execSQL("UPDATE items SET state='queued',attempts=0,next_at=0,error=NULL WHERE state='failed' AND job_id IN (SELECT id FROM jobs WHERE book_id=?)",arrayOf(book.id)); changed()
    }
    fun jobs(bookId: String): List<CacheJob> = readableDatabase.rawQuery("SELECT id,scope,state FROM jobs WHERE book_id=? ORDER BY updated_at DESC",arrayOf(bookId)).use { jobs ->
        buildList {
            while(jobs.moveToNext()) {
                val id=jobs.getString(0)
                var total=0; var completed=0
                val failures=mutableListOf<Pair<String,String>>()
                readableDatabase.rawQuery("SELECT chapter_id,state,error FROM items WHERE job_id=? ORDER BY ordinal",arrayOf(id)).use { items ->
                    while(items.moveToNext()) { total++; if(items.getString(1)=="completed")completed++; if(items.getString(1)=="failed")failures+=items.getString(0) to items.getString(2).orEmpty() }
                }
                val state=if(jobs.getString(2)=="paused")"paused" else if(total==completed)"completed" else if(completed+failures.size==total)"partial" else "running"
                add(CacheJob(id,bookId,jobs.getString(1),state,total,completed,failures))
            }
        }
    }
    companion object {
        val changes = MutableStateFlow(0L)
        private fun changed() { changes.value=changes.value+1 }
    }
}

/** One background drain; interactive reading uses its reserved network slot. */
class OnlineChapterPrefetchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        try {
            repeat(100) {
                val item=runInterruptible(Dispatchers.IO) { ChapterQueue(applicationContext).use { it.next() } }
                if(item==null) {
                    val delay=runInterruptible(Dispatchers.IO){ChapterQueue(applicationContext).use { it.nextDelay() }}
                    if(delay!=null)wake(applicationContext,delay)
                    return Result.success()
                }
                try {
                    val book=runInterruptible(Dispatchers.IO){BookDatabase(applicationContext).use{it.load(item.bookId).firstOrNull()}} ?: error("书籍已不在书架")
                    val index=book.chapters.indexOfFirst { it.id==item.chapterId }
                    require(index>=0) { "章节已从目录移除" }
                    OnlineChapterStore(applicationContext).load(book,index,interactive=false)
                    if(item.scope!="adjacent")runInterruptible(Dispatchers.IO){OnlineChapterStore(applicationContext).protect(book.id,item.chapterId,sourceKey=book.catalogSourceKey.orEmpty())}
                    runInterruptible(Dispatchers.IO){ChapterQueue(applicationContext).use { it.finish(item) }}
                } catch(cancelled: CancellationException) { throw cancelled
                } catch(error: Exception) {
                    runInterruptible(Dispatchers.IO){ChapterQueue(applicationContext).use { it.finish(item,error.message ?: "章节下载失败",error !is IllegalArgumentException) }}
                }
            }
            // A successful batch starts fresh work; it must not accumulate retry
            // backoff merely because a large book has more than 100 chapters.
            wake(applicationContext)
            return Result.success()
        } catch(cancelled: CancellationException) { throw cancelled }
    }
    companion object {
        private const val WORK_NAME="online-chapter-queue"
        private fun wake(context: Context,delayMs: Long=0) {
            val work=OneTimeWorkRequestBuilder<OnlineChapterPrefetchWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delayMs,TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,Duration.ofSeconds(10)).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK_NAME,ExistingWorkPolicy.APPEND_OR_REPLACE,work)
        }
        fun enqueue(context: Context,book: Book,currentChapter: Int) = enqueueRange(context,book,listOf(currentChapter+1,currentChapter+2,currentChapter-1),"adjacent")
        fun enqueueRange(context: Context,book: Book,indices: List<Int>,scope: String="range") {
            if(book.catalogBookId==null)return
            ChapterQueue(context.applicationContext).use { it.enqueue(book,indices,scope) }; wake(context)
        }
        fun cancel(context: Context,book: Book) { ChapterQueue(context.applicationContext).use { it.pause(book) } }
        fun resume(context: Context,book: Book) { ChapterQueue(context.applicationContext).use { it.resume(book) }; wake(context) }
    }
}
