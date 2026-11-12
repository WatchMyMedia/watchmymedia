package media.watchmy.client.mobile.components

import androidx.annotation.OptIn
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.cast.Cast
import androidx.media3.cast.MediaRouteButton
import androidx.media3.cast.rememberMediaRouteButtonState
import androidx.media3.common.util.UnstableApi
import media.watchmy.client.model.CastState
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.fractionOf
import media.watchmy.client.model.showsButton
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.WmmIcons

/**
 * The Cast button, whenever this app can cast, whether or not a Cast device
 * has been found yet ([showsButton]); gold while casting. Tapping it opens the
 * device chooser (the system's Output Switcher on Android 17, which needs no
 * local network access), or the controls for the connected device. [overArt]
 * puts it on a translucent circle, as the other buttons over a backdrop.
 */
@OptIn(UnstableApi::class)
@Composable
fun CastButton(overArt: Boolean = false) {
    val cast = LocalAppServices.current.cast
    val state by cast.state.collectAsStateWithLifecycle()
    if (!state.showsButton) return
    val context = LocalContext.current
    val inspecting = LocalInspectionMode.current
    // Fakes and previews have no Cast SDK behind them: draw the glyph alone.
    val sdk = remember(context, inspecting) { !inspecting && runCatching { !Cast.getSingletonInstance(context).needsInitialization() }.getOrDefault(false) }
    val tint = if (state is CastState.Connected) WmmColors.Accent else WmmColors.Text
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(if (overArt) Color.Black.copy(alpha = 0.35f) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) {
            if (sdk) {
                MediaRouteButton(Modifier, rememberMediaRouteButtonState())
            } else {
                Icon(PhoneIcons.Cast, contentDescription = "Cast", tint = tint, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/**
 * While casting, above the bottom bar on every screen but the player: what
 * the TV is playing, how far in, and play/pause. Tapping it opens the
 * player as the TV's remote.
 */
@Composable
fun CastMiniController(onOpen: (PlayRequest) -> Unit) {
    val services = LocalAppServices.current
    val cast = services.cast
    val castState by cast.state.collectAsStateWithLifecycle()
    val playing by cast.nowPlaying.collectAsStateWithLifecycle()
    val device = (castState as? CastState.Connected)?.deviceName ?: return
    val request = playing ?: return
    val state by cast.remote.state.collectAsStateWithLifecycle()
    val progress = cast.remote.progress.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().background(WmmColors.Surface2)) {
        HorizontalDivider(thickness = 1.dp, color = WmmColors.Line)
        // Read as the line is drawn, so the position's ticks redraw it without recomposing the controller.
        Box(
            Modifier.fillMaxWidth().height(2.dp).background(WmmColors.Line2).drawBehind {
                val p = progress.value
                drawRect(WmmColors.Accent, size = size.copy(width = size.width * fractionOf(p.positionMs, p.durationMs)))
            },
        )
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open the remote") { onOpen(request) }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(
                url = services.library.imageUrl(state.backdropPath ?: request.backdropPath, ImageSize.W300),
                seed = state.title.ifEmpty { request.title },
                contentDescription = null,
                modifier = Modifier.width(72.dp).height(40.dp).clip(RoundedCornerShape(6.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    state.title.ifEmpty { request.title },
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = WmmColors.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(PhoneIcons.Cast, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(14.dp))
                    Text(
                        "Playing on $device",
                        style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = WmmColors.TextMuted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val busy = state.status == PlayerStatus.Loading || state.status == PlayerStatus.Buffering
            val paused = state.status != PlayerStatus.Playing && !busy
            IconButton(onClick = { cast.remote.togglePlayPause() }, modifier = Modifier.size(48.dp)) {
                Icon(if (paused) WmmIcons.Play else WmmIcons.Pause, contentDescription = if (paused) "Play" else "Pause", tint = WmmColors.Text)
            }
        }
    }
}
