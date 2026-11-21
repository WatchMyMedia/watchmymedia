package media.watchmy.client.core.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import media.watchmy.client.core.api.apis.WatchApi
import media.watchmy.client.model.PlayState
import media.watchmy.client.model.ProgressReport
import media.watchmy.client.model.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.OkHttpClient

/** Queued work retains the identity that started it. */
class SessionIsolationTest {
    @Test
    fun `sign in finishing after an endpoint switch cannot install its token`() = runBlocking {
        Harness().use { h ->
            h.select()
            val reached = CountDownLatch(1)
            val resume = CountDownLatch(1)
            h.server.web.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    if (request.path == "/api/auth/login") {
                        reached.countDown()
                        check(resume.await(10, TimeUnit.SECONDS))
                        return TestServer.json(TestServer.SIGN_IN)
                    }
                    return TestServer.noContent()
                }
            }
            val request = async(Dispatchers.IO) { h.services.auth.signIn("viewer", "fixture-viewer-1") }
            try {
                assertTrue(reached.await(10, TimeUnit.SECONDS))
                h.services.servers.select(h.testServer.copy(baseUrl = h.server.baseUrl + "/different"))
            } finally {
                resume.countDown()
            }
            assertTrue(runCatching { request.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(null, h.services.auth.user.value)
            assertEquals(null, h.services.storedToken())
        }
    }

    @Test
    fun `a public server id cannot transfer credentials to a new endpoint`() = runBlocking {
        Harness().use { h ->
            h.signIn()
            val original = h.services.servers.current.value!!.server
            h.services.servers.select(original.copy(baseUrl = original.baseUrl + "/different-proxy"))
            assertEquals(null, h.services.auth.user.value)
            assertEquals(null, h.services.storedToken())
        }
    }

    @Test
    fun `a retained playback gateway sends the old report to its original server, once that has said which server it is`() = runBlocking {
        Harness().use { h ->
            TestServer().use { other ->
                h.signIn()
                val oldPlaybackGateway = h.services.playbackGateway.bind()
                val queuedOldReport = ProgressReport(4, 12_000, 60_000, "1111111111111111", PlayState.Stopped, 0)
                h.services.servers.select(Server("server-b", "Server B", other.baseUrl, "1.0.0", 3))
                other.on("POST /api/auth/login", TestServer.json(TestServer.SIGN_IN.replace("tok-viewer", "token-b")))
                h.services.auth.signIn("viewer", "fixture-viewer-1")
                h.services.awaitIdle()
                h.server.always("PUT /api/files/4/progress", TestServer.json(
                    """{"completed":false,"durationMs":60000,"positionMs":12000,"updatedAt":"2026-10-31T12:00:00Z"}""",
                ))
                h.server.always("GET /api/server", TestServer.json(TEST_INFO))
                h.server.requests.clear()
                oldPlaybackGateway.reportProgress(queuedOldReport)
                // Its server is no longer the one in use: asked which server it is, with no sign-in, then sent the report.
                assertEquals(listOf("GET /api/server", "PUT /api/files/4/progress"), h.server.requests.map { "${it.method} ${it.requestUrl!!.encodedPath}" })
                assertEquals(null, h.server.requests[0].getHeader("Authorization"))
                val sent = h.server.last("PUT /api/files/4/progress")
                assertEquals("Bearer tok-viewer", sent.getHeader("Authorization"))
                assertTrue(sent.body.readUtf8().contains("1111111111111111"))
                assertTrue(other.requests("PUT /api/files/4/progress").isEmpty())
            }
        }
    }

