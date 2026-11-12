package media.watchmy.client.mobile.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Stable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.createSavedStateHandle
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.OnTopReason
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.model.WATCHED_IT_ALL_NOTE
import media.watchmy.client.model.downloadSeasonStarted
import media.watchmy.client.model.markSeasonButtonLabel
import media.watchmy.client.model.plural
import media.watchmy.client.model.seasonCountLine
import media.watchmy.client.model.nextToAir
import media.watchmy.client.model.seasonWatchLine
import media.watchmy.client.presentation.downloads.DownloadsViewModel
import media.watchmy.client.ui.components.rememberLiveClockFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.BackTopBar
import media.watchmy.client.mobile.components.CastButton
import media.watchmy.client.mobile.components.ErrorState
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.LoadingState
import media.watchmy.client.mobile.components.MessageHost
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.mobile.downloads.ItemDownload
import media.watchmy.client.mobile.requests.DownloadBar
import media.watchmy.client.mobile.requests.MissingFrame
import media.watchmy.client.mobile.requests.OffPill
import media.watchmy.client.mobile.requests.ReportProblemSheet
import media.watchmy.client.mobile.requests.RequestSheet
import media.watchmy.client.mobile.requests.tone
import media.watchmy.client.model.CANT_DELETE_NOTE
import media.watchmy.client.model.DELETE_HISTORY_NOTE
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.MissingEpisode
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.REPLACE_TITLE
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.deleteEpisodeTitle
import media.watchmy.client.model.deleteEpisodeWhat
import media.watchmy.client.model.deleteWhere
import media.watchmy.client.model.episodeKicker
import media.watchmy.client.model.playable
import media.watchmy.client.model.episodePlayLabel
import media.watchmy.client.model.episodeTags
import media.watchmy.client.model.formatBytes
import media.watchmy.client.model.formatRating
import media.watchmy.client.model.codecLabel
import media.watchmy.client.model.hdrLabel
import media.watchmy.client.model.markShowLabel
import media.watchmy.client.model.resolutionLabel
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
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.rememberTabletLayout
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.Badge
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.ProgressStrip
import media.watchmy.client.ui.components.WatchedCheck
import media.watchmy.client.ui.components.WmmIcons

/**
 * One page for a show, its seasons and its episodes (ShowPage.dc.html,
 * ShowPageMissing.dc.html and, from 1000 dp, TabShowPage.dc.html;
 * PLAN-SHOW-PAGE.md §4.2). The chosen episode is on top with Play, Mark
 * watched and its ⋮ (Report a problem, Replace file, Delete episode…); the
 * season chips (Specials last) and the season's row of episodes under it,
 * with Mark season watched and Show missing; then About the show with Mark
 * show watched and Request missing seasons. A chip or a card changes the
 * episode on top here, never the back stack, so Back leaves the show.
 * [arrival] says which episode goes on top first.
 */
