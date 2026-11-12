package media.watchmy.client.tv.detail

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.createSavedStateHandle
import kotlinx.coroutines.launch
import media.watchmy.client.model.OnTopReason
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.model.WATCHED_IT_ALL_NOTE
import media.watchmy.client.model.markSeasonButtonLabel
import media.watchmy.client.model.monthDay
import media.watchmy.client.model.plural
import media.watchmy.client.model.seasonCountLine
import media.watchmy.client.model.nextToAir
import media.watchmy.client.model.seasonWatchLine
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.CANT_DELETE_NOTE
import media.watchmy.client.model.DELETE_HISTORY_NOTE
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.MissingEpisode
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.ProblemKind
import media.watchmy.client.model.REPLACE_TITLE
import media.watchmy.client.model.SeasonSummary
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.airedLabel
import media.watchmy.client.model.deleteEpisodeTitle
import media.watchmy.client.model.deleteEpisodeWhat
import media.watchmy.client.model.deleteWhere
import media.watchmy.client.model.episodeKicker
import media.watchmy.client.model.episodePlayLabel
import media.watchmy.client.model.episodeTags
import media.watchmy.client.model.formatBytes
import media.watchmy.client.model.formatRating
import media.watchmy.client.model.formatRemaining
import media.watchmy.client.model.markShowLabel
import media.watchmy.client.model.missingEpisodesLabel
import media.watchmy.client.model.otherEpisodesLine
import media.watchmy.client.model.partway
import media.watchmy.client.model.replaceBody
import media.watchmy.client.model.requestSeasonLabel
import media.watchmy.client.model.seasonName
import media.watchmy.client.model.subtitleLanguages
import media.watchmy.client.model.unreachableLines
import media.watchmy.client.model.watchToggleLabel
import media.watchmy.client.model.watched
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.detail.ShowPageActions
import media.watchmy.client.presentation.detail.ShowPageViewModel
import media.watchmy.client.tv.components.ArtBadge
import media.watchmy.client.tv.components.ArtProgress
import media.watchmy.client.tv.components.Backdrop
import media.watchmy.client.tv.components.ContentEnd
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.ErrorState
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.KeepInViewSpec
import media.watchmy.client.tv.components.LoadingState
import media.watchmy.client.tv.components.Notice
import media.watchmy.client.tv.components.OptionsDialog
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.tv.components.TextBright
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.TvTag
import media.watchmy.client.tv.components.bringWholeIntoView
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.overflowHeight
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.tv.requests.RequestDialog
import media.watchmy.client.tv.requests.TvDownloadBar
import media.watchmy.client.tv.requests.tvIcon
import media.watchmy.client.tv.requests.tvTone
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.WatchedCheck
import media.watchmy.client.ui.components.WmmIcons
import java.util.Locale

/**
 * One page for a show, its seasons and its episodes on TV
 * (android-tv/ShowPage.dc.html and ShowPageMissing.dc.html; PLAN-SHOW-PAGE.md
 * §4.3). The chosen episode is on top over the show's backdrop, its still on
 * the right, with Play focused first, then From the start, Mark watched and
 * More (Report a problem, Replace file, Delete episode…). ▼ reaches the
 * season tabs, where ◀ ▶ changes the season at once, with Mark season
 * watched at their end; ▼ again the episode row, where the episode on top
 * follows focus and OK moves focus back up to its Play (it never plays).
 * ▼ past the row scrolls to About the show: Mark show watched, Show missing
 * episodes, Request missing seasons and Cast & details. Back leaves the
 * show in one step; the delete confirmation opens on Cancel.
 */
