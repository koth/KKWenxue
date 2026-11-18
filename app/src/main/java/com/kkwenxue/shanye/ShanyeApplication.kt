package com.kkwenxue.shanye

import android.app.Application
import com.kkwenxue.shanye.data.SynthesisDownloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ShanyeApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        scope.launch {
            runCatching { SynthesisDownloads.resume(this@ShanyeApplication) }
                .onFailure { android.util.Log.w("SynthesisDownloads", "后台任务将在下次打开时恢复", it) }
        }
    }
}
