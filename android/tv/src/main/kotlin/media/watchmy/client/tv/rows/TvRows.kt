package media.watchmy.client.tv.rows

import media.watchmy.client.tv.watchlists.holdFor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.BrowseRequest
import media.watchmy.client.model.BrowseRequests
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.RowLink
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.SeeAllWords
import media.watchmy.client.model.airedLine
import media.watchmy.client.model.browseRequest
import media.watchmy.client.model.cardCount
import media.watchmy.client.model.codeBadge
import media.watchmy.client.model.formatDate
import media.watchmy.client.model.heading
import media.watchmy.client.model.playRequest
import media.watchmy.client.model.remainingLine
import media.watchmy.client.model.seeAllType
import media.watchmy.client.model.seeAllWords
import media.watchmy.client.tv.components.ArtBadge
import media.watchmy.client.tv.components.ArtProgress
import media.watchmy.client.tv.components.ArtWatched
import media.watchmy.client.tv.components.CardRow
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.MediaCard
import media.watchmy.client.tv.components.RowArtCard
import media.watchmy.client.tv.components.PlayMark
import media.watchmy.client.tv.components.SectionHeader
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.open

/** What a row's cards do on the TV: open, play, say a file isn't matched, and Hold OK's mark on a movie (none without [onToggleWatched]). */
internal class TvRowActions(
    val onPlay: (PlayRequest) -> Unit,
    val onOpenShow: (Long) -> Unit,
    val onOpenMovie: (Long) -> Unit,
    val onOpenEpisode: (showId: Long, season: Int, episodeId: Long) -> Unit,
    val onNotMatched: () -> Unit,
    val onToggleWatched: ((BrowseItem) -> Unit)?,
    val onOpenList: (Long) -> Unit,
)

/**
 * A row's See all on the TV: what its last card says, and
 * what OK on it does. [list] draws the list glyph instead of the grid's.
 */
internal class TvSeeAll(val words: SeeAllWords, val list: Boolean, val onClick: () -> Unit)

/**
 * A Home row's See all: a list's page, or its library's TV Shows or Movies
 * at the grid, sorted the row's way ([requests] carries the sort there,
 * [onOpenLibrary] goes). Null for a row without a link, and for a library
 * Home has no section for (one of several of its type).
 * Continue watching and Live TV have no link.
 */
internal fun tvHomeSeeAll(row: ScreenRow, home: HomeData, requests: BrowseRequests, actions: TvRowActions, onOpenLibrary: (LibraryType) -> Unit): TvSeeAll? {
    val link = row.seeAll ?: return null
    val type = home.seeAllType(row)
    val words = seeAllWords(link, type, row.heading().first) ?: return null
    return TvSeeAll(words, list = link is RowLink.ToList) {
        when (link) {
            is RowLink.ToList -> actions.onOpenList(link.listId)
            is RowLink.Library -> type?.let {
                requests.post(link.browseRequest())
                onOpenLibrary(it)
            }
        }
    }
}

/**
 * A See all on a row above the TV Shows or Movies grid: a list's page, or
 * the grid below, sorted the row's way ([onOpen]). A link to another library
 * than the grid's ([libraryId]) has none.
 */
internal fun tvLibrarySeeAll(row: ScreenRow, type: LibraryType, libraryId: Long?, actions: TvRowActions, onOpen: (BrowseRequest) -> Unit): TvSeeAll? {
    val link = row.seeAll ?: return null
    if (link is RowLink.Library && link.libraryId != libraryId) return null
    val words = seeAllWords(link, type, row.heading().first) ?: return null
    return TvSeeAll(words, list = link is RowLink.ToList) {
        when (link) {
            is RowLink.ToList -> actions.onOpenList(link.listId)
            is RowLink.Library -> onOpen(link.browseRequest())
        }
    }
}

/**
 * The focus key of a row's card, so focus comes back to it: Continue
 * watching's are those Home always used; the others are the row's.
 */
