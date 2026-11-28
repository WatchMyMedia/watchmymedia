package media.watchmy.client.tv.player

import media.watchmy.client.model.at
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.SoundSetting
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.model.PlayerEvent
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.SubtitleRequestMode
import media.watchmy.client.model.tracks
import media.watchmy.client.presentation.player.PlayerEpisodesViewModel
import media.watchmy.client.presentation.player.PlayerViewModel
import media.watchmy.client.tv.components.PanelWidth
import media.watchmy.client.tv.components.SafeX
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvClock
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.CaptionLook
import media.watchmy.client.ui.components.SubtitleCues
import media.watchmy.client.ui.components.at
import media.watchmy.client.ui.components.playerSubtitleSize
import media.watchmy.client.ui.playerKey

/**
 * The TV player: the controls with Skip intro, Up next, and the panels for
 * Audio & subtitles, Quality and Playback info. It renders a
 * [PlaybackController]; the controller does the rest.
 *
 * Opened from the app, the play is held on the player's nav entry by a
 * [PlayerViewModel]: a recreated activity finds
 * it still playing, and after process death it comes back at the same file
 * (a later episode, if the play had moved on), place and tracks. Leaving the
 * entry releases it, which reports the play stopped.
 */
@Composable
fun PlayerScreen(
    request: PlayRequest,
    onExit: () -> Unit,
    /** A player made elsewhere (a play cast to this TV); the screen still stops and releases it when it goes. */
    controller: PlaybackController? = null,
) {
    if (controller != null) {
        CastPlayer(request, controller, onExit)
        return
    }
    val services = LocalAppServices.current
    val model = viewModel { PlayerViewModel(services.playback, createSavedStateHandle(), request) }
    val local = remember(model) { model.controller() }
    val selection by model.selection.collectAsStateWithLifecycle()
    LaunchedEffect(local, selection) { model.start(local) }
    // The list follows later plays itself (playing()); it loads once, for the play the entry started with or came back to.
    val first = remember(model) { selection.request }
    PlayerFrame(local, first, onPlay = model::select, onLeave = { reportStop ->
        if (reportStop) local.stop()
        onExit()
    })
}

/**
 * A play cast to this TV (Cast Connect), with the player the cast receiver
 * made: the receiver keeps the play, so the screen has no nav entry to hold
 * it on. One viewing; it stops and releases the player when it goes.
 */
@Composable
private fun CastPlayer(request: PlayRequest, controller: PlaybackController, onExit: () -> Unit) {
    val viewing = remember(controller) { Viewing(controller) }
    LaunchedEffect(viewing, request) { controller.start(request) }
    DisposableEffect(viewing) { onDispose { viewing.close() } }
    PlayerFrame(controller, request, onPlay = { controller.start(it) }, onLeave = { reportStop ->
        viewing.finish(reportStop)
        onExit()
    })
}

/** The player on [controller], with the episode list for [request]'s show. */
@Composable
private fun PlayerFrame(controller: PlaybackController, request: PlayRequest, onPlay: (PlayRequest) -> Unit, onLeave: (reportStop: Boolean) -> Unit) {
    val services = LocalAppServices.current
    // The episode list beside the video (the Episodes panel); a recording, or a film, has none.
    // Another server's copy: its episodes and pictures are that server's.
    val there = remember(request.serverId) { services.library.at(request.serverId) ?: services.library }
    val episodes = viewModel(key = "tv-player-episodes-${request.serverId}") { PlayerEpisodesViewModel(there) }
    LaunchedEffect(episodes, request) { if (request.recordingId == null) episodes.load(request) }
    val server by services.servers.current.collectAsStateWithLifecycle()
    val leave by rememberUpdatedState(onLeave)
    // Leaving the app (Home, another app) pauses; the controller reports it.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.pause() }
    MockupScale {
        PlayerContent(
            controller = controller,
            request = request,
            imageUrl = { path, size -> there.imageUrl(path, size) },
            serverName = server?.server?.name,
            episodes = episodes,
            onPlay = onPlay,
            onLeave = { leave(it) },
        )
    }
}

