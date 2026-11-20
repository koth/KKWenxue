package com.kkwenxue.shanye.data

import org.json.JSONObject
import org.json.JSONArray
import java.io.InterruptedIOException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Public catalog client. The prototype intentionally uses HTTP: the relay has no public CA certificate yet. */
data class CatalogBook(
    val id: Long,
    val title: String,
    val author: String,
    val category: String,
    val status: String,
    val words: Long,
    val chapterNo: Long,
    val lastUpdate: String,
    val latestChapter: String,
    val latestChapterId: Long = 0
) {
    val wordsLabel: String get() = when {
        words >= 10_000_000 -> "${words / 10_000_000.0}千万字".trimEnd('0').trimEnd('.')
        words >= 10_000 -> "${words / 10_000.0}万字".trimEnd('0').trimEnd('.')
        else -> "${words}字"
    }
}

data class CatalogContentTask(
    val taskId: String,
    val state: String,
    val message: String? = null,
    val downloadUrl: String? = null,
    val kind: String? = null,
    val plan: JSONObject? = null,
    val sha256: String? = null,
    val bytes: Long? = null,
    val expiresAt: Long = 0,
    val phase: String? = null
) {
    val done get() = state == "completed" && downloadUrl != null
    internal val failureMessage: String?
        get() = when (state) {
            "expired" -> "正文任务已过期，请重新导入"
            "cancelled" -> "正文导入任务已取消，请重新导入"
            "failed" -> when (kind) {
                "content.fetch" -> if (message?.contains("connection reset", ignoreCase = true) == true) {
                    "服务器下载书源正文时连接中断，请稍后重试"
                } else "服务器未能从书源下载正文，请稍后重试"
                "content.update" -> message ?: "服务器未能生成正文更新包，请稍后重试"
                "content.serve" -> "服务器未能打包正文，请稍后重试"
                else -> "服务器未能准备正文，请稍后重试"
            }
            else -> null
        }
}

data class OnlineUpdatePlan(
    val bookId: Long,
    val fromRevision: String,
    val toRevision: String,
    val remoteRevision: String,
    val appendOnly: Boolean,
    val added: List<String>,
    val modified: List<String>,
    val deleted: List<String>,
    val reordered: Boolean,
    val bytes: Long,
    val expiresAt: Long
) {
    companion object {
        internal fun parse(task: CatalogContentTask, book: Book, version: CatalogVersion): OnlineUpdatePlan {
            val json = task.plan ?: error("目录服务未返回更新计划")
            fun titles(key: String): List<String> = json.getJSONArray(key).let { values ->
                require(values.length() <= 2000) { "更新计划章节过多" }
                List(values.length()) { values.getString(it) }
            }
            val plan = OnlineUpdatePlan(json.getLong("bookId"), json.getString("fromRevision"),
                json.getString("toRevision"), json.getString("remoteRevision"), json.getBoolean("appendOnly"),
                titles("addedTitles"), titles("modifiedTitles"), titles("deletedTitles"), json.getBoolean("reordered"),
                task.bytes ?: 0L, task.expiresAt)
            require(plan.bookId == book.remoteBookId && plan.bookId == version.bookId &&
                plan.fromRevision == book.activeRevision && plan.remoteRevision == version.remoteRevision &&
                plan.toRevision.matches(Regex("[a-f0-9]{64}")) &&
                plan.bytes in 1L..CatalogClient.MAX_PACKAGE.toLong() &&
                plan.expiresAt > System.currentTimeMillis() / 1000 &&
                (!plan.appendOnly || (plan.modified.isEmpty() && plan.deleted.isEmpty() && !plan.reordered))) {
                "更新计划与本地版本不匹配或已过期，请重新检查"
            }
            return plan
        }
    }
}

class CatalogClient(private val baseUrl: String = BASE_URL) {
    private var lastQuery: String? = null
    private var lastResults: List<CatalogBook> = emptyList()