internal fun ScreenRow.cardKey(index: Int = 0): String? = when (style) {
    RowStyle.Continue -> continueItems.getOrNull(index)?.let { "cw:${it.fileId}" }
    RowStyle.Episode -> episodes.getOrNull(index)?.let { "$id:e${it.episodeId}" }
    RowStyle.Poster, RowStyle.Wide -> items.getOrNull(index)?.let { "$id:${it.kind}${it.id}" }
    RowStyle.Live -> null
}

/** Tells the screen which card of a row has focus: its index in the row and its focus key (Home's panel follows it). */
internal typealias OnFocusItem = (index: Int, key: String) -> Unit

/** Registers the card with [memory] under [key] and reports its focus to [onFocusItem]. */
internal fun Modifier.rowCard(memory: FocusMemory, key: String, index: Int, onFocusItem: OnFocusItem?): Modifier =
    focusMemory(memory, key).then(if (onFocusItem == null) Modifier else Modifier.onFocusChanged { if (it.isFocused) onFocusItem(index, key) })

/**
 * One arranged row on the TV (Home's, or a library's): its heading
 * and the line beside it, then its cards by style, as Home's rows have
 * always looked; a row with somewhere to go ends with a See all card
 * ([seeAll]: a list's page, or its library's grid). The
 * Live TV row is the screen's to draw. Home's row screens pass [counter]
 * ("Row 2 of 7") and [onFocusItem], so the panel above the row follows the
 * focused card. [enter] is the card focus first comes to from outside the
 * row ([CardRow]). [named] makes the heading a heading and names the row
 * after it, so TalkBack says which row focus has come into.
 */
@Composable
internal fun TvArrangedRow(
    row: ScreenRow,
    library: LibraryRepository,
    memory: FocusMemory,
    actions: TvRowActions,
    modifier: Modifier = Modifier,
    counter: String? = null,
    onFocusItem: OnFocusItem? = null,
    seeAll: TvSeeAll? = null,
    enter: FocusRequester = FocusRequester.Default,
    named: Boolean = false,
) {
    val (title, small) = row.heading()
    Column(modifier) {
        SectionHeader(title, small = small, trailing = counter, modifier = if (named) Modifier.semantics(mergeDescendants = true) { heading() } else Modifier)
        CardRow(Modifier.then(if (named) Modifier.semantics { contentDescription = title } else Modifier), enter = enter) {
            when (row.style) {
                RowStyle.Continue -> itemsIndexed(row.continueItems, key = { _, it -> it.fileId }) { i, c ->
                    MediaCard(
                        title = c.title,
                        subtitle = c.remainingLine,
                        imageUrl = library.imageUrl(c.stillPath ?: c.backdropPath, ImageSize.W780),
                        artWidth = 320.dp,
                        artHeight = 180.dp,
                        onClick = { actions.onPlay(c.playRequest()) },
                        onLongClick = { c.openPage(actions) },
                        modifier = Modifier.rowCard(memory, "cw:${c.fileId}", i, onFocusItem),
                    ) {
                        PlayMark()
                        ArtProgress(c.progress)
                    }
                }
                RowStyle.Episode -> itemsIndexed(row.episodes, key = { _, it -> it.episodeId }) { i, e ->
                    MediaCard(
                        title = e.showTitle,
                        subtitle = e.airedLine(),
                        imageUrl = library.imageUrl(e.stillPath ?: e.showBackdropPath, ImageSize.W780),
                        artWidth = 320.dp,
                        artHeight = 180.dp,
                        seed = e.showTitle,
                        // Select opens the episode on its show, focused on Play; holding it plays at once.
                        onClick = { actions.onOpenEpisode(e.showId, e.season, e.episodeId) },
                        onLongClick = { e.playRequest()?.let(actions.onPlay) ?: actions.onOpenEpisode(e.showId, e.season, e.episodeId) },
                        modifier = Modifier.rowCard(memory, "${row.id}:e${e.episodeId}", i, onFocusItem),
                    ) {
                        if (e.isNew) ArtBadge("New", Modifier.align(Alignment.TopStart).padding(10.dp), BadgeStyle.Gold)
                        if (!e.hasFile) ArtBadge("Not in library", Modifier.align(Alignment.TopEnd).padding(10.dp), BadgeStyle.Warn)
                        ArtBadge(e.codeBadge, Modifier.align(Alignment.BottomStart).padding(10.dp))
                        ArtWatched(e.watched)
                    }
                }
                RowStyle.Poster -> itemsIndexed(row.items, key = { _, it -> "${it.kind}${it.id}" }) { i, item ->
                    MediaCard(
                        title = item.title,
                        subtitle = item.subtitle ?: item.year?.toString(),
                        imageUrl = library.imageUrl(item.posterPath, ImageSize.W342),
                        artWidth = 160.dp,
                        artHeight = 240.dp,
                        onClick = { item.open(actions.onOpenShow, actions.onOpenMovie, actions.onPlay, actions.onNotMatched, actions.onOpenEpisode) },
                        onLongClick = holdFor(item, actions.onToggleWatched),
                        modifier = Modifier.rowCard(memory, "${row.id}:${item.kind}${item.id}", i, onFocusItem),
                    ) {
                        if (item.isNew) ArtBadge("New", Modifier.align(Alignment.TopStart).padding(8.dp), BadgeStyle.Gold)
                        ArtWatched(item.watched, size = 24.dp)
                        ArtProgress(item.progress)
                    }
                }
                RowStyle.Wide -> itemsIndexed(row.items, key = { _, it -> "${it.kind}${it.id}" }) { i, m ->
                    MediaCard(
                        title = m.title,
                        subtitle = null,
                        imageUrl = library.imageUrl(m.backdropPath, ImageSize.W780),
                        artWidth = 320.dp,
                        artHeight = 180.dp,
                        onClick = { if (m.kind == ItemKind.Movie) actions.onOpenMovie(m.id) else m.open(actions.onOpenShow, actions.onOpenMovie, actions.onPlay, actions.onNotMatched) },
                        onLongClick = holdFor(m, actions.onToggleWatched),
                        modifier = Modifier.rowCard(memory, "${row.id}:${m.kind}${m.id}", i, onFocusItem),
                    ) {
                        formatDate(m.releaseDate)?.let { ArtBadge(it, Modifier.align(Alignment.BottomStart).padding(10.dp)) }
                        ArtWatched(m.watched)
                    }
                }
                RowStyle.Live -> Unit
            }
            if (seeAll != null) {
                item("see-all") { SeeAllCard(seeAll, wide = row.style != RowStyle.Poster, modifier = Modifier.rowCard(memory, "${row.id}:all", row.cardCount, onFocusItem)) }
            }
        }
    }
}

