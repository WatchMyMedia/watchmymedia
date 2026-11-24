package media.watchmy.client.mobile.detail

import media.watchmy.client.presentation.detail.inChooserOrder
import media.watchmy.client.model.at
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.CardAction
import media.watchmy.client.mobile.components.ErrorState
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.LoadingState
import media.watchmy.client.mobile.components.MessageHost
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.mobile.components.panel
import media.watchmy.client.mobile.downloads.ItemDownload
import media.watchmy.client.mobile.requests.DownloadBar
import media.watchmy.client.mobile.requests.ReportProblemSheet
import media.watchmy.client.model.DownloadState
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.MediaVersion
import media.watchmy.client.model.MovieDetail
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlaybackPlan
import media.watchmy.client.model.TrailerText
import media.watchmy.client.model.watchlistTitle
import media.watchmy.client.mobile.watchlists.WatchlistButton
import media.watchmy.client.ui.LocalWatchlistControls
import media.watchmy.client.model.formatDuration
import media.watchmy.client.model.formatRating
import media.watchmy.client.model.formatRemaining
import media.watchmy.client.model.label
import media.watchmy.client.model.tags
import media.watchmy.client.model.versionTags
import media.watchmy.client.model.versionTitle
import media.watchmy.client.model.versionTracks
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.detail.MovieViewModel
import media.watchmy.client.presentation.detail.TrailerPlay
import media.watchmy.client.presentation.value
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.TabletLayout
import media.watchmy.client.ui.rememberTabletLayout
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.TestTags
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.ReportShown

/**
 * A movie: backdrop, poster and facts, Resume or Play, Mark
 * watched, Download (where the server allows it), and
 * each version with Play this. [onOpenSettings] is Settings › Downloads.
 * Trailer plays a trailer file through [onPlay], and
 * opens YouTube's trailers in the trailer screen through [onOpenTrailer].
 * The page ends with its related titles, whose cards
 * open their page through [onOpenMovie].
 */
