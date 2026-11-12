package media.watchmy.client.mobile.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/** Outlined text field colours: a grey rim at 3:1 ([WmmColors.FieldOutline]), gold when focused. */
@Composable
fun fieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = WmmColors.Text,
    unfocusedTextColor = WmmColors.Text,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    cursorColor = WmmColors.Accent,
    selectionColors = TextSelectionColors(handleColor = WmmColors.Accent, backgroundColor = WmmColors.Accent.copy(alpha = 0.3f)),
    focusedBorderColor = WmmColors.Accent,
    unfocusedBorderColor = WmmColors.FieldOutline,
    focusedLabelColor = WmmColors.Accent,
    unfocusedLabelColor = WmmColors.TextMuted,
    focusedTrailingIconColor = WmmColors.TextMuted,
    unfocusedTrailingIconColor = WmmColors.TextMuted,
    focusedPlaceholderColor = WmmColors.TextFaint,
    unfocusedPlaceholderColor = WmmColors.TextFaint,
    errorBorderColor = WmmColors.Error,
    errorLabelColor = WmmColors.Error,
    errorSupportingTextColor = WmmColors.Error,
    errorCursorColor = WmmColors.Error,
    errorTrailingIconColor = WmmColors.TextMuted,
    errorTextColor = WmmColors.Text,
)

val FieldText = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = WmmColors.Text)

/** A filter chip as the Library mockup draws it: gold tint, rim and check when selected. */
@Composable
fun GoldChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, trailingIcon: ImageVector? = null) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp), maxLines = 1) },
        modifier = modifier,
        leadingIcon = if (selected) {
            { Icon(WmmIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else {
            null
        },
        trailingIcon = trailingIcon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) } },
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = Color(0xFFE5E5E5),
            iconColor = Color(0xFFE5E5E5),
            selectedContainerColor = WmmColors.Accent.copy(alpha = 0.18f),
            selectedLabelColor = WmmColors.Accent,
            selectedLeadingIconColor = WmmColors.Accent,
            selectedTrailingIconColor = WmmColors.Accent,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = WmmColors.Line3,
            selectedBorderColor = WmmColors.Accent,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp,
        ),
    )
}

/** An upper-case section label ("ON THIS NETWORK", "PLAYBACK"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(horizontal = 16.dp),
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.96.sp, color = WmmColors.TextMuted),
    )
}

/** A Home row heading: "Recently added" with a muted "TV Shows". */
@Composable
fun RowHeading(title: String, modifier: Modifier = Modifier, small: String? = null) {
    Row(modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = WmmColors.Text), modifier = Modifier.alignByBaseline())
        if (small != null) {
            Text(small, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = WmmColors.TextMuted), modifier = Modifier.alignByBaseline())
        }
    }
}

/**
 * A list row as Settings and the server list draw it: optional leading
 * content, a title with a line under it, [below] under that, and trailing
 * content.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = WmmColors.TextMuted,
    minHeight: Dp = 56.dp,
    titleSize: Int = 16,
    subtitleSize: Int = 13,
    leading: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = minHeight).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = titleSize.sp, color = WmmColors.Text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = TextStyle(fontFamily = Inter, fontSize = subtitleSize.sp, lineHeight = (subtitleSize * 1.35).sp, color = subtitleColor), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            below?.invoke()
        }
        trailing?.invoke(this)
    }
}

/**
 * "Not encrypted" in orange with an open lock (ServerNotEncrypted.dc.html):
 * a server reached over plain http beyond this network.
 */
@Composable
fun NotEncryptedMark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(WmmIcons.LockOpen, contentDescription = null, tint = WmmColors.Warn, modifier = Modifier.size(14.dp))
        Text("Not encrypted", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = WmmColors.Warn))
    }
}

/** A value on the right of a Settings row, with a chevron when it opens something. */
@Composable
fun RowValue(text: String, chevron: Boolean = true, icon: ImageVector? = if (chevron) WmmIcons.ChevronRight else null, size: Int = 14) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = TextStyle(fontFamily = Inter, fontSize = size.sp, color = WmmColors.TextSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (icon != null) Icon(icon, contentDescription = null, tint = WmmColors.TextSoft, modifier = Modifier.size(20.dp))
    }
}
