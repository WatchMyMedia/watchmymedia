package media.watchmy.client.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.noContent
import media.watchmy.client.core.data.TestServer.Companion.problem
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.FOLLOWS_API_LEVEL
import media.watchmy.client.model.NewEpisodeOpen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Following shows and the feed of their new episodes against a server that speaks the contract, and one too old for it. */
class FollowsRepositoryTest {
    private val h = Harness()

    @After
    fun tearDown() = h.close()

    private fun signInAt(level: Int) = runBlocking {
        h.server.always("GET /api/server", json(SERVER.format(level)))
        h.services.servers.select(h.testServer.copy(apiLevel = level))
        h.server.on("POST /api/auth/login", json(TestServer.SIGN_IN))
        h.services.auth.signIn("viewer", "fixture-viewer-1")
        withTimeout(5_000) { h.services.servers.current.first { it?.server?.apiLevel == level } }
    }

    @Test
    fun `below level 29 nothing is offered and nothing is asked`() = runBlocking {
        signInAt(FOLLOWS_API_LEVEL - 1)
        val follows = h.services.follows
        assertFalse(follows.supported)
        assertThrows(ApiException::class.java) { runBlocking { follows.refresh() } }
        assertThrows(ApiException::class.java) { runBlocking { follows.setFollowing(11, true) } }
        assertThrows(ApiException::class.java) { runBlocking { follows.newEpisodes(null) } }
        assertTrue(h.server.requests.none { it.requestUrl!!.encodedPath.let { p -> p.endsWith("/follow") || p.startsWith("/api/me/follows") || p.startsWith("/api/me/new-episodes") } })
    }

    @Test
    fun `the list is read newest first, with a show that has left the library`() = runBlocking {
        signInAt(FOLLOWS_API_LEVEL)
        h.server.on("GET /api/me/follows", json(FOLLOWS))
        val list = h.services.follows.refresh()
        assertEquals(listOf("Harbor Lights", "Ashgrove"), list.map { it.title })
        assertEquals(11L, list[0].showId)
        assertEquals("/hl.jpg", list[0].posterPath)
        assertEquals(3, list[0].latest?.season)
        assertEquals(7, list[0].latest?.number)
        assertTrue(list[1].gone)
        assertEquals(70412L, list[1].tmdbId)
        assertEquals(list, h.services.follows.follows.value)
    }

    @Test
    fun `following and stopping call the show's route, then read the list again`() = runBlocking {
        signInAt(FOLLOWS_API_LEVEL)
        h.server.on("PUT /api/shows/11/follow", noContent())
        h.server.on("GET /api/me/follows", json(FOLLOWS))
        h.services.follows.setFollowing(11, true)
        assertEquals(1, h.server.requests("PUT /api/shows/11/follow").size)
        assertEquals(2, h.services.follows.follows.value?.size)

        h.server.on("DELETE /api/shows/11/follow", noContent())
        h.server.on("GET /api/me/follows", json(FOLLOWS_AFTER))
        h.services.follows.setFollowing(11, false)
        assertEquals(1, h.server.requests("DELETE /api/shows/11/follow").size)
        assertEquals(listOf("Ashgrove"), h.services.follows.follows.value?.map { it.title })
    }

    @Test
    fun `a refused follow says why`() = runBlocking {
        signInAt(FOLLOWS_API_LEVEL)
        h.server.on("PUT /api/shows/11/follow", problem(409, "You follow 500 shows, the most there can be."))
        val e = assertThrows(ApiException::class.java) { runBlocking { h.services.follows.setFollowing(11, true) } }
        assertEquals(409, e.status)
    }

    @Test
    fun `a show no longer in the library is removed by its TMDB id`() = runBlocking {
        signInAt(FOLLOWS_API_LEVEL)
        h.server.on("GET /api/me/follows", json(FOLLOWS))
        h.services.follows.refresh()
        h.server.on("DELETE /api/me/follows/70412", noContent())
        h.services.follows.remove(70412)
        assertEquals(1, h.server.requests("DELETE /api/me/follows/70412").size)
        assertEquals(listOf("Harbor Lights"), h.services.follows.follows.value?.map { it.title })
    }

