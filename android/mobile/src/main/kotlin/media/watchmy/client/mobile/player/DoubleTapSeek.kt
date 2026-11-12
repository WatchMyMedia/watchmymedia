package media.watchmy.client.mobile.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Which side of the screen a tap landed on, for double-tap seeking. The middle fifth does not seek. */
internal enum class SeekZone { Back, None, Forward }

internal fun seekZone(x: Float, width: Float): SeekZone = when {
    width <= 0f -> SeekZone.None
    x < width * 0.4f -> SeekZone.Back
    x > width * 0.6f -> SeekZone.Forward
    else -> SeekZone.None
}

/** A run of seeks on one side: [steps] taps' worth, the last at [lastTapMs]. */
internal data class SeekBurst(val zone: SeekZone, val steps: Int, val lastTapMs: Long) {
    /** One tap's seek, negative going back. */
    val stepMs: Long get() = if (zone == SeekZone.Back) -DoubleTapSeek.STEP_MS else DoubleTapSeek.STEP_MS

    /** The whole run's seek. */
    val totalMs: Long get() = steps * stepMs
}

/**
 * Double-tap to seek, as on most phone players: a double tap on the left or
 * right seeks 10 s back or forward, and each further tap on the same side
 * within [WINDOW_MS] of the last adds another 10 s without waiting for a
 * second tap. A double tap in the middle toggles the controls like a tap.
 */
internal class DoubleTapSeek {
    var burst: SeekBurst? = null
        private set

    /**
     * A single tap. Returns the seek to apply when it continues a burst on
     * the same side, or null when it is an ordinary tap (the caller then
     * waits to see whether a second tap follows).
     */
    fun onTap(x: Float, width: Float, nowMs: Long): Long? {
        val b = burst?.takeIf { it.zone == seekZone(x, width) && nowMs - it.lastTapMs <= WINDOW_MS }
        if (b == null) {
            burst = null
            return null
        }
        burst = b.copy(steps = b.steps + 1, lastTapMs = nowMs)
        return b.stepMs
    }

    /** A double tap. Returns the seek to apply, or null in the middle (toggle the controls instead). */
    fun onDoubleTap(x: Float, width: Float, nowMs: Long): Long? {
        val zone = seekZone(x, width)
        if (zone == SeekZone.None) {
            burst = null
            return null
        }
        val b = SeekBurst(zone, 1, nowMs)
        burst = b
        return b.stepMs
    }

    /** True while [burst] still takes single taps at [nowMs]. */
    fun isActive(nowMs: Long): Boolean = burst?.let { nowMs - it.lastTapMs <= WINDOW_MS } ?: false

    fun reset() {
        burst = null
    }

    companion object {
        const val STEP_MS = 10_000L
        const val WINDOW_MS = 700L
    }
}

/** Replacing the recognizer also cancels TouchLayer's pending pointer-input coroutine. */
@Composable
internal fun rememberSeekForFile(fileId: Long, playGeneration: Long = 0): DoubleTapSeek = remember(fileId, playGeneration) { DoubleTapSeek() }