@Composable
fun MovieScreen(
    movieId: Long,
    onPlay: (PlayRequest) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenTrailer: (TrailerPlay.YouTube) -> Unit = {},
    onOpenMovie: (Long) -> Unit = {},
    onOpenShow: (Long) -> Unit = {},
    /** The registered server the movie is on, opened in place; null is the one in use. */
    serverId: String? = null,
) {
    val layout = rememberTabletLayout()
    val services = LocalAppServices.current
    // Another server's page: its calls go there, as the person signed in there. Signed out there since, the page says so.
    val library = remember(serverId) { services.library.at(serverId) } ?: return Box(Modifier.fillMaxSize().background(WmmColors.Ground)) {
        ErrorState(SIGNED_OUT_THERE, onBack)
    }
    val vm = viewModel(key = "movie-$serverId-$movieId") {
        MovieViewModel(
            library, movieId, services.requests, if (serverId == null) services.downloads else media.watchmy.client.model.DownloadRepository.Unavailable,
            plans = services.playback::plans, watchlists = services.watchlists,
            here = serverId, others = media.watchmy.client.presentation.servers.OtherServers(services.servers, services.library),
            choices = services.copyChoices, plansOn = services.playback::plansOn,
        )
    }
    ReportShown(vm::setShown)
    val state by vm.state.collectAsStateWithLifecycle()
    val movie = state.movie.value
    var reporting by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val threshold = with(LocalDensity.current) { HeaderScroll.roundToPx() }
    val headerGone by remember { derivedStateOf { scroll.value > threshold } }
    val onTrailer = {
        when (val play = vm.trailerPlay()) {
            is TrailerPlay.File -> onPlay(play.request)
            is TrailerPlay.YouTube -> onOpenTrailer(play)
            null -> Unit
        }
    }

    // The rows at the page's end, the collection then More like this.
    val related: @Composable () -> Unit = { RelatedRows(state.related, onOpenMovie, onOpenShow, layout.isTablet, Modifier.padding(top = 24.dp)) }

    Box(Modifier.fillMaxSize().background(WmmColors.Ground)) {
        when {
            movie == null && state.movie is Load.Failed -> ErrorState((state.movie as Load.Failed).message, vm::retry)
            movie == null -> LoadingState()
            layout.isTablet -> TabletMovieContent(movie, state, vm, onPlay, scroll, layout.twoPane, onOpenSettings, onTrailer, related, library)
            else -> MovieContent(movie, state, vm, onPlay, scroll, onOpenSettings, onTrailer, related, library)
        }
        DetailBar(
            onBack = onBack,
            title = movie?.title,
            solid = headerGone,
            modifier = if (layout.twoPane) Modifier.width(TabletLayout.detailColumn) else Modifier,
            actions = if (movie == null) {
                emptyList()
            } else {
                listOfNotNull(
                    CardAction(if (state.watched) "Mark as unwatched" else "Mark as watched") { vm.setWatched(!state.watched) },
                    CardAction("Report a problem") { reporting = true }.takeIf { state.canReport },
                )
            },
        )
        MessageHost(state.message, vm::messageShown, Modifier.align(Alignment.BottomCenter).padding(WindowInsets.navigationBars.asPaddingValues()))
    }
    if (reporting && movie != null) {
        ReportProblemSheet(
            kicker = movie.title,
            episode = false,
            itemId = movie.id,
            fileId = movie.versions.firstOrNull()?.fileId,
            onSent = { message ->
                reporting = false
                vm.say(message)
            },
            onDismiss = { reporting = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MovieContent(
    movie: MovieDetail,
    state: MovieViewModel.State,
    vm: MovieViewModel,
    onPlay: (PlayRequest) -> Unit,
    scroll: ScrollState,
    onOpenSettings: () -> Unit,
    onTrailer: () -> Unit,
    related: @Composable () -> Unit,
    library: media.watchmy.client.model.LibraryRepository,
) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = bottom + 24.dp)) {
        DetailHeader(movie.title, library.imageUrl(movie.backdropPath, ImageSize.W1280), library.imageUrl(movie.posterPath, ImageSize.W342)) {
            DetailTitle(movie.title)
            DetailFacts(listOf(movieFacts(movie)))
            val tags = movie.tags()
            if (tags.isNotEmpty()) Tags(tags)
        }
        Overview(movie.overview, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        movie.download?.let { d ->
            // Radarr is fetching it, or an upgrade.
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val failed = d.state == DownloadState.Failed
                    Icon(if (failed) WmmIcons.Alert else WmmIcons.Download, contentDescription = null, tint = if (failed) WmmColors.Warn else WmmColors.Accent, modifier = Modifier.size(16.dp))
                    Text(d.label(), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (failed) WmmColors.Warn else WmmColors.Accent))
                }
                if (d.state == DownloadState.Downloading) DownloadBar(d.progress, Modifier.fillMaxWidth())
            }
        }

        MovieActions(movie, state, vm, onPlay, onOpenSettings, onTrailer)

        MovieExtras(movie, state, vm, onPlay)
        related()
    }
}

/**
 * Resume or Play, Play from start, Trailer, Watchlist and Mark watched, as
 * the page lays them out. With watchlists, Trailer pairs with Watchlist
 * under Play from start and Mark watched; without, Trailer sits beside Play
 * from start when partway, else beside Mark watched.
 */
