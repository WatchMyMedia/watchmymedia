package media.watchmy.client.tv.search

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.tv.TvApp
import media.watchmy.client.ui.LocalAppServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import media.watchmy.client.tv.quietTvFakes

/** Back from a search result puts focus back on it, not on the keyboard (PLAN-ANDROID-REVIEW.md §3.2.3). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SearchFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun start() {
        fakeLatencyMs = 0
        // Without tonight's reminders, whose banner would take Back first (QuietFakes.kt).
        val services = quietTvFakes()
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) { TvApp() }
        }
        compose.waitForIdle()
        press(Key.DirectionLeft)
        repeat(6) { if (focused() != "Search") press(Key.DirectionUp) }
        assertEquals("Search", focused())
        press(Key.DirectionCenter)
        // Search takes focus onto its keyboard; if it stayed on the rail, move across.
        if (focused() == "Search") press(Key.DirectionRight)
    }

    @Test
    fun `Back from a result goes back to it`() {
        assertEquals("the keyboard's first letter", "q", focused())
        // "har" on the keyboard: h (down a row, five along), a, then r (up a row, three along).
        press(Key.DirectionDown)
        repeat(5) { press(Key.DirectionRight) }
        press(Key.DirectionCenter)
        repeat(5) { press(Key.DirectionLeft) }
        press(Key.DirectionCenter)
        press(Key.DirectionUp)
        repeat(3) { press(Key.DirectionRight) }
        press(Key.DirectionCenter)
        // The search waits a moment after the last key.
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        compose.waitForIdle()

        // Onto the result (the keys and the results sit side by side; where ▶ lands depends on the row).
        compose.onAllNodesWithText("Harbor Lights").onFirst().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        val result = focused()
        assertEquals("Harbor Lights", result)
        press(Key.DirectionCenter)
        assertNotEquals("the show page took focus", result, runCatching { focused() }.getOrNull())

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(result, focused())
    }

    private fun focused(): String {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull { it.text.isNotBlank() && !BADGES.matches(it.text) }?.text
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
            ?: error("The focused element has no text")
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private companion object {
        /** Quality and New badges come before a poster's title. */
        val BADGES = Regex("4K|1080p|720p|SD|HDR|HDR10|New|Dolby Vision", RegexOption.IGNORE_CASE)
    }
}
