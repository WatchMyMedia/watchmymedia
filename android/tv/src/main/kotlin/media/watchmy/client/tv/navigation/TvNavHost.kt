package media.watchmy.client.tv.navigation

import media.watchmy.client.model.at
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import media.watchmy.client.model.DeepLink
import media.watchmy.client.presentation.servers.ServerSwitchViewModel
import media.watchmy.client.tv.servers.LocalServersHost
import media.watchmy.client.presentation.watchlists.WatchlistViewModel
import media.watchmy.client.tv.components.Notice
import media.watchmy.client.tv.watchlists.CardMenuDialog
import media.watchmy.client.tv.watchlists.CardMenuHost
import media.watchmy.client.tv.watchlists.LocalCardMenu
import media.watchmy.client.tv.watchlists.WatchlistPanelTv
import media.watchmy.client.tv.watchlists.WatchlistsTv
import media.watchmy.client.ui.LocalWatchlistControls
import media.watchmy.client.ui.rememberWatchlistControls
import media.watchmy.client.tv.servers.ServersHost
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.model.LivePersonalChange
import media.watchmy.client.model.attempt
import media.watchmy.client.model.comeKey
import media.watchmy.client.presentation.live.LiveGuideViewModel
import media.watchmy.client.tv.live.BannerKeys
import media.watchmy.client.tv.live.LiveRemindersScreenTv
import media.watchmy.client.tv.live.TvReminderBanner
import media.watchmy.client.ui.components.rememberLiveClockFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.presentation.live.LiveChannelsViewModel
import media.watchmy.client.tv.components.LocalLiveTVAvailable
import media.watchmy.client.tv.live.LiveChannelsScreen
import media.watchmy.client.tv.live.LivePlayerScreen
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.Trailer
import media.watchmy.client.model.isYouTubeKey
import media.watchmy.client.tv.components.LocalRequestBadge
import media.watchmy.client.tv.detail.MovieScreen
import media.watchmy.client.tv.detail.ShowPageScreen
import media.watchmy.client.tv.detail.TvTrailerScreen
import media.watchmy.client.tv.home.HomeScreen
import media.watchmy.client.tv.library.LibraryScreen
import media.watchmy.client.tv.player.PlayerScreen
import media.watchmy.client.tv.requests.RequestsScreen
import media.watchmy.client.tv.search.SearchScreen
import media.watchmy.client.tv.settings.SettingsScreen
import media.watchmy.client.tv.rows.ListPageTv
import media.watchmy.client.ui.onBack
import media.watchmy.client.ui.rememberRequestBadge
import media.watchmy.client.presentation.live.ReminderBannerViewModel
import media.watchmy.client.ui.LocalLiveChannelsShown

/** Opens the player for [request]. */
fun NavHostController.play(request: PlayRequest) =
    navigate(TvRoutes.Player(request.fileId, request.startMs, request.title, request.subtitle, request.backdropPath, request.recordingId, request.serverId))

/** Another server's title, opened in place without switching: Back returns to where the person was. */
fun NavHostController.openInPlace(serverId: String, page: DeepLink) {
    when (page) {
        is DeepLink.Movie -> navigate(TvRoutes.Movie(page.id, serverId))
        is DeepLink.Show -> navigate(TvRoutes.Show(page.id, page.season, page.episode, serverId))
        is DeepLink.Play -> navigate(TvRoutes.Player(fileId = page.fileId, startMs = page.startMs, title = page.title.orEmpty(), subtitle = page.subtitle, serverId = serverId))
        else -> Unit
    }
}

/**
 * Goes to a rail section: Home stays at the bottom of the stack, and going
 * to the section already open does nothing, so Back from any section goes
 * Home and Back from Home leaves the app.
 */
fun NavHostController.navigateSection(route: Any) = navigate(route) {
    popUpTo<TvRoutes.Home>()
    launchSingleTop = true
}

/** A rail section as [navigateSection] goes there; a list's page or Home rows on top of the screen that opened it, so Back returns to it. */
private fun NavHostController.navigateFrom(route: Any) =
    if (route is TvRoutes.MediaList || route is TvRoutes.RowSettings) navigate(route) else navigateSection(route)