/**
 * One viewing of a cast play. The viewer leaving reports "stopped" once
 * ([finish] with reportStop); when the controller ended the play itself
 * ([PlayerEvent.Exit], an admin's stop) it has reported already. [close]
 * runs when the screen goes.
 */
private class Viewing(val controller: PlaybackController) {
    private var finished = false

    fun finish(reportStop: Boolean) {
        if (finished) return
        finished = true
        if (reportStop) controller.stop()
    }

    fun close() {
        finish(reportStop = true)
        controller.release()
    }
}

private class PlayerFocus(val root: FocusRequester) {
    val skip = FocusRequester()
    val scrubber = FocusRequester()
    val playPause = FocusRequester()
    val upNext = FocusRequester()
    val prompt = FocusRequester()
    private val tracksButton = FocusRequester()

    /** Each panel's button; Audio and Subtitles share Audio & subtitles. */
    val openers = PickerSection.entries.associateWith { if (it.panel == PickerSection.Audio) tracksButton else FocusRequester() }
    val episodes = FocusRequester()

    /** Where focus lands in a panel: the section's chosen row, or Playback info's Close. */
    val picker = PickerSection.entries.associateWith { FocusRequester() }
}

@Composable
private fun PlayerContent(
    controller: PlaybackController,
    request: PlayRequest,
    imageUrl: (String?, ImageSize) -> String?,
    onLeave: (reportStop: Boolean) -> Unit,
    serverName: String? = null,
    episodes: PlayerEpisodesViewModel? = null,
    /** Plays an episode chosen from the list, or the same file again after a problem. */
    onPlay: (PlayRequest) -> Unit = controller::start,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    // Save for everyone in Audio & subtitles' timing is for admins.
    val admin = LocalAppServices.current.auth.user.collectAsStateWithLifecycle().value?.isAdmin == true
    // Back, focus, hiding the controls and keeping the screen on: the frame Live TV's player shares.
    val shell = rememberPlayerShellState()
    val focus = remember { PlayerFocus(shell.root) }
    val seek = remember { SeekState() }
    // Read only by the scrubber and the clocks, through these lambdas, so its ticks recompose only them.
    val progress = controller.progress.collectAsStateWithLifecycle()
    val readProgress = { progress.value }
    val shownMs = { seek.displayed(progress.value.positionMs) }
    var controlsVisible by remember { mutableStateOf(true) }
    var picker by remember { mutableStateOf<PickerSection?>(null) }
    // A sound row's small list in Audio & subtitles: Back goes back to the panel's rows first.
    var soundChoice by remember { mutableStateOf<SoundSetting?>(null) }
    var pickerOpenedFromControls by remember { mutableStateOf(false) }
    val episodeList = episodes?.state?.collectAsStateWithLifecycle()?.value
    var episodesOpen by remember { mutableStateOf(false) }
    var episodesOpenedFromControls by remember { mutableStateOf(false) }
    val episodesFocus = remember { EpisodesFocus() }
    // "N min in" for the playing episode, a minute at a time, so the list redraws once a minute while it plays.
    val minutesIn by remember { derivedStateOf { progress.value.positionMs / 60_000 } }
    var area by remember { mutableStateOf(FocusArea.Root) }
    val consumedKeys = remember { mutableSetOf<Int>() }
    val onLeaveNow by rememberUpdatedState(onLeave)
    val onPlayNow by rememberUpdatedState(onPlay)
    // The file playing now: the request's, or a later episode's once the play moved on.
    var playingFile by remember { mutableLongStateOf(request.fileId) }
    // Tries at a lost server since it last played, which space the next one out.
    var retries by remember { mutableIntStateOf(0) }

    fun leave(reportStop: Boolean) {
        if (shell.leave()) onLeaveNow(reportStop)
    }

    val overlay = overlayOf(state, picker, episodesOpen)
    // Skip makes way for a seek thumbnail while the remote seeks.
    val seeking by remember { derivedStateOf { seek.targetMs != null } }
    val skipShown = state.skip != null && overlay == Overlay.None && !(seeking && state.trickplay != null)
    val chromeVisible = controlsVisible && overlay == Overlay.None

    fun hideControls() {
        if (area != FocusArea.Skip && area != FocusArea.Root) focus.root.requestFocus()
        controlsVisible = false
    }

    fun toScrubber() {
        controlsVisible = true
        shell.wanted = focus.scrubber
    }

    fun openPicker(section: PickerSection) {
        val tracks = controller.state.value.tracks
        val start = when (section) {
            PickerSection.Info, PickerSection.Quality -> section
            PickerSection.Subtitles -> if (offersSubtitles(tracks)) section else if (tracks.audio.isNotEmpty()) PickerSection.Audio else return
            PickerSection.Audio -> if (tracks.audio.isNotEmpty()) section else if (offersSubtitles(tracks)) PickerSection.Subtitles else return
        }
        pickerOpenedFromControls = controlsVisible && area == FocusArea.Buttons
        picker = start
        shell.wanted = focus.picker.getValue(start)
    }

    fun closePicker() {
        val opened = picker ?: return
        picker = null
        soundChoice = null
        controlsVisible = pickerOpenedFromControls
        shell.wanted = if (pickerOpenedFromControls) focus.openers.getValue(opened) else null
    }

    fun openEpisodes() {
        // The list as it is now: the controls' actions are made once, before it has loaded.
        if (episodes?.state?.value?.available != true) return
        episodesOpenedFromControls = controlsVisible && area == FocusArea.Buttons
        episodesOpen = true
        shell.wanted = episodesFocus.panel
    }

    fun closeEpisodes() {
        if (!episodesOpen) return
        episodesOpen = false
        controlsVisible = episodesOpenedFromControls
        shell.wanted = if (episodesOpenedFromControls) focus.episodes else null
    }

    /** Plays [episode] from the list; the one playing goes back to the video where it is. */
    fun playEpisode(episode: EpisodeDetail) {
        val list = episodes ?: return
        if (episode.id == list.state.value.playing?.id) {
            closeEpisodes()
            return
        }
        val next = list.playRequest(episode) ?: return
        episodesOpen = false
        picker = null
        onPlayNow(next)
        list.playing(next.fileId)
        playingFile = next.fileId
        controlsVisible = true
        shell.wanted = focus.playPause
    }

    /** The file playing now, again, from where it stopped, with the tracks it had once it had loaded. */
    fun retry() {
        val s = controller.state.value
        val position = controller.progress.value.positionMs
        val base = if (playingFile == request.fileId) request else PlayRequest(playingFile, title = s.title, subtitle = s.subtitle, backdropPath = s.backdropPath)
        val again = if (s.audio.isEmpty() && s.subtitles.isEmpty()) {
            base.copy(startMs = position.takeIf { it > 0 } ?: base.startMs)
        } else {
            base.copy(
                startMs = position,
                audioIndex = s.selectedAudio,
                subtitleId = s.selectedSubtitle,
                subtitleMode = if (s.selectedSubtitle == null) SubtitleRequestMode.Off else SubtitleRequestMode.Auto,
            )
        }
        retries++
        onPlayNow(again)
    }

    fun seekStep(forward: Boolean, nowMs: Long, repeat: Boolean) {
        val p = controller.progress.value
        seek.press(forward, p.positionMs, p.durationMs, nowMs, repeat)
    }

    fun finishMessage() = leave(controller.state.value.stopMessage == null)

    fun perform(action: KeyAction, nowMs: Long, repeat: Boolean) {
        val current = overlayOf(controller.state.value, picker, episodesOpen)
        when (action) {
            KeyAction.ShowControls -> controlsVisible = true
            KeyAction.ToScrubber -> toScrubber()
            is KeyAction.Seek -> {
                seekStep(action.forward, nowMs, repeat)
                // Under Up next the controls show once a seek back takes the card away.
                controlsVisible = true
                if (current == Overlay.None && area != FocusArea.Scrubber) shell.wanted = focus.scrubber
            }
            KeyAction.TogglePlayPause -> {
                controller.togglePlayPause()
                if (current == Overlay.None) controlsVisible = true
            }
            KeyAction.Play -> controller.play()
            KeyAction.Pause -> {
                controller.pause()
                if (current == Overlay.None) controlsVisible = true
            }
            KeyAction.PlayNext -> controller.playNext()
            is KeyAction.OpenPicker -> openPicker(action.section)
            KeyAction.Leave -> if (current == Overlay.Message) finishMessage() else leave(true)
            KeyAction.Swallow, KeyAction.PassThrough -> Unit
        }
    }

    fun onBack() {
        controller.noteUserInput()
        shell.noteInput()
        if (soundChoice != null && picker != null) {
            soundChoice = null
            return
        }
        val current = overlayOf(controller.state.value, picker, episodesOpen)
        when (backAction(current, controlsVisible, area)) {
            BackAction.Leave -> if (current == Overlay.Message) finishMessage() else leave(true)
            BackAction.StopWatching -> controller.answerStillWatching(false)
            BackAction.ClosePicker -> closePicker()
            BackAction.CloseEpisodes -> closeEpisodes()
            BackAction.WatchCredits -> controller.watchCredits()
            BackAction.HideControls -> hideControls()
            BackAction.ToScrubber -> toScrubber()
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        // The remote's Previous plays the episode before this one in its season (Next is routed below).
        if (native.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            val over = overlayOf(controller.state.value, picker, episodesOpen)
            if (event.type == KeyEventType.KeyDown && native.repeatCount == 0 && (over == Overlay.None || over == Overlay.UpNext)) {
                controller.noteUserInput()
                shell.noteInput()
                episodes?.previous()?.let(::playEpisode)
            }
            return event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
        }
        val key = playerKey(native.keyCode)
        if (event.type == KeyEventType.KeyUp) {
            if (!consumedKeys.remove(native.keyCode)) return false
            if (seek.holding) seek.release()
            return true
        }
        if (event.type != KeyEventType.KeyDown) return false
        val repeat = native.repeatCount > 0
        if (!repeat) controller.noteUserInput()
        shell.noteInput()
        val action = routeKey(key, overlayOf(controller.state.value, picker, episodesOpen), controlsVisible, area, hasNext = controller.state.value.nextEpisode != null)
        if (action == KeyAction.PassThrough) return false
        consumedKeys += native.keyCode
        perform(action, native.eventTime, repeat)
        return true
    }

    LaunchedEffect(controller) {
        controller.events.collect { event ->
            when (event) {
                // An admin's stop shows its message first; OK (or Back) leaves.
                PlayerEvent.Exit -> if (controller.state.value.stopMessage == null) leave(false)
                is PlayerEvent.StartedNext -> {
                    episodes?.playing(event.fileId)
                    playingFile = event.fileId
                    episodesOpen = false
                    picker = null
                    controlsVisible = true
                }
            }
        }
    }

    // Overlays take focus when they open; Up next replaces the controls.
    LaunchedEffect(overlay) {
        when (overlay) {
            Overlay.UpNext -> {
                controlsVisible = false
                shell.wanted = focus.upNext
            }
            Overlay.StillWatching, Overlay.Message -> shell.wanted = focus.prompt
            Overlay.Picker, Overlay.Episodes, Overlay.None -> Unit
        }
    }

    // Playing again puts the lost server's wait back to its shortest.
    LaunchedEffect(state.status) { if (state.status == PlayerStatus.Playing) retries = 0 }

    // The Skip button takes focus when it appears.
    val skipSegment = state.skip?.segment
    LaunchedEffect(skipSegment) {
        if (skipSegment != null && overlayOf(controller.state.value, picker, episodesOpen) == Overlay.None) shell.wanted = focus.skip
    }

    // A release's seek goes once the remote has been quiet a moment: a run of presses is one seek.
    LaunchedEffect(seek.releases) {
        if (seek.releases == 0) return@LaunchedEffect
        delay(SeekState.SEND_AFTER_MS)
        seek.send()?.let { controller.seekTo(it) }
    }

    // After a seek is sent, keep showing its target until the player gets there.
    LaunchedEffect(seek.targetMs, seek.holding) {
        if (seek.targetMs != null && !seek.holding) {
            withTimeoutOrNull(SeekState.SETTLE_TIMEOUT_MS) { controller.progress.first { seek.landed(it.positionMs) } }
            seek.settle()
        }
    }

    // Kept across the state's updates (the countdowns, the Skip button's fill), so the buttons skip recomposing.
    val chromeFocus = remember(skipShown) {
        ChromeFocus(skip = if (skipShown) focus.skip else null, scrubber = focus.scrubber, playPause = focus.playPause, openers = focus.openers, episodes = focus.episodes)
    }
    // The actions read the screen's state when they run, so one set does.
    val chromeActions = remember {
        ChromeActions(
            onTogglePlay = { controller.togglePlayPause() },
            onOpenPicker = { openPicker(it) },
            onPlayNext = { controller.playNext() },
            onFocusArea = { area = it },
            onSeekTo = { controller.seekTo(it) },
            onOpenEpisodes = { openEpisodes() },
        )
    }

    PlayerShell(
        shell,
        keepScreenOn = state.status == PlayerStatus.Playing || state.status == PlayerStatus.Buffering || state.status == PlayerStatus.Loading,
        autoHide = controlsVisible && overlay == Overlay.None && state.status == PlayerStatus.Playing && !seek.holding,
        onHide = ::hideControls,
        onBack = ::onBack,
        onKey = ::onKey,
        // Focus always rests somewhere that takes the remote: what an action asked
        // for, else the open overlay, the Skip button, the scrubber, or the screen.
        focusTarget = { wanted ->
            wanted ?: when {
                overlay != Overlay.None -> if (shell.treeHasFocus && !shell.rootFocused) null else overlayFocus(overlay, picker, focus, episodesFocus)
                !shell.treeHasFocus -> when {
                    skipShown -> focus.skip
                    controlsVisible -> focus.scrubber
                    else -> focus.root
                }
                shell.rootFocused && controlsVisible -> focus.scrubber
                else -> null
            }
        },
        focusKeys = arrayOf<Any?>(controlsVisible, overlay, skipShown),
        onFocusChanged = { if (it.isFocused || !it.hasFocus) area = FocusArea.Root },
    ) {
        VideoLayer(
            controller, state, imageUrl, raisedSubtitles = chromeVisible || overlay == Overlay.UpNext,
            // Audio & subtitles from the right: the cues move left of it, where the timing can be seen.
            besidePanel = overlay == Overlay.Picker && picker?.panel == PickerSection.Audio,
        )

        AnimatedVisibility(chromeVisible, Modifier.fillMaxSize(), enter = fadeIn(tween(150)), exit = fadeOut(tween(250))) {
            Box(Modifier.fillMaxSize()) { PlayerChrome(state, shownMs, readProgress, chromeFocus, chromeActions, seek, episodes = episodeList?.playingCode?.takeIf { episodeList.available }) }
        }

        // What is playing and the clock stay up over Up next and the panels, as they are over the controls.
        if (overlay == Overlay.UpNext || overlay == Overlay.Picker || overlay == Overlay.Episodes) {
            Box(Modifier.fillMaxSize()) {
                ChromeShades(bottom = false)
                TitleBlock(state, Modifier.align(Alignment.TopStart).padding(start = SafeX, top = SafeY))
                TvClock()
            }
        }

        val skip = state.skip
        if (skip != null && skipShown) {
            Entering(fadeIn(tween(200)), Modifier.align(Alignment.TopEnd).padding(top = SkipTop, end = SafeX)) {
                SkipButton(
                    skip,
                    onClick = { controller.skip() },
                    modifier = Modifier
                        .focusRequester(focus.skip)
                        .onFocusChanged { if (it.isFocused) area = FocusArea.Skip },
                )
            }
        }

        val upNext = state.upNext
        if (upNext != null && overlay == Overlay.UpNext) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(460.dp).background(Brush.verticalGradient(listOf(Color.Transparent, UpNextShade))))
            Entering(fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 4 }, Modifier.align(Alignment.BottomEnd).padding(end = SafeX, bottom = SafeY)) {
                NextEpisodeCard(
                    upNext,
                    stillUrl = imageUrl(upNext.next.stillPath, ImageSize.W780),
                    onPlayNow = { controller.playNext() },
                    onWatchCredits = { controller.watchCredits() },
                    playFocus = focus.upNext,
                )
            }
        }

        if (state.status == PlayerStatus.Loading || state.status == PlayerStatus.Buffering) Spinner(Modifier.align(Alignment.Center))

        // Automatic's note: over the video, with or without the controls, and never focused.
        val note = state.note
        if (note != null && overlay != Overlay.Picker && overlay != Overlay.Episodes && overlay != Overlay.Message) {
            Entering(fadeIn(tween(200)), Modifier.align(Alignment.TopCenter).padding(top = 150.dp)) {
                AutoNote(note)
            }
        }

        val error = state.error
        if (error != null && state.status != PlayerStatus.Error && overlay != Overlay.Message) {
            ErrorBanner(error, Modifier.align(Alignment.TopCenter).padding(top = 150.dp))
        }

        val open = picker
        if (overlay == Overlay.Picker && open != null) {
            if (open == PickerSection.Info) {
                Entering(fadeIn(tween(180))) {
                    PlaybackInfoPanel(state.tracks, serverName, readProgress, closeFocus = focus.picker.getValue(PickerSection.Info), onClose = ::closePicker)
                }
            } else {
                Entering(fadeIn(tween(180))) {
                    PickerSheet(
                        state.tracks, open, focus.picker, onChoose = { choose(controller, it) }, extras = pickerExtras(controller, admin),
                        choosing = soundChoice, onChoosing = { soundChoice = it },
                    )
                }
            }
        }

        if (overlay == Overlay.Episodes && episodeList != null && episodes != null) {
            Entering(fadeIn(tween(180))) {
                EpisodesPanel(
                    episodeList, episodesFocus, imageUrl,
                    positionMinutes = { minutesIn },
                    onSeason = episodes::selectSeason,
                    onPlay = ::playEpisode,
                    onRetry = episodes::retry,
                )
            }
        }

        if (overlay == Overlay.StillWatching || overlay == Overlay.Message) {
            val problem = problemOf(state, serverName, progress.value.positionMs)
            if (problem != null) {
                ProblemFor(
                    problem,
                    state,
                    retries = retries,
                    firstFocus = focus.prompt,
                    onRetry = ::retry,
                    onLeave = ::finishMessage,
                    onKeepWatching = { controller.answerStillWatching(true) },
                    onStopWatching = { controller.answerStillWatching(false) },
                )
            }
        }
    }
}

