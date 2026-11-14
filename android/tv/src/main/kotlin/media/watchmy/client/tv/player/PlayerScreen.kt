package media.watchmy.client.tv.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import media.watchmy.client.presentation.player.PlayerEpisodesViewModel
import media.watchmy.client.model.EpisodeDetail
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.model.PlayerEvent
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.tracks
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.CaptionLook
import media.watchmy.client.ui.components.SubtitleCues
import media.watchmy.client.ui.playerKey

/**
 * The TV player. Mockups: design/mockups/android-tv/PlayerControls.dc.html
 * (controls with Skip Intro), PlayerNext.dc.html (Next Episode card) and
 * PlayerPicker.dc.html (audio, subtitles and quality). It renders a
 * [PlaybackController] from the app's services, made when the screen opens
 * and released when it leaves; the controller does the rest of
 * PLAN-CLIENTS.md §2.3.
 */
@Composable
fun PlayerScreen(
    request: PlayRequest,
    onExit: () -> Unit,
    /** A player made elsewhere (a play cast to this TV); the screen still stops and releases it when it goes. */
    controller: PlaybackController? = null,
) {
    val services = LocalAppServices.current
    val viewing = remember(services, controller) { Viewing(controller ?: services.playback.create()) }
    // The episode list beside the video (TvPlayerEpisodes); a recording, or a film, has none.
    val episodes = viewModel(key = "tv-player-episodes") { PlayerEpisodesViewModel(services.library) }
    LaunchedEffect(episodes, request) { if (request.recordingId == null) episodes.load(request) }
    val exit by rememberUpdatedState(onExit)
    LaunchedEffect(viewing, request) { viewing.controller.start(request) }
    DisposableEffect(viewing) { onDispose { viewing.close() } }
    // Leaving the app (Home, another app) pauses; the controller reports it.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewing.controller.pause() }
    MockupScale {
        PlayerContent(
            controller = viewing.controller,
            imageUrl = { path, size -> services.library.imageUrl(path, size) },
            episodes = episodes,
            onLeave = { reportStop ->
                viewing.finish(reportStop)
                exit()
            },
        )
    }
}