@Composable
private fun MovieActions(movie: MovieDetail, state: MovieViewModel.State, vm: MovieViewModel, onPlay: (PlayRequest) -> Unit, onOpenSettings: () -> Unit, onTrailer: () -> Unit) {
    val default = movie.versions.firstOrNull()
    val tablet = rememberTabletLayout().isTablet
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WhereLine(state)
        // With several servers, Resume is from the latest place on any of them.
        val watch = state.furtherElsewhere?.place ?: movie.watch
        val markLabel = if (state.watched) "Mark unwatched" else "Mark watched"
        val hasTrailer = state.trailer != null
        val saves = LocalWatchlistControls.current != null && movie.watchlistTitle() != null && movie.watchlists != null && !state.offline
        val mark: @Composable (Modifier) -> Unit = { m -> TonalButton(markLabel, { vm.setWatched(!state.watched) }, m, busy = state.busy) }
        val save: @Composable (Modifier) -> Unit = { m -> WatchlistButton(movie.watchlistTitle(), movie.watchlists, m) }
        val resumable = state.resumable || state.furtherElsewhere != null
        if (saves) {
            if (resumable && watch != null) {
                ResumeButton(watch.durationMs - watch.positionMs, watch.positionMs.toFloat() / watch.durationMs) { vm.playRequest()?.let(onPlay) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TonalButton("Play from start", { vm.playRequest(fromStart = true)?.let(onPlay) }, Modifier.weight(1f))
                    mark(Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasTrailer) TrailerButton(onTrailer, Modifier.weight(1f))
                    save(Modifier.weight(1f))
                }
            } else {
                if (default != null) FilledButton("Play", { vm.playRequest()?.let(onPlay) }, large = true, icon = WmmIcons.Play, modifier = Modifier.fillMaxWidth().testTag(TestTags.PLAY))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasTrailer) TrailerButton(onTrailer, Modifier.weight(1f)) else mark(Modifier.weight(1f))
                    save(Modifier.weight(1f))
                }
                if (hasTrailer) mark(Modifier.fillMaxWidth())
            }
        } else if (resumable && watch != null) {
            ResumeButton(watch.durationMs - watch.positionMs, watch.positionMs.toFloat() / watch.durationMs) { vm.playRequest()?.let(onPlay) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalButton("Play from start", { vm.playRequest(fromStart = true)?.let(onPlay) }, Modifier.weight(1f))
                if (hasTrailer) TrailerButton(onTrailer, Modifier.weight(1f)) else TonalButton(markLabel, { vm.setWatched(!state.watched) }, Modifier.weight(1f), busy = state.busy)
            }
            if (hasTrailer) TonalButton(markLabel, { vm.setWatched(!state.watched) }, Modifier.fillMaxWidth(), busy = state.busy)
        } else {
            if (default != null) FilledButton("Play", { vm.playRequest()?.let(onPlay) }, large = true, icon = WmmIcons.Play, modifier = Modifier.fillMaxWidth().testTag(TestTags.PLAY))
            if (hasTrailer) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrailerButton(onTrailer, Modifier.weight(1f))
                    TonalButton(markLabel, { vm.setWatched(!state.watched) }, Modifier.weight(1f), busy = state.busy)
                }
            } else {
                TonalButton(markLabel, { vm.setWatched(!state.watched) }, Modifier.fillMaxWidth(), busy = state.busy)
            }
        }
        // Only the server in use's copy downloads: another server's is downloaded by switching to it.
        if (default != null && !state.elsewhere) {
            ItemDownload(
                fileId = default.fileId,
                kicker = movie.title,
                from = if (state.offline) "" else "From the ${versionTitle(default)} version",
                onPlayCopy = { file -> vm.playRequest(movie.versions.firstOrNull { it.fileId == file } ?: default)?.let(onPlay) },
                onOpenSettings = onOpenSettings,
                onMessage = vm::say,
                tablet = tablet,
                otherFiles = movie.versions.drop(1).map { it.fileId },
            )
        }
    }
}

