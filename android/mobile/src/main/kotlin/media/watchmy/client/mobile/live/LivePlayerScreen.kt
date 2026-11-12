package media.watchmy.client.mobile.live

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import kotlinx.coroutines.delay
import media.watchmy.client.mobile.components.CastButton
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.mobile.player.HideSystemBars
import media.watchmy.client.mobile.player.KeepScreenOn
import kotlinx.coroutines.withTimeoutOrNull
import media.watchmy.client.model.CastState
import media.watchmy.client.model.LIVE_QUALITIES
import media.watchmy.client.model.LIVE_TAKEOVER_DONT_RECORD
import media.watchmy.client.model.takeoverKey
import media.watchmy.client.model.takeoverMessage
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LivePlaybackController
import media.watchmy.client.model.LivePlayerState
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.forLiveTV
import media.watchmy.client.model.liveQualityLabel
import media.watchmy.client.model.liveSwitchMessage
import media.watchmy.client.model.liveTVError
import media.watchmy.client.presentation.live.nextLiveChannel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.ChannelLogo
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.ui.components.rememberLiveNow

private enum class LivePanel { Channels, Quality }

/** How long casting waits for this phone's tuner to come back before it asks the TV anyway. */
private const val STOP_HERE_MS = 5_000L

/**
 * LiveWatch in portrait and LivePlayer in landscape, with a dedicated live
 * controller. With [listings] the channel's banner and the channel lists show
 * what is on (from [channels]' now and next), and Guide goes to [onGuide].
 *
 * With [castable] (the server lets this account cast a channel) the header
 * has the Cast button, and the screen becomes the TV's remote while a
 * channel plays there (the LiveCast and TabLiveCast boards): connecting a TV
 * stops the play here and starts it on the TV, and Continue on this phone
 * brings it back. [onTv] opens it as the remote, casting [target] unless the
 * TV already plays it.
 */
@Composable
fun LivePlayerScreen(
    target: LiveTarget,
    channels: List<LiveChannel>,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    listings: Boolean = false,
    onGuide: () -> Unit = onExit,
    castable: Boolean = false,
    onTv: Boolean = false,
    onRecordings: () -> Unit = onGuide,
    onPlayRecording: (media.watchmy.client.model.PlayRequest) -> Unit = {},
) {
    val services = LocalAppServices.current
    var where by rememberSaveable { mutableStateOf(if (onTv && castable) LiveWhere.Tv else LiveWhere.Phone) }
    var here by remember { mutableStateOf(target) }
    when (where) {
        LiveWhere.Phone -> LocalLivePlayer(
            here, channels, onExit, listings, onGuide, castable,
            onCast = { channel ->
                here = LiveTarget(channel)
                where = LiveWhere.Tv
            },
            onNotLive = { where = LiveWhere.NotLive },
            modifier = modifier,
            onRecordings = onRecordings,
            onPlayRecording = onPlayRecording,
        )
        LiveWhere.Tv, LiveWhere.NotLive -> LiveRemote(
            target = here, channels = channels, listings = listings, castNow = onTv && where == LiveWhere.Tv,
            onExit = onExit, onGuide = onGuide, modifier = modifier,
            onHere = { channel ->
                here = LiveTarget(channel)
                where = LiveWhere.Phone
            },
            quality = { services.preferences.quality.value.forLiveTV(services.cast.live.state.value.automaticAvailable) },
        )
    }
}

/** Where the channel plays: here, on the TV, or nowhere yet because the TV can't play Live TV. */
internal enum class LiveWhere { Phone, Tv, NotLive }

