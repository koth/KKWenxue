package com.kkwenxue.shanye.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class RelayConnection(val endpoint: String, val token: String) {
    fun validated(): RelayConnection {
        val uri = runCatching { URI(endpoint.trim().trimEnd('/')) }.getOrElse { error("服务器地址格式无效") }
        val host = uri.host?.lowercase() ?: error("请填写完整的服务器地址")
        require(host.isNotBlank() && uri.scheme in setOf("https", "http")) {
            "请使用完整的 HTTP 或 HTTPS 地址"
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "地址中不要包含凭据、查询参数或 #" }
        require(token.isNotBlank() && token.all { it.code in 33..126 }) { "请填写有效的接入凭据" }
        return RelayConnection(uri.toASCIIString().trimEnd('/'), token)
    }
}

/** The relay credential is encrypted with a device Keystore key and excluded from backups. */
class RelaySettings(private val context: Context) {
    private val file get() = File(context.noBackupFilesDir, "relay-connection")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("shanye-relay", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("shanye-relay", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): RelayConnection? {
        if (!file.exists()) return null
        val encoded = JSONObject(file.readText())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(encoded.getString("iv"), Base64.NO_WRAP)))
        val raw = JSONObject(String(cipher.doFinal(Base64.decode(encoded.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8))
        return RelayConnection(raw.getString("endpoint"), raw.getString("token")).validated()
    }
    fun save(connection: RelayConnection) {
        val config = connection.validated()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val raw = JSONObject().put("endpoint", config.endpoint).put("token", config.token).toString().toByteArray()
        val encoded = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(raw), Base64.NO_WRAP)).toString()
        val atomic = android.util.AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(encoded.toByteArray()); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
}

data class DirectResult(val url: String, val token: String, val expiresAt: Long)
data class RelayResult(val bytes: Long, val sha256: String, val expiresAt: Long,
    val direct: DirectResult? = null, val relayState: String = "ready", val localAvailable: Boolean = false,
    val relayError: String? = null)
class DirectUnavailable(message: String, cause: Throwable? = null): java.io.IOException(message, cause)
data class RelayTask(
    val id: String, val state: String, val title: String, val bookId: String,
    val voice: String, val createdAt: Long, val progress: JSONObject, val result: RelayResult?,
    val error: String?, val json: String
) {
    companion object {
        fun parse(raw: JSONObject): RelayTask {
            val metadata = raw.optJSONObject("metadata") ?: JSONObject()
            val result = raw.optJSONObject("result")?.let {
                val direct = it.optJSONObject("direct")?.let { d -> DirectResult(d.getString("url"), d.getString("token"), d.getLong("expires_at")) }
                RelayResult(it.getLong("bytes"), it.getString("sha256"), it.getLong("expires_at"), direct,
                    it.optString("relay_state", "ready"), it.optBoolean("local_available", direct != null),
                    it.optString("relay_error").takeUnless { e -> e.isBlank() || e == "null" })
            }
            return RelayTask(raw.getString("id"), raw.getString("state"), metadata.optString("title", "合成任务 · ${raw.getString("id").take(8)}"),
                metadata.optString("book_id"), metadata.optString("voice"), raw.optLong("created_at"),
                raw.optJSONObject("progress") ?: JSONObject(), result,
                raw.optString("error").takeUnless { it.isBlank() || it == "null" },
                JSONObject(raw.toString()).apply { remove("payload"); remove("lease_token"); optJSONObject("result")?.remove("direct") }.toString())
        }
    }
}
class RelayHttpError(val status: Int, message: String): IllegalStateException(message)

