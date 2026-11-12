package media.watchmy.client.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * The reel mark: a film reel with a play triangle for its hub and the film's
 * tail running out along the bottom, white with burgundy details
 * (brand/README.md). Below 32 dp it draws the small artwork: bigger holes, no
 * play or sprockets.
 */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    val small = size < 32.dp
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 512f
        // The tail runs under the wheel; the wheel's rim parts them.
        val tailAt = Offset(216f * s, 401f * s)
        val tailSize = Size(274f * s, 48f * s)
        drawRoundRect(WmmColors.Text, tailAt, tailSize, CornerRadius(10f * s))
        drawRoundRect(WmmColors.Brand, tailAt, tailSize, CornerRadius(10f * s), style = Stroke(width = 14f * s))
        if (!small) {
            for (x in listOf(343f, 391f, 439f)) {
                drawRoundRect(WmmColors.Brand, Offset(x * s, 416f * s), Size(18f * s, 18f * s), CornerRadius(4f * s))
            }
        }
        // The wheel, drawn as if it filled the grid and scaled to r 200 about (216, 256).
        val k = 200f / 244f
        withTransform({
            translate((216f - 256f * k) * s, (256f - 256f * k) * s)
            scale(k, k, pivot = Offset.Zero)
        }) {
            val centre = Offset(256f * s, 256f * s)
            drawCircle(WmmColors.Text, radius = 244f * s, center = centre)
            drawCircle(WmmColors.Brand, radius = 233f * s, center = centre, style = Stroke(width = 22f * s))
            val (ring, hole) = if (small) 136f to 64f else 126f to 56f
            for (i in 0 until 5) {
                val a = Math.toRadians(-90.0 + 72.0 * i)
                val at = Offset((256f + ring * cos(a).toFloat()) * s, (256f + ring * sin(a).toFloat()) * s)
                drawCircle(WmmColors.Brand, radius = hole * s, center = at)
            }
            if (!small) {
                val play = Path().apply {
                    moveTo(232f * s, 214f * s)
                    lineTo(232f * s, 298f * s)
                    lineTo(300f * s, 256f * s)
                    close()
                }
                drawPath(play, WmmColors.Brand, style = Fill)
                drawPath(play, WmmColors.Brand, style = Stroke(width = 18f * s, join = StrokeJoin.Round))
            }
        }
    }
}

/** The mark with "WatchMyMedia" ("Media" muted), as in the mockups' headers. */
@Composable
fun BrandLockup(markSize: Dp = 32.dp, fontSize: TextUnit = 22.sp, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(markSize / 3)) {
        BrandMark(markSize)
        Text(
            buildAnnotatedString {
                append("WatchMy")
                withStyle(SpanStyle(color = WmmColors.TextMuted, fontWeight = FontWeight.SemiBold)) { append("Media") }
            },
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = fontSize * -0.02f, color = WmmColors.Text),
        )
    }
}
