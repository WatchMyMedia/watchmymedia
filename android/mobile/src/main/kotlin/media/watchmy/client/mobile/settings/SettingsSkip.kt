package media.watchmy.client.mobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.model.SaveFailure
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.label
import media.watchmy.client.model.shortLabel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors

/**
 * A skip part and its four modes as segmented buttons: "Intro" and
 * Never · Ask · Auto · Auto 3 s on a phone, "Skip intro" and the full words
 * on a tablet. Where a phone's row is too narrow for the label beside the
 * buttons, the label sits above them. Saving… and a failure go under it.
 */
@Composable
internal fun SkipRow(
    label: String,
    mode: SkipMode,
    onChange: (SkipMode) -> Unit,
    enabled: Boolean,
    tablet: Boolean,
    saving: Boolean,
    failure: SaveFailure?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // A tablet's page beside the list can be narrow (a tablet held upright): its words then go above the buttons.
            val inline = if (tablet) maxWidth >= 560.dp else (maxWidth - 32.dp - 70.dp - 10.dp) / 4 >= 70.dp
            val full = tablet && inline
            val title = @Composable { m: Modifier -> Text(label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = WmmColors.Text), modifier = m) }
            if (inline) {
                Row(Modifier.heightIn(min = if (tablet) 52.dp else 50.dp).padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (tablet) 16.dp else 10.dp)) {
                    if (tablet) title(Modifier.weight(1f)) else title(Modifier.width(70.dp))
                    SkipButtons(mode, onChange, enabled, full, if (tablet) Modifier.width(400.dp) else Modifier.weight(1f))
                }
            } else {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    title(Modifier)
                    SkipButtons(mode, onChange, enabled, full, Modifier.fillMaxWidth())
                }
            }
        }
        if (saving) SavingLine(Modifier.padding(start = 16.dp, bottom = 6.dp))
        RowFailure(failure, mode.label, onRetry)
    }
}

@Composable
private fun SkipButtons(mode: SkipMode, onChange: (SkipMode) -> Unit, enabled: Boolean, fullWords: Boolean, modifier: Modifier = Modifier) {
    val modes = SkipMode.entries.map { it to if (fullWords) it.label else it.shortLabel }
    SingleChoiceSegmentedButtonRow(modifier) {
        modes.forEachIndexed { i, (m, text) ->
            SegmentedButton(
                selected = m == mode,
                onClick = { onChange(m) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = WmmColors.Accent,
                    activeContentColor = WmmColors.OnAccent,
                    activeBorderColor = WmmColors.Line3,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = WmmColors.TextSoft,
                    inactiveBorderColor = WmmColors.Line3,
                    disabledActiveContainerColor = WmmColors.Accent,
                    disabledActiveContentColor = WmmColors.OnAccent,
                    disabledActiveBorderColor = WmmColors.Line3,
                    disabledInactiveContainerColor = Color.Transparent,
                    disabledInactiveContentColor = WmmColors.TextSoft,
                    disabledInactiveBorderColor = WmmColors.Line3,
                ),
                modifier = Modifier.height(40.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
                label = { Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp), maxLines = 1, softWrap = false) },
            )
        }
    }
}
