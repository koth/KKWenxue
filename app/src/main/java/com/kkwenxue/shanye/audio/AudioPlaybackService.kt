package com.kkwenxue.shanye.audio

import android.app.*
import android.annotation.SuppressLint
import android.content.*
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import androidx.compose.runtime.*
import com.kkwenxue.shanye.MainActivity
import com.kkwenxue.shanye.R
import com.kkwenxue.shanye.data.AudioChapter
import com.kkwenxue.shanye.data.AudioLibrary
import com.kkwenxue.shanye.data.Library

/** Observable playback state is shared by the reader and the foreground media service. */
object AudioPlayback {
    /** Playback speeds exposed by the reader. Synthesis speed is configured separately. */
    val supportedSpeeds = listOf(.8f, .9f, 1f, 1.1f, 1.2f)
    var current by mutableStateOf<AudioChapter?>(null); internal set
    var playing by mutableStateOf(false); internal set
    var preparing by mutableStateOf(false); internal set
    var position by mutableIntStateOf(0); internal set
    var error by mutableStateOf<String?>(null); internal set
    var speed by mutableFloatStateOf(1f); internal set
    var timerMode by mutableIntStateOf(0); internal set // 0 off, -1 until chapter end, otherwise minutes
    var timerRemainingMs by mutableLongStateOf(0L); internal set
    internal var requested: AudioChapter? = null
    private var restored = false
    fun restore(context: Context) {
        if (!restored) {
            val saved = context.getSharedPreferences("shanye_audio", Context.MODE_PRIVATE)
                .getFloat("playback_speed", 1f)
            // Migrate values from the old 0.5×–2× menu to the supported five presets.
            speed = supportedSpeeds.minBy { kotlin.math.abs(it - saved) }
            restored = true
        }
    }
    fun play(context: Context, item: AudioChapter, position: Int) {
        requested = item
        context.startForegroundService(Intent(context, AudioPlaybackService::class.java)
            .setAction("play").putExtra("artifact", item.id).putExtra("position", position))
    }
    fun command(context: Context, action: String, position: Int = 0) {
        if (current == null) return
        val intent = Intent(context, AudioPlaybackService::class.java).setAction(action).putExtra("position", position)
        if (action == "resume") context.startForegroundService(intent) else context.startService(intent)
    }
    fun setSpeed(context: Context, value: Float) {
        restore(context)
        speed = value.coerceIn(0.5f, 2f)
        context.getSharedPreferences("shanye_audio", Context.MODE_PRIVATE).edit()
            .putFloat("playback_speed", speed).apply()
        if (current != null) context.startService(Intent(context, AudioPlaybackService::class.java).setAction("speed").putExtra("speed", speed))
    }
    fun setSleepTimer(context: Context, minutes: Int) {
        timerMode = minutes
        timerRemainingMs = if (minutes > 0) minutes * 60_000L else 0L
        context.startService(Intent(context, AudioPlaybackService::class.java).setAction("timer").putExtra("minutes", minutes))
    }
}

