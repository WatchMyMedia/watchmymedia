package media.watchmy.client.tv.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.RowLink
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.airedLine
import media.watchmy.client.model.cardCount
import media.watchmy.client.model.codeBadge
import media.watchmy.client.model.formatDate
import media.watchmy.client.model.heading
import media.watchmy.client.model.playRequest
import media.watchmy.client.model.remainingLine
import media.watchmy.client.tv.components.ArtBadge
import media.watchmy.client.tv.components.ArtProgress
import media.watchmy.client.tv.components.ArtWatched
import media.watchmy.client.tv.components.CardRow
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.MediaCard
import media.watchmy.client.tv.components.PlayMark
import media.watchmy.client.tv.components.SectionHeader
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.open

/** What a row's cards do on the TV: open, play, say a file isn't matched, and Hold OK's mark on a movie. */
internal class TvRowActions(
    val onPlay: (PlayRequest) -> Unit,
    val onOpenShow: (Long) -> Unit,
    val onOpenMovie: (Long) -> Unit,
    val onOpenEpisode: (showId: Long, season: Int, episodeId: Long) -> Unit,
    val onNotMatched: () -> Unit,
    val onToggleWatched: (BrowseItem) -> Unit,
    val onOpenList: (Long) -> Unit,
)

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

/** Tells the screen which card of a row has focus: its index in the row and its focus key (Home's panel follows it, PLAN-HOME.md §7.3). */
internal typealias OnFocusItem = (index: Int, key: String) -> Unit

/** Registers the card with [memory] under [key] and reports its focus to [onFocusItem]. */
internal fun Modifier.rowCard(memory: FocusMemory, key: String, index: Int, onFocusItem: OnFocusItem?): Modifier =
    focusMemory(memory, key).then(if (onFocusItem == null) Modifier else Modifier.onFocusChanged { if (it.isFocused) onFocusItem(index, key) })

/**
 * One arranged row on the TV (Home, and LibraryRows.dc.html): its heading
 * and the line beside it, then its cards by style, as Home's rows have
 * always looked; a list's row ends with a See all card. The Live TV row is
 * the screen's to draw. Home's row screens pass [counter] ("Row 2 of 7")
 * and [onFocusItem], so the panel above the row follows the focused card.
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
) {
    val (title, small) = row.heading()
    Column(modifier) {
        SectionHeader(title, small = small, trailing = counter)
        CardRow {
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
                        onLongClick = if (item.kind == ItemKind.Movie) ({ actions.onToggleWatched(item) }) else null,
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
                        onLongClick = if (m.kind == ItemKind.Movie) ({ actions.onToggleWatched(m) }) else null,
                        modifier = Modifier.rowCard(memory, "${row.id}:${m.kind}${m.id}", i, onFocusItem),
                    ) {
                        formatDate(m.releaseDate)?.let { ArtBadge(it, Modifier.align(Alignment.BottomStart).padding(10.dp)) }
                        ArtWatched(m.watched)
                    }
                }
                RowStyle.Live -> Unit
            }
            val link = row.seeAll as? RowLink.ToList
            if (link != null) {
                item("see-all") { SeeAllCard(title, wide = row.style != RowStyle.Poster, onClick = { actions.onOpenList(link.listId) }, modifier = Modifier.rowCard(memory, "${row.id}:all", row.cardCount, onFocusItem)) }
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

/** A list row's last card (LibraryRows.dc.html `.more`): See all, with the titles the library doesn't have. */
@Composable
private fun SeeAllCard(listName: String, wide: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableBox(
        onClick = onClick,
        modifier = modifier.width(if (wide) 320.dp else 160.dp).semantics { contentDescription = "See all of $listName" },
        cornerRadius = 12.dp,
    ) { focused ->
        Column(
            Modifier
                .width(if (wide) 320.dp else 160.dp)
                .height(if (wide) 180.dp else 240.dp)
                .background(if (focused) WmmColors.Surface3 else WmmColors.Surface2, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(WmmIcons.ListBullets, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(36.dp))
            Text("See all", style = inter(21.sp, FontWeight.Bold), color = WmmColors.Text)
            Text("With the ones you don’t have", style = inter(17.sp, lineHeight = 23.sp), color = WmmColors.TextMuted, textAlign = TextAlign.Center)
        }
    }
}

/** Lays the content wider than its grid cell by [start] and [end], so a row in a padded grid starts at the screen's edge. */
internal fun Modifier.bleed(start: Dp, end: Dp): Modifier = layout { measurable, constraints ->
    val extra = (start + end).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-start.roundToPx(), 0) }
}
