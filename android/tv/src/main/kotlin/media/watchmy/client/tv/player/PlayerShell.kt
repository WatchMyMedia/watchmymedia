package media.watchmy.client.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import media.watchmy.client.ui.onBack

/** Controls hide after this long without a press while playing. */
internal const val HIDE_AFTER_MS = 5_000L

/**
 * What the TV's two players, the file player and Live TV's, keep about
 * the screen itself (PLAN-ANDROID-REVIEW.md §5.3): whether the viewer is
 * leaving, their presses, the focus move an action asked for, and where
 * focus is. [PlayerShell] draws the frame they share.
 */
@Stable
internal class PlayerShellState {
    /** The screen itself: focus rests here, with the controls hidden, so keys still arrive. */
    val root = FocusRequester()

    /**
     * Set once the viewer leaves. The screen stays composed while it fades
     * out, and the screen underneath takes focus meanwhile: the player must
     * not take it back, and keys pressed then belong to the screen underneath
     * (a quick second Back leaves it too).
     */
    var leaving by mutableStateOf(false)
        private set

    /** Something inside the player has focus. */
    var treeHasFocus by mutableStateOf(false)
        private set

    /** The screen itself has focus: nothing on it does. */
    var rootFocused by mutableStateOf(false)
        private set

    /** The viewer's presses, counted: each one starts the controls' wait to hide again. */
    var presses by mutableIntStateOf(0)
        private set

    /** A focus move an action asked for, honoured once its target is composed. */
    var wanted by mutableStateOf<FocusRequester?>(null)

    fun noteInput() {
        presses++
    }

    /** Marks the viewer as leaving; false when they already were, so only the first leave acts. */
    fun leave(): Boolean {
        if (leaving) return false
        leaving = true
        return true
    }

    internal fun focusChanged(state: FocusState) {
        treeHasFocus = state.hasFocus
        rootFocused = state.isFocused
    }
}

@Composable
internal fun rememberPlayerShellState(): PlayerShellState = remember { PlayerShellState() }

/**
 * The frame both TV players draw in, written once:
 *
 * - Back, as the remote's key or as a back callback ([onBack], until the
 *   viewer leaves), and the rest of the remote's keys before anything
 *   inside sees them ([onKey]);
 * - focus: whenever an action asks for a move ([PlayerShellState.wanted]),
 *   focus is lost, or anything in [focusKeys] changes, focus goes where
 *   [focusTarget] says, given what was asked for; while not [arbitrate]
 *   (a dialog of the player's own is open), nothing moves and the request
 *   waits;
 * - the controls hide by [onHide] after [hideAfterMs] without a press
 *   while [autoHide];
 * - the screen stays on while [keepScreenOn].
 */
@Composable
internal fun PlayerShell(
    shell: PlayerShellState,
    keepScreenOn: Boolean,
    autoHide: Boolean,
    onHide: () -> Unit,
    onBack: () -> Unit,
    onKey: (KeyEvent) -> Boolean,
    focusTarget: (wanted: FocusRequester?) -> FocusRequester?,
    focusKeys: Array<out Any?>,
    modifier: Modifier = Modifier,
    arbitrate: Boolean = true,
    hideAfterMs: Long = HIDE_AFTER_MS,
    onFocusChanged: (FocusState) -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    KeepScreenOn(keepScreenOn)
    val hide by rememberUpdatedState(onHide)
    LaunchedEffect(autoHide, shell.presses, hideAfterMs) {
        if (!autoHide) return@LaunchedEffect
        delay(hideAfterMs)
        hide()
    }
    val target by rememberUpdatedState(focusTarget)
    LaunchedEffect(shell.wanted, shell.treeHasFocus, shell.rootFocused, shell.leaving, arbitrate, *focusKeys) {
        if (shell.leaving || !arbitrate) return@LaunchedEffect
        target(shell.wanted)?.let { settleFocus(it) }
        shell.wanted = null
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .onBack(enabled = !shell.leaving, onBack = onBack)
            .onPreviewKeyEvent { !shell.leaving && onKey(it) }
            .onFocusChanged {
                shell.focusChanged(it)
                onFocusChanged(it)
            }
            .focusRequester(shell.root)
            .focusable(),
        content = content,
    )
}

/** Focuses [target] as soon as it is composed: a control that is coming into view may be a frame or two behind. */
private suspend fun settleFocus(target: FocusRequester) {
    repeat(4) {
        if (runCatching { target.requestFocus() }.getOrDefault(false)) return
        withFrameNanos { }
    }
}

/** Keeps the TV from dimming or starting its screensaver while [on], and puts back what was there before. */
@Composable
internal fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        val before = view.keepScreenOn
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = before }
    }
}
