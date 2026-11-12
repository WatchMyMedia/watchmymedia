package media.watchmy.client.mobile.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors

/** The burgundy pill: the screen's main action. [large] is the 48 dp version (Sign in, Play). */
@Composable
fun FilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
) = PillButton(
    text, onClick, modifier, large, enabled, busy, icon,
    ButtonDefaults.buttonColors(
        containerColor = WmmColors.Brand,
        contentColor = Color.White,
        disabledContainerColor = WmmColors.Brand.copy(alpha = 0.5f),
        disabledContentColor = Color.White.copy(alpha = 0.6f),
    ),
)

/** The grey pill: secondary actions ("Go to show", "Mark watched"). [compact] narrows it for two or more in a row. */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
    compact: Boolean = false,
) = PillButton(
    text, onClick, modifier, large, enabled, busy, icon,
    ButtonDefaults.buttonColors(
        containerColor = WmmColors.Line2,
        contentColor = WmmColors.Text,
        disabledContainerColor = WmmColors.Line2.copy(alpha = 0.5f),
        disabledContentColor = WmmColors.TextMuted,
    ),
    compact,
)

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    large: Boolean,
    enabled: Boolean,
    busy: Boolean,
    icon: ImageVector?,
    colors: ButtonColors,
    compact: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(if (large) 48.dp else 40.dp),
        enabled = enabled && !busy,
        shape = CircleShape,
        colors = colors,
        contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 24.dp),
    ) {
        val iconSize = if (large) 20.dp else 18.dp
        if (busy) {
            CircularProgressIndicator(Modifier.size(iconSize), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = if (compact) buttonText(false).copy(fontSize = 13.sp) else buttonText(large), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The gold text button ("Change", "Play this", "Sign out"). */
@Composable
fun GoldTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = WmmColors.Accent) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = WmmColors.TextFaint),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(text, style = buttonText(false))
    }
}

private fun buttonText(large: Boolean) =
    TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = if (large) 15.sp else 14.sp)
