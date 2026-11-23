package media.watchmy.client.tv.watchlists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.ListShown
import media.watchmy.client.model.ListSort
import media.watchmy.client.model.MediaList
import media.watchmy.client.model.Watchlist
import media.watchmy.client.model.WatchlistWords
import media.watchmy.client.model.plural
import media.watchmy.client.model.summaryLine
import media.watchmy.client.presentation.watchlists.Naming
import media.watchmy.client.presentation.watchlists.WatchlistsViewModel
import media.watchmy.client.tv.components.ContentEnd
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.ErrorState
import media.watchmy.client.tv.components.FocusGrowth
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.Kicker
import media.watchmy.client.tv.components.LoadingState
import media.watchmy.client.tv.components.Notice
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvChip
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.tv.settings.Toggle
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.WmmIcons

/**
 * A watchlist's page's top on the TV: the kicker, its name and counts, and
 * beside them Rename, Delete and the switch that keeps watched titles (with
 * what it does under it); then the type chips, the hint for the focused
 * card and the sort. Focus memory keys start with `wl:`.
 */
@Composable
internal fun WatchlistHeader(
    list: MediaList,
    shown: ListShown,
    sort: ListSort,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onRemoveWatched: (Boolean) -> Unit,
    onShow: (ListShown) -> Unit,
    onSort: () -> Unit,
    hint: String?,
) {
    val memory = rememberFocusMemory()
    Column(Modifier.padding(start = ContentStart, end = ContentEnd, top = 56.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Kicker(WatchlistWords.KICKER, fontSize = 22.sp)
                Text(list.name, style = inter(64.sp, FontWeight.ExtraBold, lineHeight = 80.sp, tracking = -0.02f), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (list.entries > 0) Text(list.summaryLine(), style = inter(22.sp), color = WmmColors.TextMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                TvButton(WatchlistWords.RENAME, onRename, Modifier.focusMemory(memory, "wl:rename"), size = TvButtonSize.Medium, icon = WmmIcons.Pencil)
                TvButton(WatchlistWords.DELETE, onDelete, Modifier.focusMemory(memory, "wl:delete"), size = TvButtonSize.Medium, icon = WmmIcons.Trash)
                Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchButton(WatchlistWords.SWITCH, list.removeWatched, { onRemoveWatched(!list.removeWatched) }, Modifier.focusMemory(memory, "wl:switch"))
                    Text(WatchlistWords.SWITCH_NOTE, style = inter(22.sp, lineHeight = 31.sp), color = WmmColors.TextMuted)
                }
            }
        }
        if (list.entries > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ListShown.entries.forEach { chip -> TvChip(chip.label, chip == shown, { onShow(chip) }, Modifier.focusMemory(memory, "wl:chip:${chip.name}")) }
                Box(Modifier.weight(1f))
                if (hint != null) {
                    Text(hint, style = inter(22.sp), color = WmmColors.TextFaint, maxLines = 1)
                    Text("·", style = inter(22.sp), color = WmmColors.TextFaint)
                    Text("Hold OK", style = inter(22.sp, FontWeight.Bold), color = WmmColors.TextSoft, modifier = Modifier.border(1.dp, WmmColors.Line2, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                    Text(WatchlistWords.removeFrom(list.name), style = inter(22.sp), color = WmmColors.TextFaint, maxLines = 1)
                }
                Text("Sort", style = inter(22.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(start = 12.dp))
                TvChip(sort.label, selected = false, onClick = onSort, modifier = Modifier.focusMemory(memory, "wl:sort"), trailingIcon = WmmIcons.ChevronDown)
            }
        }
    }
}

/** A button that is a switch (`role=switch`): its words and the gold toggle. */
@Composable
private fun SwitchButton(text: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableBox(
        onClick = onClick,
        modifier = modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(on)
        },
        cornerRadius = 12.dp,
        focusedScale = FocusGrowth.BUTTON,
    ) { focused ->
        Row(
            Modifier.height(TvButtonSize.Medium.height).background(Color.White.copy(alpha = if (focused) 0.16f else 0.10f), RoundedCornerShape(12.dp)).padding(start = 22.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text, style = inter(22.sp, FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1)
            Toggle(on)
        }
    }
}

/** Rename on the TV: the panel from the right with the Name field and the keyboard, as New watchlist's. */
@Composable
internal fun RenamePanel(naming: Naming, onCancel: () -> Unit, onSave: (String) -> Unit, onChanged: () -> Unit) {
    TvDialog(onDismiss = onCancel) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.width(800.dp).fillMaxHeight().background(WmmColors.Surface).border(1.dp, WmmColors.Line)
                    .padding(start = 44.dp, end = 44.dp, top = 64.dp, bottom = 44.dp)
                    .semantics { paneTitle = WatchlistWords.RENAME },
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(WatchlistWords.RENAME, style = inter(28.sp, FontWeight.SemiBold), color = WmmColors.Text, modifier = Modifier.semantics { heading() })
                NamingTv(naming, onCancel, onSave, onChanged, confirm = WatchlistWords.SAVE)
            }
        }
    }
}

/**
 * Watchlists on the TV, from Servers: New watchlist first, then the
 * person's watchlists in the order made, as tiles of their first posters,
 * name, counts and Keeps watched titles. Focus opens on the first
 * watchlist, or on New watchlist when there are none.
 */
