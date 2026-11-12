package media.watchmy.client.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerKeysTest {
    @Test
    fun mapsTheRemotesKeys() {
        assertEquals(PlayerKey.Left, playerKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PlayerKey.Centre, playerKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(PlayerKey.Centre, playerKey(KeyEvent.KEYCODE_ENTER))
        assertEquals(PlayerKey.PlayPause, playerKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(PlayerKey.Rewind, playerKey(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(PlayerKey.FastForward, playerKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(PlayerKey.Back, playerKey(KeyEvent.KEYCODE_BACK))
        assertEquals(PlayerKey.Other, playerKey(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun mapsAKeyboardsKeys() {
        assertEquals(PlayerKey.PlayPause, playerKey(KeyEvent.KEYCODE_SPACE))
        assertEquals(PlayerKey.PlayPause, playerKey(KeyEvent.KEYCODE_K))
        assertEquals(PlayerKey.Rewind, playerKey(KeyEvent.KEYCODE_J))
        assertEquals(PlayerKey.FastForward, playerKey(KeyEvent.KEYCODE_L))
        assertEquals(PlayerKey.Left, playerKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PlayerKey.Other, playerKey(KeyEvent.KEYCODE_A))
    }
}