/** Where Skip intro sits: at the right, over the scrubber. */
private val SkipTop = 720.dp

private val UpNextShade = Color(0xE6070708)

/**
 * The problem screen for [problem], with its buttons: Try now and Stop for
 * a lost server, which also tries again by itself after a wait that grows
 * with each try ([retries]); Try again and Back for a file that can't
 * play; Back for an admin's stop; Keep watching and Stop for Still watching.
 */
@Composable
private fun ProblemFor(
    problem: Problem,
    state: PlayerUiState,
    retries: Int,
    firstFocus: FocusRequester,
    onRetry: () -> Unit,
    onLeave: () -> Unit,
    onKeepWatching: () -> Unit,
    onStopWatching: () -> Unit,
) {
    val back = "Back to ${state.title.ifEmpty { "the library" }}"
    var countdown by remember { mutableIntStateOf(0) }
    if (problem.kind == ProblemKind.Lost) {
        val retryNow by rememberUpdatedState(onRetry)
        LaunchedEffect(retries) {
            countdown = retryDelaySeconds(retries)
            while (countdown > 0) {
                delay(1_000)
                countdown--
            }
            retryNow()
        }
    }
    val (note, buttons) = when (problem.kind) {
        ProblemKind.Lost -> "Trying again in $countdown ${if (countdown == 1) "second" else "seconds"}." to listOf(ProblemButton("Try now", onRetry), ProblemButton("Stop", onLeave))
        ProblemKind.CantPlay -> problem.note to listOf(ProblemButton("Try again", onRetry), ProblemButton(back, onLeave))
        ProblemKind.Stopped -> problem.note to listOf(ProblemButton(back, onLeave))
        ProblemKind.StillWatching -> problem.note to listOf(ProblemButton("Keep watching", onKeepWatching), ProblemButton("Stop", onStopWatching))
    }
    ProblemScreen(state, problem.title, problem.body, note, buttons, firstFocus)
}

