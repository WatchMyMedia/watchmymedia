package media.watchmy.client.mobile.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.mobile.components.BarIconButton
import media.watchmy.client.mobile.components.GoldChip
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.watchlists.CheckBox
import media.watchmy.client.model.ListShown
import media.watchmy.client.model.ListSort
import media.watchmy.client.model.MediaList
import media.watchmy.client.model.MediaListEntry
import media.watchmy.client.model.WatchlistWords
import media.watchmy.client.model.watchlistTitle
import media.watchmy.client.presentation.rows.MediaListViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WatchlistControls
import media.watchmy.client.ui.WatchlistMenuItem
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/**
 * A watchlist's ⋮: Rename, the switch that keeps watched titles (with what
 * it does under it), and Delete, apart, in the warning colour.
 */
@Composable
internal fun WatchlistMenu(list: MediaList, onRename: () -> Unit, onRemoveWatched: (Boolean) -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarIconButton(PhoneIcons.MoreVert, "More", { open = true }, overArt = true)
        DropdownMenu(open, { open = false }, containerColor = WmmColors.Surface3, modifier = Modifier.widthIn(max = 320.dp)) {
            DropdownMenuItem(text = { Text(WatchlistWords.RENAME) }, leadingIcon = { Icon(WmmIcons.Pencil, null) }, onClick = { open = false; onRename() })
            DropdownMenuItem(
                text = {
                    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(WatchlistWords.SWITCH)
                        Text(WatchlistWords.SWITCH_NOTE, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 17.sp, color = WmmColors.TextMuted))
                    }
                },
                leadingIcon = { CheckBox(list.removeWatched) },
                // Stays open: the check is the answer.
                onClick = { onRemoveWatched(!list.removeWatched) },
                modifier = Modifier.semantics {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(list.removeWatched)
                },
            )
            HorizontalDivider(color = WmmColors.Line)
            DropdownMenuItem(
                text = { Text(WatchlistWords.DELETE, color = WmmColors.Warn) },
                leadingIcon = { Icon(WmmIcons.Trash, null, tint = WmmColors.Warn) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

/** "12 titles · 9 in your library", and the sort (Date added ▾) at the row's end. */
@Composable
internal fun WatchlistControlsRow(line: String, sort: ListSort, onSort: (ListSort) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(line, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted), modifier = Modifier.weight(1f))
        var open by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "Sort: ${sort.label}" }) {
                Icon(WmmIcons.Sort, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(16.dp))
                Text(sort.label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = WmmColors.Accent), modifier = Modifier.padding(horizontal = 6.dp))
                Icon(WmmIcons.ChevronDown, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(open, { open = false }, containerColor = WmmColors.Surface3) {
                ListSort.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.label) },
                        trailingIcon = { if (s == sort) Icon(WmmIcons.Check, null, tint = WmmColors.Accent) },
                        onClick = { open = false; onSort(s) },
                    )
                }
            }
        }
    }
}

/** All · Movies · TV shows. */
@Composable
internal fun WatchlistChips(shown: ListShown, onShow: (ListShown) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ListShown.entries) { chip -> GoldChip(chip.label, selected = chip == shown, onClick = { onShow(chip) }) }
    }
}

/**
 * The session's controls as a watchlist's page uses them: each title's
 * menu item takes it off this watchlist, at once with Undo. Other lists'
 * pages keep the session's own.
 */
internal fun WatchlistControls?.forPage(state: MediaListViewModel.State, remove: (MediaListEntry) -> Unit): WatchlistControls? {
    val list = state.list?.takeIf { it.isWatchlist } ?: return this
    val controls = this ?: return null
    return controls.withItem { title, _ ->
        state.entries.firstOrNull { it.watchlistTitle()?.key == title.key }?.let { entry -> WatchlistMenuItem(WatchlistWords.removeFrom(list.name), on = true) { remove(entry) } }
    }
}
