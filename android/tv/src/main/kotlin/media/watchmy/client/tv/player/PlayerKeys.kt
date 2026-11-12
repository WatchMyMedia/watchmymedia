package media.watchmy.client.tv.player

import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.ui.PlayerKey

/** What is over the video and takes the remote. */
internal enum class Overlay { None, UpNext, Picker, StillWatching, Message }

/** The overlay up now, most important first: an admin's stop or a fatal error, Still watching, the picker, the Next Episode card. */
internal fun overlayOf(state: PlayerUiState, picker: PickerSection?): Overlay = when {
    state.stopMessage != null || state.status == PlayerStatus.Error -> Overlay.Message
    state.askStillWatching -> Overlay.StillWatching
    picker != null -> Overlay.Picker
    state.upNext != null -> Overlay.UpNext
    else -> Overlay.None
}

/** Where focus is while no overlay is up. [Root] is the screen itself (controls hidden). */
internal enum class FocusArea { Root, Back, Skip, Scrubber, Buttons }

internal enum class PickerSection { Audio, Subtitles, Quality }

/** What a key press does. */
internal sealed interface KeyAction {
    /** The focused control takes it: focus moves, Centre clicks. */
    data object PassThrough : KeyAction

    /** Swallowed, so it neither moves focus nor clicks. */
    data object Swallow : KeyAction

    data object ShowControls : KeyAction

    data class Seek(val forward: Boolean) : KeyAction

    data object TogglePlayPause : KeyAction

    data object Play : KeyAction

    data object Pause : KeyAction

    data object PlayNext : KeyAction

    data class OpenPicker(val section: PickerSection) : KeyAction

    data object Leave : KeyAction
}

/**
 * The player's remote, for a key-down. Left and right seek unless focus is in
 * the button row (where they move along it); Centre plays or pauses unless a
 * control has focus; with the controls hidden, Up and Down only bring them
 * back. An overlay (the Next Episode card, the picker, a prompt) keeps the
 * D-pad for its own buttons; media keys still work under the card and the
 * picker. Back is not routed here: the screen takes it on key-up and asks
 * [backAction].
 */
internal fun routeKey(key: PlayerKey, overlay: Overlay, controlsVisible: Boolean, focus: FocusArea): KeyAction {
    if (key == PlayerKey.Back || key == PlayerKey.Other) return KeyAction.PassThrough
    if (overlay == Overlay.Message) return if (key == PlayerKey.Stop) KeyAction.Leave else if (isDpad(key)) KeyAction.PassThrough else KeyAction.Swallow
    val prompt = overlay == Overlay.StillWatching
    return when (key) {
        PlayerKey.Left, PlayerKey.Right -> when {
            overlay != Overlay.None -> KeyAction.PassThrough
            controlsVisible && (focus == FocusArea.Buttons || focus == FocusArea.Back) -> KeyAction.PassThrough
            else -> KeyAction.Seek(forward = key == PlayerKey.Right)
        }
        PlayerKey.Up, PlayerKey.Down -> if (overlay == Overlay.None && !controlsVisible) KeyAction.ShowControls else KeyAction.PassThrough
        PlayerKey.Centre -> when {
            overlay != Overlay.None -> KeyAction.PassThrough
            focus == FocusArea.Skip || focus == FocusArea.Buttons || focus == FocusArea.Back -> KeyAction.PassThrough
            else -> KeyAction.TogglePlayPause
        }
        PlayerKey.PlayPause -> if (prompt) KeyAction.Swallow else KeyAction.TogglePlayPause
        PlayerKey.Play -> if (prompt) KeyAction.Swallow else KeyAction.Play
        PlayerKey.Pause -> if (prompt) KeyAction.Swallow else KeyAction.Pause
        PlayerKey.Rewind, PlayerKey.FastForward ->
            if (overlay == Overlay.None || overlay == Overlay.UpNext) KeyAction.Seek(forward = key == PlayerKey.FastForward) else KeyAction.Swallow
        PlayerKey.Stop -> KeyAction.Leave
        PlayerKey.Next -> if (overlay == Overlay.UpNext) KeyAction.PlayNext else KeyAction.Swallow
        PlayerKey.Captions -> if (overlay == Overlay.None) KeyAction.OpenPicker(PickerSection.Subtitles) else KeyAction.Swallow
        PlayerKey.AudioTrack -> if (overlay == Overlay.None) KeyAction.OpenPicker(PickerSection.Audio) else KeyAction.Swallow
        PlayerKey.Menu -> if (overlay == Overlay.None) KeyAction.OpenPicker(PickerSection.Audio) else KeyAction.Swallow
        PlayerKey.Info -> if (overlay == Overlay.None) KeyAction.ShowControls else KeyAction.Swallow
        PlayerKey.Back, PlayerKey.Other -> KeyAction.PassThrough
    }
}

private fun isDpad(key: PlayerKey) = key == PlayerKey.Left || key == PlayerKey.Right || key == PlayerKey.Up || key == PlayerKey.Down || key == PlayerKey.Centre

/** What Back does: close what is open first, then hide the controls, then leave. */
internal enum class BackAction { Leave, KeepWatching, ClosePicker, WatchCredits, HideControls }

internal fun backAction(overlay: Overlay, controlsVisible: Boolean): BackAction = when (overlay) {
    Overlay.Message -> BackAction.Leave
    Overlay.StillWatching -> BackAction.KeepWatching
    Overlay.Picker -> BackAction.ClosePicker
    Overlay.UpNext -> BackAction.WatchCredits
    Overlay.None -> if (controlsVisible) BackAction.HideControls else BackAction.Leave
}
