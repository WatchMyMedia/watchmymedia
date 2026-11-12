package media.watchmy.client.tv.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.Hero
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.tv.live.LiveChannelCard
import media.watchmy.client.model.RowScreens
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.arranged
import media.watchmy.client.model.cardPanelAt
import media.watchmy.client.model.headingLine
import media.watchmy.client.model.pictureUrl
import media.watchmy.client.model.drawn
import media.watchmy.client.model.episodeCards
import media.watchmy.client.tv.navigation.TvRoutes
import media.watchmy.client.tv.rows.OnFocusItem
import media.watchmy.client.tv.rows.TvArrangedRow
import media.watchmy.client.tv.rows.TvRowActions
import media.watchmy.client.tv.rows.cardKey
import media.watchmy.client.tv.rows.rowCard
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.airedLine
import media.watchmy.client.model.codeBadge
import media.watchmy.client.model.formatDate
import media.watchmy.client.model.heroBadges
import media.watchmy.client.model.playRequest
import media.watchmy.client.model.remainingLine
import media.watchmy.client.model.totalsLine
import media.watchmy.client.model.HomeEmptyState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.ArtBadge
import media.watchmy.client.tv.components.ArtProgress
import media.watchmy.client.tv.components.ArtWatched
import media.watchmy.client.tv.components.Backdrop
import media.watchmy.client.tv.components.CardRow
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.EmptyState
import media.watchmy.client.tv.components.ErrorState
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.Kicker
import media.watchmy.client.tv.components.MediaCard
import media.watchmy.client.tv.components.Notice
import media.watchmy.client.tv.components.PivotSpec
import media.watchmy.client.tv.components.PlayMark
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.tv.components.SectionHeader
import media.watchmy.client.tv.components.TextBright
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.TvTag
import media.watchmy.client.tv.components.bringWholeIntoView
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.overflowHeight
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.rememberNotMatchedNotice
import media.watchmy.client.ui.open
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.rememberLiveNow
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.tv.live.TvOnNowCard
import media.watchmy.client.tv.components.FocusableBox
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.WmmIcons

/**
 * Home. Arranged (servers that send `rows`): screens, one row each, paged
 * with ▲ ▼ (HomeStart.dc.html, HomeRow.dc.html; [ArrangedHomeContent]).
 * Fixed (older servers; Home.dc.html and HomeScrolled.dc.html): the hero,
 * then Continue watching, Recently added shows, Recently aired, Recently
 * added movies and Recently released in one scrolling page, with the
 * library totals at the bottom and the focused row scrolled to the top.
 * A Recently aired episode, and the hero's Go to episode when it is one,
 * opens the episode's page; Continue watching resumes straight away.
 */
