package com.kkwenxue.shanye.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.kkwenxue.shanye.MainActivity
import com.kkwenxue.shanye.R
import com.kkwenxue.shanye.audio.AudioPlayback
import com.kkwenxue.shanye.data.AudioLibrary
import com.kkwenxue.shanye.data.LocalBookRepository
import com.kkwenxue.shanye.data.OnlineChapterStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Own foreground service for local TTS.
 *
 * WorkManager's foreground service is suitable for short background jobs, but
 * long-running native inference plus AudioTrack playback must live in an
 * application-owned foreground service so the playback process is not torn
 * down when the activity is stopped or the display turns off.
 */
class LocalTtsSynthesisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: kotlinx.coroutines.Job? = null
    private var runtime: LocalTtsRuntime? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioLibrary: AudioLibrary? = null
    private var currentChapterTitle: String? = null
    private var sleepTimerJob: Job? = null
    private var sleepAtElapsed = 0L
    private var sleepAtChapterEnd = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // An explicit stop is a user decision. Keep generated
                // segments for a later manual retry, but do not auto-resume
                // this request on the next app launch.
                LocalTtsJobStore.clear(applicationContext)
                stopSynthesis()
                stopSelfResult(startId)
            }
            ACTION_TIMER -> configureSleepTimer(intent.getIntExtra(KEY_TIMER_MINUTES, 0))
            ACTION_START -> {
                val bookId = intent.getStringExtra(KEY_BOOK_ID)
                val chapterIndex = intent.getIntExtra(KEY_CHAPTER_INDEX, -1)
                val voiceId = intent.getStringExtra(KEY_VOICE_ID) ?: LocalTtsRuntime.DEFAULT_VOICE_ID
                val startOffset = intent.getIntExtra(KEY_START_OFFSET, 0)
                if (bookId.isNullOrBlank() || chapterIndex < 0) {
                    stopSelfResult(startId)
                } else {
                    val jobId = intent.getStringExtra(KEY_JOB_ID) ?: UUID.randomUUID().toString()
                    val jobKey = jobId
                    if (activeJobKey == jobKey && task?.isActive == true) return START_REDELIVER_INTENT
                    // START_REDELIVER_INTENT contains the original request,
                    // while the durable record may already have advanced to a
                    // later chapter. Keep the latest checkpoint instead of
                    // overwriting it with stale intent extras.
                    val stored = LocalTtsJobStore.load(applicationContext)
                        ?.takeIf { it.id == jobId && it.bookId == bookId && it.voiceId == voiceId }
                    val effectiveJob = stored ?: LocalTtsPendingJob(
                        jobId, bookId, chapterIndex, voiceId, startOffset.coerceAtLeast(0)
                    )
                    LocalTtsJobStore.save(
                        applicationContext,
                        effectiveJob
                    )
                    startForegroundNow()
                    syncSleepTimerFromPlayback()
                    task?.cancel()
                    activeJobKey = jobKey
                    task = scope.launch {
                        synthesize(
                            effectiveJob.bookId,
                            effectiveJob.chapterIndex,
                            effectiveJob.voiceId,
                            effectiveJob.startOffset,
                            startId,
                            jobId
                        )
                    }
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startForegroundNow() {
        createChannel()
        val notification = notification("正在准备本地语音…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:local-tts")
                ?.also { it.acquire() }
        }
    }

    private suspend fun synthesize(
        bookId: String,
        chapterIndex: Int,
        voiceId: String,
        requestedStartOffset: Int,
        startId: Int,
        jobId: String
    ) {
        AudioPlayback.restore(applicationContext)
        val localRuntime = LocalTtsRuntime(applicationContext)
        runtime = localRuntime
        var session: LocalTtsPlaybackSession? = null
        try {
            val book = withContext(Dispatchers.IO) {
                LocalBookRepository(applicationContext).load().firstOrNull { it.id == bookId }
                    ?: error("书籍尚未从在线书库导入")
            }
            require(chapterIndex in book.chapters.indices) { "章节不在书库中" }
            audioLibrary = AudioLibrary(applicationContext)
            val library = audioLibrary ?: error("本地听书库未初始化")
            val chapterStore = OnlineChapterStore(applicationContext)
            val requestedChapter = book.chapters.getOrNull(chapterIndex) ?: error("章节不在书库中")

            // Publish a live session before loading the native model. Model
            // mmap or chapter recovery can take a while, and the player must
            // show that work instead of falling back to an empty chapter page.
            session = LocalTtsLivePlayback.start(
                book.id, requestedChapter.id, chapterIndex, AudioPlayback.speed
            ) { currentIndex, offset ->
                library.saveLocalPlaybackPosition(book, currentIndex, offset)
            }
            session?.status("正在加载本地语音模型…", LocalTtsLivePhase.PREPARING)

            withContext(Dispatchers.IO) { localRuntime.prepare() }
            val persistedOffset = library.localTtsResumeOffset(book.id, requestedChapter.id)
            // The reader entry point can only provide the beginning of the
            // visible page. A live checkpoint may be farther into that page,
            // so never move a recovered synthesis job backwards.
            val firstOffset = maxOf(requestedStartOffset, persistedOffset ?: 0)
            // If the last safe checkpoint is exactly at the end of a chapter,
            // continue with the next chapter instead of creating an empty
            // AudioTrack stream.
            val chapterIsFinished = firstOffset >= requestedChapter.text.length &&
                requestedChapter.text.isNotBlank()
            val firstChapterIndex = if (chapterIsFinished && chapterIndex < book.chapters.lastIndex) {
                chapterIndex + 1
            } else chapterIndex
            // There is no next chapter to play. Rebuild/finalize this chapter
            // from its cached segments so an interruption after the final
            // sentence cannot leave a permanent live-only checkpoint.
            val effectiveFirstOffset = if (chapterIsFinished && firstChapterIndex == chapterIndex) 0 else firstOffset
            for (index in firstChapterIndex until book.chapters.size) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val hydrated = withContext(Dispatchers.IO) {
                    chapterStore.load(book, index)
                }
                val chapter = hydrated.chapters.getOrNull(index) ?: error("章节不在书库中")
                require(chapter.body.isNotBlank()) { "章节正文未加载：${chapter.title}" }
                currentChapterTitle = chapter.title
                updateNotification("正在合成并播放：${chapter.title}")
                val resumeOffset = if (index == chapterIndex) effectiveFirstOffset else 0
                // Advance the durable request before starting this chapter.
                // If the process dies later, recovery starts here and uses the
                // chapter's own live checkpoint instead of replaying earlier
                // chapters that have already been published.
                LocalTtsJobStore.save(
                    applicationContext,
                    LocalTtsPendingJob(jobId, bookId, index, voiceId, resumeOffset)
                )

                val chapterSession = session?.also {
                    it.beginChapter(hydrated.id, chapter.id, index) { currentIndex, offset ->
                        library.saveLocalPlaybackPosition(hydrated, currentIndex, offset)
                    }
                } ?: LocalTtsLivePlayback.start(
                    hydrated.id, chapter.id, index, AudioPlayback.speed
                ) { currentIndex, offset ->
                    library.saveLocalPlaybackPosition(hydrated, currentIndex, offset)
                }.also { session = it }
                library.saveLocalPlaybackPosition(hydrated, index, resumeOffset)
                val firstSegmentStatus = if (resumeOffset > 0) "正在合成当前句…" else "正在合成章节标题…"
                chapterSession.status(firstSegmentStatus, LocalTtsLivePhase.PREBUFFERING)
                val result = withContext(Dispatchers.IO) {
                    chapterSession.beginSynthesis(firstSegmentStatus)
                    localRuntime.synthesizeChapterStreaming(
                        hydrated, index, voiceId, startOffset = resumeOffset
                    ) { segment -> chapterSession.offer(segment) }
                }
                withContext(Dispatchers.IO) {
                    // "本章结束" must drain this chapter's queued PCM but
                    // must not keep the producer open for the next chapter.
                    chapterSession.finish(
                        keepActive = index < book.chapters.lastIndex && !sleepAtChapterEnd
                    )
                }
                val published = library.registerLocalChapter(
                    hydrated, index, result.voiceKey, result.voiceName,
                    result.audio, result.durationMs, result.cues,
                    result.sourceRevision, result.pauseRuleRevision
                )
                library.savePlaybackPosition(published, result.durationMs)
                if (index == book.chapters.lastIndex) {
                    chapterSession.close()
                    session = null
                }
                if (sleepAtChapterEnd) {
                    sleepAtChapterEnd = false
                    sleepTimerJob?.cancel()
                    sleepTimerJob = null
                    AudioPlayback.timerMode = 0
                    AudioPlayback.timerRemainingMs = 0L
                    LocalTtsJobStore.clearIfIdMatches(applicationContext, jobId)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                    return
                }
            }

            currentChapterTitle = null
            updateNotification("本地语音朗读已完成")
            LocalTtsJobStore.clearIfIdMatches(applicationContext, jobId)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        } catch (cancelled: CancellationException) {
            localRuntime.cancel()
            session?.stop()
            throw cancelled
        } catch (error: Exception) {
            LocalTtsJobStore.saveError(applicationContext, error.message ?: "本地语音合成失败")
            session?.fail(error)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        } finally {
            session?.close()
            currentChapterTitle = null
            // The native backbone and decoder are roughly 1.1 GB together.
            // Do not leave them resident after a foreground synthesis job
            // finishes or fails; otherwise the next screen can trigger a
            // system memory warning even though playback has ended.
            localRuntime.release()
            runtime = null
            audioLibrary?.close()
            audioLibrary = null
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
            if (activeJobKey == jobId) activeJobKey = null
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(text)
        )
    }

    private fun stopSynthesis() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepAtElapsed = 0L
        sleepAtChapterEnd = false
        runtime?.cancel()
        task?.cancel()
        LocalTtsNative.cancel()
        LocalTtsLivePlayback.stopActive()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun configureSleepTimer(minutes: Int) {
        when {
            minutes == -1 -> configureSleepTimerAtChapterEnd()
            minutes > 0 -> configureSleepTimerForMillis(minutes * 60_000L)
            else -> clearSleepTimer()
        }
    }

    private fun syncSleepTimerFromPlayback() {
        when {
            AudioPlayback.timerMode == -1 -> configureSleepTimerAtChapterEnd()
            AudioPlayback.timerMode > 0 && AudioPlayback.timerRemainingMs > 0L ->
                configureSleepTimerForMillis(AudioPlayback.timerRemainingMs)
        }
    }

    private fun configureSleepTimerAtChapterEnd() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepAtElapsed = 0L
        sleepAtChapterEnd = false
        sleepAtChapterEnd = true
        AudioPlayback.timerMode = -1
        AudioPlayback.timerRemainingMs = 0L
    }

    private fun configureSleepTimerForMillis(durationMs: Long) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepAtElapsed = android.os.SystemClock.elapsedRealtime() + durationMs.coerceAtLeast(1L)
        sleepAtChapterEnd = false
        if (AudioPlayback.timerMode <= 0) {
            AudioPlayback.timerMode = ((durationMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        }
        AudioPlayback.timerRemainingMs = durationMs
        sleepTimerJob = scope.launch {
            while (true) {
                delay(250)
                val remaining = (sleepAtElapsed - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                AudioPlayback.timerRemainingMs = remaining
                if (remaining == 0L) {
                    LocalTtsJobStore.clear(applicationContext)
                    runtime?.cancel()
                    task?.cancel()
                    LocalTtsNative.cancel()
                    LocalTtsLivePlayback.stopActive()
                    AudioPlayback.timerMode = 0
                    AudioPlayback.timerRemainingMs = 0L
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }
            }
        }
    }

    private fun clearSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepAtElapsed = 0L
        sleepAtChapterEnd = false
        AudioPlayback.timerMode = 0
        AudioPlayback.timerRemainingMs = 0L
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "本地语音合成", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String = "正在合成并播放当前章节"): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_audio", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LocalTtsSynthesisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_audio)
            .setContentTitle(currentChapterTitle ?: "本地语音合成")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop).build())
            .build()
    }

    override fun onDestroy() {
        stopSynthesis()
        activeJobKey = null
        audioLibrary?.close()
        audioLibrary = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile private var activeJobKey: String? = null
        private const val CHANNEL = "local-tts"
        private const val NOTIFICATION_ID = 301
        private const val ACTION_START = "com.kkwenxue.shanye.tts.START"
        private const val ACTION_STOP = "com.kkwenxue.shanye.tts.STOP"
        private const val ACTION_TIMER = "com.kkwenxue.shanye.tts.TIMER"
        private const val KEY_BOOK_ID = "book_id"
        private const val KEY_CHAPTER_INDEX = "chapter_index"
        private const val KEY_VOICE_ID = "voice_id"
        private const val KEY_START_OFFSET = "start_offset"
        private const val KEY_JOB_ID = "job_id"
        private const val KEY_TIMER_MINUTES = "timer_minutes"

        fun start(
            context: Context,
            bookId: String,
            chapterIndex: Int,
            voiceId: String = LocalTtsRuntime.DEFAULT_VOICE_ID,
            startOffset: Int = 0,
            jobId: String = UUID.randomUUID().toString()
        ) {
            val intent = Intent(context, LocalTtsSynthesisService::class.java)
                .setAction(ACTION_START)
                .putExtra(KEY_BOOK_ID, bookId)
                .putExtra(KEY_CHAPTER_INDEX, chapterIndex)
                .putExtra(KEY_VOICE_ID, voiceId)
                .putExtra(KEY_START_OFFSET, startOffset.coerceAtLeast(0))
                .putExtra(KEY_JOB_ID, jobId)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        /** Re-deliver the last unfinished request after the app process returns. */
        fun resumePending(context: Context, bookId: String? = null, chapterIndex: Int? = null) {
            LocalTtsJobStore.load(context)
                ?.takeIf { bookId == null || (it.bookId == bookId && (chapterIndex == null || it.chapterIndex == chapterIndex)) }
                ?.let { job ->
                start(context, job.bookId, job.chapterIndex, job.voiceId, job.startOffset, job.id)
            }
        }

        fun hasPending(context: Context, bookId: String, chapterIndex: Int): Boolean =
            LocalTtsJobStore.load(context)?.let { it.bookId == bookId && it.chapterIndex == chapterIndex } == true

        fun stop(context: Context) {
            LocalTtsJobStore.clear(context)
            val intent = Intent(context, LocalTtsSynthesisService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
            // Also request destruction of a service that is blocked inside a
            // native call. onDestroy() repeats the cancellation and releases
            // AudioTrack/wake-lock resources even if the action delivery is
            // delayed by the system.
            context.stopService(intent)
        }

        fun setSleepTimer(context: Context, minutes: Int) {
            context.startService(
                Intent(context, LocalTtsSynthesisService::class.java)
                    .setAction(ACTION_TIMER)
                    .putExtra(KEY_TIMER_MINUTES, minutes)
            )
        }
    }
}
