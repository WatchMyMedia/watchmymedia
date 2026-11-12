package media.watchmy.client.mobile.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.mobile.components.CardAction
import media.watchmy.client.mobile.components.PlayGlyph
import media.watchmy.client.mobile.components.PosterCard
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.mobile.components.WideCard
import media.watchmy.client.mobile.components.watchToggle
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.EpisodeCard
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.WatchTarget
import media.watchmy.client.model.airedLine
import media.watchmy.client.model.codeBadge
import media.watchmy.client.model.formatDate
import media.watchmy.client.model.heading
import media.watchmy.client.model.playRequest
import media.watchmy.client.model.remainingLine
import media.watchmy.client.model.watchTarget
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Badge
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.ProgressStrip
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.open

/**
 * What a row's cards do, for Home and the Movies and TV screens alike: open
 * a show, movie or episode, play, say a file isn't matched, mark watched.
 */
internal class RowActions(
    val onPlay: (PlayRequest) -> Unit,
    val onOpenShow: (Long) -> Unit,
    val onOpenMovie: (Long) -> Unit,
    val onOpenEpisode: (showId: Long, season: Int, episodeId: Long) -> Unit,
    val onNotMatched: () -> Unit,
    val onSetWatched: (WatchTarget, Boolean) -> Unit,
)

private val Title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = WmmColors.Text)
private val Small = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = WmmColors.TextMuted)

/**
 * A row's heading (LibraryRows.dc.html): the title, the words beside it
 * ("Movies", or a list's "18 of 50 · From TMDB"), [counter] ("· Row 2 of
 * 7" on the tablet's Home screens) and See all when there is somewhere to
 * go. The phone keeps See all for list rows, as its built-in rows never had
 * one.
 */
