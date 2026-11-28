package media.watchmy.client.tv.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
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
import media.watchmy.client.model.SOUND_HEADING
import media.watchmy.client.model.SoundSetting
import media.watchmy.client.tv.components.tryFocus
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
    extras: TvPickerExtras = TvPickerExtras(),
    /**
     * A sound row's small list of Off, Medium and High, in place of the
     * panel's rows until a choice or Back; the screen holds it, as Back
     * reaches the screen first.
     */
    choosing: SoundSetting? = null,
    onChoosing: (SoundSetting?) -> Unit = {},
) {
    val quality = section.panel == PickerSection.Quality
    var area by remember { mutableStateOf(PanelArea.Track) }
    val soundRows = remember { SoundSetting.entries.associateWith { FocusRequester() } }
    val choiceFocus = remember { FocusRequester() }
    val timingRow = remember { FocusRequester() }
    // The row a list was opened from, which focus goes back to.
    var back by remember { mutableStateOf<SoundSetting?>(null) }
    LaunchedEffect(choosing) {
        if (choosing != null) {
            back = choosing
            choiceFocus.tryFocus()
        } else {
            back?.let { soundRows.getValue(it).tryFocus() }
            back = null
        }
    }
    val sound = tracks.sound.takeIf { !quality }
    val timing = tracks.timing?.takeIf { !quality && it.changeable }
    val open = choosing
    TvSidePanel(
        title = when {
            open != null -> open.label
            quality -> "Quality"
            else -> "Audio & subtitles"
        },
        modifier = modifier,
        kicker = if (open != null) "Audio & subtitles · $SOUND_HEADING" else null,
        context = if (quality) "Lower it if the picture keeps stopping." else null,
        // Focus on the timing: the cues beside the panel show through.
        dim = if (area.timing && open == null) TimingDim else TvPalette.Dim,
    ) {
        if (open != null && sound != null) {
            SoundChoices(open, sound.level(open), choiceFocus) { level ->
                onChoosing(null)
                if (level != sound.level(open)) extras.onSound(open, level)
            }
            Box(Modifier.weight(1f))
            PickerKeys(onTiming = false, ok = "Chooses", back = "Returns")
            return@TvSidePanel
        }
        val sections = remember(tracks) { pickerSections(tracks) }
        // With the keys' line at the foot, the rows take the room down to it (a spacer beside them would take half).
        val keys = !quality && (sound != null || timing != null)
        Column(
            Modifier.weight(1f, fill = keys).verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
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
                        PickerOption(row, onClick = { onChoose(row.choice) }, modifier = m.onFocusChanged { if (it.isFocused) area = PanelArea.Track })
                        row.footer?.let {
                            Text(it, Modifier.padding(horizontal = 24.dp), style = TvType.Caption, color = TvPalette.Muted)
                        }
                    }
                }
                if (s == PickerSection.Audio && sound != null) {
                    Column(Modifier.onFocusChanged { if (it.hasFocus) area = PanelArea.Sound }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SoundRows(sound, soundRows, onOpen = onChoosing, onSound = extras.onSound)
                    }
                }
                if (s == PickerSection.Subtitles && timing != null) {
                    TimingRows(timing, extras.admin, timingRow, extras.onTiming) { on ->
                        area = when (on) {
                            TimingFocus.Row -> PanelArea.Timing
                            TimingFocus.Reset -> if (timing.resetsToShared) PanelArea.ResetShared else PanelArea.Reset
                            TimingFocus.SaveForEveryone -> PanelArea.SaveForEveryone
                            TimingFocus.Saved -> PanelArea.Saved
                        }
                    }
                }
            }
        }
        if (quality) {
            // Every choice here is kept for this TV, as the TV's own Settings keep it.
            Text("Your choice becomes this TV’s default.", Modifier.padding(horizontal = 24.dp), style = TvType.Second, color = TvPalette.Muted)
        } else if (keys) {
            PickerKeys(onTiming = area == PanelArea.Timing, ok = area.ok, back = "Closes this panel")
        }
    }
}

/** Which part of Audio & subtitles has focus, for the keys' line ([ok]) and the dim beside the panel. */
private enum class PanelArea(val timing: Boolean, val ok: String) {
    Track(false, "Chooses"),
    Sound(false, "Opens its choices"),
    Timing(true, "Chooses"),
    Reset(true, "Puts it back in step"),
    ResetShared(true, "Goes back to the timing for everyone"),
    SaveForEveryone(true, "Saves for everyone"),
    Saved(true, "Saved"),
}

/** Lighter over the video while the timing has focus, so its cues can be seen moving (`rgba(0,0,0,.35)`). */
private val TimingDim = androidx.compose.ui.graphics.Color(0x59000000)

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
