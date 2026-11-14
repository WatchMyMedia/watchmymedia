package media.watchmy.client.mobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.shortLabel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors

/**
 * A skip kind and its four modes as an M3 segmented button row. Where the
 * row is too narrow for the label beside the buttons (under ~400 dp), the
 * label sits above them.
 */
@Composable
internal fun SkipRow(label: String, mode: SkipMode, onChange: (SkipMode) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inline = (maxWidth - 32.dp - 64.dp - 12.dp) / 4 >= 76.dp
        val title = @Composable { m: Modifier -> Text(label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = WmmColors.Text), modifier = m) }
        if (inline) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                title(Modifier.width(64.dp))
                SkipButtons(mode, onChange, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                title(Modifier)
                SkipButtons(mode, onChange, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SkipButtons(mode: SkipMode, onChange: (SkipMode) -> Unit, modifier: Modifier = Modifier) {
    val modes = SkipMode.entries.map { it to it.shortLabel }
    SingleChoiceSegmentedButtonRow(modifier) {
        modes.forEachIndexed { i, (m, text) ->
            SegmentedButton(
                selected = m == mode,
                onClick = { onChange(m) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = WmmColors.Accent,
                    activeContentColor = WmmColors.OnAccent,
                    activeBorderColor = WmmColors.Line3,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = WmmColors.TextSoft,
                    inactiveBorderColor = WmmColors.Line3,
                ),
                contentPadding = PaddingValues(horizontal = 2.dp),
                label = { Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp), maxLines = 1, softWrap = false) },
            )
        }
    }
}
