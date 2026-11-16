package com.kkwenxue.shanye.data

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream

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
    val directUrl: String? = null,
    val directToken: String? = null,
    val downloadUrl: String? = null
) {
    val done get() = state == "completed" && (directUrl != null || downloadUrl != null)
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

    fun startContent(id: Long): CatalogContentTask {
        val value = request("/v1/catalog/books/$id/content", "POST")
        return parseTask(value)
    }

    fun status(taskId: String): CatalogContentTask = parseTask(request("/v1/catalog/content/$taskId"))

    /** Try the LAN capability first. If it cannot be reached, ask the catalog to start relay upload. */
    fun download(task: CatalogContentTask, target: File): File {
        val bytes = runCatching {
            if (task.directUrl.isNullOrBlank()) error("没有局域网下载地址")
            requestBytes(task.directUrl, task.directToken)
        }.getOrElse {
            val path = task.downloadUrl ?: "/v1/catalog/content/${task.taskId}/download"
            requestBytes(baseUrl.trimEnd('/') + path, null)
        }
        require(bytes.size <= MAX_PACKAGE) { "正文包过大，无法导入" }
        target.parentFile?.mkdirs(); target.writeBytes(bytes); return target
    }

    private fun request(path: String, method: String = "GET"): JSONObject {
        val url = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15_000; readTimeout = 180_000
            setRequestProperty("Accept", "application/json")
            if (method == "POST") { doOutput = true; setRequestProperty("Content-Length", "0") }
        }
        return try {
            val c = connection
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("目录服务 HTTP $status：${body.take(200)}")
            JSONObject(body)
        } finally { connection.disconnect() }
    }

    private fun requestBytes(url: String, token: String?): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 180_000
            if (!token.isNullOrBlank()) setRequestProperty("X-Result-Token", token)
        }
        return try {
            val c = connection
            val status = c.responseCode
            if (status !in 200..299) error("下载 HTTP $status")
            val output = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val read = input.read(buffer); if (read < 0) break; output.write(buffer, 0, read); require(output.size() <= MAX_PACKAGE) { "正文包过大" } }
            }
            output.toByteArray()
        } finally { connection.disconnect() }
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
        fun parseTask(item: JSONObject): CatalogContentTask {
            val direct = item.optJSONObject("direct")
            return CatalogContentTask(
                taskId = item.optString("taskId", item.optString("id")),
                state = item.optString("state", "queued"), message = item.optString("error").ifBlank { null },
                directUrl = direct?.optString("url")?.ifBlank { null }, directToken = direct?.optString("token")?.ifBlank { null },
                downloadUrl = item.optString("downloadUrl").ifBlank { null }
            )
        }
    }
}

/** Extracts the worker's ZIP and uses the existing safe TXT importer. */
object ContentPackageImporter {
    fun import(packageFile: File, fallbackTitle: String): Book {
        var text: ByteArray? = null
        ZipInputStream(packageFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name == "book.txt") {
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(32 * 1024)
                    while (true) { val n = zip.read(buffer); if (n < 0) break; output.write(buffer, 0, n); require(output.size() <= CatalogClient.MAX_PACKAGE) { "正文包过大" } }
                    text = output.toByteArray()
                }
                zip.closeEntry()
            }
        }
        return BookImporter.parse("$fallbackTitle.txt", text ?: error("正文包缺少 book.txt"))
    }
}
