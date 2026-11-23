package media.watchmy.client.tv.live

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.tv.material3.Text
import media.watchmy.client.model.LIVE_QUALITIES
import media.watchmy.client.model.LiveCastSender
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LivePlayerState
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.episodeLine
import media.watchmy.client.model.liveMinutesLeft
import media.watchmy.client.model.liveQualityLabel
import media.watchmy.client.model.liveTVError
import media.watchmy.client.model.nextAt
import media.watchmy.client.model.onAt
import media.watchmy.client.model.progressAt
import media.watchmy.client.presentation.live.nextLiveChannel
import media.watchmy.client.tv.components.FocusGrowth
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.OptionRow
import media.watchmy.client.tv.components.PanelSide
import media.watchmy.client.tv.components.SafeX
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvSidePanel
import media.watchmy.client.tv.components.TvTag
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.player.MatchFrameRate
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.LiveProgress
import media.watchmy.client.ui.components.ProgrammePicture
import media.watchmy.client.ui.components.WmmIcons

@Composable
internal fun LiveFakeBackdrop(channel: LiveChannel, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF172D38), WmmColors.Ground, Color(0xFF301C29)))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(channel.number, style = TvType.Display.copy(fontSize = 110.sp, lineHeight = 116.sp), color = WmmColors.Text.copy(alpha = .3f))
            Text(channel.name, style = TvType.CardTitle, color = TvPalette.Muted)
        }
    }
}

/** The dark under the banner and the controls, so their words read over any picture. */
@Composable
internal fun LiveChromeScrim(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 560.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .94f)))))
}

/** "0:31 behind live": how far a paused or rewound channel is behind what is on now. */
internal fun behindLiveLabel(behindMs: Long?): String {
    if (behindMs == null || behindMs < 1_000) return "Behind live"
    val seconds = behindMs / 1_000
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    val rest = (seconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$rest behind live" else "$minutes:$rest behind live"
}

/**
 * The channel's banner, along the foot of the screen at every change and
 * with the controls: the programme's picture, the channel's number and
 * name with Live (or how far behind live it is), what is on, how far it
 * has got, the time left, its times and what is next. With the controls
 * hidden, the keys that do something no button shows.
 */
@Composable
internal fun LiveChannelBanner(
    state: LivePlayerState,
    channel: LiveChannel,
    controls: Boolean,
    modifier: Modifier = Modifier,
    listings: Boolean = false,
    now: Long = 0L,
    format: LiveClockFormat? = null,
    cast: LiveCastSender? = null,
    behindMs: Long? = null,
) {
    val on = channel.onAt(now).takeIf { listings && format != null }
    val next = channel.nextAt(now).takeIf { listings && format != null }
    Row(
        modifier.fillMaxWidth().padding(start = SafeX, end = SafeX, bottom = if (controls) 158.dp else SafeY),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        ProgrammePicture(on, channel, Modifier.width(320.dp).clip(RoundedCornerShape(14.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(channel.number, style = TvType.Title, color = WmmColors.Text, maxLines = 1)
                Text(channel.name, style = TvType.CardTitle, color = WmmColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                when {
                    state.status == PlayerStatus.Paused -> PlainTag("Paused", Modifier.padding(start = 8.dp))
                    state.atLiveEdge -> RecTag("Live", Modifier.padding(start = 8.dp))
                    else -> PlainTag(behindLiveLabel(behindMs), Modifier.padding(start = 8.dp))
                }
            }
            if (on != null && format != null) {
                Text(
                    buildAnnotatedString {
                        append(on.title)
                        on.episodeLine()?.let { line -> withStyle(SpanStyle(color = WmmColors.TextSoft, fontWeight = FontWeight.Medium)) { append(" · $line") } }
                    },
                    style = TvType.Heading, color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                LiveProgress(on.progressAt(now), Modifier.width(640.dp).padding(top = 4.dp), height = 8.dp, track = Color.White.copy(alpha = .22f))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = WmmColors.Text)) { append(liveMinutesLeft(now, on.endMs)) }
                        append(" · ${format.span(on.startMs, on.endMs)}")
                        next?.let { append(" · next: ${it.title} at ${format.time(it.startMs)}") }
                    },
                    style = TvType.Body, color = WmmColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text("No listings for this channel", style = TvType.Body, color = WmmColors.TextSoft)
            }
            if (state.status == PlayerStatus.Paused && cast == null) {
                val pause = state.session?.pauseRemainingSeconds ?: 300
                Text(
                    "The tuner is given back in ${pause / 60}:${(pause % 60).toString().padStart(2, '0')} if this stays paused. Play goes live if it has been.",
                    style = TvType.Second, color = TvPalette.Muted,
                )
            }
            // Cast Connect: the TV keeps ▲ ▼ and OK; the rest is on the phone.
            if (cast != null) Text("▲ ▼ Change channel · OK Controls", style = TvType.Caption, color = TvPalette.Muted)
        }
        cast?.let { CastFromBox(it.name) }
        if (!controls && cast == null) {
            KeyHints(listOf(KeyHint("▲ ▼", "Change channel"), KeyHint("◀", if (listings) "On now" else "Channels"), KeyHint("OK", "Controls")), vertical = true)
        }
    }
}

/** An outlined tag in its own words (`.tag`), not upper case: "Watching", "0:31 behind live". */
@Composable
internal fun PlainTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.border(2.dp, TvPalette.Line3, RoundedCornerShape(8.dp)).heightIn(min = 38.dp).padding(horizontal = 10.dp, vertical = 3.dp),
        style = TvType.Caption.copy(fontWeight = FontWeight.Bold),
        color = WmmColors.TextSoft,
        maxLines = 1,
    )
}

