package com.kkwenxue.shanye.tts

/**
 * Converts reader text into the smaller punctuation vocabulary understood well by
 * the local model. The returned text is only used as TTS input; cue offsets and
 * displayed text always continue to use the original chapter text.
 */
object TtsTextNormalizer {
    const val REVISION = "tts-text-normalization-v4"

    data class NarrationChunk(val start: Int, val end: Int) {
        init {
            require(start >= 0 && end >= start) { "旁白分句范围无效" }
        }
    }

    private val decorative = setOf(
        '《', '》', '〈', '〉', '「', '」', '『', '』', '【', '】', '〔', '〕',
        '〖', '〗', '〘', '〙', '〚', '〛', '（', '）', '(', ')', '［', '］',
        '[', ']', '｛', '｝', '{', '}', '“', '”', '‘', '’', '"', '\''
    )
    private val punctuation = setOf(
        '。', '！', '？', '!', '?', '…', '.', '；', ';', '，', ',', '、', '：', ':',
        '—', '–', '-', '～', '~'
    )

    /**
     * Keep at most one meaningful pause marker for a consecutive punctuation run.
     * Decorative quotes/brackets are dropped instead of being sent to the model.
     */
    fun normalize(input: String): String {
        val output = StringBuilder(input.length)
        var pendingPause: Char? = null
        var pendingSpace = false

        fun appendPause() {
            pendingPause?.let {
                if (output.isNotEmpty() && output.last() == ' ') output.deleteCharAt(output.lastIndex)
                if (output.isNotEmpty() && output.last() in normalizedPauses) {
                    if (pauseRank(it) > pauseRank(output.last())) output.setCharAt(output.lastIndex, it)
                } else {
                    output.append(it)
                }
            }
            pendingPause = null
        }

        fun pauseFor(character: Char, index: Int): Char = when (character) {
            '。' -> '。'
            '.', '．' -> if (isEllipsisDot(input, index)) '…' else '。'
            '！', '!' -> '！'
            '？', '?' -> '？'
            '…' -> '…'
            '；', ';' -> '；'
            '：', ':' -> '：'
            '—', '–', '-', '～', '~' -> '，'
            else -> '，'
        }

        for ((index, character) in input.withIndex()) {
            when {
                character.isWhitespace() -> {
                    appendPause()
                    pendingSpace = true
                }
                character in decorative -> {
                    // Keep a pending pause across closing quotes/brackets, so
                    // `！？】` still becomes one terminal pause.
                }
                character in punctuation || character == '．' -> {
                    pendingSpace = false
                    val candidate = pauseFor(character, index)
                    val previous = pendingPause
                    pendingPause = when {
                        previous == null -> candidate
                        pauseRank(candidate) > pauseRank(previous) -> candidate
                        else -> previous
                    }
                }
                else -> {
                    appendPause()
                    if (pendingSpace && output.isNotEmpty() && output.last() != ' ') output.append(' ')
                    pendingSpace = false
                    output.append(character)
                }
            }
        }
        appendPause()
        return output.toString().trim()
    }

    fun containsSpokenContent(value: String): Boolean =
        value.any { it.isLetterOrDigit() || Character.isLetter(it) }

    /**
     * Split a body sentence into natural phrase-sized takes.
     *
     * The local model does not expose word alignment, so a complete sentence
     * cannot be edited safely after synthesis. Instead, keep the original
     * UTF-16 ranges and synthesize a few phrase-sized takes; the runtime adds a
     * very short PCM pause between takes. Punctuation and common conjunctions
     * are preferred, while short clauses are kept intact.
     */
    fun splitNarration(input: String): List<NarrationChunk> {
        if (input.isEmpty()) return emptyList()
        val result = mutableListOf<NarrationChunk>()
        var start = 0
        while (start < input.length) {
            val remaining = input.length - start
            if (remaining <= MAX_NARRATION_CHARS) {
                val natural = chooseNaturalBoundary(input, start, input.length, preferTarget = true)
                if (natural == null) {
                    result += NarrationChunk(start, input.length)
                    break
                }
                result += NarrationChunk(start, natural)
                start = natural
                continue
            }

            val maxEnd = minOf(input.length, start + MAX_NARRATION_CHARS)
            val natural = chooseNaturalBoundary(input, start, maxEnd, preferTarget = false)
            val end = natural ?: minOf(input.length, start + TARGET_NARRATION_CHARS)
            if (end <= start) {
                result += NarrationChunk(start, input.length)
                break
            }
            result += NarrationChunk(start, end)
            start = end
        }
        return result.ifEmpty { listOf(NarrationChunk(0, input.length)) }
    }

