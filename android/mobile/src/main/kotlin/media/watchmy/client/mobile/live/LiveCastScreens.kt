package media.watchmy.client.mobile.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import media.watchmy.client.mobile.components.CastButton
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.model.CastState
import media.watchmy.client.model.LIVE_QUALITIES
import media.watchmy.client.model.LiveCastPhase
import media.watchmy.client.model.LiveCastState
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.liveQualityLabel
import media.watchmy.client.model.liveChannelAfter
import media.watchmy.client.model.liveTVError
import media.watchmy.client.model.onAt
import media.watchmy.client.model.progressAt
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.ChannelLogo
import media.watchmy.client.ui.components.ProgrammePicture
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.ui.components.rememberLiveNow

/*
 * The phone as the TV's remote for a channel (PLAN-LIVE-TV.md §4.1, L10d):
 * the LiveCast board on phones and TabLiveCast on tablets and in landscape.
 * The TV runs the channel; this shows what it reports, with the logo,
 * pictures and listings from this phone's own channel list.
 */

/**
 * The remote's On now list, as the boards draw it: from two channels before
 * the TV's, so it shows third, then the ones before those, so every channel
 * is still there.
 */
internal fun remoteRows(channels: List<LiveChannel>, currentId: String?): List<LiveChannel> {
    val at = channels.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: return channels
    val from = (at - 2).coerceAtLeast(0)
    return channels.drop(from) + channels.take(from)
}

/** What [castProblem] says, and which actions go with it. */
internal data class CastProblemWords(val title: String, val body: String, val retry: Boolean = false, val here: Boolean = false, val guide: Boolean = false)

/** "this phone" or "this tablet", in the remote's buttons. */
internal fun thisDevice(tablet: Boolean) = if (tablet) "this tablet" else "this phone"

/** The line under the remote's buttons: "Playing on Living Room TV", "Tuning 4.1 on Living Room TV…". */
internal fun castStatusLine(state: LiveCastState, device: String): String = when {
    state.notLive -> "Connected to $device"
    state.phase == LiveCastPhase.Playing -> "Playing on $device"
    state.phase == LiveCastPhase.Paused -> "Paused on $device"
    state.phase == LiveCastPhase.Tuning -> "Tuning ${state.number} on $device…"
    state.phase == LiveCastPhase.Stopped -> device
    else -> "Connected to $device"
}

/** The badge beside the channel: LIVE, TUNING, PAUSED, STOPPED or NOT PLAYING. */
internal fun castBadge(state: LiveCastState): String = when (state.phase) {
    LiveCastPhase.Tuning -> "TUNING"
    LiveCastPhase.Playing -> "LIVE"
    LiveCastPhase.Paused -> "PAUSED"
    LiveCastPhase.Stopped -> "STOPPED"
    else -> "NOT PLAYING"
}

/** Minutes until the paused TV gives its tuner back, rounded up: the board's [4] min. */
internal fun castPauseMinutes(state: LiveCastState): Int = ((state.pauseLeftSeconds ?: 300) + 59) / 60

/** The mini controller's second line; null when there is no mini controller (nothing cast, or a TV that can't play Live TV). */
internal fun castMiniLine(state: LiveCastState, device: String): String? = when {
    state.notLive || state.channelId == null -> null
    state.phase == LiveCastPhase.Playing -> "Playing on $device"
    state.phase == LiveCastPhase.Paused -> "Paused on $device · tuner back in ${castPauseMinutes(state)} min"
    state.phase == LiveCastPhase.Tuning -> "Tuning on $device…"
    state.phase == LiveCastPhase.Failed && state.reason == "tuners_busy" -> "$device · all tuners in use"
    state.phase == LiveCastPhase.Failed -> "$device · not playing"
    state.phase == LiveCastPhase.Stopped -> "Stopped from Activity on $device"
    else -> null
}

/**
 * Why nothing plays on the TV, in the boards' words, or null while it plays.
 * The app can't know how many tuners there are, so busy says "All tuners".
 */
