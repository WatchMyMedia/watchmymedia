package media.watchmy.client.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import media.watchmy.client.model.Trickplay
import media.watchmy.client.model.formatClock
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import kotlin.math.roundToInt

/**
 * A seek thumbnail's sizes, from the boards (PLAN-TRICKPLAY.md §7.2): the
 * tile [width] wide (its height from the manifest's shape) with a light
 * [edge] and a shadow, and the time under it [gap] below.
 */
@Immutable
class SeekThumbnailStyle(
    val width: Dp,
    val corner: Dp,
    val edge: Dp,
    val shadow: Dp,
    val gap: Dp,
    val labelHeight: Dp,
    val labelPadding: Dp,
    val labelCorner: Dp,
    val labelShade: Float,
    val labelText: TextStyle,
    /** Between the time and a chip beside it (the TV's step). */
    val chipGap: Dp = 10.dp,
) {
    companion object {
        /** PlayerScrub on the phone. */
        val Phone = SeekThumbnailStyle(200.dp, 8.dp, 2.dp, 12.dp, 6.dp, 24.dp, 8.dp, 6.dp, 0.72f, time(13))

        /** TabPlayerScrub. */
        val Tablet = SeekThumbnailStyle(256.dp, 10.dp, 2.dp, 14.dp, 8.dp, 26.dp, 10.dp, 7.dp, 0.72f, time(14))

        /** PlayerScrub on the TV, in its 1920-dp-wide window. */
        val Tv = SeekThumbnailStyle(400.dp, 12.dp, 3.dp, 24.dp, 12.dp, 40.dp, 14.dp, 10.dp, 0.75f, time(24))

        private fun time(size: Int) = TextStyle(
            fontFamily = Inter,
            fontWeight = FontWeight.Bold,
            fontSize = size.sp,
            lineHeight = (size * 1.25).sp,
            color = WmmColors.Text,
            fontFeatureSettings = "tnum",
        )
    }
}

/**
 * The picture of the video at [positionMs] while the viewer scrubs or seeks
 * (PLAN-TRICKPLAY.md §7.1): its tile from [trickplay]'s sheets, with the time
 * under it and [chip] beside the time. The tile changes only every
 * [Trickplay.intervalMs] and the time every second, so a drag recomposes
 * little; until a tile's picture comes the last one stays, or a black frame.
 * Decorative: the scrubber still says the time.
 */
@Composable
fun SeekThumbnail(
    trickplay: Trickplay,
    positionMs: () -> Long,
    style: SeekThumbnailStyle,
    modifier: Modifier = Modifier,
    chip: (@Composable () -> Unit)? = null,
) {
    val position by rememberUpdatedState(positionMs)
    val thumbnails = LocalAppServices.current.seekThumbnails
    val tile by remember(trickplay) { derivedStateOf { trickplay.tileAt(position()) } }
    val time by remember { derivedStateOf { formatClock(position()) } }
    var picture by remember(trickplay) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(trickplay, thumbnails) {
        // A scrub starts: fetch the sheets around here, and meanwhile follow the tile.
        launch(start = CoroutineStart.UNDISPATCHED) { thumbnails.prefetch(trickplay, tile) }
        snapshotFlow { tile }.collectLatest { picture = thumbnails.load(trickplay, it)?.asImageBitmap() }
    }
    Column(
        modifier.clearAndSetSemantics { testTag = SEEK_THUMBNAIL_TAG },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(style.gap),
    ) {
        TileFrame(trickplay, { picture }, style)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(style.chipGap)) {
            SeekTime({ time }, style)
            chip?.invoke()
        }
    }
}

/**
 * Fetches the sheets around [positionMs] while the controls are up, so the
 * first tile of a scrub is there when it starts.
 */
@Composable
fun PrefetchSeekThumbnails(trickplay: Trickplay, positionMs: () -> Long) {
    val thumbnails = LocalAppServices.current.seekThumbnails
    LaunchedEffect(trickplay, thumbnails) { thumbnails.prefetch(trickplay, trickplay.tileAt(positionMs())) }
}

@Composable
private fun TileFrame(trickplay: Trickplay, picture: () -> ImageBitmap?, style: SeekThumbnailStyle) {
    val inner = RoundedCornerShape(style.corner)
    val outer = RoundedCornerShape(style.corner + style.edge)
    Box(
        Modifier
            .shadow(style.shadow, outer, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Edge, outer)
            .padding(style.edge),
    ) {
        Box(Modifier.size(style.width, style.width * trickplay.height / trickplay.width).clip(inner).background(Color.Black)) {
            picture()?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        }
    }
}

@Composable
private fun SeekTime(text: () -> String, style: SeekThumbnailStyle) {
    BasicText(
        text(),
        Modifier
            .height(style.labelHeight)
            .background(Color.Black.copy(alpha = style.labelShade), RoundedCornerShape(style.labelCorner))
            .padding(horizontal = style.labelPadding)
            .wrapContentHeight(Alignment.CenterVertically),
        style = style.labelText,
        maxLines = 1,
    )
}

/** What tests find a seek thumbnail by; it says nothing to TalkBack. */
const val SEEK_THUMBNAIL_TAG = "SeekThumbnail"

/** The tile's light edge, as the boards draw it. */
private val Edge = Color(0xFFF5F5F5).copy(alpha = 0.92f)

/**
 * A scrubber's row: [elapsed], the [track] across what is left, and
 * [remaining], [gap] apart and centred on one line; and [preview], when
 * there is one, above the thumb at [fraction] of the track, its bottom
 * [previewLift] above the track's middle and kept within the row. The
 * preview takes no room, and [fraction] is read only to place it, so it
 * follows the thumb without recomposing the row.
 */
@Composable
fun ScrubberLayout(
    fraction: () -> Float,
    gap: Dp,
    previewLift: Dp,
    elapsed: @Composable () -> Unit,
    track: @Composable () -> Unit,
    remaining: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    preview: (@Composable () -> Unit)? = null,
) {
    Layout(contents = listOf(elapsed, track, remaining, preview ?: {}), modifier) { (start, middle, end, above), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val left = start.first().measure(loose)
        val right = end.first().measure(loose)
        val space = gap.roundToPx()
        val width = constraints.maxWidth
        val trackWidth = (width - left.width - right.width - 2 * space).coerceAtLeast(0)
        val bar = middle.first().measure(loose.copy(minWidth = trackWidth, maxWidth = trackWidth))
        val height = maxOf(left.height, bar.height, right.height, constraints.minHeight)
        val shown = above.firstOrNull()?.measure(Constraints())
        layout(width, height) {
            val trackX = left.width + space
            left.place(0, (height - left.height) / 2)
            bar.place(trackX, (height - bar.height) / 2)
            right.place(width - right.width, (height - right.height) / 2)
            shown?.let {
                val thumb = trackX + fraction().coerceIn(0f, 1f) * trackWidth
                val x = (thumb - it.width / 2f).roundToInt().coerceIn(0, (width - it.width).coerceAtLeast(0))
                it.place(x, height / 2 - previewLift.roundToPx() - it.height)
            }
        }
    }
}
