package media.watchmy.client.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/**
 * An overlay above the screen, full screen and without the platform dim,
 * that takes focus while it is open. Back calls [onDismiss], so overlays
 * close before the screen behind them goes back.
 */
@Composable
fun TvDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    // A dialog's window starts from the system density; keep the TV theme's (see TvTheme).
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        CompositionLocalProvider(LocalDensity provides density, content = content)
    }
}

/** Which side a [TvSidePanel] comes from: the right for choices about what is on screen, the left for the rail's person and On now. */
enum class PanelSide { Right, Left }

/** A panel's width: 736 for choices, 900 for File details and Playback info, which carry longer lines. */
val PanelWidth = 736.dp
val WidePanelWidth = 900.dp

/**
 * A panel over a dimmed screen, from the right (or [side]): [width] wide,
 * full height, its content inside the safe area. Used by every TV panel so
 * they all look and move alike. [kicker] and [title] head it; [context] is
 * a grey line under the title.
 */
@Composable
fun TvSidePanel(
    title: String?,
    modifier: Modifier = Modifier,
    side: PanelSide = PanelSide.Right,
    width: Dp = PanelWidth,
    kicker: String? = null,
    context: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(TvPalette.Dim), contentAlignment = if (side == PanelSide.Right) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier
                .width(width)
                .fillMaxHeight()
                .background(WmmColors.Surface)
                .drawBehind {
                    val x = if (side == PanelSide.Right) 0f else size.width
                    drawLine(TvPalette.Line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                }
                .padding(
                    start = if (side == PanelSide.Right) 48.dp else SafeX,
                    end = if (side == PanelSide.Right) SafeX else 48.dp,
                    top = SafeY,
                    bottom = SafeY,
                ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (kicker != null || title != null || context != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (kicker != null) Text(kicker.uppercase(), style = TvType.Group, color = TvPalette.Muted)
                    if (title != null) Text(title, style = TvType.Heading, color = WmmColors.Text)
                    if (context != null) Text(context, style = TvType.Second, color = TvPalette.Muted)
                }
            }
            content()
        }
    }
}

/**
 * A panel of choices (sort, decade, quality): [options] with a gold tick
 * by [selected]; focus starts on the selected one. [context] goes under the
 * title, [footer] under the choices. A choice applies at once.
 */
@Composable
fun <T> OptionsDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    description: ((T) -> String?)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    /** A grey line under the title: what the choices are for ("When a video starts", "Audio languages"). */
    context: String? = null,
    /** A small heading over the title: the list the item is from ("Shows you follow"). */
    kicker: String? = null,
    /** The option focus is on, as it moves: a preview follows it (Subtitle size's). */
    onFocus: ((T) -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }
    TvDialog(onDismiss) {
        TvSidePanel(title, kicker = kicker, context = context) {
            val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
            LazyColumn(
                Modifier.selectableGroup().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                // Room for the focused row's ring and growth.
                contentPadding = PaddingValues(vertical = 10.dp, horizontal = 10.dp),
            ) {
                itemsIndexed(options) { i, option ->
                    OptionRow(
                        text = label(option),
                        description = description?.invoke(option),
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        modifier = (if (i == selectedIndex) Modifier.focusRequester(focus) else Modifier)
                            .then(if (onFocus != null) Modifier.onFocusChanged { if (it.isFocused) onFocus(option) } else Modifier),
                    )
                }
            }
            footer?.invoke(this)
        }
        InitialFocus(focus)
    }
}

/**
 * A row in a panel (`.opt`): its words, a second line when given, and a
 * gold tick when [selected]. Focused, it lifts, takes the gold ring and
 * grows a little toward the panel's middle. TalkBack hears it as a radio
 * button, selected or not.
 */
@Composable
fun OptionRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    PanelRow(
        text = text,
        onClick = onClick,
        modifier = modifier.semantics {
            role = Role.RadioButton
            this.selected = selected
        },
        description = description,
        chosen = selected,
        enabled = enabled,
    )
}

/**
 * A row in a panel that does something rather than choose (`.opt`): Mark
 * as watched, File details. [icon] leads it; [chosen] puts a gold tick at
 * its end; [trailing] is a grey word at its end ("Automatic").
 */
@Composable
fun PanelRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = WmmColors.TextSoft,
    chosen: Boolean = false,
    trailing: String? = null,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    /** A chevron at its end: it opens something rather than doing it. */
    chevron: Boolean = false,
) {
    val shape = RoundedCornerShape(16.dp)
    FocusableBox(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f),
        cornerRadius = 16.dp,
        focusedScale = FocusGrowth.ROW,
        enabled = enabled,
        role = Role.Button,
    ) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp)
                .background(if (focused) TvPalette.Lift else Color.Transparent, shape)
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text, style = if (chosen) TvType.BodyStrong else TvType.Body.copy(fontWeight = FontWeight.Medium), color = WmmColors.Text, maxLines = 2)
                if (description != null) Text(description, style = TvType.Caption, color = TvPalette.Muted, maxLines = 3)
            }
            if (trailing != null) Text(trailing, style = TvType.Second, color = TvPalette.Muted, maxLines = 1)
            if (chosen) Icon(WmmIcons.Check, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(30.dp))
            if (chevron) Icon(WmmIcons.ChevronRight, contentDescription = null, tint = TvPalette.Muted, modifier = Modifier.size(28.dp))
        }
    }
}

/** A small upper-case heading over a group of rows in a panel ("SUBTITLES"). */
@Composable
fun PanelGroup(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = TvType.Group, color = TvPalette.Muted, modifier = modifier.padding(start = 24.dp, top = 12.dp))
}

/** "Back closes this panel.", at the foot of a side panel. */
@Composable
fun BackClosesNote(modifier: Modifier = Modifier) {
    Text("Back closes this panel.", style = TvType.Caption, color = TvPalette.Muted, modifier = modifier.padding(horizontal = 24.dp))
}
