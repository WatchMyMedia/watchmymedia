package media.watchmy.client.mobile.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Stable
import androidx.lifecycle.createSavedStateHandle
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.model.downloadSeasonStarted
import media.watchmy.client.presentation.downloads.DownloadsViewModel
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.BackTopBar
import media.watchmy.client.mobile.components.CastButton
import media.watchmy.client.mobile.components.ErrorState
import media.watchmy.client.mobile.components.LoadingState
import media.watchmy.client.mobile.components.MessageHost
import media.watchmy.client.mobile.requests.ReportProblemSheet
import media.watchmy.client.mobile.requests.RequestSheet
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.episodeKicker
import media.watchmy.client.model.formatBytes
import media.watchmy.client.model.watched
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.detail.ShowPageActions
import media.watchmy.client.presentation.detail.ShowPageViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.rememberTabletLayout
import media.watchmy.client.ui.ReportShown

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
    ReportShown(vm::setShown)
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
internal const val ALL_SEASONS = -1

/** What the page's parts hand back to the screen: playing, the sheets and dialogs it opens. */
@Stable
internal class PageActions(
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
internal val StillMax = 360.dp

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

/** Back and Cast over the still; solid with the episode's title once the still has scrolled away. */
@Composable
private fun PageBar(title: String?, solid: Boolean, onBack: () -> Unit) {
    val ground by animateColorAsState(if (solid) WmmColors.Ground else Color.Transparent, label = "bar")
    BackTopBar(title, onBack, Modifier.background(ground), overArt = !solid) {
        CastButton(overArt = !solid)
    }
}
