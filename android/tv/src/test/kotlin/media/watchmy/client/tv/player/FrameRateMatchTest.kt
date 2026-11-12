package media.watchmy.client.tv.player

import android.hardware.display.DisplayManager
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

/**
 * Match frame rate: which display mode the TV asks for at each frame rate
 * ([matchingMode]), and that the player asks for it through its window and
 * gives the window's own preference back when it goes ([MatchFrameRate]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class FrameRateMatchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val uhd60 = ModeChoice(1, 3840, 2160, 60f)
    private val uhd5994 = ModeChoice(2, 3840, 2160, 59.94006f)
    private val uhd50 = ModeChoice(3, 3840, 2160, 50f)
    private val uhd48 = ModeChoice(4, 3840, 2160, 48f)
    private val uhd24 = ModeChoice(5, 3840, 2160, 24f)
    private val uhd23976 = ModeChoice(6, 3840, 2160, 23.976025f)
    private val uhd47952 = ModeChoice(7, 3840, 2160, 47.95205f)
    private val uhd30 = ModeChoice(8, 3840, 2160, 30f)
    private val uhd25 = ModeChoice(9, 3840, 2160, 25f)
    private val fhd24 = ModeChoice(10, 1920, 1080, 24f)
    private val streamer = listOf(uhd60, uhd5994, uhd50, uhd48, uhd24, uhd23976, uhd47952, uhd30, uhd25, fhd24)

    private fun pick(rate: Float, modes: List<ModeChoice> = streamer, current: ModeChoice = uhd60) = matchingMode(modes, current, rate)

    @Test fun `each frame rate gets a refresh rate that is a whole multiple of it`() {
        assertEquals(uhd23976, pick(23.976f))
        assertEquals(uhd24, pick(24f))
        assertEquals(uhd50, pick(25f))
        assertEquals(uhd5994, pick(29.97f))
        assertEquals(uhd60, pick(30f))
        assertEquals(uhd50, pick(50f))
        assertEquals(uhd5994, pick(59.94f))
        assertEquals(uhd60, pick(60f))
    }

    @Test fun `25 and 30 Hz modes are never picked, and 48 Hz only without 24`() {
        assertEquals(uhd50, pick(25f, current = uhd5994))
        assertEquals(uhd5994, pick(29.97f, current = uhd50))
        assertEquals(uhd48, pick(24f, modes = streamer - uhd24))
        assertEquals(uhd47952, pick(23.976f, modes = streamer - uhd23976))
        assertFalse(showsEvenly(25f, 25f, 0.0005f))
        assertFalse(showsEvenly(30f, 30f, 0.0005f))
        assertTrue(showsEvenly(23.976f, 23.976f, 0.0005f))
    }

    @Test fun `a mode that already fits stays, so the screen doesn't go dark for nothing`() {
        assertEquals(uhd47952, pick(23.976f, current = uhd47952))
        assertEquals(uhd5994, pick(59.94f, current = uhd5994))
        // 120 Hz shows 24p five times a frame.
        val uhd120 = ModeChoice(11, 3840, 2160, 120f)
        assertEquals(uhd120, pick(24f, modes = streamer + uhd120, current = uhd120))
    }

    @Test fun `the current size is kept`() {
        val fhd60 = ModeChoice(12, 1920, 1080, 60f)
        assertEquals(fhd24, pick(24f, modes = streamer + fhd60, current = fhd60))
        assertNull("no 1080p mode at 25 or 50", pick(25f, modes = streamer + fhd60, current = fhd60))
    }

    @Test fun `24 stands in for 23·976, and 60 for 59·94, only when nothing fits exactly`() {
        val tv = listOf(uhd60, uhd24, uhd50)
        assertEquals(uhd24, pick(23.976f, modes = tv))
        assertEquals("59.94 on a 60 Hz TV: no switch", uhd60, pick(59.94f, modes = tv))
        assertEquals(uhd23976, pick(23.976f, current = uhd24))
    }

    @Test fun `no mode for an unknown or odd rate`() {
        assertNull(pick(-1f))
        assertNull(pick(Float.NaN))
        assertNull(pick(17.5f, modes = listOf(uhd60, uhd50)))
    }

    @Test fun `the player asks its window for the mode and gives the window's own back on leaving`() {
        val display = compose.activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val now = display.mode
        fun mode(id: Int, rate: Float) = ShadowDisplayManager.ModeBuilder.modeBuilder(id).setWidth(now.physicalWidth).setHeight(now.physicalHeight).setRefreshRate(rate).build()
        val id = now.modeId
        ShadowDisplayManager.setSupportedModes(Display.DEFAULT_DISPLAY, mode(id, 60f), mode(id + 1, 23.976f), mode(id + 2, 50f))
        val preferred = { compose.activity.window.attributes.preferredDisplayModeId }

        var shown by mutableStateOf(true)
        var rate by mutableStateOf<Float?>(null)
        var enabled by mutableStateOf(true)
        compose.setContent { if (shown) MatchFrameRate(rate, enabled) }
        compose.runOnIdle { assertEquals("nothing until the rate is known", 0, preferred()) }

        rate = 23.976f
        compose.runOnIdle { assertEquals(id + 1, preferred()) }
        rate = null
        compose.runOnIdle { assertEquals("the next episode loading keeps the mode", id + 1, preferred()) }
        rate = 25f
        compose.runOnIdle { assertEquals(id + 2, preferred()) }
        shown = false
        compose.runOnIdle { assertEquals("left: the window's own preference again", 0, preferred()) }

        enabled = false
        rate = 23.976f
        shown = true
        compose.runOnIdle { assertEquals("Match frame rate off", 0, preferred()) }
    }
}