@Composable
private fun MovieExtras(movie: MovieDetail, state: MovieViewModel.State, vm: MovieViewModel, onPlay: (PlayRequest) -> Unit, sideBySide: Boolean = false) {
    val default = movie.versions.firstOrNull()
    Column {
        if (movie.versions.isNotEmpty()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailHeading("Versions")
                // Side by side on a tablet's wide pane, two to a row; one under another on a phone.
                @Composable
                fun Versions(versions: List<MediaVersion>, plan: (MediaVersion) -> PlaybackPlan?, isDefault: (MediaVersion) -> Boolean, place: String?, play: (MediaVersion) -> Unit) {
                    if (sideBySide) versions.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { v -> VersionTile(v, plan(v), isDefault(v), place, Modifier.weight(1f)) { play(v) } }
                            if (pair.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        }
                    } else versions.forEach { v -> VersionCard(v, plan(v), isDefault = isDefault(v), place = place) { play(v) } }
                }
                if (state.copies.several) {
                    // Every server's copies, under their servers, each with where the person is there; Play this plays it and keeps it for Play.
                    val play = state.copies.play
                    state.copies.all.inChooserOrder().forEach { copy ->
                        Text(copy.heading, color = WmmColors.TextSoft, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        if (copy.away) Text(media.watchmy.client.model.AWAY_FROM_HOME_NOTE, color = WmmColors.TextMuted, fontFamily = Inter, fontSize = 12.sp)
                        Versions(
                            copy.versions,
                            plan = { v -> copy.plans[v.fileId] ?: state.plans[v.fileId].takeIf { copy.serverId == state.copies.all.first().serverId } },
                            isDefault = { v -> play?.copy?.registeredId == copy.registeredId && play.version.fileId == v.fileId },
                            place = copy.placeWords(),
                        ) { v ->
                            vm.choose(copy, v)
                            vm.playCopy(copy, v)?.let(onPlay)
                        }
                    }
                    if (state.copies.asking.isNotEmpty()) Text(media.watchmy.client.model.stillAsking(state.copies.asking), color = WmmColors.TextMuted, fontFamily = Inter, fontSize = 12.sp)
                } else Versions(movie.versions, plan = { v -> state.plans[v.fileId] }, isDefault = { v -> v == default }, place = null) { v -> vm.playRequest(v)?.let(onPlay) }
            }
        }

        val tracks = default?.let { versionTracks(it) }.orEmpty()
        if (default != null && tracks.isNotEmpty()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DetailHeading("Tracks", small = versionTitle(default))
                tracks.forEachIndexed { i, t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(t.kind?.uppercase().orEmpty(), style = TextStyle(fontFamily = Inter, fontSize = 11.sp, letterSpacing = 0.66.sp, color = WmmColors.TextMuted), modifier = Modifier.width(72.dp))
                        Text(t.text, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.Text), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (t.default) Tag("Default", gold = true)
                        if (t.burnIn) Text("Burn-in required", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = WmmColors.Warn))
                    }
                    if (i < tracks.lastIndex) HorizontalDivider(color = WmmColors.Line)
                }
            }
        }

        Box(Modifier.padding(top = 24.dp)) { CastRow(movie.cast) }
    }
}

@Composable
private fun TabletMovieContent(
    movie: MovieDetail,
    state: MovieViewModel.State,
    vm: MovieViewModel,
    onPlay: (PlayRequest) -> Unit,
    scroll: ScrollState,
    twoPane: Boolean,
    onOpenSettings: () -> Unit,
    onTrailer: () -> Unit,
    related: @Composable () -> Unit,
    library: media.watchmy.client.model.LibraryRepository,
) {
    val details: @Composable () -> Unit = {
        DetailHeader(movie.title, library.imageUrl(movie.backdropPath, ImageSize.W1280), library.imageUrl(movie.posterPath, ImageSize.W342)) {
            DetailTitle(movie.title)
            DetailFacts(listOf(movieFacts(movie)))
            if (movie.tags().isNotEmpty()) Tags(movie.tags())
        }
        MovieActions(movie, state, vm, onPlay, onOpenSettings, onTrailer)
        Overview(movie.overview, Modifier.padding(24.dp), collapsedLines = 6)
    }
    if (twoPane) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(TabletLayout.detailColumn).verticalScroll(scroll).padding(bottom = 24.dp)) { details() }
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp, bottom = 24.dp)) {
                item { MovieExtras(movie, state, vm, onPlay, sideBySide = true) }
                // In the right pane, under the cast.
                item { related() }
            }
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = TabletLayout.singleColumnMax).fillMaxWidth().verticalScroll(scroll).padding(bottom = 24.dp)) {
                details()
                MovieExtras(movie, state, vm, onPlay)
                related()
            }
        }
    }
}

/** Trailer, with the film strip, whichever kind the trailer is. */
@Composable
private fun TrailerButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TonalButton(TrailerText.BUTTON, onClick, modifier, icon = WmmIcons.Movie)
}

