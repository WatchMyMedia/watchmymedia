package media.watchmy.client.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID

/**
 * The ViewModels of the signed-in sessions, one store each, kept by the
 * activity so they outlive the activity being recreated.
 * Without them the signed-in screens'
 * ViewModels lived in the activity's own store, and so did Navigation's,
 * which keeps every screen's: after a sign-out the previous account's
 * stayed alive, still collecting, until the app closed.
 */
class SessionStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    /** How many sessions have ViewModels alive: one while someone is signed in, none after a sign-out. */
    val open: Int get() = stores.size

    /** Session [id]'s store, made when it starts. */
    internal fun store(id: String): ViewModelStore = stores.getOrPut(id) { ViewModelStore() }

    /** Session [id] ended: its ViewModels are cleared. */
    internal fun end(id: String) {
        stores.remove(id)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/**
 * Gives [content] (a UI's signed-in screens, around its nav host) a
 * ViewModel store of its own for the session it shows, on the phone and
 * the TV alike. Its ViewModels are cleared when [content] leaves (signing
 * out, or another account or server signing in: the caller keys this by
 * server and user), but not when the activity is only being recreated.
 * Each session has its own store, so a new one never starts in the store
 * the last is leaving. ViewModels made here get the activity's factory and
 * creation extras, so one can still ask for a SavedStateHandle.
 */
@Composable
fun SessionViewModels(content: @Composable () -> Unit) {
    val parent = checkNotNull(LocalViewModelStoreOwner.current) { "SessionViewModels needs a ViewModelStoreOwner" }
    val stores = viewModel(parent) { SessionStores() }
    val id = rememberSaveable { UUID.randomUUID().toString() }
    val activity = LocalActivity.current
    val owner = remember(stores, id, parent) { sessionOwner(stores.store(id), parent) }
    DisposableEffect(stores, id) {
        onDispose { if (activity?.isChangingConfigurations != true) stores.end(id) }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

/** [store], with [parent]'s default factory and extras when it has them (the activity's). */
private fun sessionOwner(store: ViewModelStore, parent: ViewModelStoreOwner): ViewModelStoreOwner {
    val defaults = parent as? HasDefaultViewModelProviderFactory ?: return object : ViewModelStoreOwner {
        override val viewModelStore = store
    }
    return object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
        override val viewModelStore = store
        override val defaultViewModelProviderFactory: ViewModelProvider.Factory get() = defaults.defaultViewModelProviderFactory
        override val defaultViewModelCreationExtras: CreationExtras get() = defaults.defaultViewModelCreationExtras
    }
}
