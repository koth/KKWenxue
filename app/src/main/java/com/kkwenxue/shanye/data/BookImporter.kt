package com.kkwenxue.shanye.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/** Local, offline file import. EPUB documents are parsed as text, never executed in a WebView. */
object BookImporter {
    private const val MAX_BYTES = 64 * 1024 * 1024
    private const val MAX_CHARACTERS = 12_000_000
    private val heading = Regex("""^(?:第[0-9０-９一二三四五六七八九十百千万零〇两兩]{1,14}[章节節回卷部集篇](?:$|[：:\s].{0,60})|(?:序章|楔子|引子|前言|后记|後記|尾声|尾聲|番外)(?:$|[：:\s].{0,40}))$""")
    private val catalogHeading = Regex("""^(?:第[0-9０-９一二三四五六七八九十百千万零〇两兩]{1,16}[章节節回卷部集篇][^\r\n]{0,100}|(?:序章|楔子|引子|前言|后记|後記|尾声|尾聲|番外)[^\r\n]{0,80})$""")

    fun readLimited(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(count <= limit - output.size()) { "文件内容过大，最多支持 64 MB。" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun parse(fileName: String, bytes: ByteArray): Book {
        require(bytes.size <= MAX_BYTES) { "文件过大，最多支持 64 MB。" }
        val id = contentId(bytes)
        val fallbackTitle = fileName.substringBeforeLast('.').trim().ifBlank { "导入的书籍" }
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "txt" -> parseTxt(id, fallbackTitle, decode(bytes))
            "epub" -> parseEpub(id, fallbackTitle, bytes)
            else -> throw IllegalArgumentException("请选择 TXT 或 EPUB 文件。")
        }
    }

    internal fun parseCatalogText(source: CatalogBook, bytes: ByteArray): Book {
        require(bytes.size <= MAX_BYTES) { "文件过大，最多支持 64 MB。" }
        return parseTxt(contentId(bytes), source.title, decode(bytes), source)
    }

    private fun contentId(bytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }
        return "local-$hash"
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && ((bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) ||
                    (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()))) return String(bytes, Charsets.UTF_16)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: java.nio.charset.CharacterCodingException) {
            String(bytes, Charset.forName("GB18030"))
        }
    }

    private fun paragraphs(text: String) = text.replace("\uFFFC", "").lineSequence()
        .map { it.trim().trim('\uFEFF') }.filter { it.isNotBlank() }.joinToString("\n\n")

    private fun parseTxt(id: String, title: String, text: String, catalog: CatalogBook? = null): Book {
        require(text.length <= MAX_CHARACTERS) { "正文过长，请分卷导入。" }
        require(!text.contains('\u0000')) { "文件不是有效的文本。" }
        val lines = text.removePrefix("\uFEFF").lines()
        val metadataTitle = catalog?.title ?: lines.firstOrNull { it.trim().isNotBlank() }?.trim()
            ?.takeIf { !heading.matches(it) && !it.startsWith("作者") }
        val author = catalog?.author ?: lines.firstOrNull { it.trim().startsWith("作者") }
            ?.substringAfter('：', "").orEmpty().trim().ifBlank { "未知作者" }
        val chapters = mutableListOf<Chapter>()
        var chapterTitle = "正文"
        val body = StringBuilder()
        fun flush() {
            val content = paragraphs(body.toString())
            if (content.isNotBlank()) {
                val candidate = Chapter("$id-${chapters.size}", chapterTitle, content)
                val previous = chapters.lastOrNull()
                val sameTitle = previous?.title?.replace(Regex("[^\\p{L}\\p{N}]"), "") == candidate.title.replace(Regex("[^\\p{L}\\p{N}]"), "")
                if (catalog != null || previous == null || !sameTitle || previous.body != candidate.body) chapters += candidate
            }
            body.clear()
        }
        var foundHeading = false
        val chapterHeading = if (catalog == null) heading else catalogHeading
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            if (chapterHeading.matches(line)) {
                if (foundHeading) flush()
                chapterTitle = line
                foundHeading = true
                index++
                // Some web exports place an older chapter number immediately after the
                // current one. Keep the first heading and discard that duplicate label.
                if (catalog == null) {
                    while (index < lines.size && lines[index].trim().isEmpty()) index++
                    if (index < lines.size && heading.matches(lines[index].trim())) index++
                }
            } else {
                if (foundHeading) body.appendLine(line)
                index++
            }
        }
        flush()
        require(chapters.isNotEmpty()) { "文件中没有可阅读的正文。" }
        return Book(id, metadataTitle ?: title, author, catalog?.category ?: "本地", "${chapters.size} 章 · TXT",
            if (catalog == null) "从本机导入，支持离线阅读。" else "从在线书库导入，支持离线阅读。", 1, chapters)
    }

    private data class ManifestItem(val path: String, val mediaType: String)

    private fun parseEpub(id: String, fallbackTitle: String, bytes: ByteArray): Book {
        val entries = mutableMapOf<String, ByteArray>()
        var expanded = 0
        var entryCount = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entryCount <= 10_000) { "EPUB 文件条目过多。" }
                if (!entry.isDirectory) {
                    // Count all expanded entries, including images, to bound compressed archives.
                    val data = readLimited(zip, MAX_BYTES - expanded)
                    expanded += data.size
                    val name = resolvePath("", entry.name)
                    if (name.substringAfterLast('.').lowercase() in setOf("xml", "opf", "html", "xhtml", "htm")) entries[name] = data
                }
                zip.closeEntry()
            }
        }
        val container = entries["META-INF/container.xml"] ?: throw IllegalArgumentException("EPUB 缺少目录文件。")
        var packagePath: String? = null
        xml(container) { parser ->
            if (parser.name == "rootfile" && packagePath == null) packagePath = parser.getAttributeValue(null, "full-path")
        }
        val opfPath = resolvePath("", packagePath ?: throw IllegalArgumentException("EPUB 缺少书籍信息。"))
        val opf = entries[opfPath] ?: throw IllegalArgumentException("EPUB 书籍信息无法读取。")
        val base = opfPath.substringBeforeLast('/', "")
        val manifest = mutableMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()
        var title = fallbackTitle
        var author = "未知作者"
        xml(opf) { parser ->
            when (parser.name) {
                "title" -> parser.nextText().trim().takeIf { it.isNotBlank() }?.let { title = it }
                "creator" -> parser.nextText().trim().takeIf { it.isNotBlank() }?.let { author = it }
                "item" -> {
                    val itemId = parser.getAttributeValue(null, "id")
                    val href = parser.getAttributeValue(null, "href")
                    if (itemId != null && href != null) manifest[itemId] = ManifestItem(resolvePath(base, href), parser.getAttributeValue(null, "media-type") ?: "")
                }
                "itemref" -> if (parser.getAttributeValue(null, "linear") != "no") {
                    parser.getAttributeValue(null, "idref")?.let { spine += it }
                }
            }
        }
        require(spine.isNotEmpty()) { "EPUB 没有可阅读的章节顺序。" }
        var characters = 0
        val chapters = spine.mapIndexedNotNull { index, itemId ->
            val item = manifest[itemId] ?: throw IllegalArgumentException("EPUB 的章节目录不完整。")
            require(item.mediaType in setOf("application/xhtml+xml", "text/html")) { "暂不支持这个 EPUB 章节格式。" }
            val source = entries[item.path] ?: throw IllegalArgumentException("EPUB 缺少章节正文，可能已加密。")
            val html = decode(source)
            val bodyHtml = Regex("(?is)<body\\b[^>]*>(.*?)</body>").find(html)?.groupValues?.get(1) ?: html
            val cleanHtml = bodyHtml.replace(Regex("(?is)<(?:script|style)\\b[^>]*>.*?</(?:script|style)>"), "")
            val body = paragraphs(Html.fromHtml(cleanHtml, Html.FROM_HTML_MODE_LEGACY).toString())
            characters += body.length
            require(characters <= MAX_CHARACTERS) { "正文过长，请分卷导入。" }
            if (body.isBlank()) null else {
                val headingHtml = Regex("(?is)<h[1-6]\\b[^>]*>(.*?)</h[1-6]>").find(cleanHtml)?.groupValues?.get(1)
                val chapterTitle = headingHtml?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
                    ?.takeIf { it.isNotBlank() } ?: "第 ${index + 1} 节"
                val content = if (body.startsWith("$chapterTitle\n\n")) body.removePrefix("$chapterTitle\n\n") else body
                Chapter("$id-$index", chapterTitle, content.ifBlank { body })
            }
        }
        require(chapters.isNotEmpty()) { "EPUB 中没有可阅读的正文。" }
        return Book(id, title, author, "本地", "${chapters.size} 章 · EPUB", "从本机导入，支持离线阅读。", 1, chapters)
    }

    private fun xml(bytes: ByteArray, onStart: (XmlPullParser) -> Unit) {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), null)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) onStart(parser)
        }
    }

    private fun resolvePath(base: String, href: String): String {
        require(!href.contains("://")) { "EPUB 正文必须保存在文件内部。" }
        val parts = mutableListOf<String>()
        val path = Uri.decode(href.substringBefore('#')).replace('\\', '/')
        for (part in (if (base.isBlank()) path else "$base/$path").split('/')) when (part) {
            "", "." -> Unit
            ".." -> { require(parts.isNotEmpty()) { "EPUB 文件路径无效。" }; parts.removeAt(parts.lastIndex) }
            else -> parts += part
        }
        return parts.joinToString("/")
    }
}

class LocalBookRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = BookDatabase(context.applicationContext)

    fun import(uri: Uri): Book {
        val resolver = appContext.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        val bytes = resolver.openInputStream(uri)?.use { BookImporter.readLimited(it) }
            ?: throw IllegalArgumentException("无法打开文件，请重新选择。")
        return BookImporter.parse(name, bytes).also { save(it) }
    }

    fun save(book: Book) = database.save(book)

    fun load(): List<Book> = database.load()
}
