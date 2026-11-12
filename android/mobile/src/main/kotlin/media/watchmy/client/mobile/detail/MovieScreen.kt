package media.watchmy.client.mobile.detail

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
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/**
 * A movie (Movie.dc.html): backdrop, poster and facts, Resume or Play, Mark
 * watched, Download (MovieDownload.dc.html, where the server allows it), and
 * each version with Play this. [onOpenSettings] is Settings › Downloads.
 * Trailer (MovieTrailer.dc.html) plays a trailer file through [onPlay], and
 * opens YouTube's trailers in the trailer screen through [onOpenTrailer].
 */
@Composable
fun MovieScreen(
    movieId: Long,
    onPlay: (PlayRequest) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenTrailer: (TrailerPlay.YouTube) -> Unit = {},
) {
    val layout = rememberTabletLayout()
    val services = LocalAppServices.current
    val vm = viewModel { MovieViewModel(services.library, movieId, services.requests, services.downloads, plans = services.playback::plans) }
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

    Box(Modifier.fillMaxSize().background(WmmColors.Ground)) {
        when {
            movie == null && state.movie is Load.Failed -> ErrorState((state.movie as Load.Failed).message, vm::retry)
            movie == null -> LoadingState()
            layout.isTablet -> TabletMovieContent(movie, state, vm, onPlay, scroll, layout.twoPane, onOpenSettings, onTrailer)
            else -> MovieContent(movie, state, vm, onPlay, scroll, onOpenSettings, onTrailer)
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
private fun MovieContent(movie: MovieDetail, state: MovieViewModel.State, vm: MovieViewModel, onPlay: (PlayRequest) -> Unit, scroll: ScrollState, onOpenSettings: () -> Unit, onTrailer: () -> Unit) {
    val library = LocalAppServices.current.library
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
            // Radarr is fetching it, or an upgrade (PLAN-ARR.md §6).
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
    }
}

/**
 * Resume or Play, Play from start, Trailer and Mark watched, as
 * MovieTrailer.dc.html lays them out: Trailer beside Play from start when
 * partway, else beside Mark watched. Without a trailer, as Movie.dc.html.
 */
@Composable
private fun MovieActions(movie: MovieDetail, state: MovieViewModel.State, vm: MovieViewModel, onPlay: (PlayRequest) -> Unit, onOpenSettings: () -> Unit, onTrailer: () -> Unit) {
    val default = movie.versions.firstOrNull()
    val tablet = rememberTabletLayout().isTablet
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val watch = movie.watch
        val markLabel = if (state.watched) "Mark unwatched" else "Mark watched"
        val hasTrailer = state.trailer != null
        if (state.resumable && watch != null) {
            ResumeButton(watch.durationMs - watch.positionMs, watch.positionMs.toFloat() / watch.durationMs) { vm.playRequest()?.let(onPlay) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalButton("Play from start", { vm.playRequest(fromStart = true)?.let(onPlay) }, Modifier.weight(1f))
                if (hasTrailer) TrailerButton(onTrailer, Modifier.weight(1f)) else TonalButton(markLabel, { vm.setWatched(!state.watched) }, Modifier.weight(1f), busy = state.busy)
            }
            if (hasTrailer) TonalButton(markLabel, { vm.setWatched(!state.watched) }, Modifier.fillMaxWidth(), busy = state.busy)
        } else {
            if (default != null) FilledButton("Play", { vm.playRequest()?.let(onPlay) }, large = true, icon = WmmIcons.Play, modifier = Modifier.fillMaxWidth())
            if (hasTrailer) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrailerButton(onTrailer, Modifier.weight(1f))
                    TonalButton(markLabel, { vm.setWatched(!state.watched) }, Modifier.weight(1f), busy = state.busy)
                }
            } else {
                TonalButton(markLabel, { vm.setWatched(!state.watched) }, Modifier.fillMaxWidth(), busy = state.busy)
            }
        }
        if (default != null) {
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
                if (sideBySide) {
                    movie.versions.chunked(2).forEach { versions ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            versions.forEach { version ->
                                Column(Modifier.weight(1f).panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(versionTitle(version), color = WmmColors.Text, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    if (version == default) Tag("Default", gold = true)
                                    Text(versionTags(version).joinToString(" · "), color = WmmColors.TextSoft, fontFamily = Inter, fontSize = 12.sp)
                                    Text(state.plans[version.fileId]?.let(::planLine) ?: version.label, color = WmmColors.TextMuted, fontFamily = Inter, fontSize = 12.sp)
                                    TonalButton("Play this", { vm.playRequest(version)?.let(onPlay) }, Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                } else movie.versions.forEachIndexed { i, v -> VersionCard(v, state.plans[v.fileId], isDefault = i == 0) { vm.playRequest(v)?.let(onPlay) } }
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
) {
    val library = LocalAppServices.current.library
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
            }
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = TabletLayout.singleColumnMax).fillMaxWidth().verticalScroll(scroll).padding(bottom = 24.dp)) {
                details()
                MovieExtras(movie, state, vm, onPlay)
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
        FilledButton("Resume · ${formatRemaining(remainingMs)}", onClick, large = true, icon = WmmIcons.Play, modifier = Modifier.fillMaxWidth())
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.35f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(WmmColors.Accent))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VersionCard(version: MediaVersion, plan: PlaybackPlan?, isDefault: Boolean, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().panel().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(versionTitle(version), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = WmmColors.Text))
                if (isDefault) Tag("Default", gold = true)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                versionTags(version).forEach { Tag(it) }
            }
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
