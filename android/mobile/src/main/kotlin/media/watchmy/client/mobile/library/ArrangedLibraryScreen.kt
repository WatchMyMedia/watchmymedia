package media.watchmy.client.mobile.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.mobile.components.EmptyState
import media.watchmy.client.mobile.components.ErrorState
import media.watchmy.client.mobile.components.InlineError
import media.watchmy.client.mobile.components.MainTopBar
import media.watchmy.client.mobile.components.MessageHost
import media.watchmy.client.mobile.components.PosterCard
import media.watchmy.client.mobile.navigation.MobileRoutes
import media.watchmy.client.mobile.rows.ArrangedRow
import media.watchmy.client.mobile.rows.EditRowsButton
import media.watchmy.client.mobile.rows.RowActions
import media.watchmy.client.mobile.rows.bleed
import media.watchmy.client.mobile.rows.rowEditorRoute
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.RowLink
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.drawn
import media.watchmy.client.model.formatCount
import media.watchmy.client.presentation.rows.LibraryRowsViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.open

/**
 * The Movies or TV screen with its rows above the grid (LibraryRows.dc.html):
 * the library's rows in the person's order, Edit rows, then All movies with
 * its count and sort, the chips and the grid, all in one scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArrangedLibraryScreen(
    type: LibraryType,
    state: LibraryViewModel.State,
    vm: LibraryViewModel,
    rows: LibraryRowsViewModel.State,
    rowsVm: LibraryRowsViewModel,
    userName: String?,
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onNavigate: (Any) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onNotMatched: () -> Unit,
    sortMenu: @Composable () -> Unit,
    chips: @Composable () -> Unit,
) {
    val library = LocalAppServices.current.library
    val grid = rememberLazyGridState()
    val title = if (type == LibraryType.Tv) "TV Shows" else "Movies"
    val noun = if (type == LibraryType.Tv) "show" else "movie"
    val drawn = rows.rows.drawn(live = false)
    // A new sort or filter from deep in the grid goes back up to its heading; with the heading or
    // the rows in view nothing moves, and coming back from a show keeps the place.
    val queryKey = "${state.sort} ${state.filter} ${state.genre} ${state.decade} ${state.text} ${state.offset}"
    var shownKey by rememberSaveable { mutableStateOf(queryKey) }
    LaunchedEffect(queryKey) {
        if (queryKey != shownKey) {
            shownKey = queryKey
            val heading = drawn.size + 1
            if (grid.firstVisibleItemIndex > heading) grid.scrollToItem(heading)
        }
    }
    ShowRowsWhenTheyArrive(grid, drawn.size)
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 12 } }
    LaunchedEffect(grid, state.items.size) { snapshotFlow { nearEnd }.collect { if (it) vm.loadMore() } }
    val actions = libraryRowActions(onPlay, onOpenShow, onOpenMovie, onNavigate, onNotMatched, rowsVm)

    Column(Modifier.fillMaxSize().background(WmmColors.Ground)) {
        MainTopBar(title, userName, onSearch = { onNavigate(MobileRoutes.Search) }, onAvatar = { onNavigate(MobileRoutes.Settings) })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PullToRefreshBox(state.refreshing, { vm.refresh(); rowsVm.refresh() }, Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = grid,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    libraryRows(drawn, library, actions, tablet = false, edge = 16.dp, onNavigate = onNavigate)
                    item(key = "edit", span = { GridItemSpan(maxLineSpan) }, contentType = "edit") {
                        EditRowsButton("Edit rows", { rows.screen?.let { onNavigate(rowEditorRoute(it, title, tablet = false)) } }, tablet = false, edge = 0.dp, modifier = Modifier.padding(top = 10.dp, bottom = 10.dp))
                    }
                    item(key = "all", span = { GridItemSpan(maxLineSpan) }, contentType = "all") {
                        Column(Modifier.bleed(16.dp)) {
                            HorizontalDivider(color = WmmColors.Line)
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).semantics { heading() }, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("All ${noun}s", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = WmmColors.Text), modifier = Modifier.alignByBaseline())
                                    val count = if (state.filtered) state.total else state.summary?.items ?: state.total
                                    if (!state.loading || state.items.isNotEmpty()) Text(formatCount(count), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = WmmColors.TextMuted), modifier = Modifier.alignByBaseline())
                                }
                                sortMenu()
                            }
                            chips()
                        }
                    }
                    gridItems(state, vm, library, noun, onOpenShow, onOpenMovie, onPlay, onNotMatched)
                }
            }
            MessageHost(state.message ?: rows.message, { vm.messageShown(); rowsVm.messageShown() }, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * Rows that arrive after the grid would go in above what is on screen, where
 * a lazy grid keeps its place: the first time they come, and while the page
 * is still at its top, scroll up to show them.
 */
