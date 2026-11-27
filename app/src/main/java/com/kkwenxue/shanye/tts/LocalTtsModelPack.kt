package com.kkwenxue.shanye.tts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.kkwenxue.shanye.data.RelayConnection

data class LocalTtsFile(val name: String, val path: String, val bytes: Long, val sha256: String)
data class LocalTtsVoice(val id: String, val speaker: String, val name: String, val codes: String, val sha256: String)
data class LocalTtsManifest(
    val revision: String,
    val engineRevision: String,
    val files: List<LocalTtsFile>,
    val voices: List<LocalTtsVoice>,
    val minimumFreeBytes: Long
)

sealed interface LocalTtsPackState {
    data object Missing : LocalTtsPackState
    data class Downloading(val file: String, val received: Long, val total: Long) : LocalTtsPackState
    data class Verifying(val revision: String) : LocalTtsPackState
    data class Ready(val manifest: LocalTtsManifest) : LocalTtsPackState
    data class Failed(val message: String) : LocalTtsPackState
}

/** Downloads one immutable pack into app-private storage and publishes it only after every hash matches. */
class LocalTtsModelPack(context: Context) {
    private val context = context.applicationContext
    private val root = File(context.noBackupFilesDir, "local-tts").apply { mkdirs() }
    private val currentFile = File(root, "current.json")
    private val state = MutableStateFlow<LocalTtsPackState>(readReady() ?: LocalTtsPackState.Missing)
    val states = state.asStateFlow()

    fun current(): LocalTtsManifest? = (state.value as? LocalTtsPackState.Ready)?.manifest
    fun rootFor(manifest: LocalTtsManifest = current() ?: error("本地模型尚未准备")): File =
        File(root, manifest.revision).apply { mkdirs() }
    fun file(manifest: LocalTtsManifest, relative: String): File {
        val target = File(rootFor(manifest), relative).canonicalFile
        require(target.toPath().startsWith(rootFor(manifest).canonicalFile.toPath())) { "模型文件路径无效" }
        return target
    }

    /** Prepare from the public model source. A configured server remains a development fallback. */
    fun prepare(connection: RelayConnection? = null): LocalTtsManifest {
        val cached = current()
        return try {
            if (cached == null) {
                prepareFromHuggingFaceMirror()
            } else {
                val repaired = ensureEmbeddedVoices(cached)
                state.value = LocalTtsPackState.Verifying(repaired.revision)
                validateFiles(repaired)
                state.value = LocalTtsPackState.Ready(repaired)
                repaired
            }
        } catch (error: Exception) {
            // A download can be killed after writing a file but before the
            // manifest is published. Remove that invalid ready marker before
            // retrying, otherwise every later launch keeps mmap-ing the same
            // truncated or corrupted GGUF file.
            if (cached != null) {
                rootFor(cached).deleteRecursively()
                currentFile.delete()
                state.value = LocalTtsPackState.Missing
            }
            if (connection != null) {
                runCatching { prepareDownloaded(connection) }.getOrElse {
                    state.value = LocalTtsPackState.Failed(it.message ?: "本地模型下载失败")
                    throw it
                }
            } else {
                runCatching { prepareFromHuggingFaceMirror() }.getOrElse {
                    state.value = LocalTtsPackState.Failed(it.message ?: error.message ?: "本地模型下载失败")
                    throw it
                }
            }
        }
    }

