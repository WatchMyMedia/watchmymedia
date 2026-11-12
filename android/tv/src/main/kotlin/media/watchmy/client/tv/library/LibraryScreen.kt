package media.watchmy.client.tv.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.label
import media.watchmy.client.tv.components.ArtBadge
import media.watchmy.client.tv.components.ArtProgress
import media.watchmy.client.tv.components.ArtWatched
import media.watchmy.client.tv.components.ContentEnd
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.EmptyState
import media.watchmy.client.tv.components.ErrorState
import media.watchmy.client.tv.components.KeepInViewSpec
import media.watchmy.client.tv.components.LoadingState
import media.watchmy.client.tv.components.MediaCard
import media.watchmy.client.tv.components.Notice
import media.watchmy.client.tv.components.OptionsDialog
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvChip
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.ui.rememberNotMatchedNotice
import media.watchmy.client.ui.open
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.model.drawn
import media.watchmy.client.model.formatCount
import media.watchmy.client.model.plural
import media.watchmy.client.presentation.rows.LibraryRowsViewModel
import media.watchmy.client.tv.navigation.TvRoutes
import media.watchmy.client.tv.rows.TvArrangedRow
import media.watchmy.client.tv.rows.TvRowActions
import media.watchmy.client.tv.rows.bleed
import media.watchmy.client.tv.rows.cardKey

private enum class Picker { Sort, Decade }

