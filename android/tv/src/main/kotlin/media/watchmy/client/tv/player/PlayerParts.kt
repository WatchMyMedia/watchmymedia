package media.watchmy.client.tv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.tv.components.FocusGrowth
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
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

/** Requests focus once the target is attached (a frame after it is composed, at worst). */
internal suspend fun FocusRequester.focusSoon() {
    repeat(4) {
        if (requestFocus()) return
        withFrameNanos { }
    }
}

/**
 * A player control, as the TV's other buttons: 72 high, a dark fill that
 * lifts when focused, the gold ring, growth and a shadow. [icon] alone
 * makes it square (Pause); with [text] it leads, or follows with
 * [iconAfter] (Next episode, Skip intro). [detail] follows the words after a
 * dot ("Quality · Automatic"). [fill] (0–1) runs a gold wash across it, for
 * Skip's own countdown.
 */
@Composable
internal fun ControlButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    text: String? = null,
    detail: String? = null,
    contentDescription: String? = null,
    iconAfter: Boolean = false,
    fill: () -> Float = { 0f },
) {
    val shape = RoundedCornerShape(16.dp)
    FocusableBox(
        onClick = onClick,
        modifier = modifier.then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        cornerRadius = 16.dp,
        focusedScale = FocusGrowth.BUTTON,
        role = Role.Button,
    ) { focused ->
        Row(
            Modifier
                .heightIn(min = ButtonHeight)
                .clip(shape)
                .background(if (focused) TvPalette.Lift else TvPalette.ButtonFill, shape)
                .drawBehind {
                    val f = fill()
                    if (f > 0f) drawRect(WmmColors.Accent.copy(alpha = 0.35f), size = Size(size.width * f.coerceAtMost(1f), size.height))
                }
                .border(2.dp, if (focused) TvPalette.Lift else Color.White.copy(alpha = 0.2f), shape)
                .then(if (text == null) Modifier.width(ButtonHeight) else Modifier.padding(horizontal = 32.dp, vertical = 6.dp)),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val glyph: @Composable () -> Unit = {
                if (icon != null) Icon(icon, if (text == null) contentDescription else null, Modifier.size(if (text == null) 30.dp else 26.dp), tint = WmmColors.Text)
            }
            if (!iconAfter) glyph()
            if (text != null) {
                Text(
                    if (detail != null) "$text · $detail" else text,
                    style = TvType.BodyStrong,
                    color = WmmColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (iconAfter) glyph()
        }
    }
}

/** A button's height, as everywhere on the TV. */
internal val ButtonHeight = 72.dp

/**
 * Shows [content] with an entrance and drops it at once when the caller
 * stops composing it, so focus never stays on something fading out.
 */
@Composable
internal fun Entering(enter: EnterTransition, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(state, modifier, enter = enter, exit = ExitTransition.None) { content() }
}