@Composable
fun HomeScreen(
    onPlay: (PlayRequest) -> Unit,
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onNavigate: (Any) -> Unit,
    onOpenEpisode: (showId: Long, season: Int, episodeId: Long) -> Unit = { id, _, _ -> onOpenShow(id) },
    liveChannels: List<LiveChannel> = emptyList(),
    onWatchLive: (LiveChannel) -> Unit = {},
    liveListings: Boolean = false,
) {
    val services = LocalAppServices.current
    val vm = viewModel { HomeViewModel(services.library, services.auth, services.rows) }
    val state by vm.state.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val server by services.servers.current.collectAsStateWithLifecycle()
    val memory = rememberFocusMemory()

    TvScaffold(RailSection.Home, onNavigate) {
        when {
            state.loading && state.home == null -> HomeSkeleton()
            state.home == null && state.error != null -> ErrorState(state.error.orEmpty(), vm::retry, title = "Couldn't load Home")
            state.home != null -> {
                val home = state.home!!
                if (home.isEmpty() && liveChannels.isEmpty()) {
                    val empty = state.emptyState ?: HomeEmptyState(HomeEmptyState.Kind.Unavailable)
                    val address = server?.server?.baseUrl?.takeIf { user?.isAdmin == true && empty.offersSetup }
                    val focus = remember { FocusRequester() }
                    EmptyState(empty.title, empty.message(user?.isAdmin == true) + (address?.let { "\n\n$it" } ?: "")) {
                        TvButton("Check again", vm::retry, Modifier.focusRequester(focus))
                    }
                    InitialFocus(focus)
                } else {
                    if (home.arranged) {
                        ArrangedHomeContent(
                            home = home,
                            library = services.library,
                            memory = memory,
                            actions = TvRowActions(onPlay, onOpenShow, onOpenMovie, onOpenEpisode, rememberNotMatchedNotice(), vm::toggleMovieWatched) { id -> onNavigate(TvRoutes.MediaList(id)) },
                            liveChannels = liveChannels,
                            onWatchLive = onWatchLive,
                            liveListings = liveListings,
                            onLiveGuide = { onNavigate(RailSection.LiveTV.route) },
                            onEditRows = { onNavigate(TvRoutes.RowSettings(RowScreens.HOME)) },
                        )
                        LaunchedEffect(Unit) {
                            // The first row's cards compose inside the pager's first page a frame after it: try again once if they weren't there yet.
                            val fallback = home.arrangedFirstFocusKey(liveChannels) ?: liveChannels.firstOrNull { it.available }?.let { "live:${it.id}" }
                            withFrameNanos { }
                            if (!memory.restore(fallback)) {
                                withFrameNanos { }
                                memory.restore(fallback)
                            }
                        }
                    } else HomeContent(
                        home = home,
                        library = services.library,
                        memory = memory,
                        onPlay = onPlay,
                        onOpenShow = onOpenShow,
                        onOpenMovie = onOpenMovie,
                        onOpenEpisode = onOpenEpisode,
                        onToggleWatched = vm::toggleMovieWatched,
                        liveChannels = liveChannels,
                        onWatchLive = onWatchLive,
                        liveListings = liveListings,
                        onLiveGuide = { onNavigate(RailSection.LiveTV.route) },
                    )
                    if (!home.arranged) LaunchedEffect(Unit) {
                        withFrameNanos { }
                        memory.restore(fallback = home.firstFocusKey() ?: liveChannels.firstOrNull { it.available }?.let { "live:${it.id}" })
                    }
                }
            }
        }
        Notice(state.message, vm::messageShown)
    }
}

private fun HomeData.isEmpty() =
    hero == null && continueWatching.isEmpty() && recentlyAddedShows.isEmpty() && recentlyAired.isEmpty() && recentlyAddedMovies.isEmpty() && recentlyReleased.isEmpty() &&
        rows.orEmpty().none { it.style != RowStyle.Live && it.hasContent }

/** Arranged Home's first focus: the hero, else the first card of the first row drawn (PLAN-ROWS.md §8.2). */
internal fun HomeData.arrangedFirstFocusKey(liveChannels: List<LiveChannel>): String? {
    hero?.let { return if (it.fileId != null) "hero:play" else "hero:open" }
    val first = rows.orEmpty().drawn(live = liveChannels.any { it.available }).firstOrNull() ?: return null
    if (first.style == RowStyle.Live) return liveChannels.firstOrNull { it.available }?.let { "live:${it.id}" }
    return first.cardKey()
}

