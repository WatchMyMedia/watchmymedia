package media.watchmy.client.tv.player

import android.view.Display
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs
import kotlin.math.roundToInt

/** A display mode as [matchingMode] weighs it. */
internal data class ModeChoice(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * Match frame rate: the display mode to show [frameRate] video in, so every
 * frame stays on screen for the same time. The [current] mode's size, at a
 * refresh rate that is a whole multiple of the frame rate: 23.976 on 23.976
 * or 47.952 Hz, 24 on 24 or 48, 25 on 50, 29.97 on 59.94, 30 on 60, and 50,
 * 59.94 and 60 on themselves (25 and 30 Hz modes are left out: TVs keep them
 * for old broadcasts, and they flicker). The current mode when it already is
 * one, as a 120 Hz mode is for 24p, so the screen doesn't go dark for
 * nothing; else the lowest such rate. Only when no mode fits exactly does one
 * within 0.2% do (24 Hz for 23.976 on a TV without 23.976). Null: keep the
 * mode there is.
 */
internal fun matchingMode(modes: List<ModeChoice>, current: ModeChoice, frameRate: Float): ModeChoice? {
    if (frameRate !in MIN_FRAME_RATE..MAX_FRAME_RATE) return null
    val sameSize = modes.filter { it.width == current.width && it.height == current.height }
    for (tolerance in TOLERANCES) {
        if (showsEvenly(current.refreshRate, frameRate, tolerance)) return current
        sameSize.filter { showsEvenly(it.refreshRate, frameRate, tolerance) }.minByOrNull { it.refreshRate }?.let { return it }
    }
    return null
}

/** A [refreshRate] that shows every frame of [frameRate] video a whole number of times, within [tolerance] (a fraction). */
internal fun showsEvenly(refreshRate: Float, frameRate: Float, tolerance: Float): Boolean {
    if (refreshRate < MIN_REFRESH_RATE && refreshRate !in FILM_REFRESH_RATES) return false
    val multiple = (refreshRate / frameRate).roundToInt()
    return multiple >= 1 && abs(refreshRate / (multiple * frameRate) - 1f) <= tolerance
}

/** Exact first (rounding in the reported rates), then near: 24 for 23.976, 60 for 59.94. */
private val TOLERANCES = listOf(0.0005f, 0.002f)
private const val MIN_FRAME_RATE = 10f
private const val MAX_FRAME_RATE = 125f

/** Below this only the film rates count. */
private const val MIN_REFRESH_RATE = 47f
private val FILM_REFRESH_RATES = 23.9f..24.1f

/**
 * Match frame rate (TV Settings): while [enabled] and the video's
 * [frameRate] is known, asks the display for [matchingMode] through this
 * window, and gives the window back the mode it preferred before (usually
 * none) when the player goes. Media3 leaves the display alone meanwhile
 * (`PlayerOptions.appMatchesFrameRate`), so the two never both switch. A rate
 * that goes unknown, as the next episode loads, keeps the mode until the new
 * one is known.
 */
@Composable
internal fun MatchFrameRate(frameRate: Float?, enabled: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(window) {
        val before = window.attributes.preferredDisplayModeId
        onDispose { window.preferDisplayMode(before) }
    }
    LaunchedEffect(window, frameRate, enabled) {
        if (!enabled || frameRate == null) return@LaunchedEffect
        val display = view.display ?: return@LaunchedEffect
        val mode = matchingMode(display.supportedModes.map { it.choice() }, display.mode.choice(), frameRate) ?: return@LaunchedEffect
        window.preferDisplayMode(mode.id)
    }
}

private fun Display.Mode.choice() = ModeChoice(modeId, physicalWidth, physicalHeight, refreshRate)

private fun Window.preferDisplayMode(id: Int) {
    if (attributes.preferredDisplayModeId == id) return
    attributes = attributes.apply { preferredDisplayModeId = id }
}
