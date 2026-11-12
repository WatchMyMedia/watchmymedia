package media.watchmy.client.core.data

import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.noContent
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.Server
import media.watchmy.client.model.ServerTooOldException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import okhttp3.mockwebserver.MockResponse

class ServerRepositoryTest {
    private val h = Harness()
    private val server get() = h.server
    private val servers get() = h.services.servers

    @After
    fun tearDown() = h.close()

    @Test
    fun `probe asks the server who it is`() = runBlocking {
        server.on("GET /api/server", json(INFO.format(1)))
        val found = servers.probe("127.0.0.1:${server.web.port}")
        assertEquals(Server("2321341d1a4a571c275b88b68f88d3c5", "Living room server", "http://127.0.0.1:${server.web.port}", "0.9.3", 1), found)
        assertNull("probing needs no sign-in", server.last("GET /api/server").getHeader("Authorization"))
        assertNull("probing does not choose", servers.current.value)
    }

    @Test
    fun `a server that wants a browser check says so, and is remembered that way`() = runBlocking {
        server.on("GET /api/server", json(INFO.format(1).replace("\"turnstile\":false", "\"turnstile\":true")))
        val found = servers.probe("127.0.0.1:${server.web.port}")
        assertTrue(found.turnstile)
        servers.select(found)
        assertTrue(servers.current.value!!.server.turnstile)
    }

    @Test
    fun `probe refuses a server that is too old`() = runBlocking {
        server.on("GET /api/server", json(INFO.format(0)))
        try {
            servers.probe(server.baseUrl)
            fail("expected ServerTooOldException")
        } catch (e: ServerTooOldException) {
            assertEquals("Living room server", e.server.name)
            assertTrue(e.message!!.contains("0.9.3"))
        }
    }

    @Test
    fun `probe says when nothing answers and when something else does`() = runBlocking {
        try {
            servers.probe("127.0.0.1:1")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.message, e.message!!.startsWith("Couldn't reach 127.0.0.1:1"))
        }
        server.on("GET /api/server", MockResponse().setHeader("Content-Type", "text/html").setBody("<html>router</html>"))
        try {
            servers.probe(server.baseUrl)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.message, e.message!!.contains("doesn't look like a WatchMyMedia server"))
        }
    }

    @Test
    fun `select remembers servers, most recent first, and forget drops one`() = runBlocking {
        val a = Server("a", "A", "http://10.0.0.1:27587", "1", 1)
        val b = Server("b", "B", "http://10.0.0.2:27587", "1", 1)
        h.now = 1_000
        servers.select(a)
        h.now = 2_000
        servers.select(b)
        assertEquals(listOf("b", "a"), servers.saved.first().map { it.server.id })
        assertEquals("b", servers.current.value!!.server.id)

        // The same server at a new address keeps its place and its ID.
        h.now = 3_000
        servers.select(a.copy(baseUrl = "http://10.0.0.9:27587"))
        assertEquals(listOf("a", "b"), servers.saved.first().map { it.server.id })
        assertEquals("http://10.0.0.9:27587", servers.current.value!!.server.baseUrl)

        val restarted = h.reopen()
        assertEquals("a", restarted.servers.current.value!!.server.id)
        assertEquals(listOf("a", "b"), restarted.servers.saved.first().map { it.server.id })

        restarted.servers.forget("a")
        assertNull(restarted.servers.current.value)
        assertEquals(listOf("b"), restarted.servers.saved.first().map { it.server.id })
    }

    @Test
    fun `forgetting the current server signs out there too`() = runBlocking {
        h.signIn()
        server.on("POST /api/auth/logout", noContent())
        servers.forget(h.testServer.id)
        assertNull(h.services.auth.user.value)
        assertFalse(servers.saved.first().any { it.server.id == h.testServer.id })
        val logout = waitFor("POST /api/auth/logout")
        assertEquals("Bearer tok-viewer", logout.getHeader("Authorization"))
    }

    @Test
    fun `discovered services become server entries`() {
        val v4 = NsdDiscovery.discoveredServer(
            "Living room server",
            InetAddress.getByName("192.168.1.20"),
            27587,
            mapOf("id" to "abc", "version" to "0.9.3", "api" to "1", "path" to "/"),
        )
        assertEquals("abc", v4.id)
        assertEquals("http://192.168.1.20:27587", v4.baseUrl)
        assertEquals(1, v4.apiLevel)
        val v6 = NsdDiscovery.discoveredServer("NAS", InetAddress.getByName("fd00::5"), 8080, mapOf("path" to "/wmm/", "api" to "x"))
        assertEquals("http://[fd00:0:0:0:0:0:0:5]:8080/wmm", v6.baseUrl)
        assertEquals("NAS", v6.id)
        assertEquals(0, v6.apiLevel)
    }

    @Test
    fun `a level read before the sign-in changed doesn't overwrite the newer one`() = runBlocking {
        server.always("GET /api/server", json(TEST_INFO.format(3)))
        h.signIn()
        withTimeout(5_000) { servers.current.first { it?.server?.apiLevel == 3 } }

        // An older refresh reads level 5, and its answer is held back...
        server.always("GET /api/server", json(TEST_INFO.format(5)))
        val held = server.hold("GET /api/server")
        val stale = async(Dispatchers.IO) { runCatching { (servers as RealServerRepository).refreshCurrent() } }
        held.awaitArrival()
        // ...while signing in again reads level 6.
        server.always("GET /api/server", json(TEST_INFO.format(6)))
        server.on("POST /api/auth/login", json(TestServer.SIGN_IN.replace("tok-viewer", "tok-viewer-2")))
        h.services.auth.signIn("viewer", "fixture-viewer-1")
        withTimeout(5_000) { servers.current.first { it?.server?.apiLevel == 6 } }

        held.release()
        stale.await()
        assertEquals(6, servers.current.value?.server?.apiLevel)
    }

    @Test
    fun `a level read before the server was chosen again doesn't overwrite what its probe said`() = runBlocking {
        server.always("GET /api/server", json(TEST_INFO.format(3)))
        h.signIn()
        withTimeout(5_000) { servers.current.first { it?.server?.apiLevel == 3 } }

        val held = server.hold("GET /api/server")
        val stale = async(Dispatchers.IO) { runCatching { (servers as RealServerRepository).refreshCurrent() } }
        held.awaitArrival()
        servers.select(h.testServer.copy(apiLevel = 6))
        held.release()
        stale.await()
        assertEquals(6, servers.current.value?.server?.apiLevel)
    }

    /** Waits for the background logout, which forget() does not wait for. */
    private fun waitFor(route: String): okhttp3.mockwebserver.RecordedRequest {
        repeat(100) {
            server.requests(route).lastOrNull()?.let { return it }
            Thread.sleep(20)
        }
        error("no request to $route")
    }

    private companion object {
        const val TEST_INFO = """{"id":"srv-test","name":"Test server","version":"1.3.0","apiLevel":%d,"setupRequired":false,"turnstile":false}"""
        const val INFO = """{"id":"2321341d1a4a571c275b88b68f88d3c5","name":"Living room server","version":"0.9.3","apiLevel":%d,"setupRequired":false,"turnstile":false}"""
    }
}