internal fun castProblem(state: LiveCastState, device: String, number: String, tablet: Boolean): CastProblemWords? = when {
    state.notLive -> CastProblemWords(
        "$device can’t play Live TV yet",
        "It needs a newer WatchMyMedia on the TV. Movies and shows still cast to it, and $number can play ${if (tablet) "on this tablet" else "here"}.",
        here = true,
    )
    state.phase == LiveCastPhase.Failed && state.reason == "tuners_busy" ->
        CastProblemWords("All tuners are in use", "Nothing was taken from anyone watching. $number plays on $device once someone stops.", retry = true)
    state.phase == LiveCastPhase.Failed -> {
        val words = state.reason?.let(::liveTVError) ?: state.error ?: "The TV couldn’t play $number."
        val title = words.substringBefore(". ").removeSuffix(".")
        CastProblemWords(title, words.substringAfter(". ", "").ifBlank { "Try again, or choose another channel." }, retry = true)
    }
    state.phase == LiveCastPhase.Stopped -> CastProblemWords("Stopped from Activity", "An admin stopped $number on $device.", guide = true)
    state.phase == LiveCastPhase.Idle -> CastProblemWords("Not playing on $device", "$number isn’t playing on the TV now.", retry = true, here = true)
    else -> null
}

/**
 * The remote: [target] plays on the TV (cast when [castNow] and the TV isn't
 * already on it). Continue on this phone stops the TV and hands the channel
 * to [onHere]; Stop casting ends the Cast session and goes to the guide.
 */
@Composable
internal fun LiveRemote(
    target: LiveTarget,
    channels: List<LiveChannel>,
    listings: Boolean,
    castNow: Boolean,
    onExit: () -> Unit,
    onGuide: () -> Unit,
    onHere: (LiveChannel) -> Unit,
    quality: () -> StreamQuality,
    modifier: Modifier = Modifier,
) {
    val services = LocalAppServices.current
    val remote = services.cast.live
    val state by remote.state.collectAsStateWithLifecycle()
    val castState by services.cast.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var lastDevice by remember { mutableStateOf(state.deviceName) }
    state.deviceName?.let { lastDevice = it }
    val device = lastDevice ?: "TV"
    LaunchedEffect(Unit) {
        val s = remote.state.value
        if (castNow && !s.notLive && (s.channelId != target.channel.id || s.phase == LiveCastPhase.Idle)) remote.cast(target.channel, quality())
    }
    val now = rememberLiveNow()
    val format = rememberLiveClockFormat()
    val config = LocalConfiguration.current
    val tablet = config.smallestScreenWidthDp >= 600
    val wide = config.screenWidthDp > config.screenHeightDp
    // The TV's channel, with this phone's copy's logo and listings.
    val channel = channels.firstOrNull { it.id == state.channelId }
        ?: state.channelId?.let { LiveChannel(it, "", state.number, state.name, now = state.now, next = state.next) }
        ?: target.channel
    var quality by rememberSaveable { mutableStateOf(false) }
    val gone = castState !is CastState.Connected && castState !is CastState.Connecting
    val actions = RemoteActions(
        onBack = onExit,
        onGuide = onGuide,
        onToggle = remote::togglePlayPause,
        onLive = remote::goLive,
        onStep = { by -> liveChannelAfter(channels, channel.id, by)?.let(remote::channel) },
        onPick = { c -> if (c.id != state.channelId || !state.onTv) remote.channel(c) },
        onQuality = { quality = true },
        onRetry = { remote.cast(channel, state.quality) },
        onHere = {
            scope.launch {
                remote.stopAndWait()
                onHere(channel)
            }
        },
        onWatchHere = { onHere(channel) },
        onStop = {
            remote.stop()
            services.cast.disconnect()
            onGuide()
        },
    )
    if (wide) {
        RemoteWide(state, channel, channels, device, tablet, listings, now, format, actions, onQuality = { q -> remote.selectQuality(q) }, modifier)
    } else {
        RemotePortrait(state, channel, channels, device, tablet, listings, now, format, actions, modifier)
        if (quality && state.onTv) CastQualitySheet(state, device, channel.number, tablet, onClose = { quality = false }, onSelect = { q ->
            quality = false
            remote.selectQuality(q)
        })
    }
    if (state.askStillWatching && !gone) {
        // The TV asks after four hours without input; an answer here counts, as one there does.
        AlertDialog(
            onDismissRequest = {},
            containerColor = WmmColors.Surface2,
            titleContentColor = WmmColors.Text,
            textContentColor = WmmColors.TextSoft,
            title = { Text("Are you still watching?") },
            text = { Text("Keep watching ${channel.number} · ${channel.name} on $device? The tuner will be released if you don’t respond.") },
            confirmButton = { TextButton(onClick = { remote.answerStillWatching(true) }) { Text("Keep watching", color = WmmColors.Accent) } },
            dismissButton = { TextButton(onClick = actions.onStop) { Text("Stop casting", color = WmmColors.Accent) } },
        )
    }
    if (gone) {
        AlertDialog(
            onDismissRequest = onExit,
            containerColor = WmmColors.Surface2,
            titleContentColor = WmmColors.Text,
            textContentColor = WmmColors.TextSoft,
            title = { Text("Casting stopped") },
            text = { Text("$device is no longer playing ${channel.number}. Watch it on ${thisDevice(tablet)}?") },
            confirmButton = { TextButton(onClick = { onHere(channel) }) { Text("Continue on ${thisDevice(tablet)}", color = WmmColors.Accent) } },
            dismissButton = { TextButton(onClick = onExit) { Text("Leave", color = WmmColors.Text) } },
        )
    }
}

