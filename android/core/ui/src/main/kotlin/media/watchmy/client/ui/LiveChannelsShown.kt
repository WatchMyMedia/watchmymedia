package media.watchmy.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Where a screen showing Live TV channels says whether it is on screen: the
 * nav host provides its LiveChannelsViewModel's `showing`, which keeps the
 * channel list fresh only while one is (PLAN-ANDROID-REVIEW.md §4.1.3).
 */
val LocalLiveChannelsShown = staticCompositionLocalOf<(screen: Any, shown: Boolean) -> Unit> { { _, _ -> } }

/**
 * One line in each screen or row that shows channels (the channel lists,
 * the guide, Home's Live row): while it is on screen the channel list is
 * polled, quietly; nowhere else, so not during a film.
 */
@Composable
fun ShowsLiveChannels() {
    val report = LocalLiveChannelsShown.current
    val screen = remember { Any() }
    ReportShown { report(screen, it) }
}
