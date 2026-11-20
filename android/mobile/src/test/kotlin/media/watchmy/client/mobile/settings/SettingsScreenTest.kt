package media.watchmy.client.mobile.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakePreferencesRepository
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.AppServices
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.ServerFeatures
import media.watchmy.client.model.SettingsGroup
import media.watchmy.client.model.settingsTheServerLacks
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings as groups on a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class SettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restoreLatency() {
        fakeLatencyMs = latency
    }

    /** The fake services with preferences that fail on demand. */
    private class Services(val fake: FakeAppServices = FakeAppServices()) : AppServices by fake {
        val prefs = FailingPreferences(FakePreferencesRepository(fake.servers.current))
        override val preferences: PreferencesRepository get() = prefs
    }

    private class FailingPreferences(private val fake: FakePreferencesRepository) : PreferencesRepository by fake {
        var refreshFailure: Exception? = null
        var saveFailure: Exception? = null

        override suspend fun refresh() {
            refreshFailure?.let { throw it }
            fake.refresh()
        }

        override suspend fun setAutoplayNext(enabled: Boolean) {
            saveFailure?.let { throw it }
            fake.setAutoplayNext(enabled)
        }
    }

    private fun show(services: AppServices = Services(), open: SettingsGroup? = null, onOpenPage: (SettingsGroup, Boolean) -> Unit = { _, _ -> }) {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                WatchMyMediaPhoneTheme {
                    SettingsScreen(
                        onSwitchServer = {}, onSignedOut = {}, onLinkTv = {}, onNavigate = {},
                        open = open, onOpenPage = onOpenPage,
                        onOpenRequests = {}, onOpenInbox = {}, inboxCount = 3,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `the overview lists the groups in the web's order, each saying what is chosen`() {
        show()
        compose.onNodeWithText("Signed in as", substring = true).assertExists()
        val order = listOf("Playback", "Audio & subtitles", "Downloads", "Account & devices", "Your requests", "About", "Inbox", "Server settings")
        val tops = order.map { title -> compose.onNode(hasText(title)).performScrollTo().getUnclippedBoundsInRoot().top }
        assertEquals("in order: $order", tops.sorted(), tops)
        compose.onNodeWithText("next episode plays by itself", substring = true).assertExists()
        compose.onNodeWithText("SERVER").assertExists()
        compose.onNodeWithText("3").assertExists()
    }

    @Test fun `a group's row opens its page over the overview`() {
        var opened: Pair<SettingsGroup, Boolean>? = null
        show(onOpenPage = { group, beside -> opened = group to beside })
        compose.onNodeWithText("Account & devices").performScrollTo().performClick()
        assertEquals(SettingsGroup.Account to false, opened)
    }

    @Test fun `a save that fails stays on its row until it is tried again`() {
        val services = Services()
        show(services, open = SettingsGroup.Playback)
        services.prefs.saveFailure = ApiException(500, "The server couldn’t save it.")
        compose.onNode(hasText("Autoplay next episode") and isToggleable()).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Couldn’t save, so it’s still on.", substring = true).assertExists()

        services.prefs.saveFailure = null
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Couldn’t save", substring = true).assertDoesNotExist()
    }

    @Test fun `until the account's settings load, its rows can't be changed and the card offers Try again`() {
        val services = Services()
        services.prefs.refreshFailure = ApiException(500, "The server had a problem")
        show(services, open = SettingsGroup.Playback)
        compose.onNodeWithText("Your account’s settings didn’t load").assertExists()
        compose.onNode(hasText("Autoplay next episode") and isToggleable()).assertIsNotEnabled()
        // This phone's own settings still work.
        compose.onNodeWithText("Default quality").assertExists()

        services.prefs.refreshFailure = null
        compose.onNodeWithText("Try again").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Your account’s settings didn’t load").assertDoesNotExist()
        compose.onNode(hasText("Autoplay next episode") and isToggleable()).assertIsEnabled()
    }

    @Test fun `signing out asks first, and says which downloads it deletes`() {
        show(open = SettingsGroup.Account)
        compose.onNodeWithText("Sign out of this phone…").performScrollTo().performClick()
        compose.onNodeWithText("Sign out of this phone?").assertExists()
        compose.onNodeWithText("DELETED FROM THIS PHONE").assertExists()
        compose.onNode(hasText("download", substring = true) and hasText("GB", substring = true) and hasText(",", substring = true)).assertExists()
        compose.onNodeWithText("Stay signed in").performClick()
        compose.onNodeWithText("Sign out of this phone?").assertDoesNotExist()
    }

    @Test fun `signing another device out asks, then says it was`() {
        show(open = SettingsGroup.Account)
        // The devices are listed this one first, then the most recently seen: Sam's Pixel.
        compose.onAllNodes(hasText("Sign out") and hasClickAction())[0].performScrollTo().performClick()
        compose.onNodeWithText("Sign out Sam's Pixel?").assertExists()
        compose.onNodeWithText("Sign out Sam's Pixel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sam's Pixel is signed out").assertExists()
        compose.onNodeWithText("Sign out Sam's Pixel?").assertDoesNotExist()
    }

    @Test fun `About says which settings an older server is too old for`() {
        assertEquals(listOf("Audio languages", "Home rows", "Notifications"), settingsTheServerLacks(ServerFeatures(20)))
        assertTrue(settingsTheServerLacks(ServerFeatures(99)).isEmpty())
    }
}
