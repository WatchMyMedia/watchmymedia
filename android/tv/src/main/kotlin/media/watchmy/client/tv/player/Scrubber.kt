package media.watchmy.client.tv.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.ScrubberBand
import media.watchmy.client.ui.WmmColors

/**
 * The scrubber: a 10 dp track across the safe area, what has loaded a
 * little lighter, what has played in gold, the intro and credits as white
 * bands on it, and a gold thumb at [progress]. While a seek has a target
 * ahead of or behind what has [played], the stretch between is a paler gold.
 * Focused, the thumb grows and takes the gold ring and a shadow, as
 * everything focused does.
 */
@Composable
internal fun ScrubberTrack(progress: Float, buffered: Float, bands: List<ScrubberBand>, focused: Boolean, modifier: Modifier = Modifier, played: Float = progress) {
    Canvas(modifier.height(34.dp)) {
        val trackH = 10.dp.toPx()
        val top = size.height / 2 - trackH / 2
        val pill = CornerRadius(trackH / 2)
        drawRoundRect(Color.White.copy(alpha = 0.26f), Offset(0f, top), Size(size.width, trackH), pill)
        if (buffered > 0f) drawRoundRect(Color.White.copy(alpha = 0.16f), Offset(0f, top), Size(size.width * buffered, trackH), pill)
        for (band in bands) drawBand(band, top, trackH)
        val done = minOf(played, progress)
        if (done > 0f) drawRoundRect(WmmColors.Accent, Offset(0f, top), Size(size.width * done, trackH), pill)
        if (progress != played) {
            val from = size.width * minOf(played, progress)
            drawRect(WmmColors.Accent.copy(alpha = 0.45f), Offset(from, top), Size(size.width * kotlin.math.abs(progress - played), trackH))
        }
        val x = size.width * progress
        val cy = size.height / 2
        if (focused) {
            val r = 16.dp.toPx()
            drawCircle(Color.Black.copy(alpha = 0.35f), r + 10.dp.toPx(), Offset(x, cy + 6.dp.toPx()))
            drawCircle(WmmColors.Accent, r, Offset(x, cy))
            // The gold ring every focused thing has, 4 dp out.
            drawCircle(WmmColors.Accent, r + 6.dp.toPx(), Offset(x, cy), style = Stroke(4.dp.toPx()))
        } else {
            drawCircle(WmmColors.Accent, 9.dp.toPx(), Offset(x, cy))
        }
    }
}

/** An intro or credits band: white over the track, as long as the part is. */
private fun DrawScope.drawBand(band: ScrubberBand, top: Float, height: Float) {
    val left = size.width * band.start
    val width = size.width * (band.end - band.start)
    drawRect(Color.White.copy(alpha = 0.72f), Offset(left, top), Size(width, height))
}

/**
 * The seek thumbnail's chip beside the time: how far each press or step of
 * a held key moves the target ("+10 s", "+2 min"), in gold (PlayerScrub).
 */
@Composable
internal fun SeekStepChip(stepMs: () -> Long, modifier: Modifier = Modifier) {
    val step = stepMs()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .height(40.dp)
            .border(2.dp, WmmColors.Accent, shape)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            PlayerIcons.SeekStep,
            contentDescription = null,
            tint = WmmColors.Accent,
            modifier = Modifier.size(22.dp).graphicsLayer { scaleX = if (step < 0) -1f else 1f },
        )
        Text(seekStepLabel(step), style = playerText(22, FontWeight.Bold, color = WmmColors.Accent, tabular = true), maxLines = 1)
    }
}
