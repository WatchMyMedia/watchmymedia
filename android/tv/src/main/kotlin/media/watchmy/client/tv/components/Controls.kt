package media.watchmy.client.tv.components

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
import androidx.compose.ui.draw.alpha
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

/** #E5E5E5: the mockups' text on chips, tags and fact lines, between Text and TextSoft. */
val TextBright = Color(0xFFE5E5E5)

/** `.btn.danger`: Delete episode. */
private val Danger = Color(0xFFB42318)
private val DangerFocused = Color(0xFFC92A1D)
private val DangerRim = Color(0xFFE5484D)

/** Burgundy fill with the rim (primary), translucent white (ghost), or red for deleting, as `.btn.primary`, `.btn.ghost` and `.btn.danger`. */
enum class TvButtonStyle { Primary, Ghost, Danger }

/** The mockups' button sizes: hero (64), detail (60), version rows (50), settings (56) and settings rows (44). */
enum class TvButtonSize(val height: Dp, val fontSize: TextUnit, val padding: Dp, val icon: Dp, val radius: Dp) {
    Large(64.dp, 22.sp, 30.dp, 26.dp, 14.dp),
    Medium(60.dp, 21.sp, 26.dp, 24.dp, 14.dp),
    Small(50.dp, 19.sp, 22.dp, 22.dp, 14.dp),
    Settings(56.dp, 19.sp, 24.dp, 22.dp, 12.dp),
    Compact(44.dp, 17.sp, 16.dp, 20.dp, 12.dp),
}

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TvButtonStyle = TvButtonStyle.Ghost,
    size: TvButtonSize = TvButtonSize.Large,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(size.radius)
    FocusableBox(
        onClick = onClick,
        modifier = modifier.alpha(if (enabled) 1f else 0.5f),
        cornerRadius = size.radius,
        focusedScale = 1.04f,
        enabled = enabled,
    ) { focused ->
        val base = Modifier.height(size.height)
        val styled = when (style) {
            TvButtonStyle.Primary -> base
                .background(if (focused) WmmColors.BrandHover else WmmColors.Brand, shape)
                .border(1.dp, WmmColors.BrandLine, shape)
            TvButtonStyle.Ghost -> base
                .background(Color.White.copy(alpha = if (focused) 0.16f else 0.10f), shape)
                .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            TvButtonStyle.Danger -> base
                .background(if (focused) DangerFocused else Danger, shape)
                .border(1.dp, DangerRim, shape)
        }
        Row(
            styled.padding(horizontal = size.padding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size.icon))
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
 * A filter chip (`.chip`): gold when [selected]. TalkBack hears [role] (a
 * checkbox for a filter, a drop-down list for one that opens a picker) and
 * whether it is selected.
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
    val shape = RoundedCornerShape(24.dp)
    val described = modifier.semantics {
        this.role = role
        this.selected = selected
    }
    FocusableBox(onClick = onClick, modifier = described, cornerRadius = 24.dp, focusedScale = 1.04f) { focused ->
        val bg = when {
            selected -> WmmColors.Accent
            focused -> WmmColors.Surface3
            else -> WmmColors.Surface2
        }
        Row(
            Modifier
                .height(48.dp)
                .background(bg, shape)
                .border(1.dp, if (selected) WmmColors.Accent else WmmColors.Line2, shape)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val fg = if (selected) WmmColors.OnAccent else TextBright
            if (leadingIcon != null) Icon(leadingIcon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 19.sp), color = fg, maxLines = 1)
            if (trailingIcon != null) Icon(trailingIcon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        }
    }
}

/** An outlined tag (`.tag`): "4K", "HEVC", "EAC3 5.1". Gold fills it ("Default"). */
@Composable
fun TvTag(text: String, modifier: Modifier = Modifier, gold: Boolean = false, fontSize: TextUnit = 15.sp) {
    val shape = RoundedCornerShape(6.dp)
    val m = if (gold) modifier.background(WmmColors.Accent, shape) else modifier.border(1.dp, WmmColors.Line3, shape)
    Text(
        text.uppercase(),
        modifier = m.padding(horizontal = 10.dp, vertical = 4.dp),
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = fontSize * 0.04f),
        color = if (gold) WmmColors.OnAccent else TextBright,
        maxLines = 1,
    )
}

/** The gold upper-case kicker above a big title ("SIGN IN", "NEW EPISODE · AIRED YESTERDAY"). */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 20.sp) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = fontSize, letterSpacing = fontSize * 0.14f),
        color = WmmColors.Accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Shorthand for Inter at a mockup size. */
fun inter(size: TextUnit, weight: FontWeight = FontWeight.Normal, lineHeight: TextUnit = TextUnit.Unspecified, tracking: Float = 0f) =
    TextStyle(fontFamily = Inter, fontWeight = weight, fontSize = size, lineHeight = lineHeight, letterSpacing = size * tracking)
