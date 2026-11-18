package com.kkwenxue.shanye.data

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Work data contains only the local tracking key. Credentials stay in encrypted private storage. */
object SynthesisDownloads {
    private fun name(key: String) = "synthesis-delivery-$key"
    fun enqueue(context: Context, key: String, delaySeconds: Long = 0, append: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SynthesisDeliveryWorker>()
            .setInputData(Data.Builder().putString("key", key).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag("synthesis-delivery")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(key),
            if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request).result.get()
    }
    fun resume(context: Context) {
        val repository = SynthesisRepository(context)
        repository.deliveries().forEach { delivery ->
            enqueue(context, delivery.key, ((delivery.nextAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
        }
    }
    fun retry(context: Context, connection: RelayConnection, id: String) {
        val key = SynthesisRepository(context).requestImport(connection, id)
        enqueue(context, key)
    }
}

/** A bounded step can run independently of every UI screen, including after a process restart. */
class SynthesisDeliveryRunner(private val context: Context, private val repository: SynthesisRepository = SynthesisRepository(context)) {
    fun step(key: String, stopped: () -> Boolean = { false }, books: () -> List<Book> = { Library.loadForBackground(context) }): Long? {
        var delivery = repository.delivery(key) ?: return null
        if (delivery.state in setOf("imported", "blocked")) return null
        val deadline = SystemClock.elapsedRealtime() + 4 * 60 * 1000
        fun checkpoint() {
            if (stopped() || Thread.currentThread().isInterrupted) throw InterruptedIOException("下载已暂停，稍后自动继续")
            if (SystemClock.elapsedRealtime() > deadline) throw DeliveryPending("稍后自动继续下载", 10)
        }
        fun wait(phase: String, seconds: Long = 15): Long {
            repository.deliveryState(key, "waiting", phase, failures = 0, nextAt = System.currentTimeMillis() + seconds * 1000)
            return seconds
        }
        try {
            checkpoint()
            val config = repository.settings.load(delivery.endpoint)
                ?: throw IllegalArgumentException("请先配置此任务服务器的接入凭据")
            val client = SynthesisRelay(config)
            if (delivery.taskId == null) {
                repository.deliveryState(key, "running", "正在确认派发")
                repository.publish(config, key)
                delivery = repository.delivery(key) ?: return null
            }
            val id = delivery.taskId ?: error("派发状态尚未确认")
            val current = RelayTask.parse(client.status(id))
            repository.remember(config.endpoint, current)
            if ((repository.tasks(config.endpoint).firstOrNull { it.task.id == id }?.importedAt ?: 0L) > 0L) {
                repository.deliveryState(key, "imported", "已导入")
                return null
            }
            when (current.state) {
                "queued" -> return wait("排队合成中 · 完成后自动导入")
                "running" -> return wait("合成中 · 完成后自动导入")
                "failed" -> throw IllegalArgumentException(current.error ?: "合成失败，请检查任务后重新派发")
                "completed" -> Unit
                else -> throw IllegalArgumentException("任务已结束：${current.state}")
            }
            checkpoint()
            repository.deliveryState(key, "running", "准备自动下载")
            var lastUpdate = 0L
            AudioLibrary(context).use { library ->
                repository.importTask(config, id, library, books(), waitForTransfer = false) { phase, received, total ->
                    checkpoint()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate >= 500 || received == total || received == 0L) {
                        repository.deliveryState(key, "running", phase, received = received, total = total)
                        lastUpdate = now
                    }
                }
            }
            return null
        } catch (pending: DeliveryPending) {
            return wait(pending.phase, pending.delaySeconds)
        } catch (error: Exception) {
            if (stopped() || Thread.currentThread().isInterrupted) throw error
            val failures = delivery.failures + 1
            val retryable = when (error) {
                is PackageIntegrityError -> failures <= 3
                is RelayHttpError -> error.status in setOf(408, 425, 429) || error.status >= 500
                is IOException -> true
                else -> error.message?.contains("空间不足") == true || error.message?.contains("中转上传失败") == true
            }
            val message = error.message ?: "下载失败"
            if (!retryable) {
                repository.deliveryState(key, "blocked", "需要处理后重试", message, failures = failures)
                return null
            }
            val seconds = (10L shl (failures - 1).coerceAtMost(5)).coerceAtMost(300)
            repository.deliveryState(key, "retry", "${seconds}秒后自动重试", message, failures = failures,
                nextAt = System.currentTimeMillis() + seconds * 1000)
            return seconds
        }
    }
}

class SynthesisDeliveryWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val key = inputData.getString("key") ?: return Result.failure()
        return try {
            val delay = SynthesisDeliveryRunner(applicationContext).step(key, { isStopped })
            if (delay != null && !isStopped) {
                // Persist the successor before succeeding: a process death cannot lose the next poll.
                // Normal polling does not increase the network-error backoff.
                SynthesisDownloads.enqueue(applicationContext, key, delay, append = true)
            }
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }
}
