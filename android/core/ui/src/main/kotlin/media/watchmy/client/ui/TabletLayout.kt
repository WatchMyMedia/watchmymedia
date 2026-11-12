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
    fun isTablet(widthDp: Int) = widthDp >= 600
    fun usesTwoPanes(widthDp: Int) = widthDp >= 1000
    // A landscape phone is wider than 600 dp, but retains the phone player.
    fun usesTabletPlayer(widthDp: Int, smallestWidthDp: Int) = isTablet(widthDp) && smallestWidthDp >= 600
}

data class TabletLayoutInfo(val widthDp: Int) {
    val isTablet get() = TabletLayout.isTablet(widthDp)
    val twoPane get() = TabletLayout.usesTwoPanes(widthDp)
}

/** Configuration follows this app window, including a split-screen window. */
@Composable
fun rememberTabletLayout() = TabletLayoutInfo(LocalConfiguration.current.screenWidthDp)
