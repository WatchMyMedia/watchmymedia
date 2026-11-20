package media.watchmy.client.mobile.player

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import media.watchmy.client.ui.WmmColors

/*
 * What the phone's two players share: the file
 * player and the Live TV player draw in a PlayerShell, hide their controls
 * with AutoHideControls, keep their controls inside playerControlsInsets, and
 * go into picture in picture with rememberPictureInPicture (PlayerWindow.kt).
 */

/**
 * What a player's controls keep clear of: the system bars while they show,
 * and the camera cutout always, which on a phone held sideways is at a side.
 * The video itself draws under both.
 */
internal val playerControlsInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * A player's frame: the whole screen, any touch counted as the viewer being
 * there ([onTouch]: the controls stay up, and Still watching waits), and a
 * keyboard's or remote's keys taken before the focused control while
 * [keysEnabled]. [keys] says what a key code does in this player. The shell
 * takes focus when it opens, since with nothing focused keys reach no one.
 */
@Composable
internal fun PlayerShell(
    keys: (Int) -> KeyboardAction?,
    keysEnabled: Boolean,
    onKey: (KeyboardAction) -> Unit,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    val touched by rememberUpdatedState(onTouch)
    val pressed by rememberUpdatedState(onKey)
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(WmmColors.Ground)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touched()
                }
            }
            .playerKeyboard(focus, keysEnabled, keys) { pressed(it) },
        content = content,
    )
}

/**
 * Hides [controls] [ControlsState.HIDE_DELAY_MS] after the later of the last
 * touch and the moment [autoHide] became true (so a long load doesn't hide
 * them as soon as the play starts), by calling [onHide]. A new [reset] (the
 * next file, another channel) starts over.
 */
@Composable
internal fun AutoHideControls(controls: ControlsState, autoHide: Boolean, reset: Any?, onHide: () -> Unit) {
    var since by remember(reset) { mutableLongStateOf(0L) }
    LaunchedEffect(autoHide, reset) { if (autoHide) since = playerNow() }
    val hide by rememberUpdatedState(onHide)
    LaunchedEffect(controls, autoHide, since, reset) {
        val at = controls.hideAtMs(autoHide, since) ?: return@LaunchedEffect
        delay((at - playerNow()).coerceAtLeast(0))
        hide()
    }
}

/** The clock the players' controls run on. */
internal fun playerNow() = SystemClock.uptimeMillis()
