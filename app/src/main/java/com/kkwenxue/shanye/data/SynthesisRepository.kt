package com.kkwenxue.shanye.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PendingSynthesis(val key: String, val title: String, val error: String?)
data class SavedSynthesis(val task: RelayTask, val importedAt: Long, val error: String?, val delivery: SynthesisDelivery? = null)
data class SynthesisDelivery(val key: String, val endpoint: String, val taskId: String?, val state: String,
    val phase: String, val received: Long, val total: Long, val failures: Int, val nextAt: Long, val error: String?)

private class SynthesisDatabase(context: Context): SQLiteOpenHelper(
    context, File(context.noBackupFilesDir, "synthesis.sqlite").absolutePath, null, 2
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE outbox (key TEXT PRIMARY KEY, endpoint TEXT NOT NULL, title TEXT NOT NULL, request TEXT NOT NULL, created INTEGER NOT NULL, error TEXT)")
        db.execSQL("CREATE TABLE tasks (endpoint TEXT NOT NULL, id TEXT NOT NULL, json TEXT NOT NULL, imported_at INTEGER NOT NULL DEFAULT 0, error TEXT, PRIMARY KEY(endpoint,id))")
        createDeliveries(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createDeliveries(db)
            db.execSQL("INSERT INTO deliveries(key,endpoint,state,phase) SELECT key,endpoint,'waiting','等待确认派发' FROM outbox")
        }
    }
    private fun createDeliveries(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE deliveries (key TEXT PRIMARY KEY, endpoint TEXT NOT NULL, task_id TEXT, state TEXT NOT NULL DEFAULT 'waiting', phase TEXT NOT NULL DEFAULT '等待合成', received INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT 0, failures INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL DEFAULT 0, error TEXT, UNIQUE(endpoint,task_id))")
    }
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
        }.map { it.copy(delivery = deliveryForTask(db, endpoint, it.task.id)) }.sortedByDescending { it.task.createdAt }
    }
    fun deliveries(): List<SynthesisDelivery> = database { db ->
        readDeliveries(db, "SELECT * FROM deliveries WHERE state IN ('waiting','running','retry')", null)
    }
    fun delivery(key: String): SynthesisDelivery? = database { db -> readDeliveries(db, "SELECT * FROM deliveries WHERE key=?", arrayOf(key)).firstOrNull() }
    fun resumeCredentials(endpoint: String) {
        database { db -> db.execSQL("UPDATE deliveries SET state='waiting',phase='等待恢复下载',error=NULL,failures=0,next_at=0 WHERE endpoint=? AND state='blocked' AND error LIKE '%凭据%'",arrayOf(endpoint)) }
        changed()
    }
    private fun deliveryForTask(db: SQLiteDatabase, endpoint: String, id: String) =
        readDeliveries(db, "SELECT * FROM deliveries WHERE endpoint=? AND task_id=?", arrayOf(endpoint,id)).firstOrNull()
    private fun readDeliveries(db: SQLiteDatabase, sql: String, args: Array<String>?): List<SynthesisDelivery> = db.rawQuery(sql,args).use { rows ->
        buildList { while (rows.moveToNext()) add(SynthesisDelivery(rows.getString(0),rows.getString(1),rows.getString(2),rows.getString(3),
            rows.getString(4),rows.getLong(5),rows.getLong(6),rows.getInt(7),rows.getLong(8),rows.getString(9))) }
    }
    fun requestImport(connection: RelayConnection, id: String): String = database { db ->
        val endpoint = connection.validated().endpoint
        SynthesisRelay.requireId(id)
        val existing = deliveryForTask(db,endpoint,id)
        val key = existing?.key ?: UUID.randomUUID().toString()
        if (existing == null) db.insertOrThrow("deliveries",null,ContentValues().apply {
            put("key",key);put("endpoint",endpoint);put("task_id",id)
        }) else if (existing.state !in setOf("waiting","running","retry")) {
            db.update("deliveries",ContentValues().apply {
                put("state","waiting");put("phase","等待自动导入");put("failures",0);put("next_at",0);putNull("error")
            },"key=?",arrayOf(key))
        }
        changed();key
    }
    fun deliveryState(key: String, state: String, phase: String, error: String? = null,
        received: Long? = null, total: Long? = null, failures: Int? = null, nextAt: Long = 0) {
        database { db -> db.update("deliveries",ContentValues().apply {
            put("state",state);put("phase",phase);put("error",error);put("next_at",nextAt)
            received?.let {put("received",it)};total?.let {put("total",it)};failures?.let {put("failures",it)}
        },"key=?",arrayOf(key)) }
        changed()
    }
    fun remember(endpoint: String, task: RelayTask) = database { db -> remember(db, endpoint, task) }
    private fun remember(db: SQLiteDatabase, endpoint: String, task: RelayTask) {
        db.insertWithOnConflict("tasks", null, ContentValues().apply {
            put("endpoint", endpoint); put("id", task.id); put("json", task.json)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        db.update("tasks", ContentValues().apply { put("json", task.json) }, "endpoint=? AND id=?", arrayOf(endpoint, task.id))
    }
    fun prepare(connection: RelayConnection, book: Book, indices: List<Int>, voiceId: String, voiceName: String, speed: Float = 1f): String {
        val key = UUID.randomUUID().toString()
        val request = SynthesisPayload.create(book, indices, voiceId, voiceName, key, speed)
        return saveRequest(connection, key, request)
    }
    fun prepareRetry(connection: RelayConnection, id: String): String {
        val original = SynthesisRelay(connection).detail(id)
        require(original.getString("kind") == "tts" && original.getString("state") == "failed") { "仅失败的合成任务可重试" }
        val key = UUID.randomUUID().toString()
        val payload = JSONObject(original.getJSONObject("payload").toString()).put("retry_of", id)
        val request = JSONObject().put("kind","tts").put("payload",payload)
            .put("metadata",original.optJSONObject("metadata") ?: JSONObject().put("title","重试合成任务"))
            .put("dedupe_key","android:$key").put("max_attempts",3)
        return saveRequest(connection,key,request)
    }
    private fun saveRequest(connection: RelayConnection, key: String, request: JSONObject): String {
        val endpoint = connection.validated().endpoint
        val encoded = request.toString()
        require(encoded.toByteArray().size <= 16 * 1024 * 1024) { "章节内容过大，请减少本批章数" }
        database { db ->
            db.beginTransaction()
            try {
            check(db.insertOrThrow("outbox", null, ContentValues().apply {
                put("key", key); put("endpoint", endpoint); put("title", request.getJSONObject("metadata").getString("title"))
                put("request", encoded); put("created", System.currentTimeMillis())
            }) != -1L)
            db.insertOrThrow("deliveries",null,ContentValues().apply {put("key",key);put("endpoint",endpoint);put("phase","等待确认派发")})
            db.setTransactionSuccessful()
            } finally {db.endTransaction()}
        }
        changed()
        return key
    }
    fun publish(connection: RelayConnection, key: String): RelayTask {
        val client = SynthesisRelay(connection)
        val request = database { db ->
            db.rawQuery("SELECT request FROM outbox WHERE key=? AND endpoint=?", arrayOf(key, client.connection.endpoint)).use {
                if (it.moveToFirst()) JSONObject(it.getString(0)) else null
            }
        }
        if (request == null) {
            val id = delivery(key)?.taskId ?: error("找不到待派发任务，请切回原服务器")
            return RelayTask.parse(client.status(id))
        }
        try {
            val task = client.submit(request)
            database { db ->
                db.beginTransaction()
                try {
                    remember(db, client.connection.endpoint, task)
                    db.update("deliveries",ContentValues().apply {put("task_id",task.id)},"key=?",arrayOf(key))
                    db.delete("outbox", "key=?", arrayOf(key))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            changed()
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
        waitForTransfer: Boolean = true,
        progress: (String, Long, Long) -> Unit = { _, _, _ -> }
    ): Int {
        val lockRoot=File(context.noBackupFilesDir,"synthesis-downloads").apply {mkdirs()}
        val name=AudioLibrary.sha256("${connection.validated().endpoint}:$id".toByteArray())
        java.io.RandomAccessFile(File(lockRoot,"$name.lock"),"rw").use { file ->
            val lock=try {file.channel.tryLock()} catch (_:java.nio.channels.OverlappingFileLockException) {null}
                ?: throw DeliveryPending("已有下载正在处理",10)
            lock.use {return importLocked(connection,id,library,books,File(lockRoot,name).apply {mkdirs()},waitForTransfer,progress)}
        }
    }
    private fun importLocked(connection: RelayConnection, id: String, library: AudioLibrary, books: List<Book>,
        directory: File, waitForTransfer: Boolean, progress: (String, Long, Long) -> Unit): Int {
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
        require(result.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "听书包校验信息无效" }
        require(context.cacheDir.usableSpace > result.bytes * 3 + 64L * 1024 * 1024) { "本机空间不足，请清理空间后重试" }
        val archive = File(directory,result.sha256.lowercase()+".part")
        directory.listFiles().orEmpty().filter {it != archive}.forEach {it.delete()}
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
                        if (current.relayState == "failed") throw java.io.IOException("中转上传失败：${current.relayError ?: "稍后自动重试"}")
                        require(current.expiresAt > System.currentTimeMillis() / 1000) { "本地结果已过期，请重新派发合成" }
                        check(android.os.SystemClock.elapsedRealtime() < deadline) { "中转仍在上传，可稍后从服务器任务重试导入" }
                        progress(if(current.relayState == "uploading") "合成服务器正在上传中转包" else "等待合成服务器上传中转包", 0, current.bytes)
                        if (!waitForTransfer) throw DeliveryPending(if(current.relayState == "uploading") "合成服务器正在上传中转包" else "等待合成服务器上传中转包")
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
            }, "endpoint=? AND id=?", arrayOf(client.connection.endpoint, id))
                db.update("deliveries",ContentValues().apply {
                    put("state","imported");put("phase","已自动导入");put("received",result.bytes);put("total",result.bytes);putNull("error");put("next_at",0)
                },"endpoint=? AND task_id=?",arrayOf(client.connection.endpoint,id))
            }
            archive.delete()
            changed()
            return count
        } catch (error: Exception) {
            database { db -> db.update("tasks", ContentValues().apply {
                if (error is DeliveryPending) putNull("error") else put("error", error.message ?: "导入失败")
            }, "endpoint=? AND id=?", arrayOf(client.connection.endpoint, id)) }
            throw error
        }
    }
    companion object {
        private val updates = MutableStateFlow(0L)
        val changes = updates.asStateFlow()
        private fun changed() { synchronized(updates) {updates.value += 1} }
    }
}
