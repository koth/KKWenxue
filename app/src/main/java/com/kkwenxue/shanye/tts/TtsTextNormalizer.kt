package com.kkwenxue.shanye.tts

/**
 * Converts reader text into the smaller punctuation vocabulary understood well by
 * the local model. The returned text is only used as TTS input; cue offsets and
 * displayed text always continue to use the original chapter text.
 */
object TtsTextNormalizer {
    const val REVISION = "tts-text-normalization-v1"

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

        fun pauseFor(character: Char): Char = when (character) {
            '。', '.', '．' -> '。'
            '！', '!' -> '！'
            '？', '?' -> '？'
            '…' -> '…'
            '；', ';' -> '；'
            '：', ':' -> '：'
            '—', '–', '-', '～', '~' -> '，'
            else -> '，'
        }

        for (character in input) {
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
                    val candidate = pauseFor(character)
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

    private fun pauseRank(value: Char): Int = when (value) {
        '。', '！', '？', '…' -> 4
        '；' -> 3
        '：' -> 2
        '，' -> 1
        else -> 0
    }

    private val normalizedPauses = setOf('。', '！', '？', '…', '；', '：', '，')
}
