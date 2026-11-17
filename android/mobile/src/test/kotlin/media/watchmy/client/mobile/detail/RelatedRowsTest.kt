package media.watchmy.client.mobile.detail

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.RELATED_API_LEVEL
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
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
 * The rows at the end of the phone's movie and show pages (PLAN-RELATED.md
 * §7; MovieRelated.dc.html, ShowRelated.dc.html), on the fakes: named as
 * headings, each card read as its title and year, a card opening its title,
 * and none from an older server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class RelatedRowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L
    private val services = FakeAppServices()
    private val movies = mutableListOf<Long>()
    private val shows = mutableListOf<Long>()

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restoreLatency() {
        fakeLatencyMs = latency
    }

    private fun movie(id: Long = 500) {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                WatchMyMediaPhoneTheme { MovieScreen(id, onPlay = {}, onBack = {}, onOpenMovie = { movies += it }, onOpenShow = { shows += it }) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `the movie page ends with its collection, then More like this, and a card opens its film`() {
        movie()
        compose.onNode(isHeading() and hasText(FakeData.COLLECTION_NAME)).performScrollTo()
        compose.onNode(isHeading() and hasText("More like this")).performScrollTo()
        val card = compose.onNode(hasClickAction() and hasText("Low Tide at Carrow")).fetchSemanticsNode().config
        assertTrue("the title and the year", card.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty().containsAll(listOf("Low Tide at Carrow", "2024")))

        compose.onNode(hasClickAction() and hasText("Low Tide at Carrow")).performScrollTo().performClick()
        assertEquals(listOf(505L), movies)
        assertTrue(shows.isEmpty())
    }

    @Test fun `the show page ends with More like this, and a card opens its show`() {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                WatchMyMediaPhoneTheme { ShowPageScreen(showId = 100, arrival = ShowArrival.Show, onPlay = {}, onBack = {}, onOpenShow = { shows += it }) }
            }
        }
        compose.waitForIdle()
        val first = FakeData.showRelated(100).single().items.first()
        compose.onNode(isHeading() and hasText("More like this")).performScrollTo()
        // Under the page's own end, the card can sit below the screen: TalkBack's double-tap is its click action.
        compose.onNode(hasClickAction() and hasText(first.title)).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(first.id), shows)
    }

    @Test fun `an older server's page ends as before`() {
        runBlocking { services.servers.select(LivingRoom.copy(apiLevel = RELATED_API_LEVEL - 1)) }
        movie()
        compose.onNodeWithText("Versions").assertExists()
        compose.onNodeWithText("More like this").assertDoesNotExist()
        compose.onNodeWithText(FakeData.COLLECTION_NAME).assertDoesNotExist()
    }
}

/** The tablet's movie page puts the rows in its right pane, under the cast (TabMovieRelated.dc.html). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-xhdpi")
class TabletRelatedRowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restoreLatency() {
        fakeLatencyMs = latency
    }

    @Test fun `the rows follow the cast in the right pane`() {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices()) {
                WatchMyMediaPhoneTheme { MovieScreen(500, onPlay = {}, onBack = {}) }
            }
        }
        compose.waitForIdle()
        // The right pane is a lazy column: scroll it to the rows' item.
        compose.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(isHeading() and hasText("More like this"))
        val cast = compose.onNodeWithText("Cast").fetchSemanticsNode().boundsInRoot
        val rows = compose.onNode(isHeading() and hasText(FakeData.COLLECTION_NAME)).fetchSemanticsNode().boundsInRoot
        assertTrue("beside the details, as the cast is", rows.left >= cast.left - 1f && rows.left > 400f)
    }
}