/** Blocking, bounded I/O. Call from Dispatchers.IO; never follows redirects with credentials. */
class SynthesisRelay(connection: RelayConnection) {
    val connection = connection.validated()
    private fun open(path: String, method: String = "GET"): HttpURLConnection =
        (URI(this.connection.endpoint + path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("X-Relay-Token", this@SynthesisRelay.connection.token)
            setRequestProperty("Accept", "application/json")
        }
    private fun check(response: HttpURLConnection) {
        val code = response.responseCode
        if (code !in 200..299) {
            val reason = when (code) {
                401, 403 -> "接入凭据无效，请检查服务器设置"
                404 -> "找不到任务或接口，请检查中转服务版本和地址"
                409 -> "任务内容与之前提交不一致"
                410 -> "结果已过期或被清理，请重新派发合成"
                413 -> "任务内容过大，请减少章节数量"
                in 300..399 -> "服务器返回跳转，请填写最终的 HTTPS 地址"
                else -> "服务器请求失败（$code）"
            }
            throw RelayHttpError(code, reason)
        }
    }
    private fun json(path: String, body: JSONObject? = null): JSONObject {
        val response = open(path, if (body == null) "GET" else "POST")
        try {
            if (body != null) {
                val bytes = body.toString().toByteArray()
                require(bytes.size <= 16 * 1024 * 1024) { "任务内容过大，请减少章节数量" }
                response.doOutput = true
                response.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                response.setFixedLengthStreamingMode(bytes.size)
                response.outputStream.use { it.write(bytes) }
            }
            check(response)
            return response.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 20 * 1024 * 1024) { "服务器响应过大" }
                    output.write(buffer, 0, count)
                }
                JSONObject(output.toString("UTF-8"))
            }
        } finally { response.disconnect() }
    }
    fun health() {
        require(json("/healthz").optString("service") == "shanye-task-relay") { "这个地址不是任务中转服务" }
    }
    fun submit(request: JSONObject): RelayTask = RelayTask.parse(json("/v1/tasks", request))
    fun list(before: Long? = null): Pair<List<RelayTask>, Long?> {
        val raw = json("/v1/tasks?kind=tts&limit=30" + (before?.let { "&before=$it" } ?: ""))
        val items = raw.getJSONArray("items")
        return (0 until items.length()).map { RelayTask.parse(items.getJSONObject(it)) } to
            if (raw.isNull("next_cursor")) null else raw.getLong("next_cursor")
    }
    fun detail(id: String): JSONObject { requireId(id); return json("/v1/tasks/$id") }
    fun status(id: String): JSONObject { requireId(id); return json("/v1/tasks/$id/status") }
    fun requestTransfer(id: String): JSONObject { requireId(id); return json("/v1/tasks/$id/result/request", JSONObject()) }
    fun download(id: String, expected: RelayResult, target: File, progress: (Long, Long) -> Unit = { _, _ -> }) {
        requireId(id)
        downloadFile(open("/v1/tasks/$id/result"), expected, target, progress)
    }
    fun downloadDirect(expected: RelayResult, target: File, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val direct = expected.direct ?: throw DirectUnavailable("未提供局域网地址")
        if (direct.expiresAt <= System.currentTimeMillis() / 1000) throw DirectUnavailable("局域网结果已过期")
        val response = try {
            val uri = URI(direct.url)
            require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
            require(java.net.InetAddress.getAllByName(uri.host).all { it.isSiteLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress })
            require(direct.token.isNotBlank() && direct.token.all { it.code in 33..126 })
            (uri.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 4000
                readTimeout = 8000
                // A result capability is scoped to this ZIP. Never send relay/admin credentials to LAN hosts.
                setRequestProperty("X-Result-Token", direct.token)
            }
        } catch (error: Exception) { throw DirectUnavailable("局域网地址不可用", error) }
        downloadFile(response, expected, target, progress, direct = true)
    }
    private fun downloadFile(response: HttpURLConnection, expected: RelayResult, target: File,
        progress: (Long, Long) -> Unit, direct: Boolean = false) {
        fun <T> network(block: () -> T): T = try { block() } catch (error: java.io.IOException) {
            if (!direct || (error is java.io.InterruptedIOException && error !is java.net.SocketTimeoutException)) throw error
            throw DirectUnavailable("局域网连接不可用", error)
        }
        try {
            require(expected.bytes in 1..MAX_PACKAGE && expected.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "结果大小或校验信息无效（每包最多 4GB）" }
            require(expected.expiresAt > System.currentTimeMillis() / 1000) { "结果已过期，请重新派发合成" }
            if (direct) {
                if (network { response.responseCode } !in 200..299) throw DirectUnavailable("局域网下载暂不可用")
            } else check(response)
            val length = response.contentLengthLong
            require(length < 0 || length == expected.bytes) { "服务器结果长度发生变化，请刷新任务" }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastProgress = 0L
            network { response.inputStream }.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("下载已取消")
                    val count = network { input.read(buffer) }
                    if (count < 0) break
                    received += count
                    require(received <= expected.bytes) { "听书包长度不匹配" }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastProgress >= 100) { progress(received, expected.bytes); lastProgress = now }
                }
            } }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            require(received == expected.bytes && sha.equals(expected.sha256, true)) { "听书包下载不完整或校验失败，请重试" }
            progress(received, expected.bytes)
        } catch (error: Throwable) { target.delete(); throw error }
        finally { response.disconnect() }
    }
    companion object {
        const val MAX_PACKAGE = 4L * 1024 * 1024 * 1024
        fun requireId(id: String) { require(id.matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))) { "请填写完整的服务器任务 ID" } }
    }
}

