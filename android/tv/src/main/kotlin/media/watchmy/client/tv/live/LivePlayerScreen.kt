package media.watchmy.client.tv.live

import android.view.KeyEvent as AndroidKey
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import media.watchmy.client.model.LIVE_TAKEOVER_DONT_RECORD
import media.watchmy.client.model.LiveCastSender
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LivePlaybackController
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.liveSwitchMessage
import media.watchmy.client.model.liveTVError
import media.watchmy.client.model.onAt
import media.watchmy.client.model.takeoverKey
import media.watchmy.client.model.takeoverMessage
import media.watchmy.client.presentation.live.editLiveNumber
import media.watchmy.client.presentation.live.nextLiveChannel
import media.watchmy.client.tv.components.TvClock
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.tv.player.PlayerShell
import media.watchmy.client.tv.player.rememberPlayerShellState
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.ShowsLiveChannels
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.ui.components.rememberLiveNow

/**
 * A dedicated TV live surface: no file timeline, progress or next episode.
 * With [cast] it plays a channel cast to this TV (Cast Connect): no On now,
 * Quality or Guide, which are on the phone, and Stop casting in Guide's place.
 *
 * It opens with the channel's banner and nothing focused, as a player does.
 * With the controls hidden, ▲ ▼ change channel, ◀ opens On now and OK shows
 * the controls with focus on Pause; Back hides the controls, then leaves.
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
    // Now and next on the player (banner, On now, picture in picture) follow the polled channel list.
    ShowsLiveChannels()
    val services = LocalAppServices.current
    val viewing = remember(services, controller) { controller ?: services.livePlayback.create() }
    LaunchedEffect(viewing, target) { viewing.start(target) }
    DisposableEffect(viewing) { onDispose { viewing.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewing.stop() }
    LivePlayerContent(viewing, target, channels, onExit, onChannels, onSelect, listings, modifier, cast, onRecordings, onPlayRecording)
}

private class LiveFocus(val root: FocusRequester) {
    val playPause = FocusRequester()
    val record = FocusRequester()
    val channels = FocusRequester()
    val quality = FocusRequester()
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
    // Back, focus, hiding the controls and keeping the screen on: the frame the file player shares.
    val shell = rememberPlayerShellState()
    val focus = remember { LiveFocus(shell.root) }
    val channelMemory = rememberFocusMemory()
    // The player opens with the banner and nothing focused; OK brings the controls.
    var controls by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<LivePanel?>(null) }
    var numeric by remember { mutableStateOf("") }
    var channelProblem by remember { mutableStateOf<LiveChannel?>(null) }
    // The channel watched before this one, for the remote's Last key.
    var lastChannel by remember { mutableStateOf<LiveChannel?>(null) }
    // A recording under way that Record was pressed on: its choices are open.
    var stopping by remember { mutableStateOf<media.watchmy.client.model.RecordingMark?>(null) }
    val consumed = remember { mutableSetOf<Int>() }
    val terminal = state.status in listOf(PlayerStatus.Error, PlayerStatus.Ended)
    // The takeover warning never takes focus, like the reminder banner: Hold OK is Don't record it, Back dismisses it.
    val recorder = rememberTvRecordingActions()
    val recording by recorder.state.collectAsStateWithLifecycle()
    var dismissedTakeover by remember { mutableStateOf<String?>(null) }
    val takeover = state.session?.takeIf { cast == null && it.takeoverAtMs != null && !it.state.terminal && !terminal && it.takeoverKey() != dismissedTakeover }
    val takeoverKeys = remember(takeover?.takeoverKey(), takeover?.takeoverCanCancel) {
        BannerKeys(
            onWatch = { takeover?.takeoverRecordingId?.let(recorder::cancel) },
            holdsOk = takeover?.takeoverCanCancel == true,
        )
    }
    // How far behind live a paused or rewound channel is, read from the player while it is.
    var behindMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(player, state.atLiveEdge) {
        val p = player
        if (state.atLiveEdge || p == null) {
            behindMs = null
            return@LaunchedEffect
        }
        while (true) {
            behindMs = p.currentLiveOffset.takeIf { it != C.TIME_UNSET && it > 0 }
            delay(1_000)
        }
    }
    // What is on, and whether it records: the mark the polled list carries, or the press just made.
    val onNow = shown.onAt(now)
    val mark = onNow?.recording?.takeIf { it.active }
    val recordable = cast == null && onNow != null && recording.canRecord && onNow.endMs > now
    val recordedHere = onNow != null && recording.noticeProgrammeId == onNow.id

    fun leave(toGuide: Boolean = false) {
        if (!shell.leave()) return
        controller.stop()
        if (toGuide) fullGuide() else exit()
    }
    fun interact() {
        controller.noteUserInput()
        shell.noteInput()
    }
    fun hideControls() {
        focus.root.requestFocus()
        controls = false
    }
    fun openPanel(next: LivePanel) {
        interact()
        panel = next
        if (next != LivePanel.Number) shell.wanted = focus.panel
    }
    fun closePanel() {
        val closed = panel
        panel = null
        shell.wanted = if (!controls) focus.root else when (closed) {
            LivePanel.Channels -> focus.channels
            LivePanel.Quality -> focus.quality
            LivePanel.Number, null -> focus.playPause
        }
    }
    fun watch(channel: LiveChannel) {
        if (!channel.available) { channelProblem = channel; return }
        interact()
        panel = null
        val from = controller.state.value.target?.channel ?: current
        if (from.id != channel.id) lastChannel = from
        controller.start(LiveTarget(channel))
        banner = true
        shell.wanted = if (controls) focus.playPause else focus.root
    }
    fun step(direction: Int) { nextLiveChannel(channels, controller.state.value.target?.channel?.id ?: current.id, direction)?.let(::watch) }
    /** Record: this programme from now, once; on one that records, its choices. */
    fun record() {
        interact()
        val on = shown.onAt(now) ?: return
        if (!recordable) return
        val set = on.recording?.takeIf { it.active }
        if (set != null) stopping = set else if (recording.noticeProgrammeId != on.id) recorder.record(on)
    }
    fun back() {
        val action = liveBackAction(panel, controls, terminal, state.askStillWatching)
        interact()
        when (action) {
            LiveBackAction.ClosePanel -> closePanel()
            LiveBackAction.KeepWatching -> { controller.answerStillWatching(true); controls = true; shell.wanted = focus.playPause }
            LiveBackAction.HideControls -> hideControls()
            // A channel that stopped goes back to the guide, where another can be chosen.
            LiveBackAction.Leave -> leave(toGuide = terminal && cast == null)
        }
    }
    fun perform(action: LiveAction) {
        when (action) {
            LiveAction.Controls -> { controls = true; shell.wanted = focus.playPause }
            LiveAction.Channels -> openPanel(LivePanel.Channels)
            LiveAction.Quality -> openPanel(LivePanel.Quality)
            LiveAction.NextChannel -> step(1)
            LiveAction.PreviousChannel -> step(-1)
            LiveAction.TogglePause -> controller.togglePlayPause()
            LiveAction.Play -> controller.play()
            LiveAction.Pause -> controller.pause()
            LiveAction.Leave -> leave()
            LiveAction.LastChannel -> lastChannel?.let(::watch)
            // A cast channel's guide is on the phone that cast it.
            LiveAction.Guide -> if (cast == null) leave(toGuide = true)
            LiveAction.Record -> record()
            LiveAction.Pass, LiveAction.Swallow -> Unit
        }
    }

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
            shell.wanted = focus.prompt
        } else if (state.status == PlayerStatus.Paused) {
            controls = true
            shell.wanted = focus.playPause
        }
    }
    fun onKey(event: KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        val code = native.keyCode
        if (takeover != null && takeoverKeys.handle(code, event.type == KeyEventType.KeyDown, native.repeatCount)) return true
        if (event.type == KeyEventType.KeyUp) return consumed.remove(code)
        if (event.type != KeyEventType.KeyDown) return false
        val prompt = controller.state.value.askStillWatching
        shell.noteInput()
        if (prompt && code != AndroidKey.KEYCODE_MEDIA_STOP) {
            // Let the remote reach both prompt buttons. noteUserInput itself
            // dismisses this prompt, so only an explicit answer calls it.
            if (code in listOf(AndroidKey.KEYCODE_DPAD_LEFT, AndroidKey.KEYCODE_DPAD_RIGHT, AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_DPAD_DOWN, AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER, AndroidKey.KEYCODE_TAB)) return false
            controller.answerStillWatching(true)
            controls = true
            shell.wanted = focus.playPause
            consumed += code
            return true
        }
        if (native.repeatCount == 0) controller.noteUserInput()
        val char = liveNumberKey(code)
        // A cast channel changes from the phone's list; the TV keeps only ▲ ▼ and the channel keys.
        if (cast == null && char != null && char in '0'..'9' && panel != LivePanel.Number) {
            numeric = editLiveNumber("", char)
            openPanel(LivePanel.Number)
            consumed += code
            return true
        }
        if (panel == LivePanel.Channels && code == AndroidKey.KEYCODE_DPAD_RIGHT) {
            closePanel()
            consumed += code
            return true
        }
        val action = liveKeyAction(code, panel, controls, terminal, prompt)
            .let { if (cast != null && it in listOf(LiveAction.Channels, LiveAction.Quality)) LiveAction.Swallow else it }
        if (action == LiveAction.Pass) return false
        consumed += code
        if (native.repeatCount == 0 || action !in listOf(LiveAction.NextChannel, LiveAction.PreviousChannel)) perform(action)
        return true
    }

    PlayerShell(
        shell,
        keepScreenOn = state.status in listOf(PlayerStatus.Loading, PlayerStatus.Buffering, PlayerStatus.Playing),
        autoHide = controls && state.status == PlayerStatus.Playing && !state.askStillWatching && panel == null,
        onHide = ::hideControls,
        // Back dismisses the takeover warning first, as the reminder banner's does.
        onBack = { if (takeover != null) dismissedTakeover = takeover.takeoverKey() else back() },
        onKey = ::onKey,
        // A prompt can disappear before the controller's asynchronous retry begins.
        // Resolve the requested focus against the surface that is actually composed.
        focusTarget = { wanted ->
            if (wanted != null || !shell.treeHasFocus || shell.rootFocused) when {
                terminal || state.askStillWatching -> focus.prompt
                panel != null -> focus.panel
                controls -> wanted?.takeIf { it in listOf(focus.playPause, focus.record, focus.channels, focus.quality) } ?: focus.playPause
                else -> focus.root
            } else null
        },
        focusKeys = arrayOf<Any?>(controls, panel, terminal, state.askStillWatching),
        // Go to channel, an unavailable channel's message and a recording's choices are dialogs that hold focus themselves.
        arbitrate = panel != LivePanel.Number && channelProblem == null && stopping == null,
        modifier = modifier,
    ) {
        player?.let { LiveVideo(it) }
        if (player == null) LiveFakeBackdrop(current)
        if (!terminal && !state.askStillWatching) {
            val chrome = (banner || controls) && panel == null
            if (chrome) {
                LiveChromeScrim(Modifier.align(Alignment.BottomStart))
                TvClock()
                LiveChannelBanner(state, shown, controls, Modifier.align(Alignment.BottomStart), listings = listings, now = now, format = format, cast = cast, behindMs = behindMs)
            }
            if (state.status in listOf(PlayerStatus.Loading, PlayerStatus.Buffering)) LiveTuning(state, current, Modifier.align(Alignment.Center))
            if (controls && panel == null) LiveControls(
                state = state, channels = channels, current = current,
                playFocus = focus.playPause, channelFocus = focus.channels, qualityFocus = focus.quality,
                onToggle = { interact(); controller.togglePlayPause() }, onLive = { interact(); controller.goLive() },
                onPrevious = { step(-1) }, onNext = { step(1) }, onChannels = { openPanel(LivePanel.Channels) },
                onQuality = { openPanel(LivePanel.Quality) },
                onGuide = { leave(toGuide = cast == null) }, modifier = Modifier.align(Alignment.BottomCenter), listings = listings, cast = cast != null,
                record = if (recordable) LiveRecordButton(recording = mark != null || recordedHere, busy = recording.busy, onClick = ::record) else null,
                recordFocus = focus.record,
            )
            val notice = if (cast != null && state.status == PlayerStatus.Paused) castPausedNote(cast) else state.message ?: state.liveSwitchMessage()
            notice?.let { LiveNotice(it, Modifier.align(Alignment.TopCenter).padding(top = 110.dp)) }
            if (panel == LivePanel.Channels) LiveChannelsPanel(channels, current.id, focus.panel, channelMemory, ::watch, ::closePanel, listings = listings, now = now, format = format)
            if (panel == LivePanel.Quality) LiveQualityPanel(state.quality, focus.panel, onSelect = {
                interact(); controller.selectQuality(it); closePanel()
            }, automatic = state.automaticAvailable)
        }
        if (cast != null && (terminal || state.askStillWatching)) CastPrompt(
            state = state, channel = current, sender = cast.name, focus = focus.prompt,
            onKeepWatching = {
                interact()
                controller.answerStillWatching(true)
                controls = true
                shell.wanted = focus.playPause
            },
            onRetry = {
                interact()
                controller.retry()
                controls = true
                shell.wanted = focus.playPause
            },
            onStop = { leave() },
        ) else if (terminal || state.askStillWatching) {
            val problem = liveProblem(state, current, state.session?.takeoverTitle)
            LivePlayerProblem(
                problem, current,
                onAction = {
                    interact()
                    when (problem.action) {
                        ProblemAction.WatchRecording -> state.takeoverRecording?.let(onPlayRecording)
                        ProblemAction.OpenRecordings -> onRecordings()
                        ProblemAction.KeepWatching -> controller.answerStillWatching(true)
                        ProblemAction.Retry -> controller.retry()
                    }
                    controls = true
                    shell.wanted = focus.playPause
                },
                onLeave = { leave(toGuide = !state.askStillWatching) },
                focus = focus.prompt,
            )
        }
        takeover?.let { session -> TvRecordingTakeover(session, recorder, format, Modifier.align(Alignment.TopCenter).padding(top = 100.dp)) }
        stopping?.let { set ->
            TvLiveMenu(
                title = onNow?.title ?: "Recording", subtitle = "Recording now on ${shown.number}", logo = null,
                items = listOfNotNull(
                    TvMenuItem("Keep recording", media.watchmy.client.ui.components.WmmIcons.Record) { stopping = null },
                    if (set.canCancel) TvMenuItem("Stop recording · keep what’s recorded", media.watchmy.client.ui.components.WmmIcons.Close) { recorder.cancel(set.id); stopping = null } else null,
                ),
                onDismiss = { stopping = null; shell.wanted = focus.record },
                note = if (set.canCancel) null else "Someone else set this recording, so only they or an admin can stop it.",
            )
        }
        if (panel == LivePanel.Number) ChannelNumberDialog(channels, numeric, ::watch, ::closePanel, onStop = { leave() }, onInteraction = ::interact)
        channelProblem?.let { channel -> LiveMessageDialog("${channel.number} · ${channel.name}", liveTVError(channel.reason), onDismiss = { channelProblem = null; shell.wanted = focus.panel }, onStop = { leave() }) }
    }
}

/**
 * The warning before a recording takes this tuner.
 * It never takes focus: its keys are read first ([BannerKeys]),
 * Hold OK for Don't record it (whoever set it and admins) and Back to
 * dismiss it. TalkBack reads it out as it appears.
 */
@Composable
private fun TvRecordingTakeover(session: media.watchmy.client.model.LiveSession, recorder: media.watchmy.client.presentation.live.LiveRecordingsViewModel, format: media.watchmy.client.model.LiveClockFormat, modifier: Modifier = Modifier) {
    val state by recorder.state.collectAsStateWithLifecycle()
    val message = session.takeoverMessage(format).orEmpty()
    Column(
        modifier.width(1100.dp).background(Color(0xF2141416), RoundedCornerShape(22.dp)).border(2.dp, WmmColors.Warn, RoundedCornerShape(22.dp)).padding(28.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(message, style = TvType.Body, color = WmmColors.Text)
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            KeyHints(listOfNotNull(KeyHint("Hold OK", LIVE_TAKEOVER_DONT_RECORD).takeIf { session.takeoverCanCancel }, KeyHint("Back", "OK")))
        }
        state.error?.let { Text(it, style = TvType.Second, color = TvPalette.Muted) }
    }
}
