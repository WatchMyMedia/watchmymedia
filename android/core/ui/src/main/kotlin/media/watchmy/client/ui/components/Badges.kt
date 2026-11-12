package media.watchmy.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.ui.Inter
import media.watchmy.client.model.watchedLabel
import media.watchmy.client.ui.WmmColors

enum class BadgeStyle { Dark, Gold, Warn, Outline }

/**
 * A small upper-case label on artwork ("NEW", "4K", "S03 · E07", "NOT IN
 * LIBRARY"); a runtime ("48 min") keeps its case with [uppercase] false.
 * Sizes default to the phone mockups; TV passes larger ones.
 */
@Composable
fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    style: BadgeStyle = BadgeStyle.Dark,
    fontSize: TextUnit = 10.sp,
    horizontalPadding: Dp = 6.dp,
    verticalPadding: Dp = 3.dp,
    uppercase: Boolean = true,
) {
    val (bg, fg) = when (style) {
        BadgeStyle.Dark -> Color.Black.copy(alpha = 0.72f) to WmmColors.Text
        BadgeStyle.Gold -> WmmColors.Accent to WmmColors.OnAccent
        BadgeStyle.Warn -> WmmColors.Warn to WmmColors.OnAccent
        BadgeStyle.Outline -> Color.Transparent to WmmColors.Text
    }
    val shape = RoundedCornerShape(4.dp)
    var m = modifier.background(bg, shape)
    if (style == BadgeStyle.Outline) m = m.border(1.dp, WmmColors.Line3, shape)
    Text(
        text = if (uppercase) text.uppercase() else text,
        modifier = m.padding(horizontal = horizontalPadding, vertical = verticalPadding),
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = if (uppercase) fontSize * 0.05f else 0.sp, color = fg),
    )
}

/**
 * The gold progress strip along the bottom of artwork; [progress] is 0–1.
 * TalkBack reads it as "45% watched", within the card it is on.
 */
@Composable
fun ProgressStrip(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val label = watchedLabel(progress)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(Color.Black.copy(alpha = 0.6f))
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
                stateDescription = label
            },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(WmmColors.Accent))
    }
}

/** The gold watched check in a card's corner. */
@Composable
fun WatchedCheck(modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Box(modifier.size(size).background(WmmColors.Accent, CircleShape), contentAlignment = Alignment.Center) {
        Icon(WmmIcons.Check, contentDescription = "Watched", tint = WmmColors.OnAccent, modifier = Modifier.size(size * 0.65f))
    }
}
