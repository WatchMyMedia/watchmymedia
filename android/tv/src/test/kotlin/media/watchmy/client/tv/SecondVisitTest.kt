package media.watchmy.client.tv

import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.fake.OfficeMini
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.AppServices
import media.watchmy.client.model.DiscoveredServer
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.tv.servers.ServerPickerScreen
import media.watchmy.client.tv.signin.SignInScreen
import media.watchmy.client.ui.ProvideAppServices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The picker and sign-in are shown outside the nav host, so they come and go
 * many times in one run of the app: each visit must start afresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecondVisitTest {
    @get:Rule val compose = createComposeRule()

    private var latency = 0L

    @Before fun setUp() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun tearDown() {
        fakeLatencyMs = latency
    }

    @Test fun `Switch server a second time waits for a choice, and looking for servers stops in between`() {
        val looking = AtomicInteger()
        val fake = FakeServerRepository(signedIn = true)
        val servers = object : ServerRepository by fake {
            override val discovered: Flow<List<DiscoveredServer>> = flow {
                emit(listOf(LivingRoom, OfficeMini).map { DiscoveredServer(it.id, it.name, it.baseUrl, it.version, it.apiLevel) })
                awaitCancellation()
            }.onStart { looking.incrementAndGet() }.onCompletion { looking.decrementAndGet() }
        }
        val services = object : AppServices by FakeAppServices(signedIn = true, tv = true) {
            override val servers = servers
        }
        var picking by mutableStateOf(true)
        var chosen = 0
        compose.setContent {
            ProvideAppServices(services) {
                TvTheme { if (picking) ServerPickerScreen(onServerChosen = { chosen++; picking = false }, onBack = { picking = false }) }
            }
        }
        compose.waitForIdle()
        assertEquals(1, looking.get())
        compose.onNodeWithText("Office Mac mini").performClick()
        compose.waitUntil(5_000) { chosen == 1 }
        compose.waitForIdle()
        assertEquals(OfficeMini.id, fake.current.value?.server?.id)
        assertEquals("not looking while the picker is gone", 0, looking.get())

        compose.runOnIdle { picking = true }
        compose.waitForIdle()
        compose.onNodeWithText("Choose a server").assertExists()
        assertEquals("the second visit stays open", 1, chosen)
        assertEquals(1, looking.get())
    }

    @Test fun `after signing out, sign-in shows a new code rather than the accepted one`() {
        val services = FakeAppServices(signedIn = false, tv = true)
        var shown by mutableStateOf(true)
        compose.setContent { ProvideAppServices(services) { TvTheme { if (shown) SignInScreen(onSwitchServer = {}) } } }
        compose.waitForIdle()
        compose.onNodeWithText("Waiting for the code", substring = true).assertExists()
        // The fake approves the code after 8 s.
        shadowOf(Looper.getMainLooper()).idleFor(9, TimeUnit.SECONDS)
        compose.waitForIdle()
        compose.onNodeWithText("Code accepted. Signing in…").assertExists()
        assertEquals("Sam", services.auth.user.value?.name)

        // Signed in, the app moves on; then signs out and comes back here.
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        runBlocking { services.auth.signOut() }
        compose.runOnIdle { shown = true }
        compose.waitForIdle()
        compose.onNodeWithText("Code accepted. Signing in…").assertDoesNotExist()
        compose.onNodeWithText("Waiting for the code", substring = true).assertExists()
    }
}
