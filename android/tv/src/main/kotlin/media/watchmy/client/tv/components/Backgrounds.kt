package media.watchmy.client.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.backdropBrush

/**
 * A soft elliptical glow, as the mockups' `radial-gradient(Wpx Hpx at x% y%,
 * color, transparent stop%)`: [color] at ([x], [y]) fractions of the size,
 * fading out by [stop] of the [width]×[height] ellipse. The gradient is made
 * once per size, not on every draw (a focus move redraws the backdrop).
 */
fun Modifier.radialGlow(color: Color, x: Float, y: Float, width: Dp, height: Dp, stop: Float = 0.7f): Modifier = drawWithCache {
    val c = Offset(size.width * x, size.height * y)
    val rx = width.toPx()
    val ry = height.toPx()
    val glow = Brush.radialGradient(0f to color, stop to Color.Transparent, center = c, radius = ry)
    onDrawBehind {
        scale(scaleX = rx / ry, scaleY = 1f, pivot = c) {
            drawCircle(glow, radius = ry, center = c)
        }
    }
}

/**
 * A hero or detail backdrop: the image (or the placeholder gradient for
 * [seed]) with a gold glow, faded into the ground from the left and the
 * bottom so text over it stays readable. [leftFade] are (position, alpha)
 * stops of the ground colour from the left edge; [bottomFadeFrom] is where
 * the fade to the ground starts.
 */
@Composable
fun Backdrop(
    url: String?,
    seed: String,
    modifier: Modifier = Modifier,
    leftFade: List<Pair<Float, Float>> = listOf(0f to 0.92f, 0.4f to 0.55f, 0.75f to 0f),
    bottomFadeFrom: Float = 0.4f,
    glowX: Float = 0.75f,
    glowY: Float = 0.2f,
) {
    Box(modifier) {
        Box(Modifier.fillMaxSize().background(backdropBrush(seed)))
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .radialGlow(WmmColors.Accent.copy(alpha = 0.10f), glowX, glowY, Dp(800f), Dp(380f), stop = 0.6f)
                .background(Brush.horizontalGradient(*leftFade.map { (p, a) -> p to WmmColors.Ground.copy(alpha = a) }.toTypedArray()))
                .background(Brush.verticalGradient(bottomFadeFrom to Color.Transparent, 1f to WmmColors.Ground)),
        )
    }
}
