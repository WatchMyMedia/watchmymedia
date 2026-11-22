package media.watchmy.client.mobile.watchlists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.mobile.components.fieldColors
import media.watchmy.client.model.Watchlist
import media.watchmy.client.model.WatchlistTitle
import media.watchmy.client.model.WatchlistWords
import media.watchmy.client.presentation.watchlists.Naming
import media.watchmy.client.presentation.watchlists.WatchlistMessage
import media.watchmy.client.presentation.watchlists.WatchlistPickerActions
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalWatchlistControls
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/**
 * Watchlist, on movie and show pages:
 * the outline bookmark and "Watchlist", or the filled one in gold and "On
 * Watchlist", "On Halloween", "On 2 watchlists". A toggle to screen readers.
 * Left out where nothing saves ([read] null: a server without watchlists,
 * or a title TMDB hasn't matched).
 */
@Composable
internal fun WatchlistButton(title: WatchlistTitle?, read: List<Long>?, modifier: Modifier = Modifier) {
    val controls = LocalWatchlistControls.current ?: return
    if (title == null || read == null) return
    val ids = controls.holding(title.key, read)
    val names = controls.names(ids)
    val on = ids.isNotEmpty()
    TonalButton(
        WatchlistWords.button(names),
        { controls.press(title, ids) },
        modifier.semantics {
            contentDescription = WatchlistWords.buttonSpoken(names, title.title)
            toggleableState = ToggleableState(on)
        },
        icon = if (on) WmmIcons.BookmarkFilled else WmmIcons.Bookmark,
        contentColor = if (on) WmmColors.Accent else WmmColors.Text,
    )
}

private val SheetGround = Color(0xFF1C1C1F)
private val Line = Color(0xFF2E2E33)

/**
 * Save to watchlist: a sheet from the
 * bottom on a phone, a dialog on a tablet. Every watchlist with a check,
 * each saved or removed at once; New watchlist… names one (Create waits for
 * a name), or the limit's line where it was; Done, Back or a tap outside
 * close it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WatchlistPicker(
    title: WatchlistTitle,
    rows: List<Pair<Watchlist, Boolean>>,
    naming: Naming?,
    atLimit: Boolean,
    max: Int,
    tablet: Boolean,
    actions: WatchlistPickerActions,
) {
    val content: @Composable ColumnScope.() -> Unit = {
        Text(WatchlistWords.PICKER, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = WmmColors.Text), modifier = Modifier.semantics { heading() })
        Text(title.title, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = WmmColors.TextMuted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            rows.forEach { (w, on) -> CheckRow(w.name, WatchlistWords.titles(w.titles), on) { actions.toggle(w) } }
        }
        when {
            naming != null -> NameField(naming, actions::cancelNaming, actions::create, actions::nameChanged, Modifier.padding(top = 8.dp))
            atLimit -> Text(WatchlistWords.limit(max), style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextMuted), modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
            else -> NewRow(actions::startNaming)
        }
        if (naming == null) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) { FilledButton(WatchlistWords.DONE, actions::closePicker) }
        }
    }
    if (tablet) {
        Dialog(onDismissRequest = actions::closePicker) {
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = 620.dp).background(SheetGround, RoundedCornerShape(24.dp)).border(1.dp, Line, RoundedCornerShape(24.dp))
                    .padding(20.dp),
                content = content,
            )
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = actions::closePicker,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = SheetGround,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(Modifier.navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = content)
        }
    }
}

/** A watchlist with its check (`.wr`): a checkbox to screen readers. */
@Composable
private fun CheckRow(name: String, count: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CheckBox(checked)
        Text(name, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = WmmColors.Text), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
    }
}

/** The check, gold when on (`.bx`). */
@Composable
internal fun CheckBox(checked: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.size(22.dp).then(if (checked) Modifier.background(WmmColors.Accent, shape) else Modifier.border(2.dp, WmmColors.TextMuted, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(WmmIcons.Check, contentDescription = null, tint = WmmColors.OnAccent, modifier = Modifier.size(16.dp))
    }
}

/** New watchlist…, in gold with a plus. */
@Composable
private fun NewRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { Icon(WmmIcons.Plus, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(20.dp)) }
        Text(WatchlistWords.NEW_ONE, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = WmmColors.Accent))
    }
}

/**
 * Name, for New watchlist and Rename: the field with the keyboard up, why
 * the name was refused under it, then Cancel and [confirm] (Create, Save),
 * which waits for a name.
 */
@Composable
internal fun NameField(
    naming: Naming,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
    confirm: String = WatchlistWords.CREATE,
) {
    val field = rememberTextFieldState(naming.current)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(field) {
        var first = true
        snapshotFlow { field.text.toString() }.collect { if (!first) onChanged(); first = false }
    }
    Column(modifier) {
        OutlinedTextField(
            state = field,
            label = { Text(WatchlistWords.NAME) },
            lineLimits = TextFieldLineLimits.SingleLine,
            isError = naming.error != null,
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            onKeyboardAction = { if (field.text.isNotBlank()) onConfirm(field.text.toString()) },
        )
        naming.error?.let {
            Text(
                it,
                style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp, color = WmmColors.Warn),
                modifier = Modifier.padding(start = 4.dp, top = 6.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            GoldTextButton(WatchlistWords.CANCEL, onCancel, color = WmmColors.TextSoft)
            FilledButton(confirm, { onConfirm(field.text.toString()) }, enabled = field.text.isNotBlank() && !naming.busy)
        }
    }
}

/**
 * The message after saving or removing (`.snack`): light, over the page's
 * foot, with Undo where it takes the change back. When it goes, by itself
 * or swiped away, [onShown] sends what it held.
 */
@Composable
internal fun WatchlistMessageHost(message: WatchlistMessage?, onUndo: () -> Unit, onShown: () -> Unit, modifier: Modifier = Modifier) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            val result = host.showSnackbar(message.text, actionLabel = WatchlistWords.UNDO.takeIf { message.undo }, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) onUndo() else onShown()
        }
    }
    SnackbarHost(host, modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { data ->
        Snackbar(
            containerColor = Color(0xFFE5E5E5),
            contentColor = WmmColors.Ground,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            action = data.visuals.actionLabel?.let { label ->
                {
                    TextButton(onClick = { data.performAction() }) {
                        Text(label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = WmmColors.Brand))
                    }
                }
            },
        ) {
            Text(data.visuals.message, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp))
        }
    }
}

/** A watchlist's name and counts (`.wl`'s lines): "12 titles · 9 in your library", and Keeps watched titles. */
@Composable
internal fun ColumnScope.WatchlistLines(watchlist: Watchlist) {
    Text(WatchlistWords.line(watchlist.titles, watchlist.inLibrary), style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
    if (!watchlist.removeWatched) Text(WatchlistWords.KEEPS, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = WmmColors.Accent))
}