    private fun prepareFromHuggingFaceMirror(): LocalTtsManifest {
        val manifest = LocalTtsManifest(
            revision = "hf-mirror-zonos3-mini-2921b882",
            engineRevision = "zonos3-mini-ggml-cpu-arm64-v1",
            files = listOf(
                LocalTtsFile("backbone-ptq.gguf", "backbone-ptq.gguf", 663150624L,
                    "66fceb6d73a8c30bde80249e4112bb308380ab7f3384fa11c8e4048115e4ceec"),
                LocalTtsFile("zac-keep2.gguf", "zac-keep2.gguf", 452325440L,
                    "834e5b70b88294afc3c702a478487e0664dcc6b574c16fc6167d72444aabeafd")
            ),
            voices = emptyList(),
            minimumFreeBytes = 128L * 1024 * 1024
        )
        val destination = rootFor(manifest)
        require(destination.usableSpace >= manifest.files.sumOf { it.bytes } + manifest.minimumFreeBytes) {
            "本地空间不足，模型下载至少需要 1.1GB 可用空间"
        }
        for (entry in manifest.files) {
            downloadRemote(entry, File(destination, entry.path), remoteUrl(entry.path))
        }
        val files = manifest.files.toMutableList()
        val voices = readEmbeddedVoices().toMutableList()
        for (asset in embeddedVoiceAssets()) {
            val existing = voices.firstOrNull { it.codes.substringAfterLast('/') == asset }
            val voice = existing ?: inferredVoice(asset)
            val target = File(destination, "voices/$asset").apply { parentFile?.mkdirs() }
            copyAsset("voices/$asset", target)
            val publishedVoice = voice.copy(codes = "voices/$asset", sha256 = sha256(target))
            if (existing == null) voices += publishedVoice else voices[voices.indexOf(existing)] = publishedVoice
            files += LocalTtsFile("voice:${publishedVoice.id}", publishedVoice.codes, target.length(), publishedVoice.sha256)
        }
        require(voices.isNotEmpty()) {
            "没有可用的预编码声线（APK voices 资源：${context.assets.list("voices").orEmpty().joinToString()}）"
        }
        state.value = LocalTtsPackState.Verifying(manifest.revision)
        for (entry in files) {
            val target = File(destination, entry.path)
            require(target.isFile && target.length() == entry.bytes && sha256(target) == entry.sha256) {
                "本地模型文件校验失败：${entry.name}"
            }
        }
        return publish(manifest.copy(files = files, voices = voices))
    }

    /**
     * Repairs an already-installed model pack after an APK update. Older local
     * packs may have been published without embedded voices; do not redownload
     * the 1.1GB model just to add the small default voice code file.
     */
    private fun ensureEmbeddedVoices(manifest: LocalTtsManifest): LocalTtsManifest {
        val assets = embeddedVoiceAssets()
        if (assets.isEmpty()) return manifest

        val destination = rootFor(manifest)
        val voices = manifest.voices.toMutableList()
        val files = manifest.files.toMutableList()
        var changed = false
        val staleEmbeddedAssets = voices
            .map { it.codes.substringAfterLast('/') }
            .filter { it.endsWith(".codes.npy") && it !in assets }
        if (staleEmbeddedAssets.isNotEmpty()) {
            voices.removeAll { it.codes.substringAfterLast('/') in staleEmbeddedAssets }
            files.removeAll { it.path.substringAfterLast('/') in staleEmbeddedAssets }
            staleEmbeddedAssets.forEach { asset ->
                File(destination, "voices/$asset").takeIf { it.exists() }?.delete()
            }
            changed = true
        }
        for (asset in assets) {
            val assetPath = "voices/$asset"
            val assetBytes = context.assets.open(assetPath).use { it.readBytes() }
            val assetSha = sha256(assetBytes)
            val target = File(destination, assetPath).apply { parentFile?.mkdirs() }
            if (!target.isFile || target.length() != assetBytes.size.toLong() || sha256(target) != assetSha) {
                copyAsset(assetPath, target, overwrite = true)
            }

            val existingVoice = voices.firstOrNull { it.codes.substringAfterLast('/') == asset }
            val voice = (existingVoice ?: inferredVoice(asset)).copy(
                codes = assetPath,
                sha256 = assetSha
            )
            if (existingVoice == null) {
                voices += voice
                changed = true
            } else {
                val index = voices.indexOf(existingVoice)
                if (existingVoice != voice) {
                    voices[index] = voice
                    changed = true
                }
            }

            val file = LocalTtsFile("voice:${voice.id}", assetPath, target.length(), assetSha)
            files.removeAll { it.path == assetPath }
            files += file
            if (manifest.files.none { it.path == assetPath && it.bytes == file.bytes && it.sha256 == file.sha256 }) {
                changed = true
            }
        }
        if (!changed) return manifest

        state.value = LocalTtsPackState.Verifying(manifest.revision)
        for (entry in files) {
            val target = File(destination, entry.path)
            require(target.isFile && target.length() == entry.bytes && sha256(target) == entry.sha256) {
                "本地模型文件校验失败：${entry.name}"
            }
        }
        return publish(manifest.copy(files = files, voices = voices))
    }