    fun search(query: String, limit: Int = 20): List<CatalogBook> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        if (q == lastQuery) return lastResults
        val encoded = URLEncoder.encode(q, "UTF-8")
        val value = request("/v1/catalog/search?q=$encoded&limit=${limit.coerceIn(1, 200)}")
        val items = value.optJSONArray("items") ?: org.json.JSONArray()
        val result = buildList { for (i in 0 until items.length()) add(parseBook(items.getJSONObject(i))) }
        lastQuery = q; lastResults = result
        return result
    }

    fun book(id: Long): CatalogBook = parseBook(request("/v1/catalog/books/$id"))

    fun version(id: Long): CatalogVersion {
        val item = request("/v1/catalog/books/$id/version")
        return CatalogVersion(
            bookId = item.optLong("bookId", id),
            latestChapterId = item.optLong("latestChapterId"),
            chapterCount = item.optLong("chapterCount"),
            remoteRevision = item.optString("remoteRevision"),
            lastUpdate = item.optString("lastUpdateAt"),
            latestChapter = item.optString("latestChapter")
        )
    }

    fun startUpdate(book: Book, target: CatalogVersion? = null): CatalogContentTask {
        val chapters = JSONArray()
        book.chapters.forEachIndexed { index, chapter ->
            chapters.put(JSONObject()
                .put("chapterId", chapter.id)
                .put("index", index)
                .put("title", chapter.title)
                .put("bodySha256", syncSha256(BookImporter.normalizeChapterBody(chapter.body))))
        }
        val body = JSONObject()
            .put("fromRevision", book.activeRevision ?: "")
            .put("targetRevision", target?.remoteRevision ?: book.remoteLatestVersion ?: "")
            .put("chapters", chapters)
        return parseTask(request("/v1/catalog/books/${book.remoteBookId}/update-plan", "POST", body))
    }

    fun startContent(id: Long): CatalogContentTask {
        val value = request("/v1/catalog/books/$id/content", "POST")
        val task = parseTask(value)
        // Older catalog servers omit the failure details in POST responses.
        return if (task.state == "failed" && task.kind == null) status(task.taskId) else task
    }

    fun status(taskId: String): CatalogContentTask = parseTask(request("/v1/catalog/content/$taskId"))

    /** Returns false while the relay is preparing the ZIP (HTTP 202). */
    fun download(task: CatalogContentTask, target: File, onBytes: (Long) -> Unit = {}): Boolean {
        require(task.done) { "正文尚未打包" }
        if (task.kind == "content.update") {
            require(task.expiresAt > System.currentTimeMillis() / 1000) { "更新任务已过期，请重新检查" }
            require(task.sha256?.matches(Regex("[a-fA-F0-9]{64}")) == true &&
                task.bytes != null && task.bytes in 1L..MAX_PACKAGE.toLong()) { "目录服务未返回有效的更新包校验信息" }
        }
        val url = URL("${baseUrl.trimEnd('/')}/v1/catalog/content/${task.taskId}/download")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/zip")
        }
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_ACCEPTED) return false
            if (status != HttpURLConnection.HTTP_OK) throw IOException(errorMessage(connection, status, "正文下载"))
            val length = connection.contentLengthLong
            require(length <= MAX_PACKAGE) { "正文包过大，无法导入" }
            var total = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("下载已取消")
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_PACKAGE) { "正文包过大，无法导入" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onBytes(total)
                    }
                }
            }
            if (total == 0L || (length >= 0 && total != length)) throw IOException("正文包下载不完整")
            if (task.kind == "content.update") require(total == task.bytes) { "正文更新包大小不匹配" }
            task.sha256?.let { expected ->
                require(digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)) {
                    "正文更新包整体校验失败，请重试"
                }
            }
            return true
        } finally { connection.disconnect() }
    }

    private fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val url = baseUrl.trimEnd('/') + path
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15_000; readTimeout = 180_000
            setRequestProperty("Accept", "application/json")
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                val bytes = (body?.toString() ?: "").toByteArray(Charsets.UTF_8)
                setRequestProperty("Content-Length", bytes.size.toString())
                outputStream.use { it.write(bytes) }
            }
        }
        return try {
            val c = connection
            val status = c.responseCode
            if (status !in 200..299) error(errorMessage(c, status, "目录服务"))
            c.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
    }

    private fun errorMessage(connection: HttpURLConnection, status: Int, stage: String): String {
        val detail = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val buffer = CharArray(240)
            val read = reader.read(buffer)
            if (read > 0) String(buffer, 0, read).trim() else ""
        }.orEmpty()
        val safeDetail = if (detail.startsWith("{")) {
            runCatching { JSONObject(detail).optString("error") }.getOrDefault("")
        } else detail
        return "$stage HTTP $status" + if (safeDetail.isBlank() || safeDetail.startsWith("<")) "" else "：$safeDetail"
    }

    companion object {
        const val BASE_URL = "http://120.48.49.190"
        const val MAX_PACKAGE = 64 * 1024 * 1024
        fun parseBook(item: JSONObject) = CatalogBook(
            id = item.optLong("id"), title = item.optString("title"), author = item.optString("author", "未知作者"),
            category = item.optString("category", "未分类"), status = item.optString("status", ""),
            words = item.optLong("words"), chapterNo = item.optLong("chapter_no"),
            lastUpdate = item.optString("last_update_at"), latestChapter = item.optString("latest_chapter"),
            latestChapterId = item.optLong("latest_chapter_id")
        )
        fun parseTask(item: JSONObject) = CatalogContentTask(
            taskId = item.optString("taskId", item.optString("id")).also {
                require(it.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "目录服务未返回有效任务编号" }
            },
            state = item.optString("state", "queued"), message = item.opt("error") as? String,
            downloadUrl = item.opt("downloadUrl") as? String,
            kind = item.opt("kind") as? String,
            plan = item.optJSONObject("plan"),
            sha256 = item.optString("sha256").takeIf { it.isNotBlank() },
            bytes = item.optLong("bytes").takeIf { it > 0L },
            expiresAt = item.optLong("expiresAt"),
            phase = item.opt("phase") as? String
        )
    }
}

