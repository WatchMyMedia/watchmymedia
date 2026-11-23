package media.watchmy.client.tv.library

import media.watchmy.client.tv.components.FocusGrowth
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
import media.watchmy.client.model.BrowseRequest
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
import media.watchmy.client.presentation.library.LibraryViewModel
import media.watchmy.client.presentation.library.gridSubtitle
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

private enum class Picker { Sort, Decade }

/**
 * TV Shows or Movies:
 * an 8-column poster grid under the sort control and the filter, genre and
 * decade chips, loading more as focus nears the end. A row's See all, on
 * Home or above the grid, opens it sorted the row's way with focus on its
 * first card.
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
        LibraryViewModel(services.library, type, createSavedStateHandle(), pageSize = PAGE_SIZE, columns = COLUMNS, countsLine = true, requests = services.browseRequests)
    }
    ReportShown(vm::setShown)
    val state by vm.state.collectAsStateWithLifecycle()
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
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
            val actions = TvRowActions(onPlay, onOpenShow, onOpenMovie, { show, season, episode -> onNavigate(TvRoutes.Episode(show, season, episode)) }, notMatched, { vm.setWatched(it, !it.watched) }) { id -> onNavigate(TvRoutes.MediaList(id, type = if (type == LibraryType.Movie) "movie" else "tv")) }
            ArrangedLibrary(
                type, title, state, rows, actions, memory, gridState, jumps, byTitle,
                chips = { m -> ChipRow(vm, state, memory, { picker = Picker.Decade }, m, top = 0.dp) },
                onSort = { picker = Picker.Sort },
                onEditRows = { rows.screen?.let { onNavigate(TvRoutes.RowSettings(it)) } },
                onVisible = vm::onVisible,
                onOpen = { item -> item.open(onOpenShow, onOpenMovie, onPlay, notMatched) },
                onSeeAll = vm::open,
                onGridShown = vm::gridShown,
            )
        } else Column(Modifier.fillMaxSize().onFocusChanged { contentFocused = it.hasFocus }) {
            Row(
                Modifier.fillMaxWidth().padding(start = ContentStart, end = ContentEnd, top = 56.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = inter(48.sp, FontWeight.ExtraBold, tracking = -0.02f), color = WmmColors.Text)
                    Text(state.countsLine ?: " ", style = inter(22.sp), color = WmmColors.TextMuted)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Sort", style = inter(22.sp), color = WmmColors.TextMuted)
                    TvChip(state.choices.sort.label, selected = false, onClick = { picker = Picker.Sort }, trailingIcon = WmmIcons.ChevronDown)
                }
            }
            ChipRow(vm, state, memory, onDecade = { picker = Picker.Decade })
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
                        val spec = remember(density) { with(density) { KeepInViewSpec(leading = 20.dp.toPx(), trailing = 60.dp.toPx()) } }
                        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(8),
                                state = gridState,
                                modifier = Modifier.fillMaxSize().padding(start = ContentStart - 16.dp, end = ContentEnd - 16.dp + if (byTitle) StripRoom else 0.dp).focusRestorer().pageKeys(jumps),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 60.dp),
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                verticalArrangement = Arrangement.spacedBy(28.dp),
                            ) {
                                itemsIndexed(state.items, key = { _, it -> it.id }) { i, item ->
                                    GridCard(
                                        item = item,
                                        imageUrl = services.library.imageUrl(item.posterPath, ImageSize.W342),
                                        onClick = { item.open(onOpenShow, onOpenMovie, onPlay, notMatched) },
                                        onLongClick = holdFor(item) { vm.setWatched(it, !it.watched) },
                                        narrow = byTitle,
                                        modifier = Modifier
                                            .focusMemory(memory, "item:${item.id}")
                                            .toStrip(jumps, byTitle && i % COLUMNS == COLUMNS - 1)
                                            .onFocusChanged {
                                                if (it.isFocused) {
                                                    jumps.focusedIndex = i
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
                            Strip(state, type, jumps, { onStrip = it }, Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = ContentEnd - 16.dp))
                        }
                        JumpHints(onStrip, jumps.stripLetter, byTitle, Modifier.align(Alignment.BottomStart))
                        // Once, when the grid first shows (or comes back after Back): a filter
                        // chosen on the chips leaves focus on its chip.
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
 * Movies or TV Shows with their rows: the
 * title and its counts, the library's rows in the person's order (each with
 * somewhere to go ending with See all: a list's page, or the grid below
 * sorted the row's way, [onSeeAll]), Edit rows (Settings › Home rows on
 * this screen), then All movies with Sort, the chips and the grid, in one
 * scroll.
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
    chips: @Composable (Modifier) -> Unit,
    onSort: () -> Unit,
    onEditRows: () -> Unit,
    onVisible: (Int) -> Unit,
    onOpen: (BrowseItem) -> Unit,
    onSeeAll: (BrowseRequest) -> Unit,
    onGridShown: () -> Unit,
) {
    val services = LocalAppServices.current
    val drawn = rows.rows.drawn(live = false)
    val noun = if (type == LibraryType.Tv) "shows" else "movies"
    val density = LocalDensity.current
    val spec = remember(density) { with(density) { KeepInViewSpec(leading = 40.dp.toPx(), trailing = 60.dp.toPx()) } }
    val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
    // Sorted by title, the grid makes room for the A–Z strip; the rows still reach the screen's edge.
    val end = ContentEnd + if (byTitle) StripRoom else 0.dp
    jumps.header = drawn.size + 4
    var onStrip by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) { Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            state = gridState,
            modifier = Modifier.fillMaxSize().focusRestorer().pageKeys(jumps),
            contentPadding = PaddingValues(start = ContentStart, end = end, top = 56.dp, bottom = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item("head", span = full) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = inter(48.sp, FontWeight.ExtraBold, tracking = -0.02f), color = WmmColors.Text)
                    val lists = drawn.count { it.listId != null }
                    Text(listOfNotNull(state.countsLine, lists.takeIf { it > 0 }?.let { plural(it, "list") }).joinToString(" · ").ifEmpty { " " }, style = inter(22.sp), color = WmmColors.TextMuted)
                }
            }
            drawn.forEach { row ->
                item("row-${row.id}", span = full) {
                    TvArrangedRow(row, services.library, memory, actions, Modifier.bleed(ContentStart, end), seeAll = tvLibrarySeeAll(row, type, state.libraryId, actions, onSeeAll))
                }
            }
            item("edit", span = full) {
                Row { TvButton("Edit rows", onEditRows, Modifier.focusMemory(memory, "edit"), icon = WmmIcons.Sliders) }
            }
            item("all", span = full) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("All $noun", style = inter(28.sp, FontWeight.SemiBold), color = WmmColors.Text, modifier = Modifier.alignByBaseline())
                        if (!state.loading || state.items.isNotEmpty()) Text(formatCount(state.total), style = inter(22.sp, FontWeight.Medium), color = WmmColors.TextMuted, modifier = Modifier.alignByBaseline())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Sort", style = inter(22.sp), color = WmmColors.TextMuted)
                        TvChip(state.choices.sort.label, selected = false, onClick = onSort, trailingIcon = WmmIcons.ChevronDown, modifier = Modifier.focusMemory(memory, "sort"))
                    }
                }
            }
            item("chips", span = full) { chips(Modifier.bleed(ContentStart, end)) }
            when {
                state.loading && state.items.isEmpty() -> item("loading", span = full) { Box(Modifier.fillMaxWidth().height(300.dp)) { LoadingState() } }
                state.error != null -> item("error", span = full) { Text(state.error.orEmpty(), style = inter(22.sp), color = WmmColors.Error) }
                state.noLibrary -> item("none", span = full) { Text(noLibrary(type), style = inter(22.sp), color = WmmColors.TextMuted) }
                state.items.isEmpty() -> item("empty", span = full) {
                    Text(if (state.choices.narrowed) "No ${title.lowercase()} match these filters." else "No ${title.lowercase()} yet.", style = inter(22.sp), color = WmmColors.TextMuted)
                }
                else -> {
                    itemsIndexed(state.items, key = { _, it -> it.id }) { i, item ->
                        GridCard(
                            item = item,
                            imageUrl = services.library.imageUrl(item.posterPath, ImageSize.W342),
                            onClick = { onOpen(item) },
                            onLongClick = holdFor(item, actions.onToggleWatched),
                            narrow = byTitle,
                            modifier = Modifier.focusMemory(memory, "item:${item.id}").toStrip(jumps, byTitle && i % COLUMNS == COLUMNS - 1).onFocusChanged {
                                if (it.isFocused) {
                                    jumps.focusedIndex = i
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
            Strip(state, type, jumps, { onStrip = it }, Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = ContentEnd))
        }
        if (gridInView) JumpHints(onStrip, jumps.stripLetter, byTitle, Modifier.align(Alignment.BottomStart))
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

/** Filters, a divider, then genres and Decade; scrolls sideways when the library has many genres. */
@Composable
private fun ChipRow(vm: LibraryViewModel, state: LibraryViewModel.State, memory: media.watchmy.client.tv.components.FocusMemory, onDecade: () -> Unit, modifier: Modifier = Modifier, top: androidx.compose.ui.unit.Dp = 22.dp) {
    val c = state.choices
    LazyRow(
        modifier.fillMaxWidth().padding(top = top, start = ContentStart - 12.dp).focusRestorer(),
        contentPadding = PaddingValues(start = 12.dp, end = ContentEnd, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(vm.filters, key = { "f-$it" }) { f -> TvChip(f.label, selected = c.filter == f, onClick = { vm.setFilter(f) }, modifier = Modifier.focusMemory(memory, "chip:f-$f")) }
        if (state.genres.isNotEmpty() || state.decades.isNotEmpty()) {
            item("divider") { Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(32.dp).background(WmmColors.Line2)) }
        }
        items(state.genres, key = { "g-$it" }) { g -> TvChip(g, selected = c.genre == g, onClick = { vm.setGenre(g) }, modifier = Modifier.focusMemory(memory, "chip:g-$g")) }
        if (state.decades.isNotEmpty()) {
            item("decade") {
                TvChip(c.decade?.let { "${it}s" } ?: "Decade", selected = c.decade != null, onClick = onDecade, trailingIcon = WmmIcons.ChevronDown, modifier = Modifier.focusMemory(memory, "chip:decade"))
            }
        }
    }
}