    /**
     * Keep the default voice explicit as well as discovering packaged voices.
     * Some Android/Gradle asset mergers do not reliably expose a generated
     * directory through list(), although the file itself is readable.
     */
    private fun embeddedVoiceAssets(): List<String> {
        val candidates = (context.assets.list("voices").orEmpty().toList() + DEFAULT_VOICE_ASSET).distinct()
        return candidates.filter { asset ->
            asset.endsWith(".codes.npy") && runCatching {
                context.assets.open("voices/$asset").use { }
                true
            }.getOrDefault(false)
        }
    }

    private fun copyAsset(assetPath: String, destination: File, overwrite: Boolean = false) {
        if (!overwrite && destination.isFile) return
        val temporary = File(destination.parentFile, ".${destination.name}.part")
        context.assets.open(assetPath).use { input -> temporary.outputStream().use { input.copyTo(it) } }
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            error("无法替换预编码声线：$assetPath")
        }
        require(temporary.renameTo(destination)) { "保存预编码声线失败：$assetPath" }
    }

    private fun remoteUrl(path: String): String = REMOTE_BASE + java.net.URLEncoder.encode(path, "UTF-8").replace("%2F", "/")

    private fun downloadRemote(entry: LocalTtsFile, target: File, url: String) {
        target.parentFile?.mkdirs()
        var offset = target.takeIf { it.isFile }?.length() ?: 0L
        if (offset > entry.bytes) { target.delete(); offset = 0L }
        if (offset == entry.bytes && sha256(target) == entry.sha256) return
        val response = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 20000
            readTimeout = 180000
            setRequestProperty("Accept", "application/octet-stream")
            if (offset > 0) {
                setRequestProperty("Range", "bytes=$offset-")
                setRequestProperty("If-Range", "\"${entry.sha256}\"")
            }
        }
        try {
            val status = response.responseCode
            val append = offset > 0 && status == HttpURLConnection.HTTP_PARTIAL
            if (!append) {
                offset = 0L
                require(status == HttpURLConnection.HTTP_OK) { "模型源返回 ${status}：${entry.name}" }
            }
            FileOutputStream(target, append).use { output ->
                response.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var received = offset
                    state.value = LocalTtsPackState.Downloading(entry.name, received, entry.bytes)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("模型下载已取消")
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        require(received <= entry.bytes) { "模型文件大小超出清单：${entry.name}" }
                        output.write(buffer, 0, count)
                        state.value = LocalTtsPackState.Downloading(entry.name, received, entry.bytes)
                    }
                    output.fd.sync()
                }
            }
            require(target.length() == entry.bytes && sha256(target) == entry.sha256) { "模型文件校验失败：${entry.name}" }
        } finally { response.disconnect() }
    }

    private fun readEmbeddedVoices(): List<LocalTtsVoice> = runCatching {
        val value = JSONObject(context.assets.open("voices/voices.json").bufferedReader().use { it.readText() })
        value.optJSONArray("voices")?.let { array -> buildList {
            for (index in 0 until array.length()) {
                val voice = array.getJSONObject(index)
                val speaker = voice.optString("speaker")
                val id = voiceId(speaker)
                val codes = voice.optString("codes_path").substringAfterLast('/')
                // Do not publish stale registry entries whose code file is not
                // actually embedded in this APK. Otherwise the default voice
                // can resolve successfully, then fail later in native code
                // with an unhelpful empty-audio result.
                if (codes.isNotBlank() && assetExists("voices/$codes")) {
                    add(LocalTtsVoice(id, speaker, voice.optString("name", id), "voices/$codes", ""))
                }
            }
        } } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun assetExists(path: String): Boolean =
        runCatching { context.assets.open(path).use { } }.isSuccess

    private fun inferredVoice(asset: String): LocalTtsVoice {
        val stem = asset.removeSuffix(".codes.npy")
        val lower = stem.lowercase()
        val speaker = when {
            lower == "haoran" -> "zonos:haoran"
            lower.contains("linchuan") -> "clone:linchuan-zonos3-v1"
            lower == "adam" -> "clone:adam-zonos3-v1"
            lower == "story" -> "clone:story-zonos3-v1"
            lower.contains("shuoshuren1") -> "clone:upload-53677481cc5fa643ef6413ff"
            lower.contains("shuoshuren2") -> "clone:upload-8b3a4bdcb1e3db46b0f78b89"
            else -> "clone:$stem"
        }
        val name = when {
            lower == "haoran" -> "Haoran"
            lower.contains("linchuan") -> "林川"
            lower == "adam" -> "Adam"
            lower == "story" -> "Story"
            lower.contains("shuoshuren1") -> "说书人一"
            lower.contains("shuoshuren2") -> "说书人二"
            else -> stem
        }
        return LocalTtsVoice(voiceId(speaker), speaker, name, "voices/$asset", "")
    }

    private fun voiceId(speaker: String): String = when {
        speaker == "zonos:haoran" -> "zonos3-haoran-v1"
        speaker.startsWith("clone:") -> "clone-${speaker.removePrefix("clone:")}"
        else -> speaker
    }

    private fun publish(manifest: LocalTtsManifest): LocalTtsManifest {
        val destination = rootFor(manifest)
        require(destination.usableSpace >= manifest.minimumFreeBytes) { "本地空间不足，至少需要 ${manifest.minimumFreeBytes / (1024 * 1024)} MB 可用空间" }
        val published = JSONObject().put("manifest", encode(manifest)).toString()
        val temporary = File(root, "current.json.part")
        temporary.writeText(published)
        FileOutputStream(temporary, true).use { it.fd.sync() }
        require(temporary.renameTo(currentFile)) { "发布本地模型版本失败" }
        state.value = LocalTtsPackState.Ready(manifest)
        return manifest
    }

    private fun prepareDownloaded(connection: RelayConnection): LocalTtsManifest {
        val config = connection.validated()
        val manifest = fetchManifest(config)
        require(manifest.files.isNotEmpty()) { "服务器没有发布本地模型文件" }
        val destination = rootFor(manifest)
        require(destination.usableSpace >= manifest.minimumFreeBytes) { "本地空间不足，至少需要 ${manifest.minimumFreeBytes / (1024 * 1024)} MB 可用空间" }
        for (entry in manifest.files) {
            val target = file(manifest, entry.path)
            download(config, entry, target)
        }
        state.value = LocalTtsPackState.Verifying(manifest.revision)
        for (entry in manifest.files) {
            val target = file(manifest, entry.path)
            require(target.length() == entry.bytes && sha256(target) == entry.sha256) { "模型文件校验失败：${entry.name}" }
        }
        return publish(manifest)
    }

    fun clearCurrent() {
        val manifest = current() ?: return
        LocalTtsNative.release()
        rootFor(manifest).deleteRecursively()
        currentFile.delete()
        state.value = LocalTtsPackState.Missing
    }

    private fun fetchManifest(connection: RelayConnection): LocalTtsManifest {
        val response = open(connection, "/api/v1/local-tts/manifest")
        return try {
            require(response.responseCode in 200..299) { "无法获取本地模型清单（${response.responseCode}）" }
            parse(response.inputStream.bufferedReader().use { it.readText() })
        } finally { response.disconnect() }
    }

    private fun download(connection: RelayConnection, entry: LocalTtsFile, target: File) {
        target.parentFile?.mkdirs()
        var offset = target.takeIf { it.isFile }?.length() ?: 0L
        if (offset > entry.bytes) { target.delete(); offset = 0L }
        if (offset == entry.bytes && sha256(target) == entry.sha256) {
            state.value = LocalTtsPackState.Downloading(entry.name, offset, entry.bytes)
            return
        }
        val response = open(connection, "/api/v1/local-tts/files/${entry.path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }}")
        try {
            if (offset > 0) {
                response.setRequestProperty("Range", "bytes=$offset-")
                response.setRequestProperty("If-Range", "\"${entry.sha256}\"")
            }
            val status = response.responseCode
            val append = offset > 0 && status == HttpURLConnection.HTTP_PARTIAL
            if (!append) {
                offset = 0L
                require(status == HttpURLConnection.HTTP_OK) { "下载模型文件失败：${entry.name}（$status）" }
            }
            FileOutputStream(target, append).use { output ->
                response.inputStream.use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var received = offset
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("模型下载已取消")
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        require(received <= entry.bytes) { "模型文件大小超出清单：${entry.name}" }
                        output.write(buffer, 0, count)
                        state.value = LocalTtsPackState.Downloading(entry.name, received, entry.bytes)
                    }
                    output.fd.sync()
                }
            }
            require(target.length() == entry.bytes) { "模型文件下载不完整：${entry.name}" }
        } finally { response.disconnect() }
    }

    private fun open(connection: RelayConnection, path: String): HttpURLConnection =
        (URI(connection.validated().endpoint + path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 15000
            readTimeout = 120000
            setRequestProperty("X-Relay-Token", connection.token)
            setRequestProperty("Accept", "application/json, application/octet-stream")
        }

    private fun parse(raw: String): LocalTtsManifest {
        val value = JSONObject(raw)
        val files = value.getJSONArray("files").items { file ->
            LocalTtsFile(file.getString("name"), file.getString("path"), file.getLong("bytes"), file.getString("sha256"))
        }
        val voices = value.optJSONArray("voices")?.items { voice ->
            LocalTtsVoice(voice.getString("id"), voice.getString("speaker"), voice.getString("name"), voice.getString("codes"), voice.getString("sha256"))
        } ?: emptyList()
        return LocalTtsManifest(value.getString("pack_revision"), value.getString("engine_revision"), files, voices,
            value.optLong("minimum_free_bytes", 256L * 1024 * 1024))
    }

    private fun encode(value: LocalTtsManifest): JSONObject = JSONObject().apply {
        put("pack_revision", value.revision).put("engine_revision", value.engineRevision).put("minimum_free_bytes", value.minimumFreeBytes)
        put("files", JSONArray().apply { value.files.forEach { put(JSONObject().put("name", it.name).put("path", it.path).put("bytes", it.bytes).put("sha256", it.sha256)) } })
        put("voices", JSONArray().apply { value.voices.forEach { put(JSONObject().put("id", it.id).put("speaker", it.speaker).put("name", it.name).put("codes", it.codes).put("sha256", it.sha256)) } })
    }

    private fun readReady(): LocalTtsPackState.Ready? = runCatching {
        val value = JSONObject(currentFile.readText()).getJSONObject("manifest")
        LocalTtsPackState.Ready(parse(value.toString())).takeIf { ready ->
            ready.manifest.files.all { entry ->
                val target = file(ready.manifest, entry.path)
                target.isFile && target.length() == entry.bytes
            }
        }
    }.getOrNull()

    private fun validateFiles(manifest: LocalTtsManifest) {
        for (entry in manifest.files) {
            val target = file(manifest, entry.path)
            require(target.isFile && target.length() == entry.bytes && sha256(target) == entry.sha256) {
                "本地模型文件校验失败：${entry.name}"
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private inline fun <T> JSONArray.items(map: (JSONObject) -> T): List<T> = buildList {
        for (index in 0 until length()) add(map(getJSONObject(index)))
    }

    companion object {
        private const val DEFAULT_VOICE_ASSET = "LinChuan.codes.npy"
        private const val REMOTE_BASE = "https://hf-mirror.com/Zyphra/ZONOS3-Mini/resolve/main/gguf/"
    }
}
