package media.watchmy.client.mobile.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import media.watchmy.client.mobile.components.MobileTab

/**
 * A tap on the tab already shown, in the bottom bar or the rail:
 * its page goes back to the top, as most apps'
 * tabs do. A page takes it with [OnTabReselected].
 */
class TabReselect {
    private val taps = MutableSharedFlow<MobileTab>(extraBufferCapacity = 1)
    val reselected: SharedFlow<MobileTab> = taps.asSharedFlow()

    fun reselect(tab: MobileTab) {
        taps.tryEmit(tab)
    }
}

/** The nav host's [TabReselect]; null outside it. */
val LocalTabReselect = staticCompositionLocalOf<TabReselect?> { null }

/** Runs [toTop] when [tab] is tapped again while its page shows: scroll its list to the top. */
@Composable
fun OnTabReselected(tab: MobileTab, toTop: suspend () -> Unit) {
    val reselect = LocalTabReselect.current ?: return
    val action by rememberUpdatedState(toTop)
    LaunchedEffect(reselect, tab) {
        reselect.reselected.filter { it == tab }.collect { action() }
    }
}
