package com.kkwenxue.shanye.tts

import android.content.Context
import android.app.ActivityManager
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.kkwenxue.shanye.data.Book
import com.kkwenxue.shanye.data.AudioCue
import com.kkwenxue.shanye.data.RelayConnection

private fun localTtsSha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
private const val LOCAL_TTS_PAUSE_RULE_REVISION = "punctuation-pauses-v3-${TtsTextNormalizer.REVISION}"
private const val LOCAL_TTS_SYNTHESIS_POLICY_REVISION = "short-text-stabilization-v1"

data class LocalTtsSegmentKey(
    val bookId: String,
    val chapterId: String,
    val sourceRevision: String,
    val segmentIndex: Int,
    val text: String,
    val voiceId: String,
    val modelRevision: String,
    val engineRevision: String
) {
    fun id(): String = localTtsSha256(listOf(bookId, chapterId, sourceRevision, segmentIndex, text, voiceId, modelRevision, engineRevision).joinToString("\u0000"))
}

data class LocalTtsSegment(
    val id: String,
    val path: File,
    val sampleRate: Int,
    val samples: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
    val text: String
)

data class LocalTtsChapterResult(
    val audio: File,
    val durationMs: Int,
    val cues: List<AudioCue>,
    val voiceKey: String,
    val voiceName: String,
    val sourceRevision: String,
    val pauseRuleRevision: String = LOCAL_TTS_PAUSE_RULE_REVISION
)

data class LocalTtsStreamSegment(
    val cue: AudioCue?,
    val pcm: ByteArray,
    val isPause: Boolean = false,
    val segmentIndex: Int = -1,
    val totalSegments: Int = 0
)

sealed interface LocalTtsRuntimeState {
    data object Idle : LocalTtsRuntimeState
    data class Preparing(val message: String) : LocalTtsRuntimeState
    data class Generating(val chapterId: String, val segmentIndex: Int) : LocalTtsRuntimeState
    data object Paused : LocalTtsRuntimeState
    data class Ready(val voice: String) : LocalTtsRuntimeState
    data class Failed(val message: String) : LocalTtsRuntimeState
}

/** Coordinates one resident native model and publishes complete PCM segments to private storage. */
class LocalTtsRuntime(context: Context, val modelPack: LocalTtsModelPack = LocalTtsModelPack(context)) {
    private val context = context.applicationContext
    private val root = File(context.noBackupFilesDir, "local-tts/segments").apply { mkdirs() }
    private val state = MutableStateFlow<LocalTtsRuntimeState>(LocalTtsRuntimeState.Idle)
    private val cancelled = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    val states = state.asStateFlow()

    fun capabilities(): String = LocalTtsNative.capabilities()