/** Validates the relay ZIP before passing server-split TXT to the reader. */
object ContentPackageImporter {
    fun import(packageFile: File, source: CatalogBook): Book = ZipFile(packageFile).use { zip ->
        fun read(name: String, limit: Int): ByteArray {
            val entry = zip.getEntry(name) ?: error("正文包缺少 $name")
            require(!entry.isDirectory) { "正文包内文件无效" }
            return zip.getInputStream(entry).use { BookImporter.readLimited(it, limit) }
        }
        val manifest = JSONObject(read("manifest.json", 32 * 1024).toString(Charsets.UTF_8))
        require(manifest.getLong("bookId") == source.id) { "正文包与所选书籍不匹配" }
        val text = read("book.txt", CatalogClient.MAX_PACKAGE)
        val digest = MessageDigest.getInstance("SHA-256").digest(text).joinToString("") { "%02x".format(it) }
        require(digest.equals(manifest.getString("sha256"), ignoreCase = true)) { "正文校验失败，请重新下载" }
        val book = BookImporter.parseCatalogText(source, text)
        require(book.chapters.size == manifest.getInt("chapters")) { "正文包的章节数量不匹配" }
        val revision = manifest.optString("version").takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: digest
        require(revision == digest) { "正文版本与校验值不匹配" }
        book.copy(activeRevision = revision,
            remoteLatestVersion = (manifest.opt("catalogRevision") as? String)?.takeIf { it.startsWith("catalog-v1:${source.id}:") },
            remoteLatestChapterId = manifest.optLong("latestChapterId", source.latestChapterId))
    }