@Composable
internal fun ArrangedRowHeading(row: ScreenRow, tablet: Boolean, onSeeAll: (() -> Unit)?, edge: Dp = if (tablet) 24.dp else 16.dp, counter: String? = null) {
    val (title, small) = row.heading()
    Row(
        Modifier.fillMaxWidth().padding(start = edge, end = if (tablet) edge else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, style = Title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).alignByBaseline().semantics { heading() })
            if (small != null) Text(small, style = Small, maxLines = 1, modifier = Modifier.alignByBaseline())
            if (counter != null) Text("· $counter", style = Small, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
        if (onSeeAll != null) {
            TextButton(onClick = onSeeAll, modifier = Modifier.semantics { contentDescription = "See all of $title" }) {
                Text("See all", color = WmmColors.Accent, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (!tablet) Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * One arranged row by its style, as Home's rows have always looked: posters,
 * 16:9 cards, Recently aired's episodes or Continue watching. The Live TV row
 * is the screen's to draw. The tablet's Home screens pass the row's [state]
 * and a snapping [flingBehavior], outline the card at [lead] (the one the
 * row snapped to, PLAN-HOME.md §7.5) and show [counter] by the heading.
 */
@Composable
internal fun ArrangedRow(
    row: ScreenRow,
    library: LibraryRepository,
    tablet: Boolean,
    actions: RowActions,
    onSeeAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** The space before the heading and the first card: the screen's margin, or none inside a padded grid. */
    edge: Dp = if (tablet) 24.dp else 16.dp,
    state: LazyListState = rememberLazyListState(),
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    lead: Int = -1,
    counter: String? = null,
) {
    val spacing = when {
        tablet -> 16.dp
        row.style == RowStyle.Poster -> 8.dp
        row.style == RowStyle.Wide -> 10.dp
        else -> 12.dp
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ArrangedRowHeading(row, tablet, onSeeAll, edge, counter)
        LazyRow(state = state, flingBehavior = flingBehavior, contentPadding = PaddingValues(horizontal = edge), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            when (row.style) {
                RowStyle.Poster -> itemsIndexed(row.items, key = { _, it -> "${it.kind}${it.id}" }) { i, item -> PosterRowCard(item, library, tablet, actions, outlined = i == lead) }
                RowStyle.Wide -> itemsIndexed(row.items, key = { _, it -> "${it.kind}${it.id}" }) { i, item -> WideRowCard(item, library, tablet, actions, outlined = i == lead) }
                RowStyle.Episode -> itemsIndexed(row.episodes, key = { _, it -> it.episodeId }) { i, ep -> EpisodeRowCard(ep, library, actions, outlined = i == lead) }
                RowStyle.Continue -> itemsIndexed(row.continueItems, key = { _, it -> it.fileId }) { i, item ->
                    WideCard(
                        title = item.title,
                        subtitle = item.remainingLine,
                        imageUrl = library.imageUrl(item.stillPath ?: item.backdropPath, ImageSize.W780),
                        seed = item.title,
                        width = 232.dp,
                        outlined = i == lead,
                        onClick = { actions.onPlay(item.playRequest()) },
                        actions = listOfNotNull(
                            item.watchTarget()?.let { t -> CardAction("Mark as watched") { actions.onSetWatched(t, true) } },
                            item.movieId?.let { id -> CardAction("Go to movie") { actions.onOpenMovie(id) } },
                            item.showId?.let { id -> CardAction("Go to show") { actions.onOpenShow(id) } },
                        ),
                    ) {
                        PlayGlyph()
                        ProgressStrip(item.progress, Modifier.align(Alignment.BottomStart))
                    }
                }
                RowStyle.Live -> Unit
            }
        }
    }
}

@Composable
private fun PosterRowCard(item: BrowseItem, library: LibraryRepository, tablet: Boolean, actions: RowActions, outlined: Boolean = false) {
    PosterCard(
        item,
        library.imageUrl(item.posterPath, ImageSize.W342),
        item.subtitle ?: item.year?.toString(),
        { item.open(actions.onOpenShow, actions.onOpenMovie, actions.onPlay, actions.onNotMatched, actions.onOpenEpisode) },
        item.watchTarget()?.let { t -> { w: Boolean -> actions.onSetWatched(t, w) } },
        width = if (tablet) 150.dp else 116.dp,
        outlined = outlined,
    )
}

@Composable
private fun WideRowCard(item: BrowseItem, library: LibraryRepository, tablet: Boolean, actions: RowActions, outlined: Boolean = false) {
    WideCard(
        title = item.title,
        subtitle = null,
        imageUrl = library.imageUrl(item.backdropPath, ImageSize.W780),
        seed = item.title,
        width = if (tablet) 240.dp else 160.dp,
        outlined = outlined,
        onClick = { if (item.kind == ItemKind.Movie) actions.onOpenMovie(item.id) else item.open(actions.onOpenShow, actions.onOpenMovie, actions.onPlay, actions.onNotMatched) },
        actions = listOfNotNull(item.watchTarget()?.let { t -> watchToggle(item.watched) { actions.onSetWatched(t, it) } }),
    ) {
        formatDate(item.releaseDate)?.let { Badge(it, Modifier.align(Alignment.BottomStart).padding(6.dp)) }
        if (item.watched) Badge("Watched", Modifier.align(Alignment.TopEnd).padding(6.dp), style = BadgeStyle.Gold)
    }
}

@Composable
private fun EpisodeRowCard(ep: EpisodeCard, library: LibraryRepository, actions: RowActions, outlined: Boolean = false) {
    // A tap opens the episode's page; the long-press menu still plays at once.
    val request = ep.playRequest()
    WideCard(
        title = ep.showTitle,
        subtitle = ep.airedLine(),
        imageUrl = library.imageUrl(ep.stillPath ?: ep.showBackdropPath, ImageSize.W780),
        seed = ep.showTitle,
        width = 232.dp,
        outlined = outlined,
        onClick = { actions.onOpenEpisode(ep.showId, ep.season, ep.episodeId) },
        actions = listOfNotNull(
            request?.let { r -> CardAction("Play") { actions.onPlay(r) } },
            watchToggle(ep.watched) { actions.onSetWatched(WatchTarget.Episode(ep.episodeId), it) },
        ),
    ) {
        if (ep.isNew) Badge("New", Modifier.align(Alignment.TopStart).padding(8.dp), style = BadgeStyle.Gold)
        if (!ep.hasFile) {
            Badge("Not in library", Modifier.align(Alignment.TopEnd).padding(8.dp), style = BadgeStyle.Warn)
        } else if (ep.watched) {
            Badge("Watched", Modifier.align(Alignment.TopEnd).padding(8.dp), style = BadgeStyle.Gold)
        }
        Badge(ep.codeBadge, Modifier.align(Alignment.BottomStart).padding(8.dp))
    }
}

/** Edit Home, or a library's Edit rows, at the end of its rows (LibraryRows.dc.html). */
@Composable
internal fun EditRowsButton(label: String, onClick: () -> Unit, tablet: Boolean, modifier: Modifier = Modifier, edge: Dp = if (tablet) 24.dp else 16.dp) {
    Row(modifier.fillMaxWidth().padding(horizontal = edge)) {
        TonalButton(label, onClick, icon = WmmIcons.Sliders)
        Spacer(Modifier.weight(1f))
    }
}

/** The editor for [screen]: a page on a phone, a dialog over the screen on a tablet. */
internal fun rowEditorRoute(screen: String, name: String, tablet: Boolean): Any =
    if (tablet) media.watchmy.client.mobile.navigation.MobileRoutes.EditRowsDialog(screen, name) else media.watchmy.client.mobile.navigation.MobileRoutes.EditRows(screen, name)

/**
 * Lays the content [by] wider on each side than the grid cell it is in, so a
 * row inside a padded grid scrolls to the screen's edges, as Home's do.
 */
internal fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-by.roundToPx(), 0) }
}
