package media.watchmy.client.tv.detail

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.createSavedStateHandle
import kotlinx.coroutines.launch
import media.watchmy.client.model.ShowArrival
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.ProblemKind
import media.watchmy.client.model.REPLACE_TITLE
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.episodeKicker
import media.watchmy.client.model.formatBytes
import media.watchmy.client.model.replaceBody
import media.watchmy.client.model.watched
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.detail.ShowPageActions
import media.watchmy.client.presentation.detail.ShowPageViewModel
import media.watchmy.client.tv.components.ErrorState
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.KeepInViewSpec
import media.watchmy.client.tv.components.LoadingState
import media.watchmy.client.tv.components.Notice
import media.watchmy.client.tv.components.OptionsDialog
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.tv.requests.RequestDialog
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.ReportShown

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
 * episodes, Request missing seasons and Cast & details; ▼ again More like
 * this (ShowRelated.dc.html), whose cards open their page through
 * [onOpenShow]. Back leaves the show in one step; the delete confirmation
 * opens on Cancel.
 */
@Composable
fun ShowPageScreen(
    showId: Long,
    arrival: ShowArrival,
    onPlay: (PlayRequest) -> Unit,
    onNavigate: (Any) -> Unit = {},
    onOpenShow: (Long) -> Unit = {},
    onOpenMovie: (Long) -> Unit = {},
) {
    val services = LocalAppServices.current
    val vm = viewModel(key = "show-$showId") { ShowPageViewModel(services.library, showId, arrival, services.requests, follows = services.follows, saved = createSavedStateHandle()) }
    ReportShown(vm::setShown)
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
                PageContent(show, ep, state, vm, services.library, memory, onPlay, onMore = { more = true }, onAsk = { asking = it }, onDetails = { details = true }) {
                    RelatedRows(state.related, services.library, memory, onOpenMovie, onOpenShow, Modifier.padding(top = 60.dp))
                }
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
internal const val ALL_SEASONS = -1

/** The height of the top (`section` and the still), so the tabs and the row stay put as the episode on top changes. */
internal val TopHeight = 540.dp

/** The focus key of the season tabs' entry: the chosen tab, or Mark season watched beside a one-season heading. */
internal fun seasonKey(state: ShowPageViewModel.State): String = if (state.oneSeason) "mark-season" else "tab:${state.seasonNumber}"

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
    related: @Composable () -> Unit,
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
    // The episode on top, read where focus searches rather than where the tabs and About compose: moving along
    // the row changes it, and they don't draw it (PLAN-ANDROID-REVIEW.md §4.3.4).
    val episodeNow by rememberUpdatedState(state.episodeId)
    val episodeFocus: () -> FocusRequester = remember(memory) { { memory.requester("ep:$episodeNow") } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 64.dp)) {
            Top(show, ep, state, vm, library, memory, onPlay, onMore, onAsk)
            state.seasonBar()?.let { bar -> SeasonTabs(bar, vm, memory, episodeFocus, onAsk, Modifier.onFocusChanged { if (it.hasFocus) toTop() }) }
            EpisodeRow(show, state, vm, library, memory, onOpen = backToPlay, Modifier.onFocusChanged { if (it.hasFocus) toTop() })
            About(show, state.aboutFlags(), vm, library, memory, episodeFocus, onAsk, onDetails)
            related()
        }
    }
}
