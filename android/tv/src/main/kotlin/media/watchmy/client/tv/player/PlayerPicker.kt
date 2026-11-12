package media.watchmy.client.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import media.watchmy.client.model.PlayerTracks
import media.watchmy.client.model.modeLabel
import media.watchmy.client.ui.WmmColors

/**
 * Whether the player offers subtitles: the file has its own (or downloaded)
 * tracks, or the server's OpenSubtitles search has answered. The Subtitles
 * button and the picker's section both follow it, so a file with none of its
 * own still reaches what was found online. Until the search answers, or when
 * the server has no provider, [PlayerTracks.online] is null.
 */
internal fun offersSubtitles(tracks: PlayerTracks): Boolean = tracks.subtitles.isNotEmpty() || tracks.online != null

/** The picker's sections in order, each with its rows. Empty sections (no audio tracks listed, say) are left out. */
internal fun pickerSections(tracks: PlayerTracks): List<Pair<PickerSection, List<PickerRow>>> = listOf(
    PickerSection.Audio to audioRows(tracks),
    PickerSection.Subtitles to if (offersSubtitles(tracks)) subtitleRows(tracks) else emptyList(),
    PickerSection.Quality to qualityRows(tracks),
).filter { it.second.isNotEmpty() }

/**
 * PlayerPicker: one side sheet with the Audio, Subtitles and Quality
 * sections, the selection marked in gold. Each section's selected row (or
 * its first) carries that section's requester in [focus], so the screen can
 * open the sheet on the section asked for. Choosing applies at once and
 * keeps the sheet open; Back closes it. The rows are built when [tracks]
 * change, not while the countdowns and the Skip button run.
 */
@Composable
internal fun PickerSheet(
    tracks: PlayerTracks,
    focus: Map<PickerSection, FocusRequester>,
    onChoose: (PickerChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .width(600.dp)
            .shadow(60.dp, ambientColor = Color.Black, spotColor = Color.Black)
            .background(WmmColors.Surface)
            .drawBehind { drawLine(WmmColors.Line, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
            .padding(horizontal = 40.dp, vertical = 48.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(30.dp)) {
            val sections = remember(tracks) { pickerSections(tracks) }
            for ((section, rows) in sections) {
                val focusAt = rows.indexOfFirst { it.selected }.coerceAtLeast(0)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        section.name.uppercase(),
                        Modifier.padding(horizontal = 18.dp),
                        style = playerText(17, FontWeight.Bold, WmmColors.TextMuted, tracking = 0.1),
                    )
                    // Why the server is not sending the original as is, under the Quality heading.
                    val mode = tracks.mode
                    if (section == PickerSection.Quality && mode != null && tracks.reasons.isNotEmpty()) {
                        Text(
                            "${modeLabel(mode)}: ${tracks.reasons.joinToString("; ")}",
                            Modifier.padding(horizontal = 18.dp),
                            style = playerText(17, color = WmmColors.TextFaint),
                        )
                    }
                    rows.forEachIndexed { i, row ->
                        key(row.choice) {
                            row.heading?.let {
                                Text(it, Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp), style = playerText(17, FontWeight.SemiBold, WmmColors.Accent))
                            }
                            val m = if (i == focusAt) Modifier.focusRequester(focus.getValue(section)) else Modifier
                            PickerOption(row, onClick = { onChoose(row.choice) }, modifier = m)
                            row.footer?.let {
                                Text(it, Modifier.padding(horizontal = 18.dp), style = playerText(17, color = WmmColors.TextFaint))
                            }
                        }
                    }
                }
            }
        }
        Text(
            "Back closes this panel. Changes apply at once; a burned-in subtitle restarts the stream from here.",
            Modifier.padding(start = 18.dp, end = 18.dp, top = 30.dp),
            style = playerText(17, color = WmmColors.TextFaint),
        )
    }
}

/**
 * A picker row: the label, its detail fainter beside it, and the gold mark
 * when selected. Focus fills it and rings it inside. A row that is not
 * enabled is drawn faint and focus passes it by.
 */
@Composable
private fun PickerOption(row: PickerRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) WmmColors.Surface3 else Color.Transparent)
            .focusRing(focused, 12.dp, outset = (-4).dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = row.enabled, onClick = onClick)
            .heightIn(min = 62.dp)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val text = buildAnnotatedString {
            append(row.label)
            if (row.detail != null) {
                withStyle(SpanStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal, color = WmmColors.TextMuted)) {
                    append(" ")
                    append(row.detail)
                }
            }
        }
        Text(text, Modifier.weight(1f), style = playerText(21, FontWeight.SemiBold, if (row.enabled) WmmColors.Text else WmmColors.TextFaint))
        if (row.selected) SelectedMark()
    }
}
