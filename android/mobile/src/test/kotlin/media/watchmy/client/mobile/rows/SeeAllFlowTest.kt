package media.watchmy.client.mobile.rows

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.mobile.MobileApp
import media.watchmy.client.model.ROWS_API_LEVEL
import media.watchmy.client.model.RowIds
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
 * A Home row's See all on the phone, on the fakes (PLAN-SEE-ALL.md §4.4,
 * §7): the card at the row's end and the heading's link open the library's
 * tab at its grid, sorted the row's way, and the sort is the tab's from then
 * on, not put back on a later visit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class SeeAllFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val fake = FakeAppServices()
    private var latency = 0L

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restoreLatency() {
        fakeLatencyMs = latency
    }

    private fun open() {
        compose.setContent { CompositionLocalProvider(LocalAppServices provides fake) { MobileApp() } }
        compose.waitForIdle()
    }

    private fun tab(text: String) = compose.onAllNodes(hasText(text) and hasClickAction()).onFirst()

    private fun nodes(label: String) = compose.onAllNodes(hasContentDescription(label) and hasClickAction())

    /** The first of [nodes] on screen, if any (the tablet's pager keeps the next page composed too). */
    private fun displayed(nodes: SemanticsNodeInteractionCollection): Int? =
        (0 until nodes.fetchSemanticsNodes().size).firstOrNull { i -> runCatching { nodes[i].assertIsDisplayed() }.isSuccess }

    /** Home's scroll: the outermost scroller under its tag (the rows' own come after it). */
    private fun home(): SemanticsNodeInteraction = compose.onAllNodes(hasScrollToKeyAction() and hasAnyAncestor(hasTestTag(TestTags.HOME))).onFirst()

    /** Scrolls Home to [rowId]'s row, then that row to its See all card. */
    private fun toRowEnd(rowId: String) {
        home().performScrollToKey("row-$rowId")
        compose.waitForIdle()
        val rows = compose.onAllNodes(hasScrollToKeyAction() and hasAnyAncestor(hasTestTag(TestTags.HOME)))
        val count = rows.fetchSemanticsNodes().size
        val scrolled = (1 until count).count { i -> runCatching { rows[i].performScrollToKey(SEE_ALL_KEY) }.isSuccess }
        assertTrue("a row ends in See all", scrolled > 0)
        compose.waitForIdle()
    }

    @Test
    fun `Recently aired's card opens TV Shows at the grid, sorted by last aired`() {
        open()
        toRowEnd(RowIds.RECENTLY_AIRED)
        val label = "See all TV shows, by last aired"
        // The heading's link and the card at the row's end, read alike.
        nodes(label).assertCountEquals(2)
        nodes(label)[1].assertIsDisplayed().performClick()
        compose.waitForIdle()

        compose.onNode(hasText("Last aired") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("All shows")).assertIsDisplayed()
        // The tab's own rows, and Edit rows under them, are a scroll up away.
        if (compose.onAllNodes(hasText("Edit rows")).fetchSemanticsNodes().isNotEmpty()) compose.onNode(hasText("Edit rows")).assertIsNotDisplayed()
    }

    @Test
    fun `the heading's See all does the same, and the sort is the tab's afterwards`() {
        open()
        home().performScrollToKey("row-${RowIds.RECENTLY_RELEASED}")
        compose.waitForIdle()
        nodes("See all movies, by release date").onFirst().performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Release date") and hasClickAction()).assertIsDisplayed().performClick()
        compose.onNode(hasText("Title") and hasClickAction()).performClick()
        compose.waitForIdle()

        // Home and back to Movies from the tab bar: the person's choice stays, the row's isn't put back.
        tab("Home").performClick()
        compose.waitForIdle()
        tab("Movies").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Title") and hasClickAction()).assertIsDisplayed()
        compose.onAllNodes(hasText("Release date") and hasClickAction()).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun `on a tablet, See all opens the grid even when a show was left open in the tab`() {
        open()
        // The rail stays on a show page, so the TV Shows tab is left with the show on top.
        tab("TV Shows").performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText("Harbor Lights") and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText("All shows")).assertCountEquals(0)
        tab("Home").performClick()
        compose.waitForIdle()

        // One row a screen: the next row's name along the bottom pages down, to Recently added · TV Shows.
        val label = "See all TV shows, by date added"
        repeat(4) {
            if (displayed(nodes(label)) != null) return@repeat
            val peeks = compose.onAllNodes(SemanticsMatcher("pages to the next row") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Next row" })
            peeks[displayed(peeks) ?: return@repeat].performClick()
            compose.waitForIdle()
        }
        nodes(label)[displayed(nodes(label))!!].performClick()
        compose.waitForIdle()
        compose.onNode(hasText("All shows")).assertIsDisplayed()
        compose.onNode(hasText("Date added ▾") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun `a server older than arranged rows keeps the phone's fixed rows, without See all`() {
        runBlocking { fake.servers.select(LivingRoom.copy(apiLevel = ROWS_API_LEVEL - 1)) }
        open()
        // The fixed rows' keys, each scrolled into view in turn.
        listOf("shows", "aired", "movies", "released").forEach { key ->
            home().performScrollToKey(key)
            compose.waitForIdle()
            compose.onAllNodes(hasText("See all")).assertCountEquals(0)
            compose.onAllNodes(hasContentDescription("See all", substring = true)).assertCountEquals(0)
        }
        compose.onAllNodes(hasText("Recently released")).onFirst().assertIsDisplayed()
    }

    @Test
    fun `Back from the grid returns to Home`() {
        open()
        home().performScrollToKey("row-${RowIds.RECENTLY_ADDED_SHOWS}")
        compose.waitForIdle()
        nodes("See all TV shows, by date added").onFirst().performClick()
        compose.waitForIdle()
        compose.onNode(hasText("All shows")).assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        nodes("See all TV shows, by date added").onFirst().assertIsDisplayed()
    }
}