class AudioPlaybackService : Service() {
    private var player: MediaPlayer? = null
    private lateinit var session: MediaSession
    private lateinit var library: AudioLibrary
    private lateinit var audio: AudioManager
    private lateinit var focus: AudioFocusRequest
    private val handler = Handler(Looper.getMainLooper())
    private var resumeAfterFocus = false
    private var focusDucked = false
    private var playWhenReady = true
    private var savedAt = 0L
    private var sleepAtElapsed = 0L
    private var sleepAtChapterEnd = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { pause() }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (player != null && !AudioPlayback.preparing) {
                AudioPlayback.position = (player?.currentPosition ?: 0).coerceAtMost(AudioPlayback.current?.durationMs ?: Int.MAX_VALUE)
                if (SystemClock.elapsedRealtime() - savedAt > 5000) save()
            }
            if (sleepAtElapsed > 0L) {
                val remaining = (sleepAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                AudioPlayback.timerRemainingMs = remaining
                if (remaining == 0L) {
                    sleepAtElapsed = 0L
                    AudioPlayback.timerMode = 0
                    pause()
                }
            }
            handler.postDelayed(this, 350)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AudioPlayback.restore(this)
        library = AudioLibrary(this)
        audio = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes()).setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (focusDucked) {
                            focusDucked = false
                            setPlayerVolume(1f)
                            publish()
                        }
                        resumeAfterFocus = false
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        // Keep a narrated book running when another app starts audio. Lowering
                        // our volume lets the other source be heard without losing the reading
                        // position or requiring a second manual play action.
                        if (AudioPlayback.playing) {
                            focusDucked = true
                            setPlayerVolume(.18f)
                            publish()
                        }
                    }
                }
            }.build()
        session = MediaSession(this, "山页听书").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onStop() { stopSelf() }
                override fun onSeekTo(pos: Long) = seek(pos.toInt())
                override fun onFastForward() = seek(AudioPlayback.position + 15000)
                override fun onRewind() = seek(AudioPlayback.position - 15000)
            })
            isActive = true
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "听书播放", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        else registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "play" -> {
                val item = AudioPlayback.requested?.takeIf { it.id == intent.getStringExtra("artifact") }
                if (item == null) stopSelf() else play(item, intent.getIntExtra("position", 0))
            }
            "resume" -> { startForeground(NOTIFICATION, notification()); resume() }
            "pause" -> pause()
            "seek" -> seek(intent.getIntExtra("position", 0))
            "speed" -> applySpeed(intent.getFloatExtra("speed", AudioPlayback.speed))
            "timer" -> configureTimer(intent.getIntExtra("minutes", 0))
            "stop" -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun attributes() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private fun play(item: AudioChapter, offset: Int) {
        save()
        player?.release()
        AudioPlayback.current = item
        AudioPlayback.position = offset.coerceIn(0, item.durationMs)
        AudioPlayback.playing = false
        AudioPlayback.preparing = true
        AudioPlayback.error = null
        playWhenReady = true
        startForeground(NOTIFICATION, notification())
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, item.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, item.voice)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, item.durationMs.toLong()).build())
        try {
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(attributes())
            media.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            media.setDataSource(item.file.path)
            media.setOnPreparedListener {
                AudioPlayback.preparing = false
                setPlayerVolume(if (focusDucked) .18f else 1f)
                applySpeed(AudioPlayback.speed)
                seek(AudioPlayback.position)
                if (playWhenReady) resume() else publish()
            }
            media.setOnCompletionListener {
                if (player === media) completeChapter(item)
            }
            media.setOnErrorListener { _, _, _ -> fail(); true }
            media.prepareAsync()
            publish()
        } catch (_: Exception) { fail() }
    }

    private fun completeChapter(item: AudioChapter) {
        AudioPlayback.position = item.durationMs
        AudioPlayback.playing = false
        save()
        // A wall-clock timer can expire between ticker updates at the chapter boundary.
        val timerExpired = sleepAtElapsed > 0L && SystemClock.elapsedRealtime() >= sleepAtElapsed
        if (sleepAtChapterEnd || timerExpired) {
            sleepAtChapterEnd = false
            sleepAtElapsed = 0L
            AudioPlayback.timerMode = 0
            AudioPlayback.timerRemainingMs = 0L
        } else if (playWhenReady) {
            val book = Library.books.firstOrNull { it.id == item.bookId }
            val next = runCatching { book?.let { library.nextChapter(item, it) } }
            val chapter = next.getOrNull()
            if (chapter != null) {
                library.select(chapter)
                // Start a fresh chapter from its beginning, preserving speed and timer deadline.
                play(chapter, 0)
                return
            }
            val index = book?.chapters?.indexOfFirst { it.id == item.chapterId } ?: -1
            AudioPlayback.error = when {
                next.isFailure -> "下一章音频读取失败，请重新导入听书包"
                book != null && index >= 0 && index < book.chapters.lastIndex -> "下一章暂无听书内容，请先合成或导入"
                else -> null
            }
        }
        playWhenReady = false
        audio.abandonAudioFocusRequest(focus)
        publish()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun resume() {
        playWhenReady = true
        if (AudioPlayback.preparing || player == null) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            AudioPlayback.error = "暂时无法取得音频播放权限，请稍后重试"
            publish()
            stopForeground(STOP_FOREGROUND_DETACH)
            return
        }
        if (AudioPlayback.position >= (AudioPlayback.current?.durationMs ?: 0)) seek(0)
        player?.start()
        AudioPlayback.playing = true
        AudioPlayback.error = null
        resumeAfterFocus = false
        startForeground(NOTIFICATION, notification())
        publish()
    }

    private fun pause(abandon: Boolean = true) {
        playWhenReady = false
        resumeAfterFocus = false
        if (!AudioPlayback.preparing && player != null) {
            player?.pause()
            AudioPlayback.position = player?.currentPosition ?: AudioPlayback.position
        }
        AudioPlayback.playing = false
        if (abandon) audio.abandonAudioFocusRequest(focus)
        save()
        publish()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun seek(position: Int) {
        val item = AudioPlayback.current ?: return
        if (AudioPlayback.preparing) return
        val target = position.coerceIn(0, item.durationMs)
        player?.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
        AudioPlayback.position = target
        save()
        publish()
    }

    private fun applySpeed(value: Float) {
        val normalized = value.coerceIn(0.5f, 2f)
        AudioPlayback.speed = normalized
        if (!AudioPlayback.preparing) runCatching {
            player?.let { media ->
                // MediaPlayer starts when a nonzero speed is assigned, even if it was paused.
                val wasPlaying = AudioPlayback.playing
                media.playbackParams = media.playbackParams.setSpeed(normalized).setPitch(1f)
                if (!wasPlaying) media.pause()
            }
        }
        publish()
    }

    private fun setPlayerVolume(value: Float) {
        runCatching { player?.setVolume(value.coerceIn(0f, 1f), value.coerceIn(0f, 1f)) }
    }

    private fun configureTimer(minutes: Int) {
        when {
            minutes == -1 -> {
                sleepAtElapsed = 0L
                sleepAtChapterEnd = true
                AudioPlayback.timerMode = -1
                AudioPlayback.timerRemainingMs = 0L
            }
            minutes > 0 -> {
                sleepAtChapterEnd = false
                sleepAtElapsed = SystemClock.elapsedRealtime() + minutes * 60_000L
                AudioPlayback.timerMode = minutes
                AudioPlayback.timerRemainingMs = minutes * 60_000L
            }
            else -> {
                sleepAtElapsed = 0L
                sleepAtChapterEnd = false
                AudioPlayback.timerMode = 0
                AudioPlayback.timerRemainingMs = 0L
            }
        }
        publish()
    }

    private fun save() {
        AudioPlayback.current?.let { library.savePosition(it, AudioPlayback.position) }
        savedAt = SystemClock.elapsedRealtime()
    }

    private fun fail() {
        AudioPlayback.error = "音频播放失败，请重新导入听书包"
        player?.release()
        player = null
        AudioPlayback.playing = false
        AudioPlayback.preparing = false
        stopSelf()
    }

    // MediaSession notifications are exempt from POST_NOTIFICATIONS on Android 13+.
    // https://developer.android.com/develop/ui/views/notifications/notification-permission#media-sessions
    @SuppressLint("NotificationPermission")
    private fun publish() {
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD)
            .setState(if (AudioPlayback.preparing) PlaybackState.STATE_BUFFERING else if (AudioPlayback.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                AudioPlayback.position.toLong(), if (AudioPlayback.playing) AudioPlayback.speed else 0f).build())
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_audio", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(name: String) = PendingIntent.getService(this, name.hashCode(), Intent(this, AudioPlaybackService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_audio).setContentTitle(AudioPlayback.current?.title ?: "山页听书")
            .setContentText(AudioPlayback.current?.voice ?: "正在准备音频").setContentIntent(open).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(AudioPlayback.playing || AudioPlayback.preparing)
            .addAction(Notification.Action.Builder(if (AudioPlayback.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (AudioPlayback.playing) "暂停" else "播放", action(if (AudioPlayback.playing) "pause" else "resume")).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止", action("stop")).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1)).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        save()
        handler.removeCallbacks(ticker)
        unregisterReceiver(noisy)
        player?.release()
        audio.abandonAudioFocusRequest(focus)
        session.release()
        library.close()
        AudioPlayback.playing = false
        AudioPlayback.preparing = false
        AudioPlayback.current = null
        AudioPlayback.timerMode = 0
        AudioPlayback.timerRemainingMs = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        // A paused notification was detached from the foreground service, so it
        // must also be cancelled explicitly when the listening session ends.
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "audiobook"
        private const val NOTIFICATION = 201
    }
}
