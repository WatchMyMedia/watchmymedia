package media.watchmy.client.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import media.watchmy.client.tv.player.MatchFrameRate
import media.watchmy.client.ui.LocalAppServices
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import media.watchmy.client.model.LIVE_QUALITIES
import media.watchmy.client.model.liveQualityLabel
import media.watchmy.client.model.LiveCastSender
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.episodeLine
import media.watchmy.client.model.liveMinutesLeft
import media.watchmy.client.model.liveNextLine
import media.watchmy.client.model.nextAt
import media.watchmy.client.model.onAt
import media.watchmy.client.model.progressAt
import media.watchmy.client.model.tags
import media.watchmy.client.model.LivePlayerState
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.liveTVError
import media.watchmy.client.presentation.live.nextLiveChannel
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.OptionRow
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.ChannelLogo
import media.watchmy.client.ui.components.LiveProgress
import media.watchmy.client.ui.components.ProgrammePicture
import media.watchmy.client.ui.components.ProgrammeTag

@Composable
internal fun LiveFakeBackdrop(channel: LiveChannel, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF172D38), WmmColors.Ground, Color(0xFF301C29)))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(channel.number, style = inter(110.sp, FontWeight.Bold), color = WmmColors.Text.copy(alpha = .3f))
            Text(channel.name, style = inter(30.sp), color = WmmColors.TextMuted)
        }
    }
}

/** The channel banner on every change; with [listings], the logo box, picture, what is on with its times, and Next. */
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
) {
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f))))
        .padding(start = 58.dp, end = 58.dp, top = 70.dp, bottom = if (controls) 170.dp else 56.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ChannelBannerCard(state, channel, listings, now, format)
            // Cast Connect: who cast, and that the rest is on their phone.
            cast?.let { CastFromBox(it.name) }
        }
        if (state.quality == StreamQuality.Auto) Text(state.liveQualityLabel(), style = inter(19.sp, FontWeight.SemiBold), color = WmmColors.Accent)
        val pause = state.session?.pauseRemainingSeconds ?: 300
        val note = when {
            cast != null -> "▲ ▼ Change channel · OK Controls"
            state.status == PlayerStatus.Paused -> "Paused · tuner released in ${pause / 60}:${(pause % 60).toString().padStart(2, '0')} · Resume goes live if the buffer expires."
            else -> "▲ ▼ Change channel when controls are hidden · ◀ ${if (listings) "On now" else "Channels"} · OK Controls"
        }
        Text(note, style = inter(19.sp), color = WmmColors.TextMuted)
    }
}

