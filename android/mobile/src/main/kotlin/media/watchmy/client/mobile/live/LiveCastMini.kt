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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.model.LiveCastPhase
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.onAt
import media.watchmy.client.model.progressAt
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.ProgrammePicture
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveNow

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
