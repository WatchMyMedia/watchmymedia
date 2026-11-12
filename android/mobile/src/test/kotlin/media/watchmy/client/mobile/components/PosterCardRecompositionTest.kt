package media.watchmy.client.mobile.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import media.watchmy.client.fake.FakeData
import media.watchmy.client.mobile.ComposableRuns
import media.watchmy.client.model.BrowseItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A grid of posters as Library and Search draw them: when the grid
 * recomposes for something else (the next page loading), the cards on
 * screen are passed the same values and skip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PosterCardRecompositionTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val runs = ComposableRuns()

    private data class Grid(val items: List<BrowseItem>, val loadingMore: Boolean = false)

    @Test fun `the posters skip when the grid recomposes`() {
        val grid = MutableStateFlow(Grid(FakeData.shows.take(6)))
        val marked = mutableListOf<Pair<Long, Boolean>>()
        compose.setContent {
            val state = grid.collectAsState().value
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.size(360.dp, 1200.dp)) {
                items(state.items, key = { it.id }) { item ->
                    PosterCard(item, imageUrl = null, subtitle = null, onClick = {}, onSetWatched = { marked += item.id to it })
                }
                item { Text(if (state.loadingMore) "Loading more" else "All here") }
            }
        }
        compose.waitForIdle()
        runs.clear()

        repeat(4) {
            grid.update { it.copy(loadingMore = !it.loadingMore) }
            compose.waitForIdle()
        }

        compose.onNodeWithText("All here").assertExists()
        assertEquals(0, runs["PosterCard"])

        // The menu the card built still marks the right item.
        val first = grid.value.items.first()
        compose.onNodeWithText(first.title).performTouchInput { longClick() }
        compose.onNodeWithText(if (first.watched) "Mark as unwatched" else "Mark as watched").performClick()
        assertEquals(listOf(first.id to !first.watched), marked)
    }
}
