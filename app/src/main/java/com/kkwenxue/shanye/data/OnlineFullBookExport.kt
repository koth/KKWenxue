package com.kkwenxue.shanye.data

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.time.Duration

/** Explicit ZIP export retains the server task and verified result across pause/restart. */
class OnlineFullBookExportWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val bookId=inputData.getString("book_id") ?: return Result.failure()
        val prefs=applicationContext.getSharedPreferences("online-exports",Context.MODE_PRIVATE)
        try {
            val client=CatalogClient()
            var task=runInterruptible(Dispatchers.IO){
                val book=BookDatabase(applicationContext).use{it.load(bookId).firstOrNull()} ?: error("书籍已不在书架")
                val remote=book.remoteBookId ?: error("书籍不属于在线书库")
                val existing=prefs.getString("task:$bookId",null)?.takeIf{prefs.getString("revision:$bookId",null)==book.activeRevision}
                    ?.let{runCatching{client.status(it)}.getOrNull()}
                (existing?.takeIf{it.failureMessage==null} ?: client.startBundle(remote)).also {
                    if(it.taskId!=prefs.getString("task:$bookId",null))File(applicationContext.filesDir,"online-exports/${syncSha256(bookId)}.part").delete()
                    prefs.edit().putString("task:$bookId",it.taskId).putString("revision:$bookId",book.activeRevision).commit()
                }
            }
            repeat(30) {
                if(task.done)return@repeat
                task.failureMessage?.let{error(it)}
                setProgress(workDataOf("phase" to "正在准备整本导出包"))
                delay(2000)
                task=runInterruptible(Dispatchers.IO){client.status(task.taskId)}
            }
            if(!task.done)return Result.retry()
            require(task.sha256?.matches(Regex("[a-fA-F0-9]{64}"))==true && task.bytes!=null && task.bytes in 1L..CatalogClient.MAX_PACKAGE.toLong()) { "整本导出包缺少有效校验信息" }
            val root=File(applicationContext.filesDir,"online-exports").apply{mkdirs()}
            val target=File(root,syncSha256(bookId)+".zip")
            val temp=File(root,syncSha256(bookId)+".part")
            setProgress(workDataOf("phase" to "正在下载导出包"))
            var reportedAt=0L
            val done=runInterruptible(Dispatchers.IO){client.download(task,temp){bytes->
                val now=android.os.SystemClock.elapsedRealtime()
                if(now-reportedAt>=250 || bytes==task.bytes){reportedAt=now;setProgressAsync(workDataOf("phase" to "正在下载导出包", "received" to bytes,"total" to (task.bytes ?: 0L)))}
            }}
            if(!done)return Result.retry()
            runInterruptible(Dispatchers.IO) {
                check(temp.renameTo(target)) { "无法保存整本导出包" }
                prefs.edit().putString("path:$bookId",target.absolutePath).commit()
            }
            return Result.success(workDataOf("phase" to "整本导出包已就绪", "path" to target.absolutePath))
        } catch(cancelled: CancellationException) { throw cancelled
        } catch(error: java.io.IOException) { return Result.retry()
        } catch(error: Exception) { return Result.failure(workDataOf("error" to (error.message ?: "整本导出失败"))) }
    }
    companion object {
        fun name(book: Book)="online-export:${book.id}"
        fun savedPath(context: Context,book: Book):String?=context.getSharedPreferences("online-exports",Context.MODE_PRIVATE)
            .getString("path:${book.id}",null)?.takeIf{File(it).isFile}
        fun enqueue(context: Context,book: Book) {
            val request=OneTimeWorkRequestBuilder<OnlineFullBookExportWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("book_id" to book.id)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,Duration.ofSeconds(10)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(book),ExistingWorkPolicy.REPLACE,request)
        }
        fun cancel(context: Context,book: Book) { WorkManager.getInstance(context).cancelUniqueWork(name(book)) }
    }
}
