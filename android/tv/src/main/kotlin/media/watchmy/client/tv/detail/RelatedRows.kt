package media.watchmy.client.tv.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.relatedNote
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.bringWholeIntoView
import media.watchmy.client.tv.rows.TvArrangedRow
import media.watchmy.client.tv.rows.TvRowActions
import media.watchmy.client.tv.rows.cardKey

/**
 * The page's last rows:
 * the movie's collection, with "In release order"
 * beside its name, then More like this, drawn as Home's poster rows. ▼ from
 * what is above comes to the row's first card, or the one it had; the page
 * scrolls to show the whole row, heading and all. OK opens the title's page
 * on top of this one, and Back comes back to the card. Nothing is drawn
 * while there are no rows.
 */
@Composable
internal fun RelatedRows(
    rows: List<ScreenRow>,
    library: LibraryRepository,
    memory: FocusMemory,
    onOpenMovie: (Long) -> Unit,
    onOpenShow: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return
    // Kept across focus moves, so the cards keep the same clicks and skip drawing again.
    val actions = remember(onOpenMovie, onOpenShow) {
        TvRowActions(onPlay = {}, onOpenShow = onOpenShow, onOpenMovie = onOpenMovie, onOpenEpisode = { _, _, _ -> }, onNotMatched = {}, onToggleWatched = null, onOpenList = {})
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        for (row in rows) RelatedRow(row, library, memory, actions)
    }
}

/**
 * One row, kept whole in view while it has focus: focus brings it into view
 * at its resting size, and it grows as its cards do, so it is brought into
 * view again as it grows, or its foot would sink under the screen's edge.
 */
@Composable
private fun RelatedRow(row: ScreenRow, library: LibraryRepository, memory: FocusMemory, actions: TvRowActions) {
    val shown = remember(row) { row.copy(subtitle = row.relatedNote) }
    // The row's way in: its first card, or, back from the page a card opened, that card. The
    // row hands a focus request for any of its cards to this one until a card has had focus,
    // so the page's own restore would otherwise land on the first card.
    val enter = remember(row.id) {
        val back = Snapshot.withoutReadObservation { memory.key }?.takeIf { key -> row.items.indices.any { row.cardKey(it) == key } }
        memory.requester(back ?: row.cardKey(0) ?: row.id)
    }
    val whole = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(0) }
    LaunchedEffect(focused, height) { if (focused) whole.bringIntoView() }
    TvArrangedRow(
        shown,
        library,
        memory,
        actions,
        Modifier
            .bringIntoViewRequester(whole)
            .onSizeChanged { height = it.height }
            .onFocusChanged { focused = it.hasFocus }
            .bringWholeIntoView(),
        enter = enter,
        named = true,
    )
}
