package media.watchmy.client.tv.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.liveTVError
import media.watchmy.client.presentation.live.LiveChannelsViewModel
import media.watchmy.client.presentation.live.LiveGuideViewModel
import media.watchmy.client.presentation.live.editLiveNumber
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.Notice
import media.watchmy.client.tv.components.OptionsDialog
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.tv.components.tryFocus
import media.watchmy.client.tv.navigation.TvRoutes
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.ShowsLiveChannels
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.ui.components.rememberLiveNow

/**
 * Live TV's Guide tab: with listings (API level 13 and `capabilities.guide`)
 * the guide; without them, every available channel as a tile that plays.
 * Recordings and Reminders are tabs beside it, opened as sections so Back
 * from them goes Home. The number keys open Go to channel anywhere here.
 */
@Composable
fun LiveChannelsScreen(
    state: LiveChannelsViewModel.State,
    onSelect: (LiveChannel) -> Unit,
    onWatch: (LiveChannel) -> Unit,
    onRefresh: () -> Unit,
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
    // The tabs open Recordings and Reminders as sections, so Back from them goes Home; these stay for the callers.
    @Suppress("UNUSED_PARAMETER") onReminders: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onRecordings: () -> Unit = {},
    target: LiveGuideViewModel.Target? = null,
    onTargetShown: () -> Unit = {},
) {
    ShowsLiveChannels()
    val memory = rememberFocusMemory()
    val guideTab = remember { FocusRequester() }
    var number by remember { mutableStateOf<String?>(null) }
    var navigating by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf<LiveChannel?>(null) }
    val channels = state.channels
    val tabs = liveTabs(recordings = state.capabilities.recording || state.capabilities.canRecord, reminders = state.extras)
    fun watch(channel: LiveChannel) {
        if (!channel.available) unavailable = channel else {
            number = null
            navigating = true
            onSelect(channel)
            onWatch(channel)
        }
    }
    TvScaffold(RailSection.LiveTV, onNavigate, modifier.onPreviewKeyEvent { event ->
        val char = liveNumberKey(event.nativeKeyEvent.keyCode)
        if (char == null || !state.available || number != null) false else {
            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 && char in '0'..'9') number = editLiveNumber("", char)
            true
        }
    }) {
        val content = Modifier.fillMaxSize().padding(start = ContentStart, top = SafeY)
        when {
            state.listings && channels.isNotEmpty() -> TvGuideContent(
                state, memory, tabs, guideTab, onSelect, ::watch, onGoTo = { number = "" }, onNavigate = onNavigate, navigating = navigating, modifier = content,
                target = target, onTargetShown = onTargetShown,
            )
            else -> Column(content) {
                LiveTabsHeader(LiveTab.Guide, tabs, onNavigate, first = guideTab)
                when {
                    state.loading && channels.isEmpty() -> {
                        GuideSkeleton()
                        InitialFocus(guideTab)
                    }
                    state.error != null && channels.isEmpty() -> {
                        val retry = remember { FocusRequester() }
                        LiveStateMessage(
                            "Live TV isn’t answering", state.error.orEmpty(), Modifier.padding(top = 150.dp),
                            note = if (LiveTab.Recordings in tabs) "Recordings you already have still play." else null,
                        ) {
                            TvButton("Try again", onRefresh, Modifier.focusRequester(retry), style = TvButtonStyle.Primary, growFrom = TransformOrigin(0f, 0.5f))
                            if (LiveTab.Recordings in tabs) TvButton("Recordings", { onNavigate(TvRoutes.LiveRecordings) })
                        }
                        InitialFocus(retry)
                    }
                    !state.available -> {
                        val retry = remember { FocusRequester() }
                        LiveStateMessage("Live TV isn’t available", "This server or account can’t watch Live TV. An admin looks after tuners and who may watch in the web app.", Modifier.padding(top = 150.dp)) {
                            TvButton("Check again", onRefresh, Modifier.focusRequester(retry), style = TvButtonStyle.Primary, growFrom = TransformOrigin(0f, 0.5f))
                        }
                        InitialFocus(retry)
                    }
                    channels.isEmpty() -> {
                        val retry = remember { FocusRequester() }
                        LiveStateMessage("No channels yet", "An admin can scan the tuner for channels and turn them on in the web app.", Modifier.padding(top = 150.dp)) {
                            TvButton("Check again", onRefresh, Modifier.focusRequester(retry), style = TvButtonStyle.Primary, growFrom = TransformOrigin(0f, 0.5f))
                        }
                        InitialFocus(retry)
                    }
                    else -> ChannelTiles(state, memory, navigating, ::watch)
                }
            }
        }
        number?.let { initial -> ChannelNumberDialog(channels, initial, ::watch, onDismiss = { number = null }) }
        unavailable?.let { channel -> LiveMessageDialog("${channel.number} · ${channel.name}", liveTVError(channel.reason), onDismiss = { unavailable = null }) }
    }
}

