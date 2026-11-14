package media.watchmy.client.mobile.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier

/** What a page shows instead of its content: loading, why it failed, or why there is nothing. */
@Immutable
sealed interface LoadStatus {
    data object Loading : LoadStatus

    data class Failed(val message: String) : LoadStatus

    /** Nothing to show, with what to do about it ([action] runs [onAction]). */
    data class Empty(val title: String, val body: String?, val action: String? = null, val onAction: () -> Unit = {}) : LoadStatus
}

/**
 * A page's loading, error and empty states in one place
 * (PLAN-ANDROID-REVIEW.md §5.4): [content] once [status] is null, else the
 * spinner ([loading], a skeleton where the page has one), the message with
 * Try again ([onRetry]), or the empty state with its action.
 */
@Composable
fun LoadContent(
    status: LoadStatus?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit = { LoadingState(modifier) },
    content: @Composable () -> Unit,
) {
    when (status) {
        null -> content()
        LoadStatus.Loading -> loading()
        is LoadStatus.Failed -> ErrorState(status.message, onRetry, modifier)
        is LoadStatus.Empty -> EmptyState(status.title, status.body, modifier, action = status.action, onAction = status.onAction)
    }
}
