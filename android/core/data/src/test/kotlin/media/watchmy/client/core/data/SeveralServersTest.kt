package media.watchmy.client.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.noContent
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.OfflineOwner
import media.watchmy.client.model.Server
import media.watchmy.client.model.ServerSwitch
import media.watchmy.client.model.SignedOutException
import media.watchmy.client.model.linkAccount
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Several servers on one device: a switch that asks the server first and
 * leaves the one in use alone when it doesn't answer, signing out of or
 * removing one, and a search that asks each server as its own person at its
 * own address. Two servers are signed in: the test server (viewer, token
 * tok-viewer) and a second one (token tok-cabin), which is the one in use.
 */
class SeveralServersTest {
    private val h = Harness()
    private val second = TestServer()
    private val cabin = Server("srv-cabin", "Cabin server", second.baseUrl, "1.0.0", 3)
    private val servers get() = h.services.servers

    @After
    fun tearDown() {
        h.close()
        second.close()
    }

    /** Signed in on both; the Cabin server is the one in use. */
    private fun signInOnBoth() = runBlocking {
        h.signIn()
        servers.select(cabin)
        // Another person's id there, so a test can tell whose sign-in is in use.
        second.always("GET /api/auth/me", json("""{"id":7,"name":"viewer","isAdmin":false}"""))
        second.on("POST /api/auth/login", json(TestServer.SIGN_IN.replace("tok-viewer", "tok-cabin").replace("\"id\":2", "\"id\":7")))
        h.services.auth.signIn("viewer", "fixture-viewer-1")
        // The checks each sign-in makes in the background are done before a test counts requests.
        h.services.awaitIdle()
        h.server.requests.clear()
        second.requests.clear()
    }

    private fun testInfo(level: Int = 5) = json(INFO.format(level))

    @Test
    fun `a switch asks the server which one it is, then makes it the one in use with its sign-in kept`() = runBlocking {
        signInOnBoth()
        h.server.always("GET /api/server", testInfo())
        h.now += 60_000
        assertEquals(ServerSwitch.Switched, servers.switchTo("srv-test"))
        assertEquals("srv-test", servers.current.value?.server?.id)
        assertEquals("no password, no steps", 2L, h.services.auth.user.value?.id)
        assertNull("the question needs no sign-in", h.server.last("GET /api/server").getHeader("Authorization"))
        assertEquals("what it says about itself now is kept", 5, servers.current.value?.server?.apiLevel)
        assertEquals("the one in use is the most recently used", listOf("srv-test", "srv-cabin"), servers.saved.first().map { it.server.id })
        assertTrue("the other stays signed in", servers.saved.first().all { it.signedIn })
    }

    @Test
    fun `a server that doesn't answer leaves the one in use as it was`() = runBlocking {
        signInOnBoth()
        // The test server is off.
        h.server.close()
        assertEquals(ServerSwitch.Unreachable, servers.switchTo("srv-test"))
        assertEquals("srv-cabin", servers.current.value?.server?.id)
        assertEquals(7L, h.services.auth.user.value?.id)
        // Watching its downloads switches all the same, without asking.
        assertEquals(ServerSwitch.Switched, servers.switchTo("srv-test", ask = false))
        assertEquals("srv-test", servers.current.value?.server?.id)
    }

    @Test
    fun `another server answering at its address is not switched to, and gets no sign-in`() = runBlocking {
        signInOnBoth()
        h.server.always("GET /api/server", json(INFO.format(5).replace("srv-test", "someone-else")))
        assertEquals(ServerSwitch.Unreachable, servers.switchTo("srv-test"))
        assertEquals("srv-cabin", servers.current.value?.server?.id)
        assertTrue(h.server.requests.none { it.getHeader("Authorization") != null })
    }

    @Test
    fun `a server that answers too old for this app is not switched to`() = runBlocking {
        signInOnBoth()
        h.server.always("GET /api/server", testInfo(level = 0))
        assertEquals(ServerSwitch.TooOld, servers.switchTo("srv-test"))
        assertEquals("srv-cabin", servers.current.value?.server?.id)
    }

