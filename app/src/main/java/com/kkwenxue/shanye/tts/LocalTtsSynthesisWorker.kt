package com.kkwenxue.shanye.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kkwenxue.shanye.R
import com.kkwenxue.shanye.audio.AudioPlayback
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
        // AudioTrack does not acquire a CPU wake lock by itself. Keep the
        // native synthesis thread and streaming consumer alive while the
        // display is off; the foreground WorkManager service protects the
        // process, while this lock protects CPU execution during Doze.
        val wakeLock = applicationContext.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${applicationContext.packageName}:local-tts")
        wakeLock?.acquire()
        AudioPlayback.restore(applicationContext)
        val runtime = LocalTtsRuntime(applicationContext)
        var session: LocalTtsPlaybackSession? = null
        var audioLibrary: AudioLibrary? = null
        try {
            val book = withContext(Dispatchers.IO) {
                LocalBookRepository(applicationContext).load().firstOrNull { it.id == bookId }
                    ?: error("书籍尚未从在线书库导入")
            }
            val chapter = book.chapters.getOrNull(chapterIndex) ?: error("章节不在书库中")
            val hydrated = withContext(Dispatchers.IO) {
                OnlineChapterStore(applicationContext).load(book, chapterIndex)
            }
            audioLibrary = AudioLibrary(applicationContext)
            session = LocalTtsLivePlayback.start(
                hydrated.id, hydrated.chapters[chapterIndex].id, chapterIndex, AudioPlayback.speed
            ) { index, offset ->
                audioLibrary?.saveLocalPlaybackPosition(hydrated, index, offset)
            }
            session?.status("正在读取章节正文…", LocalTtsLivePhase.PREPARING)
            session?.status("正在加载本地语音模型…", LocalTtsLivePhase.PREPARING)
            val result = withContext(Dispatchers.IO) {
                runtime.prepare()
                session?.status("正在生成第一句…", LocalTtsLivePhase.PREBUFFERING)
                session?.beginSynthesis()
                runtime.synthesizeChapterStreaming(hydrated, chapterIndex, voiceId) { segment ->
                    session?.offer(segment) ?: throw CancellationException("本地语音朗读已停止")
                }
            }
            withContext(Dispatchers.IO) { session?.finish() }
            withContext(Dispatchers.IO) {
                val library = audioLibrary ?: error("本地听书库未初始化")
                val published = library.registerLocalChapter(hydrated, chapterIndex, result.voiceKey, result.voiceName,
                    result.audio, result.durationMs, result.cues, result.sourceRevision, result.pauseRuleRevision)
                // Replace the temporary live checkpoint with the real artifact
                // once the complete chapter has been published.
                library.savePlaybackPosition(published, result.durationMs)
            }
            return Result.success()
        } catch (cancelled: CancellationException) {
            runtime.cancel()
            session?.stop()
            throw cancelled
        } catch (error: Exception) {
            session?.fail(error)
            return if (runAttemptCount < 2) Result.retry() else Result.failure(
                Data.Builder().putString("error", error.message ?: "本地合成失败").build()
            )
        } finally {
            session?.close()
            audioLibrary?.close()
            runtime.release()
            if (wakeLock?.isHeld == true) wakeLock.release()
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
                uniqueName(bookId, chapterIndex, voiceId), ExistingWorkPolicy.REPLACE, request
            )
            return request
        }

        fun cancel(context: Context, bookId: String, chapterIndex: Int, voiceId: String = LocalTtsRuntime.DEFAULT_VOICE_ID) {
            LocalTtsLivePlayback.stopActive()
            LocalTtsNative.cancel()
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(bookId, chapterIndex, voiceId))
        }

        private fun uniqueName(bookId: String, chapterIndex: Int, voiceId: String) =
            "local-tts:$bookId:$chapterIndex:$voiceId"
    }
}
