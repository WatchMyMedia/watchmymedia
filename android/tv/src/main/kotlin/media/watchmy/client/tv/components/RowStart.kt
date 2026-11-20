package media.watchmy.client.tv.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Where a row of cards starts. Getting back to a
 * row's first card is a run of ◀: the key held, or pressed again within
 * [RUN_MS] of the last. A run stops at the first card instead of going on
 * into the rail: the row gives [offset] and springs back, and the rail's
 * active section pulses ([RailHint]). A ◀ pressed by itself leaves the row
 * as it always did, so a row that opens on its first card still reaches the
 * rail in one press.
 */
@Stable
class RowStart internal constructor(private val scope: CoroutineScope, private val give: Float, private val onStopped: () -> Unit) {
    private val shift = Animatable(0f)
    private var lastLeft: Long? = null
    private var inRun = false

    /** How far the row is pushed aside, in px, while it gives. */
    val offset: Float get() = shift.value

    /** Notes each ◀ on its way to the row's cards; never takes the key. */
    internal fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) {
            if (event.key == Key.DirectionLeft) inRun = false
            return false
        }
        if (event.key != Key.DirectionLeft) {
            lastLeft = null
            inRun = false
            return false
        }
        val at = event.nativeKeyEvent.eventTime
        inRun = event.nativeKeyEvent.repeatCount > 0 || lastLeft?.let { at - it < RUN_MS } == true
        lastLeft = at
        return false
    }

    /** Focus is about to leave the row in [direction]: true when it must stay, and the row gives. */
    internal fun refuses(direction: FocusDirection): Boolean {
        if (!inRun || direction != FocusDirection.Left) return false
        onStopped()
        scope.launch {
            // A held key asks again every repeat: each ask takes over from the last, so the row stays aside until the key is let go.
            shift.animateTo(give, tween(GIVE_MS, easing = FastOutSlowInEasing))
            shift.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
        }
        return true
    }

    companion object {
        /** A ◀ this soon after the last is part of the same run. */
        const val RUN_MS = 400L
        private const val GIVE_MS = 130
    }
}

@Composable
fun rememberRowStart(): RowStart {
    val scope = rememberCoroutineScope()
    val give = with(LocalDensity.current) { 34.dp.toPx() }
    val hint = LocalRailHint.current
    return remember(scope, give, hint) { RowStart(scope, give) { hint?.stopped() } }
}

/**
 * Stops a run of ◀ at the row's first card. Goes on a row's scrolling list
 * before its `focusRestorer`, whose own leaving it keeps.
 */
fun Modifier.rowStart(start: RowStart): Modifier = this
    .graphicsLayer { translationX = start.offset }
    .onPreviewKeyEvent(start::onKey)
    .focusProperties {
        val leave = onExit
        onExit = { if (start.refuses(requestedFocusDirection)) cancelFocusChange() else leave(this) }
    }

/** Rows tell the rail that a run of ◀ stopped at their first card, and its active section shows where the next ◀ goes. */
@Stable
class RailHint internal constructor() {
    /** How many runs have stopped; the rail pulses at each change. */
    var stops by mutableIntStateOf(0)
        private set

    fun stopped() {
        stops++
    }
}

/** The screen's [RailHint], from [TvScaffold]; null on a screen without a rail. */
@Suppress("ComposeCompositionLocalUsage")
internal val LocalRailHint = staticCompositionLocalOf<RailHint?> { null }