/** The one page for a show, a season or an episode: what goes on top first is [arrival]'s; [serverId] the server it is on. */
@Composable
private fun ShowPage(nav: NavHostController, showId: Long, arrival: ShowArrival, serverId: String? = null) {
    ShowPageScreen(
        showId = showId,
        arrival = arrival,
        serverId = serverId,
        onBack = { nav.popBackStack() },
        onPlay = nav::play,
        onNavigate = { r -> nav.navigateSection(r) },
        onOpenShow = { id -> nav.navigate(TvRoutes.Show(id, serverId = serverId)) },
        onOpenMovie = { id -> nav.navigate(TvRoutes.Movie(id, serverId)) },
        onOpenPerson = { p, from -> p.personId?.let { nav.navigate(TvRoutes.Person(it, p.name, from, serverId)) } },
    )
}

/**
 * The signed-in part of the TV app. Sign-in and the server picker are shown
 * by [media.watchmy.client.tv.TvApp] before this. [deepLink] (a
 * watchmymedia:// link) opens on top of Home; [onDeepLinkHandled] is called
 * once it has.
 *
 * [onAddServer] starts Add a server, from Servers and from Settings. A
 * search result on another server is the app's to open ([onOpenElsewhere]):
 * it switches, and the new server's screens then get [landing], Search with
 * the same words under the title's page, once ([onLandingShown]).
 */