@Composable
fun ShowPageScreen(
    showId: Long,
    arrival: ShowArrival,
    onPlay: (PlayRequest) -> Unit,
    onNavigate: (Any) -> Unit = {},
) {
    val services = LocalAppServices.current
    val vm = viewModel(key = "show-$showId") { ShowPageViewModel(services.library, showId, arrival, services.requests, saved = createSavedStateHandle()) }
    val state by vm.state.collectAsStateWithLifecycle()
    val memory = rememberFocusMemory()
    var more by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    // The request dialog: for one season, or every missing one (ALL_SEASONS); null when closed.
    var asking by rememberSaveable { mutableStateOf<Int?>(null) }
    var details by rememberSaveable { mutableStateOf(false) }
    val show = state.show
    val ep = state.episode

    TvScaffold(RailSection.Shows, onNavigate, sectionRoot = false) {
        when {
            (show == null || ep == null) && state.page is Load.Failed -> ErrorState((state.page as Load.Failed).message, vm::retry, title = "Couldn't load this show")
            show == null || ep == null -> LoadingState()
            else -> {
                PageContent(show, ep, state, vm, services.library, memory, onPlay, onMore = { more = true }, onAsk = { asking = it }, onDetails = { details = true })
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    // Play, or whatever the episode offers first; with nothing to press, the season tabs.
                    if (!runCatching { memory.restore(fallback = "play") }.getOrDefault(false)) runCatching { memory.requester(seasonKey(state)).requestFocus() }
                }
            }
        }
        Notice(state.message, vm::messageShown)
    }

    if (show == null || ep == null) return
    if (more) {
        MorePanel(
            kicker = episodeKicker(show.title, ep.season, ep.number),
            title = ep.title,
            onReport = if (state.canReport) ({ more = false; reporting = true }) else null,
            onReplace = if (state.canReplace) ({ more = false; replacing = true }) else null,
            onDelete = if (state.canDelete) ({ more = false; vm.askDelete() }) else null,
            onDismiss = { more = false },
        )
    }
    if (details) ShowDetailsSheet(show, services.library) { details = false }
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
    if (replacing) {
        val v = ep.versions.firstOrNull()
        ConfirmDialog(
            title = REPLACE_TITLE,
            kicker = episodeKicker(show.title, ep.season, ep.number),
            file = v?.let { it.label to formatBytes(it.sizeBytes) },
            confirm = "Replace file",
            confirmIcon = WmmIcons.Replace,
            onConfirm = {
                replacing = false
                vm.replaceFile()
            },
            onDismiss = { replacing = false },
        ) {
            Body(replaceBody(show.managedBy ?: "Sonarr"))
        }
    }
    state.deleting?.let { step -> DeleteDialog(show, ep, step, vm::confirmDelete, vm::cancelDelete, vm::setRecordAgain) }
    val subject = vm.requestSubject()
    if (asking != null && subject != null) {
        RequestDialog(
            subject = subject,
            options = state.options,
            only = asking?.takeIf { it != ALL_SEASONS },
            onSent = { message ->
                asking = null
                vm.say(message)
            },
            onDismiss = { asking = null },
        )
    }
}

/** [ShowPageScreen]'s `asking` for Request missing seasons. */
private const val ALL_SEASONS = -1

/** The height of the top (`section` and the still), so the tabs and the row stay put as the episode on top changes. */
private val TopHeight = 540.dp

/** The focus key of the season tabs' entry: the chosen tab, or Mark season watched beside a one-season heading. */
private fun seasonKey(state: ShowPageViewModel.State): String = if (state.oneSeason) "mark-season" else "tab:${state.seasonNumber}"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageContent(
    show: ShowDetail,
    ep: EpisodeDetail,
    state: ShowPageViewModel.State,
    vm: ShowPageActions,
    library: LibraryRepository,
    memory: FocusMemory,
    onPlay: (PlayRequest) -> Unit,
    onMore: () -> Unit,
    onAsk: (Int) -> Unit,
    onDetails: () -> Unit,
) {
    val density = LocalDensity.current
    val spec = remember(density) { with(density) { KeepInViewSpec(leading = 72.dp.toPx(), trailing = 48.dp.toPx()) } }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Back at the episodes, the top comes back into view with them.
    val toTop = { if (scroll.value != 0) scope.launch { scroll.animateScrollTo(0) } }
    // OK on a card: focus goes back up to its Play, which never plays from here.
    // Remembered, so moving along the row (which changes the state) doesn't hand every card a new one.
    val fallback by rememberUpdatedState(seasonKey(state))
    val backToPlay: () -> Unit = remember(scope, scroll, memory) {
        {
            scope.launch {
                scroll.animateScrollTo(0)
                if (!runCatching { memory.requester("play").requestFocus() }.getOrDefault(false)) runCatching { memory.requester(fallback).requestFocus() }
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 64.dp)) {
            Top(show, ep, state, vm, library, memory, onPlay, onMore, onAsk)
            SeasonTabs(state, vm, memory, onAsk, Modifier.onFocusChanged { if (it.hasFocus) toTop() })
            EpisodeRow(show, state, vm, library, memory, onOpen = backToPlay, Modifier.onFocusChanged { if (it.hasFocus) toTop() })
            About(show, state, vm, library, memory, onAsk, onDetails)
        }
    }
}

