package media.watchmy.client.mobile.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.widthIn
import media.watchmy.client.mobile.components.CardAction
import media.watchmy.client.mobile.components.EmptyState
import media.watchmy.client.mobile.components.PressableCard
import media.watchmy.client.mobile.watchlists.NameField
import media.watchmy.client.mobile.watchlists.WatchlistMessageHost
import media.watchmy.client.model.ListShown
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.WatchlistWords
import media.watchmy.client.model.deleteBody
import media.watchmy.client.model.deleteQuestion
import media.watchmy.client.model.watchlistTitle
import media.watchmy.client.presentation.watchlists.WatchlistMessage
import media.watchmy.client.ui.LocalWatchlistControls
import media.watchmy.client.mobile.components.BackTopBar
import media.watchmy.client.mobile.components.BarIconButton
import media.watchmy.client.mobile.components.ErrorState
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.LoadingState
import media.watchmy.client.mobile.components.MessageHost
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.components.PosterCard
import media.watchmy.client.mobile.requests.RequestSheet
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.MediaListEntry
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.Requestable
import media.watchmy.client.model.RequestableState
import media.watchmy.client.model.errorLine
import media.watchmy.client.model.kicker
import media.watchmy.client.model.sourceLine
import media.watchmy.client.model.summaryLine
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.rows.MediaListViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.TabletLayout
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.open
import media.watchmy.client.ui.rememberNotMatchedNotice
import media.watchmy.client.ui.rememberTabletLayout
import media.watchmy.client.ui.ReportShown

/**
 * A list's page, from its row's See all: the list's name,
 * where it comes from, how much of it the library has and why it couldn't
 * update, then every title in rank order: the library's as posters, the
 * missing ones dimmed with Request (through the request sheet) or where
 * their request stands, and imports' unmatched titles as Not found. Its ⋮
 * has Refresh now and Delete list for whoever may change the list.
 *
 * A watchlist's page has the type chips and its sort under its name, no
 * source line or rank numbers, Remove from it in each title's menu (with
 * Undo in the message), and Rename, the switch that keeps watched titles
 * and Delete in its ⋮. [type] is the chip it opens on.
 */
