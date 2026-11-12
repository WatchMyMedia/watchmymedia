package media.watchmy.client.mobile.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.compose.ui.window.DialogProperties
import media.watchmy.client.mobile.rows.ListScreen
import media.watchmy.client.mobile.rows.RowEditorScreen
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import media.watchmy.client.mobile.components.Avatar
import media.watchmy.client.mobile.components.CastMiniController
import media.watchmy.client.mobile.components.MobileBottomBar
import media.watchmy.client.mobile.components.MobileTab
import media.watchmy.client.mobile.detail.MovieScreen
import media.watchmy.client.mobile.detail.ShowPageScreen
import media.watchmy.client.mobile.detail.TrailerScreen
import media.watchmy.client.mobile.downloads.DownloadsScreen
import media.watchmy.client.mobile.home.HomeScreen
import media.watchmy.client.mobile.library.LibraryScreen
import media.watchmy.client.mobile.link.LinkTvScreen
import media.watchmy.client.mobile.live.LiveChannelsScreen
import media.watchmy.client.mobile.live.LiveCastMini
import media.watchmy.client.mobile.live.LivePlayerScreen
import media.watchmy.client.mobile.live.LiveRemindersScreen
import media.watchmy.client.mobile.live.LiveSearchScreen
import media.watchmy.client.mobile.live.castMiniLine
import media.watchmy.client.mobile.player.PlayerScreen
import media.watchmy.client.mobile.requests.InboxScreen
import media.watchmy.client.mobile.requests.MyRequestsScreen
import media.watchmy.client.mobile.search.SearchScreen
import media.watchmy.client.mobile.settings.SettingsScreen
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.CastState
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.Trailer
import media.watchmy.client.model.isYouTubeKey
import media.watchmy.client.presentation.live.LiveChannelsViewModel
import media.watchmy.client.presentation.live.LiveGuideViewModel
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.rememberRequestBadge
import media.watchmy.client.ui.rememberTabletLayout

fun NavHostController.play(request: PlayRequest) =
    navigate(MobileRoutes.Player(request.fileId, request.startMs, request.title, request.subtitle, request.backdropPath, request.recordingId))

/** The route behind each bottom-bar tab. */
private fun MobileTab.route(): Any = when (this) {
    MobileTab.Home -> MobileRoutes.Home
    MobileTab.TvShows -> MobileRoutes.TvShows
    MobileTab.Movies -> MobileRoutes.Movies
    MobileTab.Search -> MobileRoutes.Search
    MobileTab.Downloads -> MobileRoutes.Downloads
    MobileTab.LiveTV -> MobileRoutes.LiveTV
    MobileTab.Settings -> MobileRoutes.Settings
}

/** The tab a destination belongs to, or null where the bottom bar hides (details, Link a TV, the player). */
private fun NavDestination.tab(): MobileTab? = when {
    hasRoute<MobileRoutes.Home>() -> MobileTab.Home
    hasRoute<MobileRoutes.TvShows>() -> MobileTab.TvShows
    hasRoute<MobileRoutes.Movies>() -> MobileTab.Movies
    hasRoute<MobileRoutes.Search>() -> MobileTab.Search
    hasRoute<MobileRoutes.Downloads>() -> MobileTab.Downloads
    hasRoute<MobileRoutes.LiveTV>() -> MobileTab.LiveTV
    hasRoute<MobileRoutes.LiveSearch>() -> MobileTab.LiveTV
    hasRoute<MobileRoutes.LiveRecordings>() -> MobileTab.LiveTV
    hasRoute<MobileRoutes.LiveReminders>() -> MobileTab.LiveTV
    hasRoute<MobileRoutes.Settings>() -> MobileTab.Settings
    else -> null
}

/** Switches tab the Material way: one copy of each tab, each keeping its own back stack and scroll. */
private fun NavHostController.openTab(tab: MobileTab) = navigate(tab.route()) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    // Home is the retained root. Restoring its saved stack would reopen the
    // detail page just popped by a tablet rail tap instead of showing Home.
    restoreState = tab != MobileTab.Home
}

