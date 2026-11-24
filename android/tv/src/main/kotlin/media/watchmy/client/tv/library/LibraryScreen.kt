package media.watchmy.client.tv.library

import media.watchmy.client.ui.imageFor
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import media.watchmy.client.model.ItemFilter
import media.watchmy.client.tv.components.PosterHeight
import media.watchmy.client.tv.components.PosterWidth
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.watchlists.holdFor
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.BrowseRequest
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryType
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
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
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
import media.watchmy.client.presentation.library.LibraryViewModel
import media.watchmy.client.presentation.rows.LibraryRowsViewModel
import media.watchmy.client.tv.navigation.TvRoutes
import media.watchmy.client.tv.rows.TvArrangedRow
import media.watchmy.client.tv.rows.TvRowActions
import media.watchmy.client.tv.rows.bleed
import media.watchmy.client.tv.rows.cardKey
import media.watchmy.client.tv.rows.tvLibrarySeeAll
import media.watchmy.client.ui.ReportShown
import androidx.compose.runtime.derivedStateOf
import media.watchmy.client.model.ItemSort
import androidx.compose.ui.focus.focusProperties

/**
 * TV Shows or Movies: the title and what it holds, All and Unwatched, Filter
 * and Sort (one panel from the right), the focused title's facts in one
 * line, then a grid of posters six across with the next row peeking,
 * loading more as focus nears the end. A row's See all, on Home or above
 * the grid, opens it sorted the row's way with focus on its first card.
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
    val vm = viewModel(key = "library-$type") {
        LibraryViewModel(
            services.library, type, createSavedStateHandle(), pageSize = PAGE_SIZE, columns = COLUMNS, countsLine = true, requests = services.browseRequests,
            others = media.watchmy.client.presentation.servers.OtherServers(services.servers, services.library),
        )
    }
    ReportShown(vm::setShown)
    val state by vm.state.collectAsStateWithLifecycle()
    // The Sort and filter panel: closed, open on its rows (null start), or straight on Sort by.
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    var panelStart by rememberSaveable { mutableStateOf<FilterList?>(null) }
    val openPanel: (FilterList?) -> Unit = { start ->
        panelStart = start
        panelOpen = true
    }
    // The grid card with focus, whose facts show in the line above the grid.
    var focusedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val memory = rememberFocusMemory()
    val gridState = rememberLazyGridState()
    // Whether focus is somewhere on this screen's content already, which first focus leaves alone.
    var contentFocused by remember { mutableStateOf(false) }
    // The A–Z strip, when sorted by title, and two rows at a time with Page Up/Down or CH+/CH−.
    val jumps = rememberGridJumps(vm, memory, gridState)
    val byTitle = state.choices.sort == ItemSort.Title && !state.letters.isNullOrEmpty()
    var onStrip by remember { mutableStateOf(false) }
    LaunchedEffect(state.jump?.revision) { jumps.jumped(state) }
    val title = if (type == LibraryType.Tv) "TV Shows" else "Movies"
    // The library's rows above the grid, from servers that arrange them.
    val rowsVm = viewModel(key = "tv-library-rows-$type") { LibraryRowsViewModel(services.library, services.rows) }
    ReportShown(rowsVm::setShown)
    val rows by rowsVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.libraryId) { rowsVm.show(state.libraryId) }

    TvScaffold(if (type == LibraryType.Tv) RailSection.Shows else RailSection.Movies, onNavigate) {
        if (rowsVm.arranges && state.libraryId != null) {
            val actions = TvRowActions(onPlay, onOpenShow, onOpenMovie, { show, season, episode -> onNavigate(TvRoutes.Episode(show, season, episode)) }, notMatched, { vm.setWatched(it, !it.watched) }, { id -> onNavigate(TvRoutes.MediaList(id, type = if (type == LibraryType.Movie) "movie" else "tv")) })
            ArrangedLibrary(
                type, title, state, rows, actions, memory, gridState, jumps, byTitle,
                controls = { m -> LibraryControls(state.choices, memory, vm::clearFilters, { vm.setFilter(ItemFilter.Unwatched) }, { openPanel(null) }, { openPanel(FilterList.Sort) }, m) },
                focusedId = focusedId,
                onFocusItem = { focusedId = it },
                onEditRows = { rows.screen?.let { onNavigate(TvRoutes.RowSettings(it)) } },
                onVisible = vm::onVisible,
                onOpen = { item -> item.open(onOpenShow, onOpenMovie, onPlay, notMatched) },
                onSeeAll = vm::open,
                onGridShown = vm::gridShown,
                onRetry = vm::retry,
            )
        } else Column(Modifier.fillMaxSize().onFocusChanged { contentFocused = it.hasFocus }) {
            Row(
                Modifier.fillMaxWidth().padding(start = ContentStart, end = ContentEnd, top = SafeY).heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LibraryTitle(title, state.countsLine, Modifier.weight(1f))
                LibraryControls(state.choices, memory, vm::clearFilters, { vm.setFilter(ItemFilter.Unwatched) }, { openPanel(null) }, { openPanel(FilterList.Sort) })
            }
            // A grid from every server: a line under the header for one still out, one that didn't answer (with Try again), or one that can't filter so.
            if (state.merged) ServersLine(state.notes, if (state.retryServers) vm::retryServers else null, Modifier.padding(start = ContentStart, end = ContentEnd, top = 8.dp))
            FocusedFacts(state.items.firstOrNull { it.id == focusedId }, Modifier.padding(start = ContentStart, end = ContentEnd, top = 26.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.loading && state.items.isEmpty() -> LoadingState()
                    state.error != null -> ErrorState(state.error!!, vm::retry, title = "Couldn't load $title")
                    state.noLibrary -> EmptyState("No ${title.lowercase()} library", noLibrary(type))
                    state.items.isEmpty() && state.choices.narrowed -> EmptyState(
                        "Nothing matches",
                        "No ${title.lowercase()} match these filters.",
                    ) { TvButton("Clear filters", vm::clearFilters, style = TvButtonStyle.Primary) }
                    state.items.isEmpty() -> EmptyState(
                        "No ${title.lowercase()} yet",
                        "Add them to the library in the web app and scan it; they appear here.",
                    )
                    else -> {
                        val density = LocalDensity.current
                        val spec = remember(density) { with(density) { KeepInViewSpec(leading = 24.dp.toPx(), trailing = 120.dp.toPx()) } }
                        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(COLUMNS),
                                state = gridState,
                                modifier = Modifier.fillMaxHeight().padding(start = ContentStart - GridInset).width(GridWidth + GridInset * 2).focusRestorer().pageKeys(jumps),
                                contentPadding = PaddingValues(start = GridInset, end = GridInset, top = 24.dp, bottom = 60.dp),
                                horizontalArrangement = Arrangement.spacedBy(GridGapX),
                                verticalArrangement = Arrangement.spacedBy(GridGapY),
                            ) {
                                itemsIndexed(state.items, key = { _, it -> it.gridKey() }) { i, item ->
                                    GridCard(
                                        item = item,
                                        imageUrl = services.library.imageFor(item.where, item.posterPath, ImageSize.W342),
                                        onClick = { item.open(onOpenShow, onOpenMovie, onPlay, notMatched) },
                                        onLongClick = holdFor(item) { vm.setWatched(it, !it.watched) },
                                        modifier = Modifier
                                            .focusMemory(memory, "item:${item.id}")
                                            .toStrip(jumps, byTitle && i % COLUMNS == COLUMNS - 1)
                                            .onFocusChanged {
                                                if (it.isFocused) {
                                                    jumps.focusedIndex = i
                                                    focusedId = item.id
                                                    vm.onVisible(i)
                                                }
                                            },
                                    )
                                }
                                if (state.loadingMore || state.moreError != null) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Text(
                                            state.moreError ?: "Loading more…",
                                            style = inter(22.sp),
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
                        if (byTitle) {
                            Strip(state, type, jumps, { onStrip = it }, Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = ContentEnd))
                        }
                        // Once, when the grid first shows (or comes back after Back): a filter
                        // chosen in the panel leaves focus where the panel was opened from.
                        LaunchedEffect(Unit) {
                            withFrameNanos { }
                            if (!contentFocused) memory.restore(fallback = state.items.firstOrNull()?.let { "item:${it.id}" })
                        }
                        // A row's See all from Home: the grid's first card, once its first page is in.
                        val toGrid = state.toGrid && !state.loading && !state.refreshing
                        LaunchedEffect(toGrid) {
                            if (!toGrid) return@LaunchedEffect
                            gridState.scrollToItem(0)
                            state.items.firstOrNull()?.let { focusSoon(memory, "item:${it.id}") }
                            vm.gridShown()
                        }
                    }
                }
            }
        }
        Notice(state.message ?: rows.message, { vm.messageShown(); rowsVm.messageShown() })
    }

    if (panelOpen) {
        SortFilterPanel(
            type = type,
            state = state,
            filters = vm.filters,
            sorts = vm.sorts,
            start = panelStart,
            onFilter = vm::setFilter,
            onGenre = vm::setGenre,
            onDecade = vm::setDecade,
            onSort = vm::setSort,
            onClear = vm::clearFilters,
            onDismiss = { panelOpen = false },
            onRating = vm::setRating,
        )
    }
}

/**
 * Movies or TV Shows with their rows: the
 * title and its counts, the library's rows in the person's order (each with
 * somewhere to go ending with See all: a list's page, or the grid below
 * sorted the row's way, [onSeeAll]), Edit rows (Settings › Home rows on
 * this screen), then All movies with All, Unwatched, Filter and Sort, and
 * the grid, in one scroll. A poster's facts stay at the top while it has
 * focus.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArrangedLibrary(
    type: LibraryType,
    title: String,
    state: LibraryViewModel.State,
    rows: LibraryRowsViewModel.State,
    actions: TvRowActions,
    memory: media.watchmy.client.tv.components.FocusMemory,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    jumps: GridJumps,
    byTitle: Boolean,
    controls: @Composable (Modifier) -> Unit,
    focusedId: Long?,
    onFocusItem: (Long) -> Unit,
    onEditRows: () -> Unit,
    onVisible: (Int) -> Unit,
    onOpen: (BrowseItem) -> Unit,
    onSeeAll: (BrowseRequest) -> Unit,
    onGridShown: () -> Unit,
    onRetry: () -> Unit,
) {
    val services = LocalAppServices.current
    val drawn = rows.rows.drawn(live = false)
    val noun = if (type == LibraryType.Tv) "shows" else "movies"
    val density = LocalDensity.current
    // The focused title's facts line covers the screen's top while the grid has focus: cards stay clear of it.
    val spec = remember(density) { with(density) { KeepInViewSpec(leading = 150.dp.toPx(), trailing = 120.dp.toPx()) } }
    val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
    // Sorted by title, the grid makes room for the A–Z strip; the rows still reach the screen's edge.
    val end = 1920.dp - ContentStart - GridWidth
    jumps.header = drawn.size + 3
    var onStrip by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) { Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            state = gridState,
            modifier = Modifier.fillMaxSize().focusRestorer().pageKeys(jumps),
            contentPadding = PaddingValues(start = ContentStart, end = end, top = SafeY, bottom = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(GridGapX),
            verticalArrangement = Arrangement.spacedBy(GridGapY),
        ) {
            item("head", span = full) {
                val lists = drawn.count { it.listId != null }
                LibraryTitle(title, listOfNotNull(state.countsLine, lists.takeIf { it > 0 }?.let { plural(it, "list") }).joinToString(" · ").ifEmpty { null })
            }
            drawn.forEach { row ->
                item("row-${row.id}", span = full) {
                    TvArrangedRow(row, services.library, memory, actions, Modifier.bleed(ContentStart, end), seeAll = tvLibrarySeeAll(row, type, state.libraryId, actions, onSeeAll))
                }
            }
            item("edit", span = full) {
                Row { TvButton("Edit rows", onEditRows, Modifier.focusMemory(memory, "edit"), icon = WmmIcons.Sliders, growFrom = TransformOrigin(0f, 0.5f)) }
            }
            item("all", span = full) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("All $noun", style = TvType.CardTitle, color = WmmColors.Text, modifier = Modifier.alignByBaseline())
                        if (!state.loading || state.items.isNotEmpty()) Text(formatCount(state.total), style = TvType.Second, color = TvPalette.Muted, modifier = Modifier.alignByBaseline())
                    }
                    controls(Modifier)
                }
            }
            when {
                state.loading && state.items.isEmpty() -> item("loading", span = full) { Box(Modifier.fillMaxWidth().height(300.dp)) { LoadingState() } }
                state.error != null -> item("error", span = full) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.error.orEmpty(), style = TvType.Body, color = WmmColors.TextSoft, modifier = Modifier.weight(1f, fill = false))
                        TvButton("Try again", onRetry, Modifier.focusMemory(memory, "retry"))
                    }
                }
                state.noLibrary -> item("none", span = full) { Text(noLibrary(type), style = inter(22.sp), color = WmmColors.TextMuted) }
                state.items.isEmpty() -> item("empty", span = full) {
                    Text(if (state.choices.narrowed) "No ${title.lowercase()} match these filters." else "No ${title.lowercase()} yet.", style = inter(22.sp), color = WmmColors.TextMuted)
                }
                else -> {
                    itemsIndexed(state.items, key = { _, it -> it.gridKey() }) { i, item ->
                        GridCard(
                            item = item,
                            imageUrl = services.library.imageFor(item.where, item.posterPath, ImageSize.W342),
                            onClick = { onOpen(item) },
                            onLongClick = holdFor(item, actions.onToggleWatched),
                            modifier = Modifier.focusMemory(memory, "item:${item.id}").toStrip(jumps, byTitle && i % COLUMNS == COLUMNS - 1).onFocusChanged {
                                if (it.isFocused) {
                                    jumps.focusedIndex = i
                                    onFocusItem(item.id)
                                    onVisible(i)
                                }
                            },
                        )
                    }
                    if (state.loadingMore || state.moreError != null) {
                        item("more", span = full) {
                            Text(state.moreError ?: "Loading more…", style = inter(22.sp), color = if (state.moreError != null) WmmColors.Error else WmmColors.TextMuted, modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                }
            }
        }
        // The strip shows once the grid's posters are on screen, below the rows.
        val gridInView by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.any { it.index >= jumps.header } } }
        if (byTitle && gridInView) {
            Strip(state, type, jumps, { onStrip = it }, Modifier.align(Alignment.TopEnd).padding(top = 150.dp, end = ContentEnd))
        }
        // While a poster has focus, its facts stay in one line at the top of the screen, over the scrolled rows.
        val focusedItem = state.items.firstOrNull { it.id == focusedId }
        if (gridInView && focusedItem != null && memory.key?.startsWith("item:") == true) {
            FocusedFacts(
                focusedItem,
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(0f to WmmColors.Ground, 0.75f to WmmColors.Ground, 1f to WmmColors.Ground.copy(alpha = 0f)))
                    .padding(start = ContentStart, end = ContentEnd, top = SafeY, bottom = 36.dp),
            )
        }
    } }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { onVisible((it - drawn.size - 4).coerceAtLeast(0)) }
    }
    // Focus starts on the first row's first card (the board's), or comes back to the card left
    // when returning from a page; a card that took focus while the page was still drawing doesn't count.
    val returning = remember { memory.key }
    val first = drawn.firstOrNull()?.cardKey() ?: state.items.firstOrNull()?.let { "item:${it.id}" } ?: "edit"
    // Where first focus went. Rows that arrive after the grid move it up to them, but only while
    // it is still there: once the person has moved it (a chip, a filter), the page leaves it be,
    // where it used to throw it back to the top after every filter.
    var placed by remember { mutableStateOf<String?>(null) }
    // A row's See all, on Home or above the grid, lands on the grid's first card under All shows, the rows a
    // scroll up away. Rows that arrive afterwards leave focus there.
    var atGrid by remember { mutableStateOf(false) }
    val gridAsked = state.toGrid && !state.loading && !state.refreshing
    LaunchedEffect(gridAsked) {
        if (!gridAsked) return@LaunchedEffect
        atGrid = true
        gridState.scrollToItem(drawn.size + 2)
        state.items.firstOrNull()?.let { item -> if (focusSoon(memory, "item:${item.id}")) placed = "item:${item.id}" }
        onGridShown()
    }
    val ready = !(state.loading && state.items.isEmpty())
    LaunchedEffect(first, ready) {
        if (state.toGrid || atGrid) return@LaunchedEffect
        if (!ready || placed != null && memory.key != placed) return@LaunchedEffect
        val target = if (placed == null) returning ?: first else first
        // The grid's first page may have taken focus and scrolled the rows away: bring them back first.
        if (target == first) gridState.scrollToItem(0)
        repeat(10) {
            withFrameNanos { }
            if (runCatching { memory.requester(target).requestFocus() }.getOrDefault(false)) {
                placed = target
                return@LaunchedEffect
            }
        }
        memory.restore(fallback = first)
        placed = memory.key
    }
}

/** Puts focus on [key] as soon as its card is composed, within ten frames; false if it never was. */
private suspend fun focusSoon(memory: media.watchmy.client.tv.components.FocusMemory, key: String): Boolean {
    repeat(10) {
        withFrameNanos { }
        if (runCatching { memory.requester(key).requestFocus() }.getOrDefault(false)) return true
    }
    return false
}