@Composable
fun ListScreen(
    listId: Long,
    onBack: () -> Unit,
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    modifier: Modifier = Modifier,
    type: String? = null,
) {
    val services = LocalAppServices.current
    val layout = rememberTabletLayout()
    val shown = ListShown.of(MediaType.entries.firstOrNull { it.name.equals(type, ignoreCase = true) })
    val vm = viewModel(key = "list-$listId") { MediaListViewModel(services.rows, services.requests, services.library, listId, services.watchlists, shown = shown) }
    ReportShown(vm::setShown)
    val state by vm.state.collectAsStateWithLifecycle()
    val notMatched = rememberNotMatchedNotice()
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var asking by remember { mutableStateOf<Requestable?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val controls = LocalWatchlistControls.current
    // On a watchlist's page, a title's menu item takes it off this watchlist.
    val pageControls = remember(controls, state.list, state.entries) { controls.forPage(state, vm::remove) }
    LaunchedEffect(vm) { vm.events.collect { if (it == MediaListViewModel.Event.Deleted) onBack() } }
    val side = if (layout.isTablet) TabletLayout.margin else 16.dp

    Box(modifier.fillMaxSize().background(WmmColors.Ground)) {
        Box(
            Modifier.fillMaxWidth().height(260.dp).background(
                Brush.verticalGradient(0f to Color(0xFF2B2F4A), 0.5f to Color(0xFF151829), 1f to WmmColors.Ground),
            ),
        )
        Column(Modifier.fillMaxSize()) {
            BackTopBar(null, onBack, overArt = true) {
                val list = state.list
                if (list?.isWatchlist == true && controls != null) {
                    WatchlistMenu(list, vm::startRename, vm::setRemoveWatched, onDelete = { confirmDelete = true })
                } else if (list?.canEdit == true) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        BarIconButton(PhoneIcons.MoreVert, "List options", { menu = true }, overArt = true)
                        DropdownMenu(menu, { menu = false }, containerColor = WmmColors.Surface3) {
                            if (list.refreshable) DropdownMenuItem(text = { Text("Refresh now") }, leadingIcon = { Icon(PhoneIcons.Refresh, null) }, onClick = { menu = false; vm.refreshList() })
                            DropdownMenuItem(text = { Text("Delete list…") }, leadingIcon = { Icon(WmmIcons.Trash, null) }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val page = state.page) {
                    Load.Loading -> LoadingState()
                    is Load.Failed -> ErrorState(page.message, vm::retry, title = "Couldn't load this list")
                    is Load.Ready -> CompositionLocalProvider(LocalWatchlistControls provides pageControls) { LazyVerticalGrid(
                        columns = if (layout.isTablet) GridCells.Adaptive(TabletLayout.posterMinimum) else GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (layout.isTablet) 16.dp else 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        val list = state.shownList ?: page.data.list
                        val watchlist = list.isWatchlist
                        item(key = "head", span = { GridItemSpan(maxLineSpan) }, contentType = "head") {
                            Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(list.kicker().uppercase(), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.96.sp, color = WmmColors.Accent))
                                Text(list.name, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 31.sp, letterSpacing = (-0.56).sp, color = WmmColors.Text), modifier = Modifier.semantics { heading() })
                                list.sourceLine()?.let { line ->
                                    // The list's page on TMDB or mdblist.com, when it has one, opens in the browser.
                                    val url = list.sourceUrl
                                    Row(
                                        Modifier.padding(top = 2.dp).then(if (url != null) Modifier.clickable(onClickLabel = "Open the list on its site") { runCatching { uri.openUri(url) } } else Modifier),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(line, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextSoft))
                                        if (url != null) Icon(PhoneIcons.External, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(14.dp))
                                    }
                                }
                                if (watchlist) {
                                    WatchlistControlsRow(list.summaryLine(), state.sort, vm::sortBy)
                                    WatchlistChips(state.shown, vm::show)
                                } else {
                                    Text(list.summaryLine(), style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
                                }
                                list.errorLine()?.let { line -> FailedNotice(line, onRetry = vm::refreshList.takeIf { list.canEdit && list.refreshable }, busy = state.refreshing) }
                            }
                        }
                        if (watchlist && state.entries.isEmpty()) {
                            item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                                EmptyState(WatchlistWords.EMPTY_TITLE, WatchlistWords.EMPTY_BODY, Modifier.padding(top = 48.dp))
                            }
                        }
                        items(state.entries, key = { it.rank }, contentType = { if (it.item != null) "have" else "missing" }) { entry ->
                            val item = entry.item
                            if (item != null) {
                                Box {
                                    PosterCard(item.copy(isNew = false), services.library.imageUrl(item.posterPath, ImageSize.W342), item.year?.toString(), { item.open(onOpenShow, onOpenMovie, onPlay, notMatched) }, { vm.setWatched(item, it) }, titleSize = 13.sp, subtitleSize = 11.sp)
                                    if (!watchlist) RankBadge(entry.rank, Modifier.align(Alignment.TopStart))
                                }
                            } else {
                                MissingCard(entry, services.library.imageUrl(entry.posterPath, ImageSize.W342), ranked = !watchlist, onRequest = { entry.requestSubject()?.let { asking = it } })
                            }
                        }
                    } }
                }
                if (state.isWatchlist) {
                    WatchlistMessageHost(state.message?.let { WatchlistMessage(it, state.undoable) }, vm::undo, vm::messageShown, Modifier.align(Alignment.BottomCenter))
                } else {
                    MessageHost(state.message, vm::messageShown, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    asking?.let { subject ->
        RequestSheet(subject, state.options, onSent = { message -> asking = null; vm.requested(message) }, onDismiss = { asking = null })
    }
    state.naming?.let { naming ->
        Dialog(onDismissRequest = vm::cancelRename) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().background(WmmColors.Surface2, RoundedCornerShape(24.dp)).padding(20.dp)) {
                Text(WatchlistWords.RENAME, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = WmmColors.Text), modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
                NameField(naming, vm::cancelRename, vm::rename, vm::nameChanged, confirm = WatchlistWords.SAVE)
            }
        }
    }
    if (confirmDelete) {
        val list = state.list
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = WmmColors.Surface2,
            title = { Text(list?.deleteQuestion().orEmpty()) },
            text = { Text(list?.deleteBody().orEmpty()) },
            confirmButton = { GoldTextButton("Delete", { confirmDelete = false; vm.delete() }) },
            dismissButton = { GoldTextButton("Cancel", { confirmDelete = false }, color = WmmColors.TextSoft) },
        )
    }
}

