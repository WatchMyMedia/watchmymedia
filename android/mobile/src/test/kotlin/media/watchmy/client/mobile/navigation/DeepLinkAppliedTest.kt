package media.watchmy.client.mobile.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.mobile.MobileApp
import media.watchmy.client.ui.LocalAppServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A watchmymedia:// link the phone app is opened with while signed out
 * waits through sign-in, opens once someone is signed in, and is used up:
 * Back, or signing out and in again, doesn't bring it back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class DeepLinkAppliedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val services = FakeAppServices(signedIn = false)

    /** A show that isn't on Home, so its title on screen means its page is open. */
    private val show = FakeData.home().let { home ->
        val onHome = (home.recentlyAddedShows.map { it.title } + home.continueWatching.map { it.title } + home.recentlyAired.map { it.showTitle }).toSet()
        FakeData.shows.first { it.title !in onHome && it.title != home.hero?.title }
    }

    @Before
    fun open() {
        fakeLatencyMs = 0
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) { MobileApp(deepLink = "watchmymedia://shows/${show.id}") }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a show link opened signed out lands on the show after sign-in, once`() {
        assertEquals("Signed out, the sign-in screen shows first", 0, count(show.title))
        signIn()
        assertTrue("The show's page opened", count(show.title) > 0)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals("Back goes to Home, under the link", 0, count(show.title))
        assertTrue(count("Continue watching") > 0)

        runBlocking { services.auth.signOut() }
        compose.waitForIdle()
        signIn()
        assertEquals("Signing in again doesn't reopen the link", 0, count(show.title))
        assertTrue(count("Continue watching") > 0)
    }

    private fun signIn() {
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("Sam")
        fields[1].performTextInput("password")
        compose.onAllNodes(hasText("Sign in") and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun count(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size
}
