package media.watchmy.client.mobile.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import media.watchmy.client.ui.components.Sheen
import media.watchmy.client.ui.components.SkeletonColors
import media.watchmy.client.ui.components.loadingSemantics
import media.watchmy.client.ui.components.rememberSheen
import media.watchmy.client.ui.components.skeletonBlock
import media.watchmy.client.ui.components.skeletonLine

// The phone's loading skeletons (android/HomeLoading.dc.html): the shape of
// the page to come in place of a spinner, so launch goes from the splash
// screen to the page's outline and then the page.

/** The two lines under a card: [first] wide, then a shorter, thinner one. */
private val CardLines = listOf(150.dp to 96.dp, 120.dp to 80.dp, 100.dp to 70.dp, 130.dp to 88.dp, 88.dp to 64.dp, 110.dp to 76.dp, 96.dp to 60.dp, 124.dp to 84.dp, 104.dp to 72.dp, 140.dp to 90.dp, 92.dp to 66.dp, 116.dp to 78.dp)

private fun lines(i: Int, cap: Dp): Pair<Dp, Dp> = CardLines[i % CardLines.size].let { (a, b) -> minOf(a, cap) to minOf(b, cap - 20.dp) }

/** Home loading: the hero, a row of wide cards and a row of posters. TalkBack hears "Loading Home". */
@Composable
fun HomeSkeleton(modifier: Modifier = Modifier, sheen: Sheen = rememberSheen(1_600)) {
    Box(modifier.fillMaxSize().clipToBounds().loadingSemantics("Loading Home")) {
        Column(
            Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).padding(start = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.padding(end = 16.dp).fillMaxWidth().height(400.dp).skeletonBlock(sheen, RoundedCornerShape(16.dp)), contentAlignment = Alignment.BottomStart) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.skeletonLine(sheen, 150.dp, 9.dp, SkeletonColors.Strong))
                    Box(Modifier.skeletonLine(sheen, 220.dp, 26.dp, SkeletonColors.Strong, radius = 6.dp))
                    Box(Modifier.skeletonLine(sheen, 250.dp, 11.dp, SkeletonColors.Strong))
                    Box(Modifier.skeletonLine(sheen, 330.dp, 11.dp, SkeletonColors.Soft))
                    Box(Modifier.skeletonLine(sheen, 270.dp, 11.dp, SkeletonColors.Soft))
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.skeletonLine(sheen, 92.dp, 40.dp, SkeletonColors.Brand))
                        Box(Modifier.skeletonLine(sheen, 120.dp, 40.dp, SkeletonColors.Strong))
                    }
                }
            }
            SkeletonRow(sheen, headingWidth = 170.dp, count = 2, cardWidth = 232.dp, artHeight = 130.dp, gap = 12.dp, cap = 200.dp)
            Box(Modifier.padding(top = 4.dp)) {
                SkeletonRow(sheen, headingWidth = 200.dp, count = 4, cardWidth = 116.dp, artHeight = 174.dp, gap = 8.dp, cap = 100.dp)
            }
        }
    }
}

/** A row heading and its cards, running off the right edge as a real row does. */
@Composable
private fun SkeletonRow(sheen: Sheen, headingWidth: Dp, count: Int, cardWidth: Dp, artHeight: Dp, gap: Dp, cap: Dp) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.skeletonLine(sheen, headingWidth, 14.dp, radius = 6.dp))
        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true), horizontalArrangement = Arrangement.spacedBy(gap)) {
            repeat(count) { i ->
                val (w1, w2) = lines(i, cap)
                Column(Modifier.width(cardWidth), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.width(cardWidth).height(artHeight).skeletonBlock(sheen, RoundedCornerShape(8.dp)))
                    Box(Modifier.padding(top = 4.dp).skeletonLine(sheen, w1, 10.dp))
                    Box(Modifier.skeletonLine(sheen, w2, 8.dp))
                }
            }
        }
    }
}

/**
 * The library grid loading: three posters a row with their two lines, as
 * many rows as fit. [label] is what TalkBack hears ("Loading TV Shows").
 */
@Composable
fun LibraryGridSkeleton(label: String, modifier: Modifier = Modifier, sheen: Sheen = rememberSheen(1_600), columns: Int = 3) {
    Box(modifier.fillMaxSize().clipToBounds().loadingSemantics(label)) {
        Column(
            Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            repeat(4) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(columns) { col ->
                        val (w1, w2) = lines(row * columns + col, 104.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).skeletonBlock(sheen, RoundedCornerShape(8.dp)))
                            Box(Modifier.padding(top = 2.dp).skeletonLine(sheen, w1, 10.dp))
                            Box(Modifier.skeletonLine(sheen, w2, 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The library's chips while the first page loads: their outlines, as wide as the board's. TalkBack skips them. */
@Composable
fun ChipRowSkeleton(modifier: Modifier = Modifier, sheen: Sheen = rememberSheen(1_600)) {
    Row(
        modifier.fillMaxWidth().clipToBounds().wrapContentWidth(Alignment.Start, unbounded = true).padding(horizontal = 16.dp).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(52.dp, 104.dp, 72.dp, 104.dp, 48.dp, 56.dp).forEach { w -> Box(Modifier.skeletonLine(sheen, w, 32.dp, radius = 8.dp)) }
    }
}
