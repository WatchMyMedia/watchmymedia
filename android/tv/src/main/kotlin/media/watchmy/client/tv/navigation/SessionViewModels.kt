package media.watchmy.client.tv.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID

/**
 * The ViewModels of one signed-in session: the store they live in, kept by
 * the activity so it outlives the activity being recreated, and cleared
 * when the session ends. Without it they lived in the activity's own store,
 * and so did Navigation's, which keeps every screen's ViewModels: after a
 * sign-out the previous account's stayed alive, still collecting, until the
 * app closed (PLAN-ANDROID-REVIEW.md §3.3.6).
 */
internal class SessionStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    /** Session [id]'s store, made when it starts. */
    fun store(id: String): ViewModelStore = stores.getOrPut(id) { ViewModelStore() }

    /** Session [id] ended: its ViewModels are cleared. */
    fun end(id: String) {
        stores.remove(id)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/**
 * Gives [content] (the signed-in screens) a ViewModel store of its own, for
 * the session they show: cleared when they leave, by signing out or
 * switching account or server, but not when the activity is only being
 * recreated. Each session has its own store, so a new one never starts in
 * the store the last is leaving.
 */
@Composable
internal fun SessionViewModels(content: @Composable () -> Unit) {
    val stores: SessionStores = viewModel()
    val id = rememberSaveable { UUID.randomUUID().toString() }
    val activity = LocalActivity.current
    val owner = remember(stores, id) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = stores.store(id)
        }
    }
    DisposableEffect(stores, id) {
        onDispose { if (activity?.isChangingConfigurations != true) stores.end(id) }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