@Composable
private fun ChannelBannerCard(state: LivePlayerState, channel: LiveChannel, listings: Boolean, now: Long, format: LiveClockFormat?) {
    if (listings && format != null) ListingsBanner(state, channel, now, format) else Row(Modifier.width(1000.dp).background(Color(0xEE101012), RoundedCornerShape(20.dp)).border(1.dp, WmmColors.Line2, RoundedCornerShape(20.dp)).padding(26.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Text(channel.number, style = inter(56.sp, FontWeight.Bold), color = WmmColors.Text)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(channel.name, style = inter(30.sp, FontWeight.Bold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("No listings for this channel", style = inter(19.sp), color = WmmColors.TextMuted)
            }
            val label = when {
                state.status == PlayerStatus.Paused -> "PAUSED"
                state.atLiveEdge -> "LIVE"
                else -> "BEHIND LIVE"
            }
            Text(label, style = inter(17.sp, FontWeight.Bold), color = Color.White,
                modifier = Modifier.background(if (state.atLiveEdge) Color(0xFFB42318) else WmmColors.Surface3, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 7.dp))
        }
}

@Composable
private fun ListingsBanner(state: LivePlayerState, channel: LiveChannel, now: Long, format: LiveClockFormat) {
    val on = channel.onAt(now)
    val next = channel.nextAt(now)
    Row(Modifier.width(1160.dp).background(Color(0xEE101012), RoundedCornerShape(20.dp)).border(1.dp, WmmColors.Line2, RoundedCornerShape(20.dp)).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (channel.logo != null) ChannelLogo(channel, Modifier.width(64.dp))
            Text(channel.number, style = inter(44.sp, FontWeight.Bold), color = WmmColors.Text)
            Text(channel.name, style = inter(16.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ProgrammePicture(on, channel, Modifier.width(260.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (on == null) {
                Text(channel.name, style = inter(30.sp, FontWeight.Bold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("No listings for this channel", style = inter(19.sp), color = WmmColors.TextMuted)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(on.title, style = inter(30.sp, FontWeight.Bold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    on.tags().forEach { ProgrammeTag(it, fontSize = 14.sp, height = 24.dp) }
                }
                on.episodeLine()?.let { Text(it, style = inter(19.sp), color = WmmColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(format.span(on.startMs, on.endMs), style = inter(18.sp), color = WmmColors.TextMuted)
                    LiveProgress(on.progressAt(now), Modifier.weight(1f), height = 5.dp)
                    Text(liveMinutesLeft(now, on.endMs), style = inter(18.sp), color = WmmColors.TextMuted)
                }
                next?.let { Text(liveNextLine(it, format), style = inter(18.sp), color = WmmColors.TextMuted) }
            }
        }
        LiveStateLabel(state)
    }
}

@Composable
private fun LiveStateLabel(state: LivePlayerState) {
    val label = when {
        state.status == PlayerStatus.Paused -> "PAUSED"
        state.atLiveEdge -> "LIVE"
        else -> "BEHIND LIVE"
    }
    Text(label, style = inter(17.sp, FontWeight.Bold), color = Color.White,
        modifier = Modifier.background(if (state.atLiveEdge) Color(0xFFB42318) else WmmColors.Surface3, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
internal fun LiveTuning(state: LivePlayerState, channel: LiveChannel, modifier: Modifier = Modifier) {
    Row(modifier.background(Color.Black.copy(alpha = .65f), RoundedCornerShape(18.dp)).padding(horizontal = 30.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        CircularProgressIndicator(Modifier.size(42.dp), color = WmmColors.Accent, strokeWidth = 4.dp)
        Text(if (state.status == PlayerStatus.Loading) "Tuning ${channel.number}…" else "Buffering ${channel.number}…", style = inter(28.sp), color = WmmColors.Text)
    }
}

@Composable
internal fun LiveNotice(message: String, modifier: Modifier = Modifier) {
    Text(message, style = inter(21.sp, lineHeight = 28.sp), color = WmmColors.Text, textAlign = TextAlign.Center,
        modifier = modifier.width(1100.dp).background(Color(0xEE101012), RoundedCornerShape(22.dp)).padding(horizontal = 28.dp, vertical = 16.dp).semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
internal fun LiveControls(
    state: LivePlayerState,
    channels: List<LiveChannel>,
    current: LiveChannel,
    playFocus: FocusRequester,
    channelFocus: FocusRequester,
    qualityFocus: FocusRequester,
    numberFocus: FocusRequester,
    onToggle: () -> Unit,
    onLive: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onChannels: () -> Unit,
    onQuality: () -> Unit,
    onNumber: () -> Unit,
    onGuide: () -> Unit,
    modifier: Modifier = Modifier,
    listings: Boolean = false,
    cast: Boolean = false,
) {
    val previous = nextLiveChannel(channels, current.id, -1)
    val next = nextLiveChannel(channels, current.id, 1)
    Row(modifier.fillMaxWidth().padding(start = 58.dp, end = 58.dp, bottom = 56.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        TvButton(if (state.status == PlayerStatus.Paused) "Play" else "Pause", onToggle, Modifier.focusRequester(playFocus), style = TvButtonStyle.Primary, size = TvButtonSize.Medium)
        if (!state.atLiveEdge) TvButton("Go live", onLive, size = TvButtonSize.Medium)
        TvButton("Ch − ${previous?.number.orEmpty()}", onPrevious, size = TvButtonSize.Medium, enabled = previous != null)
        TvButton("Ch + ${next?.number.orEmpty()}", onNext, size = TvButtonSize.Medium, enabled = next != null)
        if (cast) {
            // Cast Connect: the channel list and Quality are on the phone; Stop casting takes Full guide's place.
            Box(Modifier.weight(1f))
            TvButton("Stop casting", onGuide, size = TvButtonSize.Medium)
            return@Row
        }
        TvButton(if (listings) "On now" else "Channels", onChannels, Modifier.focusRequester(channelFocus), size = TvButtonSize.Medium)
        TvButton("Quality ${state.liveQualityLabel()}", onQuality, Modifier.focusRequester(qualityFocus), size = TvButtonSize.Medium)
        TvButton("Go to channel", onNumber, Modifier.focusRequester(numberFocus), size = TvButtonSize.Medium)
        Box(Modifier.weight(1f))
        TvButton("Full guide", onGuide, size = TvButtonSize.Medium)
    }
}

/** ◀ Channels; with [listings], On now: each channel's picture, logo and number, what is on and its progress. */
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
    Column(modifier.width(640.dp).fillMaxHeight().background(Color(0xFA0F0F10)).border(1.dp, WmmColors.Line).padding(top = 48.dp, bottom = 40.dp).focusGroup()) {
        Text(if (listings && format != null) "On now · ${format.time(now)}" else "Channels", style = inter(30.sp, FontWeight.Bold), color = WmmColors.Text, modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp))
        if (channels.isEmpty()) {
            Text("Channels are no longer available. Return to the full guide to refresh.", style = inter(22.sp, lineHeight = 30.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(28.dp))
            // A focus target remains available even if permissions changed while opening the panel.
            TvButton("Close", onClose, Modifier.padding(28.dp).focusRequester(focus))
        } else LazyColumn(Modifier.weight(1f), state = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
            itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
                FocusableBox(onClick = { memory.focused(channel.id); onWatch(channel) }, modifier = Modifier.fillMaxWidth().focusMemory(memory, channel.id).then(if (index == selectedIndex) Modifier.focusRequester(focus) else Modifier), cornerRadius = 0.dp, focusedScale = 1f, ringOffset = (-4).dp) { focused ->
                    if (listings) OnNowPanelRow(channel, channel.id == currentId, focused, now) else Row(Modifier.fillMaxWidth().background(if (focused) WmmColors.Surface2 else Color.Transparent).padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(channel.number, style = inter(26.sp, FontWeight.Bold), color = WmmColors.Text, modifier = Modifier.width(80.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(channel.name, style = inter(22.sp, FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (channel.available) "No listings" else liveTVError(channel.reason), style = inter(17.sp, lineHeight = 23.sp), color = WmmColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (channel.id == currentId) Text("Watching", style = inter(16.sp, FontWeight.SemiBold), color = WmmColors.Accent)
                    }
                }
            }
        }
        Text("OK watches the channel. Right or Back closes this list.", style = inter(17.sp, lineHeight = 24.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp))
    }
}

@Composable
private fun OnNowPanelRow(channel: LiveChannel, watching: Boolean, focused: Boolean, now: Long) {
    val on = channel.onAt(now)
    Row(Modifier.fillMaxWidth().background(if (focused) WmmColors.Surface2 else Color.Transparent).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.width(150.dp).clip(RoundedCornerShape(8.dp))) {
            ProgrammePicture(on, channel, Modifier.fillMaxWidth())
            if (on != null) Row(Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (channel.logo != null) ChannelLogo(channel, Modifier.width(26.dp))
                Text(channel.number, style = inter(16.sp, FontWeight.Bold), color = WmmColors.Text)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(on?.title ?: channel.name, style = inter(22.sp, FontWeight.SemiBold), color = if (channel.available) WmmColors.Text else WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (on != null) LiveProgress(on.progressAt(now), Modifier.fillMaxWidth(), height = 4.dp)
            Text(
                when {
                    !channel.available -> liveTVError(channel.reason)
                    on == null -> "No listings"
                    else -> "${channel.name} · ${liveMinutesLeft(now, on.endMs)}"
                },
                style = inter(16.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (watching) Text("Watching", style = inter(16.sp, FontWeight.SemiBold), color = WmmColors.Accent)
    }
}

@Composable
internal fun LiveQualityPanel(quality: StreamQuality, focus: FocusRequester, onSelect: (StreamQuality) -> Unit, modifier: Modifier = Modifier, automatic: Boolean = false) {
    Column(modifier.width(600.dp).fillMaxHeight().background(Color(0xFA0F0F10)).border(1.dp, WmmColors.Line).padding(44.dp).focusGroup(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Quality", style = inter(30.sp, FontWeight.Bold), color = WmmColors.Text)
        Text(if (automatic) "Automatic can adapt without retuning. Choosing another quality starts the channel at live." else "Changing quality starts the channel again at live.", style = inter(21.sp, lineHeight = 30.sp), color = WmmColors.TextMuted)
        LIVE_QUALITIES.filter { it != StreamQuality.Auto || automatic }.forEach { option ->
            OptionRow(option.label, option == quality, { onSelect(option) }, modifier = if (option == quality) Modifier.focusRequester(focus) else Modifier)
        }
        Text("Back closes this panel.", style = inter(17.sp), color = WmmColors.TextMuted)
    }
}

@Composable
internal fun LivePlayerPrompt(title: String, message: String, action: String, onAction: () -> Unit, onLeave: () -> Unit, focus: FocusRequester, modifier: Modifier = Modifier, leaveLabel: String = "Back to channels") {
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = .75f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(980.dp).background(WmmColors.Surface, RoundedCornerShape(28.dp)).padding(48.dp).focusGroup(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(title, style = inter(40.sp, FontWeight.Bold), color = WmmColors.Text)
            Text(message, style = inter(24.sp, lineHeight = 34.sp), color = WmmColors.TextMuted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                TvButton(action, onAction, Modifier.focusRequester(focus), style = TvButtonStyle.Primary)
                TvButton(leaveLabel, onLeave)
            }
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
