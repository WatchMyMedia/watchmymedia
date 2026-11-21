package media.watchmy.client.mobile.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import media.watchmy.client.mobile.navigation.OnTabReselected
import media.watchmy.client.mobile.components.MobileTab
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.CardAction
import media.watchmy.client.mobile.components.EmptyState
import media.watchmy.client.mobile.components.ErrorState
import media.watchmy.client.mobile.components.GoldChip
import media.watchmy.client.mobile.components.LoadingState
import media.watchmy.client.mobile.components.MessageHost
import media.watchmy.client.mobile.components.PosterCard
import media.watchmy.client.mobile.components.PressableCard
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.mobile.components.watchToggle
import media.watchmy.client.presentation.library.gridSubtitle
import media.watchmy.client.mobile.requests.RequestSheet
import media.watchmy.client.mobile.requests.RequestableRow
import media.watchmy.client.mobile.requests.StateChip
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.EpisodeCard
import media.watchmy.client.model.SEARCH_YOUR_SERVERS
import media.watchmy.client.model.noMatchesOnServers
import media.watchmy.client.model.noMatchesOnServersLine
import media.watchmy.client.model.searchYourServersLine
import media.watchmy.client.model.stillAsking
import media.watchmy.client.presentation.search.pageElsewhere
import media.watchmy.client.mobile.components.GoldTextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.Requestable
import media.watchmy.client.model.RequestableState
import media.watchmy.client.model.besides
import media.watchmy.client.model.episodeCode
import media.watchmy.client.model.formatDay
import media.watchmy.client.model.playRequest
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.ReportShown
import media.watchmy.client.ui.TabletLayout
import media.watchmy.client.ui.rememberTabletLayout
import media.watchmy.client.mobile.navigation.MobileRoutes
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.Badge
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.open
import media.watchmy.client.ui.rememberNotMatchedNotice
import media.watchmy.client.presentation.search.SearchViewModel
import androidx.compose.foundation.text.input.TextFieldLineLimits
import media.watchmy.client.ui.rememberModelTextField

/**
 * Search (no mockup; the Library grid's style): a search field in the top
 * bar, then matching shows and movies as posters and episodes as rows; an
 * episode opens its page.
 *
 * With two or more servers signed in it looks in all of them: the server in
 * use's results first, then each other server's at the end of its group,
 * with that server's name on them. One from another server opens there
 * ([onOpenElsewhere]): the app switches to it and comes back to Search with
 * the same words ([words], taken once) under the title's page.
 */
