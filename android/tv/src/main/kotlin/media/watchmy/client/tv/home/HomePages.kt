package media.watchmy.client.tv.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.tv.components.Backdrop
import media.watchmy.client.tv.components.ContentEnd
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/**
 * Home as screens: every row fills a screen of its own, the first row's
 * first, so Home opens on what the person is most likely to go on with;
 * the last screen is Edit rows and the totals. The server's pick (the hero)
 * has a screen only when there are no rows to show.
 */
internal sealed interface HomePage {
    val key: String

    /** The hero, when there are no rows ([row] is then null). */
    data class Start(val row: ScreenRow?) : HomePage {
        override val key: String get() = "start"
    }

    /** One row under the panel of its focused card. */
    data class Row(val row: ScreenRow) : HomePage {
        override val key: String get() = "row-${row.id}"
    }

    data object End : HomePage {
        override val key: String get() = "end"
    }
}

internal fun homePages(hero: Boolean, rows: List<ScreenRow>): List<HomePage> = buildList {
    if (rows.isEmpty() && hero) add(HomePage.Start(null))
    rows.forEach { add(HomePage.Row(it)) }
    add(HomePage.End)
}

/** The row a page draws, for its focus and its name along the next page's bottom edge. */
internal val HomePage.row: ScreenRow?
    get() = when (this) {
        is HomePage.Start -> row
        is HomePage.Row -> row
        HomePage.End -> null
    }

/**
 * The focused card's picture behind the whole row screen, faded into the
 * ground from the left and the bottom so the words and the row over it read,
 * and crossfading over 200 ms as focus moves along the row. The seeded
 * gradient shows until the picture is in. [posters]: a poster row sits
 * higher, so the fade from the bottom starts higher too.
 */
@Composable
internal fun RowBackdrop(url: String?, seed: String, modifier: Modifier = Modifier, posters: Boolean = false) {
    Crossfade(targetState = url to seed, animationSpec = tween(200), label = "rowBackdrop", modifier = modifier) { (picture, placeholder) ->
        Backdrop(
            url = picture,
            seed = placeholder,
            modifier = Modifier.fillMaxSize(),
            leftFade = listOf(0f to 1f, 0.3f to 0.94f, 0.62f to 0.5f, 1f to 0f),
            bottomFadeFrom = if (posters) 0.22f else 0.34f,
            glowX = 0.78f,
            glowY = 0.25f,
        )
    }
}

/**
 * Loads the pictures of the cards either side of [index] into the image
 * cache, so moving along the row does not flash the seeded gradient.
 */
@Composable
internal fun PrefetchNeighbours(row: ScreenRow, index: Int, channels: List<LiveChannel>, library: LibraryRepository, live: LiveRepository, now: Long, format: LiveClockFormat) {
    val context = LocalContext.current
    LaunchedEffect(row.id, index) {
        val loader = SingletonImageLoader.get(context)
        listOf(index - 1, index + 1).filter { it >= 0 }.forEach { i ->
            val url = row.homeFocusAt(i, channels, now, format)?.pictureUrl(library, live, ImageSize.W1280) ?: return@forEach
            loader.enqueue(ImageRequest.Builder(context).data(url).build())
        }
    }
}

/** The name of the next row (or the one before, [up]) along the screen's foot, with a chevron, inside the safe area. */
@Composable
internal fun PeekLine(text: String, up: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(start = ContentStart, end = ContentEnd, bottom = SafeY).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WmmIcons.ChevronDown, contentDescription = if (up) "Row before:" else "Next row:", tint = WmmColors.TextSoft, modifier = Modifier.size(28.dp).rotate(if (up) 180f else 0f))
        Text(text, style = TvType.Second, color = WmmColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The picture a row screen shows behind its focused card. */
internal fun HomeFocus.pictureUrl(library: LibraryRepository, live: LiveRepository, size: ImageSize): String? =
    livePicture?.let(live::artUrl) ?: picturePath?.let { library.imageUrl(it, size) }

/**
 * ▲ and ▼ page instead of moving focus: [up] and [down] run once per press
 * (a held key's repeats are swallowed, so a remote's repeat rate cannot skip
 * screens), and a direction without a handler is left to the focus search.
 */
internal fun Modifier.pageKeys(up: (() -> Unit)?, down: (() -> Unit)?): Modifier = onPreviewKeyEvent { event ->
    val handler = when (event.key) {
        Key.DirectionDown -> down
        Key.DirectionUp -> up
        else -> null
    } ?: return@onPreviewKeyEvent false
    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) handler()
    true
}