    @Test
    fun `a server whose sign-in ended becomes the one in use, for its sign-in`() = runBlocking {
        signInOnBoth()
        // The test server's session was revoked: its token is refused, which clears that sign-in alone.
        h.server.always("GET /api/search", TestServer.problem(401, "sign in required"))
        assertTrue(runCatching { h.services.library.searchOn("srv-test", "harbor") }.exceptionOrNull() is SignedOutException)
        assertEquals("the one in use is untouched", 7L, h.services.auth.user.value?.id)
        val saved = servers.saved.first().first { it.server.id == "srv-test" }
        assertFalse(saved.signedIn)
        assertEquals("it stays on the list, as signed out", "viewer", saved.userName)

        h.server.always("GET /api/server", testInfo())
        assertEquals(ServerSwitch.NeedsSignIn, servers.switchTo("srv-test"))
        assertEquals("srv-test", servers.current.value?.server?.id)
        assertNull(h.services.auth.user.value)
    }

    @Test
    fun `a switch to a server that is gone says so`() = runBlocking {
        signInOnBoth()
        assertEquals(ServerSwitch.Gone, servers.switchTo("srv-never"))
        assertEquals("srv-cabin", servers.current.value?.server?.id)
    }

    @Test
    fun `signing out of the server in use moves to the one used last that is still signed in`() = runBlocking {
        signInOnBoth()
        second.always("POST /api/auth/logout", noContent())
        h.services.auth.signOut()
        assertEquals("srv-test", servers.current.value?.server?.id)
        assertEquals(2L, h.services.auth.user.value?.id)
        assertEquals("Bearer tok-cabin", second.last("POST /api/auth/logout").getHeader("Authorization"))
        val left = servers.saved.first().first { it.server.id == "srv-cabin" }
        assertFalse("it stays on the list, signed out", left.signedIn)
        assertEquals("viewer", left.userName)
        // With no other signed in, signing out stays on that server's sign-in, as it always did.
        h.server.always("POST /api/auth/logout", noContent())
        h.services.auth.signOut()
        assertEquals("srv-test", servers.current.value?.server?.id)
        assertNull(h.services.auth.user.value)
    }

    @Test
    fun `removing the server in use moves to another signed in, and removing the last leaves none`() = runBlocking {
        signInOnBoth()
        second.always("POST /api/auth/logout", noContent())
        h.server.always("POST /api/auth/logout", noContent())
        servers.forget("srv-cabin")
        assertEquals("srv-test", servers.current.value?.server?.id)
        assertEquals(2L, h.services.auth.user.value?.id)
        assertEquals(listOf("srv-test"), servers.saved.first().map { it.server.id })
        servers.forget("srv-test")
        assertNull("the picker, as with no server chosen", servers.current.value)
        assertTrue(servers.saved.first().isEmpty())
    }

