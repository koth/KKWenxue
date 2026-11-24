package com.kkwenxue.shanye.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AudioWorkbenchMode.entries.forEach { item ->
                val selected = item == mode
                Text(
                    item.label(),
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) WorkbenchAccent.copy(.18f) else WorkbenchWhite.copy(.055f))
                        .clickable { onModeSelected(item) }
                        .padding(vertical = 10.dp),
                    color = if (selected) WorkbenchAccent else WorkbenchMuted,
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
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
