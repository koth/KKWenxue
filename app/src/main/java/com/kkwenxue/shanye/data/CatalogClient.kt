package com.kkwenxue.shanye.data

import org.json.JSONObject
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
    val downloadUrl: String? = null
) {
    val done get() = state == "completed" && downloadUrl != null
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

    /** Returns false while the relay is preparing the ZIP (HTTP 202). */
    fun download(task: CatalogContentTask, target: File): Boolean {
        require(task.done) { "正文尚未打包" }
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
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_PACKAGE) { "正文包过大，无法导入" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total == 0L || (length >= 0 && total != length)) throw IOException("正文包下载不完整")
            return true
        } finally { connection.disconnect() }
    }

    private fun request(path: String, method: String = "GET"): JSONObject {
        val url = baseUrl.trimEnd('/') + path
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15_000; readTimeout = 180_000
            setRequestProperty("Accept", "application/json")
            if (method == "POST") { doOutput = true; setRequestProperty("Content-Length", "0") }
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
        return "$stage HTTP $status" + if (detail.isBlank() || detail.startsWith("<")) "" else "：$detail"
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
        book
    }
}
