package com.kkwenxue.shanye.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Plays verified PCM chunks while the next local TTS sentence is being generated. */
class LocalTtsStreamPlayer {
    private var track: AudioTrack? = null
    private var submittedSamples = 0L

    @Synchronized
    fun write(pcm: ByteArray) {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0) { "本地音频片段为空" }
        val output = track ?: createTrack().also {
            track = it
            it.play()
        }
        var offset = 0
        while (offset < pcm.size) {
            val written = output.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
            require(written > 0) { "本地音频播放失败" }
            offset += written
            submittedSamples += written / 2L
        }
    }

    fun finish() {
        val output = track ?: return
        val deadline = System.nanoTime() + 30_000_000_000L
        while (output.playbackHeadPosition.toLong() < submittedSamples && System.nanoTime() < deadline) {
            Thread.sleep(40)
        }
    }

    @Synchronized
    fun close() {
        track?.runCatching { stop() }
        track?.release()
        track = null
    }

    private fun createTrack(): AudioTrack {
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
    }
}
