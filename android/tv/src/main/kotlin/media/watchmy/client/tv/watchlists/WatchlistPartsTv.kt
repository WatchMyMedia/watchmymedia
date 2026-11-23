package media.watchmy.client.tv.watchlists

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.Watchlist
import media.watchmy.client.model.WatchlistTitle
import media.watchmy.client.model.WatchlistWords
import media.watchmy.client.model.watchlistTitle
import media.watchmy.client.presentation.watchlists.Naming
import media.watchmy.client.presentation.watchlists.WatchlistPickerActions
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.KeyboardField
import media.watchmy.client.tv.components.OnScreenKeyboard
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.LocalWatchlistControls
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/**
 * Watchlist, on the TV's movie and show pages: the outline bookmark and
 * "Watchlist", or the gold filled one and "On Watchlist", "On Halloween",
 * "On 2 watchlists". OK toggles with no message (the label changing is
 * enough on a TV), or opens Save to watchlist with two or more. Left out
 * where nothing saves.
 */
@Composable
internal fun WatchlistButtonTv(title: WatchlistTitle?, read: List<Long>?, modifier: Modifier = Modifier, size: TvButtonSize = TvButtonSize.Large) {
    val controls = LocalWatchlistControls.current ?: return
    if (title == null || read == null) return
    val ids = controls.holding(title.key, read)
    val names = controls.names(ids)
    val on = ids.isNotEmpty()
    TvButton(
        WatchlistWords.button(names),
        { controls.press(title, ids) },
        modifier.semantics {
            contentDescription = WatchlistWords.buttonSpoken(names, title.title)
            toggleableState = ToggleableState(on)
        },
        size = size,
        icon = if (on) WmmIcons.BookmarkFilled else WmmIcons.Bookmark,
        iconTint = if (on) WmmColors.Accent else Color.White,
    )
}

/** Whether the page has a Watchlist button: a server with watchlists and a title TMDB has matched. */
@Composable
@ReadOnlyComposable
internal fun savesHere(title: WatchlistTitle?, read: List<Long>?): Boolean = LocalWatchlistControls.current != null && title != null && read != null

/**
 * Save to watchlist on the TV: a panel from
 * the right. Each watchlist is a check, saved or removed at once; New
 * watchlist… widens the panel to a Name field with the keyboard Search uses
 * (and Use the TV's keyboard), Cancel and Create; at 25 the limit's line
 * stands where it was. Done and Back close it.
 */
@Composable
internal fun WatchlistPanelTv(
    title: WatchlistTitle,
    rows: List<Pair<Watchlist, Boolean>>,
    naming: Naming?,
    atLimit: Boolean,
    max: Int,
    actions: WatchlistPickerActions,
) {
    TvDialog(onDismiss = { if (naming != null) actions.cancelNaming() else actions.closePicker() }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(if (naming != null) 800.dp else 600.dp)
                    .fillMaxHeight()
                    .background(WmmColors.Surface)
                    .border(1.dp, WmmColors.Line)
                    .padding(start = 44.dp, end = 44.dp, top = 64.dp, bottom = 44.dp)
                    .semantics { paneTitle = WatchlistWords.PICKER },
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(WatchlistWords.PICKER, style = inter(28.sp, FontWeight.SemiBold), color = WmmColors.Text, modifier = Modifier.semantics { heading() })
                    Text(title.title, style = inter(22.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (naming == null) Listing(rows, atLimit, max, actions) else NamingTv(naming, onCancel = actions::cancelNaming, onConfirm = actions::create, onChanged = actions::nameChanged)
            }
        }
    }
}

@Composable
private fun ColumnScope.Listing(rows: List<Pair<Watchlist, Boolean>>, atLimit: Boolean, max: Int, actions: WatchlistPickerActions) {
    val first = remember { FocusRequester() }
    LazyColumn(Modifier.heightIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(rows, key = { _, r -> r.first.id }) { i, (w, on) ->
            CheckOption(w.name, WatchlistWords.titles(w.titles), on, { actions.toggle(w) }, if (i == 0) Modifier.focusRequester(first) else Modifier)
        }
        if (!atLimit) item("new") { PanelRow(WatchlistWords.NEW_ONE, null, WmmIcons.Plus, actions::startNaming, gold = true, modifier = if (rows.isEmpty()) Modifier.focusRequester(first) else Modifier) }
    }
    if (atLimit) Text(WatchlistWords.limit(max), style = inter(22.sp, lineHeight = 31.sp), color = WmmColors.TextMuted)
    TvButton(WatchlistWords.DONE, actions::closePicker, size = TvButtonSize.Medium)
    Text(WatchlistWords.BACK_CLOSES, style = inter(22.sp), color = WmmColors.TextFaint)
    InitialFocus(first)
}

