package media.watchmy.client.tv.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import media.watchmy.client.model.TrackLine
import media.watchmy.client.model.Trailer
import media.watchmy.client.model.TrailerText
import media.watchmy.client.model.formatDuration
import media.watchmy.client.model.formatRating
import media.watchmy.client.model.playRequest
import media.watchmy.client.model.tags
import media.watchmy.client.model.versionTags
import media.watchmy.client.model.versionTitle
import media.watchmy.client.model.versionTracks
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.detail.MovieViewModel
import media.watchmy.client.presentation.detail.TrailerPlay
import media.watchmy.client.presentation.value
import media.watchmy.client.tv.components.Backdrop
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
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.TvTag
import media.watchmy.client.tv.components.bringWholeIntoView
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/**
 * A movie (mockup: design/mockups/android-tv/Movie.dc.html): the header
 * with Resume or Play, Play from start, Trailer (MovieTrailer.dc.html),
 * Mark watched and, where the server takes reports, More (Report a problem,
 * as on the episode page), then its versions, each with Play this, beside
 * the default version's audio and the cast. [onNavigate] goes to a rail
 * section. A trailer file plays in the player; a YouTube trailer opens the
 * YouTube app, and without an app to open it there is no Trailer for it.
 */
@Composable
fun MovieScreen(movieId: Long, onPlay: (PlayRequest) -> Unit, onBack: () -> Unit, onNavigate: (Any) -> Unit = {}) {
    val services = LocalAppServices.current
    val context = LocalContext.current
    val youTube = remember(context) { context.canOpenYouTube() }
    val vm = viewModel(key = "movie-$movieId") { MovieViewModel(services.library, movieId, services.requests, youTube = youTube, plans = services.playback::plans) }
    val state by vm.state.collectAsStateWithLifecycle()
    val memory = rememberFocusMemory()
    var more by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    val onTrailer: () -> Unit = {
        when (val play = vm.trailerPlay()) {
            is TrailerPlay.File -> onPlay(play.request)
            is TrailerPlay.YouTube -> {
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
                MovieContent(
                    s.data,
                    state.plans,
                    services.library,
                    memory,
                    onPlay,
                    { vm.setWatched(!state.watched) },
                    onMore = if (state.canReport) ({ more = true }) else null,
                    trailer = state.trailer,
                    onTrailer = onTrailer,
                )
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    memory.restore(fallback = "play")
                }
            }
        }
        Notice(state.message, vm::messageShown)
    }

    val movie = state.movie.value ?: return
    if (more) {
        MorePanel(
            kicker = listOfNotNull("Movie", movie.year?.toString()).joinToString(" · "),
            title = movie.title,
            onReport = {
                more = false
                reporting = true
            },
            onReplace = null,
            onDelete = null,
            onDismiss = { more = false },
        )
    }
    if (reporting) {
        OptionsDialog(
            title = "Report a problem",
            options = ProblemKind.entries,
            selected = null,
            label = { it.label },
            onSelect = {
                reporting = false
                vm.report(it)
            },
            onDismiss = { reporting = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MovieContent(
    movie: MovieDetail,
    plans: Map<Long, PlaybackPlan>,
    library: LibraryRepository,
    memory: FocusMemory,
    onPlay: (PlayRequest) -> Unit,
    onToggleWatched: () -> Unit,
    onMore: (() -> Unit)?,
    trailer: Trailer?,
    onTrailer: () -> Unit,
) {
    val density = LocalDensity.current
    val spec = remember(density) { with(density) { KeepInViewSpec(leading = 140.dp.toPx(), trailing = 60.dp.toPx()) } }
    val main = movie.versions.firstOrNull()
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(500.dp).bringWholeIntoView()) {
                Backdrop(library.imageUrl(movie.backdropPath, ImageSize.W1280), movie.title, Modifier.fillMaxWidth().height(520.dp), glowX = 0.7f, glowY = 0.1f)
                DetailPoster(library.imageUrl(movie.posterPath, ImageSize.W342), movie.title, Modifier.offset(ContentStart, 96.dp))
                Column(
                    Modifier.padding(start = 434.dp, top = 96.dp, end = ContentEnd).widthIn(max = 1400.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Breadcrumb("Movies", movie.title)
                    DetailTitle(movie.title)
                    FactsLine(movie.facts()) {
                        val tags = movie.tags()
                        if (tags.isNotEmpty()) Text("·", style = inter(21.sp), color = WmmColors.TextFaint)
                        for (t in tags) TvTag(t)
                    }
                    Overview(movie.overview)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        val partway = movie.partway
                        movie.playRequest(startMs = null)?.let { req ->
                            TvButton(movie.mainLabel(), { onPlay(req) }, Modifier.focusMemory(memory, "play"), style = TvButtonStyle.Primary, size = TvButtonSize.Medium, icon = WmmIcons.Play)
                        }
                        if (partway != null) {
                            movie.playRequest(startMs = 0)?.let { req ->
                                TvButton("Play from start", { onPlay(req) }, Modifier.focusMemory(memory, "start"), size = TvButtonSize.Medium)
                            }
                        }
                        if (trailer != null) TrailerButton(youTube = trailer is Trailer.YouTube, onTrailer, Modifier.focusMemory(memory, "trailer"))
                        TvButton(
                            if (movie.watch?.completed == true) "Mark unwatched" else "Mark watched",
                            onToggleWatched,
                            Modifier.focusMemory(memory, if (main == null) "play" else "watched"),
                            size = TvButtonSize.Medium,
                        )
                        if (onMore != null) MoreButton(onMore, Modifier.focusMemory(memory, "more"), size = TvButtonSize.Medium.height, iconSize = 26.dp)
                        if (partway != null) {
                            Box(
                                Modifier
                                    .padding(start = 8.dp)
                                    .width(220.dp)
                                    .height(6.dp)
                                    .background(WmmColors.Line2, RoundedCornerShape(3.dp)),
                            ) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(partway.positionMs.toFloat() / partway.durationMs).background(WmmColors.Accent, RoundedCornerShape(3.dp)))
                            }
                        }
                    }
                }
            }

            Row(Modifier.padding(start = ContentStart, end = ContentEnd, bottom = 60.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                Column(Modifier.width(1080.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Versions", style = inter(24.sp, FontWeight.SemiBold), color = WmmColors.Text)
                    if (movie.versions.isEmpty()) {
                        Text("No file for this movie is in the library.", style = inter(19.sp), color = WmmColors.Warn)
                    }
                    movie.versions.forEachIndexed { i, v ->
                        VersionRow(
                            version = v,
                            plan = plans[v.fileId],
                            isDefault = i == 0,
                            onPlay = { movie.playRequest(startMs = null, version = v)?.let(onPlay) },
                            modifier = Modifier.focusMemory(memory, "version:${v.fileId}"),
                        )
                    }
                }
                Column(Modifier.width(572.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (main != null) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("Tracks", style = inter(24.sp, FontWeight.SemiBold), color = WmmColors.Text)
                            Text(" · ${versionTitle(main)}", style = inter(17.sp, FontWeight.Medium), color = WmmColors.TextMuted)
                        }
                        val tracks = versionTracks(main)
                        Column {
                            tracks.forEachIndexed { i, t -> TrackRow(t, last = i == tracks.lastIndex) }
                        }
                    }
                    if (movie.cast.isNotEmpty()) {
                        Text("Cast", style = inter(24.sp, FontWeight.SemiBold), color = WmmColors.Text, modifier = Modifier.padding(top = 14.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp), maxLines = 2) {
                            for (p in movie.cast.take(10)) CastMember(p, library.imageUrl(p.profilePath, ImageSize.W185))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Trailer, with "Opens the YouTube app" under it for a YouTube trailer. The
 * line takes no room in the row, so the buttons stay where they are.
 */
@Composable
private fun TrailerButton(youTube: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box {
        TvButton(TrailerText.BUTTON, onClick, modifier, size = TvButtonSize.Medium, icon = WmmIcons.Clapper)
        if (youTube) {
            Text(
                TrailerText.OPENS_YOUTUBE,
                style = inter(18.sp, FontWeight.Medium),
                color = WmmColors.TextMuted,
                maxLines = 1,
                modifier = Modifier.layout { measurable, _ ->
                    val line = measurable.measure(Constraints())
                    layout(0, 0) { line.place(0, (TvButtonSize.Medium.height + 16.dp).roundToPx()) }
                },
            )
        }
    }
}

private fun MovieDetail.facts(): List<String> = listOfNotNull(
    year?.toString(),
    runtimeMin?.let { formatDuration(it * 60_000L) },
    genres.take(2).joinToString(", ").ifEmpty { null },
    contentRating,
    formatRating(rating),
)

/** A version (`.ver`): its name, Default on the first, tags, the file name and how it would play, and Play this. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VersionRow(version: MediaVersion, plan: PlaybackPlan?, isDefault: Boolean, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(WmmColors.Surface, shape)
            .border(1.dp, WmmColors.Line, shape)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(versionTitle(version), style = inter(22.sp, FontWeight.Bold), color = WmmColors.Text)
                if (isDefault) TvTag("Default", gold = true)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in versionTags(version, spelledOut = true)) TvTag(t)
            }
            Text(
                listOfNotNull(version.label, version.recording?.label(media.watchmy.client.ui.components.rememberLiveClockFormat()), plan?.versionLine()).joinToString(" · "),
                style = inter(16.sp),
                color = WmmColors.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TvButton("Play this", onPlay, modifier, size = TvButtonSize.Small, icon = WmmIcons.Play)
    }
}

@Composable
private fun TrackRow(track: TrackLine, last: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { if (!last) drawLine(WmmColors.Line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(track.kind?.uppercase().orEmpty(), style = inter(16.sp, tracking = 0.06f), color = WmmColors.TextMuted, modifier = Modifier.width(110.dp))
        Text(track.text, style = inter(19.sp), color = WmmColors.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (track.default) TvTag("Default", gold = true)
        if (track.burnIn) Text("Burn-in required", style = inter(15.sp, FontWeight.SemiBold), color = WmmColors.Warn)
    }
}
