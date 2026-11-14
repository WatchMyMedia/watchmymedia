package media.watchmy.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Tells a page's ViewModel whether the page is on screen ([onShown]), from
 * the lifecycle of its navigation entry: shown while it is started, so not
 * while another page covers it or the app is in the background, and not
 * once it leaves composition. Pages reload for server changes only while
 * shown, and once when shown again if one came meanwhile (core:presentation's
 * `Freshness`).
 */
@Composable
fun ReportShown(onShown: (Boolean) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val report by rememberUpdatedState(onShown)
    LaunchedEffect(lifecycle) {
        lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }.distinctUntilChanged().collect { report(it) }
    }
    DisposableEffect(lifecycle) { onDispose { report(false) } }
}
