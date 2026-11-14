package media.watchmy.client.core.data

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.Server
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Start-up away from the server's network, and the player's client (PLAN-ANDROID-REVIEW.md §4.1.6, §4.2.1). */
class ReachabilityTest {
    @Test
    fun `local addresses are told apart without a lookup`() {
        for (host in listOf("127.0.0.1", "localhost", "::1", "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.178", "169.254.1.1",
            "fe80::1", "fd12:3456::1", "nas", "media-server.local", "box.lan", "tv.home.arpa")) {
            assertTrue(host, isPrivateHost(host))
        }
        for (host in listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "192.169.0.1", "watchmy.media", "2001:db8::1", "")) {
            assertFalse(host, isPrivateHost(host))
        }
        assertTrue(isLoopbackHost("127.0.0.1"))
        assertFalse(isLoopbackHost("192.168.1.2"))
    }

    @Test
    fun `an address on the local network gets three seconds to connect`() = runBlocking {
        val timeouts = CopyOnWriteArrayList<Int>()
        val base = OkHttpClient.Builder().addNetworkInterceptor { chain -> timeouts += chain.connectTimeoutMillis(); chain.proceed(chain.request()) }.build()
        Harness(httpClient = base).use { h ->
            h.signIn()
            h.server.on("GET /api/libraries", TestServer.json("""{"items":[]}"""))
            h.services.library.libraries()
            assertTrue(timeouts.isNotEmpty())
            assertTrue(timeouts.toString(), timeouts.all { it == Http.PRIVATE_CONNECT_MS })
        }
    }

    @Test
    fun `with no network a call fails at once, without waiting to connect`() = runBlocking {
        val network = TestNetwork()
        Harness(network = network).use { h ->
            h.signIn()
            network.network.value = null
            // A server on the internet can't be reached with no network: no connection is tried.
            val started = System.nanoTime()
            try {
                h.services.servers.probe("http://192.0.2.1:9")
                fail("expected the probe to fail")
            } catch (e: ApiException) {
                assertTrue(e.message, e.message!!.startsWith("Couldn't reach"))
            }
            assertTrue("failed at once", (System.nanoTime() - started) / 1_000_000 < 1_000)
            // This device itself still answers.
            val before = h.server.requests("GET /api/libraries").size
            h.server.on("GET /api/libraries", TestServer.json("""{"items":[]}"""))
            h.services.library.libraries()
            assertEquals(before + 1, h.server.requests("GET /api/libraries").size)
        }
    }

    @Test
    fun `the player's client shares the API's connections, without its token, limit or cache`() = runBlocking {
        Harness(httpCache = true).use { h ->
            h.signIn()
            val media = h.services.mediaHttpClient
            assertSame(h.services.imageHttpClient.connectionPool, media.connectionPool)
            assertNull(media.cache)
            assertEquals(15_000, media.connectTimeoutMillis)
            assertEquals(45_000, media.readTimeoutMillis)
            assertEquals(0, media.callTimeoutMillis)
            assertFalse(media.followSslRedirects)
            h.server.on("GET /api/files/4/stream", TestServer.noContent())
            media.newCall(Request.Builder().url("${h.server.baseUrl}/api/files/4/stream?access_token=m1").build()).execute().close()
            assertNull(h.server.last("GET /api/files/4/stream").getHeader("Authorization"))
        }
    }
}
