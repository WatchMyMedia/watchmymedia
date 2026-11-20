package media.watchmy.client.mobile.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import java.io.IOException
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeAuthRepository
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.AppServices
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Change password on a phone and a tablet, on the fakes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class ChangePasswordScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L
    private var closed = 0
    private var changed: Int? = null

    /** The fake sign-in ("password" is the current password), with a failure on demand. */
    private class StubAuth(private val fake: FakeAuthRepository = FakeAuthRepository()) : AuthRepository by fake {
        var changeFailure: Exception? = null

        override suspend fun changePassword(current: String, new: String) {
            changeFailure?.let { throw it }
            fake.changePassword(current, new)
        }
    }

    @Before fun setup() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun teardown() {
        fakeLatencyMs = latency
    }

    private fun open(asDialog: Boolean = false, auth: AuthRepository = StubAuth()) {
        val services: AppServices = object : AppServices by FakeAppServices() {
            override val auth = auth
        }
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                WatchMyMediaPhoneTheme {
                    ChangePasswordScreen(onClose = { closed++ }, onChanged = { changed = it }, asDialog = asDialog)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun field(label: String): SemanticsNodeInteraction = compose.onNode(hasSetTextAction() and hasText(label))

    private fun type(current: String, new: String) {
        field("Current password").performTextInput(current)
        field("New password").performTextInput(new)
    }

    @Test
    fun `opens on the current password, with the devices it signs out and the button off`() {
        open()
        field("Current password").assertIsFocused()
        compose.onNodeWithText("This phone stays signed in.", substring = true).assertExists()
        compose.onNodeWithText("Signs out 2 other devices", ignoreCase = true).assertExists()
        compose.onNodeWithText("Sam's Pixel").assertExists()
        compose.onNodeWithText("Chrome on macOS").assertExists()
        compose.onNodeWithText("Enter your current password and a new one.").assertExists()
        compose.onNodeWithText("Change password and sign out 2 devices").assertIsNotEnabled()
        compose.onNodeWithText("8 to 256 characters. Typed once: tap the eye to check it.").assertExists()
    }

    @Test
    fun `a changed password reports the devices it signed out`() {
        open()
        type("password", "lighthouse-2026")
        compose.onNodeWithText("Change password and sign out 2 devices").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(2, changed)
    }

    @Test
    fun `Done on the new password changes it`() {
        open()
        type("password", "lighthouse-2026")
        field("New password").performImeAction()
        compose.waitForIdle()
        assertEquals(2, changed)
    }

    @Test
    fun `a wrong current password says so under it and focuses it`() {
        open()
        type("harbour-12", "lighthouse-2026")
        compose.onNodeWithText("Change password and sign out 2 devices").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("That password is wrong. Nothing was changed.", useUnmergedTree = true).assertExists()
        field("Current password").assertIsFocused()
        assertNull(changed)
    }

    @Test
    fun `a short new password says so under it and the button waits for a change`() {
        open()
        type("password", "short")
        compose.onNodeWithText("Change password and sign out 2 devices").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Use at least 8 characters.", useUnmergedTree = true).assertExists()
        field("New password").assertIsFocused()
        compose.onNodeWithText("Change password and sign out 2 devices").assertIsNotEnabled()
        field("New password").performTextInput("-and-longer")
        compose.onNodeWithText("Change password and sign out 2 devices").assertIsEnabled()
    }

    @Test
    fun `an unreachable server is said above the button, which tries again`() {
        val auth = StubAuth().apply { changeFailure = ApiException(null, "Couldn't reach the server.", IOException("offline")) }
        open(auth = auth)
        type("password", "lighthouse-2026")
        compose.onNodeWithText("Change password and sign out 2 devices").performClick()
        compose.waitForIdle()
        val note = compose.onNodeWithContentDescription("Couldn’t reach Living room server. Your password wasn’t changed. Check your connection, then try again.")
        note.assertExists().assertIsFocused()
        auth.changeFailure = null
        compose.onNodeWithText("Try again").performClick()
        compose.waitForIdle()
        assertEquals(2, changed)
    }

    @Test
    fun `too many tries is said above the button and holds it off`() {
        open(auth = StubAuth().apply { changeFailure = ApiException(429, "Too many attempts.") })
        type("password", "lighthouse-2026")
        compose.onNodeWithText("Change password and sign out 2 devices").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Too many tries. Nothing was changed. Try again in a few minutes.").assertIsFocused()
        compose.onNodeWithText("Change password and sign out 2 devices").assertIsNotEnabled()
    }

    @Test
    fun `Close with nothing typed just closes`() {
        open()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun `Close with something typed asks first, and Keep editing stays`() {
        open()
        field("New password").performTextInput("light")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Leave without changing your password?").assertExists()
        compose.onNodeWithText("Keep editing").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Leave without changing your password?").assertDoesNotExist()
        assertEquals(0, closed)
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Leave").performClick()
        compose.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun `the system's Back with something typed asks first`() {
        open()
        field("Current password").performTextInput("harbour")
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Leave without changing your password?").assertExists()
        assertEquals(0, closed)
    }

    @Test
    fun `the eye shows what was typed`() {
        open()
        field("New password").performTextInput("lighthouse-2026")
        compose.onNodeWithContentDescription("Show new password").performClick()
        compose.onNodeWithContentDescription("Hide new password").assertExists()
        compose.onNodeWithContentDescription("Show current password").assertExists()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun `on a tablet the devices are a sentence and Cancel sits beside the button`() {
        open(asDialog = true)
        compose.onNodeWithText("This tablet stays signed in.", substring = true).assertExists()
        compose.onNodeWithText("Sam's Pixel and Chrome on macOS.").assertExists()
        compose.onNodeWithText("8 to 256 characters. Typed once: use the eye to check it.").assertExists()
        field("Current password").performTextInput("harbour")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Leave").performClick()
        compose.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun `names read as a sentence`() {
        assertEquals("Living room TV.", namesSentence(listOf("Living room TV")))
        assertEquals("Living room TV and Kitchen iPad.", namesSentence(listOf("Living room TV", "Kitchen iPad")))
        assertEquals("Living room TV, Kitchen iPad and Bedroom Apple TV.", namesSentence(listOf("Living room TV", "Kitchen iPad", "Bedroom Apple TV")))
    }
}
