package media.watchmy.client.tv.player

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import media.watchmy.client.model.PlaybackProgress
import media.watchmy.client.model.behindRecording
import media.watchmy.client.model.recordingEdgeMs
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.episodeCode
import media.watchmy.client.model.formatClock
import media.watchmy.client.model.fractionOf
import media.watchmy.client.model.scrubberBands
import media.watchmy.client.model.tracks
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.PrefetchSeekThumbnails
import media.watchmy.client.ui.components.ScrubberLayout
import media.watchmy.client.ui.components.SeekThumbnail
import media.watchmy.client.ui.components.SeekThumbnailStyle
import media.watchmy.client.ui.components.WmmIcons
import java.util.Date

/** The chrome's focus targets, owned by the screen so it can move focus between them. */
internal class ChromeFocus(
    val back: FocusRequester,
    val skip: FocusRequester?,
    val scrubber: FocusRequester,
    val playPause: FocusRequester,
    val openers: Map<PickerSection, FocusRequester>,
)

/** What the chrome's controls do. */
internal class ChromeActions(
    val onBack: () -> Unit,
    val onSeekStep: (forward: Boolean) -> Unit,
    val onTogglePlay: () -> Unit,
    val onOpenPicker: (PickerSection) -> Unit,
    val onPlayNext: () -> Unit,
    val onFocusArea: (FocusArea) -> Unit,
    val onSeekTo: (Long) -> Unit = {},
)

/** The dark washes behind the chrome, top and bottom, as in PlayerControls. */
@Composable
internal fun BoxScope.ChromeShades() {
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent))))
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(420.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
}

/**
 * PlayerControls without the Skip button: back, title and episode line, the
 * stream pill and clock, the scrubber with elapsed and remaining time, and
 * the transport row, ending in Next episode when there is one. [positionMs] is the scrubber's position (a seek's
 * target while the viewer is seeking) and [progress] the player's; only the
 * scrubber row reads them, so the rest stays put while the position moves.
 * While [seek] has a target, a seek thumbnail shows it above the scrubber
 * with the step beside the time (PlayerScrub), when the file has them.
 */
@Composable
internal fun BoxScope.PlayerChrome(
    state: PlayerUiState,
    positionMs: () -> Long,
    progress: () -> PlaybackProgress,
    focus: ChromeFocus,
    actions: ChromeActions,
    seek: SeekState? = null,
) {
    ChromeShades()
    TitleBlock(
        state,
        Modifier.align(Alignment.TopStart).padding(start = 58.dp, top = 48.dp),
        back = {
            ControlButton(
                onClick = actions.onBack,
                modifier = Modifier
                    .focusRequester(focus.back)
                    .focusProperties { down = focus.scrubber }
                    .onFocusChanged { if (it.hasFocus) actions.onFocusArea(FocusArea.Back) },
                icon = PlayerIcons.Back,
                contentDescription = "Back",
            )
        },
    )
    Row(
        Modifier.align(Alignment.TopEnd).padding(end = 58.dp, top = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        streamSummary(state.tracks)?.let {
            Text(
                it,
                Modifier
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                style = playerText(18, color = WmmColors.TextSoft),
            )
        }
        WallClock()
    }
    ScrubberRow(state, positionMs, progress, seek, focus, actions, Modifier.align(Alignment.BottomCenter).padding(start = 58.dp, end = 58.dp, bottom = 144.dp))
    // Go to now only while the play is more than 10 s behind what has recorded; focus goes back to the scrubber as it leaves.
    val behind by remember(progress) { derivedStateOf { state.growing != null && progress().behindRecording() } }
    if (behind) media.watchmy.client.tv.components.TvButton(
        "Go to now",
        { actions.onSeekTo(progress().recordingEdgeMs()); runCatching { focus.scrubber.requestFocus() } },
        Modifier.align(Alignment.TopEnd).padding(top = 120.dp, end = 58.dp).onFocusChanged { if (it.isFocused) actions.onFocusArea(FocusArea.Buttons) },
        size = media.watchmy.client.tv.components.TvButtonSize.Medium,
    )
    TransportRow(state, focus, actions, Modifier.align(Alignment.BottomCenter).padding(start = 58.dp, end = 58.dp, bottom = 56.dp))
}

/** The title and episode line, with [back] before them; the picker shows it dimmed with "paused" added. */
@Composable
internal fun TitleBlock(state: PlayerUiState, modifier: Modifier = Modifier, pausedNote: Boolean = false, back: (@Composable () -> Unit)? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
        back?.invoke()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.growing != null) Text("● Recording", style = playerText(20, FontWeight.Bold, color = WmmColors.Error))
            Text(state.title, style = playerText(32, FontWeight.Bold, tracking = -0.01), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(state.subtitle, "paused".takeIf { pausedNote && state.status == PlayerStatus.Paused }).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = playerText(21, color = WmmColors.TextSoft), maxLines = 1)
        }
    }
}

