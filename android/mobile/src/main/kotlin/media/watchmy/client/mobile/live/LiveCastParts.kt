package media.watchmy.client.mobile.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.model.LIVE_QUALITIES
import media.watchmy.client.model.LiveCastPhase
import media.watchmy.client.model.LiveCastState
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.liveChannelAfter
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.ChannelLogo
import media.watchmy.client.ui.components.WmmIcons

/** The status line, Ch− / play-pause / Ch+, and LIVE or Go live with the pause note. */
@Composable
internal fun RemoteButtons(state: LiveCastState, channel: LiveChannel, channels: List<LiveChannel>, device: String, actions: RemoteActions, modifier: Modifier = Modifier, numbers: (LiveChannel?, Boolean) -> String = { c, _ -> c?.number.orEmpty() }) {
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
internal fun CastBadge(state: LiveCastState, modifier: Modifier = Modifier) {
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
internal fun RemoteBanner(state: LiveCastState, channel: LiveChannel, now: Long, format: LiveClockFormat, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().semantics { contentDescription = "On ${state.deviceName ?: "the TV"}" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CastChannelLine(state, channel)
        LiveProgrammeBanner(channel, now, format)
    }
}

@Composable
internal fun RemoteProblem(state: LiveCastState, channel: LiveChannel, problem: CastProblemWords, tablet: Boolean, actions: RemoteActions, modifier: Modifier = Modifier) {
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
internal fun CastQualitySheet(state: LiveCastState, device: String, number: String, tablet: Boolean, onClose: () -> Unit, onSelect: (StreamQuality) -> Unit) {
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
