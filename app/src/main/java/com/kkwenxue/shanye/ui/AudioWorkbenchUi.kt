package com.kkwenxue.shanye.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class AudioWorkbenchMode {
    PLAYBACK,
    LOCAL_REALTIME,
    SERVER
}

internal val WorkbenchWhite = Color(0xFFF7EEEA)
internal val WorkbenchMuted = WorkbenchWhite.copy(alpha = .62f)
internal val WorkbenchAccent = Color(0xFFFFC1AA)
internal val WorkbenchPanel = Color(0xFF382529)

/**
 * The one navigation shell shared by imported audio, local realtime TTS and
 * server synthesis.  The content below the shell can differ, but entering,
 * switching and leaving the three paths is always identical.
 */
@Composable
internal fun AudioWorkbenchHeader(
    title: String,
    subtitle: String?,
    mode: AudioWorkbenchMode,
    onModeSelected: (AudioWorkbenchMode) -> Unit,
    onClose: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    var modeMenuOpen = remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WorkbenchIcon("收起听书工作台", onClose)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(title, fontSize = 16.sp, color = WorkbenchWhite, maxLines = 1)
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, Modifier.padding(top = 3.dp), fontSize = 10.sp, color = WorkbenchMuted, maxLines = 1)
                }
            }
            trailing()
        }
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            OutlinedButton(
                onClick = { modeMenuOpen.value = true },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = WorkbenchWhite),
                border = BorderStroke(1.dp, WorkbenchWhite.copy(.18f)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    "播放来源 · ${mode.label()}",
                    Modifier.weight(1f),
                    color = WorkbenchWhite,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start
                )
                Text("▾", color = WorkbenchMuted, fontSize = 16.sp)
            }
            DropdownMenu(
                expanded = modeMenuOpen.value,
                onDismissRequest = { modeMenuOpen.value = false },
                modifier = Modifier.background(WorkbenchPanel)
            ) {
                AudioWorkbenchMode.entries.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                item.label(),
                                color = if (item == mode) WorkbenchAccent else WorkbenchWhite
                            )
                        },
                        onClick = {
                            modeMenuOpen.value = false
                            onModeSelected(item)
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun AudioWorkbenchMode.label(): String = when (this) {
    AudioWorkbenchMode.PLAYBACK -> "已有音频"
    AudioWorkbenchMode.LOCAL_REALTIME -> "本地实时"
    AudioWorkbenchMode.SERVER -> "服务器合成"
}

@Composable
private fun WorkbenchIcon(label: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(44.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Glyph("collapse", WorkbenchWhite, size = 26.dp)
    }
}
