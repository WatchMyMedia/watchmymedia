package media.watchmy.client.tv.settings

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.AppServices
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.SkipMode
import media.watchmy.client.tv.TvApp
import media.watchmy.client.tv.quietTvFakes
import media.watchmy.client.ui.LocalAppServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.IOException

/**
 * Settings as groups on the D-pad (PLAN-ANDROID-SETTINGS.md §4.3, §8,
 * §12), on the fakes at a 1080p TV's size: where focus starts, moving
 * through the groups swapping the pane, ▶ into it and ◀ or Back out of it,
 * focus after a panel closes and after a device is signed out,
 * confirmations starting on the button that changes nothing, and Back
 * never held up.
 *
 * The fakes' groups: Playback, Audio & subtitles, Home rows, Notifications,
 * Account & devices, Your requests, About, then Inbox and Server settings
 * (Sam is an admin).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SettingsFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var preferences: FlakyPreferences

    /** Opens Settings on [services] (the quiet fakes when null), and checks that focus starts on its first group. */
    private fun start(services: AppServices? = null) {
        fakeLatencyMs = 0
        val fakes = quietTvFakes()
        preferences = FlakyPreferences(fakes.preferences)
        val shown = services ?: object : AppServices by fakes {
            override val preferences: PreferencesRepository = this@SettingsFocusTest.preferences
        }
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides shown) { TvApp() }
        }
        compose.waitForIdle()
        openSettings()
        assertEquals("Settings opens on its first group", "Playback", focused())
    }

    @Test
    fun `moving down the groups swaps the pane, with no press`() {
        start()
        compose.onNodeWithText("Default quality").assertExists()
        press(Key.DirectionDown)
        assertEquals("Audio & subtitles", focused())
        compose.onNodeWithText("Default quality").assertDoesNotExist()
        compose.onNodeWithText("Audio languages").assertExists()
        press(Key.DirectionDown)
        assertEquals("Home rows", focused())
        compose.onNodeWithText("Reset Home rows").assertExists()
        press(Key.DirectionDown)
        assertEquals("Notifications", focused())
        compose.onNodeWithText("Shows you follow".uppercase()).assertExists()
        press(Key.DirectionDown)
        assertEquals("Account & devices", focused())
        compose.onNodeWithText("Device name").assertExists()
    }

    @Test
    fun `right goes into the pane on its top row, and back on the row left last time`() {
        start()
        press(Key.DirectionRight)
        assertEquals("▶ lands on the pane's top row", "Default quality", focused())
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        assertEquals("Audio passthrough", focused())
        press(Key.DirectionLeft)
        assertEquals("◀ goes back to the group", "Playback", focused())
        groupIsOpen("Playback")
        press(Key.DirectionRight)
        assertEquals("▶ again lands on the row left", "Audio passthrough", focused())

        back()
        assertEquals("Back from the pane goes to the group", "Playback", focused())
        press(Key.DirectionCenter)
        assertEquals("OK on the group goes into the pane too", "Audio passthrough", focused())
        compose.onNodeWithText("Default quality").assertExists()
    }

    @Test
    fun `up and down stay in the pane, and the group stays marked while focus is there`() {
        start()
        press(Key.DirectionRight)
        press(Key.DirectionUp)
        assertEquals("▲ at the top of the pane goes nowhere", "Default quality", focused())
        repeat(30) { press(Key.DirectionDown) }
        assertEquals("▼ at the foot stays in the pane", "Live TV reminders".takeIf { exists(it) } ?: "Skip ads", focused())
        groupIsOpen("Playback")
    }

    @Test
    fun `Back from the groups leaves Settings`() {
        start()
        press(Key.DirectionDown)
        back()
        compose.onNodeWithText("Signed in as Sam", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a skip row is one stop, and its panel saves, closes and puts focus back on the row`() {
        start()
        press(Key.DirectionRight)
        goTo("Skip intro")
        compose.onNode(hasText("Skip intro") and hasText("Ask")).assertExists()
        press(Key.DirectionDown)
        assertEquals("one stop a skip row", "Skip recap", focused())
        press(Key.DirectionUp)

        press(Key.DirectionCenter)
        compose.onNodeWithText("Shows a Skip button.").assertExists()
        compose.onNodeWithText("A part you seek into is never skipped for you.").assertExists()
        assertEquals("the panel opens on the mode chosen", "Ask", focused())
        press(Key.DirectionDown)
        assertEquals("Auto", focused())
        press(Key.DirectionCenter)

        compose.onNodeWithText("Shows a Skip button.").assertDoesNotExist()
        assertEquals("focus is back on the row", "Skip intro", focused())
        compose.onNode(hasText("Skip intro") and hasText("Auto")).assertExists()
    }

    @Test
    fun `a failed save stays on its row, and OK tries it again`() {
        start()
        preferences.failSkip = true
        press(Key.DirectionRight)
        goTo("Skip intro")
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertEquals("Skip intro", focused())
        compose.onNodeWithText("Couldn’t save, so it’s still Ask. Check your connection. Press OK to try again.").assertExists()

        preferences.failSkip = false
        press(Key.DirectionCenter)
        compose.onNodeWithText("Press OK to try again.", substring = true).assertDoesNotExist()
        compose.onNode(hasText("Skip intro") and hasText("Auto")).assertExists()
        assertEquals("Skip intro", focused())
    }

    @Test
    fun `when the account's settings didn't load, a row says so and OK loads them again`() {
        val fakes = quietTvFakes()
        val flaky = FlakyPreferences(fakes.preferences).apply { failRefresh = true }
        start(object : AppServices by fakes { override val preferences: PreferencesRepository = flaky })
        compose.onNodeWithText("Your account’s settings didn’t load").assertExists()
        compose.onNode(hasText("Autoplay next episode")).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        press(Key.DirectionRight)
        assertEquals("▶ lands on the row that says so", "Your account’s settings didn’t load", focused())
        flaky.failRefresh = false
        press(Key.DirectionCenter)
        compose.onNodeWithText("Your account’s settings didn’t load").assertDoesNotExist()
        compose.onNode(hasText("Autoplay next episode")).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
        assertEquals("focus moves to the row now at the top", "Default quality", focused())
    }

    @Test
    fun `signing a device out asks first, from its safe button, and focus moves to the next device`() {
        start()
        goToGroup("Account & devices")
        press(Key.DirectionRight)
        assertEquals("Device name", focused())
        press(Key.DirectionDown)
        assertEquals("this TV's row is not a stop", "Sam's Pixel", focused())
        press(Key.DirectionCenter)
        compose.onNodeWithText("Sign out Sam's Pixel?").assertExists()
        assertEquals("the confirmation starts on the button that changes nothing", "Keep it signed in", focused())

        press(Key.DirectionCenter)
        compose.onNodeWithText("Sign out Sam's Pixel?").assertDoesNotExist()
        assertEquals("Keep it signed in puts focus back on the row", "Sam's Pixel", focused())

        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        assertEquals("Sign out Sam's Pixel", focused())
        press(Key.DirectionCenter)
        compose.onNodeWithText("Sign out Sam's Pixel?").assertDoesNotExist()
        compose.onNodeWithText("Sam's Pixel").assertDoesNotExist()
        assertEquals("focus moves to the next device", "Chrome on macOS", focused())
    }

    @Test
    fun `signing this TV out asks first, and Back is never held up`() {
        start()
        goToGroup("Account & devices")
        press(Key.DirectionRight)
        goTo("Sign out of this TV…")
        press(Key.DirectionCenter)
        compose.onNodeWithText("Sign out of this TV?").assertExists()
        compose.onNodeWithText("Your account on", substring = true).assertExists()
        assertEquals("Stay signed in", focused())

        backInDialog()
        compose.onNodeWithText("Sign out of this TV?").assertDoesNotExist()
        assertEquals("Back closed it, signed in, back on its row", "Sign out of this TV…", focused())
        back()
        assertEquals("Account & devices", focused())
        back()
        compose.onNodeWithText("Signed in as Sam", substring = true).assertDoesNotExist()
    }

    @Test
    fun `Reset rows asks first, from Keep my rows`() {
        start()
        goToGroup("Home rows")
        press(Key.DirectionRight)
        assertEquals("Screen", focused())
        goTo("Reset Home rows")
        press(Key.DirectionCenter)
        compose.onNodeWithText("Reset Home’s rows?").assertExists()
        compose.onNodeWithText("Your order, and which rows you hid.").assertExists()
        assertEquals("Keep my rows", focused())
        press(Key.DirectionCenter)
        compose.onNodeWithText("Reset Home’s rows?").assertDoesNotExist()
        assertEquals("Reset Home rows", focused())
    }

    @Test
    fun `Change password and Server settings hand off to a phone`() {
        start()
        goToGroup("Server settings")
        compose.onNodeWithText("/settings", substring = true).assertExists()
        press(Key.DirectionRight)
        assertEquals("a pane without rows keeps focus on its group", "Server settings", focused())

        goToGroup("Account & devices")
        press(Key.DirectionRight)
        goTo("Change password")
        press(Key.DirectionCenter)
        compose.onNodeWithText("/settings/account", substring = true).assertExists()
        compose.onNodeWithText("Changing it signs this TV out too", substring = true).assertExists()
        backInDialog()
        compose.onNodeWithText("Changing it signs this TV out too", substring = true).assertDoesNotExist()
        assertEquals("Change password", focused())
    }

    @Test
    fun `Back from Your requests puts focus back on its row`() {
        start()
        goToGroup("Your requests")
        press(Key.DirectionRight)
        assertEquals("See your requests", focused())
        press(Key.DirectionCenter)
        compose.onNodeWithText("Signed in as Sam", substring = true).assertDoesNotExist()

        back()
        compose.onNodeWithText("Signed in as Sam", substring = true).assertExists()
        assertEquals("Back in Settings, the row has focus again", "See your requests", focused())
        press(Key.DirectionLeft)
        assertEquals("Your requests", focused())
    }

    /** Opens the rail, goes down it to Settings and opens it. */
    @Test
    fun `Notifications lists the shows followed, OK opens a show's panel and Stop notifying moves focus to the next`() {
        start()
        goToGroup("Notifications")
        compose.onNodeWithText("Your phone or tablet notifies you. This TV shows new episodes on Home.").assertExists()
        press(Key.DirectionRight)
        assertEquals("Harbor Lights", focused())
        press(Key.DirectionDown)
        assertEquals("Copperline", focused())
        press(Key.DirectionCenter)
        assertEquals("the panel starts on Go to show", "Go to show", focused())
        compose.onNodeWithText("Back closes this panel.").assertExists()
        press(Key.DirectionDown)
        assertEquals("Stop notifying", focused())
        press(Key.DirectionCenter)
        assertTrue("the panel closed and the row went", !exists("Go to show") && !exists("Copperline"))
        assertEquals("focus is on the next show", "Ashgrove", focused())
        compose.onNodeWithText("Not in the library now").assertExists()
        press(Key.DirectionCenter)
        assertTrue("a show no longer in the library has only Stop notifying", !exists("Go to show") && exists("Stop notifying"))
        backInDialog()
        assertEquals("Back closes the panel and keeps the show", "Ashgrove", focused())
    }

    private fun openSettings() {
        press(Key.DirectionLeft)
        repeat(8) { if (focused() != "Settings") press(Key.DirectionDown) }
        assertEquals("Settings", focused())
        press(Key.DirectionCenter)
    }

    /** Up the groups to the top, then down them to [group]. */
    private fun goToGroup(group: String) {
        repeat(10) { if (focused() != group) press(Key.DirectionUp) }
        repeat(10) { if (focused() != group) press(Key.DirectionDown) }
        assertEquals(group, focused())
    }

    /** Down the open pane to [row]. */
    private fun goTo(row: String) {
        repeat(30) { if (focused() != row) press(Key.DirectionDown) }
        assertEquals(row, focused())
    }

    private fun exists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun groupIsOpen(group: String) {
        compose.onNode(hasText(group) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertExists()
    }

    /**
     * The focused element's title: a row's label, a button's or a rail
     * item's name. A panel's or a confirmation's when one is open, since the
     * screen under it keeps its own focused row.
     */
    private fun focused(): String {
        val inDialog = compose.onAllNodes(isFocused() and hasAnyAncestor(isDialog())).fetchSemanticsNodes()
        val node = inDialog.firstOrNull() ?: compose.onNode(isFocused() and !hasAnyAncestor(isDialog())).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull { it.text.isNotBlank() && it.text.any(Char::isLetter) }?.text
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            ?: error("The focused element has no text")
    }

    /** A remote key, to the panel or confirmation when one has focus, else the screen. */
    private fun press(key: Key) {
        val inDialog = compose.onAllNodes(isFocused() and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
        val target = if (inDialog) compose.onNode(isFocused() and hasAnyAncestor(isDialog())) else compose.onAllNodes(isRoot()).onFirst()
        target.performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Back in the open panel or confirmation, which has a window of its own. */
    private fun backInDialog() {
        val dialog = ShadowDialog.getLatestDialog() as? ComponentDialog
        assertNotNull("a panel is open", dialog)
        compose.runOnUiThread { dialog!!.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** The fakes' preferences, with failures on demand: skip changes, and loading the account's settings. */
    private class FlakyPreferences(private val real: PreferencesRepository) : PreferencesRepository by real {
        var failSkip = false
        var failRefresh = false

        override suspend fun setSkip(kind: SegmentKind, mode: SkipMode) {
            if (failSkip) throw unreachable()
            real.setSkip(kind, mode)
        }

        override suspend fun refresh() {
            if (failRefresh) throw unreachable()
            real.refresh()
        }

        private fun unreachable() = ApiException(null, "Can't reach the server.", IOException("offline"))
    }
}

