package media.watchmy.client.mobile.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import media.watchmy.client.model.formatCount
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.mobile.components.EmptyState
import media.watchmy.client.mobile.components.ErrorState
import media.watchmy.client.mobile.components.GoldChip
import media.watchmy.client.mobile.components.InlineError
import media.watchmy.client.mobile.components.LoadingState
import media.watchmy.client.mobile.components.MainTopBar
import media.watchmy.client.mobile.components.LoadContent
import media.watchmy.client.mobile.components.MessageHost
import media.watchmy.client.mobile.components.PosterCard
import media.watchmy.client.mobile.components.fieldColors
import media.watchmy.client.mobile.navigation.MobileRoutes
import media.watchmy.client.mobile.rows.EditRowsButton
import media.watchmy.client.mobile.rows.rowEditorRoute
import media.watchmy.client.model.drawn
import media.watchmy.client.presentation.library.LibraryViewModel
import media.watchmy.client.presentation.library.countLine
import media.watchmy.client.presentation.library.gridSubtitle
import media.watchmy.client.presentation.rows.LibraryRowsViewModel
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.ItemFilter
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.filterOptions
import media.watchmy.client.model.label
import media.watchmy.client.model.sortOptions
import media.watchmy.client.ui.TestTags
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.TabletLayout
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.open

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun TabletLibraryScreen(
    type: LibraryType,
    state: LibraryViewModel.State,
    vm: LibraryViewModel,
    grid: androidx.compose.foundation.lazy.grid.LazyGridState,
    userName: String?,
    twoPane: Boolean,
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onNavigate: (Any) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onNotMatched: () -> Unit,
    rows: LibraryRowsViewModel.State = LibraryRowsViewModel.State(),
    rowsVm: LibraryRowsViewModel? = null,
) {
    val library = LocalAppServices.current.library
    // The library's rows above the grid, from servers that arrange them.
    val arranged = rowsVm?.arranges == true && state.libraryId != null
    val drawn = if (arranged) rows.rows.drawn(live = false) else emptyList()
    val rowActions = rowsVm?.let { libraryRowActions(onPlay, onOpenShow, onOpenMovie, onNavigate, onNotMatched, it) }
    ShowRowsWhenTheyArrive(grid, drawn.size, toGrid = state.toGrid)
    var filtersVisible by rememberSaveable { mutableStateOf(true) }
    var filtersSheet by remember { mutableStateOf(false) }
    val noun = if (type == LibraryType.Tv) "show" else "movie"
    // A new sort or filter goes back up to the grid; a letter jumped to, to its first title, under the rows when there are any.
    grid.FollowLibrary(state, top = 0, first = if (arranged) drawn.size + 2 else 0, loadMore = vm::loadMore)
    // A row's See all, here or on Home, lands on the grid's heading, under the rows.
    grid.GoToGridWhenAsked(state, heading = if (arranged) drawn.size + 1 else 0, onShown = vm::gridShown)

    Column(Modifier.fillMaxSize().background(WmmColors.Ground)) {
        MainTopBar(if (type == LibraryType.Tv) "TV Shows" else "Movies", userName, { onNavigate(MobileRoutes.Search) }, { onNavigate(MobileRoutes.Settings) })
        Row(Modifier.fillMaxWidth().padding(horizontal = TabletLayout.margin), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.countLine(type), color = WmmColors.TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (twoPane) filtersVisible = !filtersVisible else filtersSheet = true }) {
                Text("Filters${if (state.choices.filterCount > 0) " · ${state.choices.filterCount}" else ""}", color = WmmColors.Accent)
            }
            var sortOpen by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { sortOpen = true }) { Text("${state.choices.sort.label} ▾", color = WmmColors.Accent) }
                DropdownMenu(sortOpen, { sortOpen = false }, containerColor = WmmColors.Surface3) {
                    sortOptions(type).forEach { sort -> MenuChoice(sort.label, state.choices.sort == sort) { sortOpen = false; vm.setSort(sort) } }
                }
            }
        }
        OutlinedTextField(state.choices.text, vm::setText, singleLine = true, placeholder = { Text("Filter ${noun}s") }, colors = fieldColors(), modifier = Modifier.padding(horizontal = TabletLayout.margin).fillMaxWidth().padding(bottom = 16.dp))
        Row(Modifier.weight(1f).padding(horizontal = TabletLayout.margin), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (twoPane && filtersVisible) {
                FilterPanel(state, vm, type, Modifier.width(220.dp).fillMaxHeight()) { filtersVisible = false }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                LoadContent(state.loadStatus(type, vm), vm::retry) {
                    androidx.compose.material3.pulltorefresh.PullToRefreshBox(state.refreshing, vm::refresh, Modifier.fillMaxSize()) {
                        LazyVerticalGrid(GridCells.Adaptive(TabletLayout.posterMinimum), state = grid, modifier = Modifier.testTag(TestTags.LIBRARY), contentPadding = PaddingValues(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (arranged && rowActions != null) {
                                libraryRows(drawn, library, rowActions, tablet = true, edge = 0.dp, type = type, libraryId = state.libraryId, onOpen = vm::open, onNavigate = onNavigate)
                                item(key = "edit", span = { GridItemSpan(maxLineSpan) }, contentType = "edit") {
                                    val title = if (type == LibraryType.Tv) "TV Shows" else "Movies"
                                    EditRowsButton("Edit rows", { rows.screen?.let { onNavigate(rowEditorRoute(it, title, tablet = true)) } }, tablet = true, edge = 0.dp)
                                }
                                item(key = "all", span = { GridItemSpan(maxLineSpan) }, contentType = "all") {
                                    Column(Modifier.padding(top = 4.dp)) {
                                        androidx.compose.material3.HorizontalDivider(color = WmmColors.Line)
                                        // "All shows 142 · Last aired": the heading says the sort a See all opened it with.
                                        Row(Modifier.padding(top = 18.dp).semantics(mergeDescendants = true) { heading() }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("All ${noun}s", color = WmmColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.alignByBaseline())
                                            val count = if (state.filtered) state.total else state.summary?.items ?: state.total
                                            val small = listOfNotNull(formatCount(count).takeIf { !state.loading || state.items.isNotEmpty() }, state.choices.sort.label).joinToString(" · ")
                                            Text(small, color = WmmColors.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.alignByBaseline())
                                        }
                                    }
                                }
                            }
                            items(state.items, key = { it.id }) { item ->
                                PosterCard(item, library.imageUrl(item.posterPath, ImageSize.W342), item.gridSubtitle(), { item.open(onOpenShow, onOpenMovie, onPlay, onNotMatched) }, { vm.setWatched(item, it) })
                            }
                            if (state.loadingMore || state.moreError != null) item(span = { GridItemSpan(maxLineSpan) }) {
                                if (state.moreError != null) InlineError(state.moreError!!, vm::retryMore)
                                else Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = WmmColors.Accent) }
                            }
                        }
                    }
                }
                if (state.loading && state.items.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth(), color = WmmColors.Accent)
                MessageHost(state.message, vm::messageShown, Modifier.align(Alignment.BottomCenter))
            }
            val letters = state.letters
            if (state.choices.sort == ItemSort.Title && !letters.isNullOrEmpty()) {
                LazyColumn(Modifier.width(48.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(letters, key = { it.letter }) { entry ->
                        TextButton(onClick = { vm.jumpToLetter(entry.letter) }, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) {
                            Text(entry.letter, color = if (entry.offset == state.offset) WmmColors.Accent else WmmColors.TextSoft, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(twoPane) { if (twoPane) filtersSheet = false }
    if (filtersSheet && !twoPane) ModalBottomSheet(onDismissRequest = { filtersSheet = false }, containerColor = WmmColors.Surface) {
        FilterPanel(state, vm, type, Modifier.fillMaxWidth().heightIn(max = 720.dp)) { filtersSheet = false }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(state: LibraryViewModel.State, vm: LibraryViewModel, type: LibraryType, modifier: Modifier, onClose: () -> Unit) {
    LazyColumn(modifier.background(WmmColors.Surface, RoundedCornerShape(16.dp)), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Show", color = WmmColors.TextMuted, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    filterOptions(type).filter { it != ItemFilter.FourK && it != ItemFilter.Hdr }.forEach { filter ->
                        GoldChip(filter.label, state.choices.filter == filter, { vm.setFilter(filter) })
                    }
                }
                Text("Quality", color = WmmColors.TextMuted, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ItemFilter.FourK, ItemFilter.Hdr).forEach { filter -> GoldChip(filter.label, state.choices.filter == filter, { vm.setFilter(if (state.choices.filter == filter) ItemFilter.All else filter) }) }
                }
                Text("Genre", color = WmmColors.TextMuted, fontWeight = FontWeight.Bold)
            }
        }
        items(state.genres, key = { it }) { genre ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.choices.genre == genre, onCheckedChange = { vm.setGenre(genre) })
                TextButton(onClick = { vm.setGenre(genre) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text(genre, color = WmmColors.Text, modifier = Modifier.fillMaxWidth()) }
                state.genreCounts[genre]?.let { Text(it.toString(), color = WmmColors.TextMuted, fontSize = 12.sp) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Decade", color = WmmColors.TextMuted, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.decades.forEach { decade -> GoldChip("${decade}s", state.choices.decade == decade, { vm.setDecade(if (state.choices.decade == decade) null else decade) }) }
                }
                TextButton(onClick = vm::clearFilters) { Text("Clear filters", color = WmmColors.Accent) }
                TextButton(onClick = onClose) { Text("Hide filters", color = WmmColors.TextSoft) }
            }
        }
    }
}
