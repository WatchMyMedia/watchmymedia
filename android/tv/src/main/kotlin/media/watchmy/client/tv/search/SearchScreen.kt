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
import media.watchmy.client.presentation.library.gridSubtitle
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

/** Where the results start: right of the keyboard (154 + 712 + 60). */
private val ResultsStart = 926.dp

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
    val vm = viewModel { SearchViewModel(services.library, auth = services.auth, requests = services.requests, saved = createSavedStateHandle()) }
    val state by vm.state.collectAsStateWithLifecycle()
    // The field holds its text, which the keyboard edits; the model searches a moment after it changes.
    val query = rememberModelTextField(state.query, state.revision, vm::setQuery)
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

    TvScaffold(RailSection.Search, onNavigate) {
        // Back while listening stops it now, before anything else takes Back.
        Column(
            Modifier.padding(start = ContentStart, top = 56.dp).width(KeyboardWidth).onBack(enabled = listening, onBack = voice::stop),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text("Search", style = inter(46.sp, FontWeight.ExtraBold, tracking = -0.02f), color = WmmColors.Text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // ▲ from the keyboard's top row comes here, and ▶ from here to the microphone; OK opens the TV's keyboard.
                FocusableBox(onClick = tvKeyboard::open, modifier = Modifier.weight(1f), cornerRadius = 14.dp, focusedScale = 1f, ringOffset = 0.dp) {
                    KeyboardField(
                        query.text.toString(),
                        placeholder = if (withLive) "Titles of shows, movies, episodes or Live TV programmes" else "Titles of shows, movies or episodes",
                        active = true,
                        dim = listening,
                    )
                }
                if (voice.available) MicKey(listening, pressMic)
            }
            voiceLine(voice.state)?.let { line -> VoiceLine(line, listening) }
            OnScreenKeyboard(
                field = query,
                modifier = Modifier.alpha(if (listening) 0.4f else 1f),
                onDone = vm::clear,
                doneLabel = "Clear",
                firstKey = firstKey,
                note = (if (voice.available) "" else "No microphone key: this TV doesn’t offer speech recognition to apps. ") +
                    "Accents and other alphabets come from the TV’s keyboard. Back or its Done returns here. Every field that uses this keyboard gets the same key, passwords included.",
                opener = tvKeyboard,
            )
        }
        if (!listening) KeyHints(voice.available, Modifier.align(Alignment.BottomStart).padding(start = ContentStart, bottom = 40.dp))
        Box(Modifier.fillMaxSize().padding(start = ResultsStart - ContentStart)) {
            val results = state.results
            when {
                state.error != null -> ErrorState(state.error!!, vm::retry, title = "Search didn't work")
                state.query.isBlank() -> Hint("Find anything in your library", "Type part of a title. Shows, movies and episodes appear here as you type.")
                results == null -> LoadingState()
                results.isEmpty() && state.requestable.isEmpty() && (liveRow == null || live.results.isEmpty()) ->
                    Hint("Nothing matches", "No titles in your library match “${state.resultsFor}”.")
                else -> Results(results, state.requestable, services.library, memory, onOpenShow, onOpenMovie, onPlay, onAsk = { asking = it }, viaSeerr = state.options.viaSeerr, onOpenEpisode = onOpenEpisode, liveRow = liveRow)
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
        InitialFocus(firstKey)
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
        // The list starts right of the keyboard, so ▶ from the last key of any of its rows comes here (ResultsInset).
        LazyColumn(Modifier.fillMaxSize().padding(start = ContentStart - ResultsInset), state = rememberLazyListState(), contentPadding = PaddingValues(top = 60.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
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
            if (results.episodes.isNotEmpty()) {
                item("episodes") {
                    Column {
                        SectionHeader("Episodes", small = "${results.episodes.size}", start = ResultsInset)
                        CardRow(start = ResultsInset) {
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
        SectionHeader(title, small = "${items.size}", start = ResultsInset)
        CardRow(start = ResultsInset) {
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

/**
 * The microphone beside Search's box: "Voice typing", or gold and ringed
 * with "Stop listening" while it listens (`.mic.live`).
 */
@Composable
private fun MicKey(listening: Boolean, onPress: () -> Unit) {
    FocusableBox(
        onClick = onPress,
        cornerRadius = 36.dp,
        focusedScale = 1.06f,
        modifier = Modifier.size(72.dp).semantics { contentDescription = if (listening) "Stop listening" else "Voice typing" },
    ) { focused ->
        Box(
            Modifier
                .fillMaxSize()
                .then(if (listening) Modifier.drawBehind { drawRings() } else Modifier)
                .background(
                    when {
                        listening -> WmmColors.Accent
                        focused -> WmmColors.Surface3
                        else -> WmmColors.Surface2
                    },
                    CircleShape,
                )
                .border(1.dp, if (listening) WmmColors.Accent else WmmColors.Line2, CircleShape),
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
private fun VoiceLine(text: String, listening: Boolean) {
    Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (listening) Box(Modifier.size(12.dp).background(WmmColors.Accent, CircleShape))
        Text(text, style = inter(20.sp, lineHeight = 28.sp), color = if (listening) WmmColors.Accent else WmmColors.TextMuted)
    }
}

/**
 * The key hints at the foot of the keyboard, while typing: one a line, and
 * no wider than the keyboard. On one line, the two a TV with a microphone
 * gets ran out across the results, and a third row of results (On Live TV)
 * drew over them.
 */
@Composable
private fun KeyHints(mic: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.width(KeyboardWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mic) KeyHint("▲", "From the top row: the search box, and ▶ from there the microphone")
        KeyHint("▼", "From the bottom row: the TV’s keyboard")
    }
}

@Composable
private fun KeyHint(key: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        KeyCap(key)
        Text(text, style = inter(19.sp), color = WmmColors.TextSoft)
    }
}

@Composable
private fun KeyCap(key: String) {
    Box(
        Modifier.height(34.dp).border(2.dp, WmmColors.Text.copy(alpha = 0.32f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(key, style = inter(16.sp, FontWeight.Bold), color = WmmColors.Text)
    }
}
