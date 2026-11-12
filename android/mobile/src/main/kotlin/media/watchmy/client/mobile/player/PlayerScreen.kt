package media.watchmy.client.mobile.player

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import kotlinx.coroutines.delay
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.model.CastState
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.model.PlayerEvent
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.deviceName
import media.watchmy.client.model.tracks
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.TabletLayout
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.SubtitleCues
import media.watchmy.client.ui.components.rememberSystemCaptionLook
import media.watchmy.client.ui.rememberTabletLayout

/**
 * The phone player: landscape, full screen, touch controls over the video.
 * Mockups: design/mockups/android/PlayerControls.dc.html (controls and Skip
 * Intro), PlayerNext.dc.html (Next Episode card), PlayerPicker.dc.html
 * (audio, subtitles and quality). It renders a [PlaybackController] from
 * the app's services, which does the playing and the rules of
 * PLAN-CLIENTS.md §2.3; this screen only shows its state and passes on
 * what the viewer does.
 *
 * While casting (PLAN-CAST.md §5.2) it is the TV's remote instead: the same
 * controls over the backdrop, driving the cast service's remote controller,
 * in any orientation. The play moves across as casting starts (the TV takes
 * over from this position, with these tracks) and ends (it offers to carry on
 * here).
 */
@Composable
fun PlayerScreen(request: PlayRequest, onExit: () -> Unit) {
    val services = LocalAppServices.current
    val castState by services.cast.state.collectAsStateWithLifecycle()
    val castDevice = (castState as? CastState.Connected)?.deviceName.takeIf { request.recordingId == null }
    // Where the play is now, and what it is (a handover carries the position and tracks).
    var selection by remember { mutableStateOf(PlayerSelection(request)) }
    var remote by remember { mutableStateOf(castDevice != null) }
    CompositionLocalProvider(LocalContentColor provides WmmColors.Text) {
        if (remote) {
            RemotePlayer(selection, castState, onExit, onPlay = { selection = selection.select(it) }, onContinueHere = {
                selection = selection.select(it)
                remote = false
            })
        } else {
            LocalPlayer(selection, castDevice, onExit, onPlay = { selection = selection.select(it) }, onCast = {
                selection = selection.select(it)
                remote = true
            })
        }
    }
}

@Composable
private fun LocalPlayer(selection: PlayerSelection, castDevice: String?, onExit: () -> Unit, onPlay: (PlayRequest) -> Unit, onCast: (PlayRequest) -> Unit) {
    val request = selection.request
    val services = LocalAppServices.current
    val controller = remember(services) { services.playback.create() }
    // Releasing reports the play stopped, so a handover to the TV ends it here.
    DisposableEffect(controller) { onDispose { controller.release() } }
    var fileId by remember(selection) { mutableLongStateOf(request.fileId) }
    StartSelectedPlayback(controller, selection)
    LaunchedEffect(controller, selection) { controller.events.collect { if (it is PlayerEvent.StartedNext) fileId = it.fileId } }
    // A Cast device connected while playing here: the TV carries on.
    LaunchedEffect(castDevice) {
        if (castDevice != null) onCast(handOver(request, fileId, controller.state.value, controller.progress.value.positionMs))
    }
    PlayerContent(controller, selection, onExit, onPlay, remoteDevice = null)
}

@Composable
private fun RemotePlayer(selection: PlayerSelection, castState: CastState, onExit: () -> Unit, onPlay: (PlayRequest) -> Unit, onContinueHere: (PlayRequest) -> Unit) {
    val request = selection.request
    val cast = LocalAppServices.current.cast
    val controller = cast.remote
    StartSelectedPlayback(controller, selection)
    // The device's name and what it plays outlive the session, for the offer to carry on here.
    val device = latest(castState.deviceName) ?: "TV"
    val playing = latest(cast.nowPlaying.collectAsStateWithLifecycle().value) ?: request
    var offer by remember { mutableStateOf<PlayRequest?>(null) }
    val gone = castState !is CastState.Connected && castState !is CastState.Connecting
    LaunchedEffect(gone) {
        offer = if (gone) handOver(playing, playing.fileId, controller.state.value, controller.progress.value.positionMs) else null
    }
    PlayerContent(controller, selection, onExit, onPlay, remoteDevice = device)
    offer?.let { here -> ContinueHereDialog(device, onContinue = { onContinueHere(here) }, onLeave = onExit) }
}


