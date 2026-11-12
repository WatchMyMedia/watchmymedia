package media.watchmy.client.tv.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.Requestable
import media.watchmy.client.model.RequestableState
import media.watchmy.client.model.SearchResults
import media.watchmy.client.model.airedLine
import media.watchmy.client.model.besides
import media.watchmy.client.model.codeBadge
import media.watchmy.client.model.playRequest
import media.watchmy.client.model.subtitle
import media.watchmy.client.presentation.Load
import media.watchmy.client.tv.components.ArtBadge
import media.watchmy.client.tv.components.ArtProgress
import media.watchmy.client.tv.components.ArtWatched
import media.watchmy.client.tv.components.CardRow
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.EmptyState
import media.watchmy.client.tv.components.ErrorState
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.KeepInViewSpec
import media.watchmy.client.tv.components.KeyboardField
import media.watchmy.client.tv.components.LoadingState
import media.watchmy.client.tv.components.MediaCard
import media.watchmy.client.tv.components.Notice
import media.watchmy.client.tv.components.OnScreenKeyboard
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.tv.components.SectionHeader
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.tv.library.gridSubtitle
import media.watchmy.client.tv.requests.RequestDialog
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.open
import media.watchmy.client.ui.rememberNotMatchedNotice
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveSearchHit
import media.watchmy.client.presentation.live.LiveSearchViewModel
import media.watchmy.client.tv.components.LocalLiveTVAvailable
import media.watchmy.client.tv.live.TvLiveSearchRow

/** Where the results start: right of the keyboard (154 + 712 + 60). */
private val ResultsStart = 926.dp

/**
 * Search (no mockup; it follows the Library's cards): the on-screen keyboard
 * on the left, and shows, movies and episodes matching the title on the
 * right as the viewer types. An episode opens its page; holding centre on
 * one plays it.
 */
