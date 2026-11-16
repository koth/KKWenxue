package com.kkwenxue.shanye.reader

/** A commentable sentence. Offsets are UTF-16 offsets into the chapter's canonical text. */
data class SentenceRange(val start: Int, val end: Int, val text: String) {
    val cleanText: String get() = text.replace(Regex("\\s+"), " ").trim()
    val key: String get() = "$start:$end"
}

object SentenceSegmentation {
    private val terminal = setOf('。', '！', '？', '!', '?', '…')
    private val closers = setOf('”', '’', '」', '』', '》', '〉', '）', ')', ']', '}', '】', '〕')

    /**
     * Split a novel chapter into commentable sentences. Commas remain inside a sentence;
     * terminal punctuation, repeated ellipses/question marks and closing quotes stay with it.
     */
    fun split(text: String): List<SentenceRange> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<SentenceRange>()
        var start = 0
        var index = 0
        while (index < text.length) {
            if (text[index] in terminal) {
                index++
                while (index < text.length && text[index] in terminal) index++
                while (index < text.length && text[index] in closers) index++
                result += SentenceRange(start, index, text.substring(start, index))
                start = index
            } else {
                index++
            }
        }
        if (start < text.length) result += SentenceRange(start, text.length, text.substring(start))
        return result
    }

    fun findAt(sentences: List<SentenceRange>, offset: Int): SentenceRange? {
        if (sentences.isEmpty()) return null
        val safe = offset.coerceIn(0, Int.MAX_VALUE)
        return sentences.firstOrNull { safe >= it.start && safe < it.end }
            ?: sentences.lastOrNull { safe == it.end }
            ?: sentences.firstOrNull { safe < it.end }
    }
}
