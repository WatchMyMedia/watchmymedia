package media.watchmy.client.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/** #E5E5E5: text on tags and fact lines, between Text and TextSoft. */
val TextBright = Color(0xFFE5E5E5)

/** The outline of a button that deletes: the warning orange, since red isn't one of the TV's colours. */
private val DangerLine = WmmColors.Warn

/**
 * Burgundy fill with its rim (the screen's one main button), a dark fill
 * (every other button), or a dark fill outlined in the warning orange for
 * one that deletes.
 */
enum class TvButtonStyle { Primary, Ghost, Danger }

/**
 * Button sizes. Every button on a screen's own surface is 72 high with
 * 26 sp words ([Large], [Medium]); [Small] and [Settings] are for a row of
 * secondary actions inside a panel or a list.
 */
enum class TvButtonSize(val height: Dp, val fontSize: TextUnit, val padding: Dp, val icon: Dp, val radius: Dp) {
    Large(72.dp, 26.sp, 32.dp, 30.dp, 16.dp),
    Medium(72.dp, 26.sp, 32.dp, 30.dp, 16.dp),
    Small(64.dp, 24.sp, 26.dp, 26.dp, 14.dp),
    Settings(64.dp, 24.sp, 26.dp, 26.dp, 14.dp),
    Compact(56.dp, 22.sp, 20.dp, 24.dp, 12.dp),
}

/**
 * A button. Focused, it takes the gold ring, grows and casts a shadow; the
 * main button keeps its burgundy and the others lift to [TvPalette.Lift].
 * Not [enabled], it dims and stays focusable, so people can find out why.
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TvButtonStyle = TvButtonStyle.Ghost,
    size: TvButtonSize = TvButtonSize.Large,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    /** The icon's colour: gold for a state that is on (a show's Notifications on, On Watchlist). */
    iconTint: Color = Color.White,
    /** The left edge for the first button at the safe edge, so it grows away from it. */
    growFrom: TransformOrigin = TransformOrigin.Center,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(size.radius)
    FocusableBox(
        onClick = onClick,
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
        onLongClick = onLongClick,
        cornerRadius = size.radius,
        focusedScale = FocusGrowth.BUTTON,
        enabled = enabled,
        role = Role.Button,
        growFrom = growFrom,
    ) { focused ->
        // At least the button's height; taller when the TV's text size is larger.
        val base = Modifier.heightIn(min = size.height)
        val styled = when (style) {
            TvButtonStyle.Primary -> base
                .background(WmmColors.Brand, shape)
                .border(2.dp, WmmColors.BrandLine, shape)
            TvButtonStyle.Ghost -> base
                .background(if (focused) TvPalette.Lift else TvPalette.ButtonFill, shape)
                .border(2.dp, if (focused) TvPalette.Lift else Color.White.copy(alpha = 0.2f), shape)
            TvButtonStyle.Danger -> base
                .background(if (focused) TvPalette.Lift else TvPalette.ButtonFill, shape)
                .border(2.dp, DangerLine, shape)
        }
        Row(
            styled.padding(horizontal = size.padding, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(size.icon))
            Text(
                text,
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = size.fontSize),
                color = if (style == TvButtonStyle.Ghost) WmmColors.Text else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A choice that stays chosen (`.tab`): a filter, a day. Chosen, it is
 * marked by a gold tick and brighter words, never a fill or a ring, so it
 * can't be taken for focus. TalkBack hears [role] (a checkbox for a filter,
 * a drop-down list for one that opens a picker) and whether it is selected.
 */
@Composable
fun TvChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: ImageVector? = null,
    role: Role = if (trailingIcon != null) Role.DropdownList else Role.Checkbox,
    leadingIcon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    val described = modifier.semantics { this.selected = selected }
    FocusableBox(onClick = onClick, modifier = described, cornerRadius = 16.dp, focusedScale = FocusGrowth.BUTTON, role = role) { focused ->
        Row(
            Modifier
                .heightIn(min = 64.dp)
                .background(if (focused) TvPalette.Lift else TvPalette.ButtonFill, shape)
                .border(2.dp, if (focused) TvPalette.Lift else Color.White.copy(alpha = 0.14f), shape)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val fg = if (selected || focused) WmmColors.Text else TvPalette.Muted
            when {
                selected -> Icon(WmmIcons.Check, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(26.dp))
                leadingIcon != null -> Icon(leadingIcon, contentDescription = null, tint = fg, modifier = Modifier.size(26.dp))
            }
            Text(text, style = TvType.BodyStrong, color = fg, maxLines = 1)
            if (trailingIcon != null) Icon(trailingIcon, contentDescription = null, tint = fg, modifier = Modifier.size(26.dp))
        }
    }
}

/**
 * A tab (`.tab`): the words alone at rest, a gold underline when it is the
 * one showing ([selected]), and the focus treatment when focused.
 */
@Composable
fun TvTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    enabled: Boolean = true,
    growFrom: TransformOrigin = TransformOrigin.Center,
) {
    val shape = RoundedCornerShape(16.dp)
    FocusableBox(
        onClick = onClick,
        modifier = modifier.semantics {
            this.selected = selected
            role = Role.Tab
        }.alpha(if (enabled) 1f else 0.45f),
        cornerRadius = 16.dp,
        focusedScale = FocusGrowth.BUTTON,
        enabled = enabled,
        growFrom = growFrom,
    ) { focused ->
        Row(
            Modifier
                .heightIn(min = 64.dp)
                .background(if (focused) TvPalette.Lift else Color.Transparent, shape)
                .padding(horizontal = 26.dp)
                .drawBehind {
                    // Inside the padding, so the underline is as wide as the words, 6 dp above the tab's foot.
                    if (selected) {
                        val h = 4.dp.toPx()
                        drawRoundRect(WmmColors.Accent, topLeft = Offset(0f, size.height - 6.dp.toPx() - h), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2))
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text, style = TvType.BodyStrong, color = if (selected || focused) WmmColors.Text else TvPalette.Muted, maxLines = 1)
            if (trailing != null) Text(trailing, style = TvType.Second, color = TvPalette.Muted, maxLines = 1)
        }
    }
}

/** An outlined tag (`.tag`): "4K", "HDR", "TV-14". [gold] outlines it in gold (New). */
@Composable
fun TvTag(text: String, modifier: Modifier = Modifier, gold: Boolean = false, @Suppress("UNUSED_PARAMETER") fontSize: TextUnit = 22.sp) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text.uppercase(),
        modifier = modifier
            .border(2.dp, if (gold) WmmColors.Accent else TvPalette.Line3, shape)
            .heightIn(min = 38.dp)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = 0.66.sp),
        color = if (gold) WmmColors.Accent else WmmColors.TextSoft,
        maxLines = 1,
    )
}

/** The gold upper-case kicker above a big title ("CONTINUE WATCHING", "NEW EPISODE · AIRED YESTERDAY"). */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, @Suppress("UNUSED_PARAMETER") fontSize: TextUnit = 22.sp) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = TvType.Kicker,
        color = WmmColors.Accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Shorthand for Inter at a mockup size. */
fun inter(size: TextUnit, weight: FontWeight = FontWeight.Normal, lineHeight: TextUnit = TextUnit.Unspecified, tracking: Float = 0f) =
    TextStyle(fontFamily = Inter, fontWeight = weight, fontSize = size, lineHeight = lineHeight, letterSpacing = size * tracking)