/** Navigates to [route], as a tab switch when it is one of the tabs' routes. */
private fun NavHostController.go(route: Any) {
    val tab = MobileTab.entries.firstOrNull { it.route() == route }
    if (tab != null) openTab(tab) else navigate(route)
}

/**
 * The signed-in part of the phone app, with the bottom bar on the five
 * top-level screens. Sign-in and the server picker are shown by
 * [media.watchmy.client.mobile.MobileApp] before this. [deepLink] is opened
 * once, then [onDeepLinkHandled] is called.
 */
@Composable
fun MobileNavHost(
    onSwitchServer: () -> Unit,
    onSignedOut: () -> Unit,
    nav: NavHostController = rememberNavController(),
    deepLink: DeepLink? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    val services = LocalAppServices.current
    val liveModel = viewModel { LiveChannelsViewModel(services.live, services.servers, services.auth) }
    val live by liveModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(liveModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                liveModel.refresh()
                delay(30_000)
            }
        }
    }
    // With a TV connected that plays Live TV, and a server that lets this account cast, Watch casts (PLAN-LIVE-TV.md §4.1).
    val castState by services.cast.state.collectAsStateWithLifecycle()
    val liveCast by services.cast.live.state.collectAsStateWithLifecycle()
    val castDevice = (castState as? CastState.Connected)?.deviceName?.takeIf { live.castable && liveCast.receiverLive == true }
    val notLiveDevice = (castState as? CastState.Connected)?.deviceName?.takeIf { live.castable && liveCast.receiverLive == false }
    val watchLive = { channel: LiveChannel ->
        nav.navigate(MobileRoutes.LivePlayer(channel.id, channel.sourceId, channel.number, channel.name, onTv = castDevice != null))
    }
    val openLiveRemote = { channel: LiveChannel ->
        nav.navigate(MobileRoutes.LivePlayer(channel.id, channel.sourceId, channel.number, channel.name, onTv = true))
    }
    // A search result or reminder to show in the guide, once.
    var guideTarget by remember { mutableStateOf<LiveGuideViewModel.Target?>(null) }
    val openInGuide = { target: LiveGuideViewModel.Target ->
        guideTarget = target
        if (!nav.popBackStack<MobileRoutes.LiveTV>(inclusive = false)) nav.openTab(MobileTab.LiveTV)
    }
    val entry by nav.currentBackStackEntryAsState()
    val tab = entry?.destination?.tab()
    val railTab = tab ?: when {
        entry?.destination?.hasRoute<MobileRoutes.Episode>() == true -> MobileTab.TvShows
        entry?.destination?.hasRoute<MobileRoutes.Inbox>() == true -> MobileTab.Settings
        entry?.destination?.hasRoute<MobileRoutes.MyRequests>() == true -> MobileTab.Settings
        entry?.destination?.hasRoute<MobileRoutes.Show>() == true -> MobileTab.TvShows
        entry?.destination?.hasRoute<MobileRoutes.Movie>() == true -> MobileTab.Movies
        entry?.destination?.hasRoute<MobileRoutes.LinkTv>() == true -> MobileTab.Settings
        entry?.destination?.hasRoute<MobileRoutes.EditRows>() == true -> MobileTab.Home
        entry?.destination?.hasRoute<MobileRoutes.MediaList>() == true -> MobileTab.Home
        else -> null
    }
    val badge by rememberRequestBadge(services.requests)

    LaunchedEffect(deepLink) {
        fun page(link: DeepLink?) = when (link) {
            is DeepLink.Show -> nav.navigate(MobileRoutes.Show(link.id, link.season, link.episode))
            is DeepLink.Movie -> nav.navigate(MobileRoutes.Movie(link.id))
            else -> Unit
        }
        when (deepLink) {
            null -> return@LaunchedEffect
            is DeepLink.Play -> nav.play(resolvePlayLink(services.library, deepLink.fileId))
            is DeepLink.Details -> page(resolveDetailsLink(services.library, deepLink.fileId))
            is DeepLink.Show, is DeepLink.Movie -> page(deepLink)
            is DeepLink.LinkTv -> nav.navigate(MobileRoutes.LinkTv(deepLink.code))
            // A reminder's Watch: the channel from the list once it is in, else its id alone (the player names it).
            is DeepLink.Live -> {
                // At a cold start the channels may still be on their way.
                val listed = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    liveModel.state.first { it.channels.any { c -> c.id == deepLink.channelId } || it.error != null || (!it.loading && it.capabilities.enabled && it.channels.isNotEmpty()) }
                }
                val channel = listed?.channels?.firstOrNull { it.id == deepLink.channelId }
                if (channel != null && channel.available) watchLive(channel) else nav.openTab(MobileTab.LiveTV)
            }
            DeepLink.LiveGuide -> nav.openTab(MobileTab.LiveTV)
        }
        onDeepLinkHandled()
    }

    val openShow = { id: Long -> nav.navigate(MobileRoutes.Show(id)) }
    val openSeason = { id: Long, season: Int -> nav.navigate(MobileRoutes.Show(id, season)) }
    val openEpisode = { id: Long, season: Int, episode: Long -> nav.navigate(MobileRoutes.Episode(id, season, episode)) }
    val openMovie = { id: Long -> nav.navigate(MobileRoutes.Movie(id)) }
    val go = { route: Any -> nav.go(route) }

    val user by services.auth.user.collectAsStateWithLifecycle()
    val layout = rememberTabletLayout()
    val adaptive = currentWindowAdaptiveInfoV2()
    val showRail = layout.isTablet && adaptive.windowSizeClass.isWidthAtLeastBreakpoint(600) &&
        entry?.destination?.hasRoute<MobileRoutes.Player>() != true &&
        entry?.destination?.hasRoute<MobileRoutes.LivePlayer>() != true &&
        entry?.destination?.hasRoute<MobileRoutes.Trailer>() != true
    val downloads by services.downloads.enabled.collectAsStateWithLifecycle()
    val tabs = MobileTab.shown(downloads, rail = showRail, liveTV = live.available)
    Box(Modifier.fillMaxSize()) {
        NavigationSuiteScaffold(
            layoutType = if (showRail) NavigationSuiteType.NavigationRail else NavigationSuiteType.None,
            containerColor = WmmColors.Ground,
            contentColor = WmmColors.Text,
            navigationSuiteItems = {
                tabs.forEach { destination ->
                    item(
                        selected = destination == railTab,
                        onClick = { nav.openTab(destination) },
                        icon = {
                            androidx.compose.material3.BadgedBox(badge = {
                                if (destination == MobileTab.Settings && (badge.inbox ?: 0) > 0) {
                                    androidx.compose.material3.Badge { Text("${badge.inbox}") }
                                }
                            }) { Icon(destination.icon, contentDescription = null) }
                        },
                        label = { Text(destination.label) },
                    )
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                NavHost(nav, startDestination = MobileRoutes.Home, modifier = Modifier.weight(1f)) {
                    composable<MobileRoutes.Home> {
                        HomeScreen(onPlay = nav::play, onOpenShow = openShow, onOpenMovie = openMovie, onNavigate = go, onOpenSeason = openSeason, onOpenEpisode = openEpisode, liveChannels = live.channels.takeIf { live.available }.orEmpty(), onWatchLive = watchLive, liveListings = live.listings)
                    }
                    composable<MobileRoutes.TvShows> {
                        LibraryScreen(type = LibraryType.Tv, onOpenShow = openShow, onOpenMovie = openMovie, onNavigate = go, onPlay = nav::play)
                    }
                    composable<MobileRoutes.Movies> {
                        LibraryScreen(type = LibraryType.Movie, onOpenShow = openShow, onOpenMovie = openMovie, onNavigate = go, onPlay = nav::play)
                    }
                    composable<MobileRoutes.Show> { entry ->
                        val route = entry.toRoute<MobileRoutes.Show>()
                        val arrival = when {
                            route.episode != null -> ShowArrival.Episode(route.episode, route.season)
                            route.season != null -> ShowArrival.Season(route.season)
                            else -> ShowArrival.Show
                        }
                        ShowPage(nav, route.id, arrival)
                    }
                    composable<MobileRoutes.Episode> {
                        val route = it.toRoute<MobileRoutes.Episode>()
                        ShowPage(nav, route.showId, ShowArrival.Episode(route.episodeId, route.season))
                    }
                    composable<MobileRoutes.Movie> {
                        MovieScreen(
                            movieId = it.toRoute<MobileRoutes.Movie>().id,
                            onPlay = nav::play,
                            onBack = { nav.popBackStack() },
                            onOpenSettings = { go(MobileRoutes.Settings) },
                            onOpenTrailer = { t -> nav.navigate(MobileRoutes.Trailer(t.movieId, t.movieTitle, t.trailers.map { it.key }, t.trailers.map { it.name })) },
                        )
                    }
                    composable<MobileRoutes.Downloads> {
                        DownloadsScreen(onPlay = nav::play, onOpenMovie = openMovie, onOpenShow = openShow, onOpenEpisode = openEpisode, onNavigate = go)
                    }
                    composable<MobileRoutes.Search> {
                        SearchScreen(onOpenShow = openShow, onOpenMovie = openMovie, onPlay = nav::play, onNavigate = go, onOpenSeason = openSeason, onOpenEpisode = openEpisode)
                    }
                    composable<MobileRoutes.LiveTV> {
                        LiveChannelsScreen(
                            live, user?.name, liveModel::search, liveModel::select, watchLive, liveModel::refresh, onSettings = { go(MobileRoutes.Settings) },
                            onSearchListings = { nav.navigate(MobileRoutes.LiveSearch) },
                            onReminders = { nav.navigate(MobileRoutes.LiveReminders) },
                            onRecordings = { nav.navigate(MobileRoutes.LiveRecordings) },
                            onPlayRecording = nav::play,
                            onReminderTime = { go(MobileRoutes.Settings) },
                            target = guideTarget, onTargetShown = { guideTarget = null },
                            castDevice = castDevice, notLiveDevice = notLiveDevice,
                        )
                    }
                    composable<MobileRoutes.LiveSearch> {
                        LiveSearchScreen(
                            onBack = { nav.popBackStack() }, onWatch = watchLive,
                            onOpen = { hit -> openInGuide(LiveGuideViewModel.Target(hit.channel.id, hit.programme.id, hit.programme.startMs)) },
                            castDevice = castDevice,
                        )
                    }
                composable<MobileRoutes.LiveRecordings> {
                    media.watchmy.client.mobile.live.LiveRecordingsScreen(live.channels, onBack = { nav.popBackStack() }, onPlay = nav::play)
                }
                    composable<MobileRoutes.LiveReminders> {
                        LiveRemindersScreen(
                            live.channels, onBack = { nav.popBackStack() },
                            onOpen = { r -> r.channelId?.let { openInGuide(LiveGuideViewModel.Target(it, r.programmeId, r.startMs)) } },
                            onSettings = { go(MobileRoutes.Settings) },
                        )
                    }
                    composable<MobileRoutes.LivePlayer> {
                        val route = it.toRoute<MobileRoutes.LivePlayer>()
                        LivePlayerScreen(
                            LiveTarget(LiveChannel(route.channelId, route.sourceId, route.number, route.name)), channels = live.channels, onExit = { nav.popBackStack() },
                            listings = live.listings, castable = live.castable, onTv = route.onTv,
                            onRecordings = { nav.navigate(MobileRoutes.LiveRecordings) },
                        onPlayRecording = nav::play,
                            onGuide = {
                                // Back to the guide whether the player was opened from it or from Home.
                                if (!nav.popBackStack<MobileRoutes.LiveTV>(inclusive = false)) {
                                    nav.popBackStack()
                                    go(MobileRoutes.LiveTV)
                                }
                            },
                        )
                    }
                    composable<MobileRoutes.Settings> {
                        SettingsScreen(
                            onSwitchServer = onSwitchServer,
                            onSignedOut = onSignedOut,
                            onLinkTv = { nav.navigate(MobileRoutes.LinkTv()) },
                            onNavigate = go,
                            onOpenRequests = { nav.navigate(MobileRoutes.MyRequests) }.takeIf { badge.requests },
                            onOpenInbox = { nav.navigate(MobileRoutes.Inbox) }.takeIf { badge.inbox != null },
                            inboxCount = badge.inbox ?: 0,
                        )
                    }
                    composable<MobileRoutes.EditRows> {
                        val r = it.toRoute<MobileRoutes.EditRows>()
                        RowEditorScreen(r.screen, r.name, onClose = { nav.popBackStack() }, onOpenList = { id -> nav.navigate(MobileRoutes.MediaList(id)) })
                    }
                    dialog<MobileRoutes.EditRowsDialog>(dialogProperties = DialogProperties(usePlatformDefaultWidth = false)) {
                        val r = it.toRoute<MobileRoutes.EditRowsDialog>()
                        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                            RowEditorScreen(r.screen, r.name, onClose = { nav.popBackStack() }, onOpenList = { id -> nav.popBackStack(); nav.navigate(MobileRoutes.MediaList(id)) }, tablet = true)
                        }
                    }
                    composable<MobileRoutes.MediaList> {
                        ListScreen(it.toRoute<MobileRoutes.MediaList>().id, onBack = { nav.popBackStack() }, onOpenShow = openShow, onOpenMovie = openMovie, onPlay = nav::play)
                    }
                    composable<MobileRoutes.MyRequests> { MyRequestsScreen(onBack = { nav.popBackStack() }) }
                    composable<MobileRoutes.Inbox> { InboxScreen(onBack = { nav.popBackStack() }) }
                    composable<MobileRoutes.LinkTv> {
                        LinkTvScreen(initialCode = it.toRoute<MobileRoutes.LinkTv>().code, onDone = { nav.popBackStack() })
                    }
                    composable<MobileRoutes.Trailer> {
                        val r = it.toRoute<MobileRoutes.Trailer>()
                        val trailers = r.keys.zip(r.names) { key, name -> Trailer.YouTube(name, key) }.filter { t -> isYouTubeKey(t.key) }
                        TrailerScreen(movieTitle = r.movieTitle, trailers = trailers, onBack = { nav.popBackStack() })
                    }
                    composable<MobileRoutes.Player> {
                        val r = it.toRoute<MobileRoutes.Player>()
                        PlayerScreen(request = PlayRequest(r.fileId, r.startMs, r.title, r.subtitle, r.backdropPath, recordingId = r.recordingId), onExit = { nav.popBackStack() })
                    }
                }
                // While casting, the TV's play on every screen but the player itself: a channel's
                // (Live TV included), or a file's (as before, not on Live TV).
                val inPlayer = entry?.destination?.hasRoute<MobileRoutes.Player>() == true || entry?.destination?.hasRoute<MobileRoutes.LivePlayer>() == true ||
                    entry?.destination?.hasRoute<MobileRoutes.Trailer>() == true
                if (!inPlayer && castMiniLine(liveCast, liveCast.deviceName.orEmpty()) != null) LiveCastMini(live.channels, onOpen = openLiveRemote)
                else if (!inPlayer && tab != MobileTab.LiveTV) CastMiniController(onOpen = nav::play)
                if (tab != null && !showRail) MobileBottomBar(tab, onSelect = nav::openTab, settingsCount = badge.inbox ?: 0, tabs = tabs)
            }
        }
        if (showRail) {
            IconButton(
                onClick = { nav.openTab(MobileTab.Settings) },
                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 16.dp, bottom = 16.dp).semantics { contentDescription = "Settings" },
            ) { Avatar(user?.name) }
        }
    }
}

/** The one page for a show, a season or an episode (PLAN-SHOW-PAGE.md §4.2): what goes on top first is [arrival]'s. */
@Composable
private fun ShowPage(nav: NavHostController, showId: Long, arrival: ShowArrival) {
    ShowPageScreen(
        showId = showId,
        arrival = arrival,
        onPlay = nav::play,
        onBack = { nav.popBackStack() },
        onOpenSettings = { nav.go(MobileRoutes.Settings) },
        onOpenDownloads = { nav.go(MobileRoutes.Downloads) },
    )
}