@OptIn(UnstableApi::class)
@Composable
private fun PlayerContent(controller: PlaybackController, selection: PlayerSelection, onExit: () -> Unit, onPlay: (PlayRequest) -> Unit, remoteDevice: String?) {
    val request = selection.request
    val local = remoteDevice == null
    val library = LocalAppServices.current.library
    val state by controller.state.collectAsStateWithLifecycle()
    // Read only by the scrubber and the clocks, through the lambda, so its ticks recompose only them.
    val progress = controller.progress.collectAsStateWithLifecycle()
    val readProgress = { progress.value }
    val tablet = TabletLayout.usesTabletPlayer(rememberTabletLayout().widthDp, LocalConfiguration.current.smallestScreenWidthDp)
    val episodesModel: PlayerEpisodesViewModel = viewModel { PlayerEpisodesViewModel(library) }
    val episodes by episodesModel.state.collectAsStateWithLifecycle()
    var playingFileId by remember(selection) { mutableLongStateOf(request.fileId) }
    var showEpisodes by remember { mutableStateOf(false) }
    LaunchedEffect(tablet, playingFileId) {
        if (tablet && request.recordingId == null) episodesModel.load(if (playingFileId == request.fileId) request else PlayRequest(playingFileId, title = state.title))
    }
    val activity = LocalActivity.current as? ComponentActivity
    val exit by rememberUpdatedState(onExit)

    var exited by remember { mutableStateOf(false) }
    var controls by remember(playingFileId, selection) { mutableStateOf(ControlsState(lastInputMs = now())) }
    var picker by remember(playingFileId, selection) { mutableStateOf<PickerTab?>(null) }
    var resumeAfterPicker by remember(playingFileId, selection) { mutableStateOf(false) }
    var scrubbing by remember(playingFileId, selection) { mutableStateOf(false) }
    var rotationLocked by remember { mutableStateOf(false) }
    var exitAfterMessage by remember(playingFileId, selection) { mutableStateOf(false) }
    var videoBounds by remember { mutableStateOf<android.graphics.Rect?>(null) }
    val seek = rememberSeekForFile(playingFileId, selection.generation)
    var ripple by remember(playingFileId, selection) { mutableStateOf<SeekBurst?>(null) }
    var rippleShown by remember(playingFileId, selection) { mutableStateOf(false) }

    fun leave(stop: Boolean) {
        if (exited) return
        exited = true
        // Leaving the remote leaves the TV playing (the mini controller has it).
        if (stop && local) controller.stop()
        exit()
    }

    /** Every touch and choice counts as the viewer being there (Still watching) and keeps the controls up. */
    fun input() {
        controller.noteUserInput()
        controls = controls.touched(now())
    }

    fun act(block: PlaybackController.() -> Unit) {
        input()
        controller.block()
    }

    fun openPicker(tab: PickerTab) {
        input()
        // The phone sheet pauses; tablets keep playing beside their panel.
        resumeAfterPicker = !tablet && state.status == PlayerStatus.Playing
        if (resumeAfterPicker) controller.pause()
        showEpisodes = false
        picker = tab
    }

    fun closePicker() {
        if (picker == null) return
        picker = null
        if (resumeAfterPicker) controller.play()
        resumeAfterPicker = false
        controls = controls.shown(now())
    }

    LaunchedEffect(controller, selection) {
        controller.events.collect { event ->
            when (event) {
                // An admin's stop shows its message first; OK leaves.
                PlayerEvent.Exit -> if (controller.state.value.stopMessage != null) exitAfterMessage = true else leave(stop = false)
                is PlayerEvent.StartedNext -> {
                    playingFileId = event.fileId
                    closePicker()
                    controls = controls.shown(now())
                }
            }
        }
    }

    val playing = state.status == PlayerStatus.Playing || state.status == PlayerStatus.Buffering
    val player by controller.player.collectAsStateWithLifecycle()
    val presentation = player?.let { rememberPresentationState(it) }
    val pip = activity?.takeIf { local }?.let {
        rememberPictureInPicture(
            it,
            aspect = pipAspectRatio(presentation?.videoSizeDp?.width, presentation?.videoSizeDp?.height),
            sourceRect = videoBounds,
            playing = playing,
            onAction = { action ->
                when (action) {
                    PipAction.Replay -> act { seekBy(-DoubleTapSeek.STEP_MS) }
                    PipAction.PlayPause -> act { togglePlayPause() }
                    PipAction.Forward -> act { seekBy(DoubleTapSeek.STEP_MS) }
                }
            },
        )
    }
    val inPip = pip?.active == true

    val rotationLockWorks = rememberRotationLockWorks(activity)
    if (activity != null && local) {
        LandscapeLock(activity, pinned = rotationLocked)
        HideSystemBars(activity.window)
    }
    if (local) {
        KeepScreenOn(playing || state.status == PlayerStatus.Loading)
        // Leaving the app without picture in picture, or closing the picture-in-picture window, pauses.
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.pause() }
    }

    LaunchedEffect(inPip) {
        if (inPip) {
            closePicker()
            showEpisodes = false
            controls = controls.hidden()
        } else {
            controls = controls.shown(now())
        }
    }
    LaunchedEffect(inPip, exitAfterMessage) { if (inPip && exitAfterMessage) leave(stop = false) }

    // A remote keeps its controls up.
    val autoHide = local && ControlsState.autoHides(state.status, scrubbing, picker != null || showEpisodes)
    var autoHideSince by remember(playingFileId, selection) { mutableLongStateOf(0L) }
    LaunchedEffect(autoHide, playingFileId, selection) { if (autoHide) autoHideSince = now() }
    LaunchedEffect(controls, autoHide, autoHideSince, playingFileId, selection) {
        val at = controls.hideAtMs(autoHide, autoHideSince) ?: return@LaunchedEffect
        delay((at - now()).coerceAtLeast(0))
        controls = controls.hidden()
    }
    LaunchedEffect(ripple, playingFileId, selection) {
        if (ripple != null) {
            delay(DoubleTapSeek.WINDOW_MS + 300)
            rippleShown = false
        }
    }

    BackHandler(enabled = !inPip) {
        input()
        when {
            picker != null -> closePicker()
            showEpisodes -> showEpisodes = false
            state.upNext != null -> controller.watchCredits()
            else -> leave(stop = true)
        }
    }

    val noteTouch by rememberUpdatedState(::input)
    val keys = remember { FocusRequester() }
    LaunchedEffect(keys) { keys.requestFocus() }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(WmmColors.Ground).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                noteTouch()
            }
        }.playerKeyboard(keys, enabled = !inPip && picker == null && !showEpisodes) { action ->
            when (action) {
                KeyboardAction.TogglePlayPause -> act { togglePlayPause() }
                is KeyboardAction.SeekBy -> act { seekBy(action.deltaMs) }
            }
        },
    ) {
        val portrait = tablet && maxHeight > maxWidth && !inPip
        val videoHeight = maxWidth * 9 / 16
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (portrait) Modifier.height(videoHeight) else Modifier.weight(1f))
                    .background(Color.Black),
            ) {
                val controlsShown = controls.visible && picker == null && !showEpisodes && state.error == null
                Box(Modifier.fillMaxSize().padding(end = if (tablet && !portrait && !inPip && (picker != null || showEpisodes)) 452.dp else 0.dp)) {
                    VideoLayer(
                        player = player,
                        coverSurface = presentation?.coverSurface ?: true,
                        videoSize = presentation?.videoSizeDp,
                        backdropUrl = library.imageUrl(state.backdropPath, ImageSize.W1280),
                        seed = state.title.ifEmpty { request.title },
                        remoteDevice = remoteDevice,
                        // Where the video sits full screen, for the picture-in-picture animation; not the small window's bounds.
                        onBounds = { if (!inPip && it != videoBounds) videoBounds = it },
                    )
                    player?.let {
                        // Above the scrubber while the controls show; small in picture in picture.
                        SubtitleCues(
                            it,
                            fontSize = if (inPip) 10.sp else 18.sp,
                            sidePadding = if (inPip) 8.dp else 48.dp,
                            bottomPadding = when {
                                inPip -> 6.dp
                                controlsShown -> if (portrait) 64.dp else 104.dp
                                else -> 24.dp
                            },
                            topPadding = if (inPip) 6.dp else 24.dp,
                            // The system's caption size and style (Accessibility settings).
                            look = rememberSystemCaptionLook(),
                        )
                    }
                }
                if (inPip) return@Box

                TouchLayer(
                    seek = seek,
                    onTap = { controls = controls.toggled(now()) },
                    onSeek = { delta ->
                        act { seekBy(delta) }
                        ripple = seek.burst
                        rippleShown = true
                    },
                )
                val shownRipple = ripple
                androidx.compose.animation.AnimatedVisibility(
                    visible = rippleShown && shownRipple != null,
                    modifier = Modifier.align(if (shownRipple?.zone == SeekZone.Back) Alignment.CenterStart else Alignment.CenterEnd),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    shownRipple?.let { SeekRipple(it) }
                }

                androidx.compose.animation.AnimatedVisibility(controlsShown, enter = fadeIn(), exit = fadeOut()) {
                    key(playingFileId, selection) {
                        PlayerControls(
                            state = state,
                            progress = readProgress,
                            remote = !local,
                            portrait = portrait,
                            rotationLocked = rotationLocked,
                            rotationLockWorks = rotationLockWorks,
                            actions = ControlsActions(
                                onBack = { leave(stop = true) },
                                onTogglePlay = { act { togglePlayPause() } },
                                onSeekBy = { act { seekBy(it) } },
                                onSeekTo = { act { seekTo(it) } },
                                onScrubbing = {
                                    input()
                                    scrubbing = it
                                },
                                onPicker = ::openPicker,
                                onPictureInPicture = if (pip?.supported == true) {
                                    {
                                        input()
                                        pip.enter()
                                    }
                                } else {
                                    null
                                },
                                onToggleRotationLock = {
                                    input()
                                    rotationLocked = !rotationLocked
                                },
                                onPlayNext = { act { playNext() } },
                                onEpisodes = if (tablet && (episodes.season != null || episodes.error != null)) { { input(); showEpisodes = true } } else null,
                            ),
                        )
                    }
                }
                if (!controls.visible && (state.status == PlayerStatus.Loading || state.status == PlayerStatus.Buffering)) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = WmmColors.Accent, strokeWidth = 3.dp, trackColor = Color.Transparent)
                }

                // Automatic's note, under the top bar whether or not the controls show.
                val note = latest(state.note)
                androidx.compose.animation.AnimatedVisibility(
                    state.note != null && picker == null && !showEpisodes && state.error == null,
                    Modifier.align(Alignment.TopCenter).padding(top = 76.dp, start = 16.dp, end = 16.dp),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    note?.let { AutoNote(it) }
                }

                val upNext = latest(state.upNext)
                val cardShown = state.upNext != null && !controls.visible && picker == null && !showEpisodes && state.error == null
                androidx.compose.animation.AnimatedVisibility(cardShown, Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 20.dp), enter = fadeIn(), exit = fadeOut()) {
                    MiniProgress(readProgress)
                }
                androidx.compose.animation.AnimatedVisibility(
                    cardShown,
                    Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
                    enter = fadeIn() + slideInHorizontally { it / 3 },
                    exit = fadeOut(),
                ) {
                    upNext?.let {
                        NextEpisodeCard(
                            prompt = it,
                            stillUrl = library.imageUrl(it.next.stillPath, ImageSize.W342),
                            onPlayNow = { act { playNext() } },
                            onWatchCredits = { act { watchCredits() } },
                        )
                    }
                }

                val skip = latest(state.skip)
                val skipBottom by animateDpAsState(if (controls.visible) { if (portrait) 64.dp else 112.dp } else 24.dp, label = "skipBottom")
                androidx.compose.animation.AnimatedVisibility(
                    // It makes way for a seek thumbnail (PlayerScrub).
                    state.skip != null && picker == null && !showEpisodes && !cardShown && state.error == null && !(scrubbing && state.trickplay != null),
                    Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = skipBottom),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    skip?.let { SkipButton(it, onSkip = { act { skip() } }) }
                }

                androidx.compose.animation.AnimatedVisibility(picker != null && !tablet, enter = fadeIn(), exit = fadeOut()) {
                    PickerBackdrop(state.title, state.subtitle, paused = state.status == PlayerStatus.Paused)
                }
                state.error?.let { ErrorOverlay(it, onRetry = { act { start(request) } }, onBack = { leave(stop = true) }) }
            }

            if (portrait) {
                PortraitPlayerDetails(
                    state, episodes, ::openPicker,
                    onPlay = { input(); showEpisodes = false; onPlay(it) },
                    onRetry = episodesModel::retry,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
        if (!inPip) {
            picker?.let { tab ->
                if (tablet) TabletTrackPicker(
                    state.tracks, tab,
                    onAudio = { act { selectAudio(it) } },
                    onSubtitle = { act { selectSubtitle(it) } },
                    onDownload = { act { downloadSubtitle(it) } },
                    onQuality = { act { selectQuality(it) } },
                    onDismiss = ::closePicker,
                ) else TrackPicker(
                    state.tracks, tab,
                    onAudio = { act { selectAudio(it) } },
                    onSubtitle = { act { selectSubtitle(it) } },
                    onDownload = { act { downloadSubtitle(it) } },
                    onQuality = { act { selectQuality(it) } },
                    onDismiss = ::closePicker,
                )
            }
            if (tablet && showEpisodes) PlayerEpisodesPanel(
                episodes,
                onPlay = { input(); showEpisodes = false; onPlay(it) },
                onRetry = episodesModel::retry,
                onDismiss = { showEpisodes = false; controls = controls.shown(now()) },
            )
        }
    }
    if (inPip) return
    if (state.askStillWatching) {
        StillWatchingDialog(state.title, state.subtitle, onAnswer = { keep -> act { answerStillWatching(keep) } })
    }
    state.stopMessage?.let { StopMessageDialog(it, onOk = { leave(stop = false) }) }
}

