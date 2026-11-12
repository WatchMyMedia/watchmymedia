package media.watchmy.client.core.data

import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.PairingState
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Which redirects the app follows: none that would carry a password, a secret or a token somewhere else. */
class RedirectsTest {
    private var h = Harness()
    private val server get() = h.server

    @After
    fun tearDown() = h.close()

    private fun redirect(code: Int, location: String) = MockResponse().setResponseCode(code).setHeader("Location", location)

    @Test
    fun `signing in follows no redirect, so the password goes nowhere else`() = runBlocking {
        h.select()
        server.on("POST /api/auth/login", redirect(307, "/elsewhere/login"))
        try {
            h.services.auth.signIn("viewer", "fixture-viewer-1")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("The server sent a reply this app doesn't understand.", e.message)
        }
        assertEquals(1, server.requests("POST /api/auth/login").size)
        assertTrue("the password was not sent on", server.requests("POST /elsewhere/login").isEmpty())
    }

    @Test
    fun `pairing follows no redirect, so the secret goes nowhere else`() = runBlocking {
        h.select()
        server.on("POST /api/auth/pair", json(PAIR_START))
        // A redirect reads as a failed poll, and this code has run out (the harness's clock is past it).
        server.on("POST /api/auth/pair/claim", redirect(308, "/elsewhere/claim"))
        h.services.auth.pair().test {
            assertEquals(PairingState.Starting, awaitItem())
            assertTrue(awaitItem() is PairingState.Waiting)
            assertEquals(PairingState.Expired, awaitItem())
            awaitComplete()
        }
        assertEquals(1, server.requests("POST /api/auth/pair/claim").size)
        assertTrue("the secret was not sent on", server.requests("POST /elsewhere/claim").isEmpty())

        server.on("POST /api/auth/pair", redirect(307, "/elsewhere/pair"))
        h.services.auth.pair().test {
            assertEquals(PairingState.Starting, awaitItem())
            assertEquals(PairingState.Failed("The server sent a reply this app doesn't understand."), awaitItem())
            awaitComplete()
        }
        assertTrue(server.requests("POST /elsewhere/pair").isEmpty())
    }

    @Test
    fun `signed-in calls never follow a redirect to another scheme`() = runBlocking {
        h.signIn()
        server.on("GET /api/home", redirect(301, "https://${server.web.hostName}:${server.web.port}/api/home"))
        try {
            h.services.library.home()
            fail("expected ApiException")
        } catch (e: ApiException) {
            // Followed, it would have failed to reach a server that has no TLS.
            assertEquals("The server sent a reply this app doesn't understand.", e.message)
        }
        assertEquals(1, server.requests("GET /api/home").size)
    }

    @Test
    fun `a probe follows no redirect to another host`() = runBlocking {
        server.on("GET /api/server", redirect(302, "http://example.invalid/api/server"))
        try {
            h.services.servers.probe(server.baseUrl)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.message, e.message!!.contains("doesn't look like a WatchMyMedia server"))
        }
        assertEquals(1, server.requests("GET /api/server").size)
    }

    @Test
    fun `a probe redirected from http to https on the same host keeps the https address`() = runBlocking {
        // TLS is out of reach of MockWebServer here: the client carries https requests to it as http,
        // as a proxy would, so the test sees which address the probe asked and kept.
        h.close()
        val asked = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            asked += request.url.scheme
            chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").build()).build())
        }.build()
        h = Harness(httpClient = client)
        val host = "${server.web.hostName}:${server.web.port}"
        server.on("GET /api/server", redirect(301, "https://$host/api/server"), json(INFO))
        val found = h.services.servers.probe("http://$host")
        assertEquals("https://$host", found.baseUrl)
        assertEquals(listOf("http", "https"), asked)
    }

    private companion object {
        const val PAIR_START = """{"code":"BPEULY","secret":"s3cret","expiresAt":"2026-10-29T17:20:20Z","intervalMs":20,"linkPath":"/link?code=BPEULY"}"""
        const val INFO = """{"id":"2321341d1a4a571c275b88b68f88d3c5","name":"Living room server","version":"0.9.3","apiLevel":1,"setupRequired":false,"turnstile":false}"""
    }
}
