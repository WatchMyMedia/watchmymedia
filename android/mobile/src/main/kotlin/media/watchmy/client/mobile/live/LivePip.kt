package media.watchmy.client.mobile.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import media.watchmy.client.mobile.player.PipAction
import media.watchmy.client.mobile.player.PipButton
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LivePlayerState
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.liveTVError
import media.watchmy.client.model.onAt
import media.watchmy.client.ui.WmmColors

/**
 * The picture-in-picture window's buttons while live TV plays (the LivePiP
 * board): the previous channel, play or pause, and the next channel, each
 * channel button named with the channel it goes to.
 */
internal fun livePipButtons(previous: String?, next: String?, playing: Boolean) = listOf(
    PipButton(PipAction.ChannelDown, previous?.let { "Previous channel, $it" } ?: "Previous channel"),
    PipButton(PipAction.PlayPause, if (playing) "Pause" else "Play"),
    PipButton(PipAction.ChannelUp, next?.let { "Next channel, $it" } ?: "Next channel"),
)

/** How long the channel's tag shows in the window after the channel changes. */
internal const val LIVE_PIP_TAG_MS = 4_000L

/**
 * What the picture-in-picture window shows over the video (the LivePiP
 * board): "Tuning 2.1…" while a channel tunes, why it couldn't tune, and
 * for a few seconds after the channel changes, or while paused, a tag with
 * the channel and what is on.
 */
@Composable
internal fun BoxScope.LivePipOverlay(state: LivePlayerState, channel: LiveChannel, now: Long) {
    var tagged by remember(channel.id) { mutableStateOf(true) }
    LaunchedEffect(channel.id) {
        delay(LIVE_PIP_TAG_MS)
        tagged = false
    }
    when (state.status) {
        PlayerStatus.Loading -> Row(
            Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), color = WmmColors.Accent, strokeWidth = 2.dp)
            Text("Tuning ${channel.number}…", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        PlayerStatus.Error, PlayerStatus.Ended -> Column(
            Modifier.fillMaxSize().background(WmmColors.Ground.copy(alpha = .85f)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Can’t tune ${channel.number}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                state.error ?: state.message ?: liveTVError(state.reason),
                color = WmmColors.TextSoft,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        else -> {
            val paused = state.status == PlayerStatus.Paused
            if (tagged || paused) {
                Row(
                    Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color.Black.copy(alpha = .65f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(channel.number, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(channel.name, color = WmmColors.TextSoft, fontSize = 11.sp, maxLines = 1)
                    channel.onAt(now)?.let { Text(it.title, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                    if (paused) Text("· Paused", color = WmmColors.Accent, fontSize = 11.sp)
                }
            }
        }
    }
}