/** The remote's buttons, in one place for both layouts. */
internal class RemoteActions(
    val onBack: () -> Unit,
    val onGuide: () -> Unit,
    val onToggle: () -> Unit,
    val onLive: () -> Unit,
    val onStep: (Int) -> Unit,
    val onPick: (LiveChannel) -> Unit,
    val onQuality: () -> Unit,
    val onRetry: () -> Unit,
    val onHere: () -> Unit,
    val onWatchHere: () -> Unit,
    val onStop: () -> Unit,
)

/** LiveCast on a phone: the TV's picture area with the remote's buttons, then the banner or problem, then On now. */
@Composable
private fun RemotePortrait(
    state: LiveCastState,
    channel: LiveChannel,
    channels: List<LiveChannel>,
    device: String,
    tablet: Boolean,
    listings: Boolean,
    now: Long,
    format: LiveClockFormat,
    actions: RemoteActions,
    modifier: Modifier = Modifier,
) {
    val problem = castProblem(state, device, channel.number, tablet)
    // Ordered once, so changing channel doesn't move the rows under a finger.
    val rows = remember(channels, state.channelId != null) { remoteRows(channels, state.channelId) }
    Column(modifier.fillMaxSize().background(WmmColors.Ground).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF0B0B0C)).semantics { contentDescription = "Remote for $device" }) {
            if (state.onTv) ProgrammePicture(channel.onAt(now), channel, Modifier.fillMaxSize(), titleLettering = false)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onBack) { Icon(WmmIcons.Back, contentDescription = "Back to the guide", tint = Color.White) }
                Box(Modifier.weight(1f))
                CastButton()
            }
            RemoteButtons(state, channel, channels, device, actions, Modifier.align(Alignment.Center))
            if (state.phase == LiveCastPhase.Playing || state.phase == LiveCastPhase.Paused) {
                val label = if (state.phase == LiveCastPhase.Paused) "Paused, behind live" else "At live"
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(WmmColors.Line2).semantics { contentDescription = label }) {
                    Box(Modifier.fillMaxWidth(if (state.phase == LiveCastPhase.Paused) .3f else 1f).height(3.dp).background(WmmColors.Accent))
                }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            if (problem == null) {
                item("banner") { RemoteBanner(state, channel, now, format, Modifier.padding(16.dp)) }
                item("actions") {
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TonalButton("Guide", actions.onGuide, icon = WmmIcons.Calendar, compact = true)
                            TonalButton("Quality · ${state.liveQualityLabel()}", actions.onQuality, compact = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TonalButton("Continue on ${thisDevice(tablet)}", actions.onHere, icon = PhoneIcons.LiveTV, compact = true)
                            GoldTextButton("Stop casting", actions.onStop)
                        }
                    }
                }
            } else {
                item("problem") { RemoteProblem(state, channel, problem, tablet, actions, Modifier.padding(16.dp)) }
            }
            if (state.onTv || state.phase == LiveCastPhase.Failed && state.reason == "tuners_busy") {
                item("on-now") {
                    Text("On now · ${format.time(now)}", color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
                items(rows, key = { it.id }) { c ->
                    val on = c.id == state.channelId && state.onTv
                    OnNowRow(
                        c, now, format, watching = on, onClick = { if (!on) actions.onPick(c) },
                        watchingLabel = "On the TV",
                        description = (if (on) "On the TV: " else "Play on the TV: ") + "${c.number} ${c.name}" + (c.onAt(now)?.let { ", ${it.title}" } ?: ", no listings"),
                    )
                }
            }
        }
    }
}