@Composable
fun ShowPageScreen(
    showId: Long,
    arrival: ShowArrival,
    onPlay: (PlayRequest) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
) {
    val services = LocalAppServices.current
    val vm = viewModel { ShowPageViewModel(services.library, showId, arrival, services.requests, services.downloads, createSavedStateHandle()) }
    val state by vm.state.collectAsStateWithLifecycle()
    val downloadsVm = viewModel { DownloadsViewModel(services.downloads) }
    val downloads by downloadsVm.state.collectAsStateWithLifecycle()
    // The request sheet: for one season, or every missing one (ALL_SEASONS); null when closed.
    var asking by rememberSaveable { mutableStateOf<Int?>(null) }
    var reporting by rememberSaveable { mutableStateOf(false) }
    var replacing by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val threshold = with(LocalDensity.current) { StillScroll.roundToPx() }
    val solid by remember { derivedStateOf { scroll.value > threshold } }
    val twoPane = rememberTabletLayout().twoPane
    val show = state.show
    val ep = state.episode

    Box(modifier.fillMaxSize().background(WmmColors.Ground)) {
        when {
            (show == null || ep == null) && state.page is Load.Failed -> ErrorState((state.page as Load.Failed).message, vm::retry)
            show == null || ep == null -> LoadingState()
            else -> {
                val page = PageActions(
                    onPlay = onPlay,
                    onAsk = { asking = it },
                    onReport = { reporting = true },
                    onReplace = { replacing = true },
                    onOpenSettings = onOpenSettings,
                    onDownloadSeason = { ids, number -> downloadsVm.downloadAll(ids, downloadSeasonStarted(ids.count { id -> downloads.all.none { it.fileId == id } }, number)) },
                )
                if (twoPane) TabletContent(show, ep, state, vm, downloads, scroll, page) else PhoneContent(show, ep, state, vm, downloads, scroll, page)
            }
        }
        PageBar(title = ep?.title?.takeIf { solid }, solid = solid, onBack = onBack)
        MessageHost(state.message, vm::messageShown, Modifier.align(Alignment.BottomCenter).padding(WindowInsets.navigationBars.asPaddingValues()))
        ViewSnackbar(downloads.message, onView = onOpenDownloads, onShown = downloadsVm::messageShown, Modifier.align(Alignment.BottomCenter).padding(WindowInsets.navigationBars.asPaddingValues()))
    }

    if (show == null || ep == null) return
    val subject = vm.requestSubject()
    if (asking != null && subject != null) {
        RequestSheet(
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
    if (reporting) {
        ReportProblemSheet(
            kicker = episodeKicker(show.title, ep.season, ep.number),
            episode = true,
            itemId = ep.id,
            fileId = ep.versions.firstOrNull()?.fileId,
            onSent = { message ->
                reporting = false
                vm.say(message)
            },
            onDismiss = { reporting = false },
        )
    }
    if (replacing) {
        ReplaceDialog(
            app = show.managedBy ?: "Sonarr",
            file = ep.versions.firstOrNull()?.let { "${it.label} · ${formatBytes(it.sizeBytes)}" },
            onReplace = {
                replacing = false
                vm.replaceFile()
            },
            onDismiss = { replacing = false },
        )
    }
    state.deleting?.let { d -> DeleteDialog(show, ep, d, onDelete = vm::confirmDelete, onDismiss = vm::cancelDelete, onRecordAgain = vm::setRecordAgain) }
}

/** [ShowPageScreen]'s `asking` for Request missing seasons. */
private const val ALL_SEASONS = -1

/** What the page's parts hand back to the screen: playing, the sheets and dialogs it opens. */
@Stable
private class PageActions(
    val onPlay: (PlayRequest) -> Unit,
    /** Request season N, or every missing season with [ALL_SEASONS]. */
    val onAsk: (Int) -> Unit,
    val onReport: () -> Unit,
    val onReplace: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onDownloadSeason: (fileIds: List<Long>, season: Int) -> Unit,
)

/** How far the page scrolls before the bar turns solid: about where the still meets it. */
private val StillScroll = 170.dp

/** The phone board's column; a tablet under 1000 dp centres it. */
private val ColumnMax = 720.dp

/** The still's height at most, on a tablet under 1000 dp. */
private val StillMax = 360.dp

/** The phone: the still edge to edge, the episode, the seasons and their row, then About the show (ShowPage.dc.html). */
@Composable
private fun PhoneContent(
    show: ShowDetail,
    ep: EpisodeDetail,
    state: ShowPageViewModel.State,
    vm: ShowPageActions,
    downloads: DownloadsViewModel.State,
    scroll: ScrollState,
    page: PageActions,
) {
    val library = LocalAppServices.current.library
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val column = Modifier.widthIn(max = ColumnMax).fillMaxWidth().padding(horizontal = 16.dp)
    BackToTop(state.episodeId, scroll)
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = bottom + 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Still(ep, library.imageUrl(ep.stillPath ?: show.backdropPath, ImageSize.W1280), "${show.title} ${ep.season}x${ep.number}", state.missing)
        Column(column.padding(top = 16.dp)) {
            EpisodeHeading(show, ep, state, titleSize = 24.sp)
            EpisodeFacts(ep, state)
            if (ep.overview.isNotBlank()) {
                Text(ep.overview, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, color = WmmColors.TextSoft), modifier = Modifier.padding(top = 8.dp))
            }
            EpisodeFile(ep)
            Actions(show, ep, state, vm, page, Modifier.padding(top = 12.dp))
        }
        SeasonsSection(show, state, vm, downloads, page, still = PhoneStill, modifier = Modifier.widthIn(max = ColumnMax).padding(top = 28.dp))
        AboutShow(show, state, vm, page, divider = true, modifier = column.padding(top = 28.dp))
    }
}

/**
 * From 1000 dp (TabShowPage.dc.html): the episode on the left with its still
 * beside its title, About the show on the right, the chips and the row
 * across the bottom.
 */