/**
 * The episode on top (`section`): the eyebrow and why it is there, its
 * title, facts, overview, file and progress, its buttons; the still on the
 * right, over the show's backdrop. Its height is fixed so moving along the
 * row redraws only this.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Top(
    show: ShowDetail,
    ep: EpisodeDetail,
    state: ShowPageViewModel.State,
    vm: ShowPageActions,
    library: LibraryRepository,
    memory: FocusMemory,
    onPlay: (PlayRequest) -> Unit,
    onMore: () -> Unit,
    onAsk: (Int) -> Unit,
) {
    val version = ep.versions.firstOrNull()
    val partway = ep.partway
    val missing = state.missing
    Box(Modifier.fillMaxWidth().height(TopHeight).bringWholeIntoView()) {
        Backdrop(
            library.imageUrl(show.backdropPath, ImageSize.W1280),
            seed = show.title,
            modifier = Modifier.align(Alignment.TopEnd).width(1360.dp).overflowHeight(700.dp, reported = 0.dp),
            leftFade = listOf(0f to 1f, 0.24f to 0.85f, 0.62f to 0f),
            bottomFadeFrom = 0.55f,
            glowX = 0.7f,
            glowY = 0.3f,
        )
        // The same size of picture as the row's cards, so a still already in the row is reused.
        TopStill(ep, library.imageUrl(ep.stillPath, ImageSize.W780), "${show.title} ${ep.season}x${ep.number}", missing, Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 100.dp))
        Column(Modifier.padding(start = ContentStart, top = 72.dp).width(1080.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(episodeKicker(show.title, ep.season, ep.number).uppercase(), style = inter(19.sp, FontWeight.Bold, tracking = 0.14f), color = WmmColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                state.reason?.let { r ->
                    Text(
                        r.label,
                        style = inter(16.sp, FontWeight.SemiBold),
                        color = WmmColors.Accent,
                        modifier = Modifier.border(1.dp, WmmColors.Accent, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (ep.isNew && !ep.watched) {
                    Text(
                        "NEW",
                        style = inter(14.sp, FontWeight.Bold, tracking = 0.05f),
                        color = WmmColors.OnAccent,
                        modifier = Modifier.background(WmmColors.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Text(ep.title.ifBlank { "Episode ${ep.number}" }, style = inter(72.sp, FontWeight.ExtraBold, lineHeight = 76.sp, tracking = -0.025f), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically, maxLines = 1) {
                val facts = listOfNotNull(
                    airedLabel(ep.airDate) ?: "Not aired yet".takeIf { !ep.aired },
                    ep.runtimeMin?.takeIf { ep.aired }?.let { "$it min" },
                    formatRating(ep.rating),
                )
                facts.forEachIndexed { i, f ->
                    if (i > 0) Text("·", style = inter(23.sp), color = WmmColors.TextFaint)
                    Text(f, style = inter(23.sp), color = TextBright)
                }
                if (ep.watched) {
                    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WatchedCheck(size = 28.dp)
                        Text("Watched", style = inter(21.sp, FontWeight.SemiBold), color = WmmColors.Accent)
                    }
                }
            }
            if (missing != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(missing.kind.tvIcon(), contentDescription = null, tint = missing.kind.tvTone(), modifier = Modifier.size(26.dp))
                    Text(missing.label, style = inter(22.sp, FontWeight.SemiBold), color = missing.kind.tvTone(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                missing.progress?.let { TvDownloadBar(it, width = 520.dp) }
            }
            if (ep.overview.isNotBlank()) {
                Text(ep.overview, style = inter(22.sp, lineHeight = 33.sp), color = WmmColors.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 960.dp))
            }
            if (state.reason == OnTopReason.WatchedItAll) Text(WATCHED_IT_ALL_NOTE, style = inter(20.sp), color = WmmColors.TextSoft)
            if (version != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically, maxLines = 1) {
                    episodeTags(version).forEach { TvTag(it) }
                    val rest = listOfNotNull(subtitleLanguages(version)?.let { "Subtitles: $it" }, formatBytes(version.sizeBytes)).joinToString(" · ")
                    Text(rest, style = inter(18.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                }
                version.recording?.let { Text(it.label(media.watchmy.client.ui.components.rememberLiveClockFormat()), style = inter(18.sp), color = WmmColors.TextMuted) }
            }
            if (partway != null && version != null) {
                Row(Modifier.width(520.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.weight(1f).height(6.dp).background(WmmColors.Line2, RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxWidth(partway.positionMs.toFloat() / partway.durationMs).fillMaxHeight().background(WmmColors.Accent, RoundedCornerShape(3.dp)))
                    }
                    Text(formatRemaining(partway.durationMs - partway.positionMs), style = inter(18.sp), color = WmmColors.TextSoft)
                }
            }
            Buttons(ep, state, vm, missing, memory, onPlay, onMore, onAsk)
        }
    }
}

/** The episode on top's still (`520 × 292`), dashed when it isn't here or hasn't aired. */
@Composable
private fun TopStill(ep: EpisodeDetail, url: String?, seed: String, missing: MissingEpisode?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val frame = modifier.size(520.dp, 292.dp)
    when {
        missing != null -> MissingStill(missing, radius = 14.dp, iconSize = 56.dp, modifier = frame)
        !ep.aired || !ep.hasFile -> Box(
            frame.background(WmmColors.Surface, shape).drawBehind {
                drawRoundRect(WmmColors.Line3, cornerRadius = CornerRadius(14.dp.toPx()), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (ep.aired) "Not in library" else "Not aired yet", style = inter(22.sp, FontWeight.SemiBold), color = WmmColors.TextMuted)
        }
        else -> Artwork(url, seed = seed, contentDescription = null, modifier = frame.border(1.dp, WmmColors.Line2, shape), shape = shape) {
            Box(Modifier.fillMaxSize()) { ArtProgress(ep.partway?.let { it.positionMs.toFloat() / it.durationMs }) }
        }
    }
}

/** One of the page's buttons; the first is keyed "play", where focus starts. */
private class Action(val label: String, val onClick: () -> Unit, val style: TvButtonStyle = TvButtonStyle.Ghost, val icon: ImageVector? = null, val enabled: Boolean = true)

/**
 * Play (Resume from and From the start), Mark watched and More, or for a
 * missing episode what can be done about it. ▼ goes to the season tabs.
 */
@Composable
private fun Buttons(
    ep: EpisodeDetail,
    state: ShowPageViewModel.State,
    vm: ShowPageActions,
    missing: MissingEpisode?,
    memory: FocusMemory,
    onPlay: (PlayRequest) -> Unit,
    onMore: () -> Unit,
    onAsk: (Int) -> Unit,
) {
    val play = episodePlayLabel(ep)
    val actions = buildList {
        if (play != null) {
            add(Action(play, { vm.playRequest()?.let(onPlay) }, TvButtonStyle.Primary, WmmIcons.Play))
            if (ep.partway != null) add(Action("From the start", { vm.playRequest(startMs = 0)?.let(onPlay) }))
        } else if (missing != null) {
            if (missing.request) add(Action(requestSeasonLabel(ep.season), { onAsk(ep.season) }, TvButtonStyle.Primary, WmmIcons.Plus))
            if (missing.search) add(Action("Search now", vm::searchMissing, enabled = !state.acting))
            if (missing.monitor) add(Action("Monitor", vm::monitor, enabled = !state.acting))
            missing.cancel?.let { r -> add(Action("Cancel request", { vm.cancelRequest(r.id) }, enabled = !state.acting)) }
        }
        if (ep.aired) add(Action(watchToggleLabel(ep.watched), vm::toggleWatched, icon = if (ep.watched) WmmIcons.Ring else WmmIcons.CheckCircle))
    }
    val down = memory.requester(seasonKey(state))
    val nav = Modifier.focusProperties {
        this.up = FocusRequester.Cancel
        this.down = down
    }
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        if (play == null && (missing == null || !missing.actions)) {
            // Nothing to play yet: when it airs, or that it isn't here.
            Row(Modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(10.dp).background(WmmColors.TextFaint, CircleShape))
                Text(if (ep.aired) "Not in library" else airedLabel(ep.airDate) ?: "Not aired yet", style = inter(22.sp, FontWeight.SemiBold), color = WmmColors.TextMuted)
            }
        }
        actions.forEachIndexed { i, a ->
            val key = if (i == 0) "play" else "action:${a.label}"
            TvButton(a.label, a.onClick, nav.focusMemory(memory, key), style = a.style, size = TvButtonSize.Large, icon = a.icon, enabled = a.enabled)
        }
        if (state.hasMore) MoreButton(onMore, nav.focusMemory(memory, if (actions.isEmpty()) "play" else "more"))
    }
}

