package com.kkwenxue.shanye.tts

import com.kkwenxue.shanye.data.AudioCue
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LocalTtsLivePhase {
    IDLE,
    PREPARING,
    PREBUFFERING,
    SYNTHESIZING,
    PLAYING,
    COMPLETED,
    STOPPED,
    FAILED
}

data class LocalTtsLiveState(
    val phase: LocalTtsLivePhase = LocalTtsLivePhase.IDLE,
    val status: String = "",
    val bookId: String? = null,
    val chapterId: String? = null,
    val chapterIndex: Int = -1,
    val totalSegments: Int = 0,
    val generatedSegments: Int = 0,
    val playedSegments: Int = 0,
    val bufferedSpeechSegments: Int = 0,
    val currentCue: AudioCue? = null,
    val currentText: String? = null,
    val generatedPcmMs: Int = 0,
    val playedPositionMs: Int = 0,
    val error: String? = null
)

/**
 * Process-local live state for the foreground local-TTS worker and the Compose UI.
 *
 * The generated chapter is not published to AudioLibrary until the whole job has
 * completed, so the UI needs a separate live source for sentence text and progress.
 */
object LocalTtsLivePlayback {
    private val lock = Any()
    private val live = MutableStateFlow(LocalTtsLiveState())
    private var active: LocalTtsPlaybackSession? = null

    val state = live.asStateFlow()

    fun start(
        bookId: String,
        chapterId: String,
        chapterIndex: Int,
        speed: Float = 1f,
        onProgress: ((chapterIndex: Int, textOffset: Int) -> Unit)? = null
    ): LocalTtsPlaybackSession {
        val previous = synchronized(lock) {
            val old = active
            active = null
            old
        }
        previous?.stop()
        val session = LocalTtsPlaybackSession(this, bookId, chapterId, chapterIndex, speed, onProgress)
        synchronized(lock) {
            active = session
            live.value = LocalTtsLiveState(
                phase = LocalTtsLivePhase.PREPARING,
                status = "正在准备本地朗读…",
                bookId = bookId,
                chapterId = chapterId,
                chapterIndex = chapterIndex
            )
        }
        return session
    }

    fun setSpeed(value: Float) {
        synchronized(lock) { active }?.setSpeed(value)
    }

    fun stopActive() {
        val session = synchronized(lock) { active }
        session?.stop()
    }

    internal fun publish(session: LocalTtsPlaybackSession, value: LocalTtsLiveState) {
        synchronized(lock) {
            if (active === session) live.value = value
        }
    }

    internal fun complete(session: LocalTtsPlaybackSession, value: LocalTtsLiveState) {
        synchronized(lock) {
            if (active === session) {
                live.value = value
                active = null
            }
        }
    }
}

