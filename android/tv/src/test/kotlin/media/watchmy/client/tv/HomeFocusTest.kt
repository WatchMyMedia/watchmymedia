package media.watchmy.client.tv

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.fakeLatencyMs
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

/**
 * Moving around the TV app with the D-pad, on the fakes at a 1080p TV's
 * size: the rail hands focus back to where it was, Home's screens page on
 * ▲ ▼ and restore each row's card (PLAN-HOME.md §7.3), and Back from a
 * page puts focus back on the card that opened it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class HomeFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val home = FakeData.home()

    @Before
    fun start() {
        fakeLatencyMs = 0
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices(tv = true)) { TvApp() }
        }
        compose.waitForIdle()
    }

    @Test
    fun `Home starts on the hero, and right from the rail goes back to the card left`() {
        assertEquals("Play", focused())
        press(Key.DirectionLeft)
        assertEquals("The rail opens on its active section", "Home", focused())
        press(Key.DirectionRight)
        assertEquals("Play", focused())

        press(Key.DirectionDown)
        val first = home.continueWatching.first().title
        assertEquals(first, focused())
        press(Key.DirectionLeft)
        assertEquals("Home", focused())
        press(Key.DirectionRight)
        assertEquals("Right from the rail puts focus back on the first card", first, focused())
    }

    @Test
    fun `Down at a row pages to the next row's screen, and up comes back to the card it had`() {
        press(Key.DirectionDown)
        assertEquals("Continue watching stays on the first screen with the hero", home.continueWatching.first().title, focused())
        press(Key.DirectionDown) // The first row screen: Live TV, as the fakes arrange it.
        val channel = focused()
        assertTrue("$channel is a channel card", channel.endsWith("Watch live"))
        press(Key.DirectionRight)
        val second = focused()
        assertNotEquals(channel, second)
        press(Key.DirectionDown) // The next row screen.
        assertTrue("${focused()} is in Recently added shows", focused() in home.recentlyAddedShows.map { it.title })
        compose.onNodeWithText("Row 3 of ", substring = true, useUnmergedTree = true).assertExists()
        press(Key.DirectionUp)
        assertEquals("The Live row comes back to the channel it had focus on", second, focused())
        press(Key.DirectionUp)
        assertEquals("Up from the first row screen returns to the first screen's row", home.continueWatching.first().title, focused())
    }

    @Test
    fun `Back from a row screen goes to the first screen before leaving Home`() {
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionDown) // Four screens in: a list row.
        compose.onNodeWithText("Row 4 of ", substring = true, useUnmergedTree = true).assertExists()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals("Back goes to the first screen, on its row", home.continueWatching.first().title, focused())
        compose.onNodeWithText("Row 1 of ", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `The last screen offers Edit rows and Back to the top`() {
        repeat(12) { press(Key.DirectionDown) } // More presses than rows: the end screen swallows the rest.
        assertEquals("Edit rows", focused())
        compose.onNodeWithText("That's everything on Home").assertExists()
        press(Key.DirectionRight)
        assertEquals("Back to the top", focused())
        press(Key.DirectionCenter)
        assertEquals("Back to the top puts focus on the first screen's row", home.continueWatching.first().title, focused())
    }

    @Test
    fun `Back from a show puts focus back on its card`() {
        press(Key.DirectionDown)
        press(Key.DirectionDown) // Live TV now sits below Continue watching.
        press(Key.DirectionDown)
        // Down from a wide card lands on the poster below it, which needn't be the row's first.
        val shows = home.recentlyAddedShows.map { it.title }
        val landed = focused()
        assertTrue("$landed is in Recently added shows", landed in shows)
        press(Key.DirectionRight)
        val card = shows[shows.indexOf(landed) + 1]
        assertEquals(card, focused())

        press(Key.DirectionCenter)
        compose.onNodeWithText(card, useUnmergedTree = true).assertExists()
        assertNotEquals("The show page took focus", card, focused())

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(card, focused())
        press(Key.DirectionLeft)
        assertEquals("And the row is where it was", landed, focused())
    }

    /** The focused element's title: a card's first line, a button's label or a rail item's name. */
    private fun focused(): String {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull { it.text.isNotBlank() && !BADGES.matches(it.text) }?.text
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
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