/** The tabs a server and person have: Guide always, Recordings where it records, Reminders where it keeps them. */
internal fun liveTabs(recordings: Boolean, reminders: Boolean): List<LiveTab> =
    listOfNotNull(LiveTab.Guide, LiveTab.Recordings.takeIf { recordings }, LiveTab.Reminders.takeIf { reminders })

/** Without listings: every channel as a tile, five across, that plays on OK. */
@Composable
private fun ChannelTiles(state: LiveChannelsViewModel.State, memory: FocusMemory, navigating: Boolean, onWatch: (LiveChannel) -> Unit) {
    val channels = state.channels
    val grid = rememberLazyGridState()
    Column(Modifier.fillMaxSize().padding(top = 36.dp)) {
        Text("Channels", style = TvType.Title, color = WmmColors.Text)
        Text(
            "There are no programme listings yet, so this is a list of channels. They all play.",
            style = TvType.Body, color = WmmColors.TextSoft, modifier = Modifier.padding(top = 10.dp).widthIn(max = 900.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            state = grid,
            modifier = Modifier.weight(1f).padding(end = TvSafeEnd - 12.dp),
            // Room for the focused tile's growth and ring; none at the foot, so the next row peeks.
            contentPadding = PaddingValues(start = 12.dp, top = 40.dp, end = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            items(channels, key = { it.id }) { channel ->
                LiveChannelCard(channel, { onWatch(channel) }, Modifier.focusMemory(memory, channel.id))
            }
        }
        KeyHints(listOf(KeyHint("OK", "Watch")), Modifier.padding(top = 16.dp, bottom = SafeY))
    }
    LaunchedEffect(channels.map { it.id }, state.selectedId, navigating) {
        if (navigating) return@LaunchedEffect
        val id = (state.selectedId ?: memory.key)?.takeIf { candidate -> channels.any { it.id == candidate } } ?: channels.firstOrNull()?.id
        val index = channels.indexOfFirst { it.id == id }
        if (index >= 0 && id != memory.key) grid.scrollToItem(index)
        withFrameNanos { }
        if (id != null) memory.requester(id).tryFocus()
    }
}

/** The guide while its listings load: grey where the hero and the rows will be, the Guide tab focused meanwhile. */
@Composable
private fun GuideSkeleton() {
    Column(Modifier.fillMaxWidth().padding(top = 36.dp, end = TvSafeEnd)) {
        Skeleton(260.dp, 26.dp)
        Skeleton(560.dp, 52.dp, Modifier.padding(top = 16.dp))
        Skeleton(820.dp, 28.dp, Modifier.padding(top = 18.dp))
        Skeleton(700.dp, 28.dp, Modifier.padding(top = 12.dp))
        Column(Modifier.padding(top = 96.dp, start = ChannelColumn + ChannelGap), verticalArrangement = Arrangement.spacedBy(RowGap)) {
            repeat(6) { Skeleton(null, RowHeight) }
        }
    }
}

/** Which picker the guide's buttons have open. */
private enum class GuidePicker { Channels, Day }

@Composable
private fun TvGuideContent(
    channels: LiveChannelsViewModel.State,
    memory: FocusMemory,
    tabs: List<LiveTab>,
    guideTab: FocusRequester,
    onSelect: (LiveChannel) -> Unit,
    onWatch: (LiveChannel) -> Unit,
    onGoTo: () -> Unit,
    onNavigate: (Any) -> Unit,
    navigating: Boolean,
    modifier: Modifier = Modifier,
    target: LiveGuideViewModel.Target? = null,
    onTargetShown: () -> Unit = {},
) {
    val services = LocalAppServices.current
    val model = viewModel { LiveGuideViewModel(services.live, services.servers, services.auth) }
    val state by model.state.collectAsStateWithLifecycle()
    val recordingActions = rememberTvRecordingActions()
    val recordingState by recordingActions.state.collectAsStateWithLifecycle()
    val now = rememberLiveNow()
    val format = rememberLiveClockFormat()
    val personal = state.personal && channels.extras
    val canRecord = recordingState.canRecord
    var picker by remember { mutableStateOf<GuidePicker?>(null) }
    var choices by remember { mutableStateOf<Pair<LiveChannel, media.watchmy.client.model.LiveProgramme>?>(null) }
    // Pressed a key in the tabs while the listings loaded: focus stays where the person put it.
    var moved by remember { mutableStateOf(false) }
    // A search card's or reminder's programme: open the guide there and put focus on it once it is in.
    var focusOn by remember { mutableStateOf<LiveGuideViewModel.Target?>(null) }
    LaunchedEffect(Unit) {
        model.setSpan(120)
        model.show(true, channels.selectedId)
    }
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        model.open(t)
        focusOn = t
        onTargetShown()
    }
    LaunchedEffect(focusOn, state.selection) {
        val t = focusOn ?: return@LaunchedEffect
        val selection = state.selection ?: return@LaunchedEffect
        if (selection.channel.id != t.channelId || selection.programme.id != t.programmeId && selection.programme.startMs != t.startMs) return@LaunchedEffect
        focusOn = null
        withFrameNanos { }
        memory.requester(cellKey(selection.channel.id, selection.programme.id)).tryFocus()
    }
    val moves = remember(model) {
        TvGuideMoves(model::earlier, model::later, model::pickDay, model::filter) { channel, programme ->
            model.select(channel, programme)
            onSelect(channel)
        }
    }
    val rows = state.rows
    fun toGrid(): Boolean {
        val selection = state.selection
        val fallback = selection?.let { cellKey(it.channel.id, it.programme.id) } ?: rows.firstOrNull()?.let { r -> r.programmes.firstOrNull { it.airingAt(now) }?.let { cellKey(r.channel.id, it.id) } ?: channelKey(r.channel.id) }
        return memory.restore(fallback)
    }
    Column(modifier) {
        LiveTabsHeader(
            LiveTab.Guide, tabs, onNavigate,
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                moved = true
                // ▼ from the tabs and buttons goes back to the programme the grid had focused.
                event.key == Key.DirectionDown && state.guide != null && toGrid()
            },
            first = guideTab,
            onShowing = { toGrid() },
        ) {
            TvButton(state.filterLabel(), { picker = GuidePicker.Channels }, size = TvButtonSize.Small)
            TvButton(state.dayLabel(), { picker = GuidePicker.Day }, size = TvButtonSize.Small, enabled = state.days.size > 1)
            TvButton("Go to channel", onGoTo, size = TvButtonSize.Small)
        }
        Box(Modifier.fillMaxSize()) {
            when {
                state.guide == null && state.error != null -> {
                    val retry = remember { FocusRequester() }
                    LiveStateMessage("The listings didn’t load", state.error.orEmpty(), Modifier.padding(top = 150.dp), note = "Channels still play: choose one with Go to channel.") {
                        TvButton("Try again", model::refresh, Modifier.focusRequester(retry), style = TvButtonStyle.Primary, growFrom = TransformOrigin(0f, 0.5f))
                    }
                    InitialFocus(retry)
                }
                state.guide == null -> {
                    GuideSkeleton()
                    LaunchedEffect(Unit) {
                        withFrameNanos { }
                        if (!moved) guideTab.tryFocus()
                    }
                }
                else -> LiveTvGuide(
                    state, rows, now, format, memory, moves, onWatch,
                    onChoices = { channel, programme -> choices = channel to programme },
                    guideTab = guideTab,
                    personal = personal,
                    canRecord = canRecord,
                )
            }
            Notice(recordingState.notice, recordingActions::clearError)
        }
    }
    picker?.let { open ->
        when (open) {
            GuidePicker.Channels -> OptionsDialog(
                title = "Channels",
                options = if (personal) state.filters else media.watchmy.client.model.LiveGuideFilter.offered(false),
                selected = state.shownFilter,
                label = { it.label },
                onSelect = { moves.onFilter(it); picker = null },
                onDismiss = { picker = null },
                context = "Which channels the guide shows",
            )
            GuidePicker.Day -> OptionsDialog(
                title = "Day",
                options = state.days.indices.toList(),
                selected = state.dayIndex,
                label = { state.days[it].label },
                onSelect = { moves.onDay(it); picker = null },
                onDismiss = { picker = null },
                context = "The guide starts at this day’s first listing",
            )
        }
    }
    choices?.let { (channel, programme) ->
        TvProgrammeChoices(
            channel, programme, state, now, format, recordingActions,
            ProgrammeChoiceActions(
                onRemind = model::remind,
                onForget = model::forget,
                onStar = model::toggleFavourite,
                onWatch = onWatch,
                onRecordings = { onNavigate(TvRoutes.LiveRecordings) },
            ),
            onDismiss = { choices = null },
        )
    }
    recordingState.clash?.let { clash -> TvRecordingClash(clash, recordingActions) }
    // Focus starts on what is on the channel watched last, or comes back to where it was, unless the person has moved since.
    LaunchedEffect(state.guide != null, navigating) {
        if (state.guide == null || navigating || focusOn != null || moved) return@LaunchedEffect
        withFrameNanos { }
        toGrid()
    }
}
