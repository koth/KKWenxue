package com.kkwenxue.shanye.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.ArrayDeque
import java.util.concurrent.CancellationException

internal const val LOCAL_TTS_PREBUFFER_SENTENCES = 8

/** Plays PCM from a bounded queue while the next local TTS sentence is generated. */
class LocalTtsStreamPlayer(
    private val listener: Listener? = null,
    initialSpeed: Float = 1f
) {
    interface Listener {
        fun onPlaybackStarted(segment: LocalTtsStreamSegment, positionMs: Int)
        fun onPlaybackFinished(positionMs: Int)
    }

    private data class QueuedSegment(val segment: LocalTtsStreamSegment, val samples: Long)

    private val lock = Object()
    private val queue = ArrayDeque<QueuedSegment>()
    private var queuedSamples = 0L
    private var playedSamples = 0L
    private var track: AudioTrack? = null
    private var consumer: Thread? = null
    private var producerFinished = false
    private var consumerFinished = false
    private var closed = false
    private var failure: Throwable? = null
    private var generatedSpeechSegments = 0
    private var queuedSpeechSegments = 0
    private var prebufferReady = false
    private var speed = initialSpeed.coerceIn(0.5f, 2f)

    fun write(segment: LocalTtsStreamSegment) {
        require(segment.pcm.isNotEmpty() && segment.pcm.size % 2 == 0) { "本地音频片段为空" }
        val queued = QueuedSegment(segment, segment.pcm.size / 2L)
        synchronized(lock) {
            ensureOpen()
            startConsumerLocked()
            // Before the initial five speech segments are available, do not let
            // pause chunks consume the queue cap and block the producer from
            // submitting the fifth sentence. Once the prebuffer is satisfied,
            // keep a larger speech queue so uneven sentence lengths do not
            // immediately catch up with synthesis.
            while (!closed && failure == null &&
                (queuedSpeechSegments >= MAX_BUFFERED_SPEECH_SEGMENTS ||
                    queuedSamples >= MAX_BUFFERED_SAMPLES) &&
                prebufferReady) lock.wait()
            ensureOpen()
            queue.addLast(queued)
            queuedSamples += queued.samples
            if (isSpeech(segment)) {
                generatedSpeechSegments++
                queuedSpeechSegments++
                if (generatedSpeechSegments >= LOCAL_TTS_PREBUFFER_SENTENCES) {
                    prebufferReady = true
                }
            }
            lock.notifyAll()
        }
    }

    fun write(pcm: ByteArray) {
        write(LocalTtsStreamSegment(null, pcm))
    }

    fun setSpeed(value: Float) {
        synchronized(lock) {
            speed = value.coerceIn(0.5f, 2f)
            track?.let { applyPlaybackParams(it, speed) }
        }
    }

    fun finish() {
        val thread = synchronized(lock) {
            ensureOpen()
            producerFinished = true
            lock.notifyAll()
            consumer
        }
        thread?.join(FINISH_TIMEOUT_MS)
        synchronized(lock) {
            if (!consumerFinished) {
                closeLocked()
                throw IllegalStateException("本地语音播放等待超时")
            }
            failure?.let { throw IllegalStateException("本地语音播放失败", it) }
        }
    }

    fun close() {
        val output: AudioTrack?
        synchronized(lock) {
            if (closed) return
            closeLocked()
            output = track
            lock.notifyAll()
        }
        consumer?.interrupt()
        output?.runCatching { pause() }
        output?.runCatching { flush() }
        output?.runCatching { stop() }
        output?.release()
    }

    private fun startConsumerLocked() {
        if (consumer != null) return
        consumer = Thread(::consume, "local-tts-audio").also { it.start() }
    }

    private fun consume() {
        val previousPriority = runCatching { Process.getThreadPriority(Process.myTid()) }
            .getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        try {
            val initialSpeed = synchronized(lock) { speed }
            val output = createTrack(initialSpeed)
            synchronized(lock) {
                if (closed) {
                    output.release()
                    return
                }
                track = output
                applyPlaybackParams(output, speed)
                lock.notifyAll()
            }
            var started = false
            while (true) {
                var item: QueuedSegment? = null
                var positionMs = 0
                var done = false
                synchronized(lock) {
                    while (!closed && queue.isEmpty() && !producerFinished) lock.wait()
                    if (closed) return
                    if (queue.isEmpty() && producerFinished) {
                        done = true
                    } else {
                        while (!closed && !started && !producerFinished &&
                            (queuedSamples < PREBUFFER_SAMPLES || !prebufferReady)) {
                            lock.wait()
                        }
                        if (closed) return
                        if (queue.isEmpty() && producerFinished) {
                            done = true
                        } else {
                            item = queue.removeFirst()
                            queuedSamples -= item!!.samples
                            if (isSpeech(item!!.segment)) queuedSpeechSegments--
                            positionMs = (playedSamples * 1000L / LocalTtsRuntime.SAMPLE_RATE)
                                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            if (!started) {
                                output.play()
                                started = true
                            }
                            lock.notifyAll()
                        }
                    }
                }
                if (done) break
                val next = item ?: continue
                listener?.onPlaybackStarted(next.segment, positionMs)
                writeBlocking(output, next.segment.pcm)
                synchronized(lock) {
                    playedSamples += next.samples
                    lock.notifyAll()
                }
            }
            waitForDrain(output)
            val finalPosition = (playedSamples * 1000L / LocalTtsRuntime.SAMPLE_RATE)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            listener?.onPlaybackFinished(finalPosition)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            synchronized(lock) { failure = error }
        } finally {
            runCatching { Process.setThreadPriority(previousPriority) }
            synchronized(lock) {
                consumerFinished = true
                lock.notifyAll()
            }
        }
    }

    private fun writeBlocking(output: AudioTrack, pcm: ByteArray) {
        var offset = 0
        while (offset < pcm.size) {
            val written = output.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) throw IllegalStateException("本地音频播放失败（AudioTrack=$written）")
            offset += written
        }
    }

    private fun waitForDrain(output: AudioTrack) {
        val deadline = System.nanoTime() + FINISH_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            val head = output.playbackHeadPosition.toLong() and 0xffffffffL
            synchronized(lock) {
                if (closed) return
                if (head >= playedSamples) return
            }
            Thread.sleep(40)
        }
        throw IllegalStateException("本地语音播放排空超时")
    }

    private fun ensureOpen() {
        if (closed) throw CancellationException("本地语音播放已停止")
        failure?.let { throw IllegalStateException("本地语音播放失败", it) }
    }

    private fun closeLocked() {
        closed = true
        producerFinished = true
        queue.clear()
        queuedSamples = 0
        queuedSpeechSegments = 0
    }

    /** Count title and body cues as playable speech, while excluding silence. */
    private fun isSpeech(segment: LocalTtsStreamSegment): Boolean = segment.cue != null

    private fun createTrack(initialSpeed: Float): AudioTrack {
        val format = AudioFormat.Builder()
            .setSampleRate(LocalTtsRuntime.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(
            LocalTtsRuntime.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(LocalTtsRuntime.SAMPLE_RATE / 2)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(format)
            .setBufferSizeInBytes(minimum)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { applyPlaybackParams(it, initialSpeed) }
    }

    private fun applyPlaybackParams(output: AudioTrack, value: Float) {
        runCatching {
            output.setPlaybackParams(output.playbackParams.setSpeed(value).setPitch(1f))
        }
    }

    companion object {
        // Keep a larger safety cushion when synthesis is briefly throttled
        // after the display turns off, while bounding PCM memory separately.
        private const val MAX_BUFFERED_SPEECH_SEGMENTS = 28
        private const val MAX_BUFFERED_AUDIO_MS = 120_000L
        private const val PREBUFFER_MS = 350
        private const val FINISH_TIMEOUT_MS = 30_000L
        private val PREBUFFER_SAMPLES = PREBUFFER_MS * LocalTtsRuntime.SAMPLE_RATE / 1000L
        private val MAX_BUFFERED_SAMPLES =
            MAX_BUFFERED_AUDIO_MS * LocalTtsRuntime.SAMPLE_RATE / 1000L
    }
}