/** The video, fitted to the screen; the backdrop, dimmed, until there is a picture (and in fakes, which have no video). */
@OptIn(UnstableApi::class)
@Composable
private fun VideoLayer(
    player: Player?,
    coverSurface: Boolean,
    videoSize: androidx.compose.ui.geometry.Size?,
    backdropUrl: String?,
    seed: String,
    remoteDevice: String?,
    onBounds: (android.graphics.Rect) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The surface goes in as soon as there is a player: the video size and the first
        // frame only arrive once the decoder has somewhere to draw. Until then it fills the
        // screen under the backdrop.
        if (player != null) {
            PlayerSurface(
                player,
                Modifier
                    .resizeWithContentScale(ContentScale.Fit, videoSize)
                    .onGloballyPositioned { onBounds(it.boundsInWindow().toAndroidRect()) },
            )
        }
        if (player == null || coverSurface) {
            Artwork(
                url = backdropUrl,
                seed = seed,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().onGloballyPositioned { if (player == null) onBounds(it.boundsInWindow().toAndroidRect()) },
                overlay = { Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (remoteDevice != null) 0.6f else 0.35f))) },
            )
        }
        remoteDevice?.let { PlayingOn(it, Modifier.align(Alignment.Center).padding(bottom = 140.dp)) }
    }
}