@Composable
private fun TabletContent(
    show: ShowDetail,
    ep: EpisodeDetail,
    state: ShowPageViewModel.State,
    vm: ShowPageActions,
    downloads: DownloadsViewModel.State,
    scroll: ScrollState,
    page: PageActions,
) {
    val library = LocalAppServices.current.library
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = top + 72.dp, bottom = bottom + 32.dp)) {
        Row(Modifier.padding(start = 32.dp, end = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    StillFrame(
                        ep,
                        library.imageUrl(ep.stillPath ?: show.backdropPath, ImageSize.W780),
                        "${show.title} ${ep.season}x${ep.number}",
                        state.missing,
                        Modifier.size(320.dp, 180.dp).clip(RoundedCornerShape(12.dp)),
                        shade = false,
                    )
                    Column(Modifier.weight(1f)) {
                        EpisodeHeading(show, ep, state, titleSize = 28.sp)
                        EpisodeFacts(ep, state)
                        if (ep.overview.isNotBlank()) {
                            Text(
                                ep.overview,
                                style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, color = WmmColors.TextSoft),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
                EpisodeFile(ep)
                Actions(show, ep, state, vm, page, Modifier)
            }
            AboutShow(
                show, state, vm, page,
                modifier = Modifier.width(360.dp).clip(RoundedCornerShape(16.dp)).background(WmmColors.Surface).border(1.dp, WmmColors.Line, RoundedCornerShape(16.dp)).padding(16.dp),
                divider = false,
                compact = true,
            )
        }
        SeasonsSection(show, state, vm, downloads, page, still = TabletStill, modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 32.dp))
    }
}

/** A card picked in the row puts its episode on top: the page goes back up to show it. */
@Composable
private fun BackToTop(episodeId: Long, scroll: ScrollState) {
    var first by remember { mutableStateOf(episodeId) }
    LaunchedEffect(episodeId) {
        if (episodeId != first) scroll.animateScrollTo(0)
        first = episodeId
    }
}

/** "HARBOR LIGHTS · S03 E07" with why it is there ("Up next"), then the title. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeHeading(show: ShowDetail, ep: EpisodeDetail, state: ShowPageViewModel.State, titleSize: TextUnit) {
    Column {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text(
            episodeKicker(show.title, ep.season, ep.number).uppercase(),
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.96.sp, color = WmmColors.Accent),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        state.reason?.let { ReasonChip(it.label) }
    }
    Text(
        ep.title.ifBlank { "Episode ${ep.number}" },
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = titleSize, lineHeight = titleSize * 1.15f, letterSpacing = (-0.24).sp, color = WmmColors.Text),
        modifier = Modifier.padding(top = 6.dp).semantics { heading() },
    )
    }
}

/** "Up next", "Watched it all": outlined in gold beside the eyebrow. */
@Composable
private fun ReasonChip(text: String) {
    Text(
        text,
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = WmmColors.Accent),
        modifier = Modifier.height(22.dp).border(1.dp, WmmColors.Accent, RoundedCornerShape(11.dp)).padding(horizontal = 8.dp).wrapContentHeight(Alignment.CenterVertically),
    )
}

/** Aired, runtime and rating with Watched; what a missing one waits for; Watched it all's note. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeFacts(ep: EpisodeDetail, state: ShowPageViewModel.State) {
    val missing = state.missing
    FlowRow(
        Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val meta = if (missing != null) missingMeta(ep) else listOfNotNull(episodeMeta(ep).ifEmpty { null }, formatRating(ep.rating)).joinToString(" · ")
        Text(meta, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
        if (ep.watched) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WatchedCheck(size = 16.dp)
                Text("Watched", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = WmmColors.Accent))
            }
        }
    }
    ep.versions.firstOrNull()?.recording?.let { Text(it.label(rememberLiveClockFormat()), color = WmmColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
    if (missing != null) {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(missing.label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp, color = missing.kind.tone()))
            missing.progress?.let { DownloadBar(it, Modifier.fillMaxWidth()) }
        }
    }
    if (state.reason == OnTopReason.WatchedItAll) {
        Text(WATCHED_IT_ALL_NOTE, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextSoft), modifier = Modifier.padding(top = 8.dp))
    }
}

/** The file's tags, subtitles and size, and its name under File (decision 7: the chosen episode's file is enough). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeFile(ep: EpisodeDetail) {
    val version = ep.versions.firstOrNull() ?: return
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            episodeTags(version).forEach { Tag(it) }
            val rest = listOfNotNull(subtitleLanguages(version)?.let { "Subtitles: $it" }, formatBytes(version.sizeBytes)).joinToString(" · ")
            Text(rest, style = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = WmmColors.TextMuted), modifier = Modifier.padding(start = 2.dp))
        }
        Row(
            Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = if (open) "Hide the file" else "Show the file") { open = !open },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(if (open) WmmIcons.ChevronDown else WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(16.dp))
            Text("File", style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = WmmColors.TextMuted))
        }
        if (open) Text(version.label, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = WmmColors.TextMuted))
    }
}

/**
 * Play (or Resume) across, then From the start, Mark watched and the
 * episode's ⋮; for a missing episode what can be done about it; then the
 * episode's download.
 */
