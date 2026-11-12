package media.watchmy.client.mobile.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.CardAction
import media.watchmy.client.mobile.components.EmptyState
import media.watchmy.client.mobile.components.ErrorState
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.HomeSkeleton
import media.watchmy.client.mobile.components.MainTopBar
import media.watchmy.client.mobile.components.MessageHost
import media.watchmy.client.mobile.components.PlayGlyph
import media.watchmy.client.mobile.components.PosterCard
import media.watchmy.client.mobile.components.RowHeading
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.mobile.components.WideCard
import media.watchmy.client.mobile.components.groundFade
import media.watchmy.client.mobile.downloads.OfflineHome
import media.watchmy.client.mobile.components.watchToggle
import media.watchmy.client.mobile.navigation.MobileRoutes
import media.watchmy.client.mobile.live.LiveHomeRow
import media.watchmy.client.mobile.rows.ArrangedRow
import media.watchmy.client.mobile.rows.EditRowsButton
import media.watchmy.client.mobile.rows.RowActions
import media.watchmy.client.mobile.rows.rowEditorRoute
import media.watchmy.client.model.RowLink
import media.watchmy.client.model.RowScreens
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.arranged
import media.watchmy.client.model.drawn
import media.watchmy.client.model.episodeCards
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.EpisodeCard
import media.watchmy.client.model.Hero
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.WatchTarget
import media.watchmy.client.model.airedLine
import media.watchmy.client.model.codeBadge
import media.watchmy.client.model.formatDate
import media.watchmy.client.model.HomeEmptyState
import androidx.compose.ui.platform.LocalUriHandler
import media.watchmy.client.model.heroBadges
import media.watchmy.client.model.playRequest
import media.watchmy.client.model.remainingLine
import media.watchmy.client.model.totalsLine
import media.watchmy.client.model.watchTarget
import media.watchmy.client.presentation.downloads.DownloadsViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.TabletLayout
import media.watchmy.client.ui.rememberTabletLayout
import media.watchmy.client.mobile.components.PressableCard
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.rememberNotMatchedNotice
import media.watchmy.client.ui.open
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.Badge
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.ProgressStrip
import media.watchmy.client.ui.components.WmmIcons

