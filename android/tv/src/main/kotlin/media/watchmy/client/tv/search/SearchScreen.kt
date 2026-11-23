package media.watchmy.client.tv.search

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Immutable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import media.watchmy.client.model.plural
import media.watchmy.client.tv.components.ContentEnd
import media.watchmy.client.tv.components.FocusGrowth
import media.watchmy.client.tv.components.PanelRow
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.WideCardHeight
import media.watchmy.client.tv.components.WideCardWidth
import media.watchmy.client.tv.components.tryFocus
import media.watchmy.client.tv.home.FactsLine
import media.watchmy.client.tv.home.HomeFact
import media.watchmy.client.tv.library.gridFacts
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
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.SEARCH_YOUR_SERVERS
import media.watchmy.client.model.noMatchesOnServers
import media.watchmy.client.model.noMatchesOnServersLine
import media.watchmy.client.model.searchYourServersLine
import media.watchmy.client.model.stillAsking
import media.watchmy.client.presentation.search.pageElsewhere
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.text.style.TextOverflow
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
import media.watchmy.client.tv.components.ArtBadge
import media.watchmy.client.tv.components.ArtProgress
import media.watchmy.client.tv.components.ArtWatched
import media.watchmy.client.tv.components.CardRow
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.ErrorState
import media.watchmy.client.tv.components.FocusMemory
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
import media.watchmy.client.tv.requests.RequestDialog
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.ReportShown
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
import media.watchmy.client.presentation.search.SearchViewModel
import media.watchmy.client.presentation.search.isEmpty
import media.watchmy.client.ui.rememberModelTextField
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import media.watchmy.client.tv.components.FocusableBox
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.layout.height
import media.watchmy.client.tv.components.TvKeyboardOpener
import media.watchmy.client.ui.onBack

/** How wide the keyboard is: ten keys and the gaps between them. Its box, its note and the key hints keep to it. */
private val KeyboardWidth = 712.dp

/** Where the results start: right of the keyboard (216 + 712 + 64). */
private val ResultsStart = 992.dp

/** Search's posters: a little narrower than the library's, so four fit beside the keyboard with the fifth peeking. */
private val SearchPosterWidth = 200.dp
private val SearchPosterHeight = 300.dp

/**
 * How far inside their list the results start. The list's own left edge has
 * to be right of the keyboard (which ends at 866): a focus search to the
 * right skips anything that starts left of the focused key, so with the
 * list starting under the keyboard's last column, ▶ from the end of the
 * number and letter rows found nothing and only the bottom row's wider keys
 * reached the results.
 */
private val ResultsInset = 40.dp

