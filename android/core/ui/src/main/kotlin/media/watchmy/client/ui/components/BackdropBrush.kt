package media.watchmy.client.ui.components

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import media.watchmy.client.ui.PlaceholderPairs
import media.watchmy.client.ui.WmmColors
import kotlin.math.absoluteValue

/**
 * The placeholder behind a hero or detail header when there is no backdrop:
 * the card colours for [seed] running down into the ground, as the TV
 * mockups draw it (linear-gradient(180deg, a 0%, mid 50%, ground 100%)).
 */
fun backdropBrush(seed: String): Brush {
    val (a, b) = PlaceholderPairs[seed.hashCode().absoluteValue % PlaceholderPairs.size]
    return Brush.verticalGradient(0f to a, 0.5f to lerp(a, b, 0.6f), 1f to WmmColors.Ground)
}

/** The first colour of [seed]'s placeholder pair, for small solid fills (cast circles). */
fun placeholderColor(seed: String): Color = PlaceholderPairs[seed.hashCode().absoluteValue % PlaceholderPairs.size].first