@Composable
internal fun LiveTuning(state: LivePlayerState, channel: LiveChannel, modifier: Modifier = Modifier) {
    Row(modifier.background(Color.Black.copy(alpha = .65f), RoundedCornerShape(20.dp)).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        CircularProgressIndicator(Modifier.size(42.dp), color = WmmColors.Accent, strokeWidth = 4.dp)
        Text(if (state.status == PlayerStatus.Loading) "Tuning ${channel.number}…" else "Buffering ${channel.number}…", style = TvType.CardTitle, color = WmmColors.Text)
    }
}

@Composable
internal fun LiveNotice(message: String, modifier: Modifier = Modifier) {
    Text(message, style = TvType.Second, color = WmmColors.Text, textAlign = TextAlign.Center,
        modifier = modifier.widthIn(max = 1100.dp).background(Color(0xEE101012), RoundedCornerShape(22.dp)).padding(horizontal = 32.dp, vertical = 18.dp).semantics { liveRegion = LiveRegionMode.Polite })
}

/**
 * The live player's controls, along the foot inside the safe area: Pause,
 * Go live (dimmed while live, and still focusable), Record (which records
 * this programme and then reads Recording), On now (Channels without
 * listings), Quality, and Guide at the right. A cast channel keeps Pause,
 * Go live, the channel buttons and Stop casting; the rest is on the phone.
 */
@Composable
internal fun LiveControls(
    state: LivePlayerState,
    channels: List<LiveChannel>,
    current: LiveChannel,
    playFocus: FocusRequester,
    channelFocus: FocusRequester,
    qualityFocus: FocusRequester,
    onToggle: () -> Unit,
    onLive: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onChannels: () -> Unit,
    onQuality: () -> Unit,
    onGuide: () -> Unit,
    modifier: Modifier = Modifier,
    listings: Boolean = false,
    cast: Boolean = false,
    record: LiveRecordButton? = null,
    recordFocus: FocusRequester? = null,
) {
    Row(modifier.fillMaxWidth().padding(start = SafeX, end = SafeX, bottom = SafeY).focusGroup(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        val paused = state.status == PlayerStatus.Paused
        TvButton(if (paused) "Play" else "Pause", onToggle, Modifier.focusRequester(playFocus), icon = if (paused) WmmIcons.Play else WmmIcons.Pause, growFrom = TransformOrigin(0f, 0.5f))
        TvButton("Go live", onLive, enabled = !state.atLiveEdge)
        if (cast) {
            val previous = nextLiveChannel(channels, current.id, -1)
            val next = nextLiveChannel(channels, current.id, 1)
            TvButton("Ch − ${previous?.number.orEmpty()}", onPrevious, enabled = previous != null)
            TvButton("Ch + ${next?.number.orEmpty()}", onNext, enabled = next != null)
            Spacer(Modifier.weight(1f))
            TvButton("Stop casting", onGuide)
            return@Row
        }
        if (record != null) TvButton(
            if (record.recording) "Recording" else if (record.busy) "Recording…" else "Record",
            record.onClick,
            if (recordFocus != null) Modifier.focusRequester(recordFocus) else Modifier,
            icon = WmmIcons.Record, iconTint = RecRim,
        )
        TvButton(if (listings) "On now" else "Channels", onChannels, Modifier.focusRequester(channelFocus))
        TvButton("Quality · ${state.liveQualityLabel()}", onQuality, Modifier.focusRequester(qualityFocus))
        Spacer(Modifier.weight(1f))
        TvButton("Guide", onGuide)
    }
}

/** The live player's Record button: whether this programme records, and what OK does. */
internal class LiveRecordButton(val recording: Boolean, val busy: Boolean, val onClick: () -> Unit)

/**
 * On now, from the left over the picture: every channel with what it has on,
 * how long is left and what is next, the one playing marked Watching. Focus
 * opens on the channel playing (or the one picked last). OK watches a
 * channel and closes; ▶ and Back close it.
 */
@Composable
internal fun LiveChannelsPanel(
    channels: List<LiveChannel>,
    currentId: String,
    focus: FocusRequester,
    memory: FocusMemory,
    onWatch: (LiveChannel) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    listings: Boolean = false,
    now: Long = 0L,
    format: LiveClockFormat? = null,
) {
    val remembered = memory.key?.takeIf { candidate -> channels.any { it.id == candidate } } ?: currentId
    val selectedIndex = channels.indexOfFirst { it.id == remembered }.coerceAtLeast(0)
    Box(modifier.fillMaxSize()) {
        TvSidePanel(
            title = if (listings) "On now" else "Channels",
            side = PanelSide.Left,
            width = 800.dp,
            context = if (format != null) "Every channel · ${format.time(now)}" else "Every channel",
            modifier = Modifier.focusGroup(),
        ) {
            if (channels.isEmpty()) {
                Text("The channels are no longer available. The guide reloads them.", style = TvType.Body, color = WmmColors.TextSoft)
                TvButton("Close", onClose, Modifier.focusRequester(focus))
            } else LazyColumn(
                Modifier.weight(1f),
                state = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex),
                // Room for the focused row's ring.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp, horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
                    ChannelRow(
                        channel, watching = channel.id == currentId, listings = listings, now = now,
                        onClick = { memory.focused(channel.id); onWatch(channel) },
                        modifier = Modifier.focusMemory(memory, channel.id).then(if (index == selectedIndex) Modifier.focusRequester(focus) else Modifier),
                    )
                }
            }
            KeyHints(listOf(KeyHint("OK", "Watch"), KeyHint("▶", "Close")))
        }
    }
}

