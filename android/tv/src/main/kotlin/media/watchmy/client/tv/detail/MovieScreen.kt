package media.watchmy.client.tv.detail

import media.watchmy.client.model.pictureAndSound
import media.watchmy.client.presentation.servers.OtherServers
import media.watchmy.client.model.at
import media.watchmy.client.model.watchedLine
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.MediaVersion
import media.watchmy.client.model.MovieDetail
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackPlan
import media.watchmy.client.model.ProblemKind
import media.watchmy.client.model.Trailer
import media.watchmy.client.model.TrailerText
import media.watchmy.client.model.formatDuration
import media.watchmy.client.model.formatRating
import media.watchmy.client.model.playRequest
import media.watchmy.client.model.versionTitle
import media.watchmy.client.model.watchToggleLabel
import media.watchmy.client.model.watchlistTitle
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.detail.MovieViewModel
import media.watchmy.client.presentation.detail.TrailerPlay
import media.watchmy.client.presentation.value
import media.watchmy.client.tv.components.ContentEnd
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.ErrorState
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.KeepInViewSpec
import media.watchmy.client.tv.components.LoadingState
import media.watchmy.client.tv.components.Notice
import media.watchmy.client.tv.components.OptionsDialog
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvClock
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.bringWholeIntoView
import media.watchmy.client.tv.components.endsAt
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.overflowHeight
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.tv.components.rememberNow
import media.watchmy.client.tv.components.wouldEndAt
import media.watchmy.client.tv.watchlists.WatchlistButtonTv
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.ReportShown
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat

/** Which of the movie's panels is open: More, or one it opened. */
private enum class MoviePanel { None, More, Details, Tracks, Report, Copies }

/** A page opened in place on a server no one is signed in to here any more. */
internal const val SIGNED_OUT_THERE = "You’re no longer signed in to that server. Sign in to it again from Servers."

/**
 * A movie, read from the couch: its title, the facts a viewer asks first
 * (year, rating, how long, and when it would end), three lines about it,
 * then Resume or Play, From the start, Trailer, Watchlist and More. The cast
 * is in sight under the buttons as a row of people; the collection and More
 * like this follow. Technical detail is one press away: More holds the
 * version, Audio & subtitles, Mark watched, File details (each version, how
 * it plays here, and what is in its file, OK playing it) and Report a
 * problem. A trailer file plays in the player; a YouTube trailer plays in
 * the app's trailer screen through [onOpenTrailer], or the YouTube app where
 * this TV has no usable WebView. [onNavigate] goes to a rail section.
 *
 * Keys: focus starts on Resume (or Play). ▼ from the buttons reaches the
 * cast, ▲ from the cast the button last focused, ▼ again the collection;
 * Back leaves the movie, or closes the panel open (File details and Audio &
 * subtitles go back to More first).
 */