private fun overlayFocus(overlay: Overlay, picker: PickerSection?, focus: PlayerFocus, episodes: EpisodesFocus): FocusRequester? = when (overlay) {
    Overlay.Episodes -> episodes.panel
    Overlay.UpNext -> focus.upNext
    Overlay.StillWatching, Overlay.Message -> focus.prompt
    Overlay.Picker -> picker?.let { focus.picker.getValue(it) }
    Overlay.None -> null
}

/** Audio & subtitles' Sound and Subtitle timing, on [controller]; Save for everyone for an [admin]. */
private fun pickerExtras(controller: PlaybackController, admin: Boolean) = TvPickerExtras(
    onSound = controller::setSound,
    onTiming = { action ->
        when (action) {
            is TimingAction.Move -> controller.moveSubtitleTiming(action.deltaMs)
            TimingAction.Reset -> controller.resetSubtitleTiming()
            TimingAction.SaveForEveryone -> controller.saveSubtitleTimingForEveryone()
            TimingAction.Retry -> controller.retrySubtitleTiming()
        }
    },
    admin = admin,
)

private fun choose(controller: PlaybackController, choice: PickerChoice) = when (choice) {
    is PickerChoice.Audio -> controller.selectAudio(choice.index)
    is PickerChoice.Subtitle -> controller.selectSubtitle(choice.id)
    is PickerChoice.Online -> controller.downloadSubtitle(choice.resultId)
    PickerChoice.Nothing -> Unit
    is PickerChoice.Quality -> controller.selectQuality(choice.quality)
}

