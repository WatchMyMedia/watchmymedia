package media.watchmy.client.mobile.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import media.watchmy.client.mobile.navigation.OnTabReselected
import media.watchmy.client.mobile.components.MobileTab
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.EmptyState
import media.watchmy.client.mobile.components.ErrorState
import media.watchmy.client.mobile.components.GoldChip
import media.watchmy.client.mobile.components.InlineError
import media.watchmy.client.mobile.components.ChipRowSkeleton
import media.watchmy.client.mobile.components.LibraryGridSkeleton
import media.watchmy.client.mobile.components.MainTopBar
import media.watchmy.client.mobile.components.LoadContent
import media.watchmy.client.mobile.components.MessageHost
import media.watchmy.client.mobile.components.PosterCard
import media.watchmy.client.mobile.navigation.MobileRoutes
import media.watchmy.client.presentation.library.LibraryViewModel
import media.watchmy.client.presentation.library.countLine
import media.watchmy.client.presentation.library.gridSubtitle
import media.watchmy.client.presentation.rows.LibraryRowsViewModel
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.filterOptions
import media.watchmy.client.model.label
import media.watchmy.client.model.sortOptions
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.TestTags
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.ui.rememberNotMatchedNotice
import media.watchmy.client.ui.open
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Sheen
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberSheen
import media.watchmy.client.ui.components.skeletonLine
import media.watchmy.client.ui.ReportShown

/**
 * The TV Shows or Movies grid (Library.dc.html): the count and sort menu,
 * the filter, genre and decade chips, and three posters a row, a page at a
 * time. Long-press a poster to mark it watched or unwatched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    type: LibraryType,
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onNavigate: (Any) -> Unit,
    onPlay: (PlayRequest) -> Unit = {},
) {
    val services = LocalAppServices.current
    val notMatched = rememberNotMatchedNotice()
    val vm = viewModel(key = "library-$type") { LibraryViewModel(services.library, type, saved = createSavedStateHandle()) }
    ReportShown(vm::setShown)
    val state by vm.state.collectAsStateWithLifecycle()
    val user by services.auth.user.collectAsStateWithLifecycle()
    // The library's rows above the grid, from servers that arrange them (PLAN-ROWS.md Decision 11).
    val rowsVm = viewModel(key = "library-rows-$type") { LibraryRowsViewModel(services.library, services.rows) }
    ReportShown(rowsVm::setShown)
    val rows by rowsVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.libraryId) { rowsVm.show(state.libraryId) }
    // One scroll for every layout, made above the phone/tablet branch, so turning the screen keeps the place.
    val grid = rememberLibraryGrid()
    // Its tab tapped again takes the grid back to the top, as on the other tabs.
    OnTabReselected(if (type == LibraryType.Tv) MobileTab.TvShows else MobileTab.Movies) { grid.animateScrollToItem(0) }
    val layout = media.watchmy.client.ui.rememberTabletLayout()
    if (layout.isTablet) {
        TabletLibraryScreen(type, state, vm, grid, user?.name, layout.twoPane, onOpenShow, onOpenMovie, onNavigate, onPlay, notMatched, rows, rowsVm)
        return
    }
    if (rowsVm.arranges && state.libraryId != null) {
        ArrangedLibraryScreen(
            type, state, vm, grid, rows, rowsVm, user?.name, onOpenShow, onOpenMovie, onNavigate, onPlay, notMatched,
            sortMenu = { SortMenu(state, vm, type) },
            chips = { ChipRow(state, vm, type) },
        )
        return
    }
    // A new sort or filter starts at the top; coming back from a show keeps the place.
    grid.FollowLibrary(state, top = 0, first = 0, loadMore = vm::loadMore)

    val title = if (type == LibraryType.Tv) "TV Shows" else "Movies"
    // The first page on its way: the outline of the grid (HomeLoading.dc.html, library), with the sort as it is.
    val firstLoad = state.loading && state.items.isEmpty() && state.libraryId == null
    // Swept only while there is a skeleton to sweep: an endless animation would keep the screen drawing.
    val sheen = if (state.loading && state.items.isEmpty()) rememberSheen(1_600) else Sheen.Still
    Column(Modifier.fillMaxSize().background(WmmColors.Ground)) {
        MainTopBar(title, user?.name, onSearch = { onNavigate(MobileRoutes.Search) }, onAvatar = { onNavigate(MobileRoutes.Settings) })
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (firstLoad) {
                Box(Modifier.weight(1f)) { Box(Modifier.skeletonLine(sheen, 160.dp, 11.dp).clearAndSetSemantics {}) }
            } else {
                Text(
                    if (state.loading && state.items.isEmpty()) "" else state.countLine(type),
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted),
                    modifier = Modifier.weight(1f),
                )
            }
            SortMenu(state, vm, type)
        }
        if (firstLoad) ChipRowSkeleton(sheen = sheen) else ChipRow(state, vm, type)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LoadContent(state.loadStatus(type, vm), vm::retry, loading = { LibraryGridSkeleton("Loading $title", sheen = sheen) }) {
                    val pull = rememberPullToRefreshState()
                    PullToRefreshBox(
                        isRefreshing = state.refreshing,
                        onRefresh = vm::refresh,
                        state = pull,
                        modifier = Modifier.fillMaxSize(),
                        indicator = {
                            PullToRefreshDefaults.Indicator(state = pull, isRefreshing = state.refreshing, modifier = Modifier.align(Alignment.TopCenter), containerColor = WmmColors.Surface3, color = WmmColors.Accent)
                        },
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = grid,
                            modifier = Modifier.fillMaxSize().testTag(TestTags.LIBRARY),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(state.items, key = { it.id }) { item ->
                                PosterCard(
                                    item = item,
                                    imageUrl = services.library.imageUrl(item.posterPath, ImageSize.W342),
                                    subtitle = item.gridSubtitle(),
                                    onClick = { item.open(onOpenShow, onOpenMovie, onPlay, notMatched) },
                                    onSetWatched = { vm.setWatched(item, it) },
                                    titleSize = 13.sp,
                                    subtitleSize = 11.sp,
                                )
                            }
                            if (state.loadingMore || state.moreError != null) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    val more = state.moreError
                                    if (more != null) {
                                        InlineError(more, vm::retryMore)
                                    } else {
                                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(Modifier.size(24.dp), color = WmmColors.Accent, strokeWidth = 2.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
            MessageHost(state.message, vm::messageShown, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** The gold "Date added ▾" button and its menu. */