@Composable
fun TvNavHost(
    onAddServer: () -> Unit,
    onSignedOut: () -> Unit,
    nav: NavHostController = rememberNavController(),
    deepLink: String? = null,
    onDeepLinkHandled: () -> Unit = {},
    landing: ServerSwitchViewModel.Landing? = null,
    onLandingShown: () -> Unit = {},
    onOpenElsewhere: (serverId: String, query: String, page: DeepLink) -> Unit = { _, _, _ -> },
) {
    val handled by rememberUpdatedState(onDeepLinkHandled)
    val services = media.watchmy.client.ui.LocalAppServices.current
    // A card, result or link from another server opens its title in place, from that server, without switching.
    media.watchmy.client.ui.ProvideInPlace { serverId, page -> nav.openInPlace(serverId, page) }
    // What Servers, on every screen's rail, needs from the app above these screens.
    val addServer by rememberUpdatedState(onAddServer)
    val openElsewhere by rememberUpdatedState(onOpenElsewhere)
    // Saving titles to watchlists, from every page's Watchlist button and cards' Hold OK. A TV says nothing on a save.
    val watchlistModel = viewModel(key = "watchlists") { WatchlistViewModel(services.watchlists, messages = false) }
    val watchlist by watchlistModel.state.collectAsStateWithLifecycle()
    val held by watchlistModel.held.collectAsStateWithLifecycle()
    val watchlistControls = rememberWatchlistControls(watchlist.supported, watchlist.watchlists, held, watchlistModel::press)
    val cardMenu = remember { CardMenuHost() }
    val openWatchlists by rememberUpdatedState({ nav.navigate(TvRoutes.Watchlists) }.takeIf { watchlist.supported })
    val serversHost = remember(watchlist.supported) {
        ServersHost(
            onAdd = { addServer() },
            onOpenElsewhere = { id, query, page -> openElsewhere(id, query, page) },
            onWatchlists = if (watchlist.supported) ({ openWatchlists?.invoke() }) else null,
        )
    }
    // The words a result on another server was found with, for Search on the server the app switched to.
    var searchWords by rememberSaveable { mutableStateOf<String?>(null) }
    val landed by rememberUpdatedState(onLandingShown)
    LaunchedEffect(landing) {
        val to = landing ?: return@LaunchedEffect
        // Home, then Search with the words, then the title: Back from the title is Search, and Back from Search is Home.
        searchWords = to.query
        nav.navigateSection(TvRoutes.Search)
        when (val page = to.page) {
            is DeepLink.Show -> nav.navigate(TvRoutes.Show(page.id, page.season, page.episode))
            is DeepLink.Movie -> nav.navigate(TvRoutes.Movie(page.id))
            is DeepLink.Play -> nav.navigate(TvRoutes.Player(fileId = page.fileId, title = page.title.orEmpty()))
            else -> Unit
        }
        landed()
    }
    val liveModel = viewModel { LiveChannelsViewModel(services.live, services.servers, services.auth) }
    val live by liveModel.state.collectAsStateWithLifecycle()
    // The channel list is polled only while a screen showing channels is on screen.
    val showChannels = remember(liveModel) { liveModel::showing }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val watchLive = { channel: LiveChannel ->
        liveModel.select(channel)
        nav.navigate(TvRoutes.LivePlayer(channel.id, channel.sourceId, channel.number, channel.name))
    }
    // A search card's or reminder's programme to show in the guide, once.
    var guideTarget by remember { mutableStateOf<LiveGuideViewModel.Target?>(null) }
    val openInGuide = { target: LiveGuideViewModel.Target ->
        guideTarget = target
        nav.navigateSection(TvRoutes.LiveTV)
    }
    // Reminders on the TV: read while the app is open, and again on the server's event.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { attempt { services.live.refreshReminders() } }
            services.live.personalChanges.collect { if (it == LivePersonalChange.Reminders) attempt { services.live.refreshReminders() } }
        }
    }
    val format = rememberLiveClockFormat()
    val bannerModel = viewModel { ReminderBannerViewModel(services.live) }
    val due by bannerModel.banner.collectAsStateWithLifecycle()
    // Nothing of ours is drawn over YouTube's player: a reminder waits for the trailer to close.
    val entry by nav.currentBackStackEntryAsState()
    val banner = due.takeUnless { entry?.destination?.hasRoute<TvRoutes.Trailer>() == true }
    val dismiss = bannerModel::dismiss
    val bannerKeys = remember(banner?.comeKey()) {
        BannerKeys(
            onWatch = { bannerModel.watch()?.let { b -> live.channels.firstOrNull { it.id == b.channelId }?.takeIf { it.available }?.let(watchLive) } },
        )
    }
    LaunchedEffect(deepLink) {
        if (deepLink == null) return@LaunchedEffect
        // A link or Watch Next entry another account here published opens in place, from that server.
        val saved = services.servers.saved.first()
        DeepLink.elsewhere(deepLink, saved, services.servers.current.value?.server?.id)?.let { (serverId, page) ->
            val there = if (page is DeepLink.Details) services.library.at(serverId)?.let { attempt { it.filePage(page.fileId) }.getOrNull() } else page
            there?.let { nav.openInPlace(serverId, it) }
            handled()
            return@LaunchedEffect
        }
        val account = media.watchmy.client.model.watchNextAccount(services.servers.current.value?.server, services.auth.user.value)
        // A reminder's own link watches its channel once the list has it; anything else opens its page.
        val live = media.watchmy.client.model.DeepLink.open(deepLink, account) as? media.watchmy.client.model.DeepLink.Live
        if (live != null) {
            val channel = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                liveModel.state.first { s -> s.channels.any { it.id == live.channelId } || s.error != null || (!s.loading && s.channels.isNotEmpty()) }
            }?.channels?.firstOrNull { it.id == live.channelId }
            if (channel != null && channel.available) watchLive(channel) else nav.navigateSection(TvRoutes.LiveTV)
        } else deepLinkRoute(deepLink, account, services.library)?.let { nav.navigate(it) }
        handled()
    }
    val badge by rememberRequestBadge(services.requests)
    CompositionLocalProvider(
        LocalRequestBadge provides badge,
        LocalLiveTVAvailable provides live.available,
        LocalLiveChannelsShown provides showChannels,
        LocalServersHost provides serversHost,
        LocalWatchlistControls provides watchlistControls,
        LocalCardMenu provides cardMenu,
    ) {
        // The banner never takes focus: Hold OK watches and Back dismisses, before the screen sees them.
        Box(
            Modifier.fillMaxSize().onBack(enabled = banner != null, overlay = true) { dismiss() }.onPreviewKeyEvent { event ->
                banner != null && bannerKeys.handle(event.nativeKeyEvent.keyCode, event.type == KeyEventType.KeyDown, event.nativeKeyEvent.repeatCount)
            }.onKeyEvent { event ->
                // The remote's Guide opens Live TV from any screen that doesn't take it itself (the Live TV player does).
                val guide = event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_GUIDE && live.available
                if (guide && event.type == KeyEventType.KeyUp) nav.navigateSection(TvRoutes.LiveTV)
                guide
            },
        ) {
            NavHost(
                nav,
                startDestination = TvRoutes.Home,
                enterTransition = { fadeIn(tween(180)) },
                exitTransition = { fadeOut(tween(180)) },
                popEnterTransition = { fadeIn(tween(180)) },
                popExitTransition = { fadeOut(tween(180)) },
            ) {
                composable<TvRoutes.Home> {
                    HomeScreen(
                        onPlay = nav::play,
                        onOpenShow = { nav.navigate(TvRoutes.Show(it)) },
                        onOpenMovie = { nav.navigate(TvRoutes.Movie(it)) },
                        onNavigate = { nav.navigateFrom(it) },
                        onOpenEpisode = { show, season, episode -> nav.navigate(TvRoutes.Episode(show, season, episode)) },
                        liveChannels = live.channels.takeIf { live.available }.orEmpty(),
                        onWatchLive = watchLive,
                        liveListings = live.listings,
                    )
                }
                composable<TvRoutes.Library> {
                    val route = it.toRoute<TvRoutes.Library>()
                    LibraryScreen(
                        type = if (route.type == "movie") LibraryType.Movie else LibraryType.Tv,
                        onOpenShow = { id -> nav.navigate(TvRoutes.Show(id)) },
                        onOpenMovie = { id -> nav.navigate(TvRoutes.Movie(id)) },
                        onNavigate = { r -> nav.navigateFrom(r) },
                        onPlay = nav::play,
                    )
                }
                composable<TvRoutes.Watchlists> {
                    WatchlistsTv(onNavigate = { r -> nav.navigateSection(r) }, onOpenList = { id -> nav.navigate(TvRoutes.MediaList(id)) })
                }
                composable<TvRoutes.MediaList> {
                    val route = it.toRoute<TvRoutes.MediaList>()
                    ListPageTv(
                        listId = route.id,
                        type = route.type,
                        onOpenShow = { id -> nav.navigate(TvRoutes.Show(id)) },
                        onOpenMovie = { id -> nav.navigate(TvRoutes.Movie(id)) },
                        onPlay = nav::play,
                        onNavigate = { r -> if (r == TvRoutes.Home) nav.popBackStack() else nav.navigateSection(r) },
                    )
                }
                composable<TvRoutes.RowSettings> {
                    SettingsScreen(
                        onAddServer = onAddServer,
                        onSignedOut = onSignedOut,
                        onNavigate = { r -> nav.navigateSection(r) },
                        onOpenRequests = { inbox -> nav.navigate(TvRoutes.Requests(inbox)) },
                        rowsScreen = it.toRoute<TvRoutes.RowSettings>().screen,
                    )
                }
                composable<TvRoutes.Show> { entry ->
                    val route = entry.toRoute<TvRoutes.Show>()
                    val arrival = when {
                        route.episode != null -> ShowArrival.Episode(route.episode, route.season)
                        route.season != null -> ShowArrival.Season(route.season)
                        else -> ShowArrival.Show
                    }
                    ShowPage(nav, route.id, arrival, route.serverId)
                }
                composable<TvRoutes.Episode> {
                    val route = it.toRoute<TvRoutes.Episode>()
                    ShowPage(nav, route.showId, ShowArrival.Episode(route.episodeId, route.season), route.serverId)
                }
                composable<TvRoutes.Movie> {
                    val route = it.toRoute<TvRoutes.Movie>()
                    val here = route.serverId
                    MovieScreen(
                        movieId = route.id,
                        serverId = here,
                        onPlay = nav::play,
                        onBack = { nav.popBackStack() },
                        onNavigate = { r -> nav.navigateSection(r) },
                        onOpenMovie = { id -> nav.navigate(TvRoutes.Movie(id, here)) },
                        onOpenShow = { id -> nav.navigate(TvRoutes.Show(id, serverId = here)) },
                        onOpenTrailer = { t -> nav.navigate(TvRoutes.Trailer(t.movieId, t.movieTitle, t.trailers.map { it.key }, t.trailers.map { it.name }, t.trailers.map { it.teaser })) },
                        onOpenPerson = { p, from -> p.personId?.let { nav.navigate(TvRoutes.Person(it, p.name, from, here)) } },
                    )
                }
                composable<TvRoutes.Person> {
                    val r = it.toRoute<TvRoutes.Person>()
                    val there = remember(r.serverId) { services.library.at(r.serverId) } ?: services.library
                    media.watchmy.client.tv.detail.PersonScreen(
                        personId = r.personId,
                        name = r.name,
                        from = r.from,
                        onBack = { nav.popBackStack() },
                        onOpenMovie = { id -> nav.navigate(TvRoutes.Movie(id, r.serverId)) },
                        onOpenShow = { id -> nav.navigate(TvRoutes.Show(id, serverId = r.serverId)) },
                        onNavigate = { route -> nav.navigateSection(route) },
                        library = there,
                        key = r.serverId,
                    )
                }
                composable<TvRoutes.Trailer> {
                    val r = it.toRoute<TvRoutes.Trailer>()
                    val trailers = r.keys.zip(r.names) { key, name -> Trailer.YouTube(name, key) }
                        .mapIndexed { i, t -> t.copy(teaser = r.teasers.getOrNull(i) == true) }.filter { t -> isYouTubeKey(t.key) }
                    TvTrailerScreen(movieTitle = r.movieTitle, trailers = trailers, onBack = { nav.popBackStack() })
                }
                composable<TvRoutes.Search> {
                    SearchScreen(
                        onOpenShow = { id -> nav.navigate(TvRoutes.Show(id)) },
                        onOpenMovie = { id -> nav.navigate(TvRoutes.Movie(id)) },
                        onPlay = nav::play,
                        onNavigate = { r -> nav.navigateSection(r) },
                        onOpenEpisode = { show, season, episode -> nav.navigate(TvRoutes.Episode(show, season, episode)) },
                        onWatchLive = watchLive,
                        onOpenLive = { hit -> openInGuide(LiveGuideViewModel.Target(hit.channel.id, hit.programme.id, hit.programme.startMs)) },
                        words = searchWords,
                        onWordsTaken = { searchWords = null },
                        // Another server's result opens in place, from that server; Back returns to Search as it was.
                        onOpenElsewhere = { serverId, _, page -> nav.openInPlace(serverId, page) },
                    )
                }
                composable<TvRoutes.LiveTV> {
                    LiveChannelsScreen(
                        live, liveModel::select, watchLive, liveModel::refresh, onNavigate = { nav.navigateSection(it) },
                        onReminders = { nav.navigate(TvRoutes.LiveReminders) },
                        onRecordings = { nav.navigate(TvRoutes.LiveRecordings) },
                        target = guideTarget, onTargetShown = { guideTarget = null },
                    )
                }
                composable<TvRoutes.LiveRecordings> {
                    media.watchmy.client.tv.live.LiveRecordingsScreenTv(onNavigate = { nav.navigateSection(it) }, onPlay = nav::play)
                }
                composable<TvRoutes.LiveReminders> {
                    LiveRemindersScreenTv(live.channels, onNavigate = { nav.navigateSection(it) })
                }
                composable<TvRoutes.LivePlayer> {
                    val route = it.toRoute<TvRoutes.LivePlayer>()
                    LivePlayerScreen(
                        LiveTarget(LiveChannel(route.channelId, route.sourceId, route.number, route.name)),
                        channels = live.channels,
                        onExit = { nav.popBackStack() },
                        onChannels = { nav.navigateSection(TvRoutes.LiveTV) },
                        onRecordings = { nav.navigate(TvRoutes.LiveRecordings) },
                        onPlayRecording = nav::play,
                        onSelect = liveModel::select,
                        listings = live.listings,
                    )
                }
                composable<TvRoutes.Settings> {
                    SettingsScreen(
                        onAddServer = onAddServer,
                        onSignedOut = onSignedOut,
                        onNavigate = { r -> nav.navigateSection(r) },
                        onOpenRequests = { inbox -> nav.navigate(TvRoutes.Requests(inbox)) },
                    )
                }
                composable<TvRoutes.Requests> {
                    RequestsScreen(
                        onNavigate = { r -> nav.navigateSection(r) },
                        onOpenShow = { id -> nav.navigate(TvRoutes.Show(id)) },
                        onOpenMovie = { id -> nav.navigate(TvRoutes.Movie(id)) },
                        inbox = it.toRoute<TvRoutes.Requests>().inbox,
                    )
                }
                composable<TvRoutes.Player> {
                    val r = it.toRoute<TvRoutes.Player>()
                    PlayerScreen(
                        request = PlayRequest(r.fileId, r.startMs, r.title, r.subtitle, r.backdropPath, recordingId = r.recordingId, serverId = r.serverId),
                        onExit = { nav.popBackStack() },
                    )
                }
            }
            watchlist.picker?.let { picker ->
                WatchlistPanelTv(picker.title, watchlist.checked(held), picker.naming, watchlist.atLimit, watchlist.max, watchlistModel)
            }
            CardMenuDialog(cardMenu)
            // A save the server refused: said, since the button went back.
            Notice(watchlist.message?.text, watchlistModel::messageShown)
            banner?.let { b ->
                TvReminderBanner(
                    b, bannerModel.leadMinutes, live.channels.firstOrNull { it.id == b.channelId }, format,
                    onDismiss = dismiss,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 58.dp),
                )
            }
        }
    }
}
