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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import media.watchmy.client.model.onAt
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
