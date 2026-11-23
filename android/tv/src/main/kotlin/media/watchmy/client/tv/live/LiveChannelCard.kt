package media.watchmy.client.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.ui.WmmColors

/**
 * A channel as a tile, for a server without listings: its number large and
 * its name under it. OK watches it. One that can't play stays focusable, so
 * its explanation can be read, and says so.
 */
@Composable
fun LiveChannelCard(channel: LiveChannel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    FocusableBox(
        onClick = onClick,
        modifier = modifier.height(150.dp).semantics {
            contentDescription = "${channel.number} ${channel.name}, ${if (channel.available) "Watch live" else "Unavailable"}"
        },
        cornerRadius = 18.dp,
        focusedScale = TileGrowth,
    ) { focused ->
        Column(
            Modifier.fillMaxSize()
                .background(if (focused) TvPalette.Lift else WmmColors.Surface2, shape)
                .border(2.dp, TvPalette.Lift, shape)
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, androidx.compose.ui.Alignment.CenterVertically),
        ) {
            Text(channel.number, style = TvType.Title.copy(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold), color = if (channel.available) WmmColors.Text else TvPalette.Muted, maxLines = 1)
            Text(
                if (channel.available) channel.name else "${channel.name} · unavailable",
                style = TvType.Second, color = TvPalette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A tile grows a little more than a button: it is smaller than a card and has only numerals and a name. */
private const val TileGrowth = 1.06f
