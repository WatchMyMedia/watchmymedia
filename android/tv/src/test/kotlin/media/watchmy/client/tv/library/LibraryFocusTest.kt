package media.watchmy.client.tv.library

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.tv.TvApp
import media.watchmy.client.ui.LocalAppServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import media.watchmy.client.tv.quietTvFakes

/**
 * Focus on the TV Shows page (PLAN-ANDROID-REVIEW.md §3.2.2): choosing a
 * filter leaves focus on its chip, where it used to be thrown back to the
 * top of the page, and Back from a show puts it on the show's card.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class LibraryFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val shows = FakeData.shows.map { it.title }.toSet()

    /** The filter and genre chips, and Decade. */
    private val chips = FILTERS + FakeData.shows.flatMap { it.genres } + "Decade"

    @Before
    fun start() {
        fakeLatencyMs = 0
        // Without tonight's reminders, whose banner would take Back first (QuietFakes.kt).
        val services = quietTvFakes()
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) { TvApp() }
        }
        compose.waitForIdle()
        // Home, then the rail's TV Shows.
        press(Key.DirectionLeft)
        repeat(6) { if (focused() != "TV Shows") press(Key.DirectionDown) }
        assertEquals("TV Shows", focused())
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
    }

    @Test
    fun `a filter chosen on its chip keeps focus on the chip`() {
        downTo("Ended")
        press(Key.DirectionCenter)
        assertEquals("focus stays on the chip, not the top of the page", "Ended", focused())
        press(Key.DirectionLeft)
        press(Key.DirectionCenter) // Continuing
        assertEquals("Continuing", focused())
    }

    @Test
    fun `Back from a show puts focus back on its card in the grid`() {
        downTo("Ended")
        press(Key.DirectionDown)
        val card = focused()
        assertTrue("$card is a show in the grid", card in shows)
        press(Key.DirectionRight)
        val next = focused()
        assertTrue("$next is a show in the grid", next in shows)

        press(Key.DirectionCenter)
        assertNotEquals("the show page took focus", next, runCatching { focused() }.getOrNull())
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(next, focused())
    }

    /** Down the page until the chips' row, to its start, then along it to [chip]. */
    private fun downTo(chip: String) {
        repeat(20) { if (focused() !in chips) press(Key.DirectionDown) }
        repeat(chips.size) { if (focused() != "All") press(Key.DirectionLeft) }
        repeat(FILTERS.size) { if (focused() != chip) press(Key.DirectionRight) }
        assertEquals(chip, focused())
    }

    private fun focused(): String {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull { it.text.isNotBlank() && !BADGES.matches(it.text) }?.text
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            // A chip that reads as a badge (4K, HDR).
            ?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
            ?: error("The focused element has no text")
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private companion object {
        val FILTERS = setOf("All", "Continuing", "Ended", "Unwatched")

        /** Quality and New badges come before a poster's title. */
        val BADGES = Regex("4K|1080p|720p|SD|HDR|HDR10|New|Dolby Vision", RegexOption.IGNORE_CASE)
    }
}
