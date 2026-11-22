package com.kkwenxue.shanye.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.StatFs
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class OnlineSyncStatus(
    val state: String,
    val message: String? = null,
    val checkedAt: Long = 0,
    val latestTitle: String? = null,
    val latestCount: Long = 0,
    val newChapters: Long = 0,
    val plan: OnlineUpdatePlan? = null,
    val downloaded: Long = 0,
    val canRollback: Boolean = false
)

data class OnlineSyncJob(
    val bookId: String, val fromRevision: String, val target: String,
    val state: String, val taskId: String? = null, val error: String? = null
)

data class OnlineSyncSettings(val automatic: Boolean, val unmeteredOnly: Boolean)

/** Non-blocking shelf scans share one queue and one in-flight job per book. */
class OnlineLibrarySync(private val context: Context) {
    private val prefs = context.getSharedPreferences("shanye_online_sync", Context.MODE_PRIVATE)
    private val database = BookDatabase(context.applicationContext)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mutableSettings = MutableStateFlow(OnlineSyncSettings(
        prefs.getBoolean("automatic_updates", true), prefs.getBoolean("unmetered_only", false)
    ))
    val settings = mutableSettings.asStateFlow()

    fun setSettings(value: OnlineSyncSettings) {
        val previous = settings.value
        prefs.edit().putBoolean("automatic_updates", value.automatic)
            .putBoolean("unmetered_only", value.unmeteredOnly).apply()
        mutableSettings.value = value
        if ((previous.automatic && !value.automatic) ||
            (!previous.unmeteredOnly && value.unmeteredOnly && !onUnmeteredNetwork())) {
            automaticJobs.values.forEach { it.cancel(PolicyPaused()) }
        }
    }

    fun observeNetwork(onAvailable: () -> Unit): () -> Unit {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var usable = false
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    (!settings.value.unmeteredOnly || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                if (available && !usable) onAvailable()
                if (!available && usable && settings.value.unmeteredOnly)
                    automaticJobs.values.forEach { it.cancel(PolicyPaused()) }
                usable = available
            }

            override fun onLost(network: Network) { usable = false }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        return { connectivity.unregisterNetworkCallback(callback) }
    }

    fun cancel(bookId: String) {
        running[bookId]?.cancel(UserCancelled())
    }

    private fun onUnmeteredNetwork(): Boolean = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    suspend fun postpone(book: Book, plan: OnlineUpdatePlan) = withContext(Dispatchers.IO) {
        val job = database.syncJob(book, plan.remoteRevision) ?: return@withContext
        if (job.state != "needs_confirmation") return@withContext
        database.recordSyncJob(job.copy(state = "postponed"))
        val status = (statuses.value[book.id] ?: database.onlineStatus(book.id))
            .copy(state = "postponed", message = "已选择稍后更新")
        database.markOnlineStatus(book.id, status)
        publish(book.id, status)
    }

    suspend fun rollback(book: Book): Book? = withContext(Dispatchers.IO) {
        val me = currentCoroutineContext()[Job]!!
        check(running.putIfAbsent(book.id, me) == null) { "请先取消或等待当前更新" }
        try {
            database.rollback(book.id)?.also {
                publish(book.id, database.onlineStatus(book.id).copy(state = "rolled_back", message = "已恢复上一版本"))
            }
        } finally {
            running.remove(book.id, me)
        }
    }

