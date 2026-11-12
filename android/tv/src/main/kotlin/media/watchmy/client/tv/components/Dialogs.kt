package media.watchmy.client.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    // A dialog's window starts from the system density; keep the TV canvas's (see TvTheme).
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        CompositionLocalProvider(LocalDensity provides density, content = content)
    }
}

/**
 * A side sheet of choices (sort, decade, quality): [options] with a gold
 * check by [selected]; focus starts on the selected one. [context] goes
 * under the title, [footer] under the choices (a preview, "Back closes
 * this panel.").
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
) {
    val focus = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(600.dp)
                    .fillMaxHeight()
                    .background(WmmColors.Surface)
                    .border(1.dp, WmmColors.Line)
                    .padding(start = 44.dp, end = 44.dp, top = 64.dp, bottom = 44.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = inter(30.sp, FontWeight.SemiBold), color = WmmColors.Text)
                    if (context != null) Text(context, style = inter(19.sp), color = WmmColors.TextMuted)
                }
                val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
                LazyColumn(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(options) { i, option ->
                        OptionRow(
                            text = label(option),
                            description = description?.invoke(option),
                            selected = option == selected,
                            onClick = { onSelect(option) },
                            modifier = if (i == selectedIndex) Modifier.focusRequester(focus) else Modifier,
                        )
                    }
                }
                footer?.invoke(this)
            }
        }
        InitialFocus(focus)
    }
}

/**
 * A settings-style row: label on the left, a check when [selected]; the
 * inset ring when focused (`.row.focus`). A long [description] takes a
 * second line. TalkBack hears it as a radio button, selected or not.
 */
@Composable
fun OptionRow(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, description: String? = null) {
    FocusableBox(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics {
            role = Role.RadioButton
            this.selected = selected
        },
        cornerRadius = 12.dp,
        focusedScale = 1f,
        ringOffset = (-4).dp,
    ) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (description != null) 84.dp else 68.dp)
                .background(if (focused) WmmColors.Surface2 else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text, style = inter(21.sp, FontWeight.SemiBold), color = if (selected) WmmColors.Accent else WmmColors.Text, maxLines = 1)
                if (description != null) Text(description, style = inter(17.sp, lineHeight = 23.sp), color = WmmColors.TextMuted, maxLines = 2)
            }
            if (selected) Icon(WmmIcons.Check, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(26.dp))
        }
    }
}

/** "Back closes this panel.", at the foot of a side panel. */
@Composable
fun BackClosesNote(modifier: Modifier = Modifier) {
    Text("Back closes this panel.", style = inter(17.sp), color = WmmColors.TextFaint, modifier = modifier.padding(horizontal = 18.dp))
}
