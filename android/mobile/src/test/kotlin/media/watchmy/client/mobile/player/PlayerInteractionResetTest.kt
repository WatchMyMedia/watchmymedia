package media.watchmy.client.mobile.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerInteractionResetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a first tap in the new episode does not continue the previous seek burst`() {
        val file = mutableLongStateOf(1L)
        val seeks = mutableListOf<Long>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val id = file.longValue
            val config = LocalViewConfiguration.current
            // Keep this stable across recompositions; only the file key may cancel input.
            val timeoutConfig = remember(config) {
                object : ViewConfiguration by config { override val doubleTapTimeoutMillis = 5_000L }
            }
            CompositionLocalProvider(LocalViewConfiguration provides timeoutConfig) {
                Box(Modifier.size(400.dp, 200.dp)) {
                    TouchLayer(rememberSeekForFile(id), onTap = {}, onSeek = { seeks += id }, modifier = Modifier.testTag("video"))
                }
            }
        }
        compose.onNodeWithTag("video").performTouchInput { doubleClick(centerRight) }
        compose.runOnIdle { assertEquals(listOf(1L), seeks); file.longValue = 2L }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("video").performTouchInput { click(centerRight) }
        settleTap()
        compose.runOnIdle {
            assertEquals(listOf(1L), seeks)
        }
    }

    @Test fun `a pending tap from the old episode cannot toggle the new controls`() {
        val file = mutableLongStateOf(1L)
        val taps = mutableListOf<Long>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val id = file.longValue
            val config = LocalViewConfiguration.current
            // Keep this stable across recompositions; only the file key may cancel input.
            val timeoutConfig = remember(config) {
                object : ViewConfiguration by config { override val doubleTapTimeoutMillis = 5_000L }
            }
            CompositionLocalProvider(LocalViewConfiguration provides timeoutConfig) {
                Box(Modifier.size(400.dp, 200.dp)) {
                    TouchLayer(rememberSeekForFile(id), onTap = { taps += id }, onSeek = {}, modifier = Modifier.testTag("video"))
                }
            }
        }
        compose.onNodeWithTag("video").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(emptyList<Long>(), taps); file.longValue = 2L }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        settleTap()
        compose.runOnIdle { assertEquals(emptyList<Long>(), taps) }
        compose.onNodeWithTag("video").performTouchInput { click(center) }
        settleTap()
        compose.runOnIdle { assertEquals(listOf(2L), taps) }
    }

    private fun settleTap() {
        compose.mainClock.advanceTimeBy(6_000)
        ShadowLooper.idleMainLooper(6, TimeUnit.SECONDS)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

}
