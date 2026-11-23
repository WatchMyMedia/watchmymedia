package media.watchmy.client.tv.servers

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.Cabin
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.fake.RemoteHome
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.tv.TvApp
import media.watchmy.client.tv.quietTvFakes
import media.watchmy.client.ui.LocalAppServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/**
 * Several servers on the TV with the remote, on the fakes: the Living room
 * server in use, the Cabin server with a library of its own, the Den server
 * that never answers, and one signed out.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ServersTvTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var services: FakeAppServices
    private val current get() = services.servers.current.value?.server?.id

    private fun start(fakes: FakeAppServices? = null) {
        fakeLatencyMs = 0
        services = fakes ?: quietTvFakes()
        compose.setContent { CompositionLocalProvider(LocalAppServices provides services) { TvApp() } }
        compose.waitForIdle()
        assertHomeOpened()
    }

    /** Home has opened, with focus on its first row's card. */
    private fun assertHomeOpened() {
        compose.onNodeWithText("Row 1 of ", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(isFocused()).assertIsDisplayed()
        assertTrue("${focused()} is a card, not the rail", focused() !in listOf("Home", "Search", "Movies", "TV Shows", "Live TV", "Settings"))
    }

    /** ◀ to the rail, then down to the account button at its foot. */
    private fun toAccountButton() {
        press(Key.DirectionLeft)
        repeat(8) { if (!focused().startsWith("Servers")) press(Key.DirectionDown) }
        assertTrue("${focused()} is the account button", focused().startsWith("Servers"))
    }

    @Test fun `the account button takes focus, OK opens Servers, and Back closes it with focus back on the button`() {
        start()
        toAccountButton()
        assertEquals("the rail has no room for the server's name, so the button says it", "Servers. Sam, on Living room server", focused())
        press(Key.DirectionCenter)
        compose.onNodeWithText("Manage servers").assertExists()
        // Each row says what OK does there, not its address.
        compose.onNodeWithText("You are here").assertExists()
        compose.onNodeWithText("Signed out here · OK signs in again").assertExists()
        compose.onNodeWithText("Sam · 192.168.4.31:27587").assertDoesNotExist()
        assertEquals("focus opens on the first server that isn't the one in use", "Cabin server", focused())
        press(Key.DirectionUp)
        assertEquals("the one in use keeps its place in the path, and OK on it does nothing", "Living room server", focused())
        press(Key.DirectionCenter)
        assertEquals(LivingRoom.id, current)

        backInDialog()
        compose.onNodeWithText("Manage servers").assertDoesNotExist()
        assertEquals("Servers. Sam, on Living room server", focused())
    }

    @Test fun `OK on a server switches, with the banner, and Home is that server's`() {
        start()
        toAccountButton()
        press(Key.DirectionCenter)
        press(Key.DirectionCenter)
        assertEquals(Cabin.id, current)
        compose.onNodeWithText("Manage servers").assertDoesNotExist()
        compose.onNodeWithText("Now on Cabin server").assertExists()
        // Its own library, and focus where Home opens: the banner takes none.
        // The Cabin server's Home: its Continue watching opens it.
        assertTrue(compose.onAllNodesWithText("Pine Ledger").fetchSemanticsNodes().isNotEmpty())
        assertHomeOpened()
        // It goes by itself.
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS)
        compose.waitForIdle()
        compose.onNodeWithText("Now on Cabin server").assertDoesNotExist()
    }

    @Test fun `a server that doesn't answer leaves the TV where it was, with focus on Try again`() {
        start()
        toAccountButton()
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        assertEquals("Den server", focused())
        press(Key.DirectionCenter)
        compose.onNodeWithText("Couldn’t reach Den server", substring = true).assertExists()
        compose.onNodeWithText("It may be off, or only reachable on its own network. You’re still on Living room server.", substring = true).assertExists()
        assertEquals("Try again", focused())
        assertEquals(LivingRoom.id, current)
        // A TV holds no downloads, so there is nothing else to offer.
        assertTrue(compose.onAllNodesWithText("Watch its", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun `with one server registered, focus opens on Add a server`() {
        val alone = quietTvFakes(several = false)
        runBlocking { alone.servers.forget(RemoteHome.id) }
        start(alone)
        toAccountButton()
        press(Key.DirectionCenter)
        assertEquals("Add a server", focused())
    }

    @Test fun `Add a server offers the servers this TV isn't signed in to, and Back leaves it where it was`() {
        start()
        toAccountButton()
        press(Key.DirectionCenter)
        repeat(6) { if (focused() != "Add a server") press(Key.DirectionDown) }
        press(Key.DirectionCenter)
        compose.onNodeWithText("Already added: Living room server, Cabin server, Den server, home.watchmy.media.").assertExists()
        compose.onNodeWithText("Office Mac mini").assertExists()
        assertTrue("the Living room server is on this network, and is not offered again", compose.onAllNodesWithText("Living room server").fetchSemanticsNodes().isEmpty())
        back()
        assertEquals(LivingRoom.id, current)
        assertTrue(compose.onAllNodesWithText(FakeData.home().continueWatching.first().title).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun `Settings' Servers row opens the list, a server's page, and Remove asks from Keep`() {
        start()
        press(Key.DirectionLeft)
        repeat(8) { if (focused() != "Settings") press(Key.DirectionDown) }
        press(Key.DirectionCenter)
        repeat(10) { if (focused() != "Account & devices") press(Key.DirectionDown) }
        assertEquals("Account & devices", focused())
        press(Key.DirectionRight)
        repeat(30) { if (focused() != "Servers") press(Key.DirectionDown) }
        assertEquals("Servers", focused())
        compose.onNode(hasText("Servers") and hasText("Living room and 3 more")).assertExists()

        press(Key.DirectionCenter)
        compose.onNodeWithText("This TV is signed in to each of these. Search looks in all of them.").assertExists()
        compose.onNodeWithText("Sam · 192.168.4.31:27587 · v0.9.3").assertExists()
        assertEquals("Cabin server", focused())

        press(Key.DirectionCenter)
        compose.onNodeWithText("WatchMyMedia 0.9.3").assertExists()
        assertEquals("Switch to this server", focused())
        press(Key.DirectionDown)
        assertEquals("Remove this server…", focused())
        press(Key.DirectionCenter)
        compose.onNodeWithText("Remove Cabin server from this TV?").assertExists()
        compose.onNodeWithText("This TV is signed out of it.").assertExists()
        assertEquals("the question opens on the button that changes nothing", "Keep", focused())

        // Keep goes back to its page; Remove takes it off this TV.
        press(Key.DirectionCenter)
        compose.onNodeWithText("Remove Cabin server from this TV?").assertDoesNotExist()
        repeat(4) { if (focused() != "Remove this server…") press(Key.DirectionDown) }
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        assertEquals("Remove", focused())
        press(Key.DirectionCenter)

        assertFalse(runBlocking { services.servers.saved.first() }.any { it.server.id == Cabin.id })
        assertEquals("the server in use is untouched", LivingRoom.id, current)
        // Back on the list, without it.
        compose.onNodeWithText("This TV is signed in to each of these. Search looks in all of them.").assertExists()
        assertTrue(compose.onAllNodesWithText("Cabin server").fetchSemanticsNodes().isEmpty())
    }

    @Test fun `Manage servers opens Settings on its Servers page`() {
        start()
        toAccountButton()
        press(Key.DirectionCenter)
        repeat(6) { if (focused() != "Manage servers") press(Key.DirectionDown) }
        press(Key.DirectionCenter)
        compose.onNodeWithText("This TV is signed in to each of these. Search looks in all of them.").assertExists()
        assertEquals("Cabin server", focused())
        // Back closes the page onto Account & devices, and ▶ goes into its pane on the row that opens the page.
        backInDialog()
        compose.onNodeWithText("This TV is signed in to each of these. Search looks in all of them.").assertDoesNotExist()
        compose.onNode(hasText("Servers") and hasText("Living room and 3 more")).assertExists()
        assertEquals("Account & devices", focused())
        press(Key.DirectionRight)
        assertEquals("Servers", focused())
    }

    @Test fun `a search card from another server carries its name, and OK on it switches and lands on its title`() {
        start()
        press(Key.DirectionLeft)
        repeat(6) { if (focused() != "Search") press(Key.DirectionUp) }
        press(Key.DirectionCenter)
        if (focused() == "Search") press(Key.DirectionRight)
        assertEquals("q", focused())
        // "har" on the keyboard: h (down a row, five along), a, then r (up a row, three along).
        press(Key.DirectionDown)
        repeat(5) { press(Key.DirectionRight) }
        press(Key.DirectionCenter)
        repeat(5) { press(Key.DirectionLeft) }
        press(Key.DirectionCenter)
        press(Key.DirectionUp)
        repeat(3) { press(Key.DirectionRight) }
        press(Key.DirectionCenter)
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        compose.waitForIdle()

        // The Cabin server's card says where it is; the server in use's doesn't.
        compose.onNode(hasText("Harborside") and hasText("Cabin server")).assertExists()
        assertTrue(compose.onAllNodes(hasText("Harbor Lights") and hasText("Living room server")).fetchSemanticsNodes().isEmpty())
        compose.onAllNodesWithText("Harborside").onFirst().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        assertEquals("Harborside", focused())
        press(Key.DirectionCenter)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        compose.waitForIdle()

        assertEquals("the TV is on the server that has it", Cabin.id, current)
        compose.onNodeWithText("Now on Cabin server").assertExists()
        // Back from its page is Search with the same words, on the Cabin server; Back again is its Home.
        back()
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        compose.waitForIdle()
        compose.onNode(hasText("Harbor Lights") and hasText("Living room server")).assertExists()
        back()
        // The Cabin server's Home: its Continue watching opens it.
        assertTrue(compose.onAllNodesWithText("Pine Ledger").fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * The focused element's title: a row's label, a button's or a rail
     * item's name. A panel's or a confirmation's when one is open, since the
     * screen under it keeps its own focused element.
     */
    private fun focused(): String {
        val inDialog = compose.onAllNodes(isFocused() and hasAnyAncestor(isDialog())).fetchSemanticsNodes()
        val node = inDialog.firstOrNull() ?: compose.onNode(isFocused() and !hasAnyAncestor(isDialog())).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull { it.startsWith("Servers") }
            ?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull { it.text.isNotBlank() && it.text.any(Char::isLetter) }?.text
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
}
