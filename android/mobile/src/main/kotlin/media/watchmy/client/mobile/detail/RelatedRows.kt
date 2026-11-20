package media.watchmy.client.mobile.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import media.watchmy.client.mobile.components.PosterCard
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.ui.LocalAppServices

/**
 * The rows at the end of a movie's or a show's page: the movie's
 * collection and More like this, each named as the page's other sections
 * are, over Home's poster cards. A card opens its title's page on top of
 * this one. Nothing is drawn while there are no rows. [edge] is the space
 * before the heading and the first card; the cards scroll to the screen's
 * edge.
 */
@Composable
internal fun RelatedRows(
    rows: List<ScreenRow>,
    onOpenMovie: (Long) -> Unit,
    onOpenShow: (Long) -> Unit,
    tablet: Boolean,
    modifier: Modifier = Modifier,
    edge: Dp = 16.dp,
) {
    if (rows.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        for (row in rows) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailHeading(row.title, Modifier.padding(horizontal = edge).semantics(mergeDescendants = true) { heading() })
                LazyRow(contentPadding = PaddingValues(horizontal = edge), horizontalArrangement = Arrangement.spacedBy(if (tablet) 12.dp else 8.dp)) {
                    items(row.items, key = { "${it.kind}${it.id}" }) { item -> RelatedCard(item, tablet, onOpenMovie, onOpenShow) }
                }
            }
        }
    }
}

/** Home's poster card, opening its title; its long-press has nothing to offer here. */
@Composable
private fun RelatedCard(item: BrowseItem, tablet: Boolean, onOpenMovie: (Long) -> Unit, onOpenShow: (Long) -> Unit) {
    val library = LocalAppServices.current.library
    PosterCard(
        item,
        library.imageUrl(item.posterPath, ImageSize.W342),
        item.subtitle ?: item.year?.toString(),
        onClick = {
            when (item.kind) {
                ItemKind.Movie -> onOpenMovie(item.id)
                ItemKind.Show -> onOpenShow(item.id)
                ItemKind.Unmatched -> Unit
            }
        },
        onSetWatched = null,
        width = if (tablet) 150.dp else 116.dp,
    )
}