/** The list couldn't be fetched (`.warn`): why, as it was shown, and Try again for whoever may refresh it. */
@Composable
private fun FailedNotice(line: String, onRetry: (() -> Unit)?, busy: Boolean) {
    Row(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .background(WmmColors.Warn.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .border(1.dp, WmmColors.Warn.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = if (onRetry != null) 6.dp else 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(WmmIcons.Warning, contentDescription = null, tint = WmmColors.Warn, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(line, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.Text))
            if (onRetry != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRetry, enabled = !busy) { Text(if (busy) "Trying…" else "Try again", color = WmmColors.Accent, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/** The entry's place in the list, on its poster (`.rank`). */
@Composable
private fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.padding(6.dp).heightIn(min = 22.dp).background(WmmColors.Ground.copy(alpha = 0.85f), RoundedCornerShape(11.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(rank.toString(), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = WmmColors.Text))
    }
}

/**
 * A title the library doesn't have: TMDB's poster dimmed, with Request, or
 * where its request stands ("Requested", "Downloading", "Not in library"
 * without requests); an import's title TMDB couldn't find is a dashed Not
 * found box.
 */
@Composable
private fun MissingCard(entry: MediaListEntry, imageUrl: String?, onRequest: () -> Unit, ranked: Boolean = true) {
    val muted = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = WmmColors.TextMuted)
    // Its menu has the title's watchlist entry: Remove on a watchlist's page, Add to Watchlist on another list's.
    val saving = LocalWatchlistControls.current?.item(entry.watchlistTitle(), entry.watchlisted)
    val actions = remember(saving) { listOfNotNull(saving?.let { CardAction(it.label, it.onClick) }) }
    PressableCard(onClick = { if (entry.requestable) onRequest() }, actions = actions, modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            if (!entry.found) {
                Column(
                    Modifier.fillMaxSize().background(Color(0xFF141416), RoundedCornerShape(8.dp)).border(1.5.dp, Color(0xFF5A5A5F), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(WmmIcons.NotFound, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(26.dp))
                    Text("Not found", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = WmmColors.TextSoft))
                    Text(entry.title, style = TextStyle(fontFamily = Inter, fontSize = 11.sp, lineHeight = 15.sp, color = WmmColors.TextMuted), textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Artwork(
                    imageUrl, seed = entry.title, contentDescription = null,
                    modifier = Modifier.fillMaxSize().alpha(0.32f),
                    shape = RoundedCornerShape(8.dp),
                )
                MissingState(entry, onRequest)
            }
            if (ranked) RankBadge(entry.rank, Modifier.align(Alignment.TopStart))
        }
        CardCaptionMuted(entry.title, if (ranked) entry.missingLine else WatchlistWords.NOT_IN_LIBRARY, muted)
    }
}

@Composable
private fun BoxScope.MissingState(entry: MediaListEntry, onRequest: () -> Unit) {
    if (entry.requestable) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(6.dp)
                .fillMaxWidth()
                .height(40.dp)
                .background(WmmColors.Brand, RoundedCornerShape(20.dp))
                .border(1.dp, WmmColors.BrandLine, RoundedCornerShape(20.dp))
                .clickable(onClickLabel = "Request ${entry.title}", onClick = onRequest)
                .semantics { contentDescription = "Request ${entry.title}" },
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(WmmIcons.Plus, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Text("Request", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White))
        }
        return
    }
    val status = entry.missingStatus ?: return
    val gold = entry.requestState == RequestableState.Downloading
    Box(
        Modifier.align(Alignment.BottomCenter).padding(6.dp).fillMaxWidth().heightIn(min = 40.dp).background(WmmColors.Surface2.copy(alpha = 0.92f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(status, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (gold) WmmColors.Accent else if (entry.requestState == null) WmmColors.TextMuted else WmmColors.TextSoft), textAlign = TextAlign.Center)
    }
}

@Composable
private fun CardCaptionMuted(title: String, line: String, style: TextStyle) {
    Column {
        Text(title, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(line, style = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = WmmColors.TextMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
