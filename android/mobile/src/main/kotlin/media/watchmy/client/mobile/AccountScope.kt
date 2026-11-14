package media.watchmy.client.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * The ViewModels of one signed-in account (PLAN-ANDROID-REVIEW §3.3.6).
 * Held by the activity, so they live through recreation like any other,
 * and cleared when the account is left: signed out, or another account or
 * server signed in. Without this the signed-in screens' ViewModels, and
 * the NavHost's store of its pages' ones, sat in the activity's store
 * until it finished, the old account's library and channels with them.
 */
internal class AccountStores : ViewModel() {
    private var account: Any? = null

    /** The store of the account signed in now; null while no one is. */
    var current: ViewModelStore? = null
        private set

    /** Stores of accounts left while their screens may still be on screen, cleared by [settle]. */
    private val left = mutableListOf<ViewModelStore>()

    /** [account]'s store. The previous account's is cleared at the next [settle], once its screens have gone. */
    fun storeFor(account: Any): ViewModelStore {
        current?.takeIf { this.account == account }?.let { return it }
        current?.let { left += it }
        this.account = account
        return ViewModelStore().also { current = it }
    }

    /** Clears the stores of the accounts left: the composition that used them has been disposed. */
    fun settle() {
        left.forEach { it.clear() }
        left.clear()
    }

    /** No one is signed in: the last account's ViewModels go. */
    fun leave() {
        current?.let { left += it }
        current = null
        account = null
        settle()
    }

    override fun onCleared() = leave()

    companion object {
        const val KEY = "media.watchmy.client.mobile.AccountStores"
    }
}

/** The activity's [AccountStores]. */
@Composable
internal fun rememberAccountStores(): AccountStores = viewModel(key = AccountStores.KEY) { AccountStores() }

/** [content] with its ViewModels kept in [account]'s store rather than the activity's. */
@Composable
internal fun AccountViewModels(stores: AccountStores, account: Any, content: @Composable () -> Unit) {
    val parent = checkNotNull(LocalViewModelStoreOwner.current) { "AccountViewModels needs a ViewModelStoreOwner" }
    val owner = remember(stores, account, parent) {
        val store = stores.storeFor(account)
        // The activity's factory and extras (its application, saved state and intent), with this account's store.
        val defaults = parent as? HasDefaultViewModelProviderFactory
        if (defaults != null) {
            object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                override val viewModelStore = store
                override val defaultViewModelProviderFactory: ViewModelProvider.Factory get() = defaults.defaultViewModelProviderFactory
                override val defaultViewModelCreationExtras: CreationExtras get() = defaults.defaultViewModelCreationExtras
            }
        } else {
            object : ViewModelStoreOwner {
                override val viewModelStore = store
            }
        }
    }
    // Runs after the previous account's screens were disposed, so their ViewModels go only once nothing draws them.
    DisposableEffect(owner) {
        stores.settle()
        onDispose {}
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
