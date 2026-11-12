package media.watchmy.client.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.problem
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.PairingState
import media.watchmy.client.model.Server
import media.watchmy.client.model.User
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV's pairing poll (RealAuthRepository.pair): what each kind of failed
 * claim does, with the wait between claims recorded instead of slept and a
 * clock the test moves.
 */
class PairingPollTest {
    private val server = TestServer()
    private val dir: File = Files.createTempDirectory("wmm-pair").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = LocalStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "p.preferences_pb") }, scope, PlainSealer)
    private val http = Http(OkHttpClient(), store, "WatchMyMedia/test")
    private var now = EXPIRES_AT - 60_000
    private val servers = RealServerRepository(store, http, ServerDiscovery.None, scope) { now }
    private val pauses = mutableListOf<Long>()
    private var onPause: () -> Unit = {}
    private val client = ClientInfo("dev-1", "Living room TV", "WatchMyMedia for Android TV", "androidtv", "0.1.0")
    private val auth = RealAuthRepository(store, http, servers, { client }, { now }) { ms ->
        pauses += ms
        onPause()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        dir.deleteRecursively()
    }

    private fun select() = runBlocking { servers.select(Server("srv-test", "Test server", server.baseUrl, "1.0.0", 7)) }

    private fun start(intervalMs: Long = 2_000) = server.on("POST /api/auth/pair", json(PAIR_START.format(intervalMs)))

    @Test
    fun `a busy server keeps polling while the code lasts`() = runBlocking {
        select()
        start()
        server.on("POST /api/auth/pair/claim", problem(503, "busy"), problem(500, "oops"), json(PENDING), json(APPROVED))
        auth.pair().test {
            assertEquals(PairingState.Starting, awaitItem())
            assertTrue(awaitItem() is PairingState.Waiting)
            assertEquals(PairingState.Approved(User(2, "viewer", false)), awaitItem())
            awaitComplete()
        }
        assertEquals(4, server.requests("POST /api/auth/pair/claim").size)
        assertEquals("One wait of the server's interval before each claim", List(4) { 2_000L }, pauses)
        assertEquals(User(2, "viewer", false), auth.user.value)
    }

    @Test
    fun `an empty reply to a claim is polled on, as a dropped connection is`() = runBlocking {
        select()
        start()
        server.on("POST /api/auth/pair/claim", MockResponse().setResponseCode(200), json(APPROVED))
        auth.pair().test {
            awaitItem()
            awaitItem()
            assertEquals(PairingState.Approved(User(2, "viewer", false)), awaitItem())
            awaitComplete()
        }
        assertEquals(2, pauses.size)
    }

    @Test
    fun `a busy server after the code ran out is an expired code`() = runBlocking {
        select()
        start()
        server.on("POST /api/auth/pair/claim", problem(502, "bad gateway"))
        onPause = { now = EXPIRES_AT + 1 }
        auth.pair().test {
            assertEquals(PairingState.Starting, awaitItem())
            assertTrue(awaitItem() is PairingState.Waiting)
            assertEquals(PairingState.Expired, awaitItem())
            awaitComplete()
        }
        assertEquals(1, server.requests("POST /api/auth/pair/claim").size)
        assertNull(auth.user.value)
    }

    @Test
    fun `a server that can't be reached is polled until the code runs out`() = runBlocking {
        select()
        start()
        // The server goes away once the code is up; the third wait takes the clock past its expiry.
        onPause = {
            if (pauses.size == 1) server.close()
            if (pauses.size == 3) now = EXPIRES_AT + 1
        }
        auth.pair().test {
            awaitItem()
            awaitItem()
            assertEquals(PairingState.Expired, awaitItem())
            awaitComplete()
        }
        assertEquals("Two claims failed while the code lasted, the third after", 3, pauses.size)
    }

    @Test
    fun `a refused claim fails with the server's message and stops`() = runBlocking {
        select()
        start()
        server.on("POST /api/auth/pair/claim", json(PENDING), problem(403, "pairing is turned off on this server"))
        auth.pair().test {
            awaitItem()
            awaitItem()
            assertEquals(PairingState.Failed("Pairing is turned off on this server."), awaitItem())
            awaitComplete()
        }
        assertEquals(2, server.requests("POST /api/auth/pair/claim").size)
    }

    @Test
    fun `an approval without a sign-in says to update the server`() = runBlocking {
        select()
        start()
        server.on("POST /api/auth/pair/claim", json("""{"status":"approved","user":{"id":2,"name":"viewer","isAdmin":false}}"""))
        auth.pair().test {
            awaitItem()
            awaitItem()
            assertEquals(PairingState.Failed("The server approved the code but sent no sign-in. Update the server."), awaitItem())
            awaitComplete()
        }
        assertNull(auth.user.value)
    }

    @Test
    fun `the server's interval is kept between a quarter second and ten seconds`() = runBlocking {
        select()
        for ((asked, used) in listOf(10L to 250L, 60_000L to 10_000L)) {
            pauses.clear()
            start(asked)
            server.on("POST /api/auth/pair/claim", json(APPROVED))
            auth.pair().test {
                awaitItem()
                assertEquals(used, (awaitItem() as PairingState.Waiting).code.pollIntervalMs)
                awaitItem()
                awaitComplete()
            }
            assertEquals(listOf(used), pauses)
        }
    }

    @Test
    fun `without a server there is nothing to pair with`() = runBlocking {
        auth.pair().test {
            assertEquals(PairingState.Starting, awaitItem())
            assertEquals(PairingState.Failed("Choose a server first."), awaitItem())
            awaitComplete()
        }
        assertTrue(server.requests.isEmpty())
    }

    private companion object {
        /** 2026-10-29T17:20:20Z. */
        const val EXPIRES_AT = 1_793_294_420_000L
        const val PAIR_START = """{"code":"BPEULY","secret":"s3cret","expiresAt":"2026-10-29T17:20:20Z","intervalMs":%d,"linkPath":"/link?code=BPEULY"}"""
        const val PENDING = """{"status":"pending"}"""
        const val APPROVED = """{"status":"approved","user":{"id":2,"name":"viewer","isAdmin":false},"sessionId":"9b4fb25394fb84df",""" +
            """"token":"tok-paired","expiresAt":"2027-10-29T17:10:21Z"}"""
    }
}