/** The status line, Ch− / play-pause / Ch+, and LIVE or Go live with the pause note. */
@Composable
private fun RemoteButtons(state: LiveCastState, channel: LiveChannel, channels: List<LiveChannel>, device: String, actions: RemoteActions, modifier: Modifier = Modifier, numbers: (LiveChannel?, Boolean) -> String = { c, _ -> c?.number.orEmpty() }) {
    val playing = state.phase == LiveCastPhase.Playing || state.phase == LiveCastPhase.Paused
    val paused = state.phase == LiveCastPhase.Paused
    Column(modifier.padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.phase == LiveCastPhase.Tuning) CircularProgressIndicator(Modifier.size(18.dp), color = WmmColors.Accent, strokeWidth = 2.dp)
            else Icon(PhoneIcons.Cast, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(18.dp))
            Text(castStatusLine(state, device), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        if (playing) {
            val previous = liveChannelAfter(channels, channel.id, -1)
            val next = liveChannelAfter(channels, channel.id, 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                StepButton(PhoneIcons.ChannelDown, numbers(previous, false), "Channel down on the TV, ${previous?.number.orEmpty()} ${previous?.name.orEmpty()}", previous != null) { actions.onStep(-1) }
                IconButton(
                    onClick = actions.onToggle, modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White, contentColor = WmmColors.Ground),
                ) {
                    Icon(if (paused) WmmIcons.Play else WmmIcons.Pause, contentDescription = if (paused) "Play on the TV" else "Pause the TV", modifier = Modifier.size(32.dp))
                }
                StepButton(PhoneIcons.ChannelUp, numbers(next, true), "Channel up on the TV, ${next?.number.orEmpty()} ${next?.name.orEmpty()}", next != null) { actions.onStep(1) }
            }
            if (paused) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(onClick = actions.onLive, shape = CircleShape, color = Color.Black.copy(alpha = .55f), contentColor = Color.White) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(6.dp).background(Color.White, CircleShape))
                            Text("Go live", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text("Paused on the TV · the tuner goes back in ${castPauseMinutes(state)} min", color = WmmColors.TextSoft, fontSize = 12.sp)
                }
            } else if (state.atLiveEdge) {
                LivePill()
            } else {
                TextButton(onClick = actions.onLive) { Text("Go live", color = WmmColors.Accent) }
            }
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, description: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = .45f), contentColor = Color.White, modifier = modifier.semantics { contentDescription = description }) {
        Column(Modifier.width(56.dp).height(64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null)
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = WmmColors.TextSoft)
        }
    }
}

