package media.watchmy.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import media.watchmy.client.ui.PlaceholderPairs
import kotlin.math.absoluteValue

/**
 * A poster, backdrop or still: the image at [url] over the mockups' gradient
 * placeholder, which also shows while loading and when there is no image.
 * [seed] (usually the title) picks the placeholder colours.
 */
@Composable
fun Artwork(
    url: String?,
    seed: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    overlay: @Composable () -> Unit = {},
) {
    val clipped = if (shape != null) modifier.clip(shape) else modifier
    Box(clipped.background(placeholderBrush(seed))) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = contentDescription, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        overlay()
    }
}

/** The placeholder gradient for [seed]: a diagonal pair with a soft light in the top left. */
fun placeholderBrush(seed: String): Brush {
    val (a, b) = PlaceholderPairs[seed.hashCode().absoluteValue % PlaceholderPairs.size]
    return Brush.linearGradient(listOf(lerpWhite(a), a, b))
}

private fun lerpWhite(c: Color) = Color(
    red = c.red + (1 - c.red) * 0.08f,
    green = c.green + (1 - c.green) * 0.08f,
    blue = c.blue + (1 - c.blue) * 0.08f,
)