/** Where the video would be while casting: which TV has it. */
@Composable
private fun PlayingOn(device: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(PhoneIcons.Cast, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(20.dp))
        Text("Playing on $device", style = PlayerType.Subtitle.copy(color = WmmColors.Text), maxLines = 1)
    }
}

/**
 * Taps on the video: a tap shows or hides the controls; a double tap on the
 * left or right seeks (see [DoubleTapSeek]), and further taps on that side
 * keep seeking without waiting for a double tap.
 */
@Composable
internal fun TouchLayer(seek: DoubleTapSeek, onTap: () -> Unit, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val tap by rememberUpdatedState(onTap)
    val seekBy by rememberUpdatedState(onSeek)
    key(seek) {
        Box(
            modifier.fillMaxSize().pointerInput(seek) {
                awaitEachGesture {
                    awaitFirstDown()
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    val width = size.width.toFloat()
                    seek.onTap(up.position.x, width, up.uptimeMillis)?.let {
                        seekBy(it)
                        return@awaitEachGesture
                    }
                    val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
                    if (second == null) {
                        tap()
                        return@awaitEachGesture
                    }
                    waitForUpOrCancellation()
                    val delta = seek.onDoubleTap(second.position.x, width, second.uptimeMillis)
                    if (delta != null) seekBy(delta) else tap()
                }
            },
        )
    }
}

private class Last<T>(var value: T?)

/** [value], or the last non-null one, so content can fade out after its value is gone. */
@Composable
private fun <T : Any> latest(value: T?): T? {
    val last = remember { Last<T>(null) }
    if (value != null) last.value = value
    return value ?: last.value
}

private fun now() = SystemClock.uptimeMillis()
