package media.watchmy.client.tv.player

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import media.watchmy.client.model.PlaybackProgress
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.behindRecording
import media.watchmy.client.model.episodeCode
import media.watchmy.client.model.formatClock
import media.watchmy.client.model.fractionOf
import media.watchmy.client.model.recordingEdgeMs
import media.watchmy.client.model.scrubberBands
import media.watchmy.client.model.tracks
import media.watchmy.client.tv.components.SafeX
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvClock
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.endsAt
import media.watchmy.client.tv.components.rememberNow
import media.watchmy.client.tv.components.timeLeft
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.PrefetchSeekThumbnails
import media.watchmy.client.ui.components.ScrubberLayout
import media.watchmy.client.ui.components.SeekThumbnail
import media.watchmy.client.ui.components.SeekThumbnailStyle
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat

/** The chrome's focus targets, owned by the screen so it can move focus between them. */
internal class ChromeFocus(
    val skip: FocusRequester?,
    val scrubber: FocusRequester,
    val playPause: FocusRequester,
    /** Each panel's button, by the section it opens on (Audio and Subtitles share Audio & subtitles). */
    val openers: Map<PickerSection, FocusRequester>,
    /** The Episodes button's, for an episode. */
    val episodes: FocusRequester? = null,
)

/** What the chrome's controls do. */
internal class ChromeActions(
    val onTogglePlay: () -> Unit,
    val onOpenPicker: (PickerSection) -> Unit,
    val onPlayNext: () -> Unit,
    val onFocusArea: (FocusArea) -> Unit,
    val onSeekTo: (Long) -> Unit = {},
    val onOpenEpisodes: () -> Unit = {},
)

/** The dark washes behind the chrome, top and bottom, so the words read over any picture. */
@Composable
internal fun BoxScope.ChromeShades(bottom: Boolean = true) {
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(300.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent))))
    if (bottom) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(460.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f)))))
}

/**
 * The controls over the video: what is playing at the top left and the
 * clock at the top right, all inside the safe area; at the foot the
 * scrubber, with the time, how long is left and when it ends under it, and
 * the buttons: Pause, Episodes for an episode, Audio & subtitles, Quality,
 * Playback info, and Next episode at the far end when there is one. No
 * Back button: the remote has one.
 *
 * [positionMs] is the scrubber's position (a seek's target while the viewer
 * is seeking) and [progress] the player's; only the scrubber row reads
 * them, so the rest stays put while the position moves. While [seek] has a
 * target, a seek thumbnail shows it above the scrubber with the step beside
 * the time, when the file has them.
 */
@Composable
internal fun BoxScope.PlayerChrome(
    state: PlayerUiState,
    positionMs: () -> Long,
    progress: () -> PlaybackProgress,
    focus: ChromeFocus,
    actions: ChromeActions,
    seek: SeekState? = null,
    /** "S03 E07" for an episode with a list to open; null hides Episodes. */
    episodes: String? = null,
) {
    ChromeShades()
    TitleBlock(state, Modifier.align(Alignment.TopStart).padding(start = SafeX, top = SafeY))
    TvClock()
    // Go to now only while the play is more than 10 s behind what has recorded; focus goes back to the scrubber as it leaves.
    val behind by remember(progress) { derivedStateOf { state.growing != null && progress().behindRecording() } }
    if (behind) {
        TvButton(
            "Go to now",
            { actions.onSeekTo(progress().recordingEdgeMs()); runCatching { focus.scrubber.requestFocus() } },
            Modifier.align(Alignment.TopEnd).padding(top = SafeY + 56.dp, end = SafeX).onFocusChanged { if (it.isFocused) actions.onFocusArea(FocusArea.Buttons) },
        )
    }
    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = SafeX, end = SafeX, bottom = ChromeBottom)) {
        ScrubberRow(state, positionMs, progress, seek, focus, actions)
        Spacer(Modifier.height(20.dp))
        TransportRow(state, focus, actions, episodes)
    }
}