/**
 * One viewing. The viewer leaving reports "stopped" once ([finish] with
 * reportStop); when the controller ended the play itself ([PlayerEvent.Exit],
 * an admin's stop) it has reported already. [close] runs when the screen goes.
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
    val back = FocusRequester()
    val skip = FocusRequester()
    val scrubber = FocusRequester()
    val playPause = FocusRequester()
    val upNext = FocusRequester()
    val prompt = FocusRequester()
    val openers = PickerSection.entries.associateWith { FocusRequester() }
    val episodes = FocusRequester()
    val picker = PickerSection.entries.associateWith { FocusRequester() }
}

@Composable
private fun PlayerContent(
    controller: PlaybackController,
    imageUrl: (String?, ImageSize) -> String?,
    onLeave: (reportStop: Boolean) -> Unit,
    episodes: PlayerEpisodesViewModel? = null,
) {
    val state by controller.state.collectAsStateWithLifecycle()
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

    fun leave(reportStop: Boolean) {
        if (shell.leave()) onLeaveNow(reportStop)
    }

    val overlay = overlayOf(state, picker, episodesOpen)
    // Skip makes way for a seek thumbnail while the remote seeks (PlayerScrub).
    val seeking by remember { derivedStateOf { seek.targetMs != null } }
    val skipShown = state.skip != null && overlay == Overlay.None && !(seeking && state.trickplay != null)
    val chromeVisible = controlsVisible && overlay == Overlay.None

    fun hideControls() {
        if (area != FocusArea.Skip && area != FocusArea.Root) focus.root.requestFocus()
        controlsVisible = false
    }

    fun openPicker(section: PickerSection) {
        val present = pickerSections(state.tracks).map { it.first }
        val start = section.takeIf { it in present } ?: present.firstOrNull() ?: return
        pickerOpenedFromControls = controlsVisible && area == FocusArea.Buttons
        picker = start
        shell.wanted = focus.picker.getValue(start)
    }

    fun closePicker() {
        val opened = picker ?: return
        picker = null
        controlsVisible = pickerOpenedFromControls
        shell.wanted = if (pickerOpenedFromControls) focus.openers.getValue(opened) else null
    }

    fun openEpisodes() {
        if (episodeList?.available != true) return
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
        val request = list.playRequest(episode) ?: return
        episodesOpen = false
        picker = null
        controller.start(request)
        list.playing(request.fileId)
        controlsVisible = true
        shell.wanted = focus.playPause
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
            is KeyAction.Seek -> {
                seekStep(action.forward, nowMs, repeat)
                // Under the Next Episode card the controls show once a seek back takes the card away.
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
        val current = overlayOf(controller.state.value, picker, episodesOpen)
        when (backAction(current, controlsVisible)) {
            BackAction.Leave -> if (current == Overlay.Message) finishMessage() else leave(true)
            BackAction.KeepWatching -> controller.answerStillWatching(true)
            BackAction.ClosePicker -> closePicker()
            BackAction.CloseEpisodes -> closeEpisodes()
            BackAction.WatchCredits -> controller.watchCredits()
            BackAction.HideControls -> hideControls()
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
            if (seek.holding) seek.release()?.let { controller.seekTo(it) }
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
                    episodesOpen = false
                    picker = null
                    controlsVisible = true
                }
            }
        }
    }

    // Overlays take focus when they open; the Next Episode card replaces the controls.
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

    // The Skip button takes focus when it appears, as in PlayerControls.
    val skipSegment = state.skip?.segment
    LaunchedEffect(skipSegment) {
        if (skipSegment != null && overlayOf(controller.state.value, picker, episodesOpen) == Overlay.None) shell.wanted = focus.skip
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
        ChromeFocus(back = focus.back, skip = if (skipShown) focus.skip else null, scrubber = focus.scrubber, playPause = focus.playPause, openers = focus.openers, episodes = focus.episodes)
    }
    // The actions read the screen's state when they run, so one set does.
    val chromeActions = remember {
        ChromeActions(
            onBack = { leave(true) },
            onSeekStep = { forward ->
                seekStep(forward, System.nanoTime() / 1_000_000, repeat = false)
                seek.release()?.let { controller.seekTo(it) }
            },
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
        VideoLayer(controller, state, imageUrl, raisedSubtitles = chromeVisible || overlay == Overlay.UpNext)

        AnimatedVisibility(chromeVisible, Modifier.fillMaxSize(), enter = fadeIn(tween(150)), exit = fadeOut(tween(250))) {
            Box(Modifier.fillMaxSize()) { PlayerChrome(state, shownMs, readProgress, chromeFocus, chromeActions, seek, episodes = episodeList?.playingCode?.takeIf { episodeList.available }) }
        }

        val skip = state.skip
        if (skip != null && skipShown) {
            Entering(fadeIn(tween(200)), Modifier.align(Alignment.BottomEnd).padding(end = 58.dp, bottom = 296.dp)) {
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
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(520.dp).background(Brush.verticalGradient(listOf(Color.Transparent, UpNextShade))))
            MiniProgress(shownMs, state.durationMs, Modifier.align(Alignment.BottomStart).padding(start = 58.dp, bottom = 60.dp))
            Entering(fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 4 }, Modifier.align(Alignment.BottomEnd).padding(end = 58.dp, bottom = 60.dp)) {
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
            ErrorBanner(error, Modifier.align(Alignment.TopCenter).padding(top = 140.dp))
        }

        if (overlay == Overlay.Picker) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            TitleBlock(state, Modifier.align(Alignment.TopStart).padding(start = 58.dp, top = 48.dp).graphicsLayer { alpha = 0.7f }, pausedNote = true)
            Entering(slideInHorizontally(tween(220)) { it }, Modifier.align(Alignment.CenterEnd)) {
                PickerSheet(state.tracks, focus.picker, onChoose = { choose(controller, it) })
            }
        }

        if (overlay == Overlay.Episodes && episodeList != null && episodes != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            TitleBlock(state, Modifier.align(Alignment.TopStart).padding(start = 58.dp, top = 48.dp).graphicsLayer { alpha = 0.7f }, pausedNote = true)
            Entering(slideInHorizontally(tween(220)) { it }, Modifier.align(Alignment.CenterEnd)) {
                EpisodesPanel(
                    episodeList, episodesFocus, imageUrl,
                    positionMinutes = { minutesIn },
                    onSeason = episodes::selectSeason,
                    onPlay = ::playEpisode,
                    onRetry = episodes::retry,
                )
            }
        }

        if (overlay == Overlay.StillWatching) {
            PromptDialog(
                title = "Are you still watching?",
                message = listOfNotNull(state.title.ifEmpty { null }, state.subtitle).joinToString(" · ").ifEmpty { null },
                primary = "Keep watching",
                onPrimary = { controller.answerStillWatching(true) },
                primaryFocus = focus.prompt,
                secondary = "Stop",
                onSecondary = { controller.answerStillWatching(false) },
            )
        }

        if (overlay == Overlay.Message) {
            val stopped = state.stopMessage
            PromptDialog(
                title = if (stopped != null) "Playback stopped" else "Can't play this",
                message = stopped ?: state.error ?: "Something went wrong while playing.",
                primary = if (stopped != null) "OK" else "Back",
                onPrimary = { finishMessage() },
                primaryFocus = focus.prompt,
            )
        }
    }
}

private val UpNextShade = Color(0xE6070708)

private fun overlayFocus(overlay: Overlay, picker: PickerSection?, focus: PlayerFocus, episodes: EpisodesFocus): FocusRequester? = when (overlay) {
    Overlay.Episodes -> episodes.panel
    Overlay.UpNext -> focus.upNext
    Overlay.StillWatching, Overlay.Message -> focus.prompt
    Overlay.Picker -> picker?.let { focus.picker.getValue(it) }
    Overlay.None -> null
}

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
private fun VideoLayer(controller: PlaybackController, state: PlayerUiState, imageUrl: (String?, ImageSize) -> String?, raisedSubtitles: Boolean) {
    val player = controller.player.collectAsStateWithLifecycle().value
    val device by LocalAppServices.current.preferences.device.collectAsStateWithLifecycle()
    if (player != null) {
        MatchFrameRate(state.frameRate, enabled = device.matchFrameRate)
        ContentFrame(player, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        SubtitleCues(
            player,
            fontSize = 44.sp,
            sidePadding = 160.dp,
            bottomPadding = if (raisedSubtitles) 250.dp else 72.dp,
            topPadding = 72.dp,
            look = CaptionLook(scale = device.subtitleSize.scale),
        )
    } else {
        Artwork(imageUrl(state.backdropPath, ImageSize.W1280), seed = state.title.ifEmpty { "player" }, contentDescription = null, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
    }
}