private fun HomeData.firstFocusKey(): String? = when {
    hero != null -> if (hero!!.fileId != null) "hero:play" else "hero:open"
    continueWatching.isNotEmpty() -> "cw:${continueWatching.first().fileId}"
    recentlyAddedShows.isNotEmpty() -> "shows:${recentlyAddedShows.first().id}"
    recentlyAired.isNotEmpty() -> "aired:${recentlyAired.first().episodeId}"
    recentlyAddedMovies.isNotEmpty() -> "movies:${recentlyAddedMovies.first().id}"
    else -> recentlyReleased.firstOrNull()?.let { "released:${it.id}" }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeContent(
    home: HomeData,
    library: LibraryRepository,
    memory: FocusMemory,
    onPlay: (PlayRequest) -> Unit,
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenEpisode: (Long, Int, Long) -> Unit,
    onToggleWatched: (BrowseItem) -> Unit,
    liveChannels: List<LiveChannel>,
    onWatchLive: (LiveChannel) -> Unit,
    liveListings: Boolean,
    onLiveGuide: () -> Unit,
) {
    val density = LocalDensity.current
    val notMatched = rememberNotMatchedNotice()
    // A focused card's top lands at 124: its row heading sits at 72, as in the scrolled mockup.
    val spec = remember(density) { PivotSpec(with(density) { 124.dp.toPx() }) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            home.hero?.let { hero ->
                // An episode's season comes from its Recently aired card; without one the hero goes to the show.
                val episode = hero.episodeId?.takeIf { hero.kind == Hero.Kind.Episode }?.let { id -> home.recentlyAired.firstOrNull { it.episodeId == id } }
                val openEpisode = episode?.let { e -> { onOpenEpisode(e.showId, e.season, e.episodeId) } }
                item("hero", contentType = "hero") { HeroSection(hero, home, library, memory, onPlay, onOpenShow, onOpenMovie, openEpisode) }
            }
            if (home.hero == null) item("top", contentType = "top") { Box(Modifier.height(72.dp)) }

            if (home.continueWatching.isNotEmpty()) {
                // The rows are all a heading over a CardRow, so one row's composition can be reused for another.
                item("cw", contentType = "row") {
                    Column {
                        SectionHeader("Continue watching")
                        CardRow {
                            items(home.continueWatching, key = { it.fileId }) { c ->
                                MediaCard(
                                    title = c.title,
                                    subtitle = c.remainingLine,
                                    imageUrl = library.imageUrl(c.stillPath ?: c.backdropPath, ImageSize.W780),
                                    artWidth = 320.dp,
                                    artHeight = 180.dp,
                                    onClick = { onPlay(c.playRequest()) },
                                    onLongClick = { c.open(onOpenShow, onOpenMovie) },
                                    modifier = Modifier.focusMemory(memory, "cw:${c.fileId}"),
                                ) {
                                    PlayMark()
                                    ArtProgress(c.progress)
                                }
                            }
                        }
                    }
                }
            }

            if (liveChannels.any { it.available }) {
                item("live", contentType = "row") { LiveRow(liveChannels, liveListings, memory, onWatchLive, onLiveGuide) }
            }

            if (home.recentlyAddedShows.isNotEmpty()) {
                item("shows", contentType = "row") {
                    Column {
                        SectionHeader("Recently added", small = "TV Shows")
                        CardRow {
                            items(home.recentlyAddedShows, key = { it.id }) { s ->
                                PosterCard(s, library, onClick = { s.open(onOpenShow, onOpenMovie, onPlay, notMatched, onOpenEpisode) }, modifier = Modifier.focusMemory(memory, "shows:${s.id}"))
                            }
                        }
                    }
                }
            }

            if (home.recentlyAired.isNotEmpty()) {
                item("aired", contentType = "row") {
                    Column {
                        SectionHeader("Recently aired", small = "Episodes")
                        CardRow {
                            items(home.recentlyAired, key = { it.episodeId }) { e ->
                                MediaCard(
                                    title = e.showTitle,
                                    subtitle = e.airedLine(),
                                    imageUrl = library.imageUrl(e.stillPath ?: e.showBackdropPath, ImageSize.W780),
                                    artWidth = 320.dp,
                                    artHeight = 180.dp,
                                    seed = e.showTitle,
                                    // Select opens the episode on its show, focused on Play; holding it plays at once.
                                    onClick = { onOpenEpisode(e.showId, e.season, e.episodeId) },
                                    onLongClick = { e.playRequest()?.let(onPlay) ?: onOpenEpisode(e.showId, e.season, e.episodeId) },
                                    modifier = Modifier.focusMemory(memory, "aired:${e.episodeId}"),
                                ) {
                                    if (e.isNew) ArtBadge("New", Modifier.align(Alignment.TopStart).padding(10.dp), BadgeStyle.Gold)
                                    if (!e.hasFile) ArtBadge("Not in library", Modifier.align(Alignment.TopEnd).padding(10.dp), BadgeStyle.Warn)
                                    ArtBadge(e.codeBadge, Modifier.align(Alignment.BottomStart).padding(10.dp))
                                    ArtWatched(e.watched)
                                }
                            }
                        }
                    }
                }
            }

            if (home.recentlyAddedMovies.isNotEmpty()) {
                item("movies", contentType = "row") {
                    Column {
                        SectionHeader("Recently added", small = "Movies")
                        CardRow {
                            items(home.recentlyAddedMovies, key = { it.id }) { m ->
                                PosterCard(
                                    m,
                                    library,
                                    onClick = { m.open(onOpenShow, onOpenMovie, onPlay, notMatched) },
                                    onLongClick = { onToggleWatched(m) },
                                    modifier = Modifier.focusMemory(memory, "movies:${m.id}"),
                                )
                            }
                        }
                    }
                }
            }

            if (home.recentlyReleased.isNotEmpty()) {
                item("released", contentType = "row") {
                    Column {
                        SectionHeader("Recently released", small = "Movies")
                        CardRow {
                            items(home.recentlyReleased, key = { it.id }) { m ->
                                MediaCard(
                                    title = m.title,
                                    subtitle = null,
                                    imageUrl = library.imageUrl(m.backdropPath, ImageSize.W780),
                                    artWidth = 320.dp,
                                    artHeight = 180.dp,
                                    onClick = { onOpenMovie(m.id) },
                                    onLongClick = { onToggleWatched(m) },
                                    modifier = Modifier.focusMemory(memory, "released:${m.id}"),
                                ) {
                                    formatDate(m.releaseDate)?.let { ArtBadge(it, Modifier.align(Alignment.BottomStart).padding(10.dp)) }
                                    ArtWatched(m.watched)
                                }
                            }
                        }
                    }
                }
            }

            item("totals", contentType = "totals") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = ContentStart, end = 58.dp, top = 8.dp)
                        .height(40.dp)
                        .drawBehind { drawLine(WmmColors.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(home.totalsLine(), style = inter(17.sp), color = WmmColors.TextFaint)
                }
            }
        }
    }
}