/** Where the buttons' foot sits: inside the safe area, with room for a focused button to grow. */
private val ChromeBottom = 66.dp

/**
 * What is playing: for an episode the show in a smaller line over the
 * episode ("S3 E7 · The Long Dark"), for a movie its title alone, and a
 * Recording mark for a recording still being made.
 */
@Composable
internal fun TitleBlock(state: PlayerUiState, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 1200.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (state.growing != null) RecordingMark()
        val over = state.title.takeIf { state.subtitle != null }
        if (over != null) Text(over, style = TvType.Second, color = WmmColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(state.subtitle ?: state.title, style = TvType.Heading, color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Recording", in burgundy with its rim: a recording still being made. */
@Composable
private fun RecordingMark() {
    val shape = RoundedCornerShape(8.dp)
    Text(
        "RECORDING",
        Modifier
            .padding(bottom = 6.dp)
            .background(WmmColors.Brand, shape)
            .border(2.dp, WmmColors.BrandLine, shape)
            .heightIn(min = 38.dp)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = TvType.Kicker,
        color = Color.White,
    )
}

/**
 * The scrubber across the safe area, and under it the time, the keys it
 * takes while it has focus, and how long is left and when it would end
 * ("29 min left · ends 8:41 PM"). It alone reads the position, so a tick
 * redraws nothing else; it also reads the clock, which moves once a minute.
 */
@Composable
private fun ScrubberRow(
    state: PlayerUiState,
    shownMs: () -> Long,
    progress: () -> PlaybackProgress,
    seek: SeekState?,
    focus: ChromeFocus,
    actions: ChromeActions,
    modifier: Modifier = Modifier,
) {
    val positionMs = shownMs()
    val (playerMs, _, bufferedMs) = progress()
    var focused by remember { mutableStateOf(false) }
    val bands = remember(state.segments, state.durationMs) { scrubberBands(state.segments, state.durationMs) }
    val trickplay = state.trickplay
    trickplay?.let { PrefetchSeekThumbnails(it) { progress().positionMs } }
    // No times until the duration is known (the first moments of loading).
    val known = state.durationMs > 0
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScrubberLayout(
            fraction = { fractionOf(shownMs(), state.durationMs) },
            gap = 0.dp,
            previewLift = 40.dp,
            // The layout measures one element either side of the track: empty ones, as the times sit under it.
            elapsed = { Spacer(Modifier) },
            track = {
                ScrubberTrack(
                    progress = fractionOf(positionMs, state.durationMs),
                    played = fractionOf(playerMs, state.durationMs),
                    buffered = fractionOf(playerMs + bufferedMs, state.durationMs),
                    bands = bands,
                    focused = focused,
                    modifier = Modifier
                        .focusRequester(focus.scrubber)
                        .focusProperties {
                            up = focus.skip ?: FocusRequester.Cancel
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
            remaining = { Spacer(Modifier) },
            modifier = Modifier.fillMaxWidth(),
            preview = if (trickplay != null && seek?.targetMs != null) {
                { SeekThumbnail(trickplay, shownMs, SeekThumbnailStyle.Tv, chip = { SeekStepChip(stepMs = { seek.stepMs }) }) }
            } else {
                null
            },
        )
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (known) formatClock(positionMs) else "", style = Tabular, color = WmmColors.Text, maxLines = 1)
            Spacer(Modifier.weight(1f))
            // The keys the scrubber takes, only while it has focus and nothing is moving.
            if (focused && seek?.targetMs == null) ScrubberKeys(paused = state.status == PlayerStatus.Paused)
            Spacer(Modifier.weight(1f))
            if (known) TimeLeft(state, positionMs)
        }
    }
}

/** "29 min left · ends 8:41 PM", against the wall clock; a recording still being made has no end yet, so it says how long it is. */
@Composable
private fun TimeLeft(state: PlayerUiState, positionMs: Long) {
    if (state.growing != null) {
        Text(formatClock(state.durationMs), style = Tabular, color = WmmColors.TextSoft, maxLines = 1)
        return
    }
    val now by rememberNow()
    val format = rememberLiveClockFormat()
    val remaining = (state.durationMs - positionMs).coerceAtLeast(0)
    val line = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = WmmColors.Text)) { append(timeLeft(remaining)) }
        withStyle(SpanStyle(color = WmmColors.TextSoft)) { append(" · ${endsAt(remaining, now, format)}") }
    }
    Text(line, style = Tabular, maxLines = 1)
}