@Composable
fun SearchScreen(
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onNavigate: (Any) -> Unit,
    onOpenSeason: (showId: Long, season: Int) -> Unit = { id, _ -> onOpenShow(id) },
    onOpenEpisode: (showId: Long, season: Int, episodeId: Long) -> Unit = { id, season, _ -> onOpenSeason(id, season) },
    words: String? = null,
    onWordsTaken: () -> Unit = {},
    onOpenElsewhere: (serverId: String, query: String, page: DeepLink) -> Unit = { _, _, _ -> },
) {
    val layout = rememberTabletLayout()
    val services = LocalAppServices.current
    val vm = viewModel { SearchViewModel(services.library, requests = services.requests, auth = services.auth, saved = createSavedStateHandle(), servers = services.servers) }
    var asking by remember { mutableStateOf<Requestable?>(null) }
    val notMatched = rememberNotMatchedNotice()
    val state by vm.state.collectAsStateWithLifecycle()
    // Each time Search comes on screen is a new visit: the other servers are asked which they are again before a sign-in goes to them.
    ReportShown(vm::setShown)
    // The field holds its text; the model searches a moment after it changes.
    val query = rememberModelTextField(state.query, state.revision, vm::setQuery)
    // The Search tab (the tablet's rail) tapped again takes the results back to the top.
    val resultsGrid = rememberLazyGridState()
    OnTabReselected(MobileTab.Search) { resultsGrid.animateScrollToItem(0) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { if (state.query.isEmpty() && words == null) focus.requestFocus() }
    // The words a result on another server was found with, searched again on the server the app switched to.
    LaunchedEffect(words) {
        if (words != null) {
            vm.selectRecentQuery(words)
            onWordsTaken()
        }
    }
    // A picture comes from the server its title is on.
    val picture = { hit: SearchViewModel.Hit<*>, path: String?, size: ImageSize ->
        hit.serverId?.let { services.library.imageUrlOn(it, path, size) } ?: services.library.imageUrl(path, size)
    }

    Column(Modifier.fillMaxSize().background(WmmColors.Ground)) {
        Row(Modifier.statusBarsPadding().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                state = query,
                placeholder = { Text("Search shows, movies, episodes", style = TextStyle(fontFamily = Inter, fontSize = 16.sp)) },
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = WmmColors.Text),
                leadingIcon = { Icon(WmmIcons.Search, contentDescription = null) },
                trailingIcon = if (query.text.isNotEmpty()) {
                    { IconButton(onClick = vm::clear) { Icon(WmmIcons.Close, contentDescription = "Clear") } }
                } else {
                    null
                },
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = WmmColors.Surface2,
                    unfocusedContainerColor = WmmColors.Surface2,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = WmmColors.Accent,
                    selectionColors = TextSelectionColors(handleColor = WmmColors.Accent, backgroundColor = WmmColors.Accent.copy(alpha = 0.3f)),
                    focusedLeadingIconColor = WmmColors.TextMuted,
                    unfocusedLeadingIconColor = WmmColors.TextMuted,
                    focusedTrailingIconColor = WmmColors.TextMuted,
                    unfocusedTrailingIconColor = WmmColors.TextMuted,
                    focusedPlaceholderColor = WmmColors.TextFaint,
                    unfocusedPlaceholderColor = WmmColors.TextFaint,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                onKeyboardAction = { focusManager.clearFocus() },
                modifier = Modifier.fillMaxWidth().height(56.dp).border(1.dp, WmmColors.FieldOutline, RoundedCornerShape(28.dp)).focusRequester(focus),
            )
        }
        Box(Modifier.height(2.dp).fillMaxWidth()) {
            if (state.loading && state.results != null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = WmmColors.Accent, trackColor = Color.Transparent)
        }
        Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            val results = state.results
            val shows = state.shows
            val movies = state.movies
            val episodes = state.episodes
            val split = layout.twoPane && results != null && (shows.isNotEmpty() || movies.isNotEmpty()) && episodes.isNotEmpty()
            val poster: @Composable (SearchViewModel.Hit<BrowseItem>) -> Unit = { hit ->
                val item = hit.item
                val server = hit.serverId
                Column {
                    PosterCard(
                        item, picture(hit, item.posterPath, ImageSize.W342), item.gridSubtitle(),
                        onClick = {
                            if (server == null) item.open(onOpenShow, onOpenMovie, onPlay, notMatched)
                            else item.pageElsewhere()?.let { onOpenElsewhere(server, state.query.trim(), it) } ?: notMatched()
                        },
                        // Watched is marked on the server in use; a title on another is opened there first.
                        onSetWatched = if (server == null) ({ watched: Boolean -> vm.setWatched(item, watched) }) else null,
                        titleSize = 13.sp, subtitleSize = 11.sp,
                    )
                    hit.serverName?.let { ServerLabel(it, Modifier.padding(top = 6.dp)) }
                }
            }
            val episode: @Composable (SearchViewModel.Hit<EpisodeCard>, Boolean) -> Unit = { hit, withPlay ->
                val ep = hit.item
                val server = hit.serverId
                EpisodeRow(
                    ep,
                    imageUrl = picture(hit, ep.stillPath ?: ep.showBackdropPath, ImageSize.W300),
                    onClick = { if (server == null) onOpenEpisode(ep.showId, ep.season, ep.episodeId) else onOpenElsewhere(server, state.query.trim(), ep.pageElsewhere()) },
                    actions = if (server != null) emptyList() else listOfNotNull(
                        ep.playRequest()?.takeIf { withPlay }?.let { r -> CardAction("Play") { onPlay(r) } },
                        watchToggle(ep.watched) { vm.setEpisodeWatched(ep.episodeId, it) },
                        CardAction("Go to show") { onOpenSeason(ep.showId, ep.season) },
                    ),
                    from = hit.serverName,
                )
            }
            when {
                state.error != null -> ErrorState(state.error!!, vm::retry, title = "Couldn't search")
                state.query.isBlank() -> {
                    val title = if (state.several) SEARCH_YOUR_SERVERS else "Search your libraries"
                    val line = if (state.several) searchYourServersLine(state.searched) else "Find a show, a movie or an episode by its title."
                    if (layout.isTablet) TabletSearchEmpty(state.recentQueries, vm::selectRecentQuery, vm::clearRecentQueries, title, line)
                    else EmptyState(title, line)
                }
                results == null -> LoadingState()
                state.noResults -> Column(Modifier.fillMaxSize()) {
                    if (state.several) EmptyState(noMatchesOnServers(state.searched), noMatchesOnServersLine(state.resultsFor), Modifier.weight(1f).fillMaxWidth())
                    else EmptyState("No matches", "Nothing is called “${state.resultsFor}”. Check the spelling, or try fewer words.", Modifier.weight(1f).fillMaxWidth())
                    // A server that didn't answer may still have it.
                    ServersTail(state, vm::retry, Modifier.padding(horizontal = if (layout.isTablet) 24.dp else 12.dp, vertical = 12.dp))
                }
                else -> Row(Modifier.fillMaxSize()) {
                  LazyVerticalGrid(
                    columns = if (layout.isTablet) GridCells.Adaptive(TabletLayout.posterMinimum) else GridCells.Fixed(3),
                    state = resultsGrid,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = if (layout.isTablet) 24.dp else 16.dp, end = if (layout.isTablet) 24.dp else 16.dp, top = 8.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (layout.isTablet) 16.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                  ) {
                    if (shows.isNotEmpty()) {
                        heading("TV Shows", shows.size)
                        items(shows, key = { "s${it.serverId.orEmpty()}:${it.item.id}" }, contentType = { "poster" }) { hit -> poster(hit) }
                    }
                    if (movies.isNotEmpty()) {
                        heading("Movies", movies.size)
                        items(movies, key = { "m${it.serverId.orEmpty()}:${it.item.id}" }, contentType = { "poster" }) { hit -> poster(hit) }
                    }
                    // What can be asked for stays the server in use's.
                    val notInLibrary = state.requestable.besides(results)
                    if (notInLibrary.isNotEmpty()) {
                        item(key = "hNot", span = { GridItemSpan(maxLineSpan) }, contentType = "heading") {
                            Text(
                                if (state.options.viaSeerr) "Not in your library · From Seerr" else "Not in your library",
                                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = WmmColors.Text),
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(notInLibrary, key = { "r${it.mediaType}${it.tmdbId}" }, span = { GridItemSpan(maxLineSpan) }, contentType = { "requestable" }) { item ->
                            NotInLibraryRow(item, onAsk = { asking = item }, onOpenShow = onOpenShow, onOpenMovie = onOpenMovie)
                        }
                    }
                    if (episodes.isNotEmpty() && !split) {
                        heading("Episodes", episodes.size)
                        items(episodes, key = { "e${it.serverId.orEmpty()}:${it.item.episodeId}" }, span = { GridItemSpan(maxLineSpan) }, contentType = { "episode" }) { hit -> episode(hit, true) }
                    }
                    if (state.stillAsking.isNotEmpty() || state.unlisted.isNotEmpty()) {
                        item(key = "servers", span = { GridItemSpan(maxLineSpan) }, contentType = "servers") { ServersTail(state, vm::retry) }
                    }
                  }
                  if (split) {
                      LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                          item { Text("Episodes · ${episodes.size}", color = WmmColors.Text, fontFamily = Inter, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                          items(episodes, key = { "${it.serverId.orEmpty()}:${it.item.episodeId}" }) { hit -> episode(hit, false) }
                      }
                  }
                }
            }
            MessageHost(state.message, vm::messageShown, Modifier.align(Alignment.BottomCenter))
        }
    }
    asking?.let { item ->
        RequestSheet(
            subject = item,
            options = state.options,
            onSent = { message ->
                asking = null
                vm.requested(message)
            },
            onDismiss = { asking = null },
        )
    }
}

