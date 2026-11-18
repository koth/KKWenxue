package com.kkwenxue.shanye.ui

import android.graphics.Color
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

internal enum class SystemBarsMode { HOME, READER_LIGHT, READER_DARK, PLAYER, SYNTHESIS }

/** One window owner: closing an overlay restores the current screen, not an old inset snapshot. */
internal class SystemBarsController(private val window: Window) {
    private var base = SystemBarsMode.HOME
    private val overlays = linkedMapOf<Any, SystemBarsMode>()

    fun setBase(mode: SystemBarsMode) {
        base = mode
        apply()
    }

    fun acquire(owner: Any, mode: SystemBarsMode) {
        overlays[owner] = mode
        apply()
    }

    fun release(owner: Any) {
        overlays.remove(owner)
        apply()
    }

    fun restore() {
        apply()
        // Focus/attach processing can reset insets after onResume. Read the latest
        // mode when this runs, so rapid navigation cannot replay an old request.
        window.decorView.post { apply() }
    }

    private fun apply() {
        val mode = overlays.values.lastOrNull() ?: base
        val reading = mode == SystemBarsMode.READER_LIGHT || mode == SystemBarsMode.READER_DARK
        val dark = mode == SystemBarsMode.PLAYER || mode == SystemBarsMode.READER_DARK
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        val color = when (mode) {
            SystemBarsMode.PLAYER -> Color.rgb(38, 25, 28)
            SystemBarsMode.READER_DARK -> Color.rgb(32, 38, 34)
            else -> Color.rgb(247, 244, 237)
        }
        window.statusBarColor = color
        window.navigationBarColor = color
        if (reading) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

internal val LocalSystemBars = staticCompositionLocalOf<SystemBarsController?> { null }

@Composable
internal fun OverlaySystemBars(mode: SystemBarsMode) {
    val controller = LocalSystemBars.current
    DisposableEffect(controller, mode) {
        val owner = Any()
        controller?.acquire(owner, mode)
        onDispose { controller?.release(owner) }
    }
}
