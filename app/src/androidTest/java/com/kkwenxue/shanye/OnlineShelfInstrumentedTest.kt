package com.kkwenxue.shanye

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkwenxue.shanye.data.OnlineSyncStatus
import com.kkwenxue.shanye.data.OnlineUpdatePlan
import com.kkwenxue.shanye.ui.LegacyOnlineAction
import com.kkwenxue.shanye.ui.OnlinePlanDialog
import com.kkwenxue.shanye.ui.OnlineShelfStatus
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class OnlineShelfInstrumentedTest {
    @Test fun legacyAssociationRiskConfirmationAndRollbackExposeExplicitActions() {
        val linked = AtomicBoolean(false)
        val rolledBack = AtomicBoolean(false)
        val applied = AtomicBoolean(false)
        val postponed = AtomicBoolean(false)
        val plan = OnlineUpdatePlan(7, "old", "new", "catalog-new", false,
            listOf("续章"), listOf("开篇修订"), listOf("旧番外"), true, 4096, Long.MAX_VALUE)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                host.setContent {
                    MaterialTheme {
                        var showPlan by remember { mutableStateOf(false) }
                        Column {
                            LegacyOnlineAction(false) { linked.set(true) }
                            OnlineShelfStatus(
                                OnlineSyncStatus("needs_confirmation", plan = plan, canRollback = true),
                                onCancel = {}, onRetry = {}, onPlan = { showPlan = true },
                                onRollback = { rolledBack.set(true) }
                            )
                        }
                        if (showPlan) OnlinePlanDialog(plan, onConfirm = {
                            applied.set(true); showPlan = false
                        }, onLater = {
                            postponed.set(true); showPlan = false
                        }, onDismiss = { showPlan = false })
                    }
                }
            }
            click("关联在线书籍")
            assertTrue(linked.get())
            click("查看更新计划")
            assertNotNull(find("开篇修订"))
            click("稍后")
            assertTrue(postponed.get())
            assertTrue(!applied.get())
            click("查看更新计划")
            click("确认更新")
            assertTrue(applied.get())
            click("回滚正文")
            assertTrue(rolledBack.get())
        }
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 6_000
        do {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(queue::add)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.text?.toString() == label) return node
                repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
            }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }

    private fun click(label: String) {
        var node = find(label)
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                return
            }
            node = node.parent
        }
        throw AssertionError("无法点击：$label")
    }
}
