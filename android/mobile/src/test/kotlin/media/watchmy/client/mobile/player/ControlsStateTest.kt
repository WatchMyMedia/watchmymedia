package media.watchmy.client.mobile.player

import media.watchmy.client.model.PlayerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsStateTest {
    @Test
    fun `controls hide 3 s after the last touch while playing`() {
        val c = ControlsState(visible = true, lastInputMs = 10_000)
        assertEquals(13_000L, c.hideAtMs(autoHide = true, allowedSinceMs = 0))
        assertEquals(15_000L, c.touched(12_000).hideAtMs(autoHide = true, allowedSinceMs = 0))
    }

    @Test
    fun `a long load does not hide them the moment playback starts`() {
        val c = ControlsState(visible = true, lastInputMs = 1_000)
        assertEquals(9_000L, c.hideAtMs(autoHide = true, allowedSinceMs = 6_000))
    }

    @Test
    fun `hidden controls and controls that may not hide have no deadline`() {
        assertNull(ControlsState(visible = false, lastInputMs = 0).hideAtMs(autoHide = true, allowedSinceMs = 0))
        assertNull(ControlsState(visible = true, lastInputMs = 0).hideAtMs(autoHide = false, allowedSinceMs = 0))
    }

    @Test
    fun `they hide by themselves only while playing or buffering, not scrubbing, with the picker closed`() {
        assertTrue(ControlsState.autoHides(PlayerStatus.Playing, scrubbing = false, pickerOpen = false))
        assertTrue(ControlsState.autoHides(PlayerStatus.Buffering, scrubbing = false, pickerOpen = false))
        assertFalse(ControlsState.autoHides(PlayerStatus.Paused, scrubbing = false, pickerOpen = false))
        assertFalse(ControlsState.autoHides(PlayerStatus.Loading, scrubbing = false, pickerOpen = false))
        assertFalse(ControlsState.autoHides(PlayerStatus.Ended, scrubbing = false, pickerOpen = false))
        assertFalse(ControlsState.autoHides(PlayerStatus.Playing, scrubbing = true, pickerOpen = false))
        assertFalse(ControlsState.autoHides(PlayerStatus.Playing, scrubbing = false, pickerOpen = true))
    }

    @Test
    fun `a tap toggles them and counts as a touch`() {
        val shown = ControlsState(visible = false, lastInputMs = 0).toggled(5_000)
        assertTrue(shown.visible)
        assertEquals(8_000L, shown.hideAtMs(autoHide = true, allowedSinceMs = 0))
        assertFalse(shown.toggled(6_000).visible)
    }
}