@Composable
fun MovieScreen(
    movieId: Long,
    onPlay: (PlayRequest) -> Unit,
    onBack: () -> Unit,
    onNavigate: (Any) -> Unit = {},
    onOpenMovie: (Long) -> Unit = {},
    onOpenShow: (Long) -> Unit = {},
    onOpenTrailer: (TrailerPlay.YouTube) -> Unit = {},
    onOpenPerson: (media.watchmy.client.model.Person, from: String) -> Unit = { _, _ -> },
    /** The registered server the movie is on, opened in place; null is the one in use. */
    serverId: String? = null,
) {
    val services = LocalAppServices.current
    val context = LocalContext.current
    // YouTube's player in a WebView here, else the YouTube app.
    var inApp by remember { mutableStateOf(TrailerWebViews.installed()) }
    val youTubeApp = remember(context) { context.canOpenYouTube() }
    // Another server's page: its calls go there, as the person signed in there. Signed out there since, the page says so.
    val library = remember(serverId) { services.library.at(serverId) } ?: return TvScaffold(RailSection.Movies, onNavigate, sectionRoot = false) {
        ErrorState(SIGNED_OUT_THERE, onBack, title = "Couldn't load this movie")
    }
    val vm = viewModel(key = "movie-$serverId-$movieId") {
        MovieViewModel(
            library, movieId, services.requests, youTube = inApp || youTubeApp, plans = services.playback::plans, watchlists = services.watchlists,
            here = serverId, others = OtherServers(services.servers, services.library), choices = services.copyChoices, plansOn = services.playback::plansOn,
        )
    }
    ReportShown(vm::setShown)
    val state by vm.state.collectAsStateWithLifecycle()
    val memory = rememberFocusMemory()
    var panel by rememberSaveable { mutableStateOf(MoviePanel.None) }
    // The row of More that opened the panel showing, so Back from it lands there.
    var moreFocus by rememberSaveable { mutableStateOf<String?>(null) }
    // Audio & subtitles, for plays of the main version from this page.
    var audio by rememberSaveable { mutableStateOf<Int?>(null) }
    var subtitles by rememberSaveable { mutableStateOf(SUBTITLES_AUTO) }
    val onTrailer: () -> Unit = {
        when (val play = vm.trailerPlay()) {
            is TrailerPlay.File -> onPlay(play.request)
            is TrailerPlay.YouTube -> if (inApp && TrailerWebViews.usable(context)) {
                onOpenTrailer(play)
            } else {
                inApp = false
                val first = play.trailers.firstOrNull()
                if (first != null && !context.openYouTube(first.watchUrl)) vm.say(TrailerText.COULD_NOT_OPEN)
            }
            null -> Unit
        }
    }

    TvScaffold(RailSection.Movies, onNavigate, sectionRoot = false) {
        when (val s = state.movie) {
            Load.Loading -> LoadingState()
            is Load.Failed -> ErrorState(s.message, vm::retry, title = "Couldn't load this movie")
            is Load.Ready -> {
                val movie = s.data
                val main = movie.versions.firstOrNull()
                MovieContent(
                    movie,
                    library,
                    memory,
                    play = { startMs -> (if (startMs == 0L) vm.playRequest(fromStart = true) else vm.playRequest())?.withTracks(audio, subtitles)?.let(onPlay) },
                    onMore = {
                        moreFocus = null
                        panel = MoviePanel.More
                    },
                    trailer = state.trailer,
                    trailerInApp = inApp,
                    onTrailer = onTrailer,
                    hasFile = main != null,
                    onOpenPerson = { p -> onOpenPerson(p, movie.title) },
                    copies = state,
                    onChooser = { panel = MoviePanel.Copies },
                ) {
                    RelatedRows(state.related, library, memory, onOpenMovie, onOpenShow, Modifier.padding(bottom = 60.dp))
                }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    if (!memory.restore(fallback = "play")) MovieButtons.firstOrNull { memory.focus(it) }
                }
            }
        }
        TvClock()
        Notice(state.message, vm::messageShown)
    }

    val movie = state.movie.value ?: return
    val main = movie.versions.firstOrNull()
    fun backToMore(from: String) {
        moreFocus = from
        panel = MoviePanel.More
    }
    when (panel) {
        MoviePanel.None -> Unit
        MoviePanel.More -> TitleMorePanel(
            context = movie.title,
            rows = movieMoreRows(
                movie,
                main,
                state.plans,
                watched = state.watched,
                canReport = state.canReport,
                tracks = main?.let { tracksLine(it, audio, subtitles) },
                open = { key, next ->
                    moreFocus = key
                    // On several servers, Version is the chooser grouped by server.
                    panel = if (key == "version" && state.copies.several) MoviePanel.Copies else next
                },
                onWatched = { vm.setWatched(!state.watched) },
            ),
            focusKey = moreFocus,
            onDismiss = { panel = MoviePanel.None },
        )
        MoviePanel.Details -> FileDetailsPanel(
            context = movie.title,
            versions = movie.versions,
            plans = state.plans,
            onPlay = { v ->
                panel = MoviePanel.None
                val request = movie.playRequest(startMs = null, version = v)?.copy(serverId = serverId)
                (if (v == main) request?.withTracks(audio, subtitles) else request)?.let(onPlay)
            },
            onDismiss = { backToMore(moreFocus ?: "details") },
        )
        MoviePanel.Tracks -> if (main != null) {
            TracksPanel(movie.title, main, audio, subtitles, onAudio = { audio = it }, onSubtitles = { subtitles = it }, onDismiss = { backToMore("tracks") })
        }
        MoviePanel.Copies -> CopiesPanel(
            movie.title,
            state.copies,
            note = state.copies.resumeNote(state.copies.all.firstOrNull()?.registeredId),
            onChoose = { copy, v ->
                vm.choose(copy, v)
                panel = MoviePanel.None
                memory.focus("play")
            },
            onDismiss = { panel = MoviePanel.None },
        )
        MoviePanel.Report -> OptionsDialog(
            title = "Report a problem",
            options = ProblemKind.entries,
            selected = null,
            label = { it.label },
            kicker = movie.title,
            onSelect = {
                panel = MoviePanel.None
                vm.report(it)
            },
            onDismiss = { backToMore("report") },
        )
    }
}

