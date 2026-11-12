package media.watchmy.client.core.data

import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import media.watchmy.client.core.data.TestServer.Companion.problem
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerEventsTest {
    private val h = Harness()

    @After
    fun tearDown() = h.close()

    @Test
    fun `the parser joins data lines and skips comments`() {
        val events = mutableListOf<ServerEvent>()
        val parser = SseParser { events += it }
        """
        : a comment
        event: ping
        data: {"time":"2026-10-29T17:00:00Z"}

        id: 7
        event: watch.changed
        data: {"itemType":"episode",
        data: "itemIds":[401]}

        data:no space after the colon

        event: dangling
        """.trimIndent().lines().forEach(parser::line)

        assertEquals(
            listOf(
                ServerEvent("ping", """{"time":"2026-10-29T17:00:00Z"}"""),
                ServerEvent("watch.changed", "{\"itemType\":\"episode\",\n\"itemIds\":[401]}", "7"),
                // The last event ID carries on until another id field (the spec's last event ID buffer).
                ServerEvent("message", "no space after the colon", "7"),
            ),
            events,
        )
    }

    @Test
    fun `changes come from watch and library events, and again after a reconnect`() = runBlocking {
        h.signIn()
        h.server.on(
            "GET /api/events",
            stream(
                "event: ping\ndata: {\"time\":\"t\"}\n\n" +
                    "event: scan.progress\ndata: {\"libraryId\":1}\n\n" +
                    "event: watch.changed\ndata: {\"itemType\":\"movie\",\"itemIds\":[21]}\n\n",
            ),
            stream("event: ping\ndata: {\"time\":\"t\"}\n\nevent: library.changed\ndata: {\"libraryId\":2}\n\n"),
        )
        h.services.library.changes.test(timeout = 5.seconds) {
            awaitItem() // watch.changed
            awaitItem() // the second connection's ping: events may have been missed
            awaitItem() // library.changed
            cancelAndIgnoreRemainingEvents()
        }
        val first = h.server.requests("GET /api/events").first()
        assertEquals("Bearer tok-viewer", first.getHeader("Authorization"))
        assertEquals("text/event-stream", first.getHeader("Accept"))
    }

    @Test
    fun `keep-alive pings on one connection change nothing`() = runBlocking {
        h.signIn()
        val ping = "event: ping\ndata: {\"time\":\"t\"}\n\n"
        h.server.on("GET /api/events", stream(ping + ping + ping + "event: watch.changed\ndata: {}\n\n"))
        h.services.library.changes.test(timeout = 5.seconds) {
            awaitItem() // watch.changed
            // The reconnect is answered 404, so the stream is over: the pings sent nothing.
            withTimeout(5_000) { while (h.server.requests("GET /api/events").size < 2) delay(20) }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a 401 on the stream signs out and stops`() = runBlocking {
        h.signIn()
        h.server.always("GET /api/events", problem(401, "sign in required"))
        h.services.library.changes.test(timeout = 5.seconds) {
            withTimeout(5_000) { h.services.auth.user.first { it == null } }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(h.services.auth.user.value)
        assertEquals(1, h.server.requests("GET /api/events").size)
    }

    @Test
    fun `a replacement token on the same server reconnects with the new account context`() = runBlocking {
        h.signIn()
        h.server.on("GET /api/events",
            stream("event: ping\ndata: {}\n\n").setBodyDelay(1, java.util.concurrent.TimeUnit.DAYS),
            stream("event: ping\ndata: {}\n\n").setBodyDelay(1, java.util.concurrent.TimeUnit.DAYS),
        )
        h.services.library.changes.test(timeout = 5.seconds) {
            withTimeout(5_000) { while (h.server.requests("GET /api/events").isEmpty()) delay(10) }
            h.server.on("POST /api/auth/login", TestServer.json(TestServer.SIGN_IN.replace("tok-viewer", "replacement-token")))
            h.services.auth.signIn("viewer", "fixture-viewer-1")
            withTimeout(5_000) { while (h.server.requests("GET /api/events").size < 2) delay(10) }
            val streams = h.server.requests("GET /api/events")
            assertEquals("Bearer tok-viewer", streams[0].getHeader("Authorization"))
            assertEquals("Bearer replacement-token", streams[1].getHeader("Authorization"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `signed out, nothing connects`() = runBlocking {
        h.select()
        h.services.library.changes.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, h.server.requests("GET /api/events").size)
    }

    private fun stream(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
}