@Composable
internal fun ShowRowsWhenTheyArrive(grid: androidx.compose.foundation.lazy.grid.LazyGridState, rows: Int) {
    var seen by rememberSaveable { mutableStateOf(rows > 0) }
    LaunchedEffect(rows > 0) {
        if (seen || rows == 0) return@LaunchedEffect
        seen = true
        if (grid.firstVisibleItemIndex <= rows + 3) grid.scrollToItem(0)
    }
}

/** What a library row's cards do; an episode opens its page. */
internal fun libraryRowActions(
    onPlay: (PlayRequest) -> Unit,
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onNavigate: (Any) -> Unit,
    onNotMatched: () -> Unit,
    rowsVm: LibraryRowsViewModel,
) = RowActions(onPlay, onOpenShow, onOpenMovie, { id, season, episode -> onNavigate(MobileRoutes.Episode(id, season, episode)) }, onNotMatched, rowsVm::setWatched)

/** The library's rows as full-width items of a grid, reaching the screen's edges. */
internal fun LazyGridScope.libraryRows(rows: List<media.watchmy.client.model.ScreenRow>, library: LibraryRepository, actions: RowActions, tablet: Boolean, edge: Dp, onNavigate: (Any) -> Unit) {
    rows.filter { it.style != RowStyle.Live }.forEach { row ->
        item(key = "row-${row.id}", span = { GridItemSpan(maxLineSpan) }, contentType = "row-${row.style}") {
            val seeAll = (row.seeAll as? RowLink.ToList)?.let { link -> { onNavigate(MobileRoutes.MediaList(link.listId)) } }
            ArrangedRow(row, library, tablet, actions, seeAll, Modifier.bleed(edge).padding(bottom = 10.dp), edge = edge)
        }
    }
}

/** The grid's posters, or why there are none, under its heading and chips. */
private fun LazyGridScope.gridItems(
    state: LibraryViewModel.State,
    vm: LibraryViewModel,
    library: LibraryRepository,
    noun: String,
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onNotMatched: () -> Unit,
) {
    val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
    when {
        state.items.isEmpty() && state.loading -> item(key = "loading", span = full) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), color = WmmColors.Accent, strokeWidth = 2.dp) }
        }
        state.items.isEmpty() && state.error != null -> item(key = "error", span = full) { ErrorState(state.error, vm::retry, Modifier.height(240.dp)) }
        state.noLibrary -> item(key = "none", span = full) { EmptyState("No $noun library", "Add one in the web app.", Modifier.height(240.dp)) }
        state.items.isEmpty() && state.filtered -> item(key = "nothing", span = full) {
            EmptyState("Nothing matches", "No ${noun}s match these filters.", Modifier.height(240.dp), action = "Clear filters", onAction = vm::clearFilters)
        }
        state.items.isEmpty() -> item(key = "empty", span = full) {
            EmptyState("No ${noun}s yet", "When the server has scanned the library, its ${noun}s appear here.", Modifier.height(240.dp), action = "Check again", onAction = vm::retry)
        }
        else -> {
            items(state.items, key = { it.id }, contentType = { "poster" }) { item ->
                PosterCard(
                    item = item,
                    imageUrl = library.imageUrl(item.posterPath, ImageSize.W342),
                    subtitle = gridSubtitle(item),
                    onClick = { item.open(onOpenShow, onOpenMovie, onPlay, onNotMatched) },
                    onSetWatched = { vm.setWatched(item, it) },
                    titleSize = 13.sp,
                    subtitleSize = 11.sp,
                )
            }
            if (state.loadingMore || state.moreError != null) {
                item(key = "more", span = full) {
                    val more = state.moreError
                    if (more != null) InlineError(more, vm::retryMore)
                    else Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), color = WmmColors.Accent, strokeWidth = 2.dp) }
                }
            }
        }
    }
}