/** Live TV's row: On now with listings, else Watch a channel. Home's row screens pass [counter] and [onFocusItem], as [TvArrangedRow] takes them. */
@Composable
private fun LiveRow(
    liveChannels: List<LiveChannel>,
    liveListings: Boolean,
    memory: FocusMemory,
    onWatchLive: (LiveChannel) -> Unit,
    onLiveGuide: () -> Unit,
    modifier: Modifier = Modifier,
    counter: String? = null,
    onFocusItem: OnFocusItem? = null,
) {
    val available = liveChannels.filter { it.available }
    if (liveListings) {
        val now = rememberLiveNow()
        val format = rememberLiveClockFormat()
        Column(modifier) {
            SectionHeader("On now", small = "Live TV · ${format.time(now)}", trailing = counter)
            CardRow {
                itemsIndexed(available, key = { _, it -> it.id }) { i, channel ->
                    TvOnNowCard(channel, now, { onWatchLive(channel) }, Modifier.width(320.dp).rowCard(memory, "live:${channel.id}", i, onFocusItem))
                }
                item("guide") {
                    FocusableBox(onClick = onLiveGuide, modifier = Modifier.width(320.dp).height(180.dp).rowCard(memory, "live:guide", available.size, onFocusItem), cornerRadius = 12.dp) { focused ->
                        Box(Modifier.fillMaxSize().background(if (focused) WmmColors.Surface3 else WmmColors.Surface2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Text("Full guide", style = inter(22.sp, FontWeight.SemiBold), color = WmmColors.Text)
                        }
                    }
                }
            }
        }
    } else {
        Column(modifier) {
            SectionHeader("Live TV", small = "Watch a channel", trailing = counter)
            CardRow {
                itemsIndexed(available, key = { _, it -> it.id }) { i, channel ->
                    LiveChannelCard(channel, { onWatchLive(channel) }, Modifier.width(258.dp).rowCard(memory, "live:${channel.id}", i, onFocusItem))
                }
            }
        }
    }
}

/**
 * Home in the person's order (PLAN-ROWS.md §8.2) as screens (PLAN-HOME.md
 * §7.3, HomeStart.dc.html and HomeRow.dc.html): page 0 is the hero and the
 * first row drawn, every later row fills a page with the focused card's
 * picture, kicker, title, facts and overview above it, and the last page is
 * Edit rows and the totals. ▼ at a row pages down and ▲ pages up (the D-pad
 * pages, never a fling); each row comes back to the card it had focus on;
 * Back from a row page goes to page 0 first. Live TV sits in its place.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArrangedHomeContent(
    home: HomeData,
    library: LibraryRepository,
    memory: FocusMemory,
    actions: TvRowActions,
    liveChannels: List<LiveChannel>,
    onWatchLive: (LiveChannel) -> Unit,
    liveListings: Boolean,
    onLiveGuide: () -> Unit,
    onEditRows: () -> Unit,
) {
    val live = liveChannels.any { it.available }
    val rows = home.rows.orEmpty().drawn(live)
    val pages = remember(home, rows) { homePages(home.hero != null, rows) }
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val available = liveChannels.filter { it.available }
    // The card each row last had focus on, by row id, so a page restores it (FocusMemory keeps only the last key overall).
    val lastFocus = remember { mutableStateMapOf<String, String>() }
    val heroKey = home.hero?.let { if (it.fileId != null) "hero:play" else "hero:open" }

    fun firstKey(row: ScreenRow): String? = if (row.style == RowStyle.Live) available.firstOrNull()?.let { "live:${it.id}" } else row.cardKey()
    fun focus(key: String?) {
        key ?: return
        runCatching { memory.requester(key).requestFocus() }
    }
    fun focusPage(page: HomePage) {
        when (page) {
            is HomePage.Start -> focus(page.row?.let { lastFocus[it.id] ?: firstKey(it) } ?: heroKey)
            is HomePage.Row -> focus(lastFocus[page.row.id] ?: firstKey(page.row))
            HomePage.End -> focus("end:edit")
        }
    }

    /** Pages to [target] and puts focus on its row's card (or the end page's Edit rows). One page move at a time. */
    fun pageTo(target: Int) {
        if (target !in pages.indices || pager.isScrollInProgress) return
        scope.launch {
            pager.animateScrollToPage(target)
            withFrameNanos { }
            focusPage(pages[target])
        }
    }

    /** Focus landed on a page the pager is not on (a focus search reached it): bring that page in. */
    fun settleOn(index: Int) {
        if (pager.settledPage != index && !pager.isScrollInProgress) scope.launch { pager.animateScrollToPage(index) }
    }

    BackHandler(enabled = pager.currentPage > 0) { pageTo(0) }

    val now = rememberLiveNow()
    val format = rememberLiveClockFormat()
    val liveRepository = LocalAppServices.current.live

    @Composable
    fun RowBlock(row: ScreenRow, pageIndex: Int, modifier: Modifier = Modifier, onFocusIndex: (Int) -> Unit = {}) {
        val counter = "Row ${rows.indexOf(row) + 1} of ${rows.size}"
        val onFocusItem: OnFocusItem = { i, key ->
            lastFocus[row.id] = key
            onFocusIndex(i)
            settleOn(pageIndex)
        }
        if (row.style == RowStyle.Live) LiveRow(liveChannels, liveListings, memory, onWatchLive, onLiveGuide, modifier, counter, onFocusItem)
        else TvArrangedRow(row, library, memory, actions, modifier, counter, onFocusItem)
    }

    fun nextName(index: Int): String = when (val next = pages.getOrNull(index + 1)) {
        is HomePage.Row -> next.row.headingLine(liveListings)
        else -> "End of Home"
    }

    // Focus never scrolls the pager by itself: ▲ ▼ page, and a card focused on another page settles it (settleOn).
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoScrollSpec) {
        VerticalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = false,
            key = { pages[it].key },
        ) { index ->
            when (val page = pages[index]) {
                is HomePage.Start -> Box(Modifier.fillMaxSize()) {
                    Column {
                        home.hero?.let { hero ->
                            val episode = hero.episodeId?.takeIf { hero.kind == Hero.Kind.Episode }?.let { id -> home.episodeCards.firstOrNull { it.episodeId == id } }
                            val openEpisode = episode?.let { e -> { actions.onOpenEpisode(e.showId, e.season, e.episodeId) } }
                            HeroSection(hero, home, library, memory, actions.onPlay, actions.onOpenShow, actions.onOpenMovie, openEpisode)
                        }
                        page.row?.let { row ->
                            Spacer(Modifier.height(80.dp))
                            // ▲ from the row reaches the hero's buttons by itself; ▼ goes to the first row screen.
                            RowBlock(row, index, Modifier.pageKeys(up = null, down = { pageTo(index + 1) }))
                        }
                    }
                    PeekLine(nextName(index), up = false, Modifier.align(Alignment.BottomStart))
                }
                is HomePage.Row -> {
                    val row = page.row
                    var focused by rememberSaveable(row.id) { mutableIntStateOf(0) }
                    val panel = remember(row, focused, available, now) { row.cardPanelAt(focused, available, now, format) }
                    Box(Modifier.fillMaxSize()) {
                        RowBackdrop(panel?.pictureUrl(library, liveRepository, ImageSize.W1280), panel?.seed ?: row.title)
                        if (panel != null) FocusPanel(panel, Modifier.padding(start = ContentStart, top = 96.dp).width(940.dp))
                        PrefetchNeighbours(row, focused, available, library, liveRepository, now, format)
                        Box(Modifier.padding(top = 556.dp)) {
                            RowBlock(row, index, Modifier.pageKeys(up = { pageTo(index - 1) }, down = { pageTo(index + 1) })) { focused = it }
                        }
                        PeekLine(nextName(index), up = false, Modifier.align(Alignment.BottomStart))
                    }
                }
                HomePage.End -> Box(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(start = ContentStart, top = 300.dp).width(1000.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        Text("That's everything on Home", style = inter(44.sp, FontWeight.Bold, tracking = -0.02f), color = WmmColors.Text)
                        Text("Move, hide and add rows. Your order follows you to every device.", style = inter(22.sp, lineHeight = 33.sp), color = WmmColors.TextSoft, modifier = Modifier.widthIn(max = 760.dp))
                        Row(Modifier.padding(top = 6.dp).pageKeys(up = { pageTo(index - 1) }, down = null), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            TvButton("Edit rows", onEditRows, Modifier.focusMemory(memory, "end:edit").onFocusChanged { if (it.isFocused) settleOn(index) }, icon = WmmIcons.Sliders)
                            TvButton("Back to the top", { pageTo(0) }, Modifier.focusMemory(memory, "end:top").onFocusChanged { if (it.isFocused) settleOn(index) })
                        }
                        Text(home.totalsLine(), style = inter(17.sp), color = WmmColors.TextFaint, modifier = Modifier.padding(top = 18.dp))
                    }
                    val before = pages.getOrNull(index - 1)?.row?.headingLine(liveListings) ?: "Home"
                    PeekLine(before, up = true, Modifier.align(Alignment.BottomStart))
                }
            }
        }
    }
}

