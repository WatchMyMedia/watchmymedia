package media.watchmy.client.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A signed-in session's ViewModels live and end with it
 * (PLAN-ANDROID-REVIEW.md §3.3.6), on the phone and the TV alike: kept
 * while it shows, cleared when it signs out or another account or server
 * takes over, and never in the activity's own store, where they outlived
 * the session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionViewModelsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    class Probe : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    @Test fun `a session's ViewModels are its own, and end with it`() {
        var signedIn by mutableStateOf(true)
        var account by mutableIntStateOf(1)
        var tick by mutableIntStateOf(0)
        val probes = mutableListOf<Probe>()
        compose.setContent {
            if (signedIn) {
                key(account) {
                    SessionViewModels {
                        val probe: Probe = viewModel()
                        if (tick >= 0 && probes.lastOrNull() !== probe) probes += probe
                    }
                }
            }
        }
        compose.waitForIdle()
        tick++
        compose.waitForIdle()
        assertEquals("The same one while the session shows", 1, probes.size)
        val first = probes.single()
        assertNotSame("Not the activity's", ViewModelProvider(compose.activity)[Probe::class.java], first)

        account = 2
        compose.waitForIdle()
        assertTrue("Another account: the last one's are cleared", first.cleared)
        assertEquals(2, probes.size)
        val second = probes.last()
        assertFalse(second.cleared)

        signedIn = false
        compose.waitForIdle()
        assertTrue("Signed out: cleared", second.cleared)
        assertSame(second, probes.last())
    }

    class Saved(val handle: SavedStateHandle) : ViewModel()

    @Test fun `a session's ViewModel can ask for a SavedStateHandle, as the activity's could`() {
        var saved: Saved? = null
        compose.setContent {
            SessionViewModels { saved = viewModel { Saved(createSavedStateHandle()) } }
        }
        compose.waitForIdle()
        assertTrue(saved != null)
        assertEquals(1, ViewModelProvider.create(compose.activity, viewModelFactory { initializer { SessionStores() } })[SessionStores::class].open)
    }
}
