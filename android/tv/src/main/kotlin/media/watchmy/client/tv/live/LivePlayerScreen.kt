package media.watchmy.client.tv.live

import android.view.KeyEvent as AndroidKey
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import media.watchmy.client.model.LiveCastSender
import media.watchmy.client.model.LIVE_TAKEOVER_DONT_RECORD
import media.watchmy.client.model.takeoverKey
import media.watchmy.client.model.takeoverMessage
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LivePlaybackController
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.liveTVError
import media.watchmy.client.model.liveSwitchMessage
import media.watchmy.client.presentation.live.editLiveNumber
import media.watchmy.client.presentation.live.nextLiveChannel
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.ui.components.rememberLiveNow

/**
 * A dedicated TV live surface: no file timeline, progress or next episode.
 * With [cast] it plays a channel cast to this TV (Cast Connect, the
 * LiveCastConnect board): no On now, Quality, Go to channel or Full guide,
 * which are on the phone, and Stop casting in Full guide's place.
 */
@Composable
fun LivePlayerScreen(
    target: LiveTarget,
    channels: List<LiveChannel>,
    onExit: () -> Unit,
    onChannels: () -> Unit,
    modifier: Modifier = Modifier,
    onSelect: (LiveChannel) -> Unit = {},
    controller: LivePlaybackController? = null,
    listings: Boolean = false,
    cast: LiveCastSender? = null,
    onRecordings: () -> Unit = onChannels,
    onPlayRecording: (media.watchmy.client.model.PlayRequest) -> Unit = {},
) {
    val services = LocalAppServices.current
    val viewing = remember(services, controller) { controller ?: services.livePlayback.create() }
    LaunchedEffect(viewing, target) { viewing.start(target) }
    DisposableEffect(viewing) { onDispose { viewing.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewing.stop() }
    LivePlayerContent(viewing, target, channels, onExit, onChannels, onSelect, listings, modifier, cast, onRecordings, onPlayRecording)
}

private class LiveFocus {
    val root = FocusRequester()
    val playPause = FocusRequester()
    val channels = FocusRequester()
    val quality = FocusRequester()
    val number = FocusRequester()
    val panel = FocusRequester()
    val prompt = FocusRequester()
}

@OptIn(UnstableApi::class)
@Composable
private fun LivePlayerContent(
    controller: LivePlaybackController,
    initial: LiveTarget,
    channels: List<LiveChannel>,
    onExit: () -> Unit,
    onChannels: () -> Unit,
    onSelect: (LiveChannel) -> Unit,
    listings: Boolean,
    modifier: Modifier = Modifier,
    cast: LiveCastSender? = null,
    onRecordings: () -> Unit = onChannels,
    onPlayRecording: (media.watchmy.client.model.PlayRequest) -> Unit = {},
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val player by controller.player.collectAsStateWithLifecycle()
    val current = state.target?.channel ?: initial.channel
    // The list's copy carries what is on; the target carries only the channel.
    val shown = channels.firstOrNull { it.id == current.id } ?: current
    val now = rememberLiveNow()
    val format = rememberLiveClockFormat()
    val exit by rememberUpdatedState(onExit)
    val fullGuide by rememberUpdatedState(onChannels)
    val select by rememberUpdatedState(onSelect)
    val focus = remember { LiveFocus() }
    val channelMemory = rememberFocusMemory()
    var controls by remember { mutableStateOf(true) }
    var banner by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<LivePanel?>(null) }
    var numeric by remember { mutableStateOf("") }
    var channelProblem by remember { mutableStateOf<LiveChannel?>(null) }
    var inputTick by remember { mutableIntStateOf(0) }
    var wanted by remember { mutableStateOf<FocusRequester?>(focus.playPause) }
    var treeHasFocus by remember { mutableStateOf(false) }
    var rootFocused by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var backDown by remember { mutableStateOf(false) }
    val consumed = remember { mutableSetOf<Int>() }
    val terminal = state.status in listOf(PlayerStatus.Error, PlayerStatus.Ended)
    // The takeover warning never takes focus, like the reminder banner: Hold OK is Don't record it, Back dismisses it.
    val recorder = rememberTvRecordingActions()
    var dismissedTakeover by remember { mutableStateOf<String?>(null) }
    val takeover = state.session?.takeIf { cast == null && it.takeoverAtMs != null && !it.state.terminal && !terminal && it.takeoverKey() != dismissedTakeover }
    val takeoverKeys = remember(takeover?.takeoverKey(), takeover?.takeoverCanCancel) {
        BannerKeys(
            onWatch = { takeover?.takeoverRecordingId?.let(recorder::cancel) },
            onDismiss = { dismissedTakeover = takeover?.takeoverKey() },
            holdsOk = takeover?.takeoverCanCancel == true,
        )
    }

    fun leave(toGuide: Boolean = false) {
        if (leaving) return
        leaving = true
        controller.stop()
        if (toGuide) fullGuide() else exit()
    }
    fun interact() {
        controller.noteUserInput()
        inputTick++
    }
    fun hideControls() {
        focus.root.requestFocus()
        controls = false
    }
    fun openPanel(next: LivePanel) {
        interact()
        panel = next
        if (next != LivePanel.Number) wanted = focus.panel
    }
    fun closePanel() {
        val closed = panel
        panel = null
        wanted = if (!controls) focus.root else when (closed) {
            LivePanel.Channels -> focus.channels
            LivePanel.Quality -> focus.quality
            LivePanel.Number -> focus.number
            null -> focus.playPause
        }
    }
    fun watch(channel: LiveChannel) {
        if (!channel.available) { channelProblem = channel; return }
        interact()
        panel = null
        controller.start(LiveTarget(channel))
        banner = true
        wanted = if (controls) focus.playPause else focus.root
    }
    fun step(direction: Int) { nextLiveChannel(channels, controller.state.value.target?.channel?.id ?: current.id, direction)?.let(::watch) }
    fun back() {
        val action = liveBackAction(panel, controls, terminal, state.askStillWatching)
        interact()
        when (action) {
            LiveBackAction.ClosePanel -> closePanel()
            LiveBackAction.KeepWatching -> { controller.answerStillWatching(true); controls = true; wanted = focus.playPause }
            LiveBackAction.HideControls -> hideControls()
            LiveBackAction.Leave -> leave()
        }
    }
    fun perform(action: LiveAction) {
        when (action) {
            LiveAction.Controls -> { controls = true; wanted = focus.playPause }
            LiveAction.Channels -> openPanel(LivePanel.Channels)
            LiveAction.Quality -> openPanel(LivePanel.Quality)
            LiveAction.NextChannel -> step(1)
            LiveAction.PreviousChannel -> step(-1)
            LiveAction.TogglePause -> controller.togglePlayPause()
            LiveAction.Play -> controller.play()
            LiveAction.Pause -> controller.pause()
            LiveAction.Leave -> leave()
            LiveAction.Pass, LiveAction.Swallow -> Unit
        }
    }

    BackHandler(enabled = !leaving, onBack = ::back)
    LaunchedEffect(current.id) {
        select(current)
        channelMemory.focused(current.id)
        banner = true
        delay(5_000)
        banner = false
    }
    LaunchedEffect(state.status, state.askStillWatching) {
        if (terminal || state.askStillWatching) {
            panel = null
            wanted = focus.prompt
        } else if (state.status == PlayerStatus.Paused) {
            controls = true
            wanted = focus.playPause
        }
    }
    LaunchedEffect(controls, state.status, state.askStillWatching, panel, inputTick) {
        if (controls && state.status == PlayerStatus.Playing && !state.askStillWatching && panel == null) {
            delay(5_000)
            hideControls()
        }
    }
    LaunchedEffect(wanted, controls, panel, terminal, state.askStillWatching, treeHasFocus, rootFocused, leaving) {
        if (leaving || panel == LivePanel.Number || channelProblem != null) return@LaunchedEffect
        // A prompt can disappear before the controller's asynchronous retry begins.
        // Resolve the requested focus against the surface that is actually composed.
        val destination = if (wanted != null || !treeHasFocus || rootFocused) when {
            terminal || state.askStillWatching -> focus.prompt
            panel != null -> focus.panel
            controls -> wanted?.takeIf { it in listOf(focus.playPause, focus.channels, focus.quality, focus.number) } ?: focus.playPause
            else -> focus.root
        } else null
        if (destination != null) {
            withFrameNanos { }
            destination.requestFocus()
        }
        wanted = null
    }
    val view = LocalView.current
    DisposableEffect(view, state.status) {
        val previous = view.keepScreenOn
        view.keepScreenOn = state.status in listOf(PlayerStatus.Loading, PlayerStatus.Buffering, PlayerStatus.Playing)
        onDispose { view.keepScreenOn = previous }
    }

    Box(
        modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
            if (leaving) return@onPreviewKeyEvent false
            val native = event.nativeKeyEvent
            val code = native.keyCode
            if (takeover != null && takeoverKeys.handle(code, event.type == KeyEventType.KeyDown, native.repeatCount)) return@onPreviewKeyEvent true
            if (code == AndroidKey.KEYCODE_BACK) {
                if (event.type == KeyEventType.KeyDown && native.repeatCount == 0) backDown = true
                if (event.type == KeyEventType.KeyUp && backDown) { backDown = false; back() }
                return@onPreviewKeyEvent true
            }
            if (event.type == KeyEventType.KeyUp) return@onPreviewKeyEvent consumed.remove(code)
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val prompt = controller.state.value.askStillWatching
            inputTick++
            if (prompt && code != AndroidKey.KEYCODE_MEDIA_STOP) {
                // Let the remote reach both prompt buttons. noteUserInput itself
                // dismisses this prompt, so only an explicit answer calls it.
                if (code in listOf(AndroidKey.KEYCODE_DPAD_LEFT, AndroidKey.KEYCODE_DPAD_RIGHT, AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_DPAD_DOWN, AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER, AndroidKey.KEYCODE_TAB)) return@onPreviewKeyEvent false
                controller.answerStillWatching(true)
                controls = true
                wanted = focus.playPause
                consumed += code
                return@onPreviewKeyEvent true
            }
            if (native.repeatCount == 0) controller.noteUserInput()
            val char = liveNumberKey(code)
            // A cast channel changes from the phone's list; the TV keeps only ▲ ▼ and the channel keys.
            if (cast == null && char != null && char in '0'..'9' && panel != LivePanel.Number) {
                numeric = editLiveNumber("", char)
                openPanel(LivePanel.Number)
                consumed += code
                return@onPreviewKeyEvent true
            }
            if (panel == LivePanel.Channels && code == AndroidKey.KEYCODE_DPAD_RIGHT) {
                closePanel()
                consumed += code
                return@onPreviewKeyEvent true
            }
            val action = liveKeyAction(code, panel, controls, terminal, prompt)
                .let { if (cast != null && it in listOf(LiveAction.Channels, LiveAction.Quality)) LiveAction.Swallow else it }
            if (action == LiveAction.Pass) false else {
                consumed += code
                if (native.repeatCount == 0 || action !in listOf(LiveAction.NextChannel, LiveAction.PreviousChannel)) perform(action)
                true
            }
        }.onFocusChanged { treeHasFocus = it.hasFocus; rootFocused = it.isFocused }.focusRequester(focus.root).focusable(),
    ) {
        player?.let { LiveVideo(it) }
        if (player == null) LiveFakeBackdrop(current)
        if (!terminal && !state.askStillWatching) {
            if ((banner || controls) && panel == null) LiveChannelBanner(state, shown, controls, Modifier.align(Alignment.BottomStart), listings = listings, now = now, format = format, cast = cast)
            if (state.status in listOf(PlayerStatus.Loading, PlayerStatus.Buffering)) LiveTuning(state, current, Modifier.align(Alignment.Center))
            if (controls && panel == null) LiveControls(
                state = state, channels = channels, current = current,
                playFocus = focus.playPause, channelFocus = focus.channels, qualityFocus = focus.quality, numberFocus = focus.number,
                onToggle = { interact(); controller.togglePlayPause() }, onLive = { interact(); controller.goLive() },
                onPrevious = { step(-1) }, onNext = { step(1) }, onChannels = { openPanel(LivePanel.Channels) },
                onQuality = { openPanel(LivePanel.Quality) }, onNumber = { numeric = ""; openPanel(LivePanel.Number) },
                onGuide = { leave(toGuide = cast == null) }, modifier = Modifier.align(Alignment.BottomCenter), listings = listings, cast = cast != null,
            )
            val notice = if (cast != null && state.status == PlayerStatus.Paused) castPausedNote(cast) else state.message ?: state.liveSwitchMessage()
            notice?.let { LiveNotice(it, Modifier.align(Alignment.TopCenter).padding(top = 110.dp)) }
            if (panel == LivePanel.Channels) LiveChannelsPanel(channels, current.id, focus.panel, channelMemory, ::watch, ::closePanel, Modifier.align(Alignment.CenterStart), listings = listings, now = now, format = format)
            if (panel == LivePanel.Quality) LiveQualityPanel(state.quality, focus.panel, onSelect = {
                interact(); controller.selectQuality(it); closePanel()
            }, modifier = Modifier.align(Alignment.CenterEnd), automatic = state.automaticAvailable)
        }
        if (cast != null && (terminal || state.askStillWatching)) CastPrompt(
            state = state, channel = current, sender = cast.name, focus = focus.prompt,
            onKeepWatching = {
                interact()
                controller.answerStillWatching(true)
                controls = true
                wanted = focus.playPause
            },
            onRetry = {
                interact()
                controller.retry()
                controls = true
                wanted = focus.playPause
            },
            onStop = { leave() },
        ) else if (terminal || state.askStillWatching) LivePlayerPrompt(
            title = if (state.askStillWatching) "Are you still watching?" else state.error?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: "Live TV stopped",
            message = if (state.askStillWatching) "Nothing has been pressed for four hours. Choose Keep watching, or Stop to release the tuner." else state.error ?: "The tuner was released. Tune again to watch live.",
            action = if (state.askStillWatching) "Keep watching" else if (state.takeoverRecording != null) "Watch from the start" else if (state.reason == "recording") "Open Recordings" else "Tune again",
            onAction = {
                val answering = controller.state.value.askStillWatching
                interact()
                if (state.takeoverRecording != null) state.takeoverRecording?.let(onPlayRecording) else if (state.reason == "recording") onRecordings() else if (answering) controller.answerStillWatching(true) else controller.retry()
                controls = true
                wanted = focus.playPause
            },
            onLeave = { leave(toGuide = true) }, focus = focus.prompt, leaveLabel = if (state.askStillWatching) "Stop" else "Back to channels",
        )
        takeover?.let { session -> TvRecordingTakeover(session, recorder, format, Modifier.align(Alignment.TopCenter).padding(top = 100.dp)) }
        if (panel == LivePanel.Number) ChannelNumberDialog(channels, numeric, ::watch, ::closePanel, onStop = { leave() }, onInteraction = ::interact)
        channelProblem?.let { channel -> LiveMessageDialog("${channel.number} · ${channel.name}", liveTVError(channel.reason), onDismiss = { channelProblem = null; wanted = focus.panel }, onStop = { leave() }) }
    }
}

/**
 * The warning before a recording takes this tuner (PLAN-LIVE-TV-DVR.md §3.1,
 * Taken over). It never takes focus: its keys are read first ([BannerKeys]),
 * Hold OK for Don't record it (whoever set it and admins) and Back to
 * dismiss it. TalkBack reads it out as it appears.
 */
@Composable
private fun TvRecordingTakeover(session: media.watchmy.client.model.LiveSession, recorder: media.watchmy.client.presentation.live.LiveRecordingsViewModel, format: media.watchmy.client.model.LiveClockFormat, modifier: Modifier = Modifier) {
    val state by recorder.state.collectAsStateWithLifecycle()
    val message = session.takeoverMessage(format).orEmpty()
    Column(
        modifier.width(1000.dp).background(Color(0xF2141416), RoundedCornerShape(20.dp)).border(1.dp, WmmColors.Line2, RoundedCornerShape(20.dp)).padding(24.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, style = inter(23.sp), color = WmmColors.Text)
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            if (session.takeoverCanCancel) TvOkHint(LIVE_TAKEOVER_DONT_RECORD, key = "Hold OK")
            TvOkHint("OK", key = "Back")
        }
        state.error?.let { Text(it, style = inter(18.sp), color = WmmColors.Error) }
    }
}
