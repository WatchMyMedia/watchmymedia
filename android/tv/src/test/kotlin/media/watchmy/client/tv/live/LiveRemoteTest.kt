package media.watchmy.client.tv.live

import android.view.KeyEvent
import media.watchmy.client.tv.components.RailSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveRemoteTest {
    @Test fun `hidden controls use channel keys while visible controls keep normal focus movement`() {
        assertEquals(LiveAction.NextChannel, liveKeyAction(KeyEvent.KEYCODE_DPAD_UP, null, false, false, false))
        assertEquals(LiveAction.PreviousChannel, liveKeyAction(KeyEvent.KEYCODE_DPAD_DOWN, null, false, false, false))
        assertEquals(LiveAction.Channels, liveKeyAction(KeyEvent.KEYCODE_DPAD_LEFT, null, false, false, false))
        assertEquals(LiveAction.Controls, liveKeyAction(KeyEvent.KEYCODE_DPAD_CENTER, null, false, false, false))
        for (code in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)) {
            assertEquals(LiveAction.Pass, liveKeyAction(code, null, true, false, false))
        }
    }

    @Test fun `dedicated channel keys change channels with controls or panels open`() {
        for (panel in listOf(null, LivePanel.Channels, LivePanel.Quality)) for (controls in listOf(false, true)) {
            assertEquals(LiveAction.NextChannel, liveKeyAction(KeyEvent.KEYCODE_CHANNEL_UP, panel, controls, false, false))
            assertEquals(LiveAction.PreviousChannel, liveKeyAction(KeyEvent.KEYCODE_CHANNEL_DOWN, panel, controls, false, false))
        }
    }

    @Test fun `live transport never routes rewind fast forward or next episode to a file operation`() {
        for (panel in listOf(null, LivePanel.Channels, LivePanel.Quality)) {
            assertEquals(LiveAction.TogglePause, liveKeyAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, panel, true, false, false))
            assertEquals(LiveAction.Play, liveKeyAction(KeyEvent.KEYCODE_MEDIA_PLAY, panel, true, false, false))
            assertEquals(LiveAction.Pause, liveKeyAction(KeyEvent.KEYCODE_MEDIA_PAUSE, panel, true, false, false))
            for (code in listOf(KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT)) {
                assertEquals(LiveAction.Swallow, liveKeyAction(code, panel, true, false, false))
            }
        }
    }

    @Test fun `Stop always leaves and Back closes panel then controls then player`() {
        for (panel in listOf(null) + LivePanel.entries) for (terminal in listOf(false, true)) for (prompt in listOf(false, true)) {
            assertEquals(LiveAction.Leave, liveKeyAction(KeyEvent.KEYCODE_MEDIA_STOP, panel, true, terminal, prompt))
        }
        assertEquals(LiveBackAction.ClosePanel, liveBackAction(LivePanel.Channels, true, false, false))
        assertEquals(LiveBackAction.HideControls, liveBackAction(null, true, false, false))
        assertEquals(LiveBackAction.Leave, liveBackAction(null, false, false, false))
        assertEquals(LiveBackAction.Leave, liveBackAction(null, true, true, false))
        assertEquals(LiveBackAction.KeepWatching, liveBackAction(null, true, false, true))
    }

    @Test fun `numeric remotes support digits subchannels and delete without treating Back as delete`() {
        for (n in 0..9) {
            assertEquals('0' + n, liveNumberKey(KeyEvent.KEYCODE_0 + n))
            assertEquals('0' + n, liveNumberKey(KeyEvent.KEYCODE_NUMPAD_0 + n))
        }
        assertEquals('.', liveNumberKey(KeyEvent.KEYCODE_PERIOD))
        assertEquals('.', liveNumberKey(KeyEvent.KEYCODE_MINUS))
        assertEquals('\b', liveNumberKey(KeyEvent.KEYCODE_DEL))
        assertNull(liveNumberKey(KeyEvent.KEYCODE_BACK))
    }

    @Test fun `old servers keep established rail sections and Live TV is inserted only when available`() {
        assertEquals(listOf("Search", "Home", "TV Shows", "Movies", "Settings"), RailSection.shown(false).map { it.label })
        assertEquals(listOf("Search", "Home", "TV Shows", "Movies", "Live TV", "Settings"), RailSection.shown(true).map { it.label })
    }
}
