package media.watchmy.client.core.data

import kotlinx.coroutines.runBlocking
import media.watchmy.client.core.data.TestServer.Companion.json
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The API client's HTTP cache (PLAN-ANDROID-REVIEW.md §4.1.1): a quiet reload
 * gets a 304, and a reply kept for one account is never what another sees
 * unless the server says theirs is the same.
 */
class HttpCacheTest {
    private val h = Harness(httpCache = true)
    private val libraries = HttpCacheTest::class.java.getResource("/golden/libraries.json")!!.readText()

    @After
    fun tearDown() = h.close()

    /** Past the library list's memo ([SessionMemo]), so the next call asks the server. */
    private fun later() {
        h.now += SessionMemo.TTL_MS
    }

    private fun tagged(body: String, tag: String, cacheControl: String = "private, no-cache") =
        json(body).setHeader("ETag", tag).setHeader("Cache-Control", cacheControl)

    private fun notModified(tag: String) = MockResponse().setResponseCode(304).setHeader("ETag", tag).setHeader("Cache-Control", "private, no-cache")

    private fun signInAs(name: String, id: Int, token: String) = runBlocking {
        h.server.on("POST /api/auth/login", json(TestServer.SIGN_IN.replace("tok-viewer", token).replace("\"id\":2", "\"id\":$id").replace("\"viewer\"", "\"$name\"")))
        h.services.auth.signIn(name, "password")
    }

    @Test
    fun `a quiet reload asks with the ETag and reads the kept reply from a 304`() = runBlocking {
        h.signIn()
        h.server.on("GET /api/libraries", tagged(libraries, "W/\"a\""), notModified("W/\"a\""))
        assertEquals("Movies", h.services.library.libraries().first().name)
        assertNull(h.server.last("GET /api/libraries").getHeader("If-None-Match"))
        later()
        assertEquals("Movies", h.services.library.libraries().first().name)
        val again = h.server.last("GET /api/libraries")
        assertEquals("W/\"a\"", again.getHeader("If-None-Match"))
        assertEquals("Bearer tok-viewer", again.getHeader("Authorization"))
        assertEquals(2, h.server.requests("GET /api/libraries").size)
    }

    @Test
    fun `a reply kept for one account is never served to another`() = runBlocking {
        h.signIn()
        h.server.on("GET /api/libraries", tagged(libraries, "W/\"viewer\""))
        assertEquals("Movies", h.services.library.libraries().first().name)

        // Another account on the same server: asked again, with its own token, and its own body read.
        signInAs("other", 3, "tok-other")
        h.server.on("GET /api/libraries", tagged(libraries.replace("\"Movies\"", "\"Their films\""), "W/\"other\""))
        assertEquals("Their films", h.services.library.libraries().first().name)
        val asked = h.server.last("GET /api/libraries")
        assertEquals("Bearer tok-other", asked.getHeader("Authorization"))
        assertEquals("W/\"viewer\"", asked.getHeader("If-None-Match"))

        // A reply that calls itself fresh for an hour is still asked again for the next account.
        later()
        h.server.on("GET /api/libraries", tagged(libraries.replace("\"Movies\"", "\"Fresh\""), "W/\"fresh\"", "private, max-age=3600"))
        assertEquals("Fresh", h.services.library.libraries().first().name)
        signInAs("viewer", 2, "tok-viewer")
        h.server.on("GET /api/libraries", tagged(libraries, "W/\"viewer\""))
        assertEquals("Movies", h.services.library.libraries().first().name)
        assertEquals("Bearer tok-viewer", h.server.last("GET /api/libraries").getHeader("Authorization"))
        assertEquals(4, h.server.requests("GET /api/libraries").size)
    }

    @Test
    fun `a reply without an ETag isn't kept`() = runBlocking {
        h.signIn()
        h.server.on("GET /api/libraries", json(libraries).setHeader("Cache-Control", "private, max-age=3600"), json(libraries.replace("\"Movies\"", "\"Later\"")))
        h.services.library.libraries()
        later()
        assertEquals("Later", h.services.library.libraries().first().name)
        assertNull(h.server.last("GET /api/libraries").getHeader("If-None-Match"))
    }
}