/**
 * Search: the box and the microphone over the on-screen keyboard on the
 * left; on the right, recent searches before anything is typed, then shows,
 * movies and episodes matching the title as the viewer types, with the
 * focused result's facts in one line above them. Focus starts on the
 * microphone where the TV offers speech, else on q. Back from a result or a
 * recent search goes to the keyboard; from the keyboard, Home. An episode
 * opens its page; holding OK on one plays it.
 *
 * With two or more servers signed in it looks in all of them: the server in
 * use's cards first, then each other server's at the end of its row, with
 * that server's name under the title. One from another server opens there
 * ([onOpenElsewhere]): the TV switches to it and comes back to Search with
 * the same words ([words], taken once) under the title's page.
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
    words: String? = null,
    onWordsTaken: () -> Unit = {},
    onOpenElsewhere: (serverId: String, query: String, page: DeepLink) -> Unit = { _, _, _ -> },
) {
    val services = LocalAppServices.current
    val vm = viewModel { SearchViewModel(services.library, auth = services.auth, requests = services.requests, saved = createSavedStateHandle(), servers = services.servers) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Each time Search comes on screen is a new visit: the other servers are asked which they are again before a sign-in goes to them.
    ReportShown(vm::setShown)
    // The field holds its text, which the keyboard edits; the model searches a moment after it changes.
    val query = rememberModelTextField(state.query, state.revision, vm::setQuery)
    // The words a result on another server was found with, searched again on the server the TV switched to.
    LaunchedEffect(words) {
        if (words != null) {
            vm.selectRecentQuery(words)
            onWordsTaken()
        }
    }
    val elsewhere = { serverId: String, page: DeepLink -> onOpenElsewhere(serverId, state.query.trim(), page) }
    // On Live TV (android-tv/LiveSearch): the listings' titles too, from API level 15 with Live TV.
    val liveTv = LocalLiveTVAvailable.current
    val liveSearch = viewModel(key = "tv-live-search") { LiveSearchViewModel(createSavedStateHandle(), services.live, services.servers) }
    val live by liveSearch.state.collectAsStateWithLifecycle()
    val withLive = liveTv && live.available
    LaunchedEffect(state.query, withLive) { if (withLive) liveSearch.search(state.query) }
    val liveRow: (@Composable () -> Unit)? = if (withLive && live.searched != null && live.error == null) {
        { TvLiveSearchRow(live, onWatchLive, onOpenLive, liveSearch::toggleReminder, Modifier.padding(start = ResultsInset)) }
    } else null
    var asking by remember { mutableStateOf<Requestable?>(null) }
    val firstKey = remember { FocusRequester() }
    val memory = rememberFocusMemory()
    // Voice typing in the app: what is heard fills the box as it is said.
    val voice = rememberVoiceTyping { query.setTextAndPlaceCursorAtEnd(it) }
    val pressMic = rememberVoicePress(voice)
    val listening = voice.state == Voice.Listening
    val tvKeyboard = remember { TvKeyboardOpener() }

    val micFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    // The first result, or the first recent search: where ▶ from the microphone lands.
    val resultsEntry = remember { FocusRequester() }
    // The line above the results: the focused result's title and facts, or how many there are.
    var focusedLine by remember { mutableStateOf<ResultLine?>(null) }
    var resultsFocused by remember { mutableStateOf(false) }

    TvScaffold(RailSection.Search, onNavigate) {
        // Back while listening stops it now, before anything else takes Back.
        Column(
            Modifier.padding(start = ContentStart, top = SafeY).width(KeyboardWidth).onBack(enabled = listening, onBack = voice::stop),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // ▲ from the keyboard's top row comes here; OK opens the TV's keyboard.
                FocusableBox(onClick = tvKeyboard::open, modifier = Modifier.weight(1f), cornerRadius = 16.dp, focusedScale = FocusGrowth.ROW) {
                    KeyboardField(
                        query.text.toString(),
                        placeholder = if (withLive) "Search your library and live TV" else "Search your library",
                        active = query.text.isNotEmpty(),
                        dim = listening,
                        icon = WmmIcons.Search,
                    )
                }
                // ▶ from the microphone goes to the results (or the recent searches) beside it, not to whatever is nearest.
                if (voice.available) {
                    MicKey(
                        listening,
                        pressMic,
                        Modifier.focusRequester(micFocus).onPreviewKeyEvent { e ->
                            if (e.key != Key.DirectionRight) return@onPreviewKeyEvent false
                            if (e.type == KeyEventType.KeyDown) resultsEntry.tryFocus() || resultsFocus.tryFocus()
                            true
                        },
                    )
                }
            }
            voiceLine(voice.state)?.let { line -> VoiceLine(line, listening, Modifier.padding(top = 16.dp)) }
            OnScreenKeyboard(
                field = query,
                modifier = Modifier.padding(top = 28.dp).alpha(if (listening) 0.4f else 1f),
                onDone = vm::clear,
                doneLabel = "Clear",
                firstKey = firstKey,
                opener = tvKeyboard,
            )
            if (!listening) KeyHints(Modifier.padding(top = 30.dp))
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = ResultsStart - ContentStart)
                .focusRequester(resultsFocus)
                .focusRestorer()
                .onFocusChanged { resultsFocused = it.hasFocus }
                // Back from the results or a recent search: the keyboard, not Home.
                .onBack(enabled = resultsFocused) { firstKey.tryFocus() },
        ) {
            val results = state.results
            when {
                state.error != null -> ErrorState(state.error!!, vm::retry, title = "Search didn't work")
                state.query.isBlank() && state.recentQueries.isNotEmpty() -> RecentSearches(state.recentQueries, voice.available, memory, vm::selectRecentQuery, resultsEntry)
                state.query.isBlank() ->
                    if (state.several) Hint(SEARCH_YOUR_SERVERS, searchYourServersLine(state.searched))
                    else Hint("Find anything in your library", if (voice.available) "Type part of a title, or press the microphone and say it. Shows, movies and episodes appear here as you type." else "Type part of a title. Shows, movies and episodes appear here as you type.")
                results == null -> LoadingState()
                state.noResults && (liveRow == null || live.results.isEmpty()) && state.unlisted.none { it.retry } -> {
                    val title = if (state.several) noMatchesOnServers(state.searched) else "Nothing in your library${if (withLive) " or on live TV" else ""} matches “${state.resultsFor}”"
                    val line = if (state.several) noMatchesOnServersLine(state.resultsFor) else "Try fewer letters." + if (state.requestable.isNotEmpty()) " Titles below aren’t in your library yet; you can ask for them." else ""
                    Results(state, results, services.library, memory, onOpenShow, onOpenMovie, onPlay, onAsk = { asking = it }, onOpenEpisode = onOpenEpisode, onOpenElsewhere = elsewhere, onRetry = vm::retry, liveRow = null, onFocusLine = { focusedLine = it }, entry = resultsEntry) {
                        NothingMatches(title, line)
                    }
                }
                else -> Results(state, results, services.library, memory, onOpenShow, onOpenMovie, onPlay, onAsk = { asking = it }, onOpenEpisode = onOpenEpisode, onOpenElsewhere = elsewhere, onRetry = vm::retry, liveRow = liveRow, onFocusLine = { focusedLine = it }, entry = resultsEntry) {
                    // The focused result's facts, or how many results there are while focus is on the keyboard.
                    ResultsLine(focusedLine.takeIf { resultsFocused } ?: ResultLine(resultsCount(state, live.results.size.takeIf { liveRow != null } ?: 0, state.resultsFor), emptyList()))
                }
            }
        }
        Notice(state.message, vm::messageShown)
    }
    // Back from a result puts focus back on it; a first visit starts on the keyboard.
    val returning = remember { memory.key != null }
    if (returning) {
        LaunchedEffect(Unit) {
            repeat(10) {
                withFrameNanos { }
                if (memory.restore(fallback = null)) return@LaunchedEffect
            }
            runCatching { firstKey.requestFocus() }
        }
    } else {
        // A first visit starts on the microphone where the TV offers speech, else on q.
        LaunchedEffect(Unit) {
            withFrameNanos { }
            if (!(voice.available && micFocus.tryFocus())) firstKey.tryFocus()
        }
    }

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

/** Before anything is typed, or when there is nothing to show: a heading and a line, level with the box. */
@Composable
private fun Hint(title: String, message: String) {
    // The column's box starts ContentStart short of the results (as the results' list does), so this starts at ContentStart in it.
    Column(Modifier.padding(start = ContentStart, top = SafeY, end = ContentEnd).widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = TvType.Heading, color = WmmColors.Text)
        Text(message, style = TvType.Body, color = WmmColors.TextSoft)
    }
}