@Composable
private fun LivePill(modifier: Modifier = Modifier) {
    Row(modifier.background(Color(0xFFB42318), RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).background(Color.White, CircleShape))
        Text("LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CastBadge(state: LiveCastState, modifier: Modifier = Modifier) {
    val red = state.phase == LiveCastPhase.Playing
    Row(
        modifier.background(if (red) Color(0xFFB42318) else WmmColors.Line2, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).background(if (red) Color.White else WmmColors.TextSoft, CircleShape))
        Text(castBadge(state), color = if (red) Color.White else WmmColors.Text, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** The channel's row: logo, number, name and the badge. */
@Composable
private fun CastChannelLine(state: LiveCastState, channel: LiveChannel, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (channel.logo != null) ChannelLogo(channel, Modifier.width(44.dp))
        Text(channel.number, color = WmmColors.Text, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(channel.name, color = WmmColors.Text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        CastBadge(state)
    }
}

/** "On Living Room TV": the channel and what is on, as the local player's banner draws it. */
@Composable
private fun RemoteBanner(state: LiveCastState, channel: LiveChannel, now: Long, format: LiveClockFormat, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().semantics { contentDescription = "On ${state.deviceName ?: "the TV"}" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CastChannelLine(state, channel)
        LiveProgrammeBanner(channel, now, format)
    }
}

@Composable
private fun RemoteProblem(state: LiveCastState, channel: LiveChannel, problem: CastProblemWords, tablet: Boolean, actions: RemoteActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.phase == LiveCastPhase.Failed || state.phase == LiveCastPhase.Stopped) CastChannelLine(state, channel)
        Text(problem.title, color = WmmColors.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(problem.body, color = WmmColors.TextMuted, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (problem.retry) FilledButton("Try again", actions.onRetry)
            if (problem.here) FilledButton("Watch on ${thisDevice(tablet)}", actions.onWatchHere, icon = PhoneIcons.LiveTV)
            if (problem.guide) TonalButton("Guide", actions.onGuide)
            GoldTextButton("Stop casting", actions.onStop)
        }
    }
}

/** The phone's Quality sheet for the TV. */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CastQualitySheet(state: LiveCastState, device: String, number: String, tablet: Boolean, onClose: () -> Unit, onSelect: (StreamQuality) -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = WmmColors.Surface2) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Quality on $device", color = WmmColors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp))
            Text(if (state.automaticAvailable) "Automatic can adapt without retuning. Choosing another quality tunes $number at live." else "Changing quality tunes $number again on the TV, at live.", color = WmmColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            LIVE_QUALITIES.filter { it != StreamQuality.Auto || state.automaticAvailable }.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { onSelect(option) }.padding(horizontal = 24.dp, vertical = 8.dp)
                        .semantics { stateDescription = if (option == state.quality) "Selected" else "Not selected" },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    RadioButton(selected = option == state.quality, onClick = null)
                    Column {
                        Text(option.label, color = WmmColors.Text, fontSize = 15.sp)
                        if (option == StreamQuality.FullHd) Text("Live TV default, set by the server", color = WmmColors.TextMuted, fontSize = 12.sp)
                    }
                }
            }
            Text("The TV streams from the server, not through ${thisDevice(tablet)}.", color = WmmColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        }
    }
}

/**
 * TabLiveCast, on tablets and phones in landscape: the TV's picture area
 * with the remote's buttons and the banner, and beside it the panel with
 * Quality on the TV and On now.
 */