/** The square ⋯ button (`.btn.ghost.sq`), [size] square: the episode's 64, the movie's 60 beside its buttons. */
@Composable
internal fun MoreButton(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 64.dp, iconSize: Dp = 28.dp) {
    val shape = RoundedCornerShape(14.dp)
    FocusableBox(onClick = onClick, modifier = modifier.semantics { contentDescription = "More" }, cornerRadius = 14.dp, focusedScale = 1.04f) { focused ->
        Box(
            Modifier.size(size).background(Color.White.copy(alpha = if (focused) 0.16f else 0.10f), shape).border(1.dp, Color.White.copy(alpha = 0.14f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(WmmIcons.MoreHoriz, contentDescription = null, tint = WmmColors.Text, modifier = Modifier.size(iconSize))
        }
    }
}

/**
 * The season tabs (`.season`, Specials last), the chosen one in gold: focus
 * on a tab changes the season at once. A one-season show has a plain
 * heading. Then the season's summary and Mark season watched, and with Show
 * missing the season's Request, Search now and Monitor. ▲ goes to Play.
 */
@Composable
private fun SeasonTabs(state: ShowPageViewModel.State, vm: ShowPageActions, memory: FocusMemory, onAsk: (Int) -> Unit, modifier: Modifier = Modifier) {
    val number = state.seasonNumber ?: return
    val summary = state.seasonSummary
    val up = memory.requester("play")
    val nav = Modifier.focusProperties {
        this.up = up
        this.down = memory.requester("ep:${state.episodeId}")
    }
    Row(modifier.fillMaxWidth().padding(start = ContentStart - 6.dp, end = ContentEnd, top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.oneSeason) {
            Text(seasonName(number), style = inter(28.sp, FontWeight.SemiBold), color = WmmColors.Text, modifier = Modifier.padding(start = 6.dp, end = 8.dp))
        } else {
            val index = state.seasons.indexOfFirst { it.number == number }
            val tabs = rememberLazyListState(initialFirstVisibleItemIndex = (index - 3).coerceAtLeast(0))
            LazyRow(
                Modifier.weight(1f, fill = false).focusRestorer(memory.requester("tab:$number")),
                state = tabs,
                contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.seasons, key = { it.number }) { s ->
                    SeasonTab(s, s.number == number, state.showMissing, nav.focusMemory(memory, "tab:${s.number}").onFocusChanged { if (it.isFocused) vm.selectSeason(s.number) }) { vm.selectSeason(s.number) }
                }
            }
        }
        if (summary != null) {
            Text(
                if (state.showMissing) seasonCountLine(summary.episodeCount, summary.availableCount) else seasonWatchLine(summary.availableCount, summary.watchedCount, state.season?.nextToAir()),
                style = inter(18.sp),
                color = WmmColors.TextMuted,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        if (state.canMarkSeason) {
            TvButton(markSeasonButtonLabel(state.seasonWatched), { vm.setSeasonWatched(!state.seasonWatched) }, nav.focusMemory(memory, "mark-season"), size = TvButtonSize.Small, enabled = !state.acting)
        }
        state.seasonActions?.takeIf { it.any }?.let { a ->
            if (a.request) TvButton(requestSeasonLabel(number), { onAsk(number) }, nav.focusMemory(memory, "season-request"), size = TvButtonSize.Small, icon = WmmIcons.Plus)
            if (a.search) TvButton("Search now", vm::searchSeason, nav.focusMemory(memory, "season-search"), size = TvButtonSize.Small, enabled = !state.acting)
            if (a.monitor) TvButton("Monitor", vm::monitorSeason, nav.focusMemory(memory, "season-monitor"), size = TvButtonSize.Small, enabled = !state.acting)
        }
    }
}

@Composable
private fun SeasonTab(s: SeasonSummary, on: Boolean, missing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusableBox(onClick = onClick, modifier = modifier, cornerRadius = 12.dp, focusedScale = 1.04f, ringColor = if (on) WmmColors.Text else WmmColors.Accent) { focused ->
        Row(
            Modifier
                .height(54.dp)
                .background(if (on) WmmColors.Accent else if (focused) WmmColors.Surface3 else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(seasonName(s.number), style = inter(21.sp, FontWeight.SemiBold), color = if (on) WmmColors.OnAccent else TextBright)
            val count = when {
                !missing || s.availableCount >= s.episodeCount -> "${s.availableCount}"
                s.availableCount == 0 -> "not in library"
                else -> "${s.availableCount} of ${s.episodeCount}"
            }
            Text(count, style = inter(21.sp, FontWeight.Medium), color = if (on) WmmColors.OnAccent else WmmColors.TextMuted)
        }
    }
}

/**
 * The season's episodes (`.card`), the one on top ringed in gold with On
 * top. Focus on a card puts its episode on top; OK ([onOpen]) goes back up
 * to its Play. ▲ goes to the chosen tab.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(show: ShowDetail, state: ShowPageViewModel.State, vm: ShowPageActions, library: LibraryRepository, memory: FocusMemory, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val spec = remember(density) { with(density) { KeepInViewSpec(leading = 12.dp.toPx(), trailing = (ContentEnd + 12.dp).toPx()) } }
    val episodes = state.episodes
    val index = episodes.indexOfFirst { it.id == state.episodeId }
    val row = rememberLazyListState(initialFirstVisibleItemIndex = (index - 1).coerceAtLeast(0))
    // A new season starts at its start: the row scrolls there without waiting for focus.
    LaunchedEffect(state.season?.season?.number) {
        val i = episodes.indexOfFirst { it.id == state.episodeId }
        if (i >= 0 && row.layoutInfo.visibleItemsInfo.none { it.index == i }) row.scrollToItem((i - 1).coerceAtLeast(0))
    }
    val up = memory.requester(seasonKey(state))
    val nav = remember(up) { Modifier.focusProperties { this.up = up } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        LazyRow(
            modifier.fillMaxWidth().padding(start = ContentStart - 12.dp, top = 12.dp).focusRestorer(memory.requester("ep:${state.episodeId}")),
            state = row,
            contentPadding = PaddingValues(start = 12.dp, end = ContentEnd, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(episodes, key = { it.id }) { e ->
                // Kept across moves along the row, so only the cards whose outline changes draw again.
                val card = remember(e.id, up, memory, vm) { nav.focusMemory(memory, "ep:${e.id}").onFocusChanged { if (it.isFocused) vm.selectEpisode(e.id) } }
                SeasonCard(
                    e,
                    library.imageUrl(e.stillPath, ImageSize.W780),
                    "${show.title} ${e.season}x${e.number}",
                    e.id == state.episodeId,
                    state.missingFor(e),
                    card,
                    onOpen,
                )
            }
        }
    }
}

@Composable
private fun SeasonCard(ep: EpisodeDetail, url: String?, seed: String, onTop: Boolean, missing: MissingEpisode?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    FocusableBox(onClick = onClick, modifier = modifier.width(320.dp), cornerRadius = 12.dp, focusedScale = 1.06f) {
        Column {
            Box(Modifier.size(320.dp, 180.dp)) {
                if (missing != null) {
                    // As Show missing draws one: its state's icon in a dashed frame.
                    MissingStill(missing, radius = 12.dp, iconSize = 44.dp, modifier = Modifier.fillMaxSize())
                } else if (!ep.aired || !ep.hasFile) {
                    Box(
                        Modifier.fillMaxSize().background(WmmColors.Surface, shape).drawBehind {
                            drawRoundRect(WmmColors.Line3, cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (ep.aired) "Not in library" else "Not aired yet", style = inter(17.sp, FontWeight.SemiBold), color = if (ep.aired) WmmColors.Warn else WmmColors.TextMuted)
                    }
                } else {
                    Artwork(url, seed = seed, contentDescription = null, modifier = Modifier.fillMaxSize(), shape = shape) {
                        Box(Modifier.fillMaxSize()) {
                            if (ep.watched) WatchedCheck(Modifier.align(Alignment.TopEnd).padding(8.dp), size = 26.dp)
                            ArtProgress(ep.partway?.let { it.positionMs.toFloat() / it.durationMs })
                        }
                    }
                }
                ArtBadge(String.format(Locale.ROOT, "E%02d", ep.number), Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 14.dp))
                if (onTop) {
                    Box(Modifier.fillMaxSize().border(3.dp, WmmColors.Accent, shape))
                    ArtBadge("On top", Modifier.align(Alignment.TopStart).padding(10.dp), BadgeStyle.Gold)
                }
            }
            Text(ep.title, style = inter(20.sp, FontWeight.SemiBold), color = if (ep.aired) WmmColors.Text else WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            Text(cardLine(ep, missing), style = inter(17.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Under a card: "29 min left", "Watched", "52 min", or when a missing one is waiting. */
private fun cardLine(ep: EpisodeDetail, missing: MissingEpisode?): String = when {
    missing != null -> missing.label.substringBefore(" · ")
    !ep.aired -> episodeMeta(ep)
    ep.partway != null -> ep.partway!!.let { formatRemaining(it.durationMs - it.positionMs) }
    ep.watched -> "Watched"
    else -> ep.runtimeMin?.let { "$it min" } ?: episodeMeta(ep)
}

/**
 * About the show (`section`, past the row): the poster, title, facts and
 * status, the overview, then Mark show watched, Show missing episodes,
 * Request missing seasons and Cast & details. ▲ goes back to the row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun About(show: ShowDetail, state: ShowPageViewModel.State, vm: ShowPageActions, library: LibraryRepository, memory: FocusMemory, onAsk: (Int) -> Unit, onDetails: () -> Unit) {
    val up = memory.requester("ep:${state.episodeId}")
    val nav = Modifier.focusProperties { this.up = up }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = ContentStart, end = ContentEnd, top = 36.dp)
            .drawBehind { drawLine(WmmColors.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(top = 36.dp)
            .bringWholeIntoView(),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Artwork(library.imageUrl(show.posterPath, ImageSize.W342), seed = show.title, contentDescription = null, modifier = Modifier.size(200.dp, 300.dp), shape = RoundedCornerShape(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(show.title, style = inter(48.sp, FontWeight.ExtraBold, lineHeight = 52.sp, tracking = -0.02f), color = WmmColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(showFacts(show), style = inter(21.sp), color = TextBright)
                showStatus(show)?.let { (text, active) ->
                    val color = if (active) WmmColors.Accent else WmmColors.TextMuted
                    Text(text, style = inter(16.sp, FontWeight.SemiBold), color = color, modifier = Modifier.border(1.dp, color, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
                }
            }
            if (show.overview.isNotBlank()) Text(show.overview, style = inter(20.sp, lineHeight = 30.sp), color = WmmColors.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 1100.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (state.canMarkShow) TvButton(markShowLabel(state.showWatched), { vm.setShowWatched(!state.showWatched) }, nav.focusMemory(memory, "about:mark"), enabled = !state.acting)
                if (state.canShowMissing) TvButton(missingEpisodesLabel(state.showMissing), { vm.setShowMissing(!state.showMissing) }, nav.focusMemory(memory, "about:missing"))
                if (state.missingSeasons) TvButton("Request missing seasons", { onAsk(ALL_SEASONS) }, nav.focusMemory(memory, "about:request"))
                TvButton("Cast & details", onDetails, nav.focusMemory(memory, "about:details"), icon = WmmIcons.Info)
            }
        }
    }
}

/** "2022– · 3 seasons · 24 episodes · Drama · ★ 8.1". */
private fun showFacts(show: ShowDetail): String {
    val seasons = show.seasons.count { it.number > 0 }
    val ended = show.status.isEnded()
    return listOfNotNull(
        show.year?.let { if (ended) "$it" else "$it–" },
        plural(seasons, "season").takeIf { seasons > 0 },
        plural(show.episodesOnDisk, "episode").takeIf { show.episodesOnDisk > 0 },
        show.genres.firstOrNull(),
        formatRating(show.rating),
    ).joinToString(" · ")
}

/** "Continuing · next Nov 18" (gold) or "Ended". */
private fun showStatus(show: ShowDetail): Pair<String, Boolean>? {
    val status = show.status ?: return null
    if (status.isEnded()) return (if (status.contains("cancel", ignoreCase = true)) "Cancelled" else "Ended") to false
    val next = monthDay(show.nextAirDate)
    return (if (next != null) "Continuing · next $next" else "Continuing") to true
}

private fun String?.isEnded() = this != null && (contains("ended", ignoreCase = true) || contains("cancel", ignoreCase = true))

/** One of More's rows beyond the episode's own (the movie page has none). */
internal class MoreItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * More (`aside`): what the viewer may do with the episode or movie, then
 * [showItems] under [showTitle] (the episode page's show group), Delete
 * episode… last in red. Back closes it.
 */
@Composable
internal fun MorePanel(
    kicker: String,
    title: String,
    onReport: (() -> Unit)?,
    onReplace: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    showTitle: String? = null,
    showItems: List<MoreItem> = emptyList(),
) {
    val first = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(600.dp)
                    .fillMaxHeight()
                    .background(WmmColors.Surface)
                    .border(1.dp, WmmColors.Line)
                    .padding(start = 40.dp, end = 40.dp, top = 64.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(kicker.uppercase(), style = inter(17.sp, FontWeight.Bold, tracking = 0.1f), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(title, style = inter(34.sp, FontWeight.ExtraBold, lineHeight = 39.sp, tracking = -0.015f), color = WmmColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var firstUsed = false
                    fun Modifier.firstOne(): Modifier = if (firstUsed) this else this.focusRequester(first).also { firstUsed = true }
                    onReport?.let { MenuRow("Report a problem", WmmIcons.Flag, it, Modifier.firstOne()) }
                    onReplace?.let { MenuRow("Replace file", WmmIcons.Replace, it, Modifier.firstOne()) }
                    if (showItems.isNotEmpty()) {
                        if (onReport != null || onReplace != null) MenuDivider()
                        showTitle?.let {
                            Text(
                                it.uppercase(),
                                style = inter(15.sp, FontWeight.Bold, tracking = 0.1f),
                                color = WmmColors.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp),
                            )
                        }
                        showItems.forEach { item -> MenuRow(item.label, item.icon, item.onClick, Modifier.firstOne()) }
                    }
                    onDelete?.let {
                        if (onReport != null || onReplace != null || showItems.isNotEmpty()) MenuDivider()
                        MenuRow("Delete episode…", WmmIcons.Trash, it, Modifier.firstOne(), danger = true)
                    }
                }
                Box(Modifier.weight(1f))
                Text("Back closes this panel.", style = inter(17.sp), color = WmmColors.TextFaint, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        InitialFocus(first)
    }
}

@Composable
private fun MenuDivider() {
    Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(WmmColors.Line))
}

@Composable
private fun MenuRow(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    FocusableBox(onClick = onClick, modifier = modifier.fillMaxWidth(), cornerRadius = 12.dp, focusedScale = 1f, ringOffset = (-4).dp) { focused ->
        Row(
            Modifier.fillMaxWidth().height(72.dp).background(if (focused) WmmColors.Surface3 else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(icon, contentDescription = null, tint = if (danger) WmmColors.Error else WmmColors.TextMuted, modifier = Modifier.size(28.dp))
            Text(text, style = inter(22.sp, FontWeight.SemiBold), color = if (danger) WmmColors.Error else WmmColors.Text)
        }
    }
}

/**
 * Delete episode (EpisodeDelete.dc.html): what Sonarr will do, as
 * previewDelete says, with Cancel focused and Delete episode beside it; or,
 * for a file no Radarr or Sonarr manages, that WatchMyMedia can't, with OK.
 */
@Composable
private fun DeleteDialog(show: ShowDetail, ep: EpisodeDetail, step: ShowPageViewModel.Deleting, onDelete: () -> Unit, onDismiss: () -> Unit, onRecordAgain: (Boolean) -> Unit = {}) {
    val confirm = step as? ShowPageViewModel.Deleting.Confirm
    val cant = step == ShowPageViewModel.Deleting.Cant
    val v = ep.versions.firstOrNull()
    val size = confirm?.preview?.sizeBytes?.takeIf { it > 0 } ?: v?.sizeBytes
    ConfirmDialog(
        title = deleteEpisodeTitle(ep.title),
        kicker = episodeKicker(show.title, ep.season, ep.number),
        file = v?.let { it.label to formatBytes(size ?: it.sizeBytes) },
        confirm = if (cant) null else "Delete episode",
        confirmIcon = WmmIcons.Trash,
        confirmEnabled = confirm != null && !confirm.busy && (confirm.preview.recording || confirm.preview.reachable.isNotEmpty()),
        dismiss = if (cant) "OK" else "Cancel",
        busy = confirm?.busy == true,
        onConfirm = onDelete,
        onDismiss = onDismiss,
    ) {
        when (step) {
            ShowPageViewModel.Deleting.Checking -> CircularProgressIndicator(color = WmmColors.Accent, trackColor = WmmColors.Line2, strokeWidth = 4.dp, modifier = Modifier.size(40.dp))
            ShowPageViewModel.Deleting.Cant -> Body(CANT_DELETE_NOTE)
            is ShowPageViewModel.Deleting.Confirm -> {
                val apps = step.preview.reachable
                if (step.preview.recording) {
                    Body("WatchMyMedia will delete the files it recorded. This can’t be undone.")
                    TvButton(if (step.recordAgain) "☐ Don’t record it again" else "☑ Don’t record it again", { onRecordAgain(!step.recordAgain) }, enabled = !step.busy, size = TvButtonSize.Medium)
                } else Body(listOf(deleteEpisodeWhat(apps), deleteWhere(apps)).filter { it.isNotEmpty() }.joinToString(" "))
                otherEpisodesLine(apps)?.let { Body(it) }
                unreachableLines(step.preview.apps).forEach { Body(it, WmmColors.Warn) }
                Body(DELETE_HISTORY_NOTE, WmmColors.TextSoft)
            }
        }
    }
}

/**
 * A centred confirmation over the page: [title], [kicker] and the [file],
 * then [body] and the buttons, focus starting on [dismiss] (Cancel, or OK
 * when there is nothing to confirm).
 */
@Composable
private fun ConfirmDialog(
    title: String,
    kicker: String,
    file: Pair<String, String>?,
    confirm: String?,
    confirmIcon: ImageVector,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    dismiss: String = "Cancel",
    busy: Boolean = false,
    body: @Composable () -> Unit,
) {
    val cancel = remember { FocusRequester() }
    TvDialog(onDismiss = { if (!busy) onDismiss() }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.66f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(1000.dp)
                    .background(WmmColors.Surface2, RoundedCornerShape(18.dp))
                    .border(1.dp, WmmColors.Line2, RoundedCornerShape(18.dp))
                    .padding(start = 56.dp, end = 56.dp, top = 48.dp, bottom = 52.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = inter(44.sp, FontWeight.ExtraBold, lineHeight = 48.sp, tracking = -0.02f), color = WmmColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(kicker, style = inter(21.sp), color = WmmColors.TextMuted)
                }
                if (file != null) {
                    Row(
                        Modifier.fillMaxWidth().background(WmmColors.Ground, RoundedCornerShape(12.dp)).border(1.dp, WmmColors.Line2, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(WmmIcons.File, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(24.dp))
                        Text(file.first, style = inter(19.sp).copy(fontFamily = FontFamily.Monospace), color = WmmColors.TextSoft, modifier = Modifier.weight(1f))
                        Text(file.second, style = inter(19.sp), color = WmmColors.TextMuted)
                    }
                }
                body()
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TvButton(dismiss, { if (!busy) onDismiss() }, Modifier.focusRequester(cancel), size = TvButtonSize.Large)
                    if (confirm != null) TvButton(confirm, onConfirm, style = TvButtonStyle.Danger, size = TvButtonSize.Large, icon = confirmIcon, enabled = confirmEnabled)
                }
            }
        }
        InitialFocus(cancel)
    }
}

@Composable
private fun Body(text: String, color: Color = TextBright) {
    Text(text, style = inter(22.sp, lineHeight = 33.sp), color = color)
}
