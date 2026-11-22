package media.watchmy.client.tv.rows

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.ListShown
import media.watchmy.client.model.ListSort
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.WatchlistWords
import media.watchmy.client.model.deleteBody
import media.watchmy.client.model.deleteQuestion
import media.watchmy.client.model.gridKey
import media.watchmy.client.model.watchlistTitle
import media.watchmy.client.tv.components.OptionsDialog
import media.watchmy.client.tv.settings.ConfirmDialog
import media.watchmy.client.tv.settings.ConfirmPart
import media.watchmy.client.tv.settings.ConfirmWords
import media.watchmy.client.tv.watchlists.CardMenu
import media.watchmy.client.tv.watchlists.CardMenuEntry
import media.watchmy.client.tv.watchlists.LocalCardMenu
import media.watchmy.client.tv.watchlists.RenamePanel
import media.watchmy.client.tv.watchlists.WatchlistHeader
import media.watchmy.client.ui.LocalWatchlistControls
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.ItemKind
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
import media.watchmy.client.tv.components.ArtBadge
import media.watchmy.client.tv.components.ArtProgress
import media.watchmy.client.tv.components.ArtWatched
import media.watchmy.client.tv.components.Backdrop
import media.watchmy.client.tv.components.ContentEnd
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.ErrorState
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.KeepInViewSpec
import media.watchmy.client.tv.components.Kicker
import media.watchmy.client.tv.components.LoadingState
import media.watchmy.client.tv.components.MediaCard
import media.watchmy.client.tv.components.Notice
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.tv.requests.RequestDialog
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.open
import media.watchmy.client.ui.rememberNotMatchedNotice
import media.watchmy.client.ui.ReportShown

