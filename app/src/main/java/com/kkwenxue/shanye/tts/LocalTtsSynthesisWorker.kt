package com.kkwenxue.shanye.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kkwenxue.shanye.R
import com.kkwenxue.shanye.data.AudioLibrary
import com.kkwenxue.shanye.data.LocalBookRepository
import com.kkwenxue.shanye.data.OnlineChapterStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Foreground WorkManager job that keeps sentence streaming alive after the reader leaves. */
class LocalTtsSynthesisWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo())
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val chapterIndex = inputData.getInt(KEY_CHAPTER_INDEX, -1)
        if (chapterIndex < 0) return Result.failure()
        val voiceId = inputData.getString(KEY_VOICE_ID) ?: LocalTtsRuntime.DEFAULT_VOICE_ID
        val runtime = LocalTtsRuntime(applicationContext)
        val player = LocalTtsStreamPlayer()
        try {
            val book = withContext(Dispatchers.IO) {
                LocalBookRepository(applicationContext).load().firstOrNull { it.id == bookId }
                    ?: error("书籍尚未从在线书库导入")
            }
            val hydrated = withContext(Dispatchers.IO) { OnlineChapterStore(applicationContext).load(book, chapterIndex) }
            val result = withContext(Dispatchers.IO) {
                runtime.prepare()
                runtime.synthesizeChapterStreaming(hydrated, chapterIndex, voiceId) { segment ->
                    player.write(segment.pcm)
                }
            }
            withContext(Dispatchers.IO) { player.finish() }
            withContext(Dispatchers.IO) {
                AudioLibrary(applicationContext).use { library ->
                    library.registerLocalChapter(hydrated, chapterIndex, result.voiceKey, result.voiceName,
                        result.audio, result.durationMs, result.cues, result.sourceRevision, result.pauseRuleRevision)
                }
            }
            return Result.success()
        } catch (cancelled: CancellationException) {
            runtime.cancel()
            throw cancelled
        } catch (error: Exception) {
            return if (runAttemptCount < 2) Result.retry() else Result.failure(
                Data.Builder().putString("error", error.message ?: "本地合成失败").build()
            )
        } finally {
            player.close()
            runtime.release()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "本地语音合成", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_audio)
            .setContentTitle("本地语音合成")
            .setContentText("正在合成并播放当前章节")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL = "local-tts"
        private const val NOTIFICATION_ID = 301
        private const val KEY_BOOK_ID = "book_id"
        private const val KEY_CHAPTER_INDEX = "chapter_index"
        private const val KEY_VOICE_ID = "voice_id"

        fun enqueue(context: Context, bookId: String, chapterIndex: Int, voiceId: String = LocalTtsRuntime.DEFAULT_VOICE_ID): OneTimeWorkRequest {
            val request = OneTimeWorkRequestBuilder<LocalTtsSynthesisWorker>()
                .setInputData(Data.Builder().putString(KEY_BOOK_ID, bookId).putInt(KEY_CHAPTER_INDEX, chapterIndex)
                    .putString(KEY_VOICE_ID, voiceId).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "local-tts:$bookId:$chapterIndex:$voiceId", ExistingWorkPolicy.KEEP, request
            )
            return request
        }
    }
}
