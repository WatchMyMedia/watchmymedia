package media.watchmy.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import media.watchmy.client.model.RequestRepository
import media.watchmy.client.model.RequestOptions

/** Whether Settings offers Your requests, and the Inbox's count for admins (null for everyone else). */
data class RequestBadge(val requests: Boolean = false, val inbox: Int? = null)

/** Listing earlier requests is still allowed when permission to create another is removed. */
internal fun RequestOptions.badge() = RequestBadge(requests = supported, inbox = if (canOpenInbox) inbox else null)

/**
 * [RequestBadge] for the signed-in user, kept up while the app is in front:
 * fetched when it comes to the front and whenever requests or problems change
 * on the server. A failure keeps what was shown.
 */
@Composable
fun rememberRequestBadge(requests: RequestRepository): State<RequestBadge> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(RequestBadge(), requests, lifecycle) {
        suspend fun load() {
            try {
                val o = requests.options()
                value = o.badge()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the last count.
            }
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            load()
            requests.changes.collect { load() }
        }
    }
}