@Composable
private fun Actions(show: ShowDetail, ep: EpisodeDetail, state: ShowPageViewModel.State, vm: ShowPageActions, page: PageActions, modifier: Modifier = Modifier) {
    val play = episodePlayLabel(ep)
    val missing = state.missing
    val fromStart = play != null && ep.partway != null
    val menu: @Composable () -> Unit = {
        EpisodeMenu(
            onReport = if (state.canReport) page.onReport else null,
            onReplace = if (state.canReplace) page.onReplace else null,
            onDelete = if (state.canDelete) vm::askDelete else null,
        )
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            play != null -> FilledButton(play, { vm.playRequest()?.let(page.onPlay) }, Modifier.fillMaxWidth(), large = true, icon = WmmIcons.Play)
            missing != null && missing.actions -> {
                if (missing.request) FilledButton(requestSeasonLabel(ep.season), { page.onAsk(ep.season) }, Modifier.fillMaxWidth(), large = true)
                if (missing.requested && missing.cancel == null) OffPill("Requested", Modifier.fillMaxWidth(), large = true)
                if (missing.search || missing.monitor) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (missing.search) TonalButton("Search now", vm::searchMissing, Modifier.weight(1f), busy = state.acting)
                        if (missing.monitor) TonalButton("Monitor", vm::monitor, Modifier.weight(1f), enabled = !state.acting)
                    }
                }
                missing.cancel?.let { r -> TonalButton("Cancel request", { vm.cancelRequest(r.id) }, Modifier.fillMaxWidth(), enabled = !state.acting) }
            }
            !ep.aired -> AirsLine(ep)
            else -> FilledButton("Not in library", {}, Modifier.fillMaxWidth(), large = true, enabled = false)
        }
        if (fromStart || ep.aired || state.hasMore) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fromStart) TonalButton("From the start", { vm.playRequest(startMs = 0)?.let(page.onPlay) }, Modifier.weight(1f), compact = true)
                if (ep.aired) {
                    TonalButton(
                        watchToggleLabel(ep.watched),
                        vm::toggleWatched,
                        Modifier.weight(1f),
                        busy = state.marking,
                        // Beside From the start and ⋮ the label needs the room its icon would take.
                        icon = if (fromStart) null else if (ep.watched) WmmIcons.Undo else WmmIcons.Check,
                        compact = fromStart,
                    )
                }
                if (state.hasMore) menu() else if (!fromStart && !ep.aired) Spacer(Modifier.weight(1f))
            }
        }
        if (ep.playable) {
            val version = ep.versions.firstOrNull()
            ItemDownload(
                fileId = version?.fileId,
                kicker = episodeKicker(show.title, ep.season, ep.number),
                from = downloadFrom(ep),
                onPlayCopy = { vm.playRequest()?.let(page.onPlay) },
                onOpenSettings = page.onOpenSettings,
                onMessage = vm::say,
                tablet = rememberTabletLayout().isTablet,
            )
        }
    }
}

/** "Airs Tue, Nov 18", where Play goes for an episode still to air. */
@Composable
private fun AirsLine(ep: EpisodeDetail) {
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(WmmColors.TextFaint))
        Text(episodeMeta(ep), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = WmmColors.TextMuted))
    }
}

/**
 * The episode's ⋮ beside Mark watched (ShowPage.dc.html, Tweak menu
 * "episode"): Report a problem, Replace file (admins) and, last, Delete
 * episode… in the danger colour. The show's actions are in About the show.
 */