@Composable
private fun LocalLivePlayer(
    target: LiveTarget,
    channels: List<LiveChannel>,
    onExit: () -> Unit,
    listings: Boolean,
    onGuide: () -> Unit,
    castable: Boolean,
    onCast: (LiveChannel) -> Unit,
    onNotLive: () -> Unit,
    modifier: Modifier = Modifier,
    onRecordings: () -> Unit = onGuide,
    onPlayRecording: (media.watchmy.client.model.PlayRequest) -> Unit = {},
) {
    val services = LocalAppServices.current
    val controller = remember(services) { services.livePlayback.create() }
    val state by controller.state.collectAsStateWithLifecycle()
    val player by controller.player.collectAsStateWithLifecycle()
    LaunchedEffect(controller, target) { controller.start(target) }
    // A TV connected while this plays: stop here first (the tuners are few), then play it on the TV.
    val castState by services.cast.state.collectAsStateWithLifecycle()
    val remote by services.cast.live.state.collectAsStateWithLifecycle()
    val device = (castState as? CastState.Connected)?.deviceName
    var connectedBefore by remember { mutableStateOf(device) }
    LaunchedEffect(device, remote.receiverLive, castable) {
        if (device == null) connectedBefore = null
        if (!castable || device == null || device == connectedBefore) return@LaunchedEffect
        when (remote.receiverLive) {
            true -> {
                val channel = controller.state.value.target?.channel ?: target.channel
                val quality = controller.state.value.quality
                withTimeoutOrNull(STOP_HERE_MS) { controller.stopAndRelease() }
                services.cast.live.cast(channel, quality)
                onCast(channel)
            }
            false -> onNotLive()
            null -> Unit
        }
    }
    DisposableEffect(controller) { onDispose { controller.release() } }
    // Live playback keeps a tuner: leaving the app releases it instead of silently keeping a lease.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.stop() }
    val activity = LocalActivity.current
    val originalOrientation = remember(activity) { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    var requestedFullscreen by remember { mutableStateOf(false) }
    DisposableEffect(activity, originalOrientation) {
        onDispose { activity?.requestedOrientation = originalOrientation }
    }
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    val tablet = config.smallestScreenWidthDp >= 600
    if (landscape && activity != null) HideSystemBars(activity.window)
    KeepScreenOn(state.status in listOf(PlayerStatus.Playing, PlayerStatus.Loading, PlayerStatus.Buffering))

    var panel by remember { mutableStateOf<LivePanel?>(null) }
    var unavailable by remember { mutableStateOf<LiveChannel?>(null) }
    var controls by remember { mutableStateOf(true) }
    var dismissedTakeover by rememberSaveable { mutableStateOf<String?>(null) }
    val current = state.target?.channel ?: target.channel
    // The list's copy carries what is on; the target carries only the channel.
    val shown = channels.firstOrNull { it.id == current.id } ?: current
    val now = rememberLiveNow()
    val format = rememberLiveClockFormat()
    LaunchedEffect(current.id, state.status) { controls = true }
    LaunchedEffect(controls, state.status, panel, landscape) {
        if (controls && landscape && panel == null && state.status == PlayerStatus.Playing) {
            delay(5_000)
            controls = false
        }
    }
    fun leave() {
        controller.stop()
        onExit()
    }
    fun act(action: LivePlaybackController.() -> Unit) {
        controls = true
        controller.noteUserInput()
        controller.action()
    }
    fun watch(channel: LiveChannel) {
        if (!channel.available) {
            controller.noteUserInput()
            unavailable = channel
            return
        }
        panel = null
        act { start(LiveTarget(channel)) }
    }
    fun step(delta: Int) {
        nextLiveChannel(channels, controller.state.value.target?.channel?.id ?: current.id, delta)?.let(::watch)
    }
    BackHandler {
        if (panel != null) panel = null else leave()
    }
    Column(modifier.fillMaxSize().background(WmmColors.Ground).then(if (!landscape) Modifier.statusBarsPadding() else Modifier)) {
        Row(Modifier.then(if (landscape) Modifier.weight(1f) else Modifier.fillMaxWidth()), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            Box(Modifier.weight(1f).then(if (landscape) Modifier.fillMaxHeight() else Modifier.aspectRatio(16f / 9f)).background(Color.Black)) {
                LiveVideo(player, Modifier.fillMaxSize().pointerInput(controller) {
                    detectTapGestures { controller.noteUserInput(); controls = !controls }
                })
                if (controls || !landscape || state.status != PlayerStatus.Playing || panel != null) {
                    Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .25f))) {
                        Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .35f)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            LiveIcon(WmmIcons.Back, "Back to channels", ::leave)
                            Column(Modifier.weight(1f)) {
                                Text("${current.number} · ${current.name}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (landscape) Text(onNowLine(shown, now).takeIf { listings } ?: "No listings for this channel", color = WmmColors.TextSoft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (castable) CastButton()
                            if (!landscape && !tablet) LiveIcon(PhoneIcons.Fullscreen, "Full screen", {
                                requestedFullscreen = true
                                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            })
                            else if (requestedFullscreen) LiveIcon(PhoneIcons.Fullscreen, "Exit full screen", {
                                requestedFullscreen = false
                                activity?.requestedOrientation = originalOrientation
                            })
                            if (landscape) TextButton(onClick = ::leave) { Text("Stop", color = Color.White) }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            when (state.status) {
                                PlayerStatus.Loading, PlayerStatus.Buffering -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CircularProgressIndicator(Modifier.size(24.dp), color = WmmColors.Accent, strokeWidth = 2.dp)
                                    Text(if (state.status == PlayerStatus.Loading) "Tuning ${current.number}…" else "Buffering…", color = Color.White)
                                }
                                PlayerStatus.Playing, PlayerStatus.Paused -> Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LiveIcon(PhoneIcons.ChannelDown, "Previous channel", { step(-1) }, enabled = channels.count { it.available } > 1)
                                    IconButton(onClick = { act { togglePlayPause() } }, modifier = Modifier.size(64.dp), colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White, contentColor = WmmColors.Ground)) {
                                        Icon(if (state.status == PlayerStatus.Paused) WmmIcons.Play else PhoneIcons.Pause, contentDescription = if (state.status == PlayerStatus.Paused) "Resume live TV" else "Pause live TV", modifier = Modifier.size(32.dp))
                                    }
                                    LiveIcon(PhoneIcons.ChannelUp, "Next channel", { step(1) }, enabled = channels.count { it.available } > 1)
                                }
                                else -> if (!landscape) Text("Live TV stopped", color = Color.White)
                            }
                        }
                        if (landscape && listings && state.status in listOf(PlayerStatus.Playing, PlayerStatus.Paused) && panel == null) {
                            LandscapeBanner(shown, now, format, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 560.dp))
                        }
                        if (landscape && state.status == PlayerStatus.Paused) {
                            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .65f)).padding(horizontal = 16.dp, vertical = 4.dp)) { LivePauseNote(state) }
                        }
                        if (landscape && (state.message ?: state.liveSwitchMessage()) != null) Text((state.message ?: state.liveSwitchMessage()).orEmpty(), color = WmmColors.Accent, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .65f)).padding(horizontal = 16.dp, vertical = 4.dp))
                        Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .45f)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.atLiveEdge && state.status == PlayerStatus.Playing) LiveBadge()
                            else if (state.status == PlayerStatus.Playing || state.status == PlayerStatus.Paused) TextButton(onClick = { act { goLive() } }) { Text("Go live", color = WmmColors.Accent) }
                            if (state.status == PlayerStatus.Paused) Text("Paused", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            else Box(Modifier.weight(1f))
                            if (landscape) {
                                TextButton(onClick = { controller.noteUserInput(); panel = LivePanel.Channels }) { Text(if (listings) "Guide" else "Channels", color = Color.White) }
                                TextButton(onClick = { controller.noteUserInput(); panel = LivePanel.Quality }) { Text(state.liveQualityLabel(), color = Color.White) }
                            }
                        }
                    }
                }
                if (landscape && state.status in listOf(PlayerStatus.Error, PlayerStatus.Ended)) {
                    LiveStopped(state, onRetry = { if (state.takeoverRecording != null) state.takeoverRecording?.let(onPlayRecording) else if (state.reason == "recording") onRecordings() else act { retry() } }, onExit = ::leave, modifier = Modifier.fillMaxSize())
                }
                // Over the controls, so its buttons stay reachable while they show.
                state.session?.takeIf { it.takeoverAtMs != null && !it.state.terminal && it.takeoverKey() != dismissedTakeover }?.let { session ->
                    RecordingTakeover(session, format, onDismiss = { dismissedTakeover = session.takeoverKey() }, Modifier.align(Alignment.TopCenter).padding(top = 64.dp))
                }
            }
            if (landscape && panel != null) {
                Surface(Modifier.width(320.dp).fillMaxHeight(), color = WmmColors.Surface) {
                    if (listings && panel == LivePanel.Channels) OnNowPanel(channels, current, now, format, onClose = { panel = null }, onGuide = { controller.stop(); onGuide() }, onWatch = ::watch)
                    else LivePlayerPanel(panel!!, channels, current, state, onClose = { panel = null }, onWatch = ::watch, onQuality = { quality -> panel = null; act { selectQuality(quality) } })
                }
            }
        }
        if (!landscape) {
            if (state.status in listOf(PlayerStatus.Error, PlayerStatus.Ended)) {
                LiveStopped(state, onRetry = { if (state.takeoverRecording != null) state.takeoverRecording?.let(onPlayRecording) else if (state.reason == "recording") onRecordings() else act { retry() } }, onExit = ::leave, modifier = Modifier.weight(1f))
            } else {
                if (listings) {
                    LazyColumn(Modifier.weight(1f)) {
                        item("banner") { LiveWatchBanner(shown, state, now, format, onGuide = { controller.stop(); onGuide() }, onQuality = { controller.noteUserInput(); panel = LivePanel.Quality }) }
                        item("on-now") {
                            Text("On now · ${format.time(now)}", color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                        }
                        items(channels, key = { it.id }) { channel -> OnNowRow(channel, now, format, watching = channel.id == current.id, onClick = { watch(channel) }) }
                    }
                } else {
                    LiveChannelInfo(current, state, onQuality = { controller.noteUserInput(); panel = LivePanel.Quality })
                    LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { Text("Channels", color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(vertical = 12.dp)) }
                        items(channels, key = { it.id }) { channel -> LiveChannelRow(channel, channel.id == current.id, onClick = { watch(channel) }, selectedLabel = "Watching") }
                    }
                }
            }
        }
    }
    if (!landscape && panel == LivePanel.Quality) {
        LiveQualitySheet(state, onClose = { panel = null }, onSelect = { quality -> panel = null; act { selectQuality(quality) } })
    }
    unavailable?.let { channel ->
        AlertDialog(
            onDismissRequest = { unavailable = null },
            title = { Text("${channel.number} · ${channel.name}") },
            text = { Text(channel.reason?.let(::liveTVError) ?: "This channel is unavailable. Try another channel.") },
            confirmButton = { TextButton(onClick = { unavailable = null }) { Text("Keep watching", color = WmmColors.Accent) } },
        )
    }
    if (state.askStillWatching) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Are you still watching?") },
            text = { Text("Keep watching ${current.number} · ${current.name}? The tuner will be released if you don’t respond.") },
            confirmButton = { TextButton(onClick = { act { answerStillWatching(true) } }) { Text("Keep watching", color = WmmColors.Accent) } },
            dismissButton = { TextButton(onClick = { controller.answerStillWatching(false); onExit() }) { Text("Stop watching", color = WmmColors.Accent) } },
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LiveVideo(player: Player?, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (player != null) {
            val presentation = rememberPresentationState(player)
            PlayerSurface(player, Modifier.resizeWithContentScale(ContentScale.Fit, presentation.videoSizeDp))
        } else {
            // Fake sessions have no video. Channel identity stays truthful and contains no invented programme.
            Icon(PhoneIcons.LiveTV, contentDescription = null, tint = WmmColors.TextFaint.copy(alpha = .25f), modifier = Modifier.size(80.dp))
        }
    }
}

