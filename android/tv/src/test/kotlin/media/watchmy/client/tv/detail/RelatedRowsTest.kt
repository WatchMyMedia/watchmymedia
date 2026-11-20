package media.watchmy.client.tv.detail

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.RELATED_API_LEVEL
import media.watchmy.client.model.RELEASE_ORDER
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.tv.TvApp
import media.watchmy.client.tv.TvTheme
import media.watchmy.client.tv.quietTvFakes
import media.watchmy.client.ui.LocalAppServices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The movie and show pages' last rows on the TV,
 * on the fakes: ▼
 * from the cast or About the show reaches the rows at their first card, ▲
 * goes back, OK opens the title, and TalkBack hears each row's name.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class RelatedRowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L
    private val services = FakeAppServices(tv = true)
    private val opened = mutableListOf<Long>()

    @Before fun start() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun stop() {
        fakeLatencyMs = latency
    }

    private fun movie(id: Long = 500) {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                TvTheme { MovieScreen(id, onPlay = {}, onBack = {}, onOpenMovie = { opened += it }) }
            }
        }
        compose.waitForIdle()
    }

    private fun show(id: Long = 100) {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                TvTheme { ShowPageScreen(id, ShowArrival.Show, onPlay = {}, onOpenShow = { opened += it }) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `the movie page ends with its collection in release order, then More like this`() {
        movie()
        compose.onNode(isHeading() and hasText(FakeData.COLLECTION_NAME) and hasText(RELEASE_ORDER)).assertExists()
        compose.onNode(isHeading() and hasText("More like this")).assertExists()
        // TalkBack names the row focus comes into, and reads each card's title and year.
        compose.onNode(hasContentDescription(FakeData.COLLECTION_NAME)).assertExists()
        compose.onNode(hasContentDescription("More like this")).assertExists()
        val card = compose.onNode(hasClickAction() and hasText("Cold Harbour Road")).fetchSemanticsNode().config
        assertEquals(listOf("Cold Harbour Road", "2022"), card.getOrNull(SemanticsProperties.Text)?.map { it.text })
    }

    @Test fun `down from the cast reaches the collection's first card, then More like this, and up goes back`() {
        movie()
        assertEquals("Play", focused())
        repeat(3) { press(Key.DirectionDown) }
        assertEquals("the cast takes focus", "Hollis Kade", focused())
        press(Key.DirectionDown)
        assertEquals("Cold Harbour Road", focused())
        compose.onNode(isFocused()).assertIsDisplayed()
        press(Key.DirectionDown)
        assertEquals("More like this, at its first card", "The Paper Kite", focused())
        compose.onNode(isFocused()).assertIsDisplayed()
        press(Key.DirectionRight)
        assertEquals("Ninety Fathoms", focused())
        press(Key.DirectionUp)
        assertEquals("Cold Harbour Road", focused())
        press(Key.DirectionDown)
        assertEquals("the row gives focus back to the card it had", "Ninety Fathoms", focused())
        press(Key.DirectionCenter)
        assertEquals("OK opens the film", listOf(502L), opened)
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        assertTrue("${focused()} is in the cast", focused() in listOf("Hollis Kade", "Mira Okonjo"))
    }

    @Test fun `down from About the show reaches More like this, and up goes back to About`() {
        show()
        val about = listOf("Notifications on", "Mark show watched", "Show missing episodes", "Cast & details")
        var presses = 0
        while (focused() !in about && presses++ < 6) press(Key.DirectionDown)
        assertTrue("${focused()} is in About the show", focused() in about)
        press(Key.DirectionDown)
        assertEquals(FakeData.showRelated(100).single().items.first().title, focused())
        compose.onNode(isFocused()).assertIsDisplayed()
        press(Key.DirectionCenter)
        assertEquals(listOf(FakeData.showRelated(100).single().items.first().id), opened)
        press(Key.DirectionUp)
        assertTrue("${focused()} is in About the show", focused() in about)
    }

    @Test fun `Back from the title a card opened puts focus back on that card`() {
        val quiet = quietTvFakes()
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides quiet) { TvApp(deepLink = "watchmymedia://movies/500") }
        }
        compose.waitForIdle()
        assertEquals("Play", focused())
        repeat(5) { press(Key.DirectionDown) }
        assertEquals("The Paper Kite", focused())
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertEquals("An Honest Winter", focused())
        press(Key.DirectionCenter)
        compose.onNode(hasText("Movies › An Honest Winter")).assertExists()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals("not the row's first card", "An Honest Winter", focused())
        compose.onNode(isFocused()).assertIsDisplayed()
    }

    @Test fun `an older server's pages end as before`() {
        runBlocking { services.servers.select(LivingRoom.copy(apiLevel = RELATED_API_LEVEL - 1)) }
        movie()
        compose.onNodeWithText("Versions").assertExists()
        compose.onNodeWithText("More like this").assertDoesNotExist()
        compose.onNodeWithText(FakeData.COLLECTION_NAME).assertDoesNotExist()
    }

    @Test fun `a title nothing is like ends as before`() {
        movie(FakeData.unlike.first { it >= 500 })
        compose.onNodeWithText("Versions").assertExists()
        compose.onNode(hasText("More like this")).assertDoesNotExist()
    }

    private fun focused(): String {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull { it.text.isNotBlank() && !it.text.equals("New", ignoreCase = true) }?.text
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            ?: error("The focused element has no text")
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }
}
