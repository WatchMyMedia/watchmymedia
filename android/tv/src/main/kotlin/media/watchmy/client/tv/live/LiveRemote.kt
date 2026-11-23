package media.watchmy.client.tv.live

import android.view.KeyEvent
import media.watchmy.client.ui.PlayerKey
import media.watchmy.client.ui.playerKey

internal enum class LivePanel { Channels, Quality, Number }
internal enum class LiveAction { Pass, Swallow, Controls, Channels, Quality, PreviousChannel, NextChannel, LastChannel, Record, Guide, TogglePause, Play, Pause, Leave }
internal enum class LiveBackAction { ClosePanel, KeepWatching, HideControls, Leave }

/** Numeric remotes and the on-screen keypad share the same exact channel matcher. */
internal fun liveNumberKey(code: Int): Char? = when (code) {
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (code - KeyEvent.KEYCODE_0)
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (code - KeyEvent.KEYCODE_NUMPAD_0)
    KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_NUMPAD_DOT, KeyEvent.KEYCODE_MINUS -> '.'
    KeyEvent.KEYCODE_DEL -> '\b'
    else -> null
}

/** D-pad movement belongs to visible controls/panels; hidden controls change channel, open On now and show the controls. */
internal fun liveKeyAction(code: Int, panel: LivePanel?, controls: Boolean, terminal: Boolean, prompt: Boolean): LiveAction {
    val key = playerKey(code)
    if (key == PlayerKey.Stop) return LiveAction.Leave
    // The remote's Guide opens the full guide from wherever the player is.
    if (code == KeyEvent.KEYCODE_GUIDE) return LiveAction.Guide
    if (terminal || prompt) return if (key in listOf(PlayerKey.Left, PlayerKey.Right, PlayerKey.Up, PlayerKey.Down, PlayerKey.Centre, PlayerKey.Back, PlayerKey.Other)) LiveAction.Pass else LiveAction.Swallow
    if (code == KeyEvent.KEYCODE_CHANNEL_UP) return LiveAction.NextChannel
    if (code == KeyEvent.KEYCODE_CHANNEL_DOWN) return LiveAction.PreviousChannel
    // Last goes back to the channel watched before this one; Record offers to record what is on.
    if (code == KeyEvent.KEYCODE_LAST_CHANNEL) return LiveAction.LastChannel
    if (code == KeyEvent.KEYCODE_MEDIA_RECORD) return LiveAction.Record
    if (panel != null) return when {
        key == PlayerKey.PlayPause -> LiveAction.TogglePause
        key == PlayerKey.Play -> LiveAction.Play
        key == PlayerKey.Pause -> LiveAction.Pause
        key in listOf(PlayerKey.Rewind, PlayerKey.FastForward, PlayerKey.Next) -> LiveAction.Swallow
        else -> LiveAction.Pass
    }
    return when {
        code == KeyEvent.KEYCODE_CHANNEL_UP -> LiveAction.NextChannel
        code == KeyEvent.KEYCODE_CHANNEL_DOWN -> LiveAction.PreviousChannel
        key == PlayerKey.PlayPause -> LiveAction.TogglePause
        key == PlayerKey.Play -> LiveAction.Play
        key == PlayerKey.Pause -> LiveAction.Pause
        key == PlayerKey.Info -> LiveAction.Controls
        key == PlayerKey.Menu -> LiveAction.Quality
        key == PlayerKey.Up && !controls -> LiveAction.NextChannel
        key == PlayerKey.Down && !controls -> LiveAction.PreviousChannel
        key == PlayerKey.Left && !controls -> LiveAction.Channels
        key == PlayerKey.Centre && !controls -> LiveAction.Controls
        // ▶ does nothing while the controls are hidden: ◀ opens On now, and there is nothing to its right.
        key == PlayerKey.Right && !controls -> LiveAction.Swallow
        key in listOf(PlayerKey.Rewind, PlayerKey.FastForward, PlayerKey.Next, PlayerKey.Captions, PlayerKey.AudioTrack) -> LiveAction.Swallow
        else -> LiveAction.Pass
    }
}

/** Back closes overlays first, then hides controls, then releases the tuner and leaves. */
internal fun liveBackAction(panel: LivePanel?, controls: Boolean, terminal: Boolean, prompt: Boolean): LiveBackAction = when {
    terminal -> LiveBackAction.Leave
    prompt -> LiveBackAction.KeepWatching
    panel != null -> LiveBackAction.ClosePanel
    controls -> LiveBackAction.HideControls
    else -> LiveBackAction.Leave
}
