package media.watchmy.client.tv.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
 * PlayerControls' scrubber track: buffered range, progress, segment bands and
 * the gold thumb. [focused] enlarges the thumb and its halo.
 */
@Composable
internal fun ScrubberTrack(progress: Float, buffered: Float, bands: List<ScrubberBand>, focused: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.height(40.dp)) {
        val trackH = 8.dp.toPx()
        val top = size.height / 2 - trackH / 2
        val pill = CornerRadius(trackH / 2)
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, top), Size(size.width, trackH), pill)
        if (buffered > 0f) drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(0f, top), Size(size.width * buffered, trackH), pill)
        if (progress > 0f) drawRoundRect(WmmColors.Accent, Offset(0f, top), Size(size.width * progress, trackH), pill)
        for (band in bands) drawBand(band, top - 6.dp.toPx())
        val x = size.width * progress
        val cy = size.height / 2
        val thumb = (if (focused) 14.dp else 11.dp).toPx()
        val halo = thumb + (if (focused) 6.dp else 4.dp).toPx()
        drawCircle(WmmColors.Accent.copy(alpha = if (focused) 0.4f else 0.3f), halo, Offset(x, cy))
        drawCircle(WmmColors.Accent, thumb, Offset(x, cy))
    }
}

private fun DrawScope.drawBand(band: ScrubberBand, top: Float) {
    val h = 20.dp.toPx()
    val left = size.width * band.start
    val width = size.width * (band.end - band.start)
    val corner = CornerRadius(3.dp.toPx())
    val (fill, line) = if (band.opening) {
        WmmColors.Accent.copy(alpha = 0.22f) to WmmColors.Accent.copy(alpha = 0.6f)
    } else {
        Color.White.copy(alpha = 0.10f) to Color.White.copy(alpha = 0.35f)
    }
    drawRoundRect(fill, Offset(left, top), Size(width, h), corner)
    val stroke = 1.dp.toPx()
    drawRoundRect(line, Offset(left + stroke / 2, top + stroke / 2), Size(width - stroke, h - stroke), corner, style = Stroke(stroke))
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
            .background(WmmColors.Accent.copy(alpha = 0.16f), shape)
            .border(1.dp, WmmColors.Accent.copy(alpha = 0.6f), shape)
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
        Text(seekStepLabel(step), style = playerText(20, FontWeight.Bold, color = WmmColors.Accent, tabular = true), maxLines = 1)
    }
}
