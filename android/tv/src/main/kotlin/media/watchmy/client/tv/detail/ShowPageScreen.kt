package media.watchmy.client.tv.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackPlan
import media.watchmy.client.model.ProblemKind
import media.watchmy.client.model.REPLACE_TITLE
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.episodeCode
import media.watchmy.client.model.episodeKicker
import media.watchmy.client.model.formatBytes
import media.watchmy.client.model.missingEpisodesLabel
import media.watchmy.client.model.playRequest
import media.watchmy.client.model.replaceBody
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
import media.watchmy.client.tv.components.TvClock
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.tv.requests.RequestDialog
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.ReportShown
import media.watchmy.client.ui.components.WmmIcons

/** Which of the show page's panels is open. */
private enum class ShowPanel { None, More, Details, Tracks, Report, Replace, About }

/**
 * One page for a show, its seasons and its episodes on TV.
 * The episode on top leads, over the show's backdrop: the show and when its
 * next episode comes, the episode's facts, how far through it is and when it
 * would end, with Resume or Play focused first, then From the start, Mark
 * watched and More (Audio & subtitles, File details, Report a problem,
 * Replace file, Delete episode…). ▼ reaches the season tabs, where ◀ ▶
 * changes the season at once and OK goes down to its episodes; ▼ again the
 * episode row, where the episode on top follows focus and OK moves focus
 * back up to its Play (it never plays). ▼ past the row scrolls to About the
 * show (Notify me, Watchlist, Mark show watched, and a More with Show
 * missing episodes and Request missing seasons), then the cast as a row of
 * people, then More like this, whose cards open their page through
 * [onOpenShow]. Back leaves the show in one step; a panel closes first, and
 * File details and Audio & subtitles go back to More.
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
    val vm = viewModel(key = "show-$showId") { ShowPageViewModel(services.library, showId, arrival, services.requests, follows = services.follows, saved = createSavedStateHandle(), watchlists = services.watchlists) }
    ReportShown(vm::setShown)
    val state by vm.state.collectAsStateWithLifecycle()
    val memory = rememberFocusMemory()
    var panel by rememberSaveable { mutableStateOf(ShowPanel.None) }
    var moreFocus by rememberSaveable { mutableStateOf<String?>(null) }
    // The request dialog: for one season, or every missing one (ALL_SEASONS); null when closed.
    var asking by rememberSaveable { mutableStateOf<Int?>(null) }
    val show = state.show
    val ep = state.episode
    // Audio & subtitles chosen for the episode on top; another episode starts again from its own defaults.
    var audio by rememberSaveable(state.episodeId) { mutableStateOf<Int?>(null) }
    var subtitles by rememberSaveable(state.episodeId) { mutableStateOf(SUBTITLES_AUTO) }
    val play: (Long?) -> Unit = { startMs -> vm.playRequest(startMs)?.withTracks(audio, subtitles)?.let(onPlay) }

    TvScaffold(RailSection.Shows, onNavigate, sectionRoot = false) {
        when {
            (show == null || ep == null) && state.page is Load.Failed -> ErrorState((state.page as Load.Failed).message, vm::retry, title = "Couldn't load this show")
            show == null || ep == null -> LoadingState()
            else -> {
                PageContent(
                    show, ep, state, vm, services.library, memory, play,
                    onMore = {
                        moreFocus = null
                        panel = ShowPanel.More
                    },
                    onAsk = { asking = it },
                    onAboutMore = if (state.aboutFlags().hasMore) ({ panel = ShowPanel.About }) else null,
                ) {
                    RelatedRows(state.related, services.library, memory, onOpenMovie, onOpenShow, Modifier.padding(top = 40.dp))
                }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    // Play, or whatever the episode offers first; with nothing to press, the season tabs.
                    if (!runCatching { memory.restore(fallback = "play") }.getOrDefault(false)) runCatching { memory.requester(seasonKey(state)).requestFocus() }
                }
            }
        }
        TvClock()
        Notice(state.message, vm::messageShown)
    }

    if (show == null || ep == null) return
    val version = ep.versions.firstOrNull()
    val context = "${episodeCode(ep.season, ep.number)} · ${ep.title}"
    fun backToMore(from: String) {
        moreFocus = from
        panel = ShowPanel.More
    }
    when (panel) {
        ShowPanel.None -> Unit
        ShowPanel.More -> TitleMorePanel(
            context = context,
            rows = buildList {
                if (version != null && (version.audio.isNotEmpty() || version.subtitles.isNotEmpty())) {
                    add(MoreRow("tracks", "Audio & subtitles", { moreFocus = "tracks"; panel = ShowPanel.Tracks }, tracksLine(version, audio, subtitles), opens = true))
                }
                if (version != null) add(MoreRow("details", "File details", { moreFocus = "details"; panel = ShowPanel.Details }, "What the file is, and how it will play here", opens = true))
                if (state.canReport) add(MoreRow("report", "Report a problem", { moreFocus = "report"; panel = ShowPanel.Report }))
                if (state.canReplace) add(MoreRow("replace", "Replace file", { moreFocus = "replace"; panel = ShowPanel.Replace }, "Asks first"))
                if (state.canDelete) add(MoreRow("delete", "Delete episode…", { panel = ShowPanel.None; vm.askDelete() }, "Asks first"))
            },
            focusKey = moreFocus,
            onDismiss = { panel = ShowPanel.None },
        )
        ShowPanel.Details -> {
            val plans by produceState(emptyMap<Long, PlaybackPlan>(), ep.id) {
                value = runCatching { services.playback.plans(ep.versions.map { it.fileId }) }.getOrDefault(emptyMap())
            }
            FileDetailsPanel(
                context = context,
                versions = ep.versions,
                plans = plans,
                onPlay = { v ->
                    panel = ShowPanel.None
                    val request = ep.playRequest(show, startMs = null, fileId = v.fileId)
                    (if (v == version) request?.withTracks(audio, subtitles) else request)?.let(onPlay)
                },
                onDismiss = { backToMore("details") },
            )
        }
        ShowPanel.Tracks -> if (version != null) {
            TracksPanel(context, version, audio, subtitles, onAudio = { audio = it }, onSubtitles = { subtitles = it }, onDismiss = { backToMore("tracks") })
        }
        ShowPanel.Report -> OptionsDialog(
            title = "Report a problem",
            options = ProblemKind.entries,
            selected = null,
            label = { it.label },
            kicker = context,
            onSelect = {
                panel = ShowPanel.None
                vm.report(it)
            },
            onDismiss = { backToMore("report") },
        )
        ShowPanel.Replace -> ConfirmDialog(
            title = REPLACE_TITLE,
            kicker = episodeKicker(show.title, ep.season, ep.number),
            file = version?.let { it.label to formatBytes(it.sizeBytes) },
            confirm = "Replace file",
            confirmIcon = WmmIcons.Replace,
            onConfirm = {
                panel = ShowPanel.None
                vm.replaceFile()
            },
            onDismiss = { backToMore("replace") },
        ) {
            Body(replaceBody(show.managedBy ?: "Sonarr"))
        }
        ShowPanel.About -> {
            val flags = state.aboutFlags()
            TitleMorePanel(
                context = show.title,
                rows = buildList {
                    if (flags.canShowMissing) add(MoreRow("missing", missingEpisodesLabel(flags.showMissing), { vm.setShowMissing(!flags.showMissing) }))
                    if (flags.missingSeasons) add(MoreRow("request", "Request missing seasons", { panel = ShowPanel.None; asking = ALL_SEASONS }))
                },
                onDismiss = { panel = ShowPanel.None },
            )
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

/** About the show's buttons' focus keys, in order. */
private val AboutButtons = listOf("about:follow", "about:watchlist", "about:mark", "about:more")

