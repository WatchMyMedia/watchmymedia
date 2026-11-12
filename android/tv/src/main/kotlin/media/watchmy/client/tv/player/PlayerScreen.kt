package media.watchmy.client.tv.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import kotlinx.coroutines.delay
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
import media.watchmy.client.ui.PlayerKey
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
    val exit by rememberUpdatedState(onExit)
    LaunchedEffect(viewing, request) { viewing.controller.start(request) }
    DisposableEffect(viewing) { onDispose { viewing.close() } }
    // Leaving the app (Home, another app) pauses; the controller reports it.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewing.controller.pause() }
    MockupScale {
        PlayerContent(
            controller = viewing.controller,
            imageUrl = { path, size -> services.library.imageUrl(path, size) },
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

private class PlayerFocus {
    val root = FocusRequester()
    val back = FocusRequester()
    val skip = FocusRequester()
    val scrubber = FocusRequester()
    val playPause = FocusRequester()
    val upNext = FocusRequester()
    val prompt = FocusRequester()
    val openers = PickerSection.entries.associateWith { FocusRequester() }
    val picker = PickerSection.entries.associateWith { FocusRequester() }
}

/** Controls hide after this long without a key while playing. */
private const val HIDE_AFTER_MS = 5_000L

@Composable
private fun PlayerContent(
    controller: PlaybackController,
    imageUrl: (String?, ImageSize) -> String?,
    onLeave: (reportStop: Boolean) -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val focus = remember { PlayerFocus() }
    val seek = remember { SeekState() }
    // Read only by the scrubber and the clocks, through these lambdas, so its ticks recompose only them.
    val progress = controller.progress.collectAsStateWithLifecycle()
    val readProgress = { progress.value }
    val shownMs = { seek.displayed(progress.value.positionMs) }
    var controlsVisible by remember { mutableStateOf(true) }
    var picker by remember { mutableStateOf<PickerSection?>(null) }
    var pickerOpenedFromControls by remember { mutableStateOf(false) }
    var area by remember { mutableStateOf(FocusArea.Root) }
    var treeHasFocus by remember { mutableStateOf(false) }
    var rootFocused by remember { mutableStateOf(false) }
    var inputTick by remember { mutableIntStateOf(0) }
    // A focus move asked for by an action, honoured once its target is composed.
    var wanted by remember { mutableStateOf<FocusRequester?>(null) }
    val consumedKeys = remember { mutableSetOf<Int>() }
    var backDown by remember { mutableStateOf(false) }
    val onLeaveNow by rememberUpdatedState(onLeave)
    // Set once the viewer leaves. The screen stays composed while it fades out, and the
    // screen underneath takes focus meanwhile: the player must not take it back, or focus
    // goes down with it and lands on the rail.
    var leaving by remember { mutableStateOf(false) }

    fun leave(reportStop: Boolean) {
        if (leaving) return
        leaving = true
        onLeaveNow(reportStop)
    }

    val overlay = overlayOf(state, picker)
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
        wanted = focus.picker.getValue(start)
    }

    fun closePicker() {
        val opened = picker ?: return
        picker = null
        controlsVisible = pickerOpenedFromControls
        wanted = if (pickerOpenedFromControls) focus.openers.getValue(opened) else null
    }

    fun seekStep(forward: Boolean, nowMs: Long, repeat: Boolean) {
        val p = controller.progress.value
        seek.press(forward, p.positionMs, p.durationMs, nowMs, repeat)
    }

    fun finishMessage() = leave(controller.state.value.stopMessage == null)

    fun perform(action: KeyAction, nowMs: Long, repeat: Boolean) {
        val current = overlayOf(controller.state.value, picker)
        when (action) {
            KeyAction.ShowControls -> controlsVisible = true
            is KeyAction.Seek -> {
                seekStep(action.forward, nowMs, repeat)
                // Under the Next Episode card the controls show once a seek back takes the card away.
                controlsVisible = true
                if (current == Overlay.None && area != FocusArea.Scrubber) wanted = focus.scrubber
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
        inputTick++
        val current = overlayOf(controller.state.value, picker)
        when (backAction(current, controlsVisible)) {
            BackAction.Leave -> if (current == Overlay.Message) finishMessage() else leave(true)
            BackAction.KeepWatching -> controller.answerStillWatching(true)
            BackAction.ClosePicker -> closePicker()
            BackAction.WatchCredits -> controller.watchCredits()
            BackAction.HideControls -> hideControls()
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        // Keys pressed while the player fades out belong to the screen underneath: a quick
        // second Back leaves it too.
        if (leaving) return false
        val native = event.nativeKeyEvent
        val key = playerKey(native.keyCode)
        // Compose turns an unhandled Back into a focus exit, so the player takes it
        // here, on key-up as the platform does, rather than leaving it to BackHandler.
        if (key == PlayerKey.Back) {
            if (event.type == KeyEventType.KeyDown && native.repeatCount == 0) backDown = true
            if (event.type == KeyEventType.KeyUp && backDown) {
                backDown = false
                onBack()
            }
            return true
        }
        if (event.type == KeyEventType.KeyUp) {
            if (!consumedKeys.remove(native.keyCode)) return false
            if (seek.holding) seek.release()?.let { controller.seekTo(it) }
            return true
        }
        if (event.type != KeyEventType.KeyDown) return false
        val repeat = native.repeatCount > 0
        if (!repeat) controller.noteUserInput()
        inputTick++
        val action = routeKey(key, overlayOf(controller.state.value, picker), controlsVisible, area)
        if (action == KeyAction.PassThrough) return false
        consumedKeys += native.keyCode
        perform(action, native.eventTime, repeat)
        return true
    }

    // Back that is not a key (a gesture, or nothing in the player has focus).
    BackHandler(enabled = !leaving) { onBack() }

    LaunchedEffect(controller) {
        controller.events.collect { event ->
            when (event) {
                // An admin's stop shows its message first; OK (or Back) leaves.
                PlayerEvent.Exit -> if (controller.state.value.stopMessage == null) leave(false)
                is PlayerEvent.StartedNext -> {
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
                wanted = focus.upNext
            }
            Overlay.StillWatching, Overlay.Message -> wanted = focus.prompt
            Overlay.Picker, Overlay.None -> Unit
        }
    }

    // The Skip button takes focus when it appears, as in PlayerControls.
    val skipSegment = state.skip?.segment
    LaunchedEffect(skipSegment) {
        if (skipSegment != null && overlayOf(controller.state.value, picker) == Overlay.None) wanted = focus.skip
    }

    // Focus always rests somewhere that takes the remote: what an action asked
    // for, else the open overlay, the Skip button, the scrubber, or the screen.
    LaunchedEffect(wanted, treeHasFocus, rootFocused, controlsVisible, overlay, skipShown, leaving) {
        if (leaving) return@LaunchedEffect
        val target = wanted ?: when {
            overlay != Overlay.None -> if (treeHasFocus && !rootFocused) null else overlayFocus(overlay, picker, focus)
            !treeHasFocus -> when {
                skipShown -> focus.skip
                controlsVisible -> focus.scrubber
                else -> focus.root
            }
            rootFocused && controlsVisible -> focus.scrubber
            else -> null
        }
        target?.focusSoon()
        wanted = null
    }

    LaunchedEffect(controlsVisible, inputTick, state.status, overlay, seek.holding) {
        if (controlsVisible && overlay == Overlay.None && state.status == PlayerStatus.Playing && !seek.holding) {
            delay(HIDE_AFTER_MS)
            hideControls()
        }
    }

    // After a seek is sent, keep showing its target until the player gets there.
    LaunchedEffect(seek.targetMs, seek.holding) {
        if (seek.targetMs != null && !seek.holding) {
            withTimeoutOrNull(SeekState.SETTLE_TIMEOUT_MS) { controller.progress.first { seek.landed(it.positionMs) } }
            seek.settle()
        }
    }

    KeepScreenOn(state.status == PlayerStatus.Playing || state.status == PlayerStatus.Buffering || state.status == PlayerStatus.Loading)

    // Kept across the state's updates (the countdowns, the Skip button's fill), so the buttons skip recomposing.
    val chromeFocus = remember(skipShown) {
        ChromeFocus(back = focus.back, skip = if (skipShown) focus.skip else null, scrubber = focus.scrubber, playPause = focus.playPause, openers = focus.openers)
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
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { onKey(it) }
            .onFocusChanged {
                treeHasFocus = it.hasFocus
                rootFocused = it.isFocused
                if (it.isFocused || !it.hasFocus) area = FocusArea.Root
            }
            .focusRequester(focus.root)
            .focusable(),
    ) {
        VideoLayer(controller, state, imageUrl, raisedSubtitles = chromeVisible || overlay == Overlay.UpNext)

        AnimatedVisibility(chromeVisible, Modifier.fillMaxSize(), enter = fadeIn(tween(150)), exit = fadeOut(tween(250))) {
            Box(Modifier.fillMaxSize()) { PlayerChrome(state, shownMs, readProgress, chromeFocus, chromeActions, seek) }
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
        if (note != null && overlay != Overlay.Picker && overlay != Overlay.Message) {
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

private fun overlayFocus(overlay: Overlay, picker: PickerSection?, focus: PlayerFocus): FocusRequester? = when (overlay) {
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

/** Keeps the TV from dimming or starting its screensaver while video plays. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}