@Composable
private fun RemoteWide(
    state: LiveCastState,
    channel: LiveChannel,
    channels: List<LiveChannel>,
    device: String,
    tablet: Boolean,
    listings: Boolean,
    now: Long,
    format: LiveClockFormat,
    actions: RemoteActions,
    onQuality: (StreamQuality) -> Unit,
    modifier: Modifier = Modifier,
) {
    var panel by rememberSaveable { mutableStateOf(true) }
    val problem = castProblem(state, device, channel.number, tablet)
    val rows = remember(channels, state.channelId != null) { remoteRows(channels, state.channelId) }
    Row(modifier.fillMaxSize().background(WmmColors.Ground)) {
        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF0B0B0C))) {
            if (state.onTv) ProgrammePicture(channel.onAt(now), channel, Modifier.fillMaxSize(), titleLettering = false)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)))
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconButton(onClick = actions.onBack) { Icon(WmmIcons.Back, contentDescription = "Back to the guide", tint = Color.White) }
                    if (channel.logo != null) ChannelLogo(channel, Modifier.width(40.dp))
                    Text(channel.number, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(channel.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    CastBadge(state)
                    Box(Modifier.weight(1f))
                    CastButton()
                    if (!panel) TonalButton("Channels · ${state.liveQualityLabel()}", { panel = true }, icon = WmmIcons.Sort, compact = true)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (problem == null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            RemoteButtons(state, channel, channels, device, actions, numbers = { c, up -> (if (up) "Ch+ " else "Ch− ") + c?.number.orEmpty() })
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                TonalButton("Continue on ${thisDevice(tablet)}", actions.onHere, icon = PhoneIcons.LiveTV, compact = true)
                                GoldTextButton("Stop casting", actions.onStop)
                            }
                        }
                    } else {
                        RemoteProblem(state, channel, problem, tablet, actions, Modifier.width(460.dp).background(Color(0xEE101012), RoundedCornerShape(16.dp)).padding(20.dp))
                    }
                }
                if (problem == null && (state.onTv)) {
                    Box(Modifier.width(560.dp).background(Color.Black.copy(alpha = .78f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                        RemoteBanner(state, channel, now, format)
                    }
                }
            }
        }
        if (panel) {
            Surface(Modifier.width(360.dp).fillMaxHeight(), color = WmmColors.Surface) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (channel.logo != null) ChannelLogo(channel, Modifier.width(40.dp))
                        Text(channel.number, color = WmmColors.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Column(Modifier.weight(1f)) {
                            Text(channel.name, color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(PhoneIcons.Cast, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(14.dp))
                                Text(device, color = WmmColors.TextMuted, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        IconButton(onClick = { panel = false }) { Icon(WmmIcons.Close, contentDescription = "Close panel", tint = WmmColors.Text) }
                    }
                    if (state.onTv) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("Quality on the TV", color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 4.dp))
                                LIVE_QUALITIES.filter { it != StreamQuality.Auto || state.automaticAvailable }.forEach { option ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { onQuality(option) }.padding(vertical = 6.dp)
                                            .semantics { stateDescription = if (option == state.quality) "Selected" else "Not selected" },
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        RadioButton(selected = option == state.quality, onClick = null)
                                        Text(option.shortLabel, color = WmmColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                        Text(option.label.substringAfter(" · "), color = WmmColors.TextMuted, fontSize = 12.sp)
                                    }
                                }
                                Text(if (state.automaticAvailable) "Automatic can adapt without retuning. Other choices tune ${channel.number} at live. The TV streams from the server, not through ${thisDevice(tablet)}." else "Changing quality tunes ${channel.number} again at live. The TV streams from the server, not through ${thisDevice(tablet)}.", color = WmmColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                            }
                            HorizontalDivider(color = WmmColors.Line)
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        item("on-now") {
                            Text("On now · ${format.time(now)}", color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                        }
                        items(rows, key = { it.id }) { c ->
                            val on = c.id == state.channelId && state.onTv
                            OnNowRow(
                                c, now, format, watching = on, onClick = { if (!on) actions.onPick(c) }, pictureWidth = 92.dp,
                                watchingLabel = "On the TV",
                                description = (if (on) "On the TV: " else "Play on the TV: ") + "${c.number} ${c.name}" + (c.onAt(now)?.let { ", ${it.title}" } ?: ", no listings"),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * While a channel plays on the TV, above the bottom bar on every screen but
 * the players (the LiveCast board's guideWithMini): what is on with its
 * picture, where it plays, and play/pause. Tapping it opens the remote.
 * There is none for a TV that can't play Live TV.
 */
@Composable
fun LiveCastMini(channels: List<LiveChannel>, onOpen: (LiveChannel) -> Unit, modifier: Modifier = Modifier) {
    val remote = LocalAppServices.current.cast.live
    val state by remote.state.collectAsStateWithLifecycle()
    val device = state.deviceName ?: return
    val line = castMiniLine(state, device) ?: return
    val id = state.channelId ?: return
    val now = rememberLiveNow()
    val channel = channels.firstOrNull { it.id == id } ?: LiveChannel(id, "", state.number, state.name, now = state.now, next = state.next)
    val on = channel.onAt(now)
    val title = on?.title ?: channel.name
    Column(modifier.fillMaxWidth().background(WmmColors.Surface2)) {
        HorizontalDivider(thickness = 1.dp, color = WmmColors.Line)
        val progress = on?.progressAt(now) ?: 0f
        Box(
            Modifier.fillMaxWidth().height(2.dp).background(WmmColors.Line2)
                .semantics { contentDescription = "$title, ${(progress * 100).toInt()} percent through" }
                .drawBehind { drawRect(WmmColors.Accent, size = size.copy(width = size.width * progress)) },
        )
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.weight(1f).clickable(role = Role.Button) { onOpen(channel) }.semantics(mergeDescendants = true) { contentDescription = "Open the remote for $device, ${channel.number} $title" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProgrammePicture(on, channel, Modifier.width(72.dp).height(40.dp).clip(RoundedCornerShape(6.dp)), titleLettering = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${channel.number} · $title", color = WmmColors.Text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(PhoneIcons.Cast, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(14.dp))
                        Text(line, color = WmmColors.TextMuted, fontFamily = Inter, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (state.phase == LiveCastPhase.Playing || state.phase == LiveCastPhase.Paused) {
                val paused = state.phase == LiveCastPhase.Paused
                IconButton(onClick = remote::togglePlayPause, modifier = Modifier.size(48.dp)) {
                    Icon(if (paused) WmmIcons.Play else WmmIcons.Pause, contentDescription = if (paused) "Play on the TV" else "Pause the TV", tint = WmmColors.Text)
                }
            }
        }
    }
}