private fun ContinueItem.openPage(actions: TvRowActions) {
    when {
        showId != null -> actions.onOpenShow(showId!!)
        movieId != null -> actions.onOpenMovie(movieId!!)
    }
}

/**
 * A row's last card, the size of the row's
 * artwork as it grows with the row ([RowArtCard]): on a list's row See all,
 * with the titles the library doesn't have;
 * on a built-in row "See all TV shows" over "By last aired" with the grid
 * glyph, read aloud as one.
 */
@Composable
private fun SeeAllCard(seeAll: TvSeeAll, wide: Boolean, modifier: Modifier = Modifier) {
    RowArtCard(
        width = if (wide) 320.dp else 160.dp,
        height = if (wide) 180.dp else 240.dp,
        onClick = seeAll.onClick,
        modifier = modifier.semantics { contentDescription = seeAll.words.spoken },
    ) { focused ->
        Column(
            Modifier
                .fillMaxSize()
                .background(if (focused) WmmColors.Surface3 else WmmColors.Surface2, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(if (seeAll.list) WmmIcons.ListBullets else WmmIcons.Grid, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(36.dp))
            Text(seeAll.words.title, style = inter(21.sp, FontWeight.Bold, lineHeight = 26.sp), color = WmmColors.Text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            seeAll.words.detail?.let { detail ->
                Text(detail, style = inter(17.sp, lineHeight = 23.sp), color = WmmColors.TextMuted, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Lays the content wider than its grid cell by [start] and [end], so a row in a padded grid starts at the screen's edge. */
internal fun Modifier.bleed(start: Dp, end: Dp): Modifier = layout { measurable, constraints ->
    val extra = (start + end).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-start.roundToPx(), 0) }
}