/** More's rows for a movie: the version Play uses, Audio & subtitles, Mark watched, File details, Report a problem. */
private fun movieMoreRows(
    movie: MovieDetail,
    main: MediaVersion?,
    plans: Map<Long, PlaybackPlan>,
    watched: Boolean,
    canReport: Boolean,
    tracks: String?,
    open: (String, MoviePanel) -> Unit,
    onWatched: () -> Unit,
): List<MoreRow> = buildList {
    if (main != null && movie.versions.size > 1) {
        val how = plans[main.fileId]?.let(::planShort)
        add(MoreRow("version", "Version", { open("version", MoviePanel.Details) }, listOfNotNull(versionTitle(main), "the one Play uses", how).joinToString(", "), opens = true))
    }
    if (main != null && (main.audio.isNotEmpty() || main.subtitles.isNotEmpty()) && tracks != null) {
        add(MoreRow("tracks", "Audio & subtitles", { open("tracks", MoviePanel.Tracks) }, tracks, opens = true))
    }
    add(MoreRow("watched", watchToggleLabel(watched), onWatched))
    if (main != null) add(MoreRow("details", "File details", { open("details", MoviePanel.Details) }, "What the file is, and how it will play here", opens = true))
    if (canReport) add(MoreRow("report", "Report a problem", { open("report", MoviePanel.Report) }))
}

/** How long the movie runs: its runtime, else its file's length. */
private val MovieDetail.runtimeMs: Long?
    get() = runtimeMin?.takeIf { it > 0 }?.let { it * 60_000L } ?: versions.firstOrNull()?.durationMs?.takeIf { it > 0 }

/**
 * The facts a viewer asks first, in that order: year, rating, how long,
 * genre, score. The picture and sound are a line of words under the
 * buttons ([pictureAndSound]), never tags.
 */