/** Resume with the gold progress line along the bottom of the pill. */
@Composable
private fun ResumeButton(remainingMs: Long, progress: Float, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(CircleShape)) {
        FilledButton("Resume · ${formatRemaining(remainingMs)}", onClick, large = true, icon = WmmIcons.Play, modifier = Modifier.fillMaxWidth().testTag(TestTags.PLAY))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.35f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(WmmColors.Accent))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
/** A version as a tile on a tablet's wide pane: its title, Default (or Play uses this), its tags, how it plays or where the person is, and Play this. */
@Composable
private fun VersionTile(version: MediaVersion, plan: PlaybackPlan?, isDefault: Boolean, place: String?, modifier: Modifier = Modifier, onPlay: () -> Unit) {
    Column(modifier.panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(versionTitle(version), color = WmmColors.Text, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (isDefault) Tag(if (place != null) "Play uses this" else "Default", gold = true)
        Text(versionTags(version).joinToString(" · "), color = WmmColors.TextSoft, fontFamily = Inter, fontSize = 12.sp)
        if (place != null) Text(place, color = WmmColors.Text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(plan?.let(::planLine) ?: version.label, color = WmmColors.TextMuted, fontFamily = Inter, fontSize = 12.sp)
        TonalButton("Play this", onPlay, Modifier.fillMaxWidth())
    }
}

@Composable
private fun VersionCard(version: MediaVersion, plan: PlaybackPlan?, isDefault: Boolean, place: String? = null, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().panel().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(versionTitle(version), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = WmmColors.Text))
                if (isDefault) Tag(if (place != null) "Play uses this" else "Default", gold = true)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                versionTags(version).forEach { Tag(it) }
            }
            if (place != null) Text(place, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = WmmColors.TextSoft))
            version.recording?.let { Text(it.label(media.watchmy.client.ui.components.rememberLiveClockFormat()), color = WmmColors.TextMuted, fontSize = 12.sp) }
            Text(plan?.let(::planLine) ?: version.label, style = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = WmmColors.TextFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GoldTextButton("Play this", onPlay)
    }
}

/** "Direct play", or "Remux to fMP4 · audio to AAC 5.1 on this phone": how the version would play here. */
internal fun planLine(plan: PlaybackPlan): String = if (plan.mode == PlaybackMode.Direct) plan.label else "${plan.label} on this phone"

/** "2026 · 2 h 14 min · Thriller · R · ★ 7.6". */
internal fun movieFacts(movie: MovieDetail): String = listOfNotNull(
    movie.year?.toString(),
    movie.runtimeMin?.let { formatDuration(it * 60_000L) },
    movie.genres.take(2).joinToString(", ").ifEmpty { null },
    movie.contentRating,
    formatRating(movie.rating),
).joinToString(" · ")


/** Where the person is with a copy, as the Versions section says it: "52 min left", "Watched", "Not started". */
private fun media.watchmy.client.presentation.detail.ServerCopy.placeWords(): String = when {
    watch?.completed == true -> "Watched"
    place != null -> media.watchmy.client.model.formatRemaining(place!!.durationMs - place!!.positionMs)
    else -> "Not started"
}

/** A page opened in place on a server no one is signed in to here any more. */
internal const val SIGNED_OUT_THERE = "You’re no longer signed in to that server. Sign in to it again from Servers."

/**
 * Where the movie is, when it matters: "On Cabin server · away from home" on
 * a page opened in place, "Also on Cabin" when other servers have it too.
 */
@Composable
internal fun WhereLine(state: MovieViewModel.State) {
    val copies = state.copies
    val text = when {
        state.elsewhere -> listOfNotNull("On ${media.watchmy.client.model.serverHeading(state.hereName.orEmpty())}", "away from home".takeIf { state.hereAway }).joinToString(" · ")
        copies.several -> "Also on " + media.watchmy.client.model.serverList(copies.all.drop(1).map { it.serverName }) + (if (state.furtherElsewhere != null) " · you got further there" else "")
        else -> null
    } ?: return
    Text(text, color = WmmColors.TextSoft, fontFamily = Inter, fontSize = 13.sp)
}
