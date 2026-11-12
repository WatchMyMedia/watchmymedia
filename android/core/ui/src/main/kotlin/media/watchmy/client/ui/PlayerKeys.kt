package media.watchmy.client.ui

import android.view.KeyEvent

/** A remote's or a keyboard's keys, as both players see them. */
enum class PlayerKey { Left, Right, Up, Down, Centre, Back, PlayPause, Play, Pause, Rewind, FastForward, Stop, Next, Captions, AudioTrack, Menu, Info, Other }

/**
 * The player key a key code is: the TV remote's D-pad and media keys, and a
 * keyboard's, where Space or K plays and pauses (as in the web player) and
 * J and L jump back and on beside the arrows.
 */
fun playerKey(keyCode: Int): PlayerKey = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT -> PlayerKey.Left
    KeyEvent.KEYCODE_DPAD_RIGHT -> PlayerKey.Right
    KeyEvent.KEYCODE_DPAD_UP -> PlayerKey.Up
    KeyEvent.KEYCODE_DPAD_DOWN -> PlayerKey.Down
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> PlayerKey.Centre
    KeyEvent.KEYCODE_BACK -> PlayerKey.Back
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K -> PlayerKey.PlayPause
    KeyEvent.KEYCODE_MEDIA_PLAY -> PlayerKey.Play
    KeyEvent.KEYCODE_MEDIA_PAUSE -> PlayerKey.Pause
    KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD, KeyEvent.KEYCODE_J -> PlayerKey.Rewind
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD, KeyEvent.KEYCODE_L -> PlayerKey.FastForward
    KeyEvent.KEYCODE_MEDIA_STOP -> PlayerKey.Stop
    KeyEvent.KEYCODE_MEDIA_NEXT -> PlayerKey.Next
    KeyEvent.KEYCODE_CAPTIONS -> PlayerKey.Captions
    KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> PlayerKey.AudioTrack
    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> PlayerKey.Menu
    KeyEvent.KEYCODE_INFO -> PlayerKey.Info
    else -> PlayerKey.Other
}