/** A watchlist with its check (`.opt` with `.cb`): a checkbox to screen readers. */
@Composable
private fun CheckOption(name: String, count: String, checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    FocusableBox(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth().semantics {
            role = Role.Checkbox
            toggleableState = ToggleableState(checked)
        },
        cornerRadius = 12.dp,
        focusedScale = 1f,
        ringOffset = (-4).dp,
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).background(if (focused) WmmColors.Surface2 else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            CheckBoxTv(checked)
            Text(name, style = inter(22.sp, FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(count, style = inter(22.sp), color = WmmColors.TextMuted)
        }
    }
}

@Composable
private fun CheckBoxTv(checked: Boolean, size: Dp = 32.dp) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(size).then(if (checked) Modifier.background(WmmColors.Accent, shape) else Modifier.border(2.dp, WmmColors.FieldOutline, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(WmmIcons.Check, contentDescription = null, tint = WmmColors.OnAccent, modifier = Modifier.size(size * 0.7f))
    }
}

/** A row of a TV panel or menu (`.mrow`, `.opt`): an icon and words. */
@Composable
internal fun PanelRow(label: String, line: String?, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, gold: Boolean = false, danger: Boolean = false) {
    FocusableBox(onClick = onClick, modifier = modifier.fillMaxWidth(), cornerRadius = 12.dp, focusedScale = 1f, ringOffset = (-4).dp, role = Role.Button) { focused ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).background(if (focused) WmmColors.Surface2 else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val tint = when {
                danger -> WmmColors.Warn
                gold -> WmmColors.Accent
                else -> WmmColors.Text
            }
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp)) }
            Column(Modifier.weight(1f)) {
                Text(label, style = inter(22.sp, FontWeight.SemiBold), color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (line != null) Text(line, style = inter(22.sp), color = WmmColors.TextMuted, maxLines = 2)
            }
        }
    }
}

/**
 * Naming on the TV: the Name field, why it was refused, the keyboard (with
 * Use the TV's keyboard), then Cancel and [confirm], which waits for a name.
 */
@Composable
internal fun ColumnScope.NamingTv(naming: Naming, onCancel: () -> Unit, onConfirm: (String) -> Unit, onChanged: () -> Unit, confirm: String = WatchlistWords.CREATE) {
    val field = rememberTextFieldState(naming.current)
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(field) {
        var first = true
        snapshotFlow { field.text.toString() }.collect { if (!first) onChanged(); first = false }
    }
    Text(WatchlistWords.NAME, style = inter(22.sp, FontWeight.SemiBold), color = WmmColors.TextMuted)
    KeyboardField(field.text.toString(), active = true)
    naming.error?.let {
        Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(WmmIcons.Alert, contentDescription = null, tint = WmmColors.Warn, modifier = Modifier.size(22.dp))
            Text(it, style = inter(22.sp, lineHeight = 31.sp), color = WmmColors.Warn)
        }
    }
    OnScreenKeyboard(field = field, firstKey = firstKey, keySize = 58.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        TvButton(WatchlistWords.CANCEL, onCancel, size = TvButtonSize.Medium)
        TvButton(confirm, { onConfirm(field.text.toString()) }, size = TvButtonSize.Medium, style = TvButtonStyle.Primary, enabled = field.text.isNotBlank() && !naming.busy)
    }
    InitialFocus(firstKey)
}

/** One entry of a card's Hold OK menu. */
internal data class CardMenuEntry(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** A card's Hold OK menu: the title, a line about it ("Movie · 2026") and its entries. */
internal data class CardMenu(val title: String, val line: String?, val entries: List<CardMenuEntry>)

/**
 * Where a card's Hold OK menu opens: one for the session's screens, drawn
 * above them, so any row or grid's card can open it.
 */
@Stable
internal class CardMenuHost {
    var open by mutableStateOf<CardMenu?>(null)
}

/** The session's [CardMenuHost]; null where there is none (a screen on its own in a test), where Hold OK does what it did before. */
@Suppress("ComposeCompositionLocalUsage")
internal val LocalCardMenu = staticCompositionLocalOf<CardMenuHost?> { null }

/**
 * What Hold OK does on [item]'s card: with watchlists, a menu with the
 * title's watchlist entry first (Add to Watchlist, Remove from Watchlist,
 * Save to watchlist…), then Mark watched where Hold OK marked it before
 * ([toggleWatched]); without them, [toggleWatched] at once, as before.
 */
@Composable
internal fun holdFor(item: BrowseItem, toggleWatched: ((BrowseItem) -> Unit)?): (() -> Unit)? {
    val toggle = toggleWatched?.takeIf { item.kind == ItemKind.Movie }?.let { t -> { t(item) } }
    val saving = LocalWatchlistControls.current?.item(item.watchlistTitle(), item.watchlisted)
    val host = LocalCardMenu.current
    if (saving == null || host == null) return toggle
    return remember(item, saving, toggle, host) {
        {
            host.open = CardMenu(
                item.title,
                listOfNotNull(if (item.kind == ItemKind.Show) "TV show" else "Movie", item.year?.toString()).joinToString(" · "),
                listOfNotNull(
                    CardMenuEntry(saving.label, if (saving.on) WmmIcons.BookmarkFilled else WmmIcons.Bookmark, saving.onClick),
                    toggle?.let { CardMenuEntry(if (item.watched) "Mark unwatched" else "Mark watched", WmmIcons.Check, it) },
                ),
            )
        }
    }
}

/** The open card menu, centred over the screen; focus starts on its first entry, and Back closes it. */
@Composable
internal fun CardMenuDialog(host: CardMenuHost) {
    val menu = host.open ?: return
    val first = remember(menu) { FocusRequester() }
    TvDialog(onDismiss = { host.open = null }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(560.dp).background(WmmColors.Surface, RoundedCornerShape(24.dp)).border(1.dp, WmmColors.Line, RoundedCornerShape(24.dp)).padding(28.dp)
                    .semantics { paneTitle = menu.title },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(Modifier.padding(start = 18.dp, bottom = 10.dp)) {
                    Text(menu.title, style = inter(26.sp, FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    menu.line?.let { Text(it, style = inter(22.sp), color = WmmColors.TextMuted) }
                }
                menu.entries.forEachIndexed { i, e ->
                    PanelRow(e.label, null, e.icon, { host.open = null; e.onClick() }, if (i == 0) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
        InitialFocus(first)
    }
}
