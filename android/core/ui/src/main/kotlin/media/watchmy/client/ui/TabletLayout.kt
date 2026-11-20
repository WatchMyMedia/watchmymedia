package media.watchmy.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Window breakpoints shared by the touch screens; never used by the TV shell. */
object TabletLayout {
    val railBreakpoint = 600.dp
    val sideBySide = 1000.dp
    val margin = 24.dp
    val posterMinimum = 150.dp
    val detailColumn = 400.dp
    val singleColumnMax = 720.dp
    /** The height a tablet layout needs: a phone held sideways, about 410 dp tall, keeps the phone layouts. */
    const val TABLET_MIN_HEIGHT_DP = 480

    /**
     * The tablet layouts: at least 600 dp wide and 480 dp tall.
     * Width alone gave a phone held sideways
     * (915 × 412) the tablet Home, whose 440 dp panel didn't fit its height.
     */
    fun isTablet(widthDp: Int, heightDp: Int) = widthDp >= 600 && heightDp >= TABLET_MIN_HEIGHT_DP
    fun usesTwoPanes(widthDp: Int, heightDp: Int) = isTablet(widthDp, heightDp) && widthDp >= 1000
    // A landscape phone is wider than 600 dp, but retains the phone player.
    fun usesTabletPlayer(widthDp: Int, smallestWidthDp: Int) = widthDp >= 600 && smallestWidthDp >= 600
}

data class TabletLayoutInfo(val widthDp: Int, val heightDp: Int) {
    val isTablet get() = TabletLayout.isTablet(widthDp, heightDp)
    val twoPane get() = TabletLayout.usesTwoPanes(widthDp, heightDp)
}

/** Configuration follows this app window, including a split-screen window. */
@Composable
fun rememberTabletLayout(): TabletLayoutInfo {
    val configuration = LocalConfiguration.current
    return TabletLayoutInfo(configuration.screenWidthDp, configuration.screenHeightDp)
}