@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).background(Color(0xFFF04438), CircleShape))
        Text("LIVE", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LiveIcon(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Icon(icon, contentDescription = description, tint = if (enabled) Color.White else WmmColors.TextFaint)
    }
}

@Composable
private fun LiveChannelInfo(channel: LiveChannel, state: LivePlayerState, onQuality: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${channel.number} · ${channel.name}", color = WmmColors.Text, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("No listings for this channel", color = WmmColors.TextMuted, fontSize = 13.sp)
        LivePauseNote(state)
        (state.message ?: state.liveSwitchMessage())?.let { Text(it, color = WmmColors.Accent, fontSize = 13.sp) }
        TonalButton("Quality · ${state.liveQualityLabel()}", onQuality)
    }
}

/** LiveWatch's banner under the video: the channel, LIVE, what is on with its picture and Next, then Guide and Quality. */
@Composable
private fun LiveWatchBanner(channel: LiveChannel, state: LivePlayerState, now: Long, format: LiveClockFormat, onGuide: () -> Unit, onQuality: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (channel.logo != null) ChannelLogo(channel, Modifier.width(44.dp))
            Text(channel.number, color = WmmColors.Text, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(channel.name, color = WmmColors.Text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (state.atLiveEdge && state.status == PlayerStatus.Playing) Row(
                Modifier.background(Color(0xFFB42318), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.size(6.dp).background(Color.White, CircleShape))
                Text("LIVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        LiveProgrammeBanner(channel, now, format)
        LivePauseNote(state)
        (state.message ?: state.liveSwitchMessage())?.let { Text(it, color = WmmColors.Accent, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TonalButton("Guide", onGuide, compact = true)
            TonalButton("Quality · ${state.liveQualityLabel()}", onQuality, compact = true)
        }
    }
}

/** LivePlayer's card over the video in landscape: the channel's logo box beside what is on. */
@Composable
private fun LandscapeBanner(channel: LiveChannel, now: Long, format: LiveClockFormat, modifier: Modifier = Modifier) {
    Row(modifier.background(Color.Black.copy(alpha = .78f), RoundedCornerShape(12.dp)).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (channel.logo != null) ChannelLogo(channel, Modifier.width(44.dp))
            Text(channel.number, color = WmmColors.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(channel.name, color = WmmColors.TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LiveProgrammeBanner(channel, now, format, Modifier.weight(1f), pictureWidth = 112.dp)
    }
}

/** The landscape Guide panel: On now with pictures, and the full guide. */
@Composable
private fun OnNowPanel(channels: List<LiveChannel>, current: LiveChannel, now: Long, format: LiveClockFormat, onClose: () -> Unit, onGuide: () -> Unit, onWatch: (LiveChannel) -> Unit) {
    Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("On now · ${format.time(now)}", color = WmmColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onGuide) { Text("Full guide", color = WmmColors.Accent) }
            LiveIcon(WmmIcons.Close, "Close", onClose)
        }
        LazyColumn {
            items(channels, key = { it.id }) { channel -> OnNowRow(channel, now, format, watching = channel.id == current.id, onClick = { onWatch(channel) }, pictureWidth = 92.dp) }
        }
    }
}

@Composable
private fun LivePauseNote(state: LivePlayerState) {
    if (state.status == PlayerStatus.Paused) {
        val seconds = state.session?.pauseRemainingSeconds ?: 300
        Text("Tuner released in ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}. Only the last ${state.session?.bufferSeconds ?: 60} seconds are kept; resume may jump to live.", color = WmmColors.TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun LiveStopped(state: LivePlayerState, onRetry: () -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.background(WmmColors.Ground.copy(alpha = .96f)).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (state.reason == "recording") "Stopped for a recording" else if (state.status == PlayerStatus.Error) "Can't play Live TV" else "Live TV stopped", color = WmmColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(state.error ?: state.message ?: "The tuner has been released. Start again to watch live.", color = WmmColors.TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledButton(if (state.takeoverRecording != null) "Watch from the start" else if (state.reason == "recording") "Open Recordings" else "Try again", onRetry)
                TonalButton("Back to channels", onExit)
            }
        }
    }
}

@Composable
private fun LivePlayerPanel(panel: LivePanel, channels: List<LiveChannel>, current: LiveChannel, state: LivePlayerState, onClose: () -> Unit, onWatch: (LiveChannel) -> Unit, onQuality: (StreamQuality) -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (panel == LivePanel.Channels) "Channels" else "Quality", color = WmmColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close", color = WmmColors.Accent) }
        }
        LivePauseNote(state)
        if (panel == LivePanel.Channels) LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(channels, key = { it.id }) { channel -> LiveChannelRow(channel, channel.id == current.id, onClick = { onWatch(channel) }, selectedLabel = "Watching") }
        } else LiveQualityOptions(state, onQuality)
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveQualitySheet(state: LivePlayerState, onClose: () -> Unit, onSelect: (StreamQuality) -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = WmmColors.Surface2) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("Quality", color = WmmColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            LiveQualityOptions(state, onSelect)
        }
    }
}

@Composable
private fun LiveQualityOptions(state: LivePlayerState, onSelect: (StreamQuality) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text(if (state.automaticAvailable) "Automatic can adapt without retuning. Choosing another quality starts the channel at live." else "Changing quality starts the channel again at live.", color = WmmColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
        items(LIVE_QUALITIES.filter { it != StreamQuality.Auto || state.automaticAvailable }, key = { it.id }) { option ->
            Surface(onClick = { onSelect(option) }, color = if (state.quality == option) WmmColors.Surface3 else Color.Transparent, shape = RoundedCornerShape(10.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.quality == option, onClick = null, modifier = Modifier.semantics { contentDescription = option.label })
                    Text(option.label, color = WmmColors.Text, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        item { Text("On mobile data, the On mobile data setting applies.", color = WmmColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp)) }
    }
}

/**
 * The warning before a recording takes this tuner (PLAN-LIVE-TV-DVR.md §3.1,
 * Taken over): OK hides it, and Don't record it is for whoever set the
 * recording and admins. TalkBack reads it out as it appears.
 */
@Composable
private fun RecordingTakeover(session: media.watchmy.client.model.LiveSession, format: LiveClockFormat, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val recorder = rememberRecordingActions()
    val state by recorder.state.collectAsStateWithLifecycle()
    val message = session.takeoverMessage(format).orEmpty()
    Column(
        modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).background(WmmColors.Surface2, RoundedCornerShape(12.dp)).padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(message, color = WmmColors.Text, fontSize = 14.sp)
        Row(Modifier.align(Alignment.End)) {
            if (session.takeoverCanCancel) TextButton(onClick = { session.takeoverRecordingId?.let(recorder::cancel) }, enabled = !state.busy) { Text(LIVE_TAKEOVER_DONT_RECORD, color = WmmColors.Accent) }
            TextButton(onClick = onDismiss) { Text("OK", color = WmmColors.Accent) }
        }
        state.error?.let { Text(it, color = WmmColors.Error, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
    }
}