/**
 * Home (Home.dc.html, HomeScrolled.dc.html): the hero, then Continue
 * watching, Recently added shows, Recently aired, Recently added movies,
 * Recently released and the library totals, with pull to refresh.
 * Long-press a card to mark it watched or unwatched. A Recently aired
 * episode, and the hero when it is one, opens the episode's page
 * (PLAN-EPISODE-PAGE.md); Continue watching resumes straight away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onPlay: (PlayRequest) -> Unit,
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onNavigate: (Any) -> Unit,
    onOpenSeason: (showId: Long, season: Int) -> Unit = { id, _ -> onOpenShow(id) },
    onOpenEpisode: (showId: Long, season: Int, episodeId: Long) -> Unit = { id, season, _ -> onOpenSeason(id, season) },
    liveChannels: List<LiveChannel> = emptyList(),
    onWatchLive: (LiveChannel) -> Unit = {},
    liveListings: Boolean = false,
) {
    val layout = rememberTabletLayout()
    val services = LocalAppServices.current
    val vm = viewModel { HomeViewModel(services.library, services.auth, services.rows) }
    val notMatched = rememberNotMatchedNotice()
    val state by vm.state.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val server by services.servers.current.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val downloads = viewModel { DownloadsViewModel(services.downloads) }
    val held by downloads.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(WmmColors.Ground)) {
        // Offline there is nothing to search (HomeOffline.dc.html).
        val offline = state.home == null && state.offline && held.enabled
        MainTopBar("Home", user?.name, onSearch = { onNavigate(MobileRoutes.Search) }.takeIf { !offline }, onAvatar = { onNavigate(MobileRoutes.Settings) })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val home = state.home
            when {
                // No server, on a device that has downloads (or may make them): they still play.
                home == null && state.offline && held.enabled -> OfflineHome(
                    downloads = held,
                    tryingAgain = state.tryingAgain,
                    onTryAgain = {
                        vm.tryAgain()
                        downloads.sync()
                    },
                    onPlay = onPlay,
                    onOpenMovie = onOpenMovie,
                    onSeeAll = { onNavigate(MobileRoutes.Downloads) },
                    tablet = layout.isTablet,
                )
                home == null && state.error != null -> ErrorState(state.error!!, vm::retry)
                home == null -> HomeSkeleton()
                else -> {
                    val pull = rememberPullToRefreshState()
                    PullToRefreshBox(
                        isRefreshing = state.refreshing,
                        onRefresh = vm::refresh,
                        state = pull,
                        modifier = Modifier.fillMaxSize(),
                        indicator = {
                            PullToRefreshDefaults.Indicator(
                                state = pull,
                                isRefreshing = state.refreshing,
                                modifier = Modifier.align(Alignment.TopCenter),
                                containerColor = WmmColors.Surface3,
                                color = WmmColors.Accent,
                            )
                        },
                    ) {
                        val liveRow: LazyListScope.() -> Unit = {
                            if (liveChannels.isNotEmpty()) {
                                item(key = "live-tv", contentType = "live-tv") {
                                    LiveHomeRow(liveChannels, onWatchLive, onSeeAll = { onNavigate(MobileRoutes.LiveTV) }, listings = liveListings)
                                }
                            }
                        }
                        // LiveHome puts On now after Continue watching; the channel row, or On now on an empty library, stays first.
                        // Arranged rows put it where the person placed it (PLAN-ROWS.md Decision 4).
                        val arranged = home.arranged
                        val liveFirst = !arranged && (!liveListings || state.isEmpty)
                        // A tablet shows arranged Home one row a screen (PLAN-HOME.md §7.5); phones keep the scrolling page.
                        if (arranged && layout.isTablet && !state.isEmpty) {
                            TabletPagedHome(
                                home = home,
                                library = services.library,
                                actions = RowActions(onPlay, onOpenShow, onOpenMovie, onOpenEpisode, notMatched, vm::setWatched),
                                twoPane = layout.twoPane,
                                onNavigate = onNavigate,
                                liveChannels = liveChannels,
                                onWatchLive = onWatchLive,
                                liveListings = liveListings,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (liveFirst || (arranged && state.isEmpty)) liveRow()
                            if (state.isEmpty && liveChannels.isEmpty()) {
                                item {
                                    val empty = state.emptyState ?: HomeEmptyState(HomeEmptyState.Kind.Unavailable)
                                    val setup = user?.isAdmin == true && empty.offersSetup && server != null
                                    EmptyState(
                                        empty.title,
                                        empty.message(user?.isAdmin == true),
                                        Modifier.fillParentMaxSize(),
                                        action = if (setup) "Open the web page" else "Check again",
                                        onAction = {
                                            if (setup) runCatching { uri.openUri(server!!.server.baseUrl) }.onFailure { vm.say("Couldn't open a browser. Open ${server!!.server.baseUrl} on another device.") }
                                            else vm.retry()
                                        },
                                    )
                                }
                            } else if (arranged) {
                                val actions = RowActions(onPlay, onOpenShow, onOpenMovie, onOpenEpisode, notMatched, vm::setWatched)
                                arrangedRows(home, services.library, vm, actions, layout.isTablet, layout.twoPane, onNavigate, live = liveRow.takeIf { liveChannels.isNotEmpty() && !state.isEmpty })
                            } else {
                                homeRows(home, services.library, vm, onPlay, onOpenShow, onOpenMovie, onOpenEpisode, notMatched, layout.isTablet, layout.twoPane, onNavigate, onNow = liveRow.takeIf { !liveFirst })
                            }
                        }
                    }
                }
            }
            MessageHost(state.message, vm::messageShown, Modifier.align(Alignment.BottomCenter))
        }
    }
}

private fun LazyListScope.homeRows(
    home: HomeData,
    library: LibraryRepository,
    vm: HomeViewModel,
    onPlay: (PlayRequest) -> Unit,
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenEpisode: (Long, Int, Long) -> Unit,
    onNotMatched: () -> Unit,
    tablet: Boolean,
    twoPane: Boolean,
    onNavigate: (Any) -> Unit,
    onNow: (LazyListScope.() -> Unit)? = null,
) {
    val itemActions = { item: BrowseItem -> listOfNotNull(item.watchTarget()?.let { t -> watchToggle(item.watched) { vm.setWatched(t, it) } }) }

    val sideContinue = twoPane && home.hero != null && home.continueWatching.isNotEmpty()
    home.hero?.let { hero ->
        item(key = "hero", contentType = "hero") {
            val goTo = {
                when {
                    hero.movieId != null -> onOpenMovie(hero.movieId!!)
                    hero.showId != null -> onOpenShow(hero.showId!!)
                }
            }
            // An episode's season comes from its Recently aired card; without one the hero opens the show.
            val episode = hero.episodeId?.takeIf { hero.kind == Hero.Kind.Episode }?.let { id -> home.recentlyAired.firstOrNull { it.episodeId == id } }
            Row(Modifier.widthIn(max = if (tablet && !twoPane) TabletLayout.singleColumnMax else Dp.Infinity).fillMaxWidth().padding(horizontal = if (tablet) 24.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HeroCard(
                    modifier = Modifier.weight(1f),
                    height = if (twoPane) 340.dp else 400.dp,
                hero = hero,
                badges = home.heroBadges,
                imageUrl = library.imageUrl(hero.backdropPath, ImageSize.W780),
                onPlay = hero.playRequest(home.recentlyAired)?.let { r -> { onPlay(r) } },
                onOpen = episode?.let { e -> { onOpenEpisode(e.showId, e.season, e.episodeId) } } ?: goTo,
                openLabel = when {
                    episode != null -> "Go to episode"
                    hero.kind == Hero.Kind.Movie -> "Go to movie"
                    else -> "Go to show"
                },
            )
                if (sideContinue) ContinuePanel(home.continueWatching, library, vm::setWatched, onPlay, onOpenShow, onOpenMovie, Modifier.width(340.dp).height(340.dp))
            }
        }
    }
    if (home.continueWatching.isNotEmpty() && !sideContinue) {
        section("continue", "Continue watching", null, tablet = tablet) {
            items(home.continueWatching, key = { "c${it.fileId}" }) { item ->
                WideCard(
                    title = item.title,
                    subtitle = item.remainingLine,
                    imageUrl = library.imageUrl(item.stillPath ?: item.backdropPath, ImageSize.W780),
                    seed = item.title,
                    width = 232.dp,
                    onClick = { onPlay(item.playRequest()) },
                    actions = listOfNotNull(
                        item.watchTarget()?.let { t -> CardAction("Mark as watched") { vm.setWatched(t, true) } },
                        item.movieId?.let { id -> CardAction("Go to movie") { onOpenMovie(id) } },
                        item.showId?.let { id -> CardAction("Go to show") { onOpenShow(id) } },
                    ),
                ) {
                    PlayGlyph()
                    ProgressStrip(item.progress, Modifier.align(Alignment.BottomStart))
                }
            }
        }
    }
    onNow?.invoke(this)
    if (tablet && home.recentlyAired.isNotEmpty()) {
        section("aired", "Recently aired", "Episodes", tablet = tablet, onSeeAll = { onNavigate(MobileRoutes.TvShows) }) {
            items(home.recentlyAired, key = { "e${it.episodeId}" }) { ep -> AiredCard(ep, library, vm, onPlay, onOpenEpisode) }
        }
    }
    if (home.recentlyAddedShows.isNotEmpty()) {
        section("shows", "Recently added", "TV Shows", spacing = 8, tablet = tablet, onSeeAll = { onNavigate(MobileRoutes.TvShows) }) {
            items(home.recentlyAddedShows, key = { "s${it.id}" }) { item ->
                PosterCard(item, library.imageUrl(item.posterPath, ImageSize.W342), item.subtitle, { item.open(onOpenShow, onOpenMovie, onPlay, onNotMatched, onOpenEpisode) }, { w: Boolean -> vm.setWatched(item, w) }.takeIf { item.watchTarget() != null }, width = if (tablet) 150.dp else 116.dp)
            }
        }
    }
    if (!tablet && home.recentlyAired.isNotEmpty()) {
        section("aired", "Recently aired", "Episodes", tablet = tablet, onSeeAll = { onNavigate(MobileRoutes.TvShows) }) {
            items(home.recentlyAired, key = { "e${it.episodeId}" }) { ep -> AiredCard(ep, library, vm, onPlay, onOpenEpisode) }
        }
    }
    if (home.recentlyAddedMovies.isNotEmpty()) {
        section("movies", "Recently added", "Movies", spacing = 8, tablet = tablet, onSeeAll = { onNavigate(MobileRoutes.Movies) }) {
            items(home.recentlyAddedMovies, key = { "m${it.id}" }) { item ->
                PosterCard(item, library.imageUrl(item.posterPath, ImageSize.W342), item.subtitle, { item.open(onOpenShow, onOpenMovie, onPlay, onNotMatched) }, { w: Boolean -> vm.setWatched(item, w) }.takeIf { item.watchTarget() != null }, width = if (tablet) 150.dp else 116.dp)
            }
        }
    }
    if (home.recentlyReleased.isNotEmpty()) {
        section("released", "Recently released", "Movies", spacing = 10, tablet = tablet, onSeeAll = { onNavigate(MobileRoutes.Movies) }) {
            items(home.recentlyReleased, key = { "r${it.id}" }) { item ->
                WideCard(
                    title = item.title,
                    subtitle = null,
                    imageUrl = library.imageUrl(item.backdropPath, ImageSize.W780),
                    seed = item.title,
                    width = if (tablet) 240.dp else 160.dp,
                    onClick = { onOpenMovie(item.id) },
                    actions = itemActions(item),
                ) {
                    formatDate(item.releaseDate)?.let { Badge(it, Modifier.align(Alignment.BottomStart).padding(6.dp)) }
                    if (item.watched) Badge("Watched", Modifier.align(Alignment.TopEnd).padding(6.dp), style = BadgeStyle.Gold)
                }
            }
        }
    }
    item(key = "totals", contentType = "totals") {
        Text(
            home.totalsLine(),
            style = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = WmmColors.TextFaint),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/**
 * Home in the person's order (PLAN-ROWS.md §8.2): the hero, then each row by
 * its style, Live TV in its place ([live], when there are channels), Edit
 * Home and the totals. The tablet keeps Continue beside the hero only while
 * it is the first row drawn.
 */