@Composable
internal fun SortMenu(state: LibraryViewModel.State, vm: LibraryViewModel, type: LibraryType) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            shape = CircleShape,
            colors = ButtonDefaults.textButtonColors(contentColor = WmmColors.Accent),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Icon(WmmIcons.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(state.choices.sort.label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
            Spacer(Modifier.width(6.dp))
            Icon(WmmIcons.ChevronDown, contentDescription = "Sort by", modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = WmmColors.Surface3) {
            sortOptions(type).forEach { sort ->
                MenuChoice(sort.label, selected = sort == state.choices.sort) {
                    open = false
                    vm.setSort(sort)
                }
            }
        }
    }
}

/** Filters, then genres, then Decade, in one sideways-scrolling row. */
@Composable
internal fun ChipRow(state: LibraryViewModel.State, vm: LibraryViewModel, type: LibraryType) {
    var decadeMenu by remember { mutableStateOf(false) }
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // A resized tablet can carry a title query or a later A–Z window into the phone layout.
        if (state.choices.text.isNotBlank()) item(key = "text") { GoldChip("${state.choices.text} ×", selected = true, onClick = { vm.setText("") }) }
        if (state.offset > 0) item(key = "start") { GoldChip("Back to start", selected = false, onClick = vm::returnToStart) }
        items(filterOptions(type), key = { "f$it" }) { filter ->
            GoldChip(filter.label, selected = state.choices.filter == filter, onClick = { vm.setFilter(filter) })
        }
        if (state.genres.isNotEmpty()) {
            item(key = "divider") { VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp), color = WmmColors.Line2) }
            items(state.genres, key = { "g$it" }) { genre ->
                GoldChip(genre, selected = state.choices.genre == genre, onClick = { vm.setGenre(genre) })
            }
        }
        if (state.decades.isNotEmpty()) {
            item(key = "decade") {
                Box {
                    GoldChip(
                        state.choices.decade?.let { "${it}s" } ?: "Decade",
                        selected = state.choices.decade != null,
                        onClick = { decadeMenu = true },
                        trailingIcon = WmmIcons.ChevronDown,
                    )
                    DropdownMenu(expanded = decadeMenu, onDismissRequest = { decadeMenu = false }, containerColor = WmmColors.Surface3) {
                        MenuChoice("Any decade", selected = state.choices.decade == null) {
                            decadeMenu = false
                            vm.setDecade(null)
                        }
                        HorizontalDivider(color = WmmColors.Line2)
                        state.decades.forEach { decade ->
                            MenuChoice("${decade}s", selected = state.choices.decade == decade) {
                                decadeMenu = false
                                vm.setDecade(decade)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A menu row with a gold check on the chosen one. */
@Composable
internal fun MenuChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) WmmColors.Accent else WmmColors.Text)) },
        trailingIcon = if (selected) {
            { Icon(WmmIcons.Check, contentDescription = "Selected", tint = WmmColors.Accent, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        onClick = onClick,
    )
}
