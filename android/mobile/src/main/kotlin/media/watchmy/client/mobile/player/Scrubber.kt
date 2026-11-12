package media.watchmy.client.mobile.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import media.watchmy.client.model.PlaybackProgress
import media.watchmy.client.model.ScrubberBand
import media.watchmy.client.model.Segment
import media.watchmy.client.model.Trickplay
import media.watchmy.client.model.formatClock
import media.watchmy.client.model.fractionOf
import media.watchmy.client.model.scrubberBands
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.PrefetchSeekThumbnails
import media.watchmy.client.ui.components.ScrubberLayout
import media.watchmy.client.ui.components.SeekThumbnail
import media.watchmy.client.ui.components.SeekThumbnailStyle
import kotlin.math.abs

/**
 * The elapsed time, the scrubber and the time left. Dragging the scrubber
 * previews the position in the elapsed time, and with [trickplay] in a seek
 * thumbnail above the thumb (PlayerScrub, TabPlayerScrub), and seeks on
 * release; a tap seeks at once. [onScrubbing] reports drags so the controls
 * stay up. It reads [progress] itself, so the position's ticks recompose
 * only this row.
 */
@Composable
internal fun ScrubberRow(
    progress: () -> PlaybackProgress,
    segments: List<Segment>,
    onSeek: (Long) -> Unit,
    onScrubbing: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trickplay: Trickplay? = null,
) {
    val (positionMs, durationMs, bufferedMs) = progress()
    var dragMs by remember { mutableStateOf<Long?>(null) }
    // After a seek, hold the target until the player's position catches up so the thumb does not jump back.
    var heldMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(heldMs) {
        if (heldMs != null) {
            delay(1_000)
            heldMs = null
        }
    }
    trickplay?.let { PrefetchSeekThumbnails(it) { progress().positionMs } }
    val shownMs = dragMs ?: heldMs?.takeIf { abs(it - positionMs) >= 1_500 } ?: positionMs
    val seek: (Long) -> Unit = {
        heldMs = it
        onSeek(it)
    }
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    ScrubberLayout(
        fraction = { fractionOf(dragMs ?: 0, durationMs) },
        gap = 12.dp,
        previewLift = if (tablet) 20.dp else 18.dp,
        elapsed = { Text(formatClock(shownMs), style = PlayerType.Time, modifier = Modifier.widthIn(min = 44.dp)) },
        track = {
            Scrubber(
                shownMs = shownMs,
                durationMs = durationMs,
                bufferedEndMs = positionMs + bufferedMs,
                segments = segments,
                dragging = dragMs != null,
                onDrag = {
                    if ((dragMs == null) != (it == null)) onScrubbing(it != null)
                    dragMs = it
                },
                onSeek = seek,
                modifier = Modifier.height(32.dp),
            )
        },
        remaining = { Text(remainingLabel(shownMs, durationMs), style = PlayerType.Time, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 52.dp)) },
        modifier = modifier,
        preview = if (trickplay != null && dragMs != null) {
            { SeekThumbnail(trickplay, { dragMs ?: 0 }, if (tablet) SeekThumbnailStyle.Tablet else SeekThumbnailStyle.Phone) }
        } else {
            null
        },
    )
}

@Composable
private fun Scrubber(
    shownMs: Long,
    durationMs: Long,
    bufferedEndMs: Long,
    segments: List<Segment>,
    dragging: Boolean,
    onDrag: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    val bands = remember(segments, durationMs) { scrubberBands(segments, durationMs) }
    val currentDrag by rememberUpdatedState(onDrag)
    val currentSeek by rememberUpdatedState(onSeek)
    var lastDragMs by remember { mutableLongStateOf(0L) }
    Canvas(
        modifier
            .semantics {
                contentDescription = "Seek"
                stateDescription = "${formatClock(shownMs)} of ${formatClock(durationMs)}"
                progressBarRangeInfo = ProgressBarRangeInfo(fractionOf(shownMs, durationMs), 0f..1f)
                setProgress { v ->
                    currentSeek((v * durationMs).toLong())
                    true
                }
            }
            .pointerInput(durationMs) {
                detectTapGestures(onTap = { currentSeek(positionAt(it.x, size.width.toFloat(), durationMs)) })
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        lastDragMs = positionAt(it.x, size.width.toFloat(), durationMs)
                        currentDrag(lastDragMs)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        lastDragMs = positionAt(change.position.x, size.width.toFloat(), durationMs)
                        currentDrag(lastDragMs)
                    },
                    onDragEnd = {
                        currentDrag(null)
                        currentSeek(lastDragMs)
                    },
                    onDragCancel = { currentDrag(null) },
                )
            },
    ) {
        val cy = size.height / 2
        val track = 4.dp.toPx()
        val round = CornerRadius(2.dp.toPx())
        fun bar(fraction: Float, color: Color) {
            if (fraction > 0f) drawRoundRect(color, Offset(0f, cy - track / 2), Size(size.width * fraction, track), round)
        }
        bar(1f, PlayerColors.Track)
        bar(fractionOf(bufferedEndMs, durationMs), PlayerColors.Buffer)
        val played = fractionOf(shownMs, durationMs)
        bar(played, WmmColors.Accent)
        for (band in bands) drawBand(band, cy)
        val x = size.width * played
        val radius = (if (dragging) 10.dp else 8.dp).toPx()
        drawCircle(PlayerColors.ThumbHalo, radius + 3.dp.toPx(), Offset(x, cy))
        drawCircle(WmmColors.Accent, radius, Offset(x, cy))
    }
}

/** A segment's band: 12 dp tall over the 4 dp track, at least 4 dp wide so a short recap still shows. */
private fun DrawScope.drawBand(band: ScrubberBand, cy: Float) {
    val h = 12.dp.toPx()
    val line = 1.dp.toPx()
    val left = size.width * band.start
    val width = (size.width * (band.end - band.start)).coerceAtLeast(4.dp.toPx())
    val (fill, edge) = if (band.opening) PlayerColors.OpeningFill to PlayerColors.OpeningLine else PlayerColors.ClosingFill to PlayerColors.ClosingLine
    val round = CornerRadius(2.dp.toPx())
    drawRoundRect(fill, Offset(left, cy - h / 2), Size(width, h), round)
    drawRoundRect(edge, Offset(left + line / 2, cy - h / 2 + line / 2), Size(width - line, h - line), round, style = Stroke(line))
}

/** The small progress line beside the Next Episode card: elapsed, a 220 dp bar, and the length. */
@Composable
internal fun MiniProgress(progress: () -> PlaybackProgress, modifier: Modifier = Modifier) {
    val (positionMs, durationMs) = progress()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(formatClock(positionMs), style = PlayerType.MiniTime)
        Box(Modifier.size(220.dp, 4.dp).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fractionOf(positionMs, durationMs)).background(WmmColors.Accent, RoundedCornerShape(2.dp)))
        }
        Text(formatClock(durationMs), style = PlayerType.MiniTime)
    }
}
