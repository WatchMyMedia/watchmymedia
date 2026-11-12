package media.watchmy.client.tv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Sheen
import media.watchmy.client.ui.components.SkeletonColors
import media.watchmy.client.ui.components.loadingSemantics
import media.watchmy.client.ui.components.rememberSheen
import media.watchmy.client.ui.components.skeletonBlock
import media.watchmy.client.ui.components.skeletonLine

/** The lines under each wide card (android-tv/HomeLoading.dc.html). */
private val WideLines = listOf(230.dp to 160.dp, 190.dp to 120.dp, 250.dp to 150.dp, 170.dp to 130.dp, 210.dp to 110.dp, 200.dp to 140.dp)

/**
 * Home loading on the TV (android-tv/HomeLoading.dc.html): a dim backdrop,
 * the hero's lines and buttons, a row of wide cards and a row of posters,
 * in place of a spinner, beside the rail. TalkBack hears "Loading Home".
 */
@Composable
fun HomeSkeleton(modifier: Modifier = Modifier, sheen: Sheen = rememberSheen(1_800)) {
    Box(modifier.fillMaxSize().clipToBounds().loadingSemantics("Loading Home")) {
        Box(
            Modifier
                .offset(x = 560.dp)
                .size(1360.dp, 620.dp)
                .drawBehind {
                    drawRect(Brush.verticalGradient(0f to Color(0xFF1C1C1F), 0.55f to WmmColors.Surface, 1f to WmmColors.Ground))
                    drawRect(Brush.radialGradient(0f to Color.White.copy(alpha = 0.04f), 0.6f to Color.Transparent, center = Offset(size.width * 0.7f, size.height * 0.3f), radius = 900.dp.toPx()))
                    drawRect(Brush.horizontalGradient(0f to WmmColors.Ground, 0.22f to WmmColors.Ground.copy(alpha = 0.85f), 0.6f to Color.Transparent))
                    drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to WmmColors.Ground))
                },
        )

        Column(Modifier.padding(start = ContentStart, top = 112.dp).width(900.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.skeletonLine(sheen, 340.dp, 18.dp))
            Box(Modifier.skeletonLine(sheen, 540.dp, 64.dp, SkeletonColors.Block, radius = 12.dp))
            Box(Modifier.skeletonLine(sheen, 470.dp, 22.dp))
            Box(Modifier.padding(top = 6.dp).skeletonLine(sheen, 760.dp, 20.dp))
            Box(Modifier.skeletonLine(sheen, 600.dp, 20.dp))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.skeletonLine(sheen, 150.dp, 64.dp, SkeletonColors.Brand, radius = 14.dp))
                Box(Modifier.skeletonLine(sheen, 210.dp, 64.dp, SkeletonColors.Block, radius = 14.dp))
            }
        }

        SkeletonRow(sheen, heading = 300.dp, Modifier.padding(start = ContentStart, top = 490.dp)) {
            WideLines.forEach { (w1, w2) ->
                Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(320.dp, 180.dp).skeletonBlock(sheen, RoundedCornerShape(12.dp)))
                    Box(Modifier.padding(top = 4.dp).skeletonLine(sheen, w1, 18.dp))
                    Box(Modifier.skeletonLine(sheen, w2, 14.dp))
                }
            }
        }
        SkeletonRow(sheen, heading = 360.dp, Modifier.padding(start = ContentStart, top = 782.dp)) {
            repeat(10) { Box(Modifier.size(160.dp, 240.dp).skeletonBlock(sheen, RoundedCornerShape(12.dp))) }
        }
    }
}

/** A row heading over its cards, which run off the right edge. */
@Composable
private fun SkeletonRow(sheen: Sheen, heading: Dp, modifier: Modifier = Modifier, cards: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.skeletonLine(sheen, heading, 28.dp, radius = 10.dp))
        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true), horizontalArrangement = Arrangement.spacedBy(24.dp)) { cards() }
    }
}
