package media.watchmy.client.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.problem
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.OnePress
import media.watchmy.client.model.WATCHLISTS_API_LEVEL
import media.watchmy.client.model.WatchlistKey
import media.watchmy.client.model.WatchlistTitle
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Watchlists against a server that speaks the contract, and one too old for it. */
class WatchlistRepositoryTest {
    private val h = Harness()
    private val title = WatchlistTitle(WatchlistKey(MediaType.Movie, 70299), "Ninety Fathoms", 2025, "/nf.jpg")

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
    fun `below level 33 nothing is offered and nothing is asked`() = runBlocking {
        signInAt(WATCHLISTS_API_LEVEL - 1)
        val watchlists = h.services.watchlists
        assertFalse(watchlists.supported)
        assertThrows(ApiException::class.java) { runBlocking { watchlists.saveToOnly(title) } }
        assertNull("nothing shown as saved", watchlists.held.value[title.key])
        assertTrue(h.server.requests.none { it.requestUrl!!.encodedPath.startsWith("/api/me/watchlist") })
    }

    @Test
    fun `the one press saves where the server says, and the watchlists' counts follow`() = runBlocking {
        signInAt(WATCHLISTS_API_LEVEL)
        h.server.on("GET /api/me/watchlists", json(ONE))
        h.services.watchlists.refresh()
        h.server.on("PUT /api/me/watchlist/titles/movie/70299", json(SAVED))
        val press = h.services.watchlists.saveToOnly(title)
        assertEquals(listOf(11L), (press as OnePress.Saved).held)
        assertEquals(listOf(11L), h.services.watchlists.held.value[title.key])
        assertEquals(13, h.services.watchlists.watchlists.value?.single()?.titles)
        val sent = h.server.last("PUT /api/me/watchlist/titles/movie/70299").body.readUtf8()
        assertTrue("a title the server may lack goes with its details: $sent", sent.contains("\"title\":\"Ninety Fathoms\"") && sent.contains("\"posterPath\":\"/nf.jpg\""))
    }

    @Test
    fun `with several watchlists the press is refused with them, for the picker`() = runBlocking {
        signInAt(WATCHLISTS_API_LEVEL)
        h.server.on("PUT /api/me/watchlist/titles/movie/70299", MockResponse().setResponseCode(409).setHeader("Content-Type", "application/problem+json").setBody(CONFLICT))
        val press = h.services.watchlists.saveToOnly(title) as OnePress.Choose
        assertEquals(listOf("Watchlist", "Halloween"), press.watchlists.map { it.name })
        assertEquals(listOf(12L), h.services.watchlists.held.value[title.key])
        assertEquals("the count is fresh now", 2, h.services.watchlists.watchlists.value?.size)
    }

    @Test
    fun `a refused save is put back and says why`() = runBlocking {
        signInAt(WATCHLISTS_API_LEVEL)
        h.server.on("PUT /api/me/watchlists/12/titles/movie/70299", problem(422, "A watchlist holds up to 500 titles."))
        val e = assertThrows(ApiException::class.java) { runBlocking { h.services.watchlists.save(12, title) } }
        assertEquals("A watchlist holds up to 500 titles.", e.message)
        assertNull(h.services.watchlists.held.value[title.key])
    }

    private companion object {
        const val SERVER = """{"id":"srv-test","name":"Test server","version":"2.1.0","apiLevel":%d,"setupRequired":false,"turnstile":false}"""
        const val WATCHLIST = """{"id":11,"name":"Watchlist","titles":12,"inLibrary":9,"removeWatched":true,"posters":["/a.jpg"],"createdAt":"2026-11-01T10:00:00Z","updatedAt":"2026-11-20T10:00:00Z"}"""
        const val ONE = """{"items":[$WATCHLIST],"max":25,"maxTitles":500}"""
        const val SAVED = """{"watchlists":[11],"watchlist":{"id":11,"name":"Watchlist","titles":13,"inLibrary":9,"removeWatched":true,"posters":["/nf.jpg","/a.jpg"],"createdAt":"2026-11-01T10:00:00Z","updatedAt":"2026-11-21T10:00:00Z"}}"""
        const val CONFLICT = """{"title":"Conflict","status":409,"detail":"Choose a watchlist.","watchlists":[""" +
            """{"id":11,"name":"Watchlist","titles":12,"inLibrary":9,"removeWatched":true,"posters":[],"contains":false,"createdAt":"2026-11-01T10:00:00Z","updatedAt":"2026-11-20T10:00:00Z"},""" +
            """{"id":12,"name":"Halloween","titles":6,"inLibrary":6,"removeWatched":false,"posters":[],"contains":true,"createdAt":"2026-11-02T10:00:00Z","updatedAt":"2026-11-20T10:00:00Z"}]}"""
    }
}
