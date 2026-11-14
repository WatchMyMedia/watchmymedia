package media.watchmy.client.core.data

import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.problem
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.REQUESTS_API_LEVEL
import media.watchmy.client.model.Server
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reference data asked once per sign-in while it is fresh (PLAN-ANDROID-REVIEW.md §4.1.4). */
class SessionMemoTest {
    private val h = Harness()
    private val libraries = SessionMemoTest::class.java.getResource("/golden/libraries.json")!!.readText()
    private val options = """{"movies":true,"shows":true,"needsApproval":false,"problems":true,"manage":false,"backend":"arr","inbox":0}"""

    @After
    fun tearDown() = h.close()

    private fun asked(route: String) = h.server.requests(route).size

    @Test
    fun `callers at once share one request, kept until it is old`() = runBlocking {
        h.signIn()
        h.server.always("GET /api/libraries", json(libraries))
        val hold = h.server.hold("GET /api/libraries")
        val first = async(Dispatchers.IO) { h.services.library.libraries() }
        val second = async(Dispatchers.IO) { h.services.library.libraries() }
        hold.awaitArrival()
        hold.release()
        assertEquals(first.await(), second.await())
        assertEquals(1, asked("GET /api/libraries"))
        h.services.library.libraries()
        assertEquals(1, asked("GET /api/libraries"))
        h.now += SessionMemo.TTL_MS
        h.services.library.libraries()
        assertEquals(2, asked("GET /api/libraries"))
    }

    @Test
    fun `another account, or a newer server level, asks again`() = runBlocking {
        h.signIn()
        h.server.always("GET /api/libraries", json(libraries))
        h.services.library.libraries()
        h.server.on("POST /api/auth/login", json(TestServer.SIGN_IN.replace("tok-viewer", "tok-other")))
        h.services.auth.signIn("viewer", "password")
        h.services.library.libraries()
        assertEquals(2, asked("GET /api/libraries"))
        h.services.servers.select(Server(h.testServer.id, h.testServer.name, h.server.baseUrl, "1.1.0", 28))
        h.services.library.libraries()
        assertEquals(3, asked("GET /api/libraries"))
    }

    @Test
    fun `a failure isn't kept`() = runBlocking {
        h.signIn()
        h.server.on("GET /api/libraries", problem(500, "busy"), json(libraries))
        assertTrue(runCatching { h.services.library.libraries() }.isFailure)
        assertEquals("Movies", h.services.library.libraries().first().name)
    }

    @Test
    fun `the matching event, or a change made here, asks again`() = runBlocking {
        h.signIn()
        h.services.servers.select(Server(h.testServer.id, h.testServer.name, h.server.baseUrl, "1.0.0", REQUESTS_API_LEVEL))
        h.server.always("GET /api/libraries", json(libraries))
        h.server.always("GET /api/requests/options", json(options))
        h.services.library.libraries()
        h.services.requests.options()
        h.server.on(
            "GET /api/events",
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: ping\ndata: {}\n\nevent: scan.finished\ndata: {\"libraryId\":1}\n\nevent: requests.changed\ndata: {}\n\n",
            ),
        )
        h.services.library.changes.test(timeout = 5.seconds) {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        withTimeout(5_000) { while (h.server.requests("GET /api/events").isEmpty()) delay(10) }
        h.services.library.libraries()
        h.services.requests.options()
        assertEquals(2, asked("GET /api/libraries"))
        assertEquals(2, asked("GET /api/requests/options"))

        h.server.on("POST /api/requests", json("""{"created":true,"request":{"id":1,"mediaType":"movie","tmdbId":5,"title":"A film","status":"pending","requestedBy":"viewer","mine":true,"seasons":[],"createdAt":"2026-11-13T10:00:00Z"}}"""))
        h.services.requests.request(MediaType.Movie, 5, emptyList())
        h.services.requests.options()
        assertEquals(3, asked("GET /api/requests/options"))
    }
}
