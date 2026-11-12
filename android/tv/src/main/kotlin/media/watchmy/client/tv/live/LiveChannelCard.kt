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
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors

/** LiveGuide's channel-only tile; unavailable entries remain focusable so their explanation can be read. */
@Composable
fun LiveChannelCard(channel: LiveChannel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableBox(
        onClick = onClick,
        modifier = modifier.height(150.dp).semantics {
            contentDescription = "${channel.number} ${channel.name}, ${if (channel.available) "Watch live" else "Unavailable"}"
        },
        cornerRadius = 14.dp,
        focusedScale = 1.04f,
    ) { focused ->
        Column(
            Modifier.fillMaxSize().background(if (focused) WmmColors.Surface2 else WmmColors.Surface, RoundedCornerShape(14.dp))
                .border(1.dp, WmmColors.Line, RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(channel.number, style = inter(40.sp, FontWeight.Bold), color = if (channel.available) WmmColors.Text else WmmColors.TextMuted)
            Column {
                Text(channel.name, style = inter(20.sp, FontWeight.SemiBold), color = if (channel.available) WmmColors.Text else WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (channel.available) "No listings" else "Unavailable", style = inter(16.sp), color = WmmColors.TextMuted)
            }
        }
    }
}
