package media.watchmy.client.tv.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.liveTVError
import media.watchmy.client.presentation.live.LiveChannelsViewModel
import media.watchmy.client.presentation.live.LiveGuideViewModel
import media.watchmy.client.presentation.live.editLiveNumber
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.EmptyState
import media.watchmy.client.tv.components.ErrorState
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.LoadingState
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.ui.components.rememberLiveNow

/**
 * LiveGuide: with listings (API level 13 and `capabilities.guide`) the guide
 * with its hero; without them (listings=none) every available channel remains
 * watchable without programme data, as L7 built it.
 */
@Composable
fun LiveChannelsScreen(
    state: LiveChannelsViewModel.State,
    onSelect: (LiveChannel) -> Unit,
    onWatch: (LiveChannel) -> Unit,
    onRefresh: () -> Unit,
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
    onReminders: () -> Unit = {},
    onRecordings: () -> Unit = {},
    target: LiveGuideViewModel.Target? = null,
    onTargetShown: () -> Unit = {},
) {
    val memory = rememberFocusMemory()
    val grid = rememberLazyGridState()
    val reload = remember { FocusRequester() }
    var number by remember { mutableStateOf<String?>(null) }
    var navigating by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf<LiveChannel?>(null) }
    val channels = state.channels
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
        when {
            state.loading && channels.isEmpty() -> LoadingState()
            state.error != null && channels.isEmpty() -> ErrorState(state.error.orEmpty(), onRefresh, title = "Couldn't load Live TV")
            !state.available -> {
                EmptyState("Live TV isn't available", "This server or account cannot watch Live TV. An admin manages tuners and access in the web app.") {
                    TvButton("Check again", onRefresh, Modifier.focusRequester(reload))
                }
                InitialFocus(reload)
            }
            channels.isEmpty() -> {
                EmptyState("No channels yet", "An admin can refresh the tuner’s lineup and enable channels in the web app.") {
                    TvButton("Reload channels", onRefresh, Modifier.focusRequester(reload))
                }
                InitialFocus(reload)
            }
            state.listings -> TvGuideContent(
                state, memory, onSelect, ::watch, onGoTo = { number = "" }, navigating = navigating, modifier = Modifier.padding(start = ContentStart - 8.dp),
                onReminders = onReminders, onRecordings = onRecordings, target = target, onTargetShown = onTargetShown,
            )
            else -> Column(Modifier.fillMaxSize().padding(start = ContentStart, end = 58.dp, top = 48.dp)) {
                Text("Live TV", style = inter(56.sp, FontWeight.ExtraBold), color = WmmColors.Text)
                Text("Listings aren’t on this server yet. Pick a channel and press OK to watch.", style = inter(21.sp, lineHeight = 29.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("${channels.count { it.available }} channels available", style = inter(20.sp), color = WmmColors.TextMuted, modifier = Modifier.weight(1f))
                    if (state.capabilities.canRecord) TvButton("Recordings", onRecordings, size = TvButtonSize.Medium)
                    TvButton("Reload", onRefresh, size = TvButtonSize.Medium)
                    TvButton("Go to channel", { number = "" }, size = TvButtonSize.Medium)
                }
                state.error?.let { Text(it, style = inter(19.sp), color = WmmColors.Error, modifier = Modifier.padding(bottom = 20.dp)) }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    state = grid,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 58.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    items(channels, key = { it.id }) { channel ->
                        LiveChannelCard(channel, { watch(channel) }, Modifier.focusMemory(memory, channel.id))
                    }
                }
                LaunchedEffect(channels.map { it.id }, state.selectedId, navigating) {
                    if (navigating) return@LaunchedEffect
                    val id = (state.selectedId ?: memory.key)?.takeIf { candidate -> channels.any { it.id == candidate } } ?: channels.firstOrNull()?.id
                    val index = channels.indexOfFirst { it.id == id }
                    if (index >= 0 && id != memory.key) grid.scrollToItem(index)
                    withFrameNanos { }
                    if (id != null) memory.requester(id).requestFocus()
                }
            }
        }
        number?.let { initial -> ChannelNumberDialog(channels, initial, ::watch, onDismiss = { number = null }) }
        unavailable?.let { channel -> LiveMessageDialog("${channel.number} · ${channel.name}", liveTVError(channel.reason), onDismiss = { unavailable = null }) }
    }
}

@Composable
private fun TvGuideContent(
    channels: LiveChannelsViewModel.State,
    memory: FocusMemory,
    onSelect: (LiveChannel) -> Unit,
    onWatch: (LiveChannel) -> Unit,
    onGoTo: () -> Unit,
    navigating: Boolean,
    modifier: Modifier = Modifier,
    onReminders: () -> Unit = {},
    onRecordings: () -> Unit = {},
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
    val extras = remember(model, channels.capabilities.canRecord) { TvGuideExtras(model::toggleFavourite, model::remind, model::forget, onReminders, onRecordings, channels.capabilities.canRecord) }.takeIf { state.personal && channels.extras }
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
        runCatching { memory.requester(cellKey(selection.channel.id, selection.programme.id)).requestFocus() }
    }
    val moves = remember(model) {
        TvGuideMoves(model::earlier, model::later, model::pickDay, model::filter) { channel, programme ->
            model.select(channel, programme)
            onSelect(channel)
        }
    }
    val rows = state.rows
    Box(modifier.fillMaxSize()) {
        when {
            state.guide == null && state.error != null -> ErrorState(state.error.orEmpty(), model::refresh, title = "Couldn’t load listings")
            state.guide == null -> LoadingState()
            else -> LiveTvGuide(state, rows, now, format, memory, moves, onWatch, onGoTo, extras = extras)
        }
        recordingState.notice?.let { Text(it, style = inter(19.sp), color = WmmColors.Accent, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) }
        recordingState.clash?.let { clash -> TvRecordingClash(clash, recordingActions) }
    }
    // Focus starts on what is on the channel watched last, or comes back to where it was.
    LaunchedEffect(state.guide != null, navigating) {
        if (state.guide == null || navigating || focusOn != null) return@LaunchedEffect
        withFrameNanos { }
        val selection = state.selection
        val fallback = selection?.let { cellKey(it.channel.id, it.programme.id) } ?: rows.firstOrNull()?.let { channelKey(it.channel.id) }
        memory.restore(fallback)
    }
}