    @Test
    fun `the feed is asked after the cursor and read by show`() = runBlocking {
        signInAt(FOLLOWS_API_LEVEL)
        h.server.on("GET /api/me/new-episodes", json("""{"cursor":"1841","shows":[]}"""))
        assertEquals("1841", h.services.follows.newEpisodes(null).cursor)
        assertNull("a first check sends no cursor", h.server.last("GET /api/me/new-episodes").requestUrl!!.queryParameter("after"))

        h.server.on("GET /api/me/new-episodes", json(FEED))
        val feed = h.services.follows.newEpisodes("1841")
        assertEquals("1841", h.server.last("GET /api/me/new-episodes").requestUrl!!.queryParameter("after"))
        assertEquals("1850", feed.cursor)
        val group = feed.shows.single()
        assertEquals(11L, group.showId)
        assertEquals("The Sleuth Files", group.title)
        assertEquals(7, group.count)
        assertEquals(listOf(7, 8), group.episodes.map { it.number })
        assertEquals("The Long Night", group.episodes[0].title)
        assertEquals(NewEpisodeOpen(4007, 3, 7), group.open)
    }

    @Test
    fun `this device's switch is kept on the device for the account, on by default`() = runBlocking {
        signInAt(FOLLOWS_API_LEVEL)
        assertTrue(h.services.follows.notifyHere.value)
        h.services.follows.setNotifyHere(false)
        assertFalse(h.services.follows.notifyHere.value)
        assertFalse("kept across a restart", h.reopen().follows.notifyHere.value)
        h.services.awaitIdle()
        h.server.on("POST /api/auth/logout", noContent())
        h.services.auth.signOut()
        assertTrue("signed out: the default", h.services.follows.notifyHere.value)
        h.server.on("POST /api/auth/login", json(OTHER_SIGN_IN))
        h.server.on("GET /api/auth/me", json("""{"id":3,"name":"other","isAdmin":false}"""))
        h.services.auth.signIn("other", "fixture-other-1")
        h.services.awaitIdle()
        assertTrue("another account's own switch", h.services.follows.notifyHere.value)
        h.server.on("POST /api/auth/login", json(TestServer.SIGN_IN))
        h.services.auth.signIn("viewer", "fixture-viewer-1")
        assertFalse("the first account's, as it left it", h.services.follows.notifyHere.value)
        h.services.follows.setNotifyHere(true)
        assertTrue(h.reopen().follows.notifyHere.value)
    }

    @Test
    fun `another account's list isn't kept`() = runBlocking {
        signInAt(FOLLOWS_API_LEVEL)
        h.server.on("GET /api/me/follows", json(FOLLOWS))
        h.services.follows.refresh()
        h.server.on("POST /api/auth/logout", noContent())
        h.services.auth.signOut()
        assertNull(h.services.follows.follows.value)
    }

    private companion object {
        const val SERVER = """{"id":"srv-test","name":"Test server","version":"1.5.0","apiLevel":%d,"setupRequired":false,"turnstile":false}"""
        const val FOLLOWS = """{"follows":[
            {"tmdbId":1399,"title":"Harbor Lights","followedAt":"2026-11-15T10:00:00Z","show":{"id":11,"title":"Harbor Lights","posterPath":"/hl.jpg"},"latest":{"season":3,"number":7,"title":"The Long Night","arrivedAt":"2026-11-15T20:00:00Z"}},
            {"tmdbId":70412,"title":"Ashgrove","followedAt":"2026-10-01T10:00:00Z"}
        ]}"""
        const val OTHER_SIGN_IN = """{"id":3,"name":"other","isAdmin":false,"sessionId":"96e0d90b273fc14b","token":"tok-other","expiresAt":"2027-10-29T17:09:01Z"}"""
        const val FOLLOWS_AFTER = """{"follows":[{"tmdbId":70412,"title":"Ashgrove","followedAt":"2026-10-01T10:00:00Z"}]}"""
        const val FEED = """{"cursor":"1850","shows":[{"show":{"id":11,"tmdbId":1399,"title":"The Sleuth Files","posterPath":"/sf.jpg"},"count":7,
            "episodes":[{"id":4007,"season":3,"number":7,"title":"The Long Night","arrivedAt":"2026-11-16T09:00:00Z"},{"id":4008,"season":3,"number":8,"arrivedAt":"2026-11-16T09:00:00Z"}],
            "open":{"episodeId":4007,"season":3,"number":7}}]}"""
    }
}