/** A bring-into-view request never scrolls: Home's pager moves only on ▲ ▼ and [HomePage] focus settling. */
@OptIn(ExperimentalFoundationApi::class)
private object NoScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

private fun ContinueItem.open(onOpenShow: (Long) -> Unit, onOpenMovie: (Long) -> Unit) {
    when {
        showId != null -> onOpenShow(showId!!)
        movieId != null -> onOpenMovie(movieId!!)
    }
}

/** A 160×240 poster with the New badge, as in Recently added (no quality badge, PLAN-HOME.md §5). */
@Composable
private fun PosterCard(
    item: BrowseItem,
    library: LibraryRepository,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    MediaCard(
        title = item.title,
        subtitle = item.subtitle,
        imageUrl = library.imageUrl(item.posterPath, ImageSize.W342),
        artWidth = 160.dp,
        artHeight = 240.dp,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
    ) {
        if (item.isNew) ArtBadge("New", Modifier.align(Alignment.TopStart).padding(8.dp), BadgeStyle.Gold)
        ArtWatched(item.watched, size = 24.dp)
        ArtProgress(item.progress)
    }
}

/** "S03 E07 · The Long Dark · 52 min · [4K] [HDR] · TV-14". */
@Composable
private fun HeroFacts(hero: Hero, tags: List<String>) {
    val style = inter(23.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        var before = false
        if (hero.facts.isNotBlank()) {
            Text(hero.facts, style = style, color = TextBright, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            before = true
        }
        if (tags.isNotEmpty()) {
            if (before) Text("·", style = style, color = WmmColors.TextFaint)
            tags.forEach { TvTag(it) }
            before = true
        }
        hero.contentRating?.let {
            if (before) Text("·", style = style, color = WmmColors.TextFaint)
            Text(it, style = style, color = TextBright, maxLines = 1)
        }
    }
}

@Composable
private fun HeroSection(
    hero: Hero,
    home: HomeData,
    library: LibraryRepository,
    memory: FocusMemory,
    onPlay: (PlayRequest) -> Unit,
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenEpisode: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().height(476.dp).bringWholeIntoView()) {
        // The backdrop runs on behind the first row (620 tall from the top, starting 560 in).
        Backdrop(
            url = library.imageUrl(hero.backdropPath, ImageSize.W1280),
            seed = hero.title,
            modifier = Modifier
                .offset(x = 560.dp)
                .width(1360.dp)
                .overflowHeight(620.dp, reported = 476.dp),
            leftFade = listOf(0f to 1f, 0.22f to 0.85f, 0.6f to 0f),
            bottomFadeFrom = 0.55f,
            glowX = 0.7f,
            glowY = 0.3f,
        )
        Column(Modifier.padding(start = ContentStart, top = 112.dp).width(900.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Kicker(hero.kicker, fontSize = 19.sp)
            Text(
                hero.title,
                style = inter(72.sp, FontWeight.ExtraBold, lineHeight = 76.sp, tracking = -0.025f),
                color = WmmColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HeroFacts(hero, home.heroBadges)
            Text(
                hero.overview,
                style = inter(22.sp, lineHeight = 33.sp),
                color = WmmColors.TextSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 760.dp),
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                val play = hero.playRequest(home.episodeCards)
                if (play != null) {
                    TvButton(
                        "Play",
                        { onPlay(play) },
                        Modifier.focusMemory(memory, "hero:play"),
                        style = TvButtonStyle.Primary,
                        icon = WmmIcons.Play,
                    )
                }
                val open: (() -> Unit)? = when {
                    onOpenEpisode != null -> onOpenEpisode
                    hero.showId != null -> { { onOpenShow(hero.showId!!) } }
                    hero.movieId != null -> { { onOpenMovie(hero.movieId!!) } }
                    else -> null
                }
                if (open != null) {
                    TvButton(
                        when {
                            onOpenEpisode != null -> "Go to episode"
                            hero.kind == Hero.Kind.Movie -> "More info"
                            else -> "Go to show"
                        },
                        open,
                        Modifier.focusMemory(memory, "hero:open"),
                        style = if (play == null) TvButtonStyle.Primary else TvButtonStyle.Ghost,
                    )
                }
            }
        }
    }
}