/** A channel in On now (`.chrow`): its number, what it has on, the time left and what is next. */
@Composable
private fun ChannelRow(channel: LiveChannel, watching: Boolean, listings: Boolean, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val on = channel.onAt(now).takeIf { listings }
    val next = channel.nextAt(now).takeIf { listings }
    FocusableBox(onClick = onClick, modifier = modifier.fillMaxWidth(), cornerRadius = 16.dp, focusedScale = FocusGrowth.ROW, growFrom = TransformOrigin(0f, 0.5f)) { focused ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 104.dp).background(if (focused) TvPalette.Lift else Color.Transparent, RoundedCornerShape(16.dp)).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text(channel.number, style = TvType.Heading.copy(fontWeight = FontWeight.Bold), color = if (channel.available) WmmColors.Text else TvPalette.Muted, modifier = Modifier.width(86.dp), maxLines = 1)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(on?.title ?: channel.name, style = TvType.BodyStrong, color = if (channel.available) WmmColors.Text else TvPalette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        !channel.available -> liveTVError(channel.reason)
                        on == null -> if (listings) "${channel.name} · no listings" else "Plays live"
                        next != null -> "${liveMinutesLeft(now, on.endMs)} · then ${next.title}"
                        else -> liveMinutesLeft(now, on.endMs)
                    },
                    style = TvType.Caption, color = TvPalette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (watching) PlainTag("Watching")
        }
    }
}

/** Quality, from the right: the fixed choices (and Automatic where the server has it), focus on the one in use. */
@Composable
internal fun LiveQualityPanel(quality: StreamQuality, focus: FocusRequester, onSelect: (StreamQuality) -> Unit, modifier: Modifier = Modifier, automatic: Boolean = false) {
    Box(modifier.fillMaxSize()) {
        TvSidePanel(
            title = "Quality",
            context = if (automatic) "Automatic changes with the connection, without tuning again. Another choice starts the channel again at live." else "Changing quality starts the channel again at live.",
            modifier = Modifier.focusGroup(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LIVE_QUALITIES.filter { it != StreamQuality.Auto || automatic }.forEach { option ->
                    OptionRow(option.label, option == quality, { onSelect(option) }, modifier = if (option == quality) Modifier.focusRequester(focus) else Modifier)
                }
            }
            Text("A choice here is this TV’s, for Live TV.", style = TvType.Second, color = TvPalette.Muted)
        }
    }
}

/** What the player says when a channel stops or won't start, and what its buttons do. */
internal enum class ProblemAction { KeepWatching, Retry, WatchRecording, OpenRecordings }