/**
 * A movie or show not in the library: Request opens
 * the sheet; one already asked for or downloading says so; one in the
 * library opens it.
 */
@Composable
private fun NotInLibraryRow(item: Requestable, onAsk: () -> Unit, onOpenShow: (Long) -> Unit, onOpenMovie: (Long) -> Unit) {
    val open: (() -> Unit)? = when {
        item.state == RequestableState.Requestable -> onAsk
        item.state == RequestableState.Available && item.showId != null -> ({ onOpenShow(item.showId!!) })
        item.state == RequestableState.Available && item.movieId != null -> ({ onOpenMovie(item.movieId!!) })
        else -> null
    }
    // The grid already pads the sides.
    RequestableRow(item, open, sidePadding = 0.dp) {
        when (item.state) {
            RequestableState.Requestable -> TonalButton("Request", onAsk, compact = true)
            RequestableState.Requested -> StateChip("Requested", WmmIcons.Clock, gold = false)
            RequestableState.Downloading -> StateChip("Downloading", WmmIcons.Download, gold = true)
            RequestableState.Available -> StateChip("In your library", WmmIcons.Check, gold = true)
        }
    }
}

/**
 * The end of the results when search looks in several servers: the ones
 * still being asked, then a line for each whose titles aren't listed, with
 * Try again where asking again may help.
 */