/** "Nothing in your library matches “harq”", and what to try, over the titles that could be asked for. */
@Composable
private fun NothingMatches(title: String, line: String) {
    Column(Modifier.padding(start = ResultsInset, end = ContentEnd, bottom = 20.dp).widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = TvType.Heading, color = WmmColors.Text)
        Text(line, style = TvType.Body, color = WmmColors.TextSoft)
    }
}

/** Recent searches beside the keyboard, before anything is typed: OK searches again. With a microphone, how to use it. */
@Composable
private fun RecentSearches(recent: List<String>, mic: Boolean, memory: FocusMemory, onPick: (String) -> Unit, entry: FocusRequester) {
    Column(Modifier.padding(start = ContentStart, top = SafeY).width(760.dp)) {
        Box(Modifier.height(80.dp), contentAlignment = Alignment.CenterStart) {
            Text("Recent searches", style = TvType.CardTitle, color = WmmColors.Text)
        }
        Column(Modifier.padding(top = 28.dp).offset(x = (-24).dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            recent.take(6).forEachIndexed { i, q ->
                PanelRow(q, { onPick(q) }, Modifier.focusMemory(memory, "recent:$q").then(if (i == 0) Modifier.focusRequester(entry) else Modifier), icon = WmmIcons.Clock, iconTint = TvPalette.Muted)
            }
        }
        if (mic) {
            Row(Modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(WmmIcons.Microphone, contentDescription = null, tint = TvPalette.Muted, modifier = Modifier.size(28.dp))
                Text("Press OK on the microphone and say a title or a channel. It stops when you stop talking.", style = TvType.Second, color = WmmColors.TextSoft)
            }
        }
    }
}

/** The line above the results: a title and its facts, or a count. */
@Immutable
private data class ResultLine(val title: String, val facts: List<HomeFact>)

@Composable
private fun ResultsLine(line: ResultLine) {
    Row(Modifier.padding(start = ResultsInset, end = ContentEnd).heightIn(min = 64.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(line.title, style = TvType.CardTitle, color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (line.facts.isNotEmpty()) {
            Text("·", style = TvType.Body, color = TvPalette.Muted)
            FactsLine(line.facts)
        }
    }
}

/** "5 results for “har”". */
private fun resultsCount(state: SearchViewModel.State, live: Int, words: String): String {
    val n = state.shows.size + state.movies.size + state.episodes.size + live
    return "${plural(n, "result")} for “$words”"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Results(
    state: SearchViewModel.State,
    results: SearchResults,
    library: LibraryRepository,
    memory: FocusMemory,
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onAsk: (Requestable) -> Unit,
    onOpenEpisode: (showId: Long, season: Int, episodeId: Long) -> Unit,
    onOpenElsewhere: (serverId: String, page: DeepLink) -> Unit,
    onRetry: () -> Unit,
    liveRow: (@Composable () -> Unit)? = null,
    onFocusLine: (ResultLine) -> Unit = {},
    entry: FocusRequester? = null,
    top: @Composable () -> Unit = {},
) {
    val notMatched = rememberNotMatchedNotice()
    val requestable = state.requestable
    val viaSeerr = state.options.viaSeerr
    val shows = state.shows
    val movies = state.movies
    val episodes = state.episodes
    // A card on the server in use opens as it always did; one on another server opens there, after a switch.
    val open = { hit: SearchViewModel.Hit<BrowseItem> ->
        val server = hit.serverId
        if (server == null) hit.item.open(onOpenShow, onOpenMovie, onPlay, notMatched)
        else hit.item.pageElsewhere()?.let { onOpenElsewhere(server, it) } ?: notMatched()
    }
    val density = LocalDensity.current
    val spec = remember(density) { with(density) { KeepInViewSpec(leading = 130.dp.toPx(), trailing = 40.dp.toPx()) } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        // The list starts right of the keyboard, so ▶ from the last key of any of its rows comes here (ResultsInset).
        LazyColumn(Modifier.fillMaxSize().padding(start = ContentStart - ResultsInset), state = rememberLazyListState(), contentPadding = PaddingValues(top = SafeY, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item("top") { top() }
            if (shows.isNotEmpty()) {
                item("shows") { PosterSection("TV Shows", shows, library, memory, state.several, open, onFocusLine, entry) }
            }
            if (movies.isNotEmpty()) {
                item("movies") { PosterSection("Movies", movies, library, memory, state.several, open, onFocusLine, entry.takeIf { shows.isEmpty() }) }
            }
            if (liveRow != null) item("live") { liveRow() }
            val notInLibrary = requestable.besides(results)
            if (notInLibrary.isNotEmpty()) {
                item("requestable") {
                    Column {
                        SectionHeader("Not in your library", small = if (viaSeerr) "From Seerr" else "${notInLibrary.size}", start = ResultsInset)
                        CardRow(start = ResultsInset) {
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
            if (episodes.isNotEmpty()) {
                item("episodes") {
                    Column {
                        SectionHeader("Episodes", small = "${episodes.size}", start = ResultsInset)
                        CardRow(start = ResultsInset) {
                            items(episodes, key = { "${it.serverId.orEmpty()}:${it.item.episodeId}" }) { hit ->
                                val e = hit.item
                                val server = hit.serverId
                                MediaCard(
                                    title = e.showTitle,
                                    subtitle = e.airedLine(),
                                    imageUrl = hit.picture(library, e.stillPath ?: e.showBackdropPath, ImageSize.W780),
                                    artWidth = WideCardWidth,
                                    artHeight = WideCardHeight,
                                    onClick = { if (server == null) onOpenEpisode(e.showId, e.season, e.episodeId) else onOpenElsewhere(server, e.pageElsewhere()) },
                                    // Holding OK plays on the server in use; one on another server is opened there first.
                                    onLongClick = if (server == null) ({ e.playRequest()?.let(onPlay) ?: onOpenShow(e.showId) }) else null,
                                    modifier = Modifier
                                        .focusMemory(memory, "ep:${server.orEmpty()}:${e.episodeId}")
                                        .onFocusChanged { if (it.isFocused) onFocusLine(ResultLine(e.showTitle, listOf(HomeFact("${e.codeBadge} · ${e.title}")))) }
                                        .onServer(e.showTitle, hit.serverName),
                                    line = serverLine(hit.serverName, state.several),
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
            if (state.stillAsking.isNotEmpty() || state.unlisted.isNotEmpty()) {
                item("servers") { ServersTail(state, onRetry, Modifier.padding(start = ResultsInset, end = 58.dp)) }
            }
        }
    }
}

/** A picture from the server its title is on. */
private fun SearchViewModel.Hit<*>.picture(library: LibraryRepository, path: String?, size: ImageSize): String? =
    serverId?.let { library.imageUrlOn(it, path, size) } ?: library.imageUrl(path, size)

/** A card on another server says so to TalkBack: "Harbor Nights, on Cabin server". */
private fun Modifier.onServer(title: String, serverName: String?): Modifier =
    if (serverName == null) this else semantics { contentDescription = "$title, on $serverName" }

/**
 * The line under a card's title that names its server (`.sv`), when it isn't
 * the one in use. With several servers searched every card keeps the line's
 * room ([keepRoom]), so a row doesn't grow, and nothing under it moves, when
 * another server's titles arrive.
 */
private fun serverLine(serverName: String?, keepRoom: Boolean): (@Composable () -> Unit)? = when {
    serverName != null -> {
        {
            Row(Modifier.padding(top = 2.dp).height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(WmmIcons.Server, contentDescription = null, tint = WmmColors.TextSoft, modifier = Modifier.size(22.dp))
                Text(serverName, style = TvType.Caption.copy(fontWeight = FontWeight.SemiBold), color = WmmColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    keepRoom -> {
        { Box(Modifier.padding(top = 2.dp).height(30.dp)) }
    }
    else -> null
}

/**
 * The end of the results when search looks in several servers: the ones
 * still being asked, then a line for each whose titles aren't listed, with
 * Try again where asking again may help.
 */
@Composable
private fun ServersTail(state: SearchViewModel.State, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.stillAsking.isNotEmpty()) {
            Row(
                Modifier.heightIn(min = 56.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), color = WmmColors.Accent, strokeWidth = 3.dp, trackColor = Color.White.copy(alpha = 0.22f))
                Text(stillAsking(state.stillAsking), style = inter(22.sp), color = WmmColors.TextSoft)
            }
        }
        state.unlisted.forEach { server ->
            Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(WmmIcons.Alert, contentDescription = null, tint = WmmColors.Warn, modifier = Modifier.size(24.dp))
                Text(server.line, style = inter(22.sp, lineHeight = 31.sp), color = WmmColors.TextSoft, modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite })
                if (server.retry) TvButton("Try again", onRetry, Modifier.padding(start = 8.dp), size = TvButtonSize.Settings)
            }
        }
    }
}

/** A movie or show not in the library, with where it stands on the poster. */
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
        subtitle = null,
        imageUrl = library.imageUrl(item.posterPath, ImageSize.W342),
        artWidth = SearchPosterWidth,
        artHeight = SearchPosterHeight,
        onClick = onClick,
        modifier = Modifier.focusMemory(memory, "req:${item.mediaType}:${item.tmdbId}"),
        subtitleLine = false,
    ) {
        // The poster dims: it isn't in the library. What OK does is a pill along its foot (`.pill`).
        Box(Modifier.fillMaxSize().background(WmmColors.Ground.copy(alpha = 0.45f)))
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 10.dp, end = 10.dp, bottom = 14.dp)
                .fillMaxWidth()
                .height(44.dp)
                .background(WmmColors.Ground.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                .border(2.dp, TvPalette.Line3, RoundedCornerShape(10.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Icon(icon, contentDescription = null, tint = if (gold) WmmColors.Accent else WmmColors.Text, modifier = Modifier.size(22.dp))
            Text(label, style = TvType.Caption.copy(fontWeight = FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1)
        }
    }
}

@Composable
private fun PosterSection(
    title: String,
    items: List<SearchViewModel.Hit<BrowseItem>>,
    library: LibraryRepository,
    memory: FocusMemory,
    several: Boolean,
    onOpen: (SearchViewModel.Hit<BrowseItem>) -> Unit,
    onFocusLine: (ResultLine) -> Unit,
    entry: FocusRequester? = null,
) {
    Column {
        SectionHeader(title, small = "${items.size}", start = ResultsInset)
        CardRow(start = ResultsInset) {
            itemsIndexed(items, key = { _, it -> "${it.serverId.orEmpty()}:${it.item.id}" }) { i, hit ->
                val item = hit.item
                MediaCard(
                    title = item.title,
                    subtitle = null,
                    imageUrl = hit.picture(library, item.posterPath, ImageSize.W342),
                    artWidth = SearchPosterWidth,
                    artHeight = SearchPosterHeight,
                    onClick = { onOpen(hit) },
                    // Hold OK saves a result on this server to a watchlist; one on another server has nothing to hold.
                    onLongClick = if (hit.serverId == null) holdFor(item, null) else null,
                    modifier = Modifier
                        .focusMemory(memory, "${item.kind}:${hit.serverId.orEmpty()}:${item.id}")
                        .then(if (i == 0 && entry != null) Modifier.focusRequester(entry) else Modifier)
                        .onFocusChanged { if (it.isFocused) onFocusLine(ResultLine(item.title, item.gridFacts())) }
                        .onServer(item.title, hit.serverName),
                    line = serverLine(hit.serverName, several),
                    subtitleLine = several,
                ) {
                    if (item.isNew) ArtBadge("New", Modifier.align(Alignment.TopStart).padding(8.dp), BadgeStyle.Gold)
                    ArtWatched(item.watched)
                    ArtProgress(item.progress)
                }
            }
        }
    }
}

/**
 * The microphone beside Search's box: "Voice typing", or gold and ringed
 * with "Stop listening" while it listens (`.mic.live`).
 */
@Composable
private fun MicKey(listening: Boolean, onPress: () -> Unit, modifier: Modifier = Modifier) {
    FocusableBox(
        onClick = onPress,
        cornerRadius = 40.dp,
        focusedScale = FocusGrowth.BUTTON,
        modifier = modifier.size(80.dp).semantics { contentDescription = if (listening) "Stop listening" else "Speak instead of typing" },
    ) { focused ->
        Box(
            Modifier
                .fillMaxSize()
                .then(if (listening) Modifier.drawBehind { drawRings() } else Modifier)
                .background(
                    when {
                        listening -> WmmColors.Accent
                        focused -> TvPalette.Lift
                        else -> TvPalette.ButtonFill
                    },
                    CircleShape,
                )
                .border(2.dp, if (listening) WmmColors.Accent else if (focused) TvPalette.Lift else WmmColors.Text.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(WmmIcons.Microphone, contentDescription = null, tint = if (listening) WmmColors.OnAccent else WmmColors.Text, modifier = Modifier.size(30.dp))
        }
    }
}

/** The live microphone's two gold rings, 10 and 22 dp out. */
private fun DrawScope.drawRings() {
    drawCircle(WmmColors.Accent.copy(alpha = 0.08f), radius = size.minDimension / 2 + 22.dp.toPx())
    drawCircle(WmmColors.Accent.copy(alpha = 0.22f), radius = size.minDimension / 2 + 10.dp.toPx())
}

/** Under the box: listening (gold, with a dot), or why the microphone can't listen. */
@Composable
private fun VoiceLine(text: String, listening: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (listening) Box(Modifier.size(14.dp).background(WmmColors.Accent, CircleShape))
        Text(text, style = TvType.Second, color = if (listening) WmmColors.Accent else TvPalette.Muted)
    }
}

/**
 * The key hints under the keyboard, while typing: only what no key shows,
 * one a line and no wider than the keyboard. On one line, they ran out
 * across the results.
 */
@Composable
private fun KeyHints(modifier: Modifier = Modifier) {
    Column(modifier.width(KeyboardWidth), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KeyHint("▶", "from the last key in a row: the results")
        KeyHint("Hold ⌫", "clears what you typed")
    }
}

@Composable
private fun KeyHint(key: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        KeyCap(key)
        Text(text, style = TvType.Caption, color = TvPalette.Muted)
    }
}

@Composable
private fun KeyCap(key: String) {
    Box(
        Modifier.height(40.dp).defaultMinSize(minWidth = 44.dp).border(2.dp, TvPalette.Line3, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(key, style = TvType.Caption.copy(fontWeight = FontWeight.Bold), color = WmmColors.TextSoft)
    }
}