/**
 * A list's page on the TV, from its row's See
 * all card: the kicker, name, where it comes from and how much the library
 * has (or why it couldn't update), and every title in rank order in an
 * 8-column grid. OK opens a title the library has; on a missing one it
 * opens the request dialog when the person may ask for it; Hold OK marks a
 * movie watched, or opens the card's menu where the server has watchlists.
 * The hint on the right says what OK does on the focused card.
 *
 * A watchlist's page has Rename, Delete and the switch that keeps watched
 * titles at its top, the type chips and the sort over the grid, no rank
 * numbers, and Remove from it first in each card's Hold OK menu (Request
 * beside it on a missing title). [type] is the chip it opens on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListPageTv(
    listId: Long,
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
    type: String? = null,
) {
    val services = LocalAppServices.current
    val shown = ListShown.of(MediaType.entries.firstOrNull { it.name.equals(type, ignoreCase = true) })
    val vm = viewModel(key = "tv-list-$listId") { MediaListViewModel(services.rows, services.requests, services.library, listId, services.watchlists, messages = false, shown = shown) }
    ReportShown(vm::setShown)
    val state by vm.state.collectAsStateWithLifecycle()
    val notMatched = rememberNotMatchedNotice()
    val memory = rememberFocusMemory()
    var asking by remember { mutableStateOf<Requestable?>(null) }
    var focusedEntry by remember { mutableStateOf<MediaListEntry?>(null) }
    var sorting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val menus = LocalCardMenu.current
    val controls = LocalWatchlistControls.current
    LaunchedEffect(vm) { vm.events.collect { if (it == MediaListViewModel.Event.Deleted) onNavigate(RailSection.Home.route) } }

    TvScaffold(null, onNavigate, modifier, sectionRoot = false) {
        Backdrop(
            url = null,
            seed = state.list?.name ?: "list",
            modifier = Modifier.offset(x = 560.dp).width(1360.dp).height(420.dp),
            leftFade = listOf(0f to 1f, 0.3f to 0.6f, 0.7f to 0f),
            bottomFadeFrom = 0.45f,
            glowX = 0.7f,
            glowY = 0.2f,
        )
        when (val page = state.page) {
            Load.Loading -> LoadingState()
            is Load.Failed -> ErrorState(page.message, vm::retry, title = "Couldn't load this list")
            is Load.Ready -> {
                val list = state.shownList ?: page.data.list
                val watchlist = list.isWatchlist && controls != null
                // What Hold OK opens on a card: Remove from this watchlist first; elsewhere, the title's watchlist entry.
                val hold: (MediaListEntry) -> (() -> Unit)? = { entry ->
                    val saving = if (watchlist) {
                        entry.watchlistTitle()?.let { CardMenuEntry(WatchlistWords.removeFrom(list.name), WmmIcons.BookmarkFilled) { vm.remove(entry) } }
                    } else {
                        controls?.item(entry.watchlistTitle(), entry.watchlisted)?.let { CardMenuEntry(it.label, if (it.on) WmmIcons.BookmarkFilled else WmmIcons.Bookmark, it.onClick) }
                    }
                    val item = entry.item
                    val more = when {
                        item != null && item.kind == ItemKind.Movie -> CardMenuEntry(if (item.watched) "Mark unwatched" else "Mark watched", WmmIcons.Check) { vm.setWatched(item, !item.watched) }
                        item == null && entry.requestable && state.options.canRequest -> CardMenuEntry("Request", WmmIcons.Plus) { entry.requestSubject()?.let { asking = it } }
                        else -> null
                    }
                    when {
                        saving != null && menus != null -> ({ menus.open = CardMenu(entry.title, entry.menuLine(), listOfNotNull(saving, more)) })
                        item != null && item.kind == ItemKind.Movie -> ({ vm.setWatched(item, !item.watched) })
                        else -> null
                    }
                }
                if (watchlist) WatchlistHeader(list, state.shown, state.sort, vm::startRename, { confirmDelete = true }, vm::setRemoveWatched, vm::show, { sorting = true }, focusedEntry?.okHint(state.options.canRequest))
                if (!watchlist) Row(
                    Modifier.padding(start = ContentStart, end = ContentEnd, top = 56.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Kicker(list.kicker(), fontSize = 19.sp)
                        Text(list.name, style = inter(56.sp, FontWeight.ExtraBold, lineHeight = 59.sp, tracking = -0.02f), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        list.sourceLine()?.let { Text(it, style = inter(21.sp), color = WmmColors.TextSoft) }
                        Text(list.summaryLine(), style = inter(20.sp), color = WmmColors.TextMuted)
                    }
                    val failed = list.errorLine()
                    if (failed != null) {
                        Row(
                            Modifier
                                .width(620.dp)
                                .background(WmmColors.Warn.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                                .border(1.dp, WmmColors.Warn.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                .padding(horizontal = 20.dp, vertical = 18.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(WmmIcons.Warning, contentDescription = null, tint = WmmColors.Warn, modifier = Modifier.size(26.dp))
                            Text(failed, style = inter(19.sp, lineHeight = 27.sp), color = WmmColors.Text)
                        }
                    } else {
                        val hint = focusedEntry?.okHint(state.options.canRequest)
                        if (hint != null) Text(hint, style = inter(19.sp), color = WmmColors.TextFaint, maxLines = 1)
                    }
                }
                val density = LocalDensity.current
                val spec = remember(density) { with(density) { KeepInViewSpec(leading = 30.dp.toPx(), trailing = 60.dp.toPx()) } }
                CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(8),
                        modifier = Modifier.fillMaxSize().padding(top = if (watchlist) 380.dp else 290.dp).focusRestorer(),
                        contentPadding = PaddingValues(start = ContentStart, end = ContentEnd, top = 10.dp, bottom = 60.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        items(state.entries, key = { it.gridKey(watchlist) }) { entry ->
                            val key = "entry:${entry.gridKey(watchlist)}"
                            val watch = Modifier.focusMemory(memory, key).onFocusChanged { if (it.isFocused) focusedEntry = entry }
                            val item = entry.item
                            if (item != null) {
                                MediaCard(
                                    title = item.title,
                                    subtitle = item.year?.toString(),
                                    imageUrl = services.library.imageUrl(item.posterPath, ImageSize.W342),
                                    artWidth = 190.dp,
                                    artHeight = 285.dp,
                                    onClick = { item.open(onOpenShow, onOpenMovie, onPlay, notMatched) },
                                    onLongClick = hold(entry),
                                    focusedScale = 1.08f,
                                    modifier = watch,
                                ) {
                                    if (!watchlist) RankBadge(entry.rank)
                                    ArtWatched(item.watched)
                                    ArtProgress(item.progress)
                                }
                            } else {
                                MissingCardTv(
                                    entry,
                                    services.library.imageUrl(entry.posterPath, ImageSize.W342),
                                    onClick = { if (entry.requestable && state.options.canRequest) entry.requestSubject()?.let { asking = it } },
                                    modifier = watch,
                                    onLongClick = hold(entry),
                                    ranked = !watchlist,
                                )
                            }
                        }
                        if (state.entries.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            if (watchlist) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(WatchlistWords.EMPTY_TITLE, style = inter(30.sp, FontWeight.SemiBold), color = WmmColors.Text)
                                    Text(WatchlistWords.EMPTY_BODY, style = inter(21.sp), color = WmmColors.TextMuted)
                                }
                            } else {
                                Text("Nothing on this list yet.", style = inter(22.sp), color = WmmColors.TextMuted)
                            }
                        }
                    }
                }
                LaunchedEffect(state.entries.size) {
                    withFrameNanos { }
                    memory.restore(fallback = state.entries.firstOrNull()?.let { "entry:${it.gridKey(watchlist)}" } ?: "wl:rename".takeIf { watchlist })
                }
            }
        }
        Notice(state.message, vm::messageShown)
    }

    asking?.let { subject ->
        RequestDialog(subject, state.options, onSent = { message -> asking = null; vm.requested(message) }, onDismiss = { asking = null })
    }
    if (sorting) {
        OptionsDialog("Sort", ListSort.entries, state.sort, { it.label }, onSelect = { vm.sortBy(it); sorting = false }, onDismiss = { sorting = false })
    }
    state.naming?.let { naming -> RenamePanel(naming, vm::cancelRename, vm::rename, vm::nameChanged) }
    val list = state.list
    if (confirmDelete && list != null) {
        ConfirmDialog(
            ConfirmWords(list.deleteQuestion(), WatchlistWords.CANCEL, WatchlistWords.DELETE, "Deleting…"),
            working = state.deleting,
            failure = null,
            onKeep = { confirmDelete = false },
            onGo = vm::delete,
        ) { ConfirmPart(null, list.deleteBody()) }
    }
}

/** What OK does on the focused card, for the hint over the grid. */
private fun MediaListEntry.okHint(canRequest: Boolean): String? = when {
    item == null && requestable && canRequest -> "OK requests it"
    item != null -> "OK opens the ${if (item?.kind == ItemKind.Show) "show" else "movie"}"
    else -> null
}