internal data class LiveProblem(val title: String, val body: String, val note: String?, val action: ProblemAction, val actionLabel: String, val leaveLabel: String)

/**
 * The player's problem in words: why it stopped and what to do, never the
 * tuner's make or a technical code. [takeover] names the recording that
 * took the tuner, when the server said.
 */
internal fun liveProblem(state: LivePlayerState, channel: LiveChannel, takeover: String? = null): LiveProblem = when {
    state.askStillWatching -> LiveProblem(
        "Still watching ${channel.number}?",
        "Nothing has been pressed for four hours. Live TV stops soon, so the tuner is free for someone else.",
        null, ProblemAction.KeepWatching, "Keep watching", "Stop",
    )
    state.takeoverRecording != null || state.reason == "recording" -> LiveProblem(
        "Stopped for a recording",
        "${takeover ?: "A recording"} needed the tuner you were using.",
        "Recordings come first, so a show someone asked for is never missed.",
        if (state.takeoverRecording != null) ProblemAction.WatchRecording else ProblemAction.OpenRecordings,
        if (state.takeoverRecording != null) "Watch it from the start" else "Open Recordings",
        "Back to the guide",
    )
    state.reason == "tuners_busy" -> LiveProblem(
        "The tuners are busy",
        "Every tuner is in use, for watching or for a recording. Try again when one comes free, or watch a channel that’s already on.",
        null, ProblemAction.Retry, "Try again", "Back to the guide",
    )
    state.reason == "no_signal" -> LiveProblem(
        "No signal on ${channel.number}",
        "The antenna isn’t picking this channel up right now. Weather can do that, and it usually comes back by itself.",
        null, ProblemAction.Retry, "Try again", "Back to the guide",
    )
    state.reason in listOf("signal_lost", "stream_stalled") -> LiveProblem(
        "${channel.number} stopped coming through",
        "The broadcast stopped. It usually comes back when you try again; a lower quality can help.",
        null, ProblemAction.Retry, "Try again", "Back to the guide",
    )
    state.reason == "tuner_unavailable" -> LiveProblem(
        "The tuner isn’t answering",
        "Live TV can’t reach the antenna tuner. Whoever looks after the server can check that it is on and connected.",
        null, ProblemAction.Retry, "Try again", "Back to the guide",
    )
    else -> {
        val error = state.error ?: state.reason?.let(::liveTVError)
        LiveProblem(
            error?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: "Live TV stopped",
            error ?: "The tuner was given back. Tune again to watch live.",
            null, ProblemAction.Retry, "Tune again", "Back to the guide",
        )
    }
}

/**
 * A channel that stopped or won't start, full screen: the channel at the
 * top left, the problem in words a third of the way down, and its buttons,
 * the first focused.
 */
@Composable
internal fun LivePlayerProblem(problem: LiveProblem, channel: LiveChannel, onAction: () -> Unit, onLeave: () -> Unit, focus: FocusRequester, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2A2A30), Color(0xFF0B0B0D))))) {
        Row(Modifier.padding(start = SafeX, top = 58.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(channel.number, style = TvType.Title, color = WmmColors.Text)
            Text(channel.name, style = TvType.CardTitle, color = WmmColors.TextSoft, maxLines = 1)
        }
        LiveStateMessage(
            problem.title, problem.body, Modifier.padding(start = SafeX, top = 330.dp).focusGroup().semantics { liveRegion = LiveRegionMode.Assertive },
            note = problem.note,
        ) {
            TvButton(problem.actionLabel, onAction, Modifier.focusRequester(focus), style = TvButtonStyle.Primary, growFrom = TransformOrigin(0f, 0.5f))
            TvButton(problem.leaveLabel, onLeave)
        }
    }
}

/** Reuse the TV's display preference without adding file playback fields to live state. */
@OptIn(UnstableApi::class)
@Composable
internal fun LiveVideo(player: Player, modifier: Modifier = Modifier) {
    val device by LocalAppServices.current.preferences.device.collectAsStateWithLifecycle()
    var frameRate by remember(player) { mutableStateOf<Float?>(null) }
    DisposableEffect(player) {
        fun read(tracks: Tracks): Float? = tracks.groups.asSequence()
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group -> (0 until group.length).asSequence().filter { group.isTrackSelected(it) }.map { group.getTrackFormat(it).frameRate } }
            .firstOrNull { it > 0 }
        frameRate = read(player.currentTracks)
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { frameRate = read(tracks) }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    MatchFrameRate(frameRate, enabled = device.matchFrameRate)
    ContentFrame(player, modifier.fillMaxSize(), contentScale = ContentScale.Fit)
}
