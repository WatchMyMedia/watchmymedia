package media.watchmy.client.tv.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.MissingEpisode
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.airedLabel
import media.watchmy.client.model.formatBytes
import media.watchmy.client.model.monthDay
import media.watchmy.client.tv.requests.tvIcon
import media.watchmy.client.tv.requests.tvTone
import media.watchmy.client.ui.WmmColors

// The TV show page's parts shared with other screens (PLAN-SHOW-PAGE.md §4.3).

/** Cast & details: the show's whole overview, its facts and cast, from the show page and the episode page's More. */
@Composable
internal fun ShowDetailsSheet(show: ShowDetail, library: LibraryRepository, onDismiss: () -> Unit) {
    DetailsSheet(
        title = show.title,
        overview = show.overview,
        facts = listOfNotNull(
            show.status?.let { "Status" to it },
            show.genres.takeIf { it.isNotEmpty() }?.let { "Genres" to it.joinToString(", ") },
            "Episodes" to "${show.episodesOnDisk} on disk",
            "Size" to formatBytes(show.totalSizeBytes),
            show.nextAirDate?.let { "Next episode" to (monthDay(it) ?: it) },
        ),
        cast = show.cast,
        castImage = { library.imageUrl(it.profilePath, ImageSize.W185) },
        onDismiss = onDismiss,
    )
}

/** Where a missing episode's still would be (`.still.miss`): dashed, with the state's icon. */
@Composable
internal fun MissingStill(missing: MissingEpisode, radius: androidx.compose.ui.unit.Dp, iconSize: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(WmmColors.Surface, RoundedCornerShape(radius))
            .drawBehind {
                drawRoundRect(
                    WmmColors.Line3,
                    cornerRadius = CornerRadius(radius.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(missing.kind.tvIcon(), contentDescription = null, tint = missing.kind.tvTone(), modifier = Modifier.size(iconSize))
    }
}

/** "Aired yesterday · 52 min" or "Airs Nov 4". */
internal fun episodeMeta(e: EpisodeDetail): String = listOfNotNull(
    airedLabel(e.airDate),
    e.runtimeMin?.takeIf { e.aired }?.let { "$it min" },
).joinToString(" · ")
