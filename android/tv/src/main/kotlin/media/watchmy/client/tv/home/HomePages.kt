package media.watchmy.client.tv.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import media.watchmy.client.model.CardPanel
import media.watchmy.client.model.CardTarget
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.cardPanelAt
import media.watchmy.client.model.pictureUrl
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.tv.components.Backdrop
import media.watchmy.client.tv.components.ContentEnd
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.Kicker
import media.watchmy.client.tv.components.TextBright
import media.watchmy.client.tv.components.TvTag
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/**
 * Home as screens (PLAN-HOME.md §7, HomeStart.dc.html and HomeRow.dc.html):
 * the first screen is the hero and the first drawn row, every row after it
 * fills a screen of its own, and the last screen is Edit rows and the
 * totals. Without a hero every row is a row screen.
 */
internal sealed interface HomePage {
    val key: String

    /** The hero over [row], the first row drawn (null: a hero alone). */
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
    if (hero) add(HomePage.Start(rows.firstOrNull()))
    rows.drop(if (hero) 1 else 0).forEach { add(HomePage.Row(it)) }
    add(HomePage.End)
}

/** The row a page draws, for its focus and its name along the next page's bottom edge. */
internal val HomePage.row: ScreenRow?
    get() = when (this) {
        is HomePage.Start -> row
        is HomePage.Row -> row
        HomePage.End -> null
    }

/** What OK does with the panel's card: "opens the film". */
private val CardTarget.hint: String
    get() = when (this) {
        CardTarget.Film -> "opens the film"
        CardTarget.Show -> "opens the show"
        CardTarget.Episode -> "opens the episode"
        CardTarget.Resume -> "resumes"
        CardTarget.Channel -> "watches the channel"
        CardTarget.Guide -> "opens the guide"
        CardTarget.List -> "opens the list"
        CardTarget.File -> "shows the file"
    }

private const val MORE = " · ◀ ▶ more in this row · ▼ next row"

/**
 * The focused card's picture filling the top of a row screen (HomeRow.dc.html:
 * 1360 wide from 560 in, 660 tall, faded into the ground from the left and
 * the bottom), crossfading over 200 ms as focus moves along the row. The
 * seeded gradient shows until the image is in.
 */
@Composable
internal fun RowBackdrop(url: String?, seed: String, modifier: Modifier = Modifier) {
    Crossfade(targetState = url to seed, animationSpec = tween(200), label = "rowBackdrop", modifier = modifier) { (picture, placeholder) ->
        Backdrop(
            url = picture,
            seed = placeholder,
            modifier = Modifier.offset(x = 560.dp).width(1360.dp).height(660.dp),
            leftFade = listOf(0f to 1f, 0.22f to 0.85f, 0.6f to 0f),
            bottomFadeFrom = 0.5f,
            glowX = 0.7f,
            glowY = 0.3f,
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
            val url = row.cardPanelAt(i, channels, now, format)?.pictureUrl(library, live, ImageSize.W1280) ?: return@forEach
            loader.enqueue(ImageRequest.Builder(context).data(url).build())
        }
    }
}

/** The kicker, title, facts, two-line overview and OK hint over a row screen's backdrop (HomeRow.dc.html `Focused title`). */
@Composable
internal fun FocusPanel(panel: CardPanel, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Kicker(panel.kicker, fontSize = 19.sp)
        Text(
            panel.title,
            style = inter(64.sp, FontWeight.ExtraBold, lineHeight = 66.sp, tracking = -0.025f),
            color = WmmColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val style = inter(22.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            var before = false
            if (panel.facts.isNotEmpty()) {
                Text(panel.facts.joinToString(" · "), style = style, color = TextBright, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                before = true
            }
            if (panel.tags.isNotEmpty()) {
                if (before) Text("·", style = style, color = WmmColors.TextFaint)
                panel.tags.forEach { TvTag(it) }
                before = true
            }
            panel.rating?.let {
                if (before) Text("·", style = style, color = WmmColors.TextFaint)
                Text(it, style = style, color = TextBright, maxLines = 1)
            }
        }
        if (panel.overview.isNotBlank()) {
            Text(
                panel.overview,
                style = inter(21.sp, lineHeight = 31.5.sp),
                color = WmmColors.TextSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 800.dp),
            )
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.defaultMinSize(minWidth = 34.dp).height(28.dp).border(1.dp, WmmColors.Line3, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("OK", style = inter(14.sp, FontWeight.Bold), color = WmmColors.TextSoft)
            }
            Text(panel.target.hint + MORE, style = inter(17.sp), color = WmmColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The name of the next row (or the one before, [up]) along the screen's bottom edge, with a chevron (HomeRow.dc.html `.peek`). */
@Composable
internal fun PeekLine(text: String, up: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = ContentStart, end = ContentEnd)
            .height(60.dp)
            .drawBehind { drawLine(WmmColors.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WmmIcons.ChevronDown, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(22.dp).rotate(if (up) 180f else 0f))
        Text(text, style = inter(19.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

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
