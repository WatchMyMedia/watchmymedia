package media.watchmy.client.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.noContent
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.OfflineOwner
import media.watchmy.client.model.Server
import media.watchmy.client.model.ServerSwitch
import media.watchmy.client.model.SignedOutException
import media.watchmy.client.model.linkAccount
import okhttp3.mockwebserver.MockResponse
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
 * own address. Whatever is sent to a server that isn't in use (a search, a
 * report, the end of its session) goes only after it has said, asked with no
 * sign-in, which server it is.
 * Two servers are signed in: the test server (viewer, token tok-viewer) and
 * a second one (token tok-cabin), which is the one in use.
 */
class SeveralServersTest {
    private val network = TestNetwork()
    private val h = Harness(network = network)
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

    private val cabinInfo get() = json(CABIN_INFO)

    /** A search that finds nothing. */
    private val found get() = json("""{"movies":[],"shows":[],"episodes":[]}""")

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
        h.server.always("GET /api/server", testInfo())
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
        second.always("GET /api/server", cabinInfo)
        second.always("POST /api/auth/logout", noContent())
        h.services.auth.signOut()
        assertEquals("srv-test", servers.current.value?.server?.id)
        assertEquals(2L, h.services.auth.user.value?.id)
        // Another server took its place, so it says which server it is before the sign-out goes to it.
        assertEquals(listOf("GET /api/server", "POST /api/auth/logout"), second.requests.map { "${it.method} ${it.requestUrl!!.encodedPath}" })
        assertNull(second.requests[0].getHeader("Authorization"))
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
    fun `a sign-out is not sent to another server at the address of the one signed out of`() = runBlocking {
        signInOnBoth()
        second.always("GET /api/server", json(CABIN_INFO.replace("srv-cabin", "someone-else")))
        second.always("POST /api/auth/logout", noContent())
        h.services.auth.signOut()
        assertEquals("the app moves on all the same", "srv-test", servers.current.value?.server?.id)
        assertFalse("and is signed out of it here", servers.saved.first().first { it.server.id == "srv-cabin" }.signedIn)
        assertEquals(listOf("GET /api/server"), second.requests.map { "${it.method} ${it.requestUrl!!.encodedPath}" })
        assertTrue("no request to it carried a sign-in", second.requests.none { it.getHeader("Authorization") != null })
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
        h.server.always("GET /api/server", testInfo())
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

    /** As a download does when its server is removed: tells that server what the device no longer holds. */
    private fun reportsOnRemoval(): MutableList<Boolean> {
        val sent = mutableListOf<Boolean>()
        h.server.always("POST /api/offline/sync", json("""{"serverTime":"2027-01-15T08:00:01Z","offline":true,"downloads":[],"progress":[]}"""))
        h.services.offlineGateway.beforeSignOut { owner ->
            repeat(2) {
                sent += runCatching { h.services.offlineGateway.sync(owner, h.services.client, 0, emptyList(), emptyList()) }.isSuccess
            }
        }
        return sent
    }

    /** Waits for what a removal sends in the background to stop arriving. */
    private fun settled(server: TestServer) {
        var seen = -1
        while (seen != server.requests.size) {
            seen = server.requests.size
            Thread.sleep(250)
        }
    }

    @Test
    fun `a removal asks a server that isn't in use once, for what it reports and for the sign-out after`() = runBlocking {
        signInOnBoth()
        val sent = reportsOnRemoval()
        h.server.always("GET /api/server", testInfo())
        h.server.always("POST /api/auth/logout", noContent())

        servers.forget("srv-test")
        settled(h.server)

        assertEquals(listOf(true, true), sent)
        assertEquals(
            listOf("GET /api/server", "POST /api/offline/sync", "POST /api/offline/sync", "POST /api/auth/logout"),
            h.server.requests.map { "${it.method} ${it.requestUrl!!.encodedPath}" },
        )
        assertNull("the question carries no sign-in", h.server.requests[0].getHeader("Authorization"))
        assertTrue(h.server.requests.drop(1).all { it.getHeader("Authorization") == "Bearer tok-viewer" })
    }

    @Test
    fun `removing a server at whose address another answers sends it no report and no sign-out, and removes it all the same`() = runBlocking {
        signInOnBoth()
        val sent = reportsOnRemoval()
        h.server.always("GET /api/server", json(INFO.format(5).replace("srv-test", "someone-else")))
        h.server.always("POST /api/auth/logout", noContent())

        servers.forget("srv-test")
        settled(h.server)

        assertEquals("srv-cabin", servers.current.value?.server?.id)
        assertEquals("it is gone from this device", listOf("srv-cabin"), servers.saved.first().map { it.server.id })
        assertEquals("what it was to be told could not be sent", listOf(false, false), sent)
        assertEquals("asked which server it is once, and nothing else", listOf("GET /api/server"), h.server.requests.map { "${it.method} ${it.requestUrl!!.encodedPath}" })
        assertTrue("no request to it carried a sign-in", h.server.requests.none { it.getHeader("Authorization") != null })
    }

    @Test
    fun `removing a server whose address doesn't answer removes it all the same, and sends nothing when something else is there`() = runBlocking {
        signInOnBoth()
        val sent = reportsOnRemoval()
        // Something that isn't a WatchMyMedia server has the address: it has no such page.
        h.server.always("POST /api/auth/logout", noContent())

        servers.forget("srv-test")
        settled(h.server)

        assertEquals(listOf("srv-cabin"), servers.saved.first().map { it.server.id })
        assertEquals(listOf(false, false), sent)
        assertEquals(listOf("GET /api/server"), h.server.requests.map { "${it.method} ${it.requestUrl!!.encodedPath}" })
        assertTrue(h.server.requests.none { it.getHeader("Authorization") != null })

        // And with nothing at an address at all: the server in use, removed while its machine is off.
        second.close()
        servers.forget("srv-cabin")
        assertTrue(servers.saved.first().isEmpty())
    }

    @Test
    fun `a search of another server goes to its own address as its own person`() = runBlocking {
        signInOnBoth()
        h.server.always("GET /api/server", testInfo())
        h.server.always("GET /api/search", found)
        second.always("GET /api/search", found)

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
    fun `a picture from another server carries that server's sign-in once it has said who it is, and no server gets another's`() = runBlocking {
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
            fun load() = urls.forEach { h.services.imageHttpClient.newCall(okhttp3.Request.Builder().url(it).build()).execute().close() }

            // Before the other server has been asked which server it is, its picture is asked for with no sign-in.
            load()
            assertNull(h.server.last("GET /api/images/w342/a.jpg").getHeader("Authorization"))
            assertEquals("the server in use's goes as it always has", "Bearer tok-cabin", second.last("GET /api/images/w342/a.jpg").getHeader("Authorization"))

            // A search of it asks, and it is the server remembered.
            h.server.always("GET /api/server", testInfo())
            h.server.always("GET /api/search", found)
            h.services.library.searchOn("srv-test", "harbor")
            load()
            assertEquals("Bearer tok-viewer", h.server.last("GET /api/images/w342/a.jpg").getHeader("Authorization"))
            assertEquals("Bearer tok-cabin", second.last("GET /api/images/w342/a.jpg").getHeader("Authorization"))
            assertNull("a server that isn't registered gets none", stranger.last("GET /api/images/w342/a.jpg").getHeader("Authorization"))
            assertTrue("each server's sign-in went to its own address alone", h.server.requests.none { it.getHeader("Authorization") == "Bearer tok-cabin" })
            assertTrue(second.requests.none { it.getHeader("Authorization") == "Bearer tok-viewer" })

            // Search is left and comes back: until it is asked again, no sign-in.
            h.services.library.searchVisit()
            load()
            assertNull(h.server.last("GET /api/images/w342/a.jpg").getHeader("Authorization"))
        }
    }

    @Test
    fun `a search asks another server which server it is, with no sign-in, before its sign-in goes there`() = runBlocking {
        signInOnBoth()
        h.server.always("GET /api/server", testInfo())
        h.server.always("GET /api/search", found)
        second.always("GET /api/search", found)

        h.services.library.searchOn("srv-test", "harbor")
        assertEquals(listOf("GET /api/server", "GET /api/search"), h.server.requests.map { "${it.method} ${it.requestUrl!!.encodedPath}" })
        assertNull("the question carries no sign-in", h.server.requests[0].getHeader("Authorization"))
        assertEquals("Bearer tok-viewer", h.server.requests[1].getHeader("Authorization"))

        // The server in use is searched as it always was.
        h.services.library.search("harbor")
        assertEquals(listOf("GET /api/search"), second.requests.map { "${it.method} ${it.requestUrl!!.encodedPath}" })
    }

    @Test
    fun `another server answering at the address gets no sign-in at all`() = runBlocking {
        signInOnBoth()
        // The address kept is another machine's here, and it is a WatchMyMedia server too.
        h.server.always("GET /api/server", json(INFO.format(5).replace("srv-test", "someone-else")))
        h.server.always("GET /api/search", found)
        h.server.always("GET /api/images/w342/a.jpg", noContent())

        assertTrue(runCatching { h.services.library.searchOn("srv-test", "harbor") }.exceptionOrNull() is ApiException)
        // A picture of something it found before is asked for with no sign-in either.
        val picture = h.services.library.imageUrlOn("srv-test", "/a.jpg", ImageSize.W342)!!
        h.services.imageHttpClient.newCall(okhttp3.Request.Builder().url(picture).build()).execute().close()

        assertTrue("nothing was searched", h.server.requests("GET /api/search").isEmpty())
        assertEquals(1, h.server.requests("GET /api/images/w342/a.jpg").size)
        assertTrue("no request to it carried a sign-in", h.server.requests.none { it.getHeader("Authorization") != null })
        val kept = servers.saved.first().first { it.server.id == "srv-test" }
        assertTrue("the sign-in is kept for when the server is there again", kept.signedIn)
        assertEquals("what the stranger says of itself is not kept", "Test server", kept.server.name)
    }

    @Test
    fun `an address where something else answers, or nothing does, gets no sign-in`() = runBlocking {
        signInOnBoth()
        h.server.always("GET /api/search", found)
        // Something that isn't a WatchMyMedia server: it has no such page.
        assertTrue(runCatching { h.services.library.searchOn("srv-test", "harbor") }.exceptionOrNull() is ApiException)
        // Something that answers the question with a page of its own.
        h.server.always("GET /api/server", MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>router</html>"))
        assertTrue(runCatching { h.services.library.searchOn("srv-test", "harbor") }.exceptionOrNull() is ApiException)
        assertEquals("each search asked, since neither was answered", 2, h.server.requests("GET /api/server").size)
        assertTrue(h.server.requests("GET /api/search").isEmpty())
        assertTrue(h.server.requests.none { it.getHeader("Authorization") != null })

        // Nothing at the address at all.
        val before = h.server.requests.size
        h.server.close()
        assertTrue(runCatching { h.services.library.searchOn("srv-test", "harbor") }.exceptionOrNull() is ApiException)
        assertEquals(before, h.server.requests.size)
    }

    @Test
    fun `typing asks a server once, a call that fails asks again, and so does a new visit to Search`() = runBlocking {
        signInOnBoth()
        h.server.always("GET /api/server", testInfo())
        h.server.always("GET /api/search", found)
        val library = h.services.library

        listOf("h", "ha", "har").forEach { library.searchOn("srv-test", it) }
        assertEquals("asked once for three searches", 1, h.server.requests("GET /api/server").size)
        assertEquals(3, h.server.requests("GET /api/search").size)

        // A call to it fails: Try again asks which server it is before it searches.
        h.server.on("GET /api/search", TestServer.problem(500, "broken"))
        assertTrue(runCatching { library.searchOn("srv-test", "harb") }.isFailure)
        library.searchOn("srv-test", "harb")
        assertEquals(2, h.server.requests("GET /api/server").size)

        // Search comes on screen again with nothing of this server's showing: it is asked when there is next something to send.
        library.searchVisit()
        assertEquals(2, h.server.requests("GET /api/server").size)
        library.searchOn("srv-test", "harbo")
        assertEquals(3, h.server.requests("GET /api/server").size)

        // With its results showing it is asked at once, and the search that follows doesn't ask again.
        library.searchVisit(listOf("srv-test"))
        assertEquals(4, h.server.requests("GET /api/server").size)
        library.searchOn("srv-test", "harbor")
        assertEquals(4, h.server.requests("GET /api/server").size)
        assertTrue("the question never carried a sign-in", h.server.requests("GET /api/server").none { it.getHeader("Authorization") != null })
    }

    @Test
    fun `a keystroke cancels the search, not the question, and the next search waits for the same one`() = runBlocking {
        signInOnBoth()
        h.server.always("GET /api/server", testInfo())
        h.server.always("GET /api/search", found)
        val held = h.server.hold("GET /api/server")
        val first = launch(Dispatchers.IO) { h.services.library.searchOn("srv-test", "h") }
        held.awaitArrival()
        // The next letter: the first search is dropped while the server is still being asked.
        first.cancelAndJoin()
        val second = async(Dispatchers.IO) { h.services.library.searchOn("srv-test", "ha") }
        held.release()
        second.await()
        assertEquals("one question for both", 1, h.server.requests("GET /api/server").size)
        assertEquals("only the search still wanted was sent", listOf("ha"), h.server.requests("GET /api/search").map { it.requestUrl!!.queryParameter("q") })
    }

    @Test
    fun `on another network what a server said no longer stands, and it is asked again before its sign-in goes there`() = runBlocking {
        signInOnBoth()
        h.server.always("GET /api/server", testInfo())
        h.server.always("GET /api/search", found)
        h.server.always("GET /api/images/w342/a.jpg", noContent())
        val picture = h.services.library.imageUrlOn("srv-test", "/a.jpg", ImageSize.W342)!!
        fun loadPicture() = h.services.imageHttpClient.newCall(okhttp3.Request.Builder().url(picture).build()).execute().close()
        h.services.library.searchOn("srv-test", "harbor")
        loadPicture()
        assertEquals("Bearer tok-viewer", h.server.last("GET /api/images/w342/a.jpg").getHeader("Authorization"))

        // With Search still open the device joins another network, where the address kept is someone else's server.
        network.network.value = 2L
        h.server.always("GET /api/server", json(INFO.format(5).replace("srv-test", "someone-else")))
        h.server.requests.clear()
        loadPicture()
        assertTrue(runCatching { h.services.library.searchOn("srv-test", "harbour") }.exceptionOrNull() is ApiException)
        assertEquals("it was asked again", 1, h.server.requests("GET /api/server").size)
        assertTrue("nothing was searched there", h.server.requests("GET /api/search").isEmpty())
        assertTrue("and nothing there got a sign-in", h.server.requests.none { it.getHeader("Authorization") != null })

        // Home again: asked once more, it is the server remembered.
        network.network.value = 1L
        h.server.always("GET /api/server", testInfo())
        h.services.library.searchOn("srv-test", "harbour")
        assertEquals("Bearer tok-viewer", h.server.last("GET /api/search").getHeader("Authorization"))
    }

    @Test
    fun `what a server says of itself when asked is kept, and one now too old gets no sign-in`() = runBlocking {
        signInOnBoth()
        h.server.always("GET /api/server", json(INFO.format(5).replace("Test server", "Attic server").replace("1.3.0", "1.4.0")))
        h.server.always("GET /api/search", found)
        h.services.library.searchOn("srv-test", "harbor")
        val kept = servers.saved.first().first { it.server.id == "srv-test" }.server
        assertEquals(listOf("Attic server", "1.4.0", "5"), listOf(kept.name, kept.version, kept.apiLevel.toString()))
        assertEquals("the server in use stays the one in use", "srv-cabin", servers.current.value?.server?.id)

        // On the next visit it has been put back to a version this app can't use.
        h.services.library.searchVisit()
        h.server.always("GET /api/server", testInfo(level = 0))
        h.server.requests.clear()
        assertTrue(runCatching { h.services.library.searchOn("srv-test", "harbor") }.exceptionOrNull() is ApiException)
        assertTrue("it is kept as too old, which leaves it out of the searches that follow", servers.saved.first().first { it.server.id == "srv-test" }.server.tooOld)
        assertTrue(h.server.requests("GET /api/search").isEmpty())
        assertTrue(h.server.requests.none { it.getHeader("Authorization") != null })
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
        const val CABIN_INFO = """{"id":"srv-cabin","name":"Cabin server","version":"1.0.0","apiLevel":3,"setupRequired":false,"turnstile":false}"""
    }
}
