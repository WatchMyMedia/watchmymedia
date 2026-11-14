package media.watchmy.client.mobile.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fit and fill and the immediate tap (the PlayerFitFill board,
 * PLAN-ANDROID-REVIEW §6.3), on a phone held sideways, 915 × 412.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FitFillTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `filling crops a 2·39 film's sides by 7% and a 16·9 episode's top and bottom by 20%`() {
        assertEquals(7, (fillCrop(2390f, 1000f, 915f, 412f)!! * 100).toInt())
        assertEquals(20, Math.round(fillCrop(1920f, 1080f, 915f, 412f)!! * 100))
        assertEquals("Fill · 20% of the picture is cropped at the edges", fitFillNote(true, fillCrop(1920f, 1080f, 915f, 412f)!!))
        assertEquals("Fit · the whole picture", fitFillNote(false, 0.2f))
    }

    @Test fun `fill is offered only when the picture isn't the screen's shape and its size is known`() {
        assertTrue(offersFill(fillCrop(2390f, 1000f, 915f, 412f)))
        assertFalse(offersFill(fillCrop(1920f, 1080f, 1280f, 720f)))
        assertNull(fillCrop(null, null, 915f, 412f))
        assertFalse(offersFill(null))
    }

    @Test fun `a tap in the middle shows the controls at once, while a tap at the side waits for a double tap`() {
        val taps = mutableListOf<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(400.dp, 200.dp)) {
                TouchLayer(rememberSeekForFile(1), onTap = { taps += "tap" }, onSeek = { taps += "seek" }, modifier = Modifier.testTag("video"))
            }
        }
        compose.onNodeWithTag("video").performTouchInput { click(center) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(listOf("tap"), taps) }

        // The middle's quick second tap is part of the first, not a second toggle.
        compose.onNodeWithTag("video").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithTag("video").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(listOf("tap", "tap"), taps) }

        compose.onNodeWithTag("video").performTouchInput { click(centerLeft) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(listOf("tap", "tap"), taps) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(listOf("tap", "tap", "tap"), taps) }
    }

    @Test fun `pinching out fills and pinching in fits, and isn't a tap`() {
        val pinches = mutableListOf<Boolean>()
        val taps = mutableListOf<Unit>()
        compose.setContent {
            Box(Modifier.size(400.dp, 200.dp)) {
                TouchLayer(rememberSeekForFile(1), onTap = { taps += Unit }, onSeek = {}, modifier = Modifier.testTag("video"), onPinch = { pinches += it })
            }
        }
        compose.onNodeWithTag("video").performTouchInput {
            pinch(center - Offset(20f, 0f), center - Offset(150f, 0f), center + Offset(20f, 0f), center + Offset(150f, 0f))
        }
        compose.onNodeWithTag("video").performTouchInput {
            pinch(center - Offset(150f, 0f), center - Offset(20f, 0f), center + Offset(150f, 0f), center + Offset(20f, 0f))
        }
        compose.waitForIdle()
        assertEquals(listOf(true, false), pinches)
        assertEquals(0, taps.size)
    }
}