    suspend fun sync(
        books: List<Book>,
        retry: Boolean = false,
        confirmed: OnlineUpdatePlan? = null,
        onUpdated: (Book) -> Unit
    ) = supervisorScope {
        books.filter { it.remoteBookId != null && !it.activeRevision.isNullOrBlank() }
            .distinctBy { it.id }.map { shelfBook ->
                async(Dispatchers.IO) {
                    val me = currentCoroutineContext()[Job]!!
                    if (running.putIfAbsent(shelfBook.id, me) != null) return@async
                    if (!retry && confirmed == null) automaticJobs[shelfBook.id] = me
                    try {
                        concurrency.withPermit {
                            context.cacheDir.listFiles { file -> file.name.startsWith("online-update-") &&
                                file.name.endsWith(".zip") && file.lastModified() < System.currentTimeMillis() - 86_400_000L }
                                ?.forEach { it.delete() }
                            val book = database.load(shelfBook.id).firstOrNull() ?: return@withPermit
                            updateOne(book, retry, confirmed)?.let {
                                // Once the database commit succeeds, mirror it even if a
                                // cancellation arrived just as the commit was finishing.
                                withContext(Dispatchers.Main + NonCancellable) { onUpdated(it) }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        android.util.Log.w("OnlineLibrarySync", "无法读取在线书籍同步状态", error)
                        val current = statuses.value[shelfBook.id] ?: OnlineSyncStatus("idle")
                        publish(shelfBook.id, current.copy(state = "failed", message = "无法读取本地同步状态"))
                    } finally {
                        running.remove(shelfBook.id, me)
                        automaticJobs.remove(shelfBook.id, me)
                    }
                }
            }.forEach { job ->
                try { job.await() } catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
            }
    }

    private suspend fun updateOne(book: Book, retry: Boolean, confirmed: OnlineUpdatePlan?): Book? {
        val remoteId = book.remoteBookId ?: return null
        val from = book.activeRevision ?: return null
        val client = CatalogClient()
        val cached = database.onlineStatus(book.id)
        var status = cached.copy(canRollback = database.hasRollback(book.id))
        var job: OnlineSyncJob? = null
        fun phase(
            state: String,
            message: String? = null,
            plan: OnlineUpdatePlan? = status.plan,
            persist: Boolean = false
        ) {
            status = status.copy(state = state, message = message, plan = plan)
            if (persist) database.markOnlineStatus(book.id, status)
            publish(book.id, status)
        }

        return try {
            phase("checking")
            val version = runInterruptible { client.version(remoteId) }
            require(version.bookId == remoteId && version.remoteRevision.isNotBlank()) { "目录版本无效" }
            status = status.copy(checkedAt = System.currentTimeMillis(), latestTitle = version.latestChapter,
                latestCount = version.chapterCount, newChapters = (version.chapterCount - book.chapters.size).coerceAtLeast(0),
                plan = null, downloaded = 0)
            val rolledBack = database.rollbackHeld(book.id) && version.remoteRevision == book.remoteLatestVersion
            if (version.remoteRevision == book.remoteLatestVersion && (!rolledBack || !retry)) {
                phase(if (rolledBack) "rolled_back" else "up_to_date", persist = true)
                return null
            }
            if (settings.value.unmeteredOnly && !onUnmeteredNetwork()) {
                phase("paused", "等待不限流量网络", persist = true)
                return null
            }
            if (!settings.value.automatic && !retry && confirmed == null) {
                phase("paused", "自动更新已暂停", persist = true)
                return null
            }

            // Online books use a lightweight manifest revision. Refreshing it
            // keeps already cached chapter bodies by stable source ID and never
            // starts the legacy whole-book ZIP task during an automatic check.
            if (book.remoteSource == "catalog" && version.remoteRevision != from) {
                phase("syncing", "正在更新章节目录", persist = true)
                val updated = loadOnlineManifest(book, client, version)
                database.save(updated, from)
                phase("completed", "目录已更新，共 ${updated.chapters.size} 章", persist = true)
                publish(book.id, status.copy(latestCount = updated.chapters.size.toLong()))
                return updated
            }

            job = database.syncJob(book, version.remoteRevision)
            if (!retry && confirmed == null && job?.state == "cancelled") {
                phase("cancelled", "已取消等待，可手动重试", persist = true)
                return null
            }
            val saved = job
            phase("planning", persist = true)
            var task = saved?.taskId?.let {
                try { runInterruptible { client.status(it) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }
            if (task == null || task.failureMessage != null) {
                if (confirmed != null) {
                    phase("available", "原更新计划已经失效，请重新检查后确认", persist = true)
                    return null
                }
                task = runInterruptible { client.startUpdate(book, version) }
            }
            var pending = requireNotNull(task)
            job = OnlineSyncJob(book.id, from, version.remoteRevision, "running", pending.taskId)
            database.recordSyncJob(job)
            var polls = 0
            while (!pending.done) {
                pending.failureMessage?.let { error(it) }
                check(polls++ < 360) { "服务器仍在准备正文，请稍后重试" }
                phase(if (pending.phase == "fetch-txt") "fetching" else "planning")
                delay(1000)
                val taskId = pending.taskId
                pending = runInterruptible { client.status(taskId) }
            }
            val plan = OnlineUpdatePlan.parse(pending, book, version)
            if (confirmed != null && confirmed != plan) {
                job = job.copy(state = "needs_confirmation")
                database.recordSyncJob(job)
                phase("needs_confirmation", "更新计划有变化，请重新确认", plan, persist = true)
                return null
            }
            if (!plan.appendOnly && confirmed == null) {
                val state = if (!retry && saved?.state == "postponed") "postponed" else "needs_confirmation"
                job = job.copy(state = state)
                database.recordSyncJob(job)
                phase(state, "已有正文或章节顺序发生变化，需确认后更新", plan, persist = true)
                return null
            }
            if ((!settings.value.automatic && !retry && confirmed == null) ||
                (settings.value.unmeteredOnly && !onUnmeteredNetwork())) {
                phase("paused", "下载策略已暂停更新", plan, persist = true)
                return null
            }
            val requiredBytes = plan.bytes + 8L * 1024 * 1024 + book.chapters.sumOf { it.body.length.toLong() * 8 }
            check(StatFs(context.cacheDir.path).availableBytes > requiredBytes) { "设备可用空间不足，无法保存在线更新" }
            val archive = File.createTempFile("online-update-", ".zip", context.cacheDir)
            try {
                var waits = 0
                phase("relaying", plan = plan)
                while (!runInterruptible {
                    client.download(pending, archive) { bytes ->
                        if (status.state != "downloading" || bytes - status.downloaded >= 256 * 1024 || bytes == plan.bytes) {
                            status = status.copy(state = "downloading", downloaded = bytes)
                            publish(book.id, status)
                        }
                    }
                }) {
                    check(waits++ < 60) { "正文中转仍未完成，请稍后重试" }
                    delay(2000)
                }
                phase("validating")
                val updated = ContentPackageImporter.importUpdate(archive, book, plan)
                currentCoroutineContext().ensureActive()
                phase("applying")
                database.save(updated, from, job)
                phase("completed", if (plan.added.isEmpty()) "正文已更新" else "已新增 ${plan.added.size} 章",
                    plan = null, persist = true)
                publish(book.id, status.copy(canRollback = database.hasRollback(book.id)))
                updated
            } finally { archive.delete() }
        } catch (cancelled: CancellationException) {
            if (cancelled is UserCancelled || cancelled is PolicyPaused) {
                val state = if (cancelled is UserCancelled) "cancelled" else "paused"
                if (status.state != "completed") {
                    job?.let { database.recordSyncJob(it.copy(state = state)) }
                    phase(state, if (state == "cancelled") "已取消等待，可手动重试" else "下载策略已暂停更新", persist = true)
                }
            }
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val message = error.message ?: "在线更新失败，请稍后重试"
            job?.let { database.recordSyncJob(it.copy(state = "failed", error = message)) }
            phase("failed", message, persist = true)
            android.util.Log.w("OnlineLibrarySync", "在线书籍同步失败：${book.title}", error)
            null
        }
    }

    private suspend fun loadOnlineManifest(book: Book, client: CatalogClient, version: CatalogVersion): Book {
        val manifest=runInterruptible(Dispatchers.IO){client.completeManifest(requireNotNull(book.remoteBookId))}
        val refs=manifest.chapters
        val chapters = refs.sortedBy { it.index }.map { ref ->
            Chapter("online:catalog:${book.remoteBookId}:chapter:source:${ref.sourceId}", ref.title, "", ref.bodySha256)
        }
        return book.copy(
            subtitle = "${chapters.size} 章 · 在线目录",
            chapters = chapters,
            activeRevision = manifest.revision,
            remoteLatestChapterId = version.latestChapterId,
            remoteLatestVersion = manifest.revision
        )
    }

    private class UserCancelled : CancellationException("用户取消在线更新")
    private class PolicyPaused : CancellationException("下载策略已暂停更新")

    companion object {
        private val running = ConcurrentHashMap<String, Job>()
        private val automaticJobs = ConcurrentHashMap<String, Job>()
        private val concurrency = Semaphore(3)
        private val statusFlow = MutableStateFlow<Map<String, OnlineSyncStatus>>(emptyMap())
        val statuses = statusFlow.asStateFlow()

        private fun publish(bookId: String, status: OnlineSyncStatus) {
            statusFlow.update { it + (bookId to status) }
        }
    }
}