    private fun chooseNaturalBoundary(
        input: String,
        start: Int,
        maxEnd: Int,
        preferTarget: Boolean
    ): Int? {
        val minimumEnd = start + MIN_NARRATION_CHARS
        if (minimumEnd >= maxEnd) return null
        val target = if (preferTarget) {
            minOf(maxEnd - MIN_NARRATION_TAIL_CHARS, start + TARGET_NARRATION_CHARS)
        } else {
            minOf(maxEnd, start + TARGET_NARRATION_CHARS)
        }
        val candidates = mutableListOf<Int>()
        for (offset in minimumEnd until maxEnd) {
            val previous = input[offset - 1]
            if (previous in NARRATION_PAUSE_PUNCTUATION &&
                offset - start >= MIN_PUNCTUATION_PHRASE_CHARS &&
                !isGroupedNumberComma(input, offset - 1)
            ) {
                val end = includeClosingPunctuation(input, offset)
                if (end <= maxEnd && input.length - end >= MIN_NARRATION_TAIL_CHARS) {
                    candidates += end
                }
            }
            if (previous.isWhitespace() &&
                offset - start >= MIN_PUNCTUATION_PHRASE_CHARS &&
                containsCjk(input, start, offset)
            ) {
                val end = offset
                if (input.length - end >= MIN_NARRATION_TAIL_CHARS) candidates += end
            }
            if (startsWithNarrationConnector(input, offset) &&
                offset - start >= MIN_CONNECTOR_PHRASE_CHARS &&
                input.length - offset >= MIN_NARRATION_TAIL_CHARS
            ) {
                candidates += offset
            }
        }
        return candidates.minByOrNull { kotlin.math.abs(it - target) }
    }

    private fun startsWithNarrationConnector(input: String, offset: Int): Boolean =
        NARRATION_CONNECTORS.any { connector ->
            offset + connector.length <= input.length &&
                input.startsWith(connector, offset)
        }

    private fun containsCjk(input: String, start: Int, end: Int): Boolean =
        input.substring(start, end).any { it in '\u3400'..'\u9fff' }

    private fun isGroupedNumberComma(input: String, offset: Int): Boolean =
        offset > 0 && offset + 1 < input.length &&
            input[offset] in ",，" &&
            input[offset - 1].isDigit() &&
            input[offset + 1].isDigit()

    private fun isEllipsisDot(input: String, index: Int): Boolean {
        val character = input[index]
        return input.getOrNull(index - 1) == character || input.getOrNull(index + 1) == character
    }

    private fun includeClosingPunctuation(input: String, initialEnd: Int): Int {
        var end = initialEnd
        while (end < input.length && input[end] in NARRATION_CLOSING_PUNCTUATION) end++
        return end
    }

    private fun pauseRank(value: Char): Int = when (value) {
        '。', '！', '？', '…' -> 4
        '；' -> 3
        '：' -> 2
        '，' -> 1
        else -> 0
    }

    private val normalizedPauses = setOf('。', '！', '？', '…', '；', '：', '，')
    private const val TARGET_NARRATION_CHARS = 36
    private const val MIN_NARRATION_CHARS = 10
    private const val MIN_PUNCTUATION_PHRASE_CHARS = 10
    private const val MIN_CONNECTOR_PHRASE_CHARS = 18
    private const val MIN_NARRATION_TAIL_CHARS = 8
    private const val MAX_NARRATION_CHARS = 64
    private const val NARRATION_PAUSE_PUNCTUATION = "，,、；;：:\n"
    private const val NARRATION_CLOSING_PUNCTUATION = "”’」』》〉）)]}】〕\"'"
    private val NARRATION_CONNECTORS = listOf(
        "不过", "但是", "然而", "于是", "然后", "接着", "随后", "同时",
        "此时", "这时", "只见", "原来", "其实", "因为", "所以", "因此",
        "只是", "没想到", "就在", "与此同时", "话音刚落", "过了片刻",
        "片刻后", "不一会儿"
    )
}