    fun importUpdate(packageFile: File, current: Book, plan: OnlineUpdatePlan): Book = ZipFile(packageFile).use { zip ->
        val manifest = JSONObject(read(zip, "manifest.json", 2 * 1024 * 1024).toString(Charsets.UTF_8))
        require(manifest.getInt("schema_version") == 2 && manifest.getString("kind") == "content.update") { "不是在线书籍更新包" }
        require(manifest.getLong("bookId") == current.remoteBookId && plan.bookId == current.remoteBookId) { "更新包与本地书籍不匹配" }
        require(manifest.getString("fromRevision") == current.activeRevision && plan.fromRevision == current.activeRevision &&
            manifest.getString("toRevision") == plan.toRevision && manifest.getString("remoteRevision") == plan.remoteRevision &&
            manifest.getBoolean("appendOnly") == plan.appendOnly && plan.expiresAt > System.currentTimeMillis() / 1000) {
            "更新包版本已变化或过期，请重新检查更新"
        }
        val oldChapters = current.chapters.associateBy { it.id }
        val entries = manifest.getJSONArray("chapters")
        require(entries.length() in 1..2000) { "更新包章节数量无效" }
        val targetIds = mutableSetOf<String>()
        val occurrences = mutableMapOf<String, Int>()
        val ordered = ArrayList<Chapter>(entries.length())
        var chars = 0
        for (i in 0 until entries.length()) {
            val item = entries.getJSONObject(i)
            val id = item.getString("chapterId")
            val title = item.getString("title")
            val expectedHash = item.getString("bodySha256")
            require(item.getInt("index") == i && targetIds.add(id)) { "更新包章节顺序校验失败" }
            val identity = syncSha256("$title\n$expectedHash")
            val occurrence = (occurrences[identity] ?: 0) + 1
            occurrences[identity] = occurrence
            require(id == "online:catalog:${plan.bookId}:chapter:sha256:$identity:$occurrence") { "更新包章节身份无效" }
            val bodyEntry = item.opt("bodyEntry") as? String
            val body = if (bodyEntry != null) {
                BookImporter.normalizeChapterBody(read(zip, bodyEntry, MAX_UPDATE_CHAPTER).toString(Charsets.UTF_8))
            } else {
                oldChapters[id]?.body ?: error("更新包缺少章节正文")
            }
            require(body.isNotBlank() && syncSha256(BookImporter.normalizeChapterBody(body)) == expectedHash) { "更新章节校验失败" }
            chars += title.length + body.length
            require(chars <= 12_000_000) { "更新后正文超过本地容量限制" }
            ordered += Chapter(id, title, body)
        }
        val deletes = manifest.getJSONArray("deletedChapterIds")
        require(List(deletes.length()) { deletes.getString(it) }.toSet() == oldChapters.keys - targetIds) { "更新包删除列表不匹配" }
        require(!plan.appendOnly || ordered.take(current.chapters.size) == current.chapters) {
            "旧章节发生变化，不能自动应用更新"
        }
        current.copy(chapters = ordered, activeRevision = plan.toRevision,
            remoteLatestVersion = plan.remoteRevision,
            remoteLatestChapterId = manifest.optLong("latestChapterId", current.remoteLatestChapterId),
            subtitle = "${ordered.size} 章 · 在线同步")
    }

    private fun read(zip: ZipFile, name: String, limit: Int): ByteArray {
        require(name.matches(Regex("[A-Za-z0-9_./-]{1,240}")) && !name.contains("..")) { "正文包文件名无效" }
        val entry = zip.getEntry(name) ?: error("正文包缺少 $name")
        require(!entry.isDirectory) { "正文包内文件无效" }
        return zip.getInputStream(entry).use { BookImporter.readLimited(it, limit) }
    }

    private const val MAX_UPDATE_CHAPTER = 1 * 1024 * 1024
}

internal fun syncSha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

data class CatalogVersion(
    val bookId: Long,
    val latestChapterId: Long,
    val chapterCount: Long,
    val remoteRevision: String,
    val lastUpdate: String,
    val latestChapter: String
)
