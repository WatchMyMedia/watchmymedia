package media.watchmy.client.core.data

import app.cash.turbine.test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import media.watchmy.client.core.data.TestServer.Companion.problem
import media.watchmy.client.model.LibraryChange
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the event stream connects and reconnects (PLAN-ANDROID-REVIEW.md
 * §4.1.5): with backoff after a failure, never again after a 404, only in
 * front and on a network, and at once on the return to either.
 */
class EventStreamGatingTest {
    private val ping = "event: ping\ndata: {}\n\n"

    /** A stream that sends its first ping and then stays open, as a server's does between events. */
    private fun open() = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(ping + ": open\n").throttleBody(ping.length.toLong(), 1, TimeUnit.DAYS)

    private fun stream(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    private suspend fun TestServer.awaitStreams(count: Int) = withTimeout(5_000) { while (requests("GET /api/events").size < count) delay(10) }

    @Test
    fun `5xx and dropped connections back off, a ping resets it, and a 404 stops`() = runBlocking {
        val waits = CopyOnWriteArrayList<Int>()
        Harness(eventBackoffMs = { attempt -> waits += attempt; 20L }).use { h ->
            h.signIn()
            h.server.on(
                "GET /api/events",
                problem(503, "busy"),
                problem(502, "bad gateway"),
                stream(ping), // connects, then the server closes it
                problem(404, "no events here"),
            )
            h.services.library.changes.test(timeout = 5.seconds) {
                h.server.awaitStreams(4)
                delay(300)
                assertEquals("after the 404, no more tries", 4, h.server.requests("GET /api/events").size)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(listOf(0, 1, 0), waits.toList())
        }
    }

    @Test
    fun `the backoff doubles from a second to a minute`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (0..7).map(ServerEvents.BACKOFF))
        assertEquals(60_000L, ServerEvents.BACKOFF(100))
    }

    @Test
    fun `out of the front the stream closes, and the return reconnects at once and says so`() = runBlocking {
        // A backoff of a minute: a reconnection within the test is the return's, not a retry's.
        Harness(eventBackoffMs = { 60_000L }).use { h ->
            h.signIn()
            h.server.on("GET /api/events", open(), open())
            h.services.library.libraryChanges.test(timeout = 5.seconds) {
                h.server.awaitStreams(1)
                delay(200)
                h.services.setInFront(false)
                delay(300)
                assertEquals("nothing connects in the background", 1, h.server.requests("GET /api/events").size)
                val checks = h.server.requests("GET /api/server").size
                h.services.setInFront(true)
                h.server.awaitStreams(2)
                assertEquals(LibraryChange.Everything, awaitItem())
                // The server's level is read again after a gap.
                withTimeout(5_000) { while (h.server.requests("GET /api/server").size <= checks) delay(10) }
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `with no network nothing connects, and the network's return reconnects at once`() = runBlocking {
        val network = TestNetwork()
        Harness(network = network, eventBackoffMs = { 60_000L }).use { h ->
            h.signIn()
            h.server.on("GET /api/events", open(), open())
            h.services.library.libraryChanges.test(timeout = 5.seconds) {
                h.server.awaitStreams(1)
                delay(200)
                network.network.value = null
                delay(300)
                assertEquals(1, h.server.requests("GET /api/events").size)
                network.network.value = 2L
                h.server.awaitStreams(2)
                assertEquals(LibraryChange.Everything, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `a start in the background asks the server nothing until the app is in front`() = runBlocking {
        Harness().use { h ->
            h.signIn()
            withTimeout(5_000) { while (h.server.requests("GET /api/auth/me").isEmpty()) delay(10) }
            // A restart in the background: the saved sign-in's checks wait.
            val restarted = h.reopen(inFront = false)
            val before = h.server.requests("GET /api/auth/me").size
            delay(300)
            assertEquals(before, h.server.requests("GET /api/auth/me").size)
            restarted.setInFront(true)
            withTimeout(5_000) { while (h.server.requests("GET /api/auth/me").size == before) delay(10) }
            assertTrue(h.server.requests("GET /api/me/preferences").isNotEmpty())
        }
    }
}