@Composable
private fun EpisodeMenu(onReport: (() -> Unit)?, onReplace: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = WmmColors.Line2, contentColor = WmmColors.Text),
        ) {
            Icon(PhoneIcons.MoreVert, contentDescription = "More for this episode")
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.width(240.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = MenuGround,
            border = BorderStroke(1.dp, WmmColors.Line2),
        ) {
            fun pick(action: () -> Unit) {
                open = false
                action()
            }
            onReport?.let { MenuItem("Report a problem", WmmIcons.Flag) { pick(it) } }
            onReplace?.let { MenuItem("Replace file", WmmIcons.Replace) { pick(it) } }
            onDelete?.let {
                if (onReport != null || onReplace != null) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = WmmColors.Line2)
                MenuItem("Delete episode…", WmmIcons.Trash, color = DangerText) { pick(it) }
            }
        }
    }
}

/** A still's size in the row: the phone board's 148 by 83, the tablet's 188 by 106. */
private val PhoneStill = 148.dp
private val TabletStill = 188.dp

/**
 * The season chips (Specials last; a plain heading for a one-season show),
 * what the season has and Mark season watched, Show missing, the season's
 * missing actions, then its episodes in a row with the one on top outlined.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonsSection(
    show: ShowDetail,
    state: ShowPageViewModel.State,
    vm: ShowPageActions,
    downloads: DownloadsViewModel.State,
    page: PageActions,
    still: Dp,
    modifier: Modifier = Modifier,
) {
    val library = LocalAppServices.current.library
    val number = state.seasonNumber ?: return
    val summary = state.seasonSummary
    val season = state.season
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.oneSeason) {
            Text(
                seasonName(number),
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = WmmColors.Text),
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            )
        } else {
            val index = state.seasons.indexOfFirst { it.number == number }
            val chips = rememberLazyListState(initialFirstVisibleItemIndex = (index - 1).coerceAtLeast(0))
            LazyRow(Modifier.semantics { contentDescription = "Seasons" }, state = chips, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.seasons, key = { it.number }) { s -> SeasonChip(s, s.number == number, state.showMissing) { vm.selectSeason(s.number) } }
            }
        }
        if (summary != null) {
            // What the season has and Mark season watched, then Show missing; wrapping on a narrow phone.
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.showMissing) seasonCountLine(summary.episodeCount, summary.availableCount) else seasonWatchLine(summary.availableCount, summary.watchedCount, state.season?.nextToAir()),
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted),
                    maxLines = 1,
                    modifier = Modifier.padding(end = 4.dp),
                )
                if (state.canMarkSeason) GoldTextButton(markSeasonButtonLabel(state.seasonWatched), { vm.setSeasonWatched(!state.seasonWatched) }, enabled = !state.acting)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (state.canShowMissing) MissingSwitch(state.showMissing, vm::setShowMissing)
                    if (downloads.enabled && season != null && !state.offline) SeasonDownloadMenu(summary, season, downloads, page.onDownloadSeason)
                }
            }
        }
        state.seasonActions?.takeIf { it.any }?.let { actions ->
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (actions.request) TonalButton(requestSeasonLabel(number), { page.onAsk(number) }, compact = true)
                if (actions.requested) OffPill("Requested")
                if (actions.search) TonalButton("Search now", vm::searchSeason, compact = true, busy = state.acting)
                if (actions.monitor) TonalButton("Monitor", vm::monitorSeason, compact = true, enabled = !state.acting)
            }
        }
        if (season != null) EpisodeRow(show, state, vm, library, still)
    }
}

/** The season's episodes; the one on top outlined in gold and kept in view. */
@Composable
private fun EpisodeRow(show: ShowDetail, state: ShowPageViewModel.State, vm: ShowPageActions, library: LibraryRepository, still: Dp) {
    val episodes = state.episodes
    val index = episodes.indexOfFirst { it.id == state.episodeId }
    val row = rememberLazyListState(initialFirstVisibleItemIndex = (index - 1).coerceAtLeast(0))
    LaunchedEffect(state.episodeId, state.season?.season?.number) {
        if (index < 0) return@LaunchedEffect
        val seen = row.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        val whole = seen != null && seen.offset >= row.layoutInfo.viewportStartOffset && seen.offset + seen.size <= row.layoutInfo.viewportEndOffset
        if (!whole) row.animateScrollToItem((index - 1).coerceAtLeast(0))
    }
    LazyRow(Modifier.padding(top = 4.dp), state = row, contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(episodes, key = { it.id }) { e ->
            SeasonCard(e, library.imageUrl(e.stillPath, ImageSize.W300), "${show.title} ${e.season}x${e.number}", e.id == state.episodeId, state.missingFor(e), still) { vm.selectEpisode(e.id) }
        }
    }
}

