package media.watchmy.client.tv.rows

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.ItemQuery
import media.watchmy.client.model.ItemSort
import media.watchmy.client.tv.TvApp
import media.watchmy.client.tv.quietTvFakes
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
 * A row's See all on the TV, on the fakes (PLAN-SEE-ALL.md §4.2, §7): past
 * a built-in row's last card on Home is a See all card, its panel says where
 * it goes, and OK opens TV Shows or Movies sorted the row's way with focus on
 * the grid's first card. Back comes back to the card, and the rail's TV Shows
 * later opens as it always does. A row above the grid sorts the grid below.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SeeAllTvTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val home = FakeData.home()
    private lateinit var services: FakeAppServices

    @Before
    fun start() {
        fakeLatencyMs = 0
        services = quietTvFakes()
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) { TvApp() }
        }
        compose.waitForIdle()
    }

    /** The grid's first title in [sort], as the fakes sort it. */
    private fun firstBy(library: Long, sort: ItemSort): String = runBlocking { services.library.items(ItemQuery(library, sort)).items.first().title }

    @Test
    fun `Recently aired ends in See all, which opens TV Shows by last aired at its grid, and Back returns to it`() {
        repeat(5) { press(Key.DirectionDown) } // Hero, Continue watching, Live TV, Recently added shows, a list, then Recently aired.
        compose.onNodeWithText("Row 5 of ", substring = true, useUnmergedTree = true).assertExists()
        repeat(home.recentlyAired.size) { if (focused() != "See all TV shows") press(Key.DirectionRight) }
        assertEquals("See all TV shows", focused())
        assertEquals("See all TV shows, by last aired", spoken())
        // The panel above the row says it too, and what OK does.
        assertTrue(compose.onAllNodesWithText("See all TV shows", useUnmergedTree = true).fetchSemanticsNodes().size >= 2)
        assertTrue(compose.onAllNodesWithText("By last aired", useUnmergedTree = true).fetchSemanticsNodes().size >= 2)
        compose.onNodeWithText("opens the library", substring = true, useUnmergedTree = true).assertExists()

        val aired = firstBy(FakeData.TV_LIBRARY, ItemSort.Aired)
        assertNotEquals("the fakes' first show differs by sort", firstBy(FakeData.TV_LIBRARY, ItemSort.Added), aired)
        press(Key.DirectionCenter)
        compose.onNodeWithText("Last aired", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("All shows", useUnmergedTree = true).assertExists()
        assertEquals("focus is on the grid's first card, not the rows above it", aired, focused())

        back()
        assertEquals("Back comes back to the See all card", "See all TV shows", focused())
        compose.onNodeWithText("Row 5 of ", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the rail's TV Shows afterwards opens as it always does`() {
        repeat(5) { press(Key.DirectionDown) }
        repeat(home.recentlyAired.size) { if (focused() != "See all TV shows") press(Key.DirectionRight) }
        press(Key.DirectionCenter)
        compose.onNodeWithText("Last aired", useUnmergedTree = true).assertExists()
        back()

        openTvShows()
        assertTrue("focus starts on the rows, as it always does", focused() in home.recentlyAddedShows.map { it.title })
        // Down past the rows to the grid's heading, which the screen draws once it is near.
        repeat(8) { if (compose.onAllNodesWithText("All shows", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) press(Key.DirectionDown) }
        compose.onNodeWithText("Date added", useUnmergedTree = true).assertExists()
        assertEquals("the sort was used once", 0, compose.onAllNodesWithText("Last aired", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `Recently released ends in See all movies by release date`() {
        repeat(8) { press(Key.DirectionDown) } // ... Recently added movies, a list, then Recently released.
        compose.onNodeWithText("Row 8 of ", substring = true, useUnmergedTree = true).assertExists()
        repeat(home.recentlyReleased.size) { if (focused() != "See all movies") press(Key.DirectionRight) }
        assertEquals("See all movies, by release date", spoken())
        press(Key.DirectionCenter)
        compose.onNodeWithText("Release date", useUnmergedTree = true).assertExists()
        assertEquals(firstBy(FakeData.MOVIE_LIBRARY, ItemSort.Released), focused())
    }

    @Test
    fun `a row above the TV Shows grid sorts the grid below the row's way`() {
        openTvShows()
        // Focus starts on the first row (Recently added); the second is Recently aired.
        press(Key.DirectionDown)
        repeat(home.recentlyAired.size + 1) { if (spoken() != "See all TV shows, by last aired") press(Key.DirectionRight) }
        assertEquals("See all TV shows, by last aired", spoken())
        press(Key.DirectionCenter)
        compose.onNodeWithText("Last aired", useUnmergedTree = true).assertExists()
        assertEquals(firstBy(FakeData.TV_LIBRARY, ItemSort.Aired), focused())
    }

    /** From Home, wherever focus is on its row, the rail's TV Shows. */
    private fun openTvShows() {
        repeat(30) { if (focused() != "Home") press(Key.DirectionLeft) }
        assertEquals("the rail, on Home", "Home", focused())
        repeat(6) { if (focused() != "TV Shows") press(Key.DirectionDown) }
        assertEquals("TV Shows", focused())
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** The focused element's title: a card's first line, a button's label or a rail item's name. */
    private fun focused(): String {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull { it.text.isNotBlank() && !BADGES.matches(it.text) }?.text
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            ?: error("The focused element has no text")
    }

    /** What TalkBack reads for the focused element. */
    private fun spoken(): String? = compose.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private companion object {
        /** Quality and New badges come before a poster's title. */
        val BADGES = Regex("4K|1080p|720p|SD|HDR|HDR10|New|Dolby Vision", RegexOption.IGNORE_CASE)
    }
}