    @Test
    fun `removing a server that isn't in use tells what is kept for it first, ends its session there and leaves the one in use alone`() = runBlocking {
        signInOnBoth()
        val ending = mutableListOf<OfflineOwner>()
        var changes = 0
        var loggedOutBeforeHook = false
        h.services.offlineGateway.beforeSignOut { owner ->
            ending += owner
            loggedOutBeforeHook = h.server.requests("POST /api/auth/logout").isNotEmpty()
        }
        h.services.offlineGateway.onSessionChange { changes++ }
        h.server.always("POST /api/auth/logout", noContent())

        servers.forget("srv-test")

        assertEquals(listOf(OfflineOwner("srv-test", 2, h.server.baseUrl, 1)), ending)
        assertFalse("while its session still stands", loggedOutBeforeHook)
        assertEquals("what is kept for each sign-in hears of it at once, though the one in use didn't change", 1, changes)
        assertFalse(h.services.offlineGateway.signedIn(OfflineOwner("srv-test", 2, h.server.baseUrl, 1)))
        assertEquals("srv-cabin", servers.current.value?.server?.id)
        assertEquals(7L, h.services.auth.user.value?.id)
        // The session there ends in the background.
        val deadline = System.currentTimeMillis() + 5_000
        while (h.server.requests("POST /api/auth/logout").isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("Bearer tok-viewer", h.server.last("POST /api/auth/logout").getHeader("Authorization"))
        assertTrue("the server in use is not signed out of", second.requests("POST /api/auth/logout").isEmpty())
    }

    @Test
    fun `a search of another server goes to its own address as its own person`() = runBlocking {
        signInOnBoth()
        val found = """{"movies":[],"shows":[],"episodes":[]}"""
        h.server.always("GET /api/search", json(found))
        second.always("GET /api/search", json(found))

        h.services.library.searchOn("srv-test", " harbor ")
        assertEquals("Bearer tok-viewer", h.server.last("GET /api/search").getHeader("Authorization"))
        assertEquals("harbor", h.server.last("GET /api/search").requestUrl!!.queryParameter("q"))
        assertTrue("the server in use hears nothing of it", second.requests("GET /api/search").isEmpty())

        h.services.library.search("harbor")
        assertEquals("Bearer tok-cabin", second.last("GET /api/search").getHeader("Authorization"))
        assertEquals("each server was asked once", 1, h.server.requests("GET /api/search").size)
        assertTrue("each server's sign-in went to its own address alone", h.server.requests.none { it.getHeader("Authorization") == "Bearer tok-cabin" })
        assertTrue(second.requests.none { it.getHeader("Authorization") == "Bearer tok-viewer" })
    }

    @Test
    fun `a search of a server no one is signed in to here is refused without a call`() = runBlocking {
        h.signIn()
        servers.select(cabin)
        assertTrue(runCatching { h.services.library.searchOn("srv-cabin", "harbor") }.exceptionOrNull() is SignedOutException)
        assertTrue(runCatching { h.services.library.searchOn("srv-never", "harbor") }.exceptionOrNull() is SignedOutException)
        assertTrue(second.requests("GET /api/search").isEmpty())
    }

    @Test
    fun `a picture from another server carries that server's sign-in, and no server gets another's`() = runBlocking {
        signInOnBoth()
        TestServer().use { stranger ->
            val urls = listOf(
                h.services.library.imageUrlOn("srv-test", "/a.jpg", ImageSize.W342)!!,
                h.services.library.imageUrl("/a.jpg", ImageSize.W342)!!,
                "${stranger.baseUrl}/api/images/w342/a.jpg",
            )
            assertEquals("${h.server.baseUrl}/api/images/w342/a.jpg", urls[0])
            assertEquals("${second.baseUrl}/api/images/w342/a.jpg", urls[1])
            listOf(h.server, second, stranger).forEach { it.always("GET /api/images/w342/a.jpg", noContent()) }
            urls.forEach { h.services.imageHttpClient.newCall(okhttp3.Request.Builder().url(it).build()).execute().close() }
            assertEquals("Bearer tok-viewer", h.server.last("GET /api/images/w342/a.jpg").getHeader("Authorization"))
            assertEquals("Bearer tok-cabin", second.last("GET /api/images/w342/a.jpg").getHeader("Authorization"))
            assertNull("a server that isn't registered gets none", stranger.last("GET /api/images/w342/a.jpg").getHeader("Authorization"))
        }
    }

    @Test
    fun `each registered server names its account, so a link for one that isn't in use finds it`() = runBlocking {
        signInOnBoth()
        val saved = servers.saved.first()
        assertEquals(mapOf("srv-cabin" to 7L, "srv-test" to 2L), saved.associate { it.server.id to it.userId })
        val test = saved.first { it.server.id == "srv-test" }
        val link = DeepLink.newEpisode(showId = 12, season = 2, episodeId = 34, account = test.linkAccount()!!)
        assertEquals("srv-test", DeepLink.serverFor(link, saved)?.server?.id)
        assertNull("a link for an account that isn't signed in here names no server", DeepLink.serverFor(DeepLink.home("someone-else"), saved))
        assertNull(DeepLink.serverFor("watchmymedia://shows/12", saved))
    }

    private companion object {
        const val INFO = """{"id":"srv-test","name":"Test server","version":"1.3.0","apiLevel":%d,"setupRequired":false,"turnstile":false}"""
    }
}