    fun prepare(connection: RelayConnection? = null, threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)): LocalTtsManifest {
        state.value = LocalTtsRuntimeState.Preparing("准备本地模型")
        return try {
            val manifest = modelPack.prepare(connection)
            val backbone = modelPack.file(manifest, manifest.files.first { it.name == "backbone-ptq.gguf" }.path)
            val decoder = modelPack.file(manifest, manifest.files.first { it.name == "zac-keep2.gguf" }.path)
            require(LocalTtsNative.load(backbone.absolutePath, decoder.absolutePath, threads)) { "本机原生推理库或模型不可用" }
            state.value = LocalTtsRuntimeState.Ready(manifest.voices.firstOrNull()?.name ?: "本地声线")
            manifest
        } catch (error: Exception) {
            val message = error.message ?: "本地模型准备失败"
            state.value = LocalTtsRuntimeState.Failed(message)
            throw IllegalStateException(message, error)
        }
    }

    fun cancel() {
        cancelled.set(true)
        paused.set(false)
        LocalTtsNative.cancel()
    }

    fun pause() { paused.set(true); state.value = LocalTtsRuntimeState.Paused }
    fun resume() { paused.set(false) }

    fun release() {
        cancelled.set(true)
        LocalTtsNative.release()
        state.value = LocalTtsRuntimeState.Idle
    }

    @Synchronized
    fun synthesizeSegment(
        key: LocalTtsSegmentKey,
        sourceStart: Int,
        sourceEnd: Int,
        maxFrames: Int = 300,
        stabilizeShortText: Boolean = false
    ): LocalTtsSegment {
        val manifest = modelPack.current() ?: error("本地模型尚未准备")
        val voice = manifest.voices.firstOrNull { it.id == key.voiceId || it.speaker == key.voiceId }
            ?: error("本地模型没有声线：${key.voiceId}")
        val output = File(root, "${key.id()}.wav")
        if (isValidWav(output)) {
            return LocalTtsSegment(key.id(), output, SAMPLE_RATE, wavSamples(output), sourceStart, sourceEnd, key.text)
        }
        // A process kill can leave a truncated file after the temporary file
        // was published. It must never be treated as a completed segment.
        if (output.exists()) output.delete()
        cancelled.set(false)
        state.value = LocalTtsRuntimeState.Generating(key.chapterId, key.segmentIndex)
        val codes = modelPack.file(manifest, voice.codes)
        var pcm: ByteArray? = null
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            if (pcm != null || cancelled.get()) return@repeat
            pcm = runCatching {
                LocalTtsNative.synthesize(
                    key.text,
                    codes.absolutePath,
                    maxFrames,
                    UUID.randomUUID().hashCode(),
                    stabilizeShortText
                )
                    ?: error("native 返回空音频")
            }.onFailure { lastError = it }.getOrNull()
            if (pcm == null && attempt < 2) Thread.yield()
        }
        if (cancelled.get()) throw java.util.concurrent.CancellationException("本地语音合成已取消")
        val generatedPcm = pcm ?: throw IllegalStateException("本地语音合成失败", lastError)
        if (cancelled.get()) throw java.util.concurrent.CancellationException("本地语音合成已取消")
        require(generatedPcm.isNotEmpty() && generatedPcm.size % 2 == 0) { "本地合成返回了空音频" }
        val temporary = File(root, ".${key.id()}.${UUID.randomUUID()}.part")
        writeWav(temporary, generatedPcm, SAMPLE_RATE)
        require(temporary.renameTo(output)) { "发布本地音频失败" }
        state.value = LocalTtsRuntimeState.Ready(voice.name)
        return LocalTtsSegment(key.id(), output, SAMPLE_RATE, generatedPcm.size / 2, sourceStart, sourceEnd, key.text)
    }

    @Synchronized
    fun synthesizeChapter(book: Book, chapterIndex: Int, voiceId: String = DEFAULT_VOICE_ID): LocalTtsChapterResult {
        return synthesizeChapterStreaming(book, chapterIndex, voiceId) { }
    }

    /** Generate one title/sentence segment at a time and publish its PCM immediately. */
    @Synchronized
    fun synthesizeChapterStreaming(
        book: Book,
        chapterIndex: Int,
        voiceId: String = DEFAULT_VOICE_ID,
        startOffset: Int = 0,
        onSegment: (LocalTtsStreamSegment) -> Unit
    ): LocalTtsChapterResult {
        val chapter = book.chapters.getOrNull(chapterIndex) ?: error("章节不在书库中")
        require(chapter.body.isNotBlank()) { "请先加载章节正文" }
        val manifest = modelPack.current() ?: error("本地模型尚未准备")
        val voice = manifest.voices.firstOrNull { it.id == voiceId || it.speaker == voiceId }
            ?: manifest.voices.firstOrNull { it.name == voiceId }
            ?: error("本地模型没有可用声线：$voiceId")
        val sourceRevision = chapter.audioSourceRevision
        val pieces = mutableListOf<Piece>()
        pieces += Piece(-1, 0, 0, chapter.title)
        pieces += sentencePieces(chapter.text)
        val pcm = java.io.ByteArrayOutputStream()
        val cues = mutableListOf<AudioCue>()
        var positionMs = 0
        var previousTtsText: String? = null
        var previousPieceIndex: Int? = null
        val resumeOffset = startOffset.coerceIn(0, chapter.text.length)
        var streamingStarted = resumeOffset <= 0
        var streamOriginMs = 0
        for ((piecePosition, piece) in pieces.withIndex()) {
            waitUntilRunnable()
            if (cancelled.get()) throw java.util.concurrent.CancellationException("本地语音合成已取消")
            val ttsText = TtsTextNormalizer.normalize(piece.text)
            if (ttsText.isBlank() || !TtsTextNormalizer.containsSpokenContent(ttsText)) continue
            val shouldStream = resumeOffset <= 0 || (piece.index >= 0 && piece.end > resumeOffset)
            previousTtsText?.let { previous ->
                val pauseMs = if (previousPieceIndex == -1) TITLE_PAUSE_MS else pauseFor(previous)
                if (pauseMs > 0) {
                    val silence = ByteArray(pauseMs * SAMPLE_RATE / 1000 * 2)
                    pcm.write(silence)
                    // Do not replay the pause that belongs to text before the
                    // resume point. It is part of the complete chapter file,
                    // but the live stream starts at the first resumed cue.
                    if (shouldStream && streamingStarted) {
                        onSegment(LocalTtsStreamSegment(null, silence, isPause = true, totalSegments = pieces.size))
                    }
                    positionMs += pauseMs
                }
            }
            val stabilizeShortText = piece.index < 0 || ttsText.length <= SHORT_TEXT_CHAR_LIMIT
            val key = LocalTtsSegmentKey(
                book.id,
                chapter.id,
                sourceRevision,
                piece.index,
                ttsText,
                voice.id,
                manifest.revision,
                "${manifest.engineRevision}:$LOCAL_TTS_SYNTHESIS_POLICY_REVISION"
            )
            val segment = synthesizeSegment(
                key,
                piece.start,
                piece.end,
                stabilizeShortText = stabilizeShortText
            )
            val bytes = segment.path.readBytes()
            require(bytes.size > 44 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())) { "本地片段 WAV 无效" }
            val payload = bytes.copyOfRange(44, bytes.size)
            pcm.write(payload)
            val endMs = positionMs + segment.samples * 1000 / SAMPLE_RATE
            val kind = if (piece.index < 0) "title" else "body"
            val cue = AudioCue(positionMs, endMs, if (kind == "title") 0 else piece.start,
                if (kind == "title") 0 else piece.end, piece.text, kind)
            cues += cue
            // When resuming, the complete chapter still keeps its original
            // cue timeline, but the live player starts at the first sentence
            // after the checkpoint. Rebase emitted cues to that live stream so
            // sentence highlighting and progress remain aligned.
            if (shouldStream) {
                if (!streamingStarted) {
                    streamingStarted = true
                    streamOriginMs = cue.startMs
                }
                val liveCue = cue.copy(
                    startMs = (cue.startMs - streamOriginMs).coerceAtLeast(0),
                    endMs = (cue.endMs - streamOriginMs).coerceAtLeast(1)
                )
                onSegment(LocalTtsStreamSegment(liveCue, payload, segmentIndex = piecePosition, totalSegments = pieces.size))
            }
            positionMs = endMs
            previousTtsText = ttsText
            previousPieceIndex = piece.index
        }
        val chapterId = localTtsSha256("chapter:${book.id}:${chapter.id}:$sourceRevision:${voice.id}:${manifest.revision}")
        val output = File(root, "$chapterId.wav")
        writeWav(output, pcm.toByteArray(), SAMPLE_RATE)
        return LocalTtsChapterResult(output, positionMs, cues, voice.id, voice.name, sourceRevision, LOCAL_TTS_PAUSE_RULE_REVISION)
    }

    fun removeSegment(key: LocalTtsSegmentKey) { File(root, "${key.id()}.wav").delete() }

    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
                .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate)
                .putInt(sampleRate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size)
            output.write(header.array())
            output.write(pcm)
            output.fd.sync()
        }
    }

    private fun wavSamples(file: File): Int = ((file.length() - 44).coerceAtLeast(0) / 2L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun isValidWav(file: File): Boolean {
        if (!file.isFile || file.length() <= 44) return false
        val header = ByteArray(44)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) return false
                offset += count
            }
        }
        if (!header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) ||
            !header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray()) ||
            !header.copyOfRange(12, 16).contentEquals("fmt ".toByteArray()) ||
            !header.copyOfRange(36, 40).contentEquals("data".toByteArray())) return false
        val payload = ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return payload > 0 && payload % 2 == 0 && 44L + payload == file.length()
    }

    private fun waitUntilRunnable() {
        val manager = context.getSystemService(ActivityManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        while (true) {
            if (cancelled.get()) throw java.util.concurrent.CancellationException("本地语音合成已取消")
            manager?.getMemoryInfo(memoryInfo)
            val tooHot = Build.VERSION.SDK_INT >= 29 && power != null &&
                power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
            if (!paused.get() && !memoryInfo.lowMemory && !tooHot && root.usableSpace >= MIN_FREE_BYTES) break
            state.value = LocalTtsRuntimeState.Paused
            Thread.sleep(250)
        }
    }

    private fun pauseFor(text: String): Int = when (text.trimEnd().lastOrNull()) {
        '。', '！', '？', '!', '?', '…' -> 220
        '；', ';' -> 150
        '，', ',', '、' -> 100
        '：', ':' -> 120
        else -> 80
    }

    private data class Piece(val index: Int, val start: Int, val end: Int, val text: String)

    private fun sentencePieces(text: String): List<Piece> {
        val result = mutableListOf<Piece>()
        var start = 0
        var index = 0
        while (start < text.length) {
            var offset = start
            var boundary = -1
            while (offset < text.length) {
                val character = text[offset]
                val length = offset + 1 - start
                val hardBoundary = length >= MAX_PIECE_CHARS
                val softBoundary = length >= MIN_SOFT_PIECE_CHARS &&
                    character in "，,、；;：:\n"
                if (character in "。！？!?…") {
                    var end = offset + 1
                    while (end < text.length && text[end] in "。！？!?…”’」』》〉）)]}】〕》\"'") end++
                    boundary = end
                    break
                }
                if (hardBoundary || softBoundary) {
                    boundary = offset + 1
                    break
                }
                offset++
            }
            if (boundary < 0) boundary = text.length
            if (boundary > start) result += Piece(index++, start, boundary, text.substring(start, boundary))
            start = boundary
        }
        return result.ifEmpty { listOf(Piece(0, 0, text.length, text)) }
    }

    companion object {
        const val SAMPLE_RATE = 44100
        const val DEFAULT_VOICE_ID = "clone-linchuan-zonos3-v1"
        const val PAUSE_RULE_REVISION = "punctuation-pauses-v3-tts-text-normalization-v1"
        private const val MIN_FREE_BYTES = 128L * 1024 * 1024
        private const val MIN_SOFT_PIECE_CHARS = 48
        private const val MAX_PIECE_CHARS = 96
        private const val SHORT_TEXT_CHAR_LIMIT = 24
        private const val TITLE_PAUSE_MS = 700
    }
}
