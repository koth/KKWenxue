package com.kkwenxue.shanye.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class PendingSynthesis(val key: String, val title: String, val error: String?)
data class SavedSynthesis(val task: RelayTask, val importedAt: Long, val error: String?)

private class SynthesisDatabase(context: Context): SQLiteOpenHelper(
    context, File(context.noBackupFilesDir, "synthesis.sqlite").absolutePath, null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE outbox (key TEXT PRIMARY KEY, endpoint TEXT NOT NULL, title TEXT NOT NULL, request TEXT NOT NULL, created INTEGER NOT NULL, error TEXT)")
        db.execSQL("CREATE TABLE tasks (endpoint TEXT NOT NULL, id TEXT NOT NULL, json TEXT NOT NULL, imported_at INTEGER NOT NULL DEFAULT 0, error TEXT, PRIMARY KEY(endpoint,id))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

/** A durable outbox makes an uncertain POST safe to retry with the original dedupe key. */
class SynthesisRepository(context: Context) {
    private val context = context.applicationContext
    val settings = RelaySettings(this.context)
    private fun <T> database(block: (SQLiteDatabase) -> T): T = SynthesisDatabase(context).use { block(it.writableDatabase) }
    fun pending(endpoint: String): List<PendingSynthesis> = database { db ->
        db.rawQuery("SELECT key,title,error FROM outbox WHERE endpoint=? ORDER BY created DESC", arrayOf(endpoint)).use { rows ->
            buildList { while (rows.moveToNext()) add(PendingSynthesis(rows.getString(0), rows.getString(1), rows.getString(2))) }
        }
    }
    fun tasks(endpoint: String): List<SavedSynthesis> = database { db ->
        db.rawQuery("SELECT json,imported_at,error FROM tasks WHERE endpoint=? ORDER BY rowid DESC", arrayOf(endpoint)).use { rows ->
            buildList { while (rows.moveToNext()) add(SavedSynthesis(RelayTask.parse(JSONObject(rows.getString(0))), rows.getLong(1), rows.getString(2))) }
        }.sortedByDescending { it.task.createdAt }
    }
    fun remember(endpoint: String, task: RelayTask) = database { db -> remember(db, endpoint, task) }
    private fun remember(db: SQLiteDatabase, endpoint: String, task: RelayTask) {
        db.insertWithOnConflict("tasks", null, ContentValues().apply {
            put("endpoint", endpoint); put("id", task.id); put("json", task.json)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        db.update("tasks", ContentValues().apply { put("json", task.json) }, "endpoint=? AND id=?", arrayOf(endpoint, task.id))
    }
    fun prepare(connection: RelayConnection, book: Book, indices: List<Int>, voiceId: String, voiceName: String, speed: Float = 1f): String {
        val endpoint = connection.validated().endpoint
        val key = UUID.randomUUID().toString()
        val request = SynthesisPayload.create(book, indices, voiceId, voiceName, key, speed)
        val encoded = request.toString()
        require(encoded.toByteArray().size <= 16 * 1024 * 1024) { "章节内容过大，请减少本批章数" }
        database { db ->
            check(db.insertOrThrow("outbox", null, ContentValues().apply {
                put("key", key); put("endpoint", endpoint); put("title", request.getJSONObject("metadata").getString("title"))
                put("request", encoded); put("created", System.currentTimeMillis())
            }) != -1L)
        }
        return key
    }
    fun publish(connection: RelayConnection, key: String): RelayTask {
        val client = SynthesisRelay(connection)
        val request = database { db ->
            db.rawQuery("SELECT request FROM outbox WHERE key=? AND endpoint=?", arrayOf(key, client.connection.endpoint)).use {
                require(it.moveToFirst()) { "找不到待派发任务，请切回原服务器" }
                JSONObject(it.getString(0))
            }
        }
        try {
            val task = client.submit(request)
            database { db ->
                db.beginTransaction()
                try {
                    remember(db, client.connection.endpoint, task)
                    db.delete("outbox", "key=?", arrayOf(key))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            return task
        } catch (error: Exception) {
            database { db -> db.update("outbox", ContentValues().apply { put("error", error.message ?: "派发状态待确认") }, "key=?", arrayOf(key)) }
            throw error
        }
    }
    fun refresh(connection: RelayConnection, before: Long? = null): Long? {
        val client = SynthesisRelay(connection)
        val (items, cursor) = client.list(before)
        database { db -> items.forEach { remember(db, client.connection.endpoint, it) } }
        if (before == null) {
            val visibleIds = items.map { it.id }.toSet()
            tasks(client.connection.endpoint).filter { it.task.id !in visibleIds &&
                (it.task.state in setOf("queued", "running") || it.task.result?.relayState in setOf("queued", "uploading")) }.take(20).forEach {
                remember(client.connection.endpoint, RelayTask.parse(client.status(it.task.id)))
            }
        }
        return cursor
    }
    fun lookup(connection: RelayConnection, id: String): RelayTask {
        val client = SynthesisRelay(connection)
        val raw = client.detail(id.trim())
        require(raw.getString("kind") == "tts") { "这不是语音合成任务" }
        val task = RelayTask.parse(raw)
        remember(client.connection.endpoint, task)
        return task
    }
    fun importTask(
        connection: RelayConnection, id: String, library: AudioLibrary, books: List<Book>,
        progress: (String, Long, Long) -> Unit = { _, _, _ -> }
    ): Int {
        val client = SynthesisRelay(connection)
        val raw = client.detail(id)
        require(raw.getString("kind") == "tts" && raw.getString("state") == "completed") { "任务尚未完成，暂时不能导入" }
        val task = RelayTask.parse(raw)
        remember(client.connection.endpoint, task)
        val payload = raw.getJSONObject("payload")
        val request = payload.optJSONObject("request") ?: payload
        val book = books.firstOrNull { it.id == request.getString("book_id") } ?: error("本机没有这本书，请先导入相同的小说文本")
        val chapterIds = request.getJSONArray("chapter_ids")
        require((0 until chapterIds.length()).all { n -> book.chapters.any { it.id == chapterIds.getString(n) } }) { "本机缺少对应章节，请检查小说版本" }
        val result = task.result ?: error("任务结果已清理，请重新派发合成")
        require(result.bytes in 1..SynthesisRelay.MAX_PACKAGE) { "听书包过大，请在服务器分批打包（最多 4GB）" }
        require(context.cacheDir.usableSpace > result.bytes * 3 + 64L * 1024 * 1024) { "本机空间不足，请清理空间后重试" }
        val archive = File.createTempFile("relay-audio-", ".zip", context.cacheDir)
        try {
            var downloaded = false
            if (result.direct != null) {
                progress("尝试局域网直连", 0, result.bytes)
                try {
                    client.downloadDirect(result, archive) { received, total -> progress("局域网下载中", received, total) }
                    downloaded = true
                } catch (_: DirectUnavailable) {
                    // Only unreachable/expired transport triggers relay; a hash failure must remain an error.
                    progress("局域网不可达，申请中转", 0, result.bytes)
                }
            }
            if (!downloaded) {
                var ready = task
                if (result.relayState != "ready") {
                    ready = RelayTask.parse(client.requestTransfer(id))
                    val deadline = android.os.SystemClock.elapsedRealtime() + 10 * 60 * 1000
                    while (ready.result?.relayState != "ready") {
                        remember(client.connection.endpoint, ready)
                        val current = ready.result ?: error("结果已清理，请重新派发合成")
                        require(current.relayState != "failed") { current.relayError ?: "中转上传失败，请重试" }
                        require(current.expiresAt > System.currentTimeMillis() / 1000) { "本地结果已过期，请重新派发合成" }
                        check(android.os.SystemClock.elapsedRealtime() < deadline) { "中转仍在上传，可稍后从服务器任务重试导入" }
                        progress(if(current.relayState == "uploading") "合成服务器正在上传中转包" else "等待合成服务器上传中转包", 0, current.bytes)
                        Thread.sleep(1000)
                        ready = RelayTask.parse(client.status(id))
                    }
                    remember(client.connection.endpoint, ready)
                }
                val remote = ready.result ?: error("中转结果不可用")
                require(remote.sha256.equals(result.sha256, true) && remote.bytes == result.bytes) { "任务结果发生变化，请刷新重试" }
                progress("中转下载中", 0, remote.bytes)
                client.download(id, remote, archive) { received, total -> progress("中转下载中", received, total) }
            }
            progress("校验并导入", result.bytes, result.bytes)
            val count = library.importBundle(archive.inputStream(), books)
            database { db -> db.update("tasks", ContentValues().apply {
                put("imported_at", System.currentTimeMillis()); putNull("error")
            }, "endpoint=? AND id=?", arrayOf(client.connection.endpoint, id)) }
            return count
        } catch (error: Exception) {
            database { db -> db.update("tasks", ContentValues().apply { put("error", error.message ?: "导入失败") }, "endpoint=? AND id=?", arrayOf(client.connection.endpoint, id)) }
            throw error
        } finally { archive.delete() }
    }
}
