package media.watchmy.client.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.RoundedCornerShape

/** The skeletons' fills (HomeLoading.dc.html): `.ln` lines and the hero's stronger ones, and its burgundy button. */
object SkeletonColors {
    val Line = Color(0xFF222225)
    val Strong = Color(0xFF2E2E33)
    val Soft = Color(0xFF2A2A2E)
    val Block = Color(0xFF26262A)
    val Brand = Color(0xFF3A2020)
}

/**
 * The light that sweeps across a skeleton's blocks and lines while a page
 * loads (`@keyframes sheen`), every one in step. Null [offset] draws none:
 * the device has animations turned off (Remove animations, or animator
 * duration scale off), as `prefers-reduced-motion` asks.
 */
@Stable
class Sheen internal constructor(private val state: State<Float>?) {
    /** -1 (off the left edge) to 1 (off the right). */
    val offset: Float? get() = state?.value

    internal fun ContentDrawScope.draw() {
        val p = offset ?: return
        val w = size.width
        val x = w * p
        drawRect(
            Brush.horizontalGradient(
                0.2f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.05f),
                0.8f to Color.Transparent,
                startX = x,
                endX = x + w,
            ),
        )
    }

    companion object {
        /** No sweep: previews, tests and devices with animations off. */
        val Still = Sheen(null)
    }
}

/** A [Sheen] that sweeps once every [periodMs] (1.6 s on the phone, 1.8 s on the TV), or none with animations off. */
@Composable
fun rememberSheen(periodMs: Int): Sheen {
    val moving = remember { ValueAnimator.areAnimatorsEnabled() }
    if (!moving) return Sheen.Still
    val transition = rememberInfiniteTransition(label = "sheen")
    val spec: InfiniteRepeatableSpec<Float> = infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing))
    val offset = transition.animateFloat(-1f, 1f, spec, label = "sheenOffset")
    return remember(offset) { Sheen(offset) }
}

/**
 * `.sk`: where artwork will be, a dark diagonal with a soft light in its top
 * left, and the [sheen] across it.
 */
fun Modifier.skeletonBlock(sheen: Sheen, shape: Shape): Modifier = clip(shape)
    .drawBehind {
        drawRect(Brush.linearGradient(listOf(SkeletonColors.Block, Color(0xFF161618)), start = Offset(size.width * 0.63f, 0f), end = Offset(size.width * 0.37f, size.height)))
        drawRect(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.06f),
                0.55f to Color.Transparent,
                center = Offset(size.width * 0.2f, 0f),
                radius = size.width * 1.2f,
            ),
        )
    }
    .drawWithContent {
        drawContent()
        with(sheen) { draw() }
    }

/** `.ln`: a line of text to come, [width] × [height], rounded, in [color], with the [sheen] across it. */
fun Modifier.skeletonLine(sheen: Sheen, width: Dp, height: Dp, color: Color = SkeletonColors.Line, radius: Dp = height / 2): Modifier =
    size(width, height)
        .clip(RoundedCornerShape(radius))
        .background(color)
        .drawWithContent {
            drawContent()
            with(sheen) { draw() }
        }

/** What a loading skeleton says to TalkBack ("Loading Home"), politely, in place of its shapes. */
fun Modifier.loadingSemantics(label: String): Modifier = clearAndSetSemantics {
    contentDescription = label
    liveRegion = LiveRegionMode.Polite
}