/**
 * TV Shows or Movies (mockup: design/mockups/android-tv/Library.dc.html):
 * an 8-column poster grid under the sort control and the filter, genre and
 * decade chips, loading more as focus nears the end.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    val state by vm.state.collectAsStateWithLifecycle()
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    val memory = rememberFocusMemory()
    val gridState = rememberLazyGridState()
    val title = if (type == LibraryType.Tv) "TV Shows" else "Movies"
    // The library's rows above the grid, from servers that arrange them (PLAN-ROWS.md Decision 11).
    val rowsVm = viewModel(key = "tv-library-rows-$type") { LibraryRowsViewModel(services.library, services.rows) }
    val rows by rowsVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.libraryId) { rowsVm.show(state.libraryId) }

    TvScaffold(if (type == LibraryType.Tv) RailSection.Shows else RailSection.Movies, onNavigate) {
        if (rowsVm.arranges && state.libraryId != null) {
            val actions = TvRowActions(onPlay, onOpenShow, onOpenMovie, { show, season, episode -> onNavigate(TvRoutes.Episode(show, season, episode)) }, notMatched, vm::toggleWatched) { id -> onNavigate(TvRoutes.MediaList(id)) }
            ArrangedLibrary(
                type, title, state, rows, actions, memory, gridState,
                chips = { m -> ChipRow(vm, state, { picker = Picker.Decade }, m, top = 0.dp) },
                onSort = { picker = Picker.Sort },
                onEditRows = { rows.screen?.let { onNavigate(TvRoutes.RowSettings(it)) } },
                onVisible = vm::onVisible,
                onOpen = { item -> item.open(onOpenShow, onOpenMovie, onPlay, notMatched) },
            )
        } else Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = ContentStart, end = ContentEnd, top = 56.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = inter(46.sp, FontWeight.ExtraBold, tracking = -0.02f), color = WmmColors.Text)
                    Text(state.summary ?: " ", style = inter(20.sp), color = WmmColors.TextMuted)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Sort", style = inter(19.sp), color = WmmColors.TextMuted)
                    TvChip(state.choices.sort.label, selected = false, onClick = { picker = Picker.Sort }, trailingIcon = WmmIcons.ChevronDown)
                }
            }
            ChipRow(vm, state, onDecade = { picker = Picker.Decade })
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.loading -> LoadingState()
                    state.error != null -> ErrorState(state.error!!, vm::reload, title = "Couldn't load $title")
                    state.items.isEmpty() && state.choices.narrowed -> EmptyState(
                        "Nothing matches",
                        "No ${title.lowercase()} match these filters.",
                    ) { TvButton("Clear filters", vm::clearChoices, style = TvButtonStyle.Primary) }
                    state.items.isEmpty() -> EmptyState(
                        "No ${title.lowercase()} yet",
                        "Add them to the library in the web app and scan it; they appear here.",
                    )
                    else -> {
                        val density = LocalDensity.current
                        val spec = remember(density) { with(density) { KeepInViewSpec(leading = 20.dp.toPx(), trailing = 60.dp.toPx()) } }
                        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(8),
                                state = gridState,
                                modifier = Modifier.fillMaxSize().padding(start = ContentStart - 16.dp, end = ContentEnd - 16.dp).focusRestorer(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 60.dp),
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                verticalArrangement = Arrangement.spacedBy(28.dp),
                            ) {
                                itemsIndexed(state.items, key = { _, it -> it.id }) { i, item ->
                                    GridCard(
                                        item = item,
                                        imageUrl = services.library.imageUrl(item.posterPath, ImageSize.W342),
                                        onClick = { item.open(onOpenShow, onOpenMovie, onPlay, notMatched) },
                                        onLongClick = if (item.kind == ItemKind.Movie) ({ vm.toggleWatched(item) }) else null,
                                        modifier = Modifier
                                            .focusMemory(memory, "item:${item.id}")
                                            .onFocusChanged { if (it.isFocused) vm.onVisible(i) },
                                    )
                                }
                                if (state.loadingMore || state.moreError != null) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Text(
                                            state.moreError ?: "Loading more…",
                                            style = inter(19.sp),
                                            color = if (state.moreError != null) WmmColors.Error else WmmColors.TextMuted,
                                            modifier = Modifier.padding(vertical = 12.dp),
                                        )
                                    }
                                }
                            }
                        }
                        LaunchedEffect(gridState) {
                            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect(vm::onVisible)
                        }
                        LaunchedEffect(state.choices) {
                            withFrameNanos { }
                            memory.restore(fallback = state.items.firstOrNull()?.let { "item:${it.id}" })
                        }
                    }
                }
            }
        }
        Notice(state.notice ?: rows.message, { vm.noticeShown(); rowsVm.messageShown() })
    }

    when (picker) {
        Picker.Sort -> OptionsDialog(
            title = "Sort by",
            options = vm.sorts,
            selected = state.choices.sort,
            label = { it.label },
            onSelect = {
                picker = null
                vm.setSort(it)
            },
            onDismiss = { picker = null },
        )
        Picker.Decade -> OptionsDialog(
            title = "Decade",
            options = listOf<Int?>(null) + state.decades,
            selected = state.choices.decade,
            label = { it?.let { d -> "${d}s" } ?: "Any decade" },
            onSelect = {
                picker = null
                vm.setDecade(it)
            },
            onDismiss = { picker = null },
        )
        null -> Unit
    }
}

/**
 * Movies or TV Shows with their rows (android-tv/LibraryRows.dc.html): the
 * title and its counts, the library's rows in the person's order (a list's
 * ending with See all), Edit rows (Settings › Home rows on this screen),
 * then All movies with Sort, the chips and the grid, in one scroll.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArrangedLibrary(
    type: LibraryType,
    title: String,
    state: LibraryViewModel.UiState,
    rows: LibraryRowsViewModel.State,
    actions: TvRowActions,
    memory: media.watchmy.client.tv.components.FocusMemory,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    chips: @Composable (Modifier) -> Unit,
    onSort: () -> Unit,
    onEditRows: () -> Unit,
    onVisible: (Int) -> Unit,
    onOpen: (BrowseItem) -> Unit,
) {
    val services = LocalAppServices.current
    val drawn = rows.rows.drawn(live = false)
    val noun = if (type == LibraryType.Tv) "shows" else "movies"
    val density = LocalDensity.current
    val spec = remember(density) { with(density) { KeepInViewSpec(leading = 40.dp.toPx(), trailing = 60.dp.toPx()) } }
    val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            state = gridState,
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(start = ContentStart, end = ContentEnd, top = 56.dp, bottom = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item("head", span = full) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = inter(46.sp, FontWeight.ExtraBold, tracking = -0.02f), color = WmmColors.Text)
                    val lists = drawn.count { it.listId != null }
                    Text(listOfNotNull(state.summary, lists.takeIf { it > 0 }?.let { plural(it, "list") }).joinToString(" · ").ifEmpty { " " }, style = inter(20.sp), color = WmmColors.TextMuted)
                }
            }
            drawn.forEach { row ->
                item("row-${row.id}", span = full) { TvArrangedRow(row, services.library, memory, actions, Modifier.bleed(ContentStart, ContentEnd)) }
            }
            item("edit", span = full) {
                Row { TvButton("Edit rows", onEditRows, Modifier.focusMemory(memory, "edit"), icon = WmmIcons.Sliders) }
            }
            item("all", span = full) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("All $noun", style = inter(28.sp, FontWeight.SemiBold), color = WmmColors.Text, modifier = Modifier.alignByBaseline())
                        if (!state.loading) Text(formatCount(state.total), style = inter(19.sp, FontWeight.Medium), color = WmmColors.TextMuted, modifier = Modifier.alignByBaseline())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Sort", style = inter(19.sp), color = WmmColors.TextMuted)
                        TvChip(state.choices.sort.label, selected = false, onClick = onSort, trailingIcon = WmmIcons.ChevronDown, modifier = Modifier.focusMemory(memory, "sort"))
                    }
                }
            }
            item("chips", span = full) { chips(Modifier.bleed(ContentStart, ContentEnd)) }
            when {
                state.loading -> item("loading", span = full) { Box(Modifier.fillMaxWidth().height(300.dp)) { LoadingState() } }
                state.error != null -> item("error", span = full) { Text(state.error.orEmpty(), style = inter(22.sp), color = WmmColors.Error) }
                state.items.isEmpty() -> item("empty", span = full) {
                    Text(if (state.choices.narrowed) "No ${title.lowercase()} match these filters." else "No ${title.lowercase()} yet.", style = inter(22.sp), color = WmmColors.TextMuted)
                }
                else -> {
                    itemsIndexed(state.items, key = { _, it -> it.id }) { i, item ->
                        GridCard(
                            item = item,
                            imageUrl = services.library.imageUrl(item.posterPath, ImageSize.W342),
                            onClick = { onOpen(item) },
                            onLongClick = if (item.kind == ItemKind.Movie) ({ actions.onToggleWatched(item) }) else null,
                            modifier = Modifier.focusMemory(memory, "item:${item.id}").onFocusChanged { if (it.isFocused) onVisible(i) },
                        )
                    }
                    if (state.loadingMore || state.moreError != null) {
                        item("more", span = full) {
                            Text(state.moreError ?: "Loading more…", style = inter(19.sp), color = if (state.moreError != null) WmmColors.Error else WmmColors.TextMuted, modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { onVisible((it - drawn.size - 4).coerceAtLeast(0)) }
    }
    // Focus starts on the first row's first card (the board's), or comes back to the card left
    // when returning from a page; a card that took focus while the page was still drawing doesn't count.
    val returning = remember { memory.key }
    val first = drawn.firstOrNull()?.cardKey() ?: state.items.firstOrNull()?.let { "item:${it.id}" } ?: "edit"
    LaunchedEffect(state.choices, first, state.loading) {
        if (state.loading) return@LaunchedEffect
        val target = returning ?: first
        // The grid's first page may have taken focus and scrolled the rows away: bring them back first.
        if (returning == null) gridState.scrollToItem(0)
        repeat(10) {
            withFrameNanos { }
            if (runCatching { memory.requester(target).requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
        memory.restore(fallback = first)
    }
}

/** Filters, a divider, then genres and Decade; scrolls sideways when the library has many genres. */
@Composable
private fun ChipRow(vm: LibraryViewModel, state: LibraryViewModel.UiState, onDecade: () -> Unit, modifier: Modifier = Modifier, top: androidx.compose.ui.unit.Dp = 22.dp) {
    val c = state.choices
    LazyRow(
        modifier.fillMaxWidth().padding(top = top, start = ContentStart - 12.dp).focusRestorer(),
        contentPadding = PaddingValues(start = 12.dp, end = ContentEnd, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(vm.filters, key = { "f-$it" }) { f -> TvChip(f.label, selected = c.filter == f, onClick = { vm.setFilter(f) }) }
        if (state.genres.isNotEmpty() || state.decades.isNotEmpty()) {
            item("divider") { Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(32.dp).background(WmmColors.Line2)) }
        }
        items(state.genres, key = { "g-$it" }) { g -> TvChip(g, selected = c.genre == g, onClick = { vm.toggleGenre(g) }) }
        if (state.decades.isNotEmpty()) {
            item("decade") {
                TvChip(c.decade?.let { "${it}s" } ?: "Decade", selected = c.decade != null, onClick = onDecade, trailingIcon = WmmIcons.ChevronDown)
            }
        }
    }
}

/** A 190×285 poster (`.card`): the New badge, the watched check and progress (no quality badge, PLAN-HOME.md §5). */
@Composable
private fun GridCard(item: BrowseItem, imageUrl: String?, onClick: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    MediaCard(
        title = item.title,
        subtitle = item.gridSubtitle(),
        imageUrl = imageUrl,
        artWidth = 190.dp,
        artHeight = 285.dp,
        onClick = onClick,
        onLongClick = onLongClick,
        focusedScale = 1.08f,
        modifier = modifier,
    ) {
        if (item.isNew) ArtBadge("New", Modifier.align(Alignment.TopStart).padding(8.dp), BadgeStyle.Gold)
        ArtWatched(item.watched)
        ArtProgress(item.progress)
    }
}