/** [ShowPageScreen]'s `asking` for Request missing seasons. */
internal const val ALL_SEASONS = -1

/** The height of the top, so the tabs and the row stay put as the episode on top changes. */
internal val TopHeight = 485.dp

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
    play: (Long?) -> Unit,
    onMore: () -> Unit,
    onAsk: (Int) -> Unit,
    onAboutMore: (() -> Unit)?,
    related: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    // Below the heading that stays at the top once the page has scrolled.
    val spec = remember(density) { with(density) { KeepInViewSpec(leading = 150.dp.toPx(), trailing = 54.dp.toPx()) } }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Back at the episodes, the top comes back into view with them.
    val toTop = { if (scroll.value != 0) scope.launch { scroll.animateScrollTo(0) } }
    // The buttons focus was last on, above the tabs and in About, where ▲ goes back to.
    val topButton = remember { mutableStateOf("play") }
    val aboutButton = remember { mutableStateOf("about:follow") }
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
    // the row changes it, and they don't draw it.
    val episodeNow by rememberUpdatedState(state.episodeId)
    val episodeFocus: () -> FocusRequester = remember(memory) { { memory.requester("ep:$episodeNow") } }
    var rowFocused by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 64.dp)) {
                Top(show, ep, state, vm, library, memory, { topButton.value = it }, play, onMore, onAsk)
                state.seasonBar()?.let { bar -> SeasonTabs(bar, vm, memory, { topButton.value }, episodeFocus, onAsk, Modifier.onFocusChanged { if (it.hasFocus) toTop() }) }
                EpisodeRow(show, state, vm, library, memory, onOpen = backToPlay, Modifier.onFocusChanged {
                    rowFocused = it.hasFocus
                    if (it.hasFocus) toTop()
                })
                AboutPeek(show, rowFocused, Modifier.padding(top = 30.dp))
                About(show, state.aboutFlags(), vm, library, memory, { aboutButton.value = it }, episodeFocus, onAboutMore, Modifier.padding(top = 140.dp))
                CastRow(show.cast, library, memory, Modifier.padding(top = 40.dp).upTo(memory) { listOf(aboutButton.value) + AboutButtons })
                related()
            }
            ScrolledHeading(scroll, back = show.title) {
                Text("${episodeCode(ep.season, ep.number)} · ${ep.title} is on top", style = TvType.Second, color = TvPalette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}
