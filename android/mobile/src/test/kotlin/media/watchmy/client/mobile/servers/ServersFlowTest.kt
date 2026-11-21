package media.watchmy.client.mobile.servers

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.Cabin
import media.watchmy.client.fake.Den
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.fake.OfficeMini
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.mobile.MobileApp
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.TestTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Several servers on the phone, on the fakes: the Living room server in use,
 * the Cabin server with a library of its own, the Den server that never
 * answers, one signed out, and the Office Mac mini not added yet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class ServersFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L
    private val fake = FakeAppServices()
    private val current get() = fake.servers.current.value?.server?.id

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restoreLatency() {
        fakeLatencyMs = latency
    }

    private fun start() {
        compose.setContent { CompositionLocalProvider(LocalAppServices provides fake) { MobileApp() } }
        compose.waitForIdle()
    }

    /** Waits for [text] to be on screen: what a search, a page fading in, or a switch brings. */
    private fun waitFor(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The account button: the one in the top bar on a phone; on a tablet the last is the one at the rail's foot. */
    private fun openServers() {
        compose.onAllNodesWithTag(TestTags.ACCOUNT).onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.SERVERS).assertIsDisplayed()
    }

    @Test fun `Home names the server in use, and the account button opens Servers`() {
        start()
        // With several registered, the name under Home's title is a second way in.
        compose.onNodeWithContentDescription("On Living room server. Open Servers").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sam on Living room server. Open Servers").assertIsDisplayed()
        openServers()
        compose.onNodeWithText("Cabin server").assertIsDisplayed()
        compose.onNodeWithText("Sam · 192.168.4.31:27587").assertIsDisplayed()
        compose.onNodeWithText("Signed out here · sign in again").assertIsDisplayed()
        compose.onNodeWithText("Add a server").assertIsDisplayed()
        compose.onNodeWithText("Manage servers").assertIsDisplayed()
    }

    @Test fun `choosing a server switches, says so, and shows that server's Home`() {
        start()
        openServers()
        compose.onNodeWithText("Cabin server").performClick()
        compose.waitForIdle()
        assertEquals(Cabin.id, current)
        compose.onNodeWithText("Now on Cabin server").assertIsDisplayed()
        // Its own library: the Cabin server's hero, not the Living room server's.
        compose.onNodeWithText("Harbour Run").assertIsDisplayed()
        compose.onNodeWithContentDescription("On Cabin server. Open Servers").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Harbor Lights").fetchSemanticsNodes().isEmpty())
    }

    @Test fun `a server that doesn't answer leaves the phone where it was, with its ways on`() {
        start()
        openServers()
        compose.onNodeWithText("Den server").performClick()
        compose.waitForIdle()
        assertEquals(LivingRoom.id, current)
        compose.onNodeWithText("Couldn’t reach Den server").assertIsDisplayed()
        compose.onNodeWithText("It may be off, or only reachable on its own network. You’re still on Living room server.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
        // The phone holds downloads from it, so it can be switched to all the same.
        compose.onNodeWithText("Watch its 3 downloads").performClick()
        compose.waitForIdle()
        assertEquals(Den.id, current)
    }

    @Test fun `Add a server goes from the servers not added yet to the last step`() {
        start()
        openServers()
        compose.onNodeWithText("Add a server").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Step 1 of 3 · Choose a server · next: Sign in").assertIsDisplayed()
        compose.onNodeWithText("Which server?").assertIsDisplayed()
        compose.onNodeWithText("Already added: Living room server, Cabin server, Den server, home.watchmy.media.").assertIsDisplayed()
        // The Living room server is on this network too, and is not offered again.
        assertTrue(compose.onAllNodesWithText("Living room server").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Office Mac mini").performClick()
        compose.waitForIdle()

        assertEquals(OfficeMini.id, current)
        compose.onNodeWithContentDescription("Step 2 of 3 · Sign in · next: Added").assertIsDisplayed()
        compose.onNodeWithTag(TestTags.SIGN_IN_NAME).performTextInput("Sam")
        compose.onNodeWithTag(TestTags.SIGN_IN_PASSWORD).performTextInput("password")
        compose.onNodeWithTag(TestTags.SIGN_IN).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Step 3 of 3 · Added").assertIsDisplayed()
        compose.onNodeWithText("Office Mac mini is added").assertIsDisplayed()
        compose.onNodeWithText("You’re on it now, signed in as Sam.").assertIsDisplayed()
        compose.onNodeWithText("This phone is signed in to 4 servers. Search looks in all of them.").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("On Office Mac mini. Open Servers").assertIsDisplayed()
    }

    @Test fun `closing Add a server at its sign-in leaves the phone where it was`() {
        start()
        openServers()
        compose.onNodeWithText("Add a server").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Office Mac mini").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertEquals(LivingRoom.id, current)
        compose.onNodeWithContentDescription("On Living room server. Open Servers").assertIsDisplayed()
        assertFalse("not kept: no one signed in to it", runBlocking { fake.servers.saved.first() }.any { it.server.id == OfficeMini.id })
    }

    @Test fun `Manage servers opens Settings' Servers page, where a server is removed after a question`() {
        start()
        openServers()
        compose.onNodeWithText("Manage servers").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("This phone is signed in to each of these. Search looks in all of them.").assertIsDisplayed()
        compose.onNodeWithText("Sam · 192.168.4.31:27587 · v0.9.3").assertIsDisplayed()
        compose.onNodeWithText("Cabin server").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Downloads from it").assertIsDisplayed()
        compose.onNodeWithText("3 · 12.4 GB").assertIsDisplayed()
        compose.onNodeWithText("Switch to this server").assertIsDisplayed()
        compose.onNodeWithText("Remove this server…").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Remove Cabin server from this phone?").assertIsDisplayed()
        compose.onNodeWithText("This phone is signed out of it, and its 3 downloads here are deleted.").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()

        assertFalse(runBlocking { fake.servers.saved.first() }.any { it.server.id == Cabin.id })
        assertFalse("its downloads went with it", Cabin.id in fake.downloads.byServer.value)
        assertEquals("the server in use is untouched", LivingRoom.id, current)
        // Back on the list, without it.
        compose.onNodeWithText("This phone is signed in to each of these. Search looks in all of them.").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Cabin server").fetchSemanticsNodes().isEmpty())
        // Back from the list is Account & devices, where its row is.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNode(hasText("Servers") and hasText("Living room and 2 more")).assertExists()
    }

    @Test fun `search looks in every server, and a result on another opens there and comes back to the words`() {
        start()
        compose.onNode(hasContentDescription("Search") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Search your servers").assertIsDisplayed()
        compose.onNodeWithText("Find a show, a movie or an episode on any of your 3 servers.").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("harbor")
        waitFor("Harborside")

        // The server in use's first, with no name; the Cabin server's after, named.
        compose.onNodeWithText("Harbor Lights").assertIsDisplayed()
        compose.onNodeWithText("Harborside").assertIsDisplayed()
        compose.onAllNodesWithText("Cabin server").onFirst().assertIsDisplayed()

        compose.onNodeWithText("Harborside").performClick()
        // The switch is the app's own work, not the screen's: let it run.
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            current == Cabin.id
        }
        assertEquals("the phone is on the server that has it", Cabin.id, current)
        waitFor("Now on Cabin server")
        // The title's page, on the Cabin server.
        waitFor("Harborside")
        compose.onAllNodesWithText("Harborside").onFirst().assertIsDisplayed()

        // Back is Search with the same words, asked again: the rows name the server that is no longer in use.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("Living room server")
        compose.onNode(hasSetTextAction() and hasText("harbor")).assertIsDisplayed()
        compose.onAllNodesWithText("Living room server").onFirst().assertIsDisplayed()
        // At the end of the results: the server that didn't answer, and the one signed out here.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Den server didn’t answer, so its titles aren’t listed."))
        compose.onNodeWithText("Den server didn’t answer, so its titles aren’t listed.").assertIsDisplayed()
        compose.onNodeWithText("You’re signed out of home.watchmy.media here, so its titles aren’t listed.").assertExists()

        // Back again is the Cabin server's Home.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("On Cabin server. Open Servers").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun `on a tablet the rail's account button opens Servers as a popover beside it, and a switch says where it went`() {
        start()
        // The rail has no room for the server's name: it is the label of the button at its foot.
        compose.onAllNodesWithContentDescription("Sam on Living room server. Open Servers").onLast().assertIsDisplayed()
        openServers()
        val popover = compose.onNodeWithTag(TestTags.SERVERS).fetchSemanticsNode().boundsInRoot
        assertEquals("384 wide", 384f, popover.width / compose.density.density, 1f)
        compose.onNodeWithText("Cabin server").performClick()
        compose.waitForIdle()
        assertEquals(Cabin.id, current)
        compose.onNodeWithText("Now on Cabin server").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Sam on Cabin server. Open Servers").onLast().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun `on a tablet the Servers pages open beside Settings' list, each with its way back`() {
        start()
        openServers()
        compose.onNodeWithText("Manage servers").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("This tablet is signed in to each of these. Search looks in all of them.").assertIsDisplayed()
        // Settings' list stays beside it, on the group the page belongs to.
        compose.onNode(hasText("Account & devices") and isSelectable()).assertIsSelected()
        compose.onNodeWithText("Sam · 192.168.4.31:27587 · v0.9.3").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Switch to this server").assertIsDisplayed()
        compose.onNodeWithText("Remove this server…").assertIsDisplayed()
        // Up from a server's page is the list; up from the list is Account & devices.
        compose.onNode(hasText("Servers") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("This tablet is signed in to each of these. Search looks in all of them.").assertIsDisplayed()
        compose.onNode(hasText("Account & devices") and hasClickAction() and !isSelectable()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sign out of this tablet…").assertExists()
        compose.onNodeWithText("Living room and 3 more", substring = true).assertExists()
    }
}