@Composable
fun WatchlistsTv(onNavigate: (Any) -> Unit, onOpenList: (Long) -> Unit, modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    val vm = viewModel(key = "tv-watchlists") { WatchlistsViewModel(services.watchlists) }
    val state by vm.state.collectAsStateWithLifecycle()
    val memory = rememberFocusMemory()
    LaunchedEffect(vm) { vm.events.collect { if (it is WatchlistsViewModel.Event.Made) onOpenList(it.watchlist.id) } }

    TvScaffold(null, onNavigate, modifier, sectionRoot = false) {
        val lists = state.watchlists
        when {
            lists == null && state.failed != null -> ErrorState(state.failed.orEmpty(), vm::retry, title = "Couldn't load your watchlists")
            lists == null -> LoadingState()
            else -> {
                Column(Modifier.padding(start = ContentStart, top = 56.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(WatchlistWords.WATCHLISTS, style = inter(64.sp, FontWeight.ExtraBold, tracking = -0.02f), color = WmmColors.Text, modifier = Modifier.semantics { heading() })
                    if (lists.isNotEmpty()) Text(plural(lists.size, "watchlist"), style = inter(22.sp), color = WmmColors.TextMuted)
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize().padding(top = if (lists.isEmpty()) 164.dp else 196.dp),
                    contentPadding = PaddingValues(start = ContentStart, end = ContentEnd, top = 12.dp, bottom = 60.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    item(key = "new") { NewTile(vm::startNaming, Modifier.focusMemory(memory, "new")) }
                    items(lists, key = { it.id }) { w -> WatchlistTile(w, services.library::imageUrl, { onOpenList(w.id) }, Modifier.focusMemory(memory, "wl:${w.id}")) }
                    if (lists.isEmpty()) {
                        item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(WatchlistWords.NONE_TITLE, style = inter(28.sp, FontWeight.SemiBold), color = WmmColors.Text)
                                Text(WatchlistWords.NONE_BODY, style = inter(22.sp), color = WmmColors.TextMuted)
                            }
                        }
                    }
                }
                LaunchedEffect(lists.size) {
                    androidx.compose.runtime.withFrameNanos { }
                    memory.restore(fallback = lists.firstOrNull()?.let { "wl:${it.id}" } ?: "new")
                }
            }
        }
        Notice(state.message, vm::messageShown)
    }
    state.naming?.let { naming ->
        TvDialog(onDismiss = vm::cancelNaming) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.CenterEnd) {
                Column(
                    Modifier.width(800.dp).fillMaxHeight().background(WmmColors.Surface).border(1.dp, WmmColors.Line)
                        .padding(start = 44.dp, end = 44.dp, top = 64.dp, bottom = 44.dp)
                        .semantics { paneTitle = WatchlistWords.NEW_BUTTON },
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text(WatchlistWords.NEW_BUTTON, style = inter(28.sp, FontWeight.SemiBold), color = WmmColors.Text, modifier = Modifier.semantics { heading() })
                    NamingTv(naming, vm::cancelNaming, vm::create, vm::nameChanged)
                }
            }
        }
    }
}

/** New watchlist's tile: a bookmark with a plus. */
@Composable
private fun NewTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableBox(onClick = onClick, modifier = modifier.fillMaxWidth(), cornerRadius = 16.dp, focusedScale = FocusGrowth.BUTTON, role = Role.Button) { focused ->
        Column(
            // About as tall as a watchlist's tile beside it: four posters and two lines.
            Modifier.fillMaxWidth().aspectRatio(1.73f).background(if (focused) WmmColors.Surface2 else WmmColors.Surface, RoundedCornerShape(16.dp))
                .border(2.dp, WmmColors.Line2, RoundedCornerShape(16.dp)),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(WmmIcons.BookmarkPlus, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(44.dp))
            Text(WatchlistWords.NEW_BUTTON, style = inter(22.sp, FontWeight.SemiBold), color = WmmColors.Text)
        }
    }
}

/** A watchlist's tile (`.tile`): its first four posters, its name, Keeps watched titles, and its counts. */
@Composable
private fun WatchlistTile(watchlist: Watchlist, imageUrl: (String?, ImageSize) -> String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spoken = listOfNotNull(watchlist.name, WatchlistWords.titles(watchlist.titles), "${watchlist.inLibrary} in your library", WatchlistWords.KEEPS.lowercase().takeIf { !watchlist.removeWatched }).joinToString(", ")
    FocusableBox(onClick = onClick, modifier = modifier.fillMaxWidth(), cornerRadius = 16.dp, focusedScale = FocusGrowth.BUTTON, role = Role.Button) { focused ->
        Column(
            Modifier.fillMaxWidth().background(WmmColors.Surface, RoundedCornerShape(16.dp)).border(1.dp, WmmColors.Line, RoundedCornerShape(16.dp)).padding(16.dp)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    this.focused = focused
                },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0 until 4).forEach { i ->
                    Artwork(imageUrl(watchlist.posters.getOrNull(i), ImageSize.W185), seed = "${watchlist.name}$i", contentDescription = null, modifier = Modifier.weight(1f).aspectRatio(2f / 3f), shape = RoundedCornerShape(8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(watchlist.name, style = inter(22.sp, FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).widthIn(max = 260.dp))
                if (!watchlist.removeWatched) Text(WatchlistWords.KEEPS, style = inter(22.sp, FontWeight.SemiBold), color = WmmColors.Accent, maxLines = 1)
            }
            Text(WatchlistWords.line(watchlist.titles, watchlist.inLibrary), style = inter(22.sp), color = WmmColors.TextMuted)
        }
    }
}
