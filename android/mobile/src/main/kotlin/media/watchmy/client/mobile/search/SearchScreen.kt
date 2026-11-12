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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import media.watchmy.client.mobile.library.gridSubtitle
import media.watchmy.client.mobile.requests.RequestSheet
import media.watchmy.client.mobile.requests.RequestableRow
import media.watchmy.client.mobile.requests.StateChip
import media.watchmy.client.model.EpisodeCard
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.Requestable
import media.watchmy.client.model.RequestableState
import media.watchmy.client.model.besides
import media.watchmy.client.model.episodeCode
import media.watchmy.client.model.formatDay
import media.watchmy.client.model.playRequest
import media.watchmy.client.ui.Inter
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

/**
 * Search (no mockup; the Library grid's style): a search field in the top
 * bar, then matching shows and movies as posters and episodes as rows; an
 * episode opens its page.
 */
@Composable
fun SearchScreen(
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onNavigate: (Any) -> Unit,
    onOpenSeason: (showId: Long, season: Int) -> Unit = { id, _ -> onOpenShow(id) },
    onOpenEpisode: (showId: Long, season: Int, episodeId: Long) -> Unit = { id, season, _ -> onOpenSeason(id, season) },
) {
    val layout = rememberTabletLayout()
    val services = LocalAppServices.current
    val vm = viewModel { SearchViewModel(services.library, requests = services.requests, auth = services.auth, saved = createSavedStateHandle()) }
    var asking by remember { mutableStateOf<Requestable?>(null) }
    val notMatched = rememberNotMatchedNotice()
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { if (state.query.isEmpty()) focus.requestFocus() }

    Column(Modifier.fillMaxSize().background(WmmColors.Ground)) {
        Row(Modifier.statusBarsPadding().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.query,
                onValueChange = vm::setQuery,
                placeholder = { Text("Search shows, movies, episodes", style = TextStyle(fontFamily = Inter, fontSize = 16.sp)) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = WmmColors.Text),
                leadingIcon = { Icon(WmmIcons.Search, contentDescription = null) },
                trailingIcon = if (state.query.isNotEmpty()) {
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
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().height(56.dp).border(1.dp, WmmColors.FieldOutline, RoundedCornerShape(28.dp)).focusRequester(focus),
            )
        }
        Box(Modifier.height(2.dp).fillMaxWidth()) {
            if (state.loading && state.results != null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = WmmColors.Accent, trackColor = Color.Transparent)
        }
        Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            val results = state.results
            val split = layout.twoPane && results != null && (results.shows.isNotEmpty() || results.movies.isNotEmpty()) && results.episodes.isNotEmpty()
            when {
                state.error != null -> ErrorState(state.error!!, vm::retry, title = "Couldn't search")
                state.query.isBlank() -> {
                    if (layout.isTablet) TabletSearchEmpty(state.recentQueries, vm::selectRecentQuery, vm::clearRecentQueries)
                    else EmptyState("Search your libraries", "Find a show, a movie or an episode by its title.")
                }
                results == null -> LoadingState()
                state.noResults -> EmptyState("No matches", "Nothing is called “${state.resultsFor}”. Check the spelling, or try fewer words.")
                else -> Row(Modifier.fillMaxSize()) {
                  LazyVerticalGrid(
                    columns = if (layout.isTablet) GridCells.Adaptive(TabletLayout.posterMinimum) else GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = if (layout.isTablet) 24.dp else 16.dp, end = if (layout.isTablet) 24.dp else 16.dp, top = 8.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (layout.isTablet) 16.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (results.shows.isNotEmpty()) {
                        heading("TV Shows", results.shows.size)
                        items(results.shows, key = { "s${it.id}" }, contentType = { "poster" }) { item ->
                            PosterCard(item, services.library.imageUrl(item.posterPath, ImageSize.W342), gridSubtitle(item), { item.open(onOpenShow, onOpenMovie, onPlay, notMatched) }, { vm.setWatched(item, it) }, titleSize = 13.sp, subtitleSize = 11.sp)
                        }
                    }
                    if (results.movies.isNotEmpty()) {
                        heading("Movies", results.movies.size)
                        items(results.movies, key = { "m${it.id}" }, contentType = { "poster" }) { item ->
                            PosterCard(item, services.library.imageUrl(item.posterPath, ImageSize.W342), gridSubtitle(item), { item.open(onOpenShow, onOpenMovie, onPlay, notMatched) }, { vm.setWatched(item, it) }, titleSize = 13.sp, subtitleSize = 11.sp)
                        }
                    }
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
                    if (results.episodes.isNotEmpty() && !split) {
                        heading("Episodes", results.episodes.size)
                        items(results.episodes, key = { "e${it.episodeId}" }, span = { GridItemSpan(maxLineSpan) }, contentType = { "episode" }) { ep ->
                            EpisodeRow(
                                ep,
                                imageUrl = services.library.imageUrl(ep.stillPath ?: ep.showBackdropPath, ImageSize.W300),
                                onClick = { onOpenEpisode(ep.showId, ep.season, ep.episodeId) },
                                actions = listOfNotNull(
                                    ep.playRequest()?.let { r -> CardAction("Play") { onPlay(r) } },
                                    watchToggle(ep.watched) { vm.setEpisodeWatched(ep.episodeId, it) },
                                    CardAction("Go to show") { onOpenSeason(ep.showId, ep.season) },
                                ),
                            )
                        }
                    }
                  }
                  if (split) {
                      LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                          item { Text("Episodes · ${results.episodes.size}", color = WmmColors.Text, fontFamily = Inter, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                          items(results.episodes, key = { it.episodeId }) { ep ->
                              EpisodeRow(ep, services.library.imageUrl(ep.stillPath ?: ep.showBackdropPath, ImageSize.W300),
                                  onClick = { onOpenEpisode(ep.showId, ep.season, ep.episodeId) },
                                  actions = listOf(watchToggle(ep.watched) { vm.setEpisodeWatched(ep.episodeId, it) }, CardAction("Go to show") { onOpenSeason(ep.showId, ep.season) }),
                              )
                          }
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
 * A movie or show not in the library (SearchRequest.dc.html): Request opens
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TabletSearchEmpty(recent: List<String>, onSelect: (String) -> Unit, onClear: () -> Unit) {
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
        EmptyState("Search your libraries", "Find a show, a movie or an episode by its title.", Modifier.weight(1f).fillMaxWidth())
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

/** An episode match: still, show, code and title, as the Show screen's episode rows. */
@Composable
private fun EpisodeRow(ep: EpisodeCard, imageUrl: String?, onClick: () -> Unit, actions: List<CardAction>) {
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
        }
    }
}