@Composable
fun SearchScreen(
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onNavigate: (Any) -> Unit,
    onOpenEpisode: (showId: Long, season: Int, episodeId: Long) -> Unit = { id, _, _ -> onOpenShow(id) },
    onWatchLive: (LiveChannel) -> Unit = {},
    onOpenLive: (LiveSearchHit) -> Unit = {},
) {
    val services = LocalAppServices.current
    val vm = viewModel { SearchViewModel(services.library, requests = services.requests, saved = createSavedStateHandle()) }
    val state by vm.state.collectAsStateWithLifecycle()
    // On Live TV (android-tv/LiveSearch): the listings' titles too, from API level 15 with Live TV.
    val liveTv = LocalLiveTVAvailable.current
    val liveSearch = viewModel(key = "tv-live-search") { LiveSearchViewModel(createSavedStateHandle(), services.live, services.servers) }
    val live by liveSearch.state.collectAsStateWithLifecycle()
    val withLive = liveTv && live.available
    LaunchedEffect(state.query, withLive) { if (withLive) liveSearch.search(state.query) }
    val liveRow: (@Composable () -> Unit)? = if (withLive && live.searched != null && live.error == null) {
        { TvLiveSearchRow(live, onWatchLive, onOpenLive, liveSearch::toggleReminder, Modifier.padding(start = ContentStart)) }
    } else null
    var asking by remember { mutableStateOf<Requestable?>(null) }
    val firstKey = remember { FocusRequester() }
    val memory = rememberFocusMemory()

    TvScaffold(RailSection.Search, onNavigate) {
        Column(Modifier.padding(start = ContentStart, top = 56.dp).width(712.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("Search", style = inter(46.sp, FontWeight.ExtraBold, tracking = -0.02f), color = WmmColors.Text)
            KeyboardField(state.query, placeholder = if (withLive) "Titles of shows, movies, episodes or Live TV programmes" else "Titles of shows, movies or episodes", active = true)
            OnScreenKeyboard(
                onText = vm::type,
                onBackspace = vm::backspace,
                onDone = vm::clear,
                doneLabel = "Clear",
                firstKey = firstKey,
            )
        }
        Box(Modifier.fillMaxSize().padding(start = ResultsStart - ContentStart)) {
            when (val r = state.results) {
                null -> Hint("Find anything in your library", "Type part of a title. Shows, movies and episodes appear here as you type.")
                Load.Loading -> LoadingState()
                is Load.Failed -> ErrorState(r.message, vm::retry, title = "Search didn't work")
                is Load.Ready -> if (r.data.isEmpty() && state.requestable.isEmpty() && (liveRow == null || live.results.isEmpty())) {
                    Hint("Nothing matches", "No titles in your library match “${state.query.trim()}”.")
                } else {
                    Results(r.data, state.requestable, services.library, memory, onOpenShow, onOpenMovie, onPlay, onAsk = { asking = it }, viaSeerr = state.options.viaSeerr, onOpenEpisode = onOpenEpisode, liveRow = liveRow)
                }
            }
        }
        Notice(state.notice, vm::noticeShown)
    }
    InitialFocus(firstKey)

    asking?.let { item ->
        RequestDialog(
            subject = item,
            options = state.options,
            onSent = { message ->
                asking = null
                vm.requested(message)
            },
            onDismiss = { asking = null },
        )
    }
}

@Composable
private fun Hint(title: String, message: String) {
    Box(Modifier.fillMaxSize().padding(start = ContentStart, end = 58.dp), contentAlignment = Alignment.Center) {
        EmptyState(title, message)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Results(
    results: SearchResults,
    requestable: List<Requestable>,
    library: LibraryRepository,
    memory: FocusMemory,
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onAsk: (Requestable) -> Unit,
    viaSeerr: Boolean,
    onOpenEpisode: (showId: Long, season: Int, episodeId: Long) -> Unit,
    liveRow: (@Composable () -> Unit)? = null,
) {
    val notMatched = rememberNotMatchedNotice()
    val density = LocalDensity.current
    val spec = remember(density) { with(density) { KeepInViewSpec(leading = 90.dp.toPx(), trailing = 40.dp.toPx()) } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(top = 60.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (results.shows.isNotEmpty()) {
                item("shows") {
                    PosterSection("TV Shows", results.shows, library, memory) { it.open(onOpenShow, onOpenMovie, onPlay, notMatched) }
                }
            }
            if (results.movies.isNotEmpty()) {
                item("movies") {
                    PosterSection("Movies", results.movies, library, memory) { it.open(onOpenShow, onOpenMovie, onPlay, notMatched) }
                }
            }
            if (liveRow != null) item("live") { liveRow() }
            val notInLibrary = requestable.besides(results)
            if (notInLibrary.isNotEmpty()) {
                item("requestable") {
                    Column {
                        SectionHeader("Not in your library", small = if (viaSeerr) "From Seerr" else "${notInLibrary.size}", start = ContentStart)
                        CardRow(start = ContentStart) {
                            items(notInLibrary, key = { "${it.mediaType}${it.tmdbId}" }) { item ->
                                NotInLibraryCard(item, library, memory) {
                                    when {
                                        item.state == RequestableState.Requestable -> onAsk(item)
                                        item.state == RequestableState.Available && item.showId != null -> onOpenShow(item.showId!!)
                                        item.state == RequestableState.Available && item.movieId != null -> onOpenMovie(item.movieId!!)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (results.episodes.isNotEmpty()) {
                item("episodes") {
                    Column {
                        SectionHeader("Episodes", small = "${results.episodes.size}", start = ContentStart)
                        CardRow(start = ContentStart) {
                            items(results.episodes, key = { it.episodeId }) { e ->
                                MediaCard(
                                    title = e.showTitle,
                                    subtitle = e.airedLine(),
                                    imageUrl = library.imageUrl(e.stillPath ?: e.showBackdropPath, ImageSize.W780),
                                    artWidth = 320.dp,
                                    artHeight = 180.dp,
                                    onClick = { onOpenEpisode(e.showId, e.season, e.episodeId) },
                                    onLongClick = { e.playRequest()?.let(onPlay) ?: onOpenShow(e.showId) },
                                    modifier = Modifier.focusMemory(memory, "ep:${e.episodeId}"),
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
        }
    }
}

/** A movie or show not in the library, with where it stands on the poster (SearchRequest.dc.html, Android TV). */
@Composable
private fun NotInLibraryCard(item: Requestable, library: LibraryRepository, memory: FocusMemory, onClick: () -> Unit) {
    val (label, icon, gold) = when (item.state) {
        RequestableState.Requestable -> Triple("Request", WmmIcons.Plus, false)
        RequestableState.Requested -> Triple("Requested", WmmIcons.Clock, false)
        RequestableState.Downloading -> Triple("Downloading", WmmIcons.Download, true)
        RequestableState.Available -> Triple("In your library", WmmIcons.CheckCircle, true)
    }
    MediaCard(
        title = item.title,
        subtitle = item.subtitle(),
        imageUrl = library.imageUrl(item.posterPath, ImageSize.W342),
        artWidth = 190.dp,
        artHeight = 285.dp,
        onClick = onClick,
        focusedScale = 1.08f,
        modifier = Modifier.focusMemory(memory, "req:${item.mediaType}:${item.tmdbId}"),
    ) {
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 14.dp).background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, tint = if (gold) WmmColors.Accent else WmmColors.Text, modifier = Modifier.size(16.dp))
            Text(label, style = inter(14.sp, FontWeight.Bold), color = WmmColors.Text, maxLines = 1)
        }
    }
}

@Composable
private fun PosterSection(title: String, items: List<BrowseItem>, library: LibraryRepository, memory: FocusMemory, onOpen: (BrowseItem) -> Unit) {
    Column {
        SectionHeader(title, small = "${items.size}", start = ContentStart)
        CardRow(start = ContentStart) {
            items(items, key = { it.id }) { item ->
                MediaCard(
                    title = item.title,
                    subtitle = item.gridSubtitle(),
                    imageUrl = library.imageUrl(item.posterPath, ImageSize.W342),
                    artWidth = 190.dp,
                    artHeight = 285.dp,
                    onClick = { onOpen(item) },
                    focusedScale = 1.08f,
                    modifier = Modifier.focusMemory(memory, "${item.kind}:${item.id}"),
                ) {
                    if (item.isNew) ArtBadge("New", Modifier.align(Alignment.TopStart).padding(8.dp), BadgeStyle.Gold)
                    ArtWatched(item.watched)
                    ArtProgress(item.progress)
                }
            }
        }
    }
}
