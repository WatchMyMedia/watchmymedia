package media.watchmy.client.mobile.player

import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import media.watchmy.client.ui.PlayerKey
import media.watchmy.client.ui.playerKey

/** What a key does in the phone player. */
internal sealed interface KeyboardAction {
    data object TogglePlayPause : KeyboardAction

    data class SeekBy(val deltaMs: Long) : KeyboardAction
}

/**
 * The phone player's keys, from the TV's mapping ([playerKey]): Space and K
 * play and pause, the arrows and J and L jump 10 s as a double tap does.
 * Null leaves a key to the focused control and the system: Enter clicks,
 * Tab and Up and Down move focus, Back leaves, and the media session takes
 * the other media keys.
 */
internal fun keyboardAction(key: PlayerKey): KeyboardAction? = when (key) {
    PlayerKey.PlayPause -> KeyboardAction.TogglePlayPause
    PlayerKey.Left, PlayerKey.Rewind -> KeyboardAction.SeekBy(-DoubleTapSeek.STEP_MS)
    PlayerKey.Right, PlayerKey.FastForward -> KeyboardAction.SeekBy(DoubleTapSeek.STEP_MS)
    else -> null
}

/**
 * Takes the player's keys ([keyboardAction]) from a keyboard or a remote
 * while [enabled], before the focused control does, so Space never clicks a
 * button as well. The player is focusable through [focus], which it requests
 * when it opens: with nothing focused, keys reach no one.
 */
internal fun Modifier.playerKeyboard(focus: FocusRequester, enabled: Boolean, onAction: (KeyboardAction) -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val action = keyboardAction(playerKey(event.nativeKeyEvent.keyCode))
        if (!enabled || action == null) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) onAction(action)
        // The key-up too, so a focused control never sees half a press.
        true
    }
        .focusRequester(focus)
        .focusable()