/** Show missing, a switch with its label (ShowPage.dc.html). */
@Composable
private fun MissingSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).toggleable(value = on, role = Role.Switch, onValueChange = onChange).padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = WmmColors.Ground,
                checkedTrackColor = WmmColors.Accent,
                checkedBorderColor = WmmColors.Accent,
                uncheckedThumbColor = WmmColors.TextFaint,
                uncheckedTrackColor = WmmColors.Line2,
                uncheckedBorderColor = WmmColors.TextFaint,
            ),
        )
        Text("Show missing", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = WmmColors.Text))
    }
}

/**
 * About the show: its poster, facts and status, overview, Mark show watched
 * and Request missing seasons, cast and what is on disk. [compact] is the
 * tablet's card beside the episode.
 */
@Composable
private fun AboutShow(show: ShowDetail, state: ShowPageViewModel.State, vm: ShowPageActions, page: PageActions, divider: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    val library = LocalAppServices.current.library
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (divider) HorizontalDivider(color = WmmColors.Line, modifier = Modifier.padding(bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Artwork(
                library.imageUrl(show.posterPath, ImageSize.W342),
                seed = show.title,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 64.dp else 72.dp, if (compact) 96.dp else 108.dp),
                shape = RoundedCornerShape(8.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    show.title,
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, lineHeight = 24.sp, color = WmmColors.Text),
                    modifier = Modifier.semantics { heading() },
                )
                DetailFacts(showFacts(show))
                statusLine(show)?.let { (text, active) -> StatusPill(text, active) }
            }
        }
        if (show.overview.isNotBlank()) Overview(show.overview, collapsedLines = if (compact) 3 else 4)
        if (state.canMarkShow) TonalButton(markShowLabel(state.showWatched), { vm.setShowWatched(!state.showWatched) }, Modifier.fillMaxWidth(), enabled = !state.acting)
        if (state.missingSeasons) TonalButton("Request missing seasons", { page.onAsk(ALL_SEASONS) }, Modifier.fillMaxWidth())
        if (compact) {
            if (show.cast.isNotEmpty()) AboutLine("Cast", show.cast.take(3).joinToString(", ") { it.name })
            if (show.genres.isNotEmpty()) AboutLine("Genres", show.genres.joinToString(", "))
            AboutLine("On disk", onDiskLine(show))
        } else {
            Box(Modifier.layout { m, c ->
                // The cast row runs to the screen's edges, past the column's padding.
                val wide = c.maxWidth + 32.dp.roundToPx()
                val p = m.measure(c.copy(minWidth = wide, maxWidth = wide))
                layout(c.maxWidth, p.height) { p.place(-16.dp.roundToPx(), 0) }
            }) { CastRow(show.cast) }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(WmmColors.Surface).border(1.dp, WmmColors.Line, RoundedCornerShape(12.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                show.status?.let { AboutLine("Status", it) }
                if (show.genres.isNotEmpty()) AboutLine("Genres", show.genres.joinToString(", "))
                AboutLine("On disk", onDiskLine(show))
            }
        }
    }
}

/** "24 episodes · 57.6 GB". */
private fun onDiskLine(show: ShowDetail): String = listOf(plural(show.episodesOnDisk, "episode"), formatBytes(show.totalSizeBytes)).joinToString(" · ")

@Composable
private fun AboutLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = WmmColors.TextMuted), modifier = Modifier.width(72.dp))
        Text(value, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 17.sp, color = WmmColors.TextSoft))
    }
}

/** Back and Cast over the still; solid with the episode's title once the still has scrolled away. */
@Composable
private fun PageBar(title: String?, solid: Boolean, onBack: () -> Unit) {
    val ground by animateColorAsState(if (solid) WmmColors.Ground else Color.Transparent, label = "bar")
    BackTopBar(title, onBack, Modifier.background(ground), overArt = !solid) {
        CastButton(overArt = !solid)
    }
}

/** The download sheet's line under its title: "The Long Dark · 52 min · 4K HEVC HDR". */
internal fun downloadFrom(ep: EpisodeDetail): String {
    val v = ep.versions.firstOrNull()
    val video = v?.let { listOfNotNull(resolutionLabel(it.width, it.height), codecLabel(it.videoCodec), hdrLabel(it.hdr)).joinToString(" ") }?.ifEmpty { null }
    return listOfNotNull(ep.title, ep.runtimeMin?.let { "$it min" }, video).joinToString(" · ")
}