/**
 * The video through Media3's Compose surface (ContentFrame: PlayerSurface
 * sized to the video's aspect, with a shutter until the first frame), with
 * text subtitles over it; without a player (the fakes) the backdrop, dimmed.
 * media3-ui-compose is all @UnstableApi so far.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoLayer(controller: PlaybackController, state: PlayerUiState, imageUrl: (String?, ImageSize) -> String?, raisedSubtitles: Boolean, besidePanel: Boolean = false) {
    val player = controller.player.collectAsStateWithLifecycle().value
    val device by LocalAppServices.current.preferences.device.collectAsStateWithLifecycle()
    if (player != null) {
        MatchFrameRate(state.frameRate, enabled = device.matchFrameRate)
        ContentFrame(player, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        SubtitleCues(
            player,
            fontSize = 48.sp,
            sidePadding = if (besidePanel) SafeX else 160.dp,
            modifier = if (besidePanel) Modifier.padding(end = PanelWidth - SafeX) else Modifier,
            bottomPadding = if (raisedSubtitles) 270.dp else 72.dp,
            topPadding = 72.dp,
            // The person's size where the server keeps one (or the size of a play cast here); the TV's own before that.
            look = CaptionLook.Default.at(playerSubtitleSize(state.subtitleSize, deviceOwn = device.subtitleSize)),
        )
    } else {
        Artwork(imageUrl(state.backdropPath, ImageSize.W1280), seed = state.title.ifEmpty { "player" }, contentDescription = null, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
    }
}