private fun LazyListScope.arrangedRows(
    home: HomeData,
    library: LibraryRepository,
    vm: HomeViewModel,
    actions: RowActions,
    tablet: Boolean,
    twoPane: Boolean,
    onNavigate: (Any) -> Unit,
    live: (LazyListScope.() -> Unit)?,
) {
    val rows = home.rows.orEmpty().drawn(live = live != null)
    val sideContinue = twoPane && home.hero != null && rows.firstOrNull()?.style == RowStyle.Continue
    home.hero?.let { hero ->
        item(key = "hero", contentType = "hero") {
            val goTo = {
                when {
                    hero.movieId != null -> actions.onOpenMovie(hero.movieId!!)
                    hero.showId != null -> actions.onOpenShow(hero.showId!!)
                }
            }
            val episode = hero.episodeId?.takeIf { hero.kind == Hero.Kind.Episode }?.let { id -> home.episodeCards.firstOrNull { it.episodeId == id } }
            Row(Modifier.widthIn(max = if (tablet && !twoPane) TabletLayout.singleColumnMax else Dp.Infinity).fillMaxWidth().padding(horizontal = if (tablet) 24.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HeroCard(
                    modifier = Modifier.weight(1f),
                    height = if (twoPane) 340.dp else 400.dp,
                    hero = hero,
                    badges = home.heroBadges,
                    imageUrl = library.imageUrl(hero.backdropPath, ImageSize.W780),
                    onPlay = hero.playRequest(home.episodeCards)?.let { r -> { actions.onPlay(r) } },
                    onOpen = episode?.let { e -> { actions.onOpenEpisode(e.showId, e.season, e.episodeId) } } ?: goTo,
                    openLabel = when {
                        episode != null -> "Go to episode"
                        hero.kind == Hero.Kind.Movie -> "Go to movie"
                        else -> "Go to show"
                    },
                )
                if (sideContinue) ContinuePanel(rows.first().continueItems, library, actions.onSetWatched, actions.onPlay, actions.onOpenShow, actions.onOpenMovie, Modifier.width(340.dp).height(340.dp))
            }
        }
    }
    rows.drop(if (sideContinue) 1 else 0).forEach { row ->
        if (row.style == RowStyle.Live) {
            live?.invoke(this)
        } else {
            item(key = "row-${row.id}", contentType = "row-${row.style}") {
                ArrangedRow(row, library, tablet, actions, onSeeAll = row.seeAll?.let { link -> seeAll(link, home, tablet, onNavigate) })
            }
        }
    }
    item(key = "edit", contentType = "edit") {
        EditRowsButton("Edit Home", { onNavigate(rowEditorRoute(RowScreens.HOME, "Home", tablet)) }, tablet)
    }
    item(key = "totals", contentType = "totals") {
        Text(
            home.totalsLine(),
            style = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = WmmColors.TextFaint),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/**
 * Where a Home row's See all goes: a list's page, or the library's tab. The
 * phone's built-in rows keep the heading without one, as before.
 */
private fun seeAll(link: RowLink, home: HomeData, tablet: Boolean, onNavigate: (Any) -> Unit): (() -> Unit)? = when (link) {
    is RowLink.ToList -> ({ onNavigate(MobileRoutes.MediaList(link.listId)) })
    is RowLink.Library -> if (!tablet) null else ({ onNavigate(if (link.libraryId == home.tvLibraryId) MobileRoutes.TvShows else MobileRoutes.Movies) })
}

/** A titled row of cards. */
private fun LazyListScope.section(
    key: String,
    title: String,
    small: String?,
    spacing: Int = 12,
    tablet: Boolean = false,
    onSeeAll: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    // Every row is a heading over a LazyRow, so one row's composition can be reused for another.
    item(key = key, contentType = "row") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tablet) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = WmmColors.Text, modifier = Modifier.weight(1f))
                    if (small != null) Text(small, fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted)
                    if (onSeeAll != null) TextButton(onClick = onSeeAll) { Text("See all", color = WmmColors.Accent) }
                }
            } else RowHeading(title, small = small)
            LazyRow(contentPadding = PaddingValues(horizontal = if (tablet) 24.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(if (tablet) 16.dp else spacing.dp), content = content)
        }
    }
}