/** The still edge to edge (`.still`), with NEW while unwatched, the runtime, and progress while part watched. */
@Composable
private fun Still(ep: EpisodeDetail, url: String?, seed: String, missing: MissingEpisode?) {
    // 16:9 across a phone; a tablet's width would make it most of the screen, so it is cropped to StillMax.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        StillFrame(ep, url, seed, missing, Modifier.fillMaxWidth().height(minOf(maxWidth * 9 / 16, StillMax)).clipToBounds())
    }
}

@Composable
private fun StillFrame(ep: EpisodeDetail, url: String?, seed: String, missing: MissingEpisode?, modifier: Modifier = Modifier, shade: Boolean = true) {
    Box(modifier) {
        if (!ep.aired || (missing != null && !ep.hasFile)) {
            Box(
                Modifier.fillMaxSize().background(WmmColors.Surface).drawBehind {
                    drawRect(WmmColors.Line3, style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (!ep.aired) "Not aired yet" else missing?.label.orEmpty(),
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = missing?.kind?.tone() ?: WmmColors.TextMuted),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        } else {
            Artwork(url, seed = seed, contentDescription = null, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    if (ep.isNew && !ep.watched) Badge("New", Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 14.dp), style = BadgeStyle.Gold)
                    ep.runtimeMin?.let { Badge("$it min", Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 14.dp)) }
                    ep.partway?.let { ProgressStrip(it.positionMs.toFloat() / it.durationMs, Modifier.align(Alignment.BottomStart), height = 5.dp) }
                }
            }
        }
        // Darkens the top so the bar's buttons read over a bright still.
        if (shade) Box(Modifier.fillMaxWidth().height(128.dp).background(Brush.verticalGradient(listOf(WmmColors.Ground.copy(alpha = 0.55f), Color.Transparent))))
    }
}

@Composable
private fun SeasonCard(ep: EpisodeDetail, url: String?, seed: String, current: Boolean, missing: MissingEpisode?, width: Dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val still = RoundedCornerShape(8.dp)
    Column(
        Modifier.width(width + 12.dp).clip(shape)
            .then(if (current) Modifier.background(WmmColors.Surface2, shape).border(2.dp, WmmColors.Accent, shape) else Modifier)
            .clickable(onClickLabel = "Put it on top", onClick = onClick)
            .semantics { selected = current }
            .padding(6.dp),
    ) {
        val frame = Modifier.size(width, width * 9 / 16)
        if (missing != null) {
            // As the show page draws a missing episode: its state's icon in a dashed frame.
            MissingFrame(missing.kind, frame, radius = 8.dp)
        } else if (!ep.aired || !ep.hasFile) {
            Box(
                frame.background(WmmColors.Surface, still).drawBehind {
                    drawRoundRect(WmmColors.Line3, cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (ep.aired) "Not in library" else "Not aired yet",
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = if (ep.aired) WmmColors.Warn else WmmColors.TextMuted),
                )
            }
        } else {
            Artwork(url, seed = seed, contentDescription = null, modifier = frame, shape = still) {
                Box(Modifier.fillMaxSize()) {
                    if (ep.watched) WatchedCheck(Modifier.align(Alignment.TopEnd).padding(5.dp), size = 18.dp)
                    if (ep.isNew && !ep.watched) Badge("New", Modifier.align(Alignment.TopStart).padding(5.dp), style = BadgeStyle.Gold, fontSize = 10.sp, horizontalPadding = 5.dp, verticalPadding = 2.dp)
                    ep.partway?.let { ProgressStrip(it.positionMs.toFloat() / it.durationMs, Modifier.align(Alignment.BottomStart)) }
                }
            }
        }
        Text(
            "${ep.number}. ${ep.title}",
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = WmmColors.Text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            when {
                // What a missing one waits for, shortly: "Missing", "Downloading 45%", "Requested".
                missing != null -> missing.label.substringBefore(" · ")
                ep.aired -> ep.runtimeMin?.let { "$it min" }.orEmpty()
                else -> episodeMeta(ep)
            },
            style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = missing?.kind?.tone() ?: WmmColors.TextMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, color: Color = Color(0xFFE5E5E5), onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp)) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        colors = MenuDefaults.itemColors(textColor = color, leadingIconColor = color),
        contentPadding = PaddingValues(horizontal = 16.dp),
    )
}