private fun MovieDetail.viewerFacts(): List<Fact> = buildList {
    year?.let { add(Fact.Words("$it")) }
    contentRating?.takeIf { it.isNotBlank() }?.let { add(Fact.Tag(it)) }
    runtimeMs?.let { add(Fact.Words(formatDuration(it), bold = true)) }
    genres.firstOrNull()?.let { add(Fact.Words(it)) }
    formatRating(rating)?.let { add(Fact.Words(it)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MovieContent(
    movie: MovieDetail,
    library: LibraryRepository,
    memory: FocusMemory,
    play: (startMs: Long?) -> Unit,
    onMore: () -> Unit,
    trailer: Trailer?,
    trailerInApp: Boolean,
    onTrailer: () -> Unit,
    hasFile: Boolean,
    onOpenPerson: (media.watchmy.client.model.Person) -> Unit,
    copies: MovieViewModel.State,
    onChooser: () -> Unit,
    related: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    // The compact heading takes the top of the screen once the page has scrolled: rows stay under it.
    val spec = remember(density) { with(density) { KeepInViewSpec(leading = 150.dp.toPx(), trailing = 60.dp.toPx()) } }
    val scroll = rememberScrollState()
    // The button focus was last on, where ▲ from the cast goes back to.
    val lastButton = remember { mutableStateOf("play") }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Box(Modifier.fillMaxWidth().bringWholeIntoView()) {
                    PageBackdrop(library.imageUrl(movie.backdropPath, ImageSize.W1280), movie.title, Modifier.fillMaxWidth().overflowHeight(1080.dp, reported = 0.dp))
                    Column(Modifier.padding(top = 150.dp, bottom = 36.dp)) {
                        Header(movie, memory, { lastButton.value = it }, play, onMore, trailer, trailerInApp, onTrailer, hasFile, copies, onChooser, Modifier.padding(start = ContentStart, end = ContentEnd).heightIn(min = 502.dp))
                        CastRow(movie.cast, library, memory, Modifier.padding(top = 18.dp).upTo(memory) { listOf(lastButton.value) + MovieButtons }, onOpenPerson = onOpenPerson)
                    }
                }
                related()
            }
            ScrolledHeading(scroll, back = movie.title) {
                Text(movie.title, style = TvType.Heading, color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                val facts = listOfNotNull(movie.year?.toString(), movie.contentRating, movie.runtimeMs?.let(::formatDuration)).joinToString(" · ")
                if (facts.isNotEmpty()) Text(facts, style = TvType.Second, color = TvPalette.Muted, maxLines = 1)
            }
        }
    }
}

/** The movie's buttons' focus keys, in order. */
private val MovieButtons = listOf("play", "copy", "start", "trailer", "watchlist", "more")

/** The movie's title, facts, when it would end, what it is about and its buttons. */
@Composable
private fun Header(
    movie: MovieDetail,
    memory: FocusMemory,
    onButtonFocused: (String) -> Unit,
    play: (startMs: Long?) -> Unit,
    onMore: () -> Unit,
    trailer: Trailer?,
    trailerInApp: Boolean,
    onTrailer: () -> Unit,
    hasFile: Boolean,
    state: MovieViewModel.State,
    onChooser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now by rememberNow()
    val format = rememberLiveClockFormat()
    val copies = state.copies
    // With several servers, the place is the latest on any of them.
    val further = state.furtherElsewhere
    val partway = further?.place ?: movie.partway
    val runtime = movie.runtimeMs
    Column(modifier.widthIn(max = 1500.dp)) {
        Text(movie.title, style = TvType.Display, color = WmmColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        FactsLine(movie.viewerFacts(), Modifier.padding(top = 18.dp))
        // Where the title is, when it matters: opened in place from another server, or on others too.
        val where = when {
            state.elsewhere -> listOfNotNull("On ${media.watchmy.client.model.serverHeading(state.hereName.orEmpty())}", "away from home".takeIf { state.hereAway }).joinToString(" · ")
            copies.several -> "Also on " + media.watchmy.client.model.serverList(copies.all.drop(1).map { it.serverName }) + (if (further != null) " · you got further there" else "")
            else -> null
        }
        if (where != null) Text(where, style = TvType.Second, color = WmmColors.TextSoft, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
        if (partway != null) {
            Meter(
                partway.positionMs.toFloat() / partway.durationMs,
                Modifier.padding(top = 26.dp),
                spoken = "${formatDuration(partway.positionMs)} of ${formatDuration(partway.durationMs)} watched",
            )
        }
        val whenLine = Modifier.padding(top = 14.dp)
        when {
            !hasFile -> Text("No file for this movie is in the library.", style = TvType.Body, color = WmmColors.Warn, modifier = whenLine)
            partway != null -> WhenLine("${formatDuration(partway.positionMs)} in" + (further?.let { ", on ${it.shortName}" } ?: ""), " · ${endsAt(partway.durationMs - partway.positionMs, now, format)}", whenLine)
            movie.watch?.completed == true -> WhenLine(movie.watch?.watchedLine() ?: "Watched", runtime?.let { " · ${formatDuration(it)} · ${wouldEndAt(it, now, format)} if you started now" }, whenLine)
            runtime != null -> WhenLine(formatDuration(runtime), " · ${wouldEndAt(runtime, now, format)} if you started now", whenLine)
        }
        Overview(movie.overview, lines = 3, Modifier.padding(top = 22.dp))
        Spacer(Modifier.height(38.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            fun Modifier.button(key: String) = focusMemory(memory, key).onFocused { onButtonFocused(key) }
            val first = TransformOrigin(0f, 0.5f)
            if (hasFile) {
                val playLabel = copies.resumeLabel(copies.all.firstOrNull()?.registeredId) ?: movie.mainLabel()
                TvButton(playLabel, { play(null) }, Modifier.button("play"), style = TvButtonStyle.Primary, icon = WmmIcons.Play, growFrom = first)
                // On two servers or more: the copy Play uses, which opens the versions grouped by server.
                copies.buttonLabel()?.takeIf { copies.several }?.let { label -> TvButton(label, onChooser, Modifier.button("copy"), icon = WmmIcons.ChevronDown) }
                if (partway != null) TvButton("From the start", { play(0) }, Modifier.button("start"))
            }
            if (trailer != null) TrailerButton(handOff = trailer is Trailer.YouTube && !trailerInApp, onTrailer, Modifier.button("trailer"))
            WatchlistButtonTv(movie.watchlistTitle(), movie.watchlists, Modifier.button("watchlist"))
            TvButton("More", onMore, Modifier.button("more"))
        }
        // "In 4K with Dolby Vision and 7.1 sound": one quiet line under the buttons, below Trailer's
        // "Opens the YouTube app" when that shows; the formats are in More › File details.
        if (hasFile) movie.pictureAndSound()?.let { line ->
            val handOff = trailer is Trailer.YouTube && !trailerInApp
            Text(line, style = TvType.Second, color = TvPalette.Muted, maxLines = 1, modifier = Modifier.padding(top = if (handOff) 58.dp else 18.dp))
        }
    }
}

/**
 * Trailer, with "Opens the YouTube app" under it for a YouTube trailer
 * handed to the YouTube app ([handOff]); one that plays in the app has no
 * line, as a file has none. The line takes no room in the row, so the
 * buttons stay where they are.
 */
@Composable
private fun TrailerButton(handOff: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box {
        TvButton(TrailerText.BUTTON, onClick, modifier, icon = WmmIcons.Clapper)
        if (handOff) {
            Text(
                TrailerText.OPENS_YOUTUBE,
                style = TvType.Caption,
                color = TvPalette.Muted,
                maxLines = 1,
                modifier = Modifier.layout { measurable, _ ->
                    val line = measurable.measure(Constraints())
                    layout(0, 0) { line.place(0, (72.dp + 14.dp).roundToPx()) }
                },
            )
        }
    }
}