/**
 * The compact panel keeps [count] resumable titles beside the hero (three
 * on the scrolling Home, six on the paged one, TabHomeStart.dc.html);
 * See all opens the complete list. [counter] is the paged Home's "Row 1 of 7".
 */
@Composable
internal fun ContinuePanel(
    items: List<ContinueItem>,
    library: LibraryRepository,
    onSetWatched: (WatchTarget, Boolean) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    modifier: Modifier = Modifier,
    count: Int = 3,
    counter: String? = null,
) {
    var all by rememberSaveable { mutableStateOf(false) }
    Column(modifier.background(WmmColors.Surface, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Continue watching", color = WmmColors.Text, fontFamily = Inter, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (counter != null) Text(counter, color = WmmColors.TextMuted, fontFamily = Inter, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { all = true }, contentPadding = PaddingValues(4.dp)) { Text("See all", color = WmmColors.Accent) }
        }
        items.take(count).forEach { ContinueRow(it, library, onSetWatched, onPlay, onOpenShow, onOpenMovie) }
    }
    if (all) Dialog(onDismissRequest = { all = false }) {
        Surface(shape = RoundedCornerShape(24.dp), color = WmmColors.Surface2) {
            LazyColumn(Modifier.widthIn(max = TabletLayout.singleColumnMax), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Continue watching", color = WmmColors.Text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { all = false }) { Text("Close", color = WmmColors.Accent) }
                    }
                }
                items(items, key = { it.fileId }) { ContinueRow(it, library, onSetWatched, onPlay, onOpenShow, onOpenMovie) }
            }
        }
    }
}

