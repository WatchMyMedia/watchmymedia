package media.watchmy.client.tv.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import media.watchmy.client.model.PlaybackProgress
import media.watchmy.client.model.PlayerTracks
import media.watchmy.client.tv.components.OptionRow
import media.watchmy.client.tv.components.PanelGroup
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvSidePanel
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.WidePanelWidth
import media.watchmy.client.ui.WmmColors

/**
 * Whether the player offers subtitles: the file has its own (or downloaded)
 * tracks, or the server's OpenSubtitles search has answered. The Audio &
 * subtitles panel's Subtitles group follows it, so a file with none of its
 * own still reaches what was found online. Until the search answers, or when
 * the server has no provider, [PlayerTracks.online] is null.
 */
internal fun offersSubtitles(tracks: PlayerTracks): Boolean = tracks.subtitles.isNotEmpty() || tracks.online != null

/** The panels' sections in order, each with its rows. Empty sections (no audio tracks listed, say) are left out; Playback info has no rows. */
internal fun pickerSections(tracks: PlayerTracks): List<Pair<PickerSection, List<PickerRow>>> = listOf(
    PickerSection.Audio to audioRows(tracks),
    PickerSection.Subtitles to if (offersSubtitles(tracks)) subtitleRows(tracks) else emptyList(),
    PickerSection.Quality to qualityRows(tracks),
).filter { it.second.isNotEmpty() }

/** Whether the Audio & subtitles panel has anything to pick. */
internal fun offersTracks(tracks: PlayerTracks): Boolean = tracks.audio.isNotEmpty() || offersSubtitles(tracks)

/**
 * The panel a section is in: Audio and Subtitles share Audio & subtitles;
 * Quality and Playback info have their own.
 */
internal val PickerSection.panel: PickerSection
    get() = if (this == PickerSection.Subtitles) PickerSection.Audio else this

/**
 * Audio & subtitles (Audio and Subtitles groups in one panel), or Quality
 * ([section]), from the right. Each section's selected row (or its first)
 * carries that section's requester in [focus], so the screen can open the
 * panel on the row asked for. Choosing applies at once and keeps the panel
 * open; Back closes it. The rows are built when [tracks] change, not while
 * the countdowns and the Skip button run.
 */
@Composable
internal fun PickerSheet(
    tracks: PlayerTracks,
    section: PickerSection,
    focus: Map<PickerSection, FocusRequester>,
    onChoose: (PickerChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val quality = section.panel == PickerSection.Quality
    TvSidePanel(
        title = if (quality) "Quality" else "Audio & subtitles",
        modifier = modifier,
        context = if (quality) "Lower it if the picture keeps stopping." else null,
    ) {
        val sections = remember(tracks) { pickerSections(tracks) }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((s, rows) in sections) {
                if ((s == PickerSection.Quality) != quality) continue
                val focusAt = rows.indexOfFirst { it.selected }.coerceAtLeast(0)
                if (!quality) PanelGroup(if (s == PickerSection.Audio) "Audio" else "Subtitles", Modifier.semantics { heading() })
                rows.forEachIndexed { i, row ->
                    key(row.choice) {
                        row.heading?.let {
                            Text(it, Modifier.padding(start = 24.dp, top = 12.dp), style = TvType.Caption.copy(fontWeight = FontWeight.SemiBold), color = WmmColors.TextSoft)
                        }
                        val m = if (i == focusAt) Modifier.focusRequester(focus.getValue(s)) else Modifier
                        PickerOption(row, onClick = { onChoose(row.choice) }, modifier = m)
                        row.footer?.let {
                            Text(it, Modifier.padding(horizontal = 24.dp), style = TvType.Caption, color = TvPalette.Muted)
                        }
                    }
                }
            }
        }
        if (quality) {
            // Every choice here is kept for this TV, as the TV's own Settings keep it.
            Text("Your choice becomes this TV’s default.", Modifier.padding(horizontal = 24.dp), style = TvType.Second, color = TvPalette.Muted)
        }
    }
}

/** A row of a panel: the choice and its words under it, a gold tick when it is the one in use. Dimmed when it can't be chosen. */
@Composable
private fun PickerOption(row: PickerRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OptionRow(
        text = row.label,
        selected = row.selected,
        onClick = { if (row.enabled) onClick() },
        modifier = modifier,
        description = row.detail,
        enabled = row.enabled,
    )
}

/**
 * Playback info, from the right and wider than the other panels: what is
 * happening in one sentence ([playbackStory]), then the video, the sound,
 * the subtitles, the quality, the stream, how far ahead it has loaded and
 * the server. Close has the focus; Back closes it too.
 */
@Composable
internal fun PlaybackInfoPanel(
    tracks: PlayerTracks,
    serverName: String?,
    progress: () -> PlaybackProgress,
    closeFocus: FocusRequester,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvSidePanel(title = "Playback info", modifier = modifier, width = WidePanelWidth) {
        Text(playbackStory(tracks), style = TvType.CardTitle.copy(fontWeight = FontWeight.Medium), color = WmmColors.Text)
        val lines = remember(tracks, serverName) { playbackInfo(tracks, serverName) }
        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .drawBehind { drawLine(TvPalette.Lift, Offset(0f, 0f), Offset(size.width, 0f), 2.dp.toPx()) }
                .padding(top = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (line in lines) InfoRow(line.what, line.value)
            Buffered(progress)
        }
        TvButton("Close", onClose, Modifier.padding(top = 12.dp).focusRequester(closeFocus))
    }
}

/** How far ahead the player has loaded, read here alone, so the panel's other rows stay put as it moves. */
@Composable
private fun Buffered(progress: () -> PlaybackProgress) {
    val seconds = progress().bufferedMs / 1_000
    InfoRow("Buffered", "$seconds s ahead")
}

@Composable
private fun InfoRow(what: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
        Text(what, Modifier.width(InfoKeyWidth), style = TvType.Second, color = TvPalette.Muted)
        Text(value, Modifier.weight(1f), style = TvType.Second, color = WmmColors.Text)
    }
}

/** The width of Playback info's left column ("Subtitles", "Buffered"). */
private val InfoKeyWidth = 150.dp
