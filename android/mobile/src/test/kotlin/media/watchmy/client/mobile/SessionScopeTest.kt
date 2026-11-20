package media.watchmy.client.mobile

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.SessionStores
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's signed-in screens keep their ViewModels in a session of their
 * own (core:ui SessionViewModels): signing out
 * ends it, and the next account starts a new one. The mechanism itself is
 * tested in core:ui.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class SessionScopeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restoreLatency() {
        fakeLatencyMs = latency
    }

    private fun open() = ViewModelProvider.create(compose.activity, viewModelFactory { initializer { SessionStores() } })[SessionStores::class].open

    @Test fun `signing out on the phone ends the session's ViewModels, and the next account starts its own`() {
        val fake = FakeAppServices()
        compose.setContent { CompositionLocalProvider(LocalAppServices provides fake) { MobileApp() } }
        compose.waitForIdle()
        assertEquals("signed in: one session", 1, open())

        runBlocking { fake.auth.signOut() }
        compose.waitForIdle()
        assertEquals("signed out: its ViewModels are cleared", 0, open())

        runBlocking { fake.auth.signIn("Alex", "password") }
        compose.waitForIdle()
        assertEquals("another account: a session of its own", 1, open())
    }
}