    @Test
    fun `a retained playback gateway sends nothing to another server at its original server's address`() = runBlocking {
        Harness().use { h ->
            TestServer().use { other ->
                h.signIn()
                val oldPlaybackGateway = h.services.playbackGateway.bind()
                h.services.servers.select(Server("server-b", "Server B", other.baseUrl, "1.0.0", 3))
                other.on("POST /api/auth/login", TestServer.json(TestServer.SIGN_IN.replace("tok-viewer", "token-b")))
                h.services.auth.signIn("viewer", "fixture-viewer-1")
                h.services.awaitIdle()
                h.server.always("PUT /api/files/4/progress", TestServer.json(
                    """{"completed":false,"durationMs":60000,"positionMs":12000,"updatedAt":"2026-10-31T12:00:00Z"}""",
                ))
                // The app moved to another server, and the device to a network where the first one's address is someone else's.
                h.server.always("GET /api/server", TestServer.json(TEST_INFO.replace("srv-test", "someone-else")))
                h.server.requests.clear()
                val report = runCatching { oldPlaybackGateway.reportProgress(ProgressReport(4, 12_000, 60_000, "1111111111111111", PlayState.Stopped, 0)) }
                assertTrue("the report fails, as one that can't reach its server does", report.exceptionOrNull() is media.watchmy.client.model.ApiException)
                assertTrue(h.server.requests("PUT /api/files/4/progress").isEmpty())
                assertTrue("no request to it carried a sign-in", h.server.requests.none { it.getHeader("Authorization") != null })
                assertTrue(other.requests("PUT /api/files/4/progress").isEmpty())
            }
        }
    }

    @Test
    fun `a queued mutation retains its account and discards the stale response`() = runBlocking {
        Harness().use { h ->
            val store = LocalStore(h.dataStore, h.scope, PlainSealer)
            val accountA = StoredAccount(2, "viewer-a", false, "session-a", store.seal("token-a"))
            val accountB = StoredAccount(3, "viewer-b", false, "session-b", store.seal("token-b"))
            store.updateAndSave {
                it.copy(currentId = "audit-server", servers = listOf(StoredServer(
                    "audit-server", "Audit server", h.server.baseUrl, "1.0.0", 3, account = accountA,
                )))
            }
            val reached = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val base = OkHttpClient.Builder().addInterceptor { chain ->
                reached.countDown()
                check(resume.await(10, TimeUnit.SECONDS))
                chain.proceed(chain.request())
            }.build()
            val http = Http(base, store, "Audit")
            h.server.always("POST /api/watch/movie/4/watched", TestServer.noContent())
            val request = async(Dispatchers.IO) {
                http.signedIn { baseUrl, client -> WatchApi(baseUrl, client).markWatched(WatchApi.TypeMarkWatched.movie, 4) }
            }
            try {
                assertTrue(reached.await(10, TimeUnit.SECONDS))
                store.updateAndSave { s -> s.withServer("audit-server") { it.copy(account = accountB) } }
            } finally {
                resume.countDown()
            }
            assertTrue(runCatching { request.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals("Bearer token-a", h.server.requests.single().getHeader("Authorization"))
        }
    }

    @Test
    fun `one interceptor sends each request's own token, and only to its server`() = runBlocking {
        Harness().use { h ->
            TestServer().use { other ->
                h.signIn()
                // An image (no credential of its own) goes as the current sign-in, to its server only.
                h.server.always("GET /api/images/w342/a.jpg", TestServer.noContent())
                other.always("GET /api/images/w342/a.jpg", TestServer.noContent())
                for (base in listOf(h.server.baseUrl, other.baseUrl)) {
                    h.services.imageHttpClient.newCall(okhttp3.Request.Builder().url("$base/api/images/w342/a.jpg").build()).execute().close()
                }
                assertEquals("Bearer tok-viewer", h.server.last("GET /api/images/w342/a.jpg").getHeader("Authorization"))
                assertEquals(null, other.last("GET /api/images/w342/a.jpg").getHeader("Authorization"))

                // A cast token goes as itself, never as this device's sign-in, and its 401 signs nobody out here.
                other.on("GET /api/me/preferences", TestServer.json(TestServer.PREFERENCES), TestServer.problem(401, "sign in required"))
                val cast = h.services.castPlayback(ReachedServer(other.baseUrl, 27), "c1.cast", 1_900_000_000_000, null)
                cast.preferences.refresh()
                assertEquals("Bearer c1.cast", other.last("GET /api/me/preferences").getHeader("Authorization"))
                assertTrue(runCatching { cast.preferences.refresh() }.isFailure)
                assertEquals("viewer", h.services.auth.user.value?.name)
                assertTrue(h.server.requests.none { it.getHeader("Authorization") == "Bearer c1.cast" })
            }
        }
    }

    private companion object {
        /** What the harness's test server says of itself. */
        const val TEST_INFO = """{"id":"srv-test","name":"Test server","version":"1.0.0","apiLevel":5,"setupRequired":false,"turnstile":false}"""
    }
}