/** "◀ ▶ 10 seconds, hold to go faster" and "OK Pause", with the keys in boxes. */
@Composable
private fun ScrubberKeys(paused: Boolean) {
    Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
        KeyHint("◀ ▶", "10 seconds, hold to go faster")
        KeyHint("OK", if (paused) "Play" else "Pause")
    }
}

/** A remote key in a box and what it does, at the size of a key hint. */
@Composable
internal fun KeyHint(key: String, does: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            key,
            Modifier
                .border(2.dp, TvPalette.Line3, RoundedCornerShape(10.dp))
                .heightIn(min = 40.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = TvType.Caption.copy(fontWeight = FontWeight.Bold),
            color = WmmColors.TextSoft,
            maxLines = 1,
        )
        Text(does, style = TvType.Caption, color = TvPalette.Muted, maxLines = 1)
    }
}

private val Tabular = TvType.Second.copy(fontFeatureSettings = "tnum")

/**
 * The buttons under the scrubber: Pause, Episodes, Audio & subtitles,
 * Quality and Playback info, then Next episode at the far end. ◀ ▶ move
 * along them and stop at the ends; ▲ goes back to the scrubber; ▼ stops.
 */
@Composable
private fun TransportRow(state: PlayerUiState, focus: ChromeFocus, actions: ChromeActions, episodes: String?, modifier: Modifier = Modifier) {
    val playing = state.status == PlayerStatus.Playing || state.status == PlayerStatus.Buffering
    val tracks = state.tracks
    Row(
        modifier
            .fillMaxWidth()
            .focusProperties {
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Left || requestedFocusDirection == FocusDirection.Right || requestedFocusDirection == FocusDirection.Down) cancelFocusChange()
                }
            }
            .focusGroup()
            .onFocusChanged { if (it.hasFocus) actions.onFocusArea(FocusArea.Buttons) },
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlButton(
            actions.onTogglePlay,
            Modifier.focusRequester(focus.playPause),
            icon = if (playing) WmmIcons.Pause else WmmIcons.Play,
            contentDescription = if (playing) "Pause" else "Play",
        )
        // Episodes before the rest, for an episode with a list to open.
        val episodesFocus = focus.episodes
        if (episodes != null && episodesFocus != null) {
            ControlButton(actions.onOpenEpisodes, Modifier.focusRequester(episodesFocus), text = "Episodes")
        }
        if (offersTracks(tracks)) {
            ControlButton(
                { actions.onOpenPicker(PickerSection.Audio) },
                Modifier.focusRequester(focus.openers.getValue(PickerSection.Audio)),
                text = "Audio & subtitles",
            )
        }
        ControlButton(
            { actions.onOpenPicker(PickerSection.Quality) },
            Modifier.focusRequester(focus.openers.getValue(PickerSection.Quality)),
            text = "Quality",
            detail = qualitySummary(tracks),
        )
        ControlButton(
            { actions.onOpenPicker(PickerSection.Info) },
            Modifier.focusRequester(focus.openers.getValue(PickerSection.Info)),
            text = "Playback info",
        )
        state.nextEpisode?.let { next ->
            Spacer(Modifier.weight(1f))
            ControlButton(
                actions.onPlayNext,
                icon = PlayerIcons.SkipNext,
                text = "Next episode",
                iconAfter = true,
                contentDescription = "Next episode, ${episodeCode(next.season, next.episode)}",
            )
        }
    }
}
