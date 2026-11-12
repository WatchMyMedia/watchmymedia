package media.watchmy.client.tv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors

/** The artboards' width: inside [MockupScale], 1 dp is one mockup pixel. */
internal const val MOCKUP_WIDTH = 1920f

/**
 * Lays the player out in the artboards' 1920-wide units on any screen: a
 * 1080p Google TV is 960×540 dp and the Pixel Tablet emulator 1280×800, so
 * the mockups' pixels are scaled to the screen's width. Worked out from
 * pixels, so it changes nothing if a parent already scales the same way.
 */
@Composable
internal fun MockupScale(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val density = remember(constraints.maxWidth, fontScale) { Density(constraints.maxWidth / MOCKUP_WIDTH, fontScale) }
        CompositionLocalProvider(LocalDensity provides density, content = content)
    }
}

/** Inter at a mockup size; [tabular] for clocks, so digits do not jiggle. */
internal fun playerText(
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    color: Color = WmmColors.Text,
    tabular: Boolean = false,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * 1.25).sp,
    letterSpacing = (size * tracking).sp,
    color = color,
    fontFeatureSettings = if (tabular) "tnum" else null,
)

private val RingWidth = 4.dp

/**
 * The mockups' focus outline: a 4 dp gold ring [outset] beyond the edge
 * (CSS outline-offset; negative draws it inside, as the picker rows do).
 */
internal fun Modifier.focusRing(focused: Boolean, corner: Dp, outset: Dp = 4.dp): Modifier = drawWithContent {
    drawContent()
    if (focused) {
        val w = RingWidth.toPx()
        val o = outset.toPx() + w / 2
        drawRoundRect(
            color = WmmColors.Accent,
            topLeft = Offset(-o, -o),
            size = Size(size.width + 2 * o, size.height + 2 * o),
            cornerRadius = CornerRadius((corner.toPx() + o).coerceAtLeast(0f)),
            style = Stroke(w),
        )
    }
}

/** Requests focus once the target is attached (a frame after it is composed, at worst). */
internal suspend fun FocusRequester.focusSoon() {
    repeat(4) {
        if (requestFocus()) return
        withFrameNanos { }
    }
}

/**
 * A transport control (the artboard's `.cbtn`): a 60 dp pill, an icon-only
 * circle, or with [primary] the 76 dp white play/pause. Focus adds the gold
 * ring and a 6 % scale.
 */
@Composable
internal fun ControlButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    text: String? = null,
    detail: String? = null,
    contentDescription: String? = null,
    primary: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "focus scale")
    val height = if (primary) 76.dp else 60.dp
    val bg = if (primary) WmmColors.Text else Color.White.copy(alpha = 0.12f)
    val fg = if (primary) WmmColors.Ground else WmmColors.Text
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRing(focused, height / 2)
            .clip(shape)
            .background(bg, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .height(height)
            .then(if (text == null) Modifier.width(height) else Modifier.padding(horizontal = 22.dp)),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            val iconSize = when {
                primary -> 34.dp
                text == null -> 28.dp
                else -> 24.dp
            }
            Icon(icon, contentDescription, Modifier.size(iconSize), tint = fg)
        }
        if (text != null) Text(text, style = playerText(20, FontWeight.SemiBold, fg), maxLines = 1)
        if (detail != null) {
            Text(detail, style = playerText(17, FontWeight.Medium, WmmColors.TextMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A card button (PlayerNext's `.nbtn`): [filled] on a light wash, else an
 * outline. [progress] (0–1) runs a burgundy fill across it, for countdowns.
 */
@Composable
internal fun CardButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    icon: ImageVector? = null,
    progress: Float = 0f,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val fg = if (filled) WmmColors.Text else Color(0xFFE5E5E5)
    var m = modifier
        .onFocusChanged { focused = it.isFocused }
        .focusRing(focused, 12.dp)
        .clip(shape)
    m = if (filled) m.background(Color.White.copy(alpha = 0.14f)) else m.border(1.dp, Color.White.copy(alpha = 0.28f), shape)
    Box(
        m
            .drawBehind { if (progress > 0f) drawRect(WmmColors.Brand, size = Size(size.width * progress.coerceAtMost(1f), size.height)) }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .height(60.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = fg)
            Text(text, style = playerText(20, FontWeight.SemiBold, fg), maxLines = 1)
        }
    }
}

/**
 * Shows [content] with an entrance and drops it at once when the caller
 * stops composing it, so focus never stays on something fading out.
 */
@Composable
internal fun Entering(enter: EnterTransition, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(state, modifier, enter = enter, exit = ExitTransition.None) { content() }
}
