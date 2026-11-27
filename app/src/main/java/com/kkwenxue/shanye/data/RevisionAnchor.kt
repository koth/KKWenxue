package com.kkwenxue.shanye.data

/** A short verbatim UTF-16 context; migration never guesses between multiple matches. */
data class RevisionAnchor(
    val chapterId: String,
    val title: String,
    val revision: String?,
    val offset: Int,
    val context: String,
    val withinContext: Int
) {
    fun locate(book: Book, rangeLength: Int = 0): ReadingPosition? {
        val same = book.chapters.indexOfFirst { it.id == chapterId }
        if (same >= 0 && book.catalogBookId != null && book.chapters[same].body.isBlank()) {
            return ReadingPosition(same, offset.coerceAtLeast(0))
        }
        val chapterRevision = book.chapters.getOrNull(same)?.takeIf { it.body.isNotBlank() }?.let { "chapter:" + syncSha256(it.body) }
        if (same >= 0 && (revision == chapterRevision || revision == book.activeRevision || chapterId.contains(":chapter:sha256:"))) {
            return ReadingPosition(same, offset).takeIf { validOffset(book.chapters[same].text, offset, rangeLength) }
        }
        if (context.length < 8 || withinContext !in 0..context.length) return null
        val index = if (same >= 0) same else book.chapters.indices.singleOrNull { book.chapters[it].title == title } ?: return null
        val text = book.chapters[index].text
        val at = text.indexOf(context)
        if (at < 0 || text.indexOf(context, at + 1) >= 0) return null
        val migrated = at + withinContext
        return ReadingPosition(index, migrated).takeIf { validOffset(text, migrated, rangeLength) }
    }

    companion object {
        fun capture(book: Book, chapter: Int, start: Int, end: Int = start): RevisionAnchor {
            val source = book.chapters[chapter]
            if(book.catalogBookId!=null && source.body.isBlank())return RevisionAnchor(source.id,source.title,book.activeRevision,start.coerceAtLeast(0),"",0)
            return fromText(source.id, source.title, if (book.catalogBookId != null && source.body.isNotBlank()) "chapter:" + syncSha256(source.body) else book.activeRevision, source.text, start, end)
        }

        internal fun fromText(chapterId: String, title: String, revision: String?, text: String,
            start: Int, end: Int = start, baseOffset: Int = 0): RevisionAnchor {
            val at = start.coerceIn(0, text.length)
            var lo = (at - 24).coerceAtLeast(0)
            var hi = (end.coerceIn(at, text.length) + 24).coerceAtMost(text.length)
            if (lo > 0 && Character.isLowSurrogate(text[lo])) lo--
            if (hi < text.length && Character.isLowSurrogate(text[hi])) hi++
            return RevisionAnchor(chapterId, title, revision, baseOffset + at, text.substring(lo, hi), at - lo)
        }

        private fun validOffset(text: String, offset: Int, length: Int): Boolean =
            offset in 0..text.length && length in 0..(text.length - offset)
    }
}
