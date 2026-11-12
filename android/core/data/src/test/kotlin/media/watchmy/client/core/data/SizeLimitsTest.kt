package media.watchmy.client.core.data

import kotlinx.coroutines.runBlocking
import media.watchmy.client.model.ApiException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Replies and events stop at a size, so a broken or hostile server can't fill memory. */
class SizeLimitsTest {
    private val web = MockWebServer().apply { start() }
    private val client = OkHttpClient.Builder().addInterceptor(ReplyLimit(maxBytes = 100)).build()

    @After
    fun tearDown() = web.shutdown()

    private fun get(accept: String? = null): String {
        val request = Request.Builder().url(web.url("/api/home")).apply { accept?.let { header("Accept", it) } }.build()
        return client.newCall(request).execute().use { it.body.string() }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `a reply within the limit reads whole`() {
        web.enqueue(json("x".repeat(100)))
        assertEquals(100, get().length)
    }

    @Test
    fun `a reply over the limit stops, whether or not it says its length`() {
        web.enqueue(json("x".repeat(101)))
        expectTooLarge { get() }
        web.enqueue(MockResponse().setHeader("Content-Type", "application/json").setChunkedBody("x".repeat(500), 64))
        expectTooLarge { get() }
        // An error page is read whole too (the generated client reads it as text).
        web.enqueue(MockResponse().setResponseCode(502).setHeader("Content-Type", "text/html").setChunkedBody("x".repeat(500), 64))
        expectTooLarge { get() }
    }

    @Test
    fun `images and the event stream are streamed, outside the limit`() {
        web.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody("x".repeat(500)))
        assertEquals(500, get().length)
        web.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody("x".repeat(500), 64))
        assertEquals(500, get().length)
        web.enqueue(MockResponse().setChunkedBody("x".repeat(500), 64))
        assertEquals(500, get(accept = "text/event-stream").length)
    }

    @Test
    fun `the app's calls refuse a reply over 16 MB as one they don't understand`() = runBlocking {
        Harness().use { h ->
            h.signIn()
            // The length alone is enough: nothing is read.
            h.server.on("GET /api/home", TestServer.json("{}").setHeader("Content-Length", (ReplyLimit.MAX_REPLY_BYTES + 1).toString()))
            try {
                h.services.library.home()
                fail("expected ApiException")
            } catch (e: ApiException) {
                assertEquals("The server sent a reply this app doesn't understand.", e.message)
            }
        }
    }

    @Test
    fun `an event-stream line over the limit ends the connection, and a last unfinished one is dropped`() {
        val lines = mutableListOf<String>()
        readEventLines(Buffer().writeUtf8("event: ping\r\ndata: 12345\n\n"), maxLineBytes = 11) { lines += it }
        assertEquals(listOf("event: ping", "data: 12345", ""), lines)

        lines.clear()
        readEventLines(Buffer().writeUtf8("data: 1\ndata: unfinished"), maxLineBytes = 64) { lines += it }
        assertEquals(listOf("data: 1"), lines)

        lines.clear()
        try {
            readEventLines(Buffer().writeUtf8("data: 1\ndata: 123456789012345\n"), maxLineBytes = 11) { lines += it }
            fail("expected EventTooLargeException")
        } catch (_: EventTooLargeException) {
        }
        assertEquals(listOf("data: 1"), lines)
    }

    @Test
    fun `an event whose data runs past the limit ends the connection`() {
        val events = mutableListOf<ServerEvent>()
        val parser = SseParser(maxDataChars = 10) { events += it }
        listOf("data: 12345", "data: 1234", "").forEach(parser::line)
        assertEquals(listOf(ServerEvent("message", "12345\n1234")), events)
        parser.line("data: 123456")
        try {
            parser.line("data: 12345")
            fail("expected EventTooLargeException")
        } catch (_: EventTooLargeException) {
        }
    }

    private fun expectTooLarge(block: () -> Unit) {
        try {
            block()
            fail("expected ReplyTooLargeException")
        } catch (_: ReplyTooLargeException) {
        }
    }
}