@Composable
private fun ScrubberRow(
    state: PlayerUiState,
    shownMs: () -> Long,
    progress: () -> PlaybackProgress,
    seek: SeekState?,
    focus: ChromeFocus,
    actions: ChromeActions,
    modifier: Modifier,
) {
    val positionMs = shownMs()
    val (playerMs, _, bufferedMs) = progress()
    var focused by remember { mutableStateOf(false) }
    val clock = playerText(22, FontWeight.SemiBold, tabular = true)
    val bands = remember(state.segments, state.durationMs) { scrubberBands(state.segments, state.durationMs) }
    val trickplay = state.trickplay
    trickplay?.let { PrefetchSeekThumbnails(it) { progress().positionMs } }
    // No times until the duration is known (the first moments of loading).
    val known = state.durationMs > 0
    ScrubberLayout(
        fraction = { fractionOf(shownMs(), state.durationMs) },
        gap = 22.dp,
        previewLift = 31.dp,
        elapsed = { Text(if (known) formatClock(positionMs) else "", Modifier.width(88.dp), style = clock, maxLines = 1) },
        track = {
            ScrubberTrack(
                progress = fractionOf(positionMs, state.durationMs),
                buffered = fractionOf(playerMs + bufferedMs, state.durationMs),
                bands = bands,
                focused = focused,
                modifier = Modifier
                    .focusRequester(focus.scrubber)
                    .focusProperties {
                        up = focus.skip ?: focus.back
                        down = focus.playPause
                    }
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) actions.onFocusArea(FocusArea.Scrubber)
                    }
                    .semantics {
                        contentDescription = "Position ${formatClock(positionMs)} of ${formatClock(state.durationMs)}"
                        progressBarRangeInfo = ProgressBarRangeInfo(positionMs.toFloat(), 0f..state.durationMs.coerceAtLeast(1).toFloat())
                    }
                    .focusable(),
            )
        },
        remaining = { Text(if (known) "−" + formatClock(state.durationMs - positionMs) else "", Modifier.width(88.dp), style = clock, textAlign = TextAlign.End, maxLines = 1) },
        modifier = modifier.fillMaxWidth(),
        preview = if (trickplay != null && seek?.targetMs != null) {
            { SeekThumbnail(trickplay, shownMs, SeekThumbnailStyle.Tv, chip = { SeekStepChip(stepMs = { seek.stepMs }) }) }
        } else {
            null
        },
    )
}

@Composable
private fun TransportRow(state: PlayerUiState, focus: ChromeFocus, actions: ChromeActions, modifier: Modifier) {
    val playing = state.status == PlayerStatus.Playing || state.status == PlayerStatus.Buffering
    Row(
        modifier
            .fillMaxWidth()
            .focusProperties { up = focus.scrubber }
            .onFocusChanged { if (it.hasFocus) actions.onFocusArea(FocusArea.Buttons) },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlButton({ actions.onSeekStep(false) }, icon = WmmIcons.Replay10, contentDescription = "Back 10 seconds")
        ControlButton(
            actions.onTogglePlay,
            Modifier.focusRequester(focus.playPause),
            icon = if (playing) WmmIcons.Pause else WmmIcons.Play,
            contentDescription = if (playing) "Pause" else "Play",
            primary = true,
        )
        ControlButton({ actions.onSeekStep(true) }, icon = WmmIcons.Forward10, contentDescription = "Forward 10 seconds")
        Spacer(Modifier.width(18.dp))
        audioSummary(state.tracks)?.let {
            ControlButton(
                { actions.onOpenPicker(PickerSection.Audio) },
                Modifier.focusRequester(focus.openers.getValue(PickerSection.Audio)),
                icon = PlayerIcons.Audio,
                text = "Audio",
                detail = it,
            )
        }
        if (offersSubtitles(state.tracks)) {
            ControlButton(
                { actions.onOpenPicker(PickerSection.Subtitles) },
                Modifier.focusRequester(focus.openers.getValue(PickerSection.Subtitles)),
                icon = PlayerIcons.Subtitles,
                text = "Subtitles",
                detail = subtitleSummary(state.tracks),
            )
        }
        ControlButton(
            { actions.onOpenPicker(PickerSection.Quality) },
            Modifier.focusRequester(focus.openers.getValue(PickerSection.Quality)),
            icon = PlayerIcons.Quality,
            text = "Quality",
            detail = qualitySummary(state.tracks),
        )
        state.nextEpisode?.let { next ->
            Spacer(Modifier.weight(1f))
            ControlButton(actions.onPlayNext, icon = PlayerIcons.SkipNext, text = "Next episode", detail = episodeCode(next.season, next.episode))
        }
    }
}

/** The time of day, in the device's 12- or 24-hour style, as at the top right of PlayerControls. */
@Composable
private fun WallClock() {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(context) {
        val format = DateFormat.getTimeFormat(context)
        while (true) {
            text = format.format(Date())
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    Text(text, style = playerText(18, color = WmmColors.TextSoft, tabular = true))
}