object SynthesisPayload {
    fun chapterNumber(chapter: Chapter, index: Int): Int = Regex("^第\\s*(\\d+)\\s*章").find(chapter.title)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
    fun resolve(book: Book, expression: String): List<Int> {
        require(expression.isNotBlank()) { "请输入章号，例如 984 或 984,986-988" }
        val selected = mutableSetOf<Int>()
        expression.trim().split(Regex("[,，\\s]+" )).forEach { term ->
            val match = Regex("(\\d+)(?:-(\\d+))?").matchEntire(term) ?: error("章号格式无效：$term")
            val first = match.groupValues[1].toIntOrNull() ?: error("章号过大")
            val end = match.groupValues[2]
            val last = if (end.isEmpty()) first else end.toIntOrNull() ?: error("章号过大")
            require(first >= 1 && last >= first && last - first < 1000) { "章节范围无效：$term" }
            val found = book.chapters.indices.filter { chapterNumber(book.chapters[it], it) in first..last }
            val missing = (first..last).toSet() - found.map { chapterNumber(book.chapters[it], it) }.toSet()
            require(missing.isEmpty()) { "没有找到第 ${missing.take(5).joinToString("、")} 章" }
            if (first == last) require(found.size == 1) { "存在多个第 $first 章，请使用范围明确包含同号章节" }
            selected.addAll(found)
        }
        require(selected.size in 1..100) { "每批请选择 1–100 章" }
        return selected.sorted()
    }
    fun create(book: Book, indices: List<Int>, voiceId: String, voiceName: String, key: String, speed: Float = 1f): JSONObject {
        require(indices.distinct().size == indices.size && indices.size in 1..100 && indices.all { it in book.chapters.indices }) { "章节选择无效" }
        require(voiceId.isNotBlank() && voiceId.length <= 200) { "请选择音色" }
        require(speed in setOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f)) { "成品语速必须为 0.8、0.9、1.0、1.1 或 1.2 倍" }
        val chapters = JSONArray()
        val ids = JSONArray()
        indices.sorted().forEach { index ->
            val chapter = book.chapters[index]
            val text = chapter.text
            val hash = AudioLibrary.sha256(text.toByteArray())
            val revision = AudioLibrary.sha256("${chapter.id}:$hash:reader-canonical+OpenCC-t2s-v1:reader-sentence-bounded160-v1".toByteArray())
            ids.put(chapter.id)
            chapters.put(JSONObject().put("id", chapter.id).put("book_id", book.id).put("book_title", book.title)
                .put("chapter_index", index).put("chapter_number", chapterNumber(chapter, index)).put("title", chapter.title)
                .put("canonical_text", text).put("source_sha256", hash).put("current_revision", revision).put("segments", JSONArray()))
        }
        val label = if (indices.size == 1) book.chapters[indices.first()].title else "${book.chapters[indices.first()].title} 等 ${indices.size} 章"
        return JSONObject().put("kind", "tts").put("dedupe_key", "android:$key").put("max_attempts", 3)
            .put("metadata", JSONObject().put("title", "${book.title.take(100)} · ${label.take(120)}")
                .put("book_id", book.id).put("book_title", book.title.take(100)).put("voice", voiceName.take(100)).put("chapters", indices.size))
            .put("payload", JSONObject().put("request", JSONObject().put("book_id", book.id).put("chapter_ids", ids)
                .put("voice_preset_version_id", voiceId).put("mode", "reuse").put("inline_chapters", chapters)
                .put("export", JSONObject().put("speed", speed.toDouble()).put("pause_ms", 180).put("normalize_loudness", true))))
    }
}
