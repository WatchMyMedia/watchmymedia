package media.watchmy.client.mobile.library

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import media.watchmy.client.mobile.components.LoadStatus
import media.watchmy.client.model.LibraryType
import media.watchmy.client.presentation.library.LibraryViewModel

/**
 * The library grid's scroll, one for the phone, the arranged and the
 * tablet layouts (PLAN-ANDROID-REVIEW.md §5.4). It is made above the
 * phone/tablet branch, so turning or resizing the screen keeps the place.
 */
@Composable
internal fun rememberLibraryGrid(): LazyGridState = rememberLazyGridState()

/**
 * What the three layouts do with the grid's scroll: a new sort, filter or
 * window goes back up to [top] (the grid's heading) when it is below it, a
 * letter jumped to scrolls to its first title ([first] is the first
 * poster's index), and nearing the end asks for the next page ([loadMore]).
 */
@Composable
internal fun LazyGridState.FollowLibrary(state: LibraryViewModel.State, top: Int, first: Int, loadMore: () -> Unit) {
    val grid = this
    val query = "${state.choices}/${state.offset}"
    var shown by rememberSaveable { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query != shown) {
            shown = query
            if (grid.firstVisibleItemIndex > top) grid.scrollToItem(top)
        }
    }
    LaunchedEffect(state.jump?.revision) { state.jump?.let { grid.scrollToItem(first + it.index) } }
    // Restarted after each page, so a short page that leaves the end in view asks for the next one.
    val nearEnd by remember(grid) { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= grid.layoutInfo.totalItemsCount - 12 } }
    val more by rememberUpdatedState(loadMore)
    LaunchedEffect(grid, state.items.size, state.offset) { snapshotFlow { nearEnd }.collect { if (it) more() } }
}

/** The grid's loading, error and empty states, the same in each layout; null once it has posters. */
internal fun LibraryViewModel.State.loadStatus(type: LibraryType, vm: LibraryViewModel): LoadStatus? {
    val noun = if (type == LibraryType.Tv) "show" else "movie"
    return when {
        items.isNotEmpty() -> null
        loading -> LoadStatus.Loading
        error != null -> LoadStatus.Failed(error!!)
        noLibrary -> LoadStatus.Empty(
            if (type == LibraryType.Tv) "No TV library" else "No movie library",
            "This server has no ${if (type == LibraryType.Tv) "TV Shows" else "Movies"} library yet. Add one in the web app.",
        )
        filtered -> LoadStatus.Empty("Nothing matches", "No ${noun}s match these filters.", action = "Clear filters", onAction = vm::clearFilters)
        else -> LoadStatus.Empty("No ${noun}s yet", "When the server has scanned the library, its ${noun}s appear here.", action = "Check again", onAction = vm::retry)
    }
}