/** A 190×285 poster (`.card`): the New badge, the watched check and progress (no quality badge). */
@Composable
private fun GridCard(item: BrowseItem, imageUrl: String?, onClick: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier = Modifier, narrow: Boolean = false) {
    MediaCard(
        title = item.title,
        subtitle = item.gridSubtitle(),
        imageUrl = imageUrl,
        // Narrower beside the A–Z strip, so eight still fit.
        artWidth = if (narrow) 178.dp else 190.dp,
        artHeight = if (narrow) 267.dp else 285.dp,
        onClick = onClick,
        onLongClick = onLongClick,
        focusedScale = FocusGrowth.CARD,
        modifier = modifier,
    ) {
        if (item.isNew) ArtBadge("New", Modifier.align(Alignment.TopStart).padding(8.dp), BadgeStyle.Gold)
        ArtWatched(item.watched)
        ArtProgress(item.progress)
    }
}

/** The TV grid's columns, and a page of six rows of them. */
internal const val COLUMNS = 8
private const val PAGE_SIZE = COLUMNS * 6

private fun noLibrary(type: LibraryType) =
    if (type == LibraryType.Tv) "This server has no TV library yet. Add one in the web app." else "This server has no movie library yet. Add one in the web app."

/** The room the A–Z strip takes at the grid's right edge. */
private val StripRoom = 96.dp

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
