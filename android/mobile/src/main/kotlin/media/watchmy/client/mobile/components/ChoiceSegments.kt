package media.watchmy.client.mobile.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors

private val SegmentLabel = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)

/**
 * One of a few choices in M3 segmented buttons, as Sound and Subtitle size
 * draw it: the chosen one filled gold with its tick. [busy]: the chosen one
 * spins while its change goes through, and the others wait. [enabled] false
 * greys them all (a download's sound, which can't change).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceSegments(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
    name: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyLabel: String? = null,
) {
    val colors = SegmentedButtonDefaults.colors(
        activeContainerColor = WmmColors.Accent,
        activeContentColor = WmmColors.OnAccent,
        activeBorderColor = WmmColors.Line3,
        inactiveContainerColor = WmmColors.Ground.copy(alpha = 0f),
        inactiveContentColor = WmmColors.TextSoft,
        inactiveBorderColor = WmmColors.Line3,
        // Busy: the chosen one stays gold and spins; disabled, it greys with the rest.
        disabledActiveContainerColor = if (busy) WmmColors.Accent else WmmColors.Text.copy(alpha = 0.12f),
        disabledActiveContentColor = if (busy) WmmColors.OnAccent else WmmColors.Text.copy(alpha = 0.38f),
        disabledActiveBorderColor = if (busy) WmmColors.Line3 else WmmColors.Text.copy(alpha = 0.12f),
        disabledInactiveContainerColor = WmmColors.Ground.copy(alpha = 0f),
        disabledInactiveContentColor = WmmColors.Text.copy(alpha = 0.38f),
        disabledInactiveBorderColor = if (busy) WmmColors.Line3 else WmmColors.Text.copy(alpha = 0.12f),
    )
    SingleChoiceSegmentedButtonRow(
        modifier.fillMaxWidth().semantics {
            contentDescription = name
            if (busy && busyLabel != null) stateDescription = busyLabel
        },
    ) {
        options.forEachIndexed { index, option ->
            val chosen = option == selected
            SegmentedButton(
                selected = chosen,
                onClick = { if (!chosen) onPick(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                enabled = enabled && !busy,
                colors = colors,
                icon = {
                    if (busy && chosen) CircularProgressIndicator(Modifier.size(14.dp), color = WmmColors.OnAccent, strokeWidth = 2.dp)
                    else SegmentedButtonDefaults.Icon(chosen)
                },
                label = { Text(label(option), style = SegmentLabel, maxLines = 1, overflow = TextOverflow.Clip) },
            )
        }
    }
}
