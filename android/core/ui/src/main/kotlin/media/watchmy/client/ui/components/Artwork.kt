package media.watchmy.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size
import media.watchmy.client.ui.PlaceholderPairs
import kotlin.math.absoluteValue

/**
 * A poster, backdrop or still: the image at [url] over the mockups' gradient
 * placeholder, which also shows while loading and when there is no image.
 * [seed] (usually the title) picks the placeholder colours.
 *
 * The image is decoded at the size the box has when it is first laid out.
 * Art drawn larger later passes [decodeSize], the largest it is drawn at:
 * a TV row that grows when it takes focus, which would otherwise stretch
 * the image it decoded at rest.
 */
@Composable
fun Artwork(
    url: String?,
    seed: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    decodeSize: DpSize? = null,
    overlay: @Composable () -> Unit = {},
) {
    val clipped = if (shape != null) modifier.clip(shape) else modifier
    Box(clipped.background(placeholderBrush(seed))) {
        if (url != null) {
            val model: Any = if (decodeSize == null) {
                url
            } else {
                val context = LocalPlatformContext.current
                val size = with(LocalDensity.current) { Size(decodeSize.width.roundToPx(), decodeSize.height.roundToPx()) }
                remember(url, size, context) { ImageRequest.Builder(context).data(url).size(size).build() }
            }
            AsyncImage(model = model, contentDescription = contentDescription, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