/**
 * Delete episode (EpisodeMenu.dc.html, confirm and cantDelete): what Sonarr
 * will do, as previewDelete says, with Cancel and Delete episode; or, for a
 * file no Radarr or Sonarr manages, that WatchMyMedia can't, with one OK.
 */
@Composable
private fun DeleteDialog(show: ShowDetail, ep: EpisodeDetail, step: ShowPageViewModel.Deleting, onDelete: () -> Unit, onDismiss: () -> Unit, onRecordAgain: (Boolean) -> Unit = {}) {
    val confirm = step as? ShowPageViewModel.Deleting.Confirm
    val busy = confirm?.busy == true
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = DialogWidth,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = WmmColors.Surface2,
        shape = RoundedCornerShape(28.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(deleteEpisodeTitle(ep.title), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp, color = WmmColors.Text))
                Text(episodeKicker(show.title, ep.season, ep.number), style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = WmmColors.TextMuted))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (step) {
                    ShowPageViewModel.Deleting.Checking -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = WmmColors.Accent, strokeWidth = 3.dp)
                    }
                    ShowPageViewModel.Deleting.Cant -> DialogBody(CANT_DELETE_NOTE)
                    is ShowPageViewModel.Deleting.Confirm -> {
                        val apps = step.preview.reachable
                        ep.versions.firstOrNull()?.let { v -> FileLine("${v.label} · ${formatBytes(step.preview.sizeBytes.takeIf { it > 0 } ?: v.sizeBytes)}") }
                        if (step.preview.recording) {
                            DialogBody("WatchMyMedia will delete the files it recorded. This can’t be undone.")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(checked = !step.recordAgain, onCheckedChange = { onRecordAgain(!it) }, enabled = !step.busy)
                                Text("Don’t record it again", color = WmmColors.TextSoft)
                            }
                        } else DialogBody(listOf(deleteEpisodeWhat(apps), deleteWhere(apps)).filter { it.isNotEmpty() }.joinToString(" "))
                        otherEpisodesLine(apps)?.let { DialogBody(it) }
                        unreachableLines(step.preview.apps).forEach { DialogBody(it, WmmColors.Warn) }
                        DialogBody(DELETE_HISTORY_NOTE, WmmColors.TextMuted)
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                ShowPageViewModel.Deleting.Cant -> GoldTextButton("OK", onDismiss)
                else -> DangerButton("Delete episode", onDelete, enabled = confirm != null && (confirm.preview.recording || confirm.preview.reachable.isNotEmpty()), busy = busy)
            }
        },
        dismissButton = if (step == ShowPageViewModel.Deleting.Cant) null else ({ GoldTextButton("Cancel", onDismiss, enabled = !busy, color = WmmColors.TextSoft) }),
    )
}

/** Replace file, admins only: Sonarr blocks the release and searches again. */
@Composable
private fun ReplaceDialog(app: String, file: String?, onReplace: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = DialogWidth,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = WmmColors.Surface2,
        shape = RoundedCornerShape(28.dp),
        title = { Text(REPLACE_TITLE, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp, color = WmmColors.Text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DialogBody(replaceBody(app))
                file?.let { FileLine(it) }
            }
        },
        confirmButton = { DangerButton("Replace file", onReplace) },
        dismissButton = { GoldTextButton("Cancel", onDismiss, color = WmmColors.TextSoft) },
    )
}

@Composable
private fun DialogBody(text: String, color: Color = WmmColors.TextSoft) {
    Text(text, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, color = color))
}

/** The file name and size (`.file`), monospaced. */
@Composable
private fun FileLine(text: String) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, color = WmmColors.TextSoft),
        modifier = Modifier.fillMaxWidth().background(WmmColors.Surface, shape).border(1.dp, WmmColors.Line2, shape).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** The red pill (`.btn.danger`). */
@Composable
private fun DangerButton(text: String, onClick: () -> Unit, enabled: Boolean = true, busy: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.height(40.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, DangerRim),
        colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White, disabledContainerColor = Danger.copy(alpha = 0.5f), disabledContentColor = Color.White.copy(alpha = 0.6f)),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
    }
}

/** The board's dialog: the screen's width less 24 dp each side, as wide as M3 lets a dialog be on a tablet. */
private val DialogWidth = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth()

private val MenuGround = Color(0xFF1C1C1F)
private val DangerText = Color(0xFFFCA5A5)
private val Danger = Color(0xFFB42318)
private val DangerRim = Color(0xFFE5484D)