/** A poster (`.card.p`): its title under it, the New flag, the watched tick and progress (no quality badge); the line above the grid says the rest. */
@Composable
private fun GridCard(item: BrowseItem, imageUrl: String?, onClick: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    MediaCard(
        title = item.title,
        subtitle = null,
        imageUrl = imageUrl,
        artWidth = PosterWidth,
        artHeight = PosterHeight,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        subtitleLine = false,
    ) {
        if (item.isNew) ArtBadge("New", Modifier.align(Alignment.TopStart).padding(10.dp), BadgeStyle.Gold)
        // A show partway through says how many episodes are left ("4 left"), where the server counts them.
        val left = item.unwatchedCount?.takeIf { item.kind == media.watchmy.client.model.ItemKind.Show && it > 0 && !item.watched }
        if (left != null) ArtBadge("$left left", Modifier.align(Alignment.TopEnd).padding(10.dp))
        ArtWatched(item.watched)
        ArtProgress(item.progress)
    }
}

/** The grid's posters: six across, 40 apart, with 30 between rows, so the next row peeks at the foot. */
private val GridGapX = 40.dp
private val GridGapY = 30.dp
private val GridWidth = PosterWidth * COLUMNS + GridGapX * (COLUMNS - 1)

/** Room at the grid's edges for a focused poster's growth and ring. */
private val GridInset = 16.dp

