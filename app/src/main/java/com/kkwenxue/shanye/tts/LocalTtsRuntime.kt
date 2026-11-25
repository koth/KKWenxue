package com.kkwenxue.shanye.tts

import android.content.Context
import android.app.ActivityManager
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.kkwenxue.shanye.data.Book
import com.kkwenxue.shanye.data.AudioCue
import com.kkwenxue.shanye.data.ChineseScript
import com.kkwenxue.shanye.data.ChineseScriptConverter
import com.kkwenxue.shanye.data.RelayConnection

private fun localTtsSha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
private const val LOCAL_TTS_PAUSE_RULE_REVISION = "punctuation-pauses-v3-${TtsTextNormalizer.REVISION}"
private const val LOCAL_TTS_SYNTHESIS_POLICY_REVISION = "title-server-short-text-v2"

private fun localTtsWavHeader(pcmBytes: Int, sampleRate: Int): ByteArray =
    ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVE".toByteArray())
        .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate)
        .putInt(sampleRate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcmBytes)
        .array()

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
    val totalSegments: Int = 0,
    val chapterId: String? = null,
    val chapterIndex: Int = -1
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
        titleSynthesis: Boolean = false
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
        require(codes.isFile && codes.length() > 0) {
            "本地声线编码文件不存在：${voice.name}（${voice.codes}）"
        }
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
                    titleSynthesis
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
        if (shouldSpeakTitle(chapter)) {
            pieces += titlePieces(chapter.title)
        }
        pieces += sentencePieces(chapter.text)
        val cues = mutableListOf<AudioCue>()
        var positionMs = 0
        var previousTtsText: String? = null
        var previousPieceIndex: Int? = null
        val resumeOffset = startOffset.coerceIn(0, chapter.text.length)
        var streamingStarted = resumeOffset <= 0
        var streamOriginMs = 0
        val chapterId = localTtsSha256("chapter:${book.id}:${chapter.id}:$sourceRevision:${voice.id}:${manifest.revision}")
        val output = File(root, "$chapterId.wav")
        val temporary = File(root, ".${chapterId}.${UUID.randomUUID()}.part")
        val wav = WavStreamWriter(temporary, SAMPLE_RATE)
        // On resume, stream the unread suffix before rebuilding the already
        // read prefix. Otherwise a long prefix delays the first audible cue.
        val streamedPieceIndexes = mutableSetOf<Int>()
        try {
            if (resumeOffset > 0) {
                val firstResumedPiece = pieces.indexOfFirst { piece ->
                    piece.index >= 0 && piece.end > resumeOffset
                }
                if (firstResumedPiece >= 0) {
                    var livePositionMs = 0
                    var previousStreamText: String? = null
                    for (piecePosition in firstResumedPiece until pieces.size) {
                        waitUntilRunnable()
                        if (cancelled.get()) throw java.util.concurrent.CancellationException("本地语音合成已取消")
                        val piece = pieces[piecePosition]
                        val ttsText = ttsTextFor(piece)
                        if (ttsText.isBlank() || !TtsTextNormalizer.containsSpokenContent(ttsText)) continue
                        previousStreamText?.let { previous ->
                            val pauseMs = pauseFor(previous)
                            if (pauseMs > 0) {
                                val silence = ByteArray(pauseMs * SAMPLE_RATE / 1000 * 2)
                                onSegment(LocalTtsStreamSegment(
                                    null, silence, isPause = true, totalSegments = pieces.size,
                                    chapterId = chapter.id, chapterIndex = chapterIndex
                                ))
                                livePositionMs += pauseMs
                            }
                        }
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
                        val segment = synthesizeSegment(key, piece.start, piece.end)
                        val payload = readWavPayload(segment.path)
                        val endMs = livePositionMs + segment.samples * 1000 / SAMPLE_RATE
                        val liveCue = AudioCue(
                            livePositionMs,
                            endMs,
                            piece.start,
                            piece.end,
                            piece.text,
                            "body"
                        )
                        onSegment(LocalTtsStreamSegment(
                            liveCue,
                            payload,
                            segmentIndex = piecePosition,
                            totalSegments = pieces.size,
                            chapterId = chapter.id,
                            chapterIndex = chapterIndex
                        ))
                        streamedPieceIndexes += piecePosition
                        livePositionMs = endMs
                        previousStreamText = ttsText
                    }
                }
            }
            for ((piecePosition, piece) in pieces.withIndex()) {
                waitUntilRunnable()
                if (cancelled.get()) throw java.util.concurrent.CancellationException("本地语音合成已取消")
                val ttsText = ttsTextFor(piece)
                if (ttsText.isBlank() || !TtsTextNormalizer.containsSpokenContent(ttsText)) continue
                val shouldStream = resumeOffset <= 0 || (piece.index >= 0 && piece.end > resumeOffset)
                val alreadyStreamed = streamedPieceIndexes.contains(piecePosition)
                previousTtsText?.let { previous ->
                    val pauseMs = when {
                        previousPieceIndex != null && previousPieceIndex < 0 && piece.index < 0 ->
                            TITLE_INTERNAL_PAUSE_MS
                        previousPieceIndex != null && previousPieceIndex < 0 -> TITLE_PAUSE_MS
                        else -> pauseFor(previous)
                    }
                    if (pauseMs > 0) {
                        val silence = ByteArray(pauseMs * SAMPLE_RATE / 1000 * 2)
                        wav.write(silence)
                        // Do not replay the pause that belongs to text before the
                        // resume point. It is part of the complete chapter file,
                        // but the live stream starts at the first resumed cue.
                        if (shouldStream && streamingStarted && !alreadyStreamed) {
                            onSegment(LocalTtsStreamSegment(
                                null, silence, isPause = true, totalSegments = pieces.size,
                                chapterId = chapter.id, chapterIndex = chapterIndex
                            ))
                        }
                        positionMs += pauseMs
                    }
                }
                // The server sends headings through the alignment-aware path. The
                // native bridge uses the same Chinese alignment heads to stop at
                // the heading's text boundary instead of guessing from waveform
                // silence or appending a filler sentence.
                val titleSynthesis = piece.index < 0
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
                    titleSynthesis = titleSynthesis
                )
                val payload = readWavPayload(segment.path)
                wav.write(payload)
                val endMs = positionMs + segment.samples * 1000 / SAMPLE_RATE
                val kind = if (piece.index < 0) "title" else "body"
                val cue = AudioCue(positionMs, endMs, if (kind == "title") 0 else piece.start,
                    if (kind == "title") 0 else piece.end, piece.text, kind)
                cues += cue
                // When resuming, the complete chapter still keeps its original
                // cue timeline, but the live player starts at the first sentence
                // after the checkpoint. Rebase emitted cues to that live stream so
                // sentence highlighting and progress remain aligned.
                if (shouldStream && !alreadyStreamed) {
                    if (!streamingStarted) {
                        streamingStarted = true
                        streamOriginMs = cue.startMs
                    }
                    val liveCue = cue.copy(
                        startMs = (cue.startMs - streamOriginMs).coerceAtLeast(0),
                        endMs = (cue.endMs - streamOriginMs).coerceAtLeast(1)
                    )
                    onSegment(LocalTtsStreamSegment(
                        liveCue,
                        payload,
                        segmentIndex = piecePosition,
                        totalSegments = pieces.size,
                        chapterId = chapter.id,
                        chapterIndex = chapterIndex
                    ))
                }
                positionMs = endMs
                previousTtsText = ttsText
                previousPieceIndex = piece.index
            }
            wav.finish()
            if (output.exists() && !output.delete()) error("无法替换本地章节音频")
            require(temporary.renameTo(output)) { "发布本地章节音频失败" }
            return LocalTtsChapterResult(output, positionMs, cues, voice.id, voice.name, sourceRevision, LOCAL_TTS_PAUSE_RULE_REVISION)
        } catch (error: Throwable) {
            wav.abort()
            throw error
        }
    }

    fun removeSegment(key: LocalTtsSegmentKey) { File(root, "${key.id()}.wav").delete() }

    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            output.write(localTtsWavHeader(pcm.size, sampleRate))
            output.write(pcm)
            output.fd.sync()
        }
    }

    private fun readWavPayload(file: File): ByteArray {
        require(isValidWav(file)) { "本地片段 WAV 无效" }
        require(file.length() - 44L <= Int.MAX_VALUE.toLong()) { "本地片段 WAV 过大" }
        val payloadSize = (file.length() - 44L).toInt()
        require(payloadSize > 0) { "本地片段 WAV 没有音频数据" }
        val payload = ByteArray(payloadSize)
        FileInputStream(file).use { input ->
            var headerRemaining = 44
            while (headerRemaining > 0) {
                val count = input.skip(headerRemaining.toLong()).toInt()
                if (count > 0) {
                    headerRemaining -= count
                } else if (input.read() >= 0) {
                    headerRemaining--
                } else {
                    error("本地片段 WAV 头部不完整")
                }
            }
            var offset = 0
            while (offset < payload.size) {
                val count = input.read(payload, offset, payload.size - offset)
                if (count < 0) error("本地片段 WAV 数据不完整")
                offset += count
            }
        }
        return payload
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

    private class WavStreamWriter(private val file: File, private val sampleRate: Int) {
        private var output: FileOutputStream? = FileOutputStream(file).also { it.write(ByteArray(44)) }
        private var payloadBytes = 0L

        fun write(pcm: ByteArray) {
            require(pcm.size % 2 == 0) { "PCM 数据长度必须为偶数" }
            output?.write(pcm) ?: error("WAV 输出已关闭")
            payloadBytes += pcm.size
        }

        fun finish() {
            val current = output ?: error("WAV 输出已关闭")
            require(payloadBytes in 2..Int.MAX_VALUE.toLong()) { "章节音频过大或为空" }
            current.fd.sync()
            current.close()
            output = null
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(localTtsWavHeader(payloadBytes.toInt(), sampleRate))
                raf.fd.sync()
            }
        }

        fun abort() {
            output?.runCatching { close() }
            output = null
            file.delete()
        }
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

    private fun shouldSpeakTitle(chapter: com.kkwenxue.shanye.data.Chapter): Boolean {
        val title = chapter.title.trim()
        if (!TtsTextNormalizer.containsSpokenContent(title) ||
            !TtsTextNormalizer.containsSpokenContent(chapter.text)) return false
        val firstLine = chapter.body.trimStart().lineSequence()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return titleKey(firstLine) != titleKey(title)
    }

    private fun titleKey(value: String): String =
        ChineseScriptConverter.convert(value, ChineseScript.SIMPLIFIED)
            .filter { it.isLetterOrDigit() || Character.isLetter(it) }

    private fun ttsTextFor(piece: Piece): String {
        val normalized = TtsTextNormalizer.normalize(piece.text)
        return ChineseScriptConverter.convert(normalized, ChineseScript.SIMPLIFIED)
    }

    /**
     * Match the server's bounded heading segments. A very long heading should
     * not be sent as one unbounded title take, while each piece still retains
     * the original title text for the cue and cache key.
     */
    private fun titlePieces(title: String): List<Piece> {
        val result = mutableListOf<Piece>()
        var start = 0
        var index = 0
        val chapterPrefixEnd = chapterPrefixEnd(title)
        if (chapterPrefixEnd != null && chapterPrefixEnd < title.length) {
            result += Piece(-1, 0, chapterPrefixEnd, title.substring(0, chapterPrefixEnd))
            start = chapterPrefixEnd
            index = 1
        }
        while (start < title.length) {
            val hardEnd = findHardBoundary(title, start)
            val remaining = title.length - start
            val boundary = when {
                remaining <= MAX_TITLE_PIECE_CHARS -> title.length
                hardEnd != null && hardEnd - start <= MAX_TITLE_PIECE_CHARS -> hardEnd
                else -> chooseTitleBoundary(title, start, hardEnd ?: title.length)
            }
            if (boundary <= start) break
            result += Piece(-1 - index, start, boundary, title.substring(start, boundary))
            index++
            start = boundary
        }
        return result
    }

    private fun chapterPrefixEnd(text: String): Int? {
        val match = CHAPTER_PREFIX.find(text) ?: return null
        return match.range.last + 1
    }

    private fun chooseTitleBoundary(text: String, start: Int, sentenceEnd: Int): Int {
        val maxEnd = minOf(text.length, start + MAX_TITLE_PIECE_CHARS, sentenceEnd)
        val minEnd = minOf(text.length, start + MIN_TITLE_PIECE_CHARS)
        val target = minOf(text.length, start + TARGET_TITLE_PIECE_CHARS, sentenceEnd)
        var best: Int? = null
        var bestDistance = Int.MAX_VALUE
        for (offset in minEnd until maxEnd) {
            if (text[offset] !in SOFT_BOUNDARIES) continue
            val candidate = includeClosingPunctuation(text, offset + 1)
            if (candidate > maxEnd) continue
            val distance = kotlin.math.abs(candidate - target)
            if (distance < bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best ?: minOf(text.length, target, sentenceEnd)
    }

    private fun sentencePieces(text: String): List<Piece> {
        val result = mutableListOf<Piece>()
        var start = 0
        var index = 0
        while (start < text.length) {
            val hardEnd = findHardBoundary(text, start)
            val remaining = text.length - start
            // Keep ordinary short sentences intact. Long sentences are split
            // around a comma/semicolon near the target size, rather than at
            // the first punctuation after the minimum size.
            val boundary = when {
                remaining <= MAX_PIECE_CHARS -> text.length
                hardEnd != null && hardEnd - start <= MAX_PIECE_CHARS -> hardEnd
                else -> chooseLongPieceBoundary(text, start, hardEnd ?: text.length)
            }
            if (boundary > start) result += Piece(index++, start, boundary, text.substring(start, boundary))
            start = boundary
        }
        return result.ifEmpty { listOf(Piece(0, 0, text.length, text)) }
    }

    private fun findHardBoundary(text: String, start: Int): Int? {
        for (offset in start until text.length) {
            if (text[offset] in HARD_BOUNDARIES) {
                return includeClosingPunctuation(text, offset + 1)
            }
        }
        return null
    }

    private fun chooseLongPieceBoundary(text: String, start: Int, sentenceEnd: Int): Int {
        val maxEnd = minOf(text.length, start + MAX_PIECE_CHARS, sentenceEnd)
        val minEnd = minOf(text.length, start + MIN_SOFT_PIECE_CHARS)
        val target = minOf(text.length, start + TARGET_PIECE_CHARS, sentenceEnd)
        var best: Int? = null
        var bestDistance = Int.MAX_VALUE
        for (offset in minEnd until maxEnd) {
            if (text[offset] !in SOFT_BOUNDARIES) continue
            val candidate = includeClosingPunctuation(text, offset + 1)
            if (candidate > maxEnd) continue
            // Avoid making a tiny tail when a slightly earlier split gives a
            // more balanced pair of chunks.
            if (sentenceEnd - candidate in 1 until MIN_SOFT_PIECE_CHARS) continue
            val distance = kotlin.math.abs(candidate - target)
            if (distance < bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best ?: minOf(text.length, start + TARGET_PIECE_CHARS, sentenceEnd)
    }

    private fun includeClosingPunctuation(text: String, initialEnd: Int): Int {
        var end = initialEnd
        while (end < text.length && text[end] in CLOSING_PUNCTUATION) end++
        return end
    }

    companion object {
        const val SAMPLE_RATE = 44100
        const val DEFAULT_VOICE_ID = "clone-linchuan-zonos3-v1"
        const val PAUSE_RULE_REVISION = "punctuation-pauses-v3-tts-text-normalization-v1"
        private const val MIN_FREE_BYTES = 128L * 1024 * 1024
        private const val TARGET_PIECE_CHARS = 64
        private const val MIN_SOFT_PIECE_CHARS = 36
        private const val MAX_PIECE_CHARS = 88
        private const val SHORT_TEXT_CHAR_LIMIT = 24
        private const val TARGET_TITLE_PIECE_CHARS = 48
        private const val MIN_TITLE_PIECE_CHARS = 24
        private const val MAX_TITLE_PIECE_CHARS = 72
        private const val TITLE_INTERNAL_PAUSE_MS = 500
        private const val TITLE_PAUSE_MS = 700
        private const val HARD_BOUNDARIES = "。！？!?…"
        private const val SOFT_BOUNDARIES = "，,、；;：:\n"
        private const val CLOSING_PUNCTUATION = "。！？!?…”’」』》〉）)]}】〕》\"'"
        private val CHAPTER_PREFIX = Regex(
            "^第\\s*[0-9零〇一二三四五六七八九十百千万两兩]+\\s*[章节節回卷部集篇](?:\\s+|[：:]\\s*|$)"
        )
    }
}
