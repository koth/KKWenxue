package com.kkwenxue.shanye.tts

import android.content.Context
import java.util.UUID

/**
 * Durable record for the one foreground local-TTS request.
 *
 * The service's live StateFlow disappears when Android recreates the process.
 * Segment files remain the source of truth for completed work, while this
 * record lets the next activity hand the same request back to the service.
 */
internal data class LocalTtsPendingJob(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val voiceId: String,
    val startOffset: Int
)

internal object LocalTtsJobStore {
    private const val PREFS = "shanye_local_tts"
    private const val JOB_ID = "job_id"
    private const val BOOK_ID = "book_id"
    private const val CHAPTER_INDEX = "chapter_index"
    private const val VOICE_ID = "voice_id"
    private const val START_OFFSET = "start_offset"
    private const val ERROR = "error"

    fun save(context: Context, job: LocalTtsPendingJob) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(JOB_ID, job.id)
            .putString(BOOK_ID, job.bookId)
            .putInt(CHAPTER_INDEX, job.chapterIndex)
            .putString(VOICE_ID, job.voiceId)
            .putInt(START_OFFSET, job.startOffset.coerceAtLeast(0))
            .remove(ERROR)
            .commit()
    }

    fun load(context: Context): LocalTtsPendingJob? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bookId = prefs.getString(BOOK_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val chapterIndex = prefs.getInt(CHAPTER_INDEX, -1)
        if (chapterIndex < 0) return null
        return LocalTtsPendingJob(
            prefs.getString(JOB_ID, null) ?: UUID.randomUUID().toString(),
            bookId,
            chapterIndex,
            prefs.getString(VOICE_ID, null) ?: LocalTtsRuntime.DEFAULT_VOICE_ID,
            prefs.getInt(START_OFFSET, 0).coerceAtLeast(0)
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    fun clearIfIdMatches(context: Context, id: String) {
        if (load(context)?.id == id) clear(context)
    }

    fun saveError(context: Context, error: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ERROR, error)
            .commit()
    }

    fun error(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ERROR, null)
}