class LocalTtsPlaybackSession internal constructor(
    private val owner: LocalTtsLivePlayback,
    initialBookId: String,
    initialChapterId: String,
    initialChapterIndex: Int,
    initialSpeed: Float,
    initialOnProgress: ((chapterIndex: Int, textOffset: Int) -> Unit)?
) {
    private val stopped = AtomicBoolean(false)
    private var bookId = initialBookId
    private var chapterId = initialChapterId
    private var chapterIndex = initialChapterIndex
    private val progressCallbacks = mutableMapOf(initialChapterIndex to initialOnProgress)
    private var playbackChapterIndex: Int? = null
    private var chapterStreamOriginMs = 0
    private var continuousPlayback = false
    private var liveState = LocalTtsLiveState(
        phase = LocalTtsLivePhase.PREPARING,
        bookId = bookId,
        chapterId = chapterId,
        chapterIndex = chapterIndex
    )
    private val player = LocalTtsStreamPlayer(object : LocalTtsStreamPlayer.Listener {
        override fun onPlaybackStarted(segment: LocalTtsStreamSegment, positionMs: Int) {
            synchronized(this@LocalTtsPlaybackSession) {
                if (stopped.get()) return
                // A chapter can be synthesized before the previous chapter's
                // queued PCM has finished playing. Ignore old-chapter callbacks
                // for the visible live state, but still report their durable
                // reading progress with the segment's own chapter index.
                val segmentChapterIndex = segment.chapterIndex.takeIf { it >= 0 } ?: chapterIndex
                if (segment.cue?.kind == "body") {
                    progressCallbacks[segmentChapterIndex]?.invoke(segmentChapterIndex, segment.cue.start)
                }
                if (segmentChapterIndex != chapterIndex) return
                if (playbackChapterIndex != segmentChapterIndex) {
                    playbackChapterIndex = segmentChapterIndex
                    chapterStreamOriginMs = positionMs
                }
                val cue = segment.cue
                liveState = liveState.copy(
                    phase = LocalTtsLivePhase.PLAYING,
                    status = "正在朗读",
                    playedPositionMs = (positionMs - chapterStreamOriginMs).coerceAtLeast(0),
                    playedSegments = if (cue == null) liveState.playedSegments
                    else maxOf(liveState.playedSegments, segment.segmentIndex + 1),
                    currentCue = cue ?: liveState.currentCue,
                    currentText = cue?.text ?: liveState.currentText
                )
                owner.publish(this@LocalTtsPlaybackSession, liveState)
            }
        }

        override fun onPlaybackFinished(positionMs: Int) {
            synchronized(this@LocalTtsPlaybackSession) {
                if (stopped.get()) return
                liveState = liveState.copy(
                    playedPositionMs = (positionMs - chapterStreamOriginMs).coerceAtLeast(0)
                )
                owner.publish(this@LocalTtsPlaybackSession, liveState)
                liveState.currentCue?.takeIf { it.kind == "body" }?.let {
                    progressCallbacks[chapterIndex]?.invoke(chapterIndex, it.end)
                }
            }
        }
    }, initialSpeed)

    fun beginSynthesis(message: String = "正在生成第一句…") {
        synchronized(this) {
            check(!stopped.get()) { "本地语音朗读已停止" }
            liveState = liveState.copy(phase = LocalTtsLivePhase.PREBUFFERING, status = message)
            owner.publish(this, liveState)
        }
    }

    fun status(message: String, phase: LocalTtsLivePhase = liveState.phase) {
        synchronized(this) {
            if (stopped.get()) return
            liveState = liveState.copy(phase = phase, status = message)
            owner.publish(this, liveState)
        }
    }

    fun setSpeed(value: Float) {
        player.setSpeed(value)
    }

    /**
     * Move the live session to the next chapter without replacing its
     * AudioTrack. The previous chapter's queued PCM is therefore allowed to
     * drain naturally while the next chapter is synthesized into the same
     * queue, instead of restarting the five-sentence prebuffer.
     */
    fun beginChapter(
        newBookId: String,
        newChapterId: String,
        newChapterIndex: Int,
        newOnProgress: ((chapterIndex: Int, textOffset: Int) -> Unit)?
    ) {
        synchronized(this) {
            check(!stopped.get()) { "本地语音朗读已停止" }
            bookId = newBookId
            chapterId = newChapterId
            chapterIndex = newChapterIndex
            progressCallbacks[newChapterIndex] = newOnProgress
            playbackChapterIndex = null
            chapterStreamOriginMs = 0
            continuousPlayback = true
            liveState = LocalTtsLiveState(
                phase = LocalTtsLivePhase.SYNTHESIZING,
                status = "正在准备下一章…",
                bookId = newBookId,
                chapterId = newChapterId,
                chapterIndex = newChapterIndex
            )
            owner.publish(this, liveState)
        }
    }

    fun offer(segment: LocalTtsStreamSegment) {
        if (stopped.get()) throw CancellationException("本地语音朗读已停止")
        synchronized(this) {
            if (stopped.get()) throw CancellationException("本地语音朗读已停止")
            val generatedMs = segment.pcm.size / 2 * 1000 / LocalTtsRuntime.SAMPLE_RATE
            // The chapter title is the first playable segment and participates
            // in the initial 4–5 segment buffer. Silence chunks do not count.
            val isSpeech = segment.cue != null
            val bufferedSpeechSegments = liveState.bufferedSpeechSegments + if (isSpeech) 1 else 0
            liveState = liveState.copy(
                phase = if (!continuousPlayback && liveState.generatedSegments == 0) LocalTtsLivePhase.PREBUFFERING
                else LocalTtsLivePhase.SYNTHESIZING,
                status = if (!continuousPlayback && bufferedSpeechSegments < LOCAL_TTS_PREBUFFER_SENTENCES) {
                    "正在缓冲前${LOCAL_TTS_PREBUFFER_SENTENCES}句（$bufferedSpeechSegments/${LOCAL_TTS_PREBUFFER_SENTENCES}）…"
                } else "正在合成下一句…",
                totalSegments = maxOf(liveState.totalSegments, segment.totalSegments),
                generatedSegments = if (segment.segmentIndex >= 0)
                    maxOf(liveState.generatedSegments, segment.segmentIndex + 1)
                else liveState.generatedSegments,
                bufferedSpeechSegments = bufferedSpeechSegments,
                generatedPcmMs = liveState.generatedPcmMs + generatedMs
            )
            owner.publish(this, liveState)
        }
        player.write(segment)
    }

    fun finish(keepActive: Boolean = false) {
        if (stopped.get()) throw CancellationException("本地语音朗读已停止")
        // A non-final chapter must leave the producer open. Its queued PCM is
        // still being played while the next chapter is synthesized.
        if (keepActive) {
            synchronized(this) {
                if (stopped.get()) throw CancellationException("本地语音朗读已停止")
                liveState = liveState.copy(
                    phase = LocalTtsLivePhase.SYNTHESIZING,
                    status = "正在准备下一章…"
                )
                owner.publish(this, liveState)
            }
            return
        }
        player.finish()
        synchronized(this) {
            if (stopped.get()) throw CancellationException("本地语音朗读已停止")
            liveState = liveState.copy(
                phase = LocalTtsLivePhase.COMPLETED,
                status = "本章合成完成",
                totalSegments = liveState.totalSegments,
                generatedSegments = liveState.generatedSegments,
                playedSegments = liveState.playedSegments,
                bufferedSpeechSegments = liveState.bufferedSpeechSegments,
                currentCue = liveState.currentCue,
                currentText = liveState.currentText,
                generatedPcmMs = liveState.generatedPcmMs,
                playedPositionMs = liveState.generatedPcmMs
            )
            owner.complete(this, liveState)
        }
    }

    fun fail(error: Throwable) {
        synchronized(this) {
            if (stopped.get()) return
            liveState = liveState.copy(
                phase = LocalTtsLivePhase.FAILED,
                status = error.message ?: "本地语音合成失败",
                error = error.message ?: "本地语音合成失败"
            )
            owner.complete(this, liveState)
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        player.close()
        synchronized(this) {
            liveState = liveState.copy(
                phase = LocalTtsLivePhase.STOPPED,
                status = "本地语音朗读已停止",
                error = null
            )
            owner.complete(this, liveState)
        }
    }

    fun close() {
        player.close()
    }
}
