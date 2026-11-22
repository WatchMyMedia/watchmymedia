package media.watchmy.client.mobile.watchlists

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasText
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.FakeRowsData
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.mobile.MobileApp
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.WatchlistKey
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.TestTags
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Watchlists on the phone, on the fakes, where Sam has Watchlist, Halloween
 * and With the kids, and Dead Reckoning Bay is on With the kids.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class WatchlistFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L
    private val fake = FakeAppServices()
    private val bay = FakeData.movies.first { it.title == "Dead Reckoning Bay" }

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restoreLatency() {
        fakeLatencyMs = latency
    }

    private fun start(deepLink: String? = null) {
        compose.setContent { CompositionLocalProvider(LocalAppServices provides fake) { MobileApp(deepLink) } }
        compose.waitForIdle()
    }

    private fun waitFor(text: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun `Watchlists in Servers lists them, and one opens its page`() {
        start()
        compose.onAllNodesWithTag(TestTags.ACCOUNT).onLast().performClick()
        compose.onNodeWithText("Watchlists").performClick()
        // Each watchlist reads as one thing: its name, counts, and whether it keeps watched titles.
        val halloween = hasContentDescription("Halloween, 6 titles · 6 in your library, keeps watched titles")
        compose.waitUntil(5_000) { compose.onAllNodes(halloween).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("With the kids", substring = true)).assertIsDisplayed()
        compose.onNode(halloween).performClick()
        waitFor("WATCHLIST")
        compose.onNodeWithText("6 titles · 6 in your library").assertIsDisplayed()
    }

    @Test fun `with several watchlists the movie's button opens Save to watchlist, and a row saves at once`() {
        start("watchmymedia://movies/${bay.id}")
        waitFor("On With the kids")
        compose.onNode(hasContentDescription("On With the kids: Dead Reckoning Bay")).performScrollTo().performClick()
        waitFor("Save to watchlist")
        compose.onNode(isToggleable() and hasText("Halloween")).performClick()
        compose.waitForIdle()
        assertTrue("saved to Halloween", FakeRowsData.HALLOWEEN in fake.watchlists.holdingIds(WatchlistKey(MediaType.Movie, FakeData.tmdbId(bay.id))))
        compose.onNodeWithText("Done").performClick()
        waitFor("On 2 watchlists")
    }
}