/** The line under a card menu's title: "Movie · 2026 · Not in your library". */
private fun MediaListEntry.menuLine(): String = listOfNotNull(
    if (mediaType == MediaType.Tv) "TV show" else "Movie",
    year?.toString(),
    WatchlistWords.NOT_IN_LIBRARY.takeIf { item == null },
).joinToString(" · ")

/** The entry's place in the list (`.rank`). */
@Composable
private fun BoxScope.RankBadge(rank: Int) {
    Box(
        Modifier.align(Alignment.TopStart).padding(8.dp).heightIn(min = 32.dp).background(WmmColors.Ground.copy(alpha = 0.85f), RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(rank.toString(), style = inter(16.sp, FontWeight.ExtraBold), color = WmmColors.Text)
    }
}

/**
 * A title the library doesn't have (`.dim` with `.pill`): TMDB's poster dimmed,
 * with Request or where its request stands; an import's title TMDB couldn't
 * find is a dashed Not found box, which takes focus like the others.
 */
@Composable
private fun MissingCardTv(entry: MediaListEntry, imageUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, ranked: Boolean = true) {
    val status = entry.missingStatus
    val description = (if (ranked) "${entry.rank}. " else "") + "${entry.title}, " + when {
        !entry.found -> "not found on TMDB"
        entry.requestable -> "not in your library, OK to request"
        else -> status ?: "not in your library"
    }
    FocusableBox(onClick = onClick, onLongClick = onLongClick, modifier = modifier.width(190.dp).semantics(mergeDescendants = true) { contentDescription = description }, cornerRadius = 12.dp, focusedScale = 1.08f) {
        Column {
            Box(Modifier.size(190.dp, 285.dp)) {
                if (!entry.found) {
                    Column(
                        Modifier.fillMaxSize().background(Color(0xFF141416), RoundedCornerShape(12.dp)).border(2.dp, Color(0xFF5A5A5F), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(WmmIcons.NotFound, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(36.dp))
                        Text("Not found", style = inter(19.sp, FontWeight.Bold), color = WmmColors.TextSoft)
                        Text(entry.title, style = inter(16.sp, lineHeight = 22.sp), color = WmmColors.TextMuted, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Artwork(imageUrl, seed = entry.title, contentDescription = null, modifier = Modifier.fillMaxSize().alpha(0.32f), shape = RoundedCornerShape(12.dp))
                    when {
                        entry.requestable -> Row(
                            Modifier.align(Alignment.BottomCenter).padding(10.dp).fillMaxWidth().heightIn(min = 48.dp)
                                .background(WmmColors.Brand, RoundedCornerShape(12.dp)).border(1.dp, WmmColors.BrandLine, RoundedCornerShape(12.dp)),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(WmmIcons.Plus, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Text("Request", style = inter(17.sp, FontWeight.SemiBold), color = Color.White)
                        }
                        status != null -> Box(
                            Modifier.align(Alignment.BottomCenter).padding(10.dp).fillMaxWidth().heightIn(min = 48.dp).background(WmmColors.Surface2.copy(alpha = 0.92f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            val tone = if (entry.requestState == RequestableState.Downloading) WmmColors.Accent else if (entry.requestState == null) WmmColors.TextMuted else WmmColors.TextSoft
                            Text(status, style = inter(17.sp, FontWeight.SemiBold), color = tone, textAlign = TextAlign.Center)
                        }
                    }
                }
                if (ranked) RankBadge(entry.rank)
            }
            Text(entry.title, style = inter(20.sp, FontWeight.SemiBold), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            Text(if (ranked) entry.missingLine else WatchlistWords.NOT_IN_LIBRARY, style = inter(17.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
