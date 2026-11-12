package media.watchmy.client.tv.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * Seek steps for left/right and rewind/fast-forward: 10 s a press. Held,
 * the key repeats about twenty times a second; a step is taken at most every
 * [REPEAT_INTERVAL_MS] and grows the longer the key is down, so a long hold
 * crosses an episode in seconds.
 */
internal class SeekAccelerator {
    private var heldSince = NOT_HELD
    private var lastStepAt = 0L

    /** The step for a key-down at [nowMs] (uptime); [repeat] for the key's auto-repeats. 0 between throttled repeats. */
    fun step(nowMs: Long, repeat: Boolean): Long {
        if (!repeat || heldSince == NOT_HELD) {
            heldSince = nowMs
            lastStepAt = nowMs
            return PRESS_MS
        }
        if (nowMs - lastStepAt < REPEAT_INTERVAL_MS) return 0
        lastStepAt = nowMs
        val held = nowMs - heldSince
        return TIERS.firstOrNull { held < it.first }?.second ?: MAX_STEP_MS
    }

    fun release() {
        heldSince = NOT_HELD
    }

    companion object {
        const val PRESS_MS = 10_000L
        const val REPEAT_INTERVAL_MS = 200L
        const val MAX_STEP_MS = 120_000L

        /** (held for less than, step). */
        private val TIERS = listOf(2_000L to 10_000L, 4_000L to 30_000L, 7_000L to 60_000L)
        private const val NOT_HELD = -1L
    }
}

/**
 * The scrubber's target while the viewer seeks with the remote. Presses move
 * the target without seeking; the seek is sent once, when the key comes up
 * ([release]), so a held key does not send a request per step. The target
 * stays on screen until the player's position reaches it ([settle]).
 */
@Stable
internal class SeekState(private val accelerator: SeekAccelerator = SeekAccelerator()) {
    var targetMs by mutableStateOf<Long?>(null)
        private set
    var holding by mutableStateOf(false)
        private set

    /** The last step taken, back (below 0) or forward: what the seek thumbnail's chip says ("+2 min"). */
    var stepMs by mutableLongStateOf(0L)
        private set

    fun press(forward: Boolean, positionMs: Long, durationMs: Long, nowMs: Long, repeat: Boolean) {
        val step = accelerator.step(nowMs, repeat)
        if (step != 0L) stepMs = if (forward) step else -step
        val from = targetMs ?: positionMs
        val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
        targetMs = (from + if (forward) step else -step).coerceIn(0, max)
        holding = true
    }

    /** The key came up: the position to seek to, or null if nothing was pressed. */
    fun release(): Long? {
        accelerator.release()
        holding = false
        return targetMs
    }

    /** The player reached the target, or gave up: the scrubber follows the position again. */
    fun settle() {
        if (!holding) targetMs = null
    }

    fun displayed(positionMs: Long): Long = targetMs ?: positionMs

    /** Whether [positionMs] is close enough to the target to stop showing it. */
    fun landed(positionMs: Long): Boolean {
        val target = targetMs ?: return true
        return abs(positionMs - target) <= LANDED_WITHIN_MS
    }

    companion object {
        const val LANDED_WITHIN_MS = 1_500L
        const val SETTLE_TIMEOUT_MS = 2_500L
    }
}

/** A seek step for the seek thumbnail's chip: "+10 s", "+30 s", "+1 min", "+2 min"; back with a minus sign. */
internal fun seekStepLabel(stepMs: Long): String {
    val sign = if (stepMs < 0) "−" else "+"
    val seconds = abs(stepMs) / 1_000
    return if (seconds >= 60 && seconds % 60 == 0L) "$sign${seconds / 60} min" else "$sign$seconds s"
}