/** The TV grid's columns, and a page of six rows of them. */
internal const val COLUMNS = 6
private const val PAGE_SIZE = COLUMNS * 6

private fun noLibrary(type: LibraryType) =
    if (type == LibraryType.Tv) "This server has no TV library yet. Add one in the web app." else "This server has no movie library yet. Add one in the web app."

/** The strip for this grid's letters, telling [onStrip] whether focus is on it. */
@Composable
private fun Strip(state: LibraryViewModel.State, type: LibraryType, jumps: GridJumps, onStrip: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    LetterStrip(
        letters = state.letters.orEmpty(),
        total = state.total,
        noun = if (type == LibraryType.Tv) "show" else "movie",
        current = jumps.currentLetter(state),
        onPreview = jumps::preview,
        onPick = jumps::pick,
        onBack = jumps::back,
        entryRequester = jumps.strip,
        modifier = modifier.onFocusChanged {
            if (it.hasFocus) jumps.enteredStrip()
            onStrip(it.hasFocus)
        },
    )
}

/** ▶ from a poster in the grid's last column goes to the A–Z strip ([on]), not wherever is nearest. */
private fun Modifier.toStrip(jumps: GridJumps, on: Boolean): Modifier =
    if (on) focusProperties { right = jumps.strip } else this


/** A grid card's key: its server's, as two servers' ids can be the same. */
private fun BrowseItem.gridKey(): String = "${where.serverId}:${kind}:${id}"

/**
 * The header's second line on a grid from every server: what a server
 * still out, one that didn't answer or one too old for a filter means for
 * the grid, with Try again for the one that didn't answer. Its height is
 * kept when there is nothing to say, so the grid doesn't jump.
 */
@Composable
private fun ServersLine(notes: List<String>, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        val line = notes.firstOrNull { !it.startsWith("Still asking") } ?: notes.firstOrNull()
        if (line != null) Text(line, style = TvType.Second, color = WmmColors.TextSoft, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (onRetry != null) TvButton("Try again", onRetry, size = media.watchmy.client.tv.components.TvButtonSize.Small)
    }
}
