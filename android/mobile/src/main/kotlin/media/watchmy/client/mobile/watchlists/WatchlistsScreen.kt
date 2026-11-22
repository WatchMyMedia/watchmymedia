package media.watchmy.client.mobile.watchlists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.BackTopBar
import media.watchmy.client.mobile.components.EmptyState
import media.watchmy.client.mobile.components.ErrorState
import media.watchmy.client.mobile.components.LoadingState
import media.watchmy.client.mobile.components.MessageHost
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.Watchlist
import media.watchmy.client.model.WatchlistWords
import media.watchmy.client.presentation.watchlists.WatchlistsViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.TabletLayout
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.rememberTabletLayout

/**
 * Watchlists, from Servers: the person's
 * watchlists in the order made, each with its name, counts, whether it
 * keeps watched titles and its first posters, and New watchlist, which
 * names one and opens it. A list on the phone, a grid of the same cards on
 * a tablet, where New watchlist is in the header.
 */
@Composable
fun WatchlistsScreen(onBack: () -> Unit, onOpenList: (Long) -> Unit, modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    val tablet = rememberTabletLayout().isTablet
    val vm = viewModel { WatchlistsViewModel(services.watchlists) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.events.collect { if (it is WatchlistsViewModel.Event.Made) onOpenList(it.watchlist.id) } }
    val side = if (tablet) TabletLayout.margin else 16.dp

    Box(modifier.fillMaxSize().background(WmmColors.Ground)) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(WatchlistWords.WATCHLISTS, onBack) {
                if (tablet && !state.watchlists.isNullOrEmpty()) NewButton(vm::startNaming, Modifier.padding(end = 8.dp))
            }
            val lists = state.watchlists
            when {
                lists == null && state.failed != null -> ErrorState(state.failed.orEmpty(), vm::retry, title = "Couldn't load your watchlists")
                lists == null -> LoadingState()
                lists.isEmpty() -> EmptyState(WatchlistWords.NONE_TITLE, WatchlistWords.NONE_BODY, action = WatchlistWords.NEW_BUTTON, onAction = vm::startNaming)
                else -> LazyVerticalGrid(
                    columns = if (tablet) GridCells.Adaptive(320.dp) else GridCells.Fixed(1),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(lists, key = { it.id }) { w -> WatchlistCard(w, services.library::imageUrl) { onOpenList(w.id) } }
                    if (!tablet) item(key = "new", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(top = 8.dp)) { NewButton(vm::startNaming) } }
                }
            }
        }
        MessageHost(state.message, vm::messageShown, Modifier.align(Alignment.BottomCenter))
    }

    state.naming?.let { naming ->
        Dialog(onDismissRequest = vm::cancelNaming) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().background(WmmColors.Surface2, RoundedCornerShape(24.dp)).padding(20.dp)) {
                Text(WatchlistWords.NEW_BUTTON, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = WmmColors.Text), modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
                NameField(naming, vm::cancelNaming, vm::create, vm::nameChanged)
            }
        }
    }
}

@Composable
private fun NewButton(onClick: () -> Unit, modifier: Modifier = Modifier) = TonalButton(WatchlistWords.NEW_BUTTON, onClick, modifier, icon = WmmIcons.Plus)

/** A watchlist (`.wl`): name and chevron, its counts, Keeps watched titles, and its first four posters. */
@Composable
private fun WatchlistCard(watchlist: Watchlist, imageUrl: (String?, ImageSize) -> String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val spoken = listOfNotNull(watchlist.name, WatchlistWords.line(watchlist.titles, watchlist.inLibrary), WatchlistWords.KEEPS.lowercase().takeIf { !watchlist.removeWatched }).joinToString(", ")
    Column(
        Modifier.fillMaxWidth().background(WmmColors.Surface, shape).border(1.dp, WmmColors.Line, shape).clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(watchlist.name, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = WmmColors.Text), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(20.dp))
        }
        WatchlistLines(watchlist)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Four places, with the posters it has and the gradient where it has fewer.
            (0 until 4).forEach { i ->
                val path = watchlist.posters.getOrNull(i)
                Artwork(imageUrl(path, ImageSize.W185), seed = "${watchlist.name}$i", contentDescription = null, modifier = Modifier.weight(1f).aspectRatio(2f / 3f), shape = RoundedCornerShape(6.dp))
            }
        }
    }
}
