package media.watchmy.client.mobile

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.ui.LocalAppServices
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The signed-in screens' ViewModels belong to the account (PLAN-ANDROID-REVIEW
 * §3.3.6): signing out, or in as someone else, clears the last account's
 * rather than leaving them in the activity until it finishes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountScopeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restoreLatency() {
        fakeLatencyMs = latency
    }

    private class Marker : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    private fun ViewModelStore.marker() = ViewModelProvider.create(this, viewModelFactory { initializer { Marker() } })[Marker::class]

    @Test fun `the same account keeps its store, and another's clears the last once it settles`() {
        val stores = AccountStores()
        val sam = stores.storeFor("server" to 1)
        val marker = sam.marker()
        assertSame(sam, stores.storeFor("server" to 1))

        val alex = stores.storeFor("server" to 2)
        assertNotSame(sam, alex)
        // Sam's screens are still going away: their ViewModels stay until then.
        assertFalse(marker.cleared)
        stores.settle()
        assertTrue(marker.cleared)
        assertSame(alex, stores.current)
    }

    @Test fun `signing out on the phone clears the account's ViewModels, and signing in again starts afresh`() {
        val fake = FakeAppServices()
        compose.setContent { CompositionLocalProvider(LocalAppServices provides fake) { MobileApp() } }
        compose.waitForIdle()
        val stores = ViewModelProvider.create(compose.activity, viewModelFactory { initializer { AccountStores() } })[AccountStores.KEY, AccountStores::class]
        val signedIn = checkNotNull(stores.current) { "signed in, with a store" }
        val marker = signedIn.marker()

        runBlocking { fake.auth.signOut() }
        compose.waitForIdle()
        assertTrue("the last account's ViewModels are cleared", marker.cleared)
        assertNull(stores.current)

        runBlocking { fake.auth.signIn("Alex", "password") }
        compose.waitForIdle()
        assertNotSame(signedIn, stores.current)
        assertFalse(stores.current!!.marker().cleared)
    }
}