@Composable
private fun ContinueRow(item: ContinueItem, library: LibraryRepository, onSetWatched: (WatchTarget, Boolean) -> Unit, onPlay: (PlayRequest) -> Unit, onOpenShow: (Long) -> Unit, onOpenMovie: (Long) -> Unit) {
    PressableCard({ onPlay(item.playRequest()) }, listOfNotNull(
        item.watchTarget()?.let { t -> CardAction("Mark as watched") { onSetWatched(t, true) } },
        item.movieId?.let { id -> CardAction("Go to movie") { onOpenMovie(id) } },
        item.showId?.let { id -> CardAction("Go to show") { onOpenShow(id) } },
    )) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Artwork(library.imageUrl(item.stillPath ?: item.backdropPath, ImageSize.W300), seed = item.title, contentDescription = null, modifier = Modifier.size(112.dp, 63.dp), shape = RoundedCornerShape(8.dp)) {
                Box(Modifier.fillMaxSize()) { PlayGlyph(); ProgressStrip(item.progress, Modifier.align(Alignment.BottomStart)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, color = WmmColors.Text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.remainingLine, color = WmmColors.TextMuted, fontFamily = Inter, fontSize = 12.sp, maxLines = 2)
            }
        }
    }
}

/** The hero card; [height] null fills the height the parent gives it (the paged tablet Home). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HeroCard(hero: Hero, badges: List<String>, imageUrl: String?, onPlay: (() -> Unit)?, onOpen: () -> Unit, openLabel: String, modifier: Modifier = Modifier, height: Dp? = 400.dp) {
    Box(
        modifier.fillMaxWidth().then(if (height != null) Modifier.height(height) else Modifier).clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen),
    ) {
        Artwork(imageUrl, seed = hero.title, contentDescription = null, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.radialGradient(0f to WmmColors.Accent.copy(alpha = 0.12f), 0.6f to Color.Transparent, center = Offset(size.width * 0.7f, size.height * 0.2f), radius = size.width))
                drawRect(groundFade(0.3f, 0.75f, alpha = 0.92f))
            },
        )
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                hero.kicker.uppercase(),
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.32.sp, color = WmmColors.Accent),
            )
            Text(
                hero.title,
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 31.5.sp, letterSpacing = (-0.6).sp, color = WmmColors.Text),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                val facts = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = Color(0xFFE5E5E5))
                val parts = hero.facts.split(" · ").filter { it.isNotBlank() }
                parts.forEachIndexed { i, part ->
                    if (i > 0) Text("·", style = facts.copy(color = WmmColors.TextFaint))
                    Text(part, style = facts)
                }
                badges.forEachIndexed { i, badge ->
                    if (i == 0 && parts.isNotEmpty()) Text("·", style = facts.copy(color = WmmColors.TextFaint))
                    Badge(badge, style = BadgeStyle.Outline, horizontalPadding = 5.dp, verticalPadding = 2.dp)
                }
                // Boxed like the quality badges, so a narrow phone wraps it whole.
                hero.contentRating?.let { rating ->
                    if (badges.isEmpty() && parts.isNotEmpty()) Text("·", style = facts.copy(color = WmmColors.TextFaint))
                    Badge(rating, style = BadgeStyle.Outline, horizontalPadding = 5.dp, verticalPadding = 2.dp)
                }
            }
            if (hero.overview.isNotBlank()) {
                Text(
                    hero.overview,
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.85.sp, color = WmmColors.TextSoft),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onPlay != null) FilledButton("Play", onPlay, icon = WmmIcons.Play)
                TonalButton(openLabel, onOpen)
            }
        }
    }
}

@Composable
private fun AiredCard(ep: EpisodeCard, library: LibraryRepository, vm: HomeViewModel, onPlay: (PlayRequest) -> Unit, onOpenEpisode: (Long, Int, Long) -> Unit) {
    // A tap opens the episode's page; the long-press menu still plays at once.
    val request = ep.playRequest()
    WideCard(
        title = ep.showTitle,
        subtitle = ep.airedLine(),
        imageUrl = library.imageUrl(ep.stillPath ?: ep.showBackdropPath, ImageSize.W780),
        seed = ep.showTitle,
        width = 232.dp,
        onClick = { onOpenEpisode(ep.showId, ep.season, ep.episodeId) },
        actions = listOfNotNull(
            request?.let { r -> CardAction("Play") { onPlay(r) } },
            watchToggle(ep.watched) { vm.setWatched(WatchTarget.Episode(ep.episodeId), it) },
        ),
    ) {
        if (ep.isNew) Badge("New", Modifier.align(Alignment.TopStart).padding(8.dp), style = BadgeStyle.Gold)
        if (!ep.hasFile) {
            Badge("Not in library", Modifier.align(Alignment.TopEnd).padding(8.dp), style = BadgeStyle.Warn)
        } else if (ep.watched) {
            Badge("Watched", Modifier.align(Alignment.TopEnd).padding(8.dp), style = BadgeStyle.Gold)
        }
        Badge(ep.codeBadge, Modifier.align(Alignment.BottomStart).padding(8.dp))
    }
}