@Composable
private fun ServersTail(state: SearchViewModel.State, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.stillAsking.isNotEmpty()) {
            Row(
                Modifier.heightIn(min = 52.dp).padding(start = 4.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), color = WmmColors.Accent, strokeWidth = 3.dp, trackColor = Color.White.copy(alpha = 0.2f))
                Text(stillAsking(state.stillAsking), style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextSoft))
            }
        }
        state.unlisted.forEach { server ->
            Row(Modifier.heightIn(min = 52.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(WmmIcons.Alert, contentDescription = null, tint = WmmColors.Warn, modifier = Modifier.size(20.dp))
                Text(
                    server.line,
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextSoft),
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (server.retry) GoldTextButton("Try again", onRetry)
            }
        }
    }
}

/** The server a result is on, when it isn't the one in use (`.from`): its name in a thin pill. */
@Composable
private fun ServerLabel(name: String, modifier: Modifier = Modifier) {
    Row(
        modifier.height(24.dp).border(1.dp, WmmColors.Line3, RoundedCornerShape(12.dp)).padding(start = 7.dp, end = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(WmmIcons.Server, contentDescription = null, tint = WmmColors.TextSoft, modifier = Modifier.size(13.dp))
        Text(name, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = WmmColors.TextSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TabletSearchEmpty(recent: List<String>, onSelect: (String) -> Unit, onClear: () -> Unit, title: String, line: String) {
    Column(Modifier.fillMaxSize()) {
        if (recent.isNotEmpty()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent searches", color = WmmColors.Text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClear) { Text("Clear", color = WmmColors.Accent) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { query -> GoldChip(query, selected = false, onClick = { onSelect(query) }) }
                }
            }
        }
        EmptyState(title, line, Modifier.weight(1f).fillMaxWidth())
    }
}

private fun LazyGridScope.heading(title: String, count: Int) {
    item(key = "h$title", span = { GridItemSpan(maxLineSpan) }, contentType = "heading") {
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = WmmColors.Text), modifier = Modifier.alignByBaseline())
            Text("$count", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = WmmColors.TextMuted), modifier = Modifier.alignByBaseline())
        }
    }
}

/** An episode match: still, show, code and title, as the Show screen's episode rows; [from] names its server when it isn't the one in use. */
@Composable
private fun EpisodeRow(ep: EpisodeCard, imageUrl: String?, onClick: () -> Unit, actions: List<CardAction>, from: String? = null) {
    PressableCard(onClick, actions, Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(imageUrl, seed = ep.showTitle, contentDescription = null, modifier = Modifier.size(128.dp, 72.dp), shape = RoundedCornerShape(6.dp)) {
                Box(Modifier.fillMaxSize()) {
                    if (ep.isNew) Badge("New", Modifier.align(Alignment.TopStart).padding(5.dp), style = BadgeStyle.Gold, fontSize = 9.sp, horizontalPadding = 5.dp, verticalPadding = 2.dp)
                    if (ep.watched) Badge("Watched", Modifier.align(Alignment.TopEnd).padding(5.dp), style = BadgeStyle.Gold, fontSize = 9.sp, horizontalPadding = 5.dp, verticalPadding = 2.dp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(ep.showTitle, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = WmmColors.Text), maxLines = 1)
                Text("${episodeCode(ep.season, ep.episode)} · ${ep.title}", style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = WmmColors.TextSoft), maxLines = 1)
                val line = listOfNotNull(formatDay(ep.airDate)?.let { "Aired $it" }, "Not in library".takeIf { !ep.hasFile }).joinToString(" · ")
                if (line.isNotEmpty()) {
                    Text(line, style = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = if (ep.hasFile) WmmColors.TextMuted else WmmColors.Warn), maxLines = 1)
                }
            }
            if (from != null) ServerLabel(from)
        }
    }
}
