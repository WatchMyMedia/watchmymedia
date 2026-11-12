package media.watchmy.client.tv.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.Badge
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.ProgressStrip
import media.watchmy.client.ui.components.WatchedCheck
import media.watchmy.client.ui.components.WmmIcons
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Left edge of screen content, right of the rail (154 in the mockups). */
val ContentStart = 154.dp

/** Right margin of screen content. */
val ContentEnd = 58.dp

/**
 * A card in a row or grid: artwork [artWidth]×[artHeight] with [overlay]
 * (badges, progress), then the title and an optional grey line. The whole
 * card takes the focus ring, as the mockups' focused `<a>` does. In a
 * [CardRow] the artwork grows while the row has focus (see [CardRowFocus]).
 * The grey line's space is kept when there is none, so every card in a row
 * is as tall as the next and the row does not change height as it scrolls.
 */
@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    artWidth: Dp,
    artHeight: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    focusedScale: Float = 1.06f,
    seed: String = title,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val row = LocalCardRowFocus.current
    val landscape = artWidth > artHeight
    val scale: () -> Float = { row?.scale(landscape) ?: 1f }
    val density = LocalDensity.current
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val inRow = if (row == null) {
        Modifier
    } else {
        Modifier
            .onPlaced { placed[0] = it }
            .onFocusChanged { if (it.isFocused) row.cardFocused(placed[0], with(density) { artWidth.toPx() }, landscape) }
    }
    FocusableBox(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.then(inRow).scaledSize(artWidth, null, scale),
        cornerRadius = 12.dp,
        focusedScale = focusedScale,
    ) {
        Column {
            Artwork(
                url = imageUrl,
                seed = seed,
                // The title under it names the card; TalkBack would read it twice.
                contentDescription = null,
                modifier = Modifier.scaledSize(artWidth, artHeight, scale),
                shape = RoundedCornerShape(12.dp),
                overlay = { Box(Modifier.fillMaxSize(), content = overlay) },
            )
            Text(
                title,
                modifier = Modifier.padding(top = 10.dp),
                style = inter(20.sp, FontWeight.SemiBold),
                color = WmmColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle.orEmpty(),
                modifier = Modifier.padding(top = 2.dp),
                style = inter(17.sp),
                color = WmmColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Lays the content out [width] wide (and [height] tall, if given) times
 * [scale], read while laying out, so a growing row re-lays its cards out
 * each frame without composing them again.
 */
private fun Modifier.scaledSize(width: Dp, height: Dp?, scale: () -> Float): Modifier = layout { measurable, constraints ->
    val s = scale()
    val w = (width.toPx() * s).roundToInt()
    val h = height?.let { (it.toPx() * s).roundToInt() }
    val placeable = measurable.measure(
        if (h != null) Constraints.fixed(w, h) else Constraints(minWidth = w, maxWidth = w, maxHeight = constraints.maxHeight),
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * Whether a [CardRow] has focus, as it animates: the focused row's cards are
 * the largest on screen. Rows at rest show their cards at [REST]; a focused
 * row at [FOCUSED_POSTER] for posters and [FOCUSED_LANDSCAPE] for 16:9
 * cards, which start shorter. A focused 16:9 row (180 × 1.5 = 270 tall)
 * stands above the tallest poster row at rest (Search's 285 × 0.9 = 256).
 */
@Stable
class CardRowFocus internal constructor(private val animated: State<Float>, private val list: LazyListState) {
    /** 0 at rest, 1 focused, in between while it grows or shrinks. */
    val progress: Float get() = animated.value

    fun scale(landscape: Boolean): Float {
        val focused = if (landscape) FOCUSED_LANDSCAPE else FOCUSED_POSTER
        return REST + (focused - REST) * progress
    }

    /** The row's viewport, for placing its cards. */
    internal var viewport: LayoutCoordinates? = null

    private class Anchor(val index: Int, val widthPx: Float, val landscape: Boolean)

    private var anchor: Anchor? = null
    private var anchorX: Float? = null
    private var lastProgress = 0f

    /** A card in the row took focus: the row grows and shrinks around it. */
    internal fun cardFocused(card: LayoutCoordinates?, widthPx: Float, landscape: Boolean) {
        val viewport = viewport ?: return
        if (card == null || !card.isAttached || !viewport.isAttached) return
        val x = viewport.localPositionOf(card, Offset.Zero).x
        val item = list.layoutInfo.visibleItemsInfo.minByOrNull { abs(it.offset - x) } ?: return
        anchor = Anchor(item.index, widthPx, landscape)
    }

    /**
     * Keeps the card that has (or last had) focus where it was while the row
     * grows or shrinks, moving it left only as far as it must to stay whole
     * on screen. The cards before it widen too, and would otherwise push it
     * off the edge until the row scrolled after it. Runs before each frame's
     * layout, which reads the new sizes, so the two land together.
     */
    internal fun holdAnchor(progress: Float, leading: Float, trailing: Float) {
        if (progress == lastProgress) return
        val starting = lastProgress == 0f || lastProgress == 1f
        lastProgress = progress
        val a = anchor ?: return
        val viewport = viewport ?: return
        if (starting) anchorX = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == a.index }?.offset?.toFloat()
        val from = anchorX ?: return
        val width = a.widthPx * scale(a.landscape)
        val x = min(from, viewport.size.width - trailing - width).coerceAtLeast(leading)
        list.requestScrollToItem(a.index, (leading - x).roundToInt())
    }

    companion object {
        const val REST = 0.9f
        const val FOCUSED_POSTER = 1.35f
        const val FOCUSED_LANDSCAPE = 1.5f
    }
}

private val LocalCardRowFocus = compositionLocalOf<CardRowFocus?> { null }

/** A badge on artwork at the TV size (`.badge`: 14 px, 4×8 padding). */
@Composable
fun ArtBadge(text: String, modifier: Modifier = Modifier, style: BadgeStyle = BadgeStyle.Dark) {
    Badge(text, modifier, style = style, fontSize = 14.sp, horizontalPadding = 8.dp, verticalPadding = 4.dp)
}

/** The gold progress strip along the bottom of artwork (6 dp on TV). */
@Composable
fun BoxScope.ArtProgress(progress: Float?) {
    if (progress != null && progress > 0f) ProgressStrip(progress, Modifier.align(Alignment.BottomCenter), height = 6.dp)
}

/** The watched check in the bottom-right corner. */
@Composable
fun BoxScope.ArtWatched(watched: Boolean, size: Dp = 28.dp) {
    if (watched) WatchedCheck(Modifier.align(Alignment.BottomEnd).padding(8.dp), size = size)
}

/** The round play mark in the middle of a Continue watching card. */
@Composable
fun BoxScope.PlayMark() {
    Box(
        Modifier
            .align(Alignment.Center)
            .size(56.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(WmmIcons.Play, contentDescription = null, tint = WmmColors.Text, modifier = Modifier.size(26.dp))
    }
}

/**
 * A horizontal row of cards that starts at [start], keeps the
 * focused card in view with the least scroll, and gives focus back to the
 * card it had when focus comes back to the row. Its cards grow while it has
 * focus, so the row in focus is the tallest on screen ([CardRowFocus]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardRow(modifier: Modifier = Modifier, start: Dp = ContentStart, content: LazyListScope.() -> Unit) {
    val density = LocalDensity.current
    val spec = remember(density) { with(density) { KeepInViewSpec(leading = 12.dp.toPx(), trailing = (ContentEnd + 12.dp).toPx()) } }
    val leading = with(density) { 12.dp.toPx() }
    val trailing = with(density) { (ContentEnd + 12.dp).toPx() }
    val list = rememberLazyListState()
    var hasFocus by remember { mutableStateOf(false) }
    val progress = animateFloatAsState(if (hasFocus) 1f else 0f, tween(220), label = "rowFocus")
    val rowFocus = remember(progress, list) { CardRowFocus(progress, list) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, LocalCardRowFocus provides rowFocus) {
        LazyRow(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = start - 12.dp)
                .onPlaced { rowFocus.viewport = it }
                .onFocusChanged { hasFocus = it.hasFocus }
                .focusRestorer(),
            state = list,
            contentPadding = PaddingValues(start = 12.dp, end = ContentEnd, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
    }
    HoldFocusedCard(rowFocus, leading, trailing)
}

/** Composes again each frame the row grows or shrinks, to hold its focused card in place. */
@Composable
private fun HoldFocusedCard(row: CardRowFocus, leading: Float, trailing: Float) {
    val progress = row.progress
    SideEffect { row.holdAnchor(progress, leading, trailing) }
}

/**
 * A row heading (`.h2`): "Recently added" with a grey "TV Shows" beside it,
 * and [trailing] ("Row 2 of 7", HomeRow.dc.html) at the far right when given.
 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, small: String? = null, start: Dp = ContentStart, trailing: String? = null) {
    val width = if (trailing != null) Modifier.fillMaxWidth().padding(end = ContentEnd) else Modifier
    Row(modifier.then(width).padding(start = start, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = inter(28.sp, FontWeight.SemiBold), color = WmmColors.Text, modifier = Modifier.alignByBaseline())
        if (small != null) Text(small, style = inter(19.sp, FontWeight.Medium), color = WmmColors.TextMuted, modifier = Modifier.alignByBaseline())
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(trailing, style = inter(19.sp), color = WmmColors.TextMuted, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
    }
}

/**
 * Lays the content out at [height] but tells the parent it is [reported]
 * tall, so a backdrop can run on behind what follows it (Home's hero).
 */
fun Modifier.overflowHeight(height: Dp, reported: Dp): Modifier = layout { measurable, constraints ->
    val h = height.roundToPx()
    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
    layout(placeable.width, reported.roundToPx()) { placeable.place(0, 0) }
}
