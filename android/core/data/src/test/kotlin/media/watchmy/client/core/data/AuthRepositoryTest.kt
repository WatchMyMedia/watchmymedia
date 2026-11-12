package media.watchmy.client.core.data

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.noContent
import media.watchmy.client.core.data.TestServer.Companion.problem
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.DeviceSession
import media.watchmy.client.model.PairingState
import media.watchmy.client.model.SignedOutException
import media.watchmy.client.model.User
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AuthRepositoryTest {
    private val h = Harness()
    private val server get() = h.server

    @After
    fun tearDown() = h.close()

    @Test
    fun `sign-in sends the device, keeps the token and signs later calls`() = runBlocking {
        val user = h.signIn()

        assertEquals(User(2, "viewer", false), user)
        assertEquals(user, h.services.auth.user.value)
        val login = server.last("POST /api/auth/login")
        assertNull("no bearer on sign-in", login.getHeader("Authorization"))
        val body = h.body(login)
        assertEquals("viewer", body["name"]!!.jsonPrimitive.content)
        val client = body["client"]!!.jsonObject
        assertEquals("dev-1", client["deviceId"]!!.jsonPrimitive.content)
        assertEquals("Living room TV", client["name"]!!.jsonPrimitive.content)
        assertEquals("androidtv", client["platform"]!!.jsonPrimitive.content)
        assertEquals("WatchMyMedia for Android TV", client["product"]!!.jsonPrimitive.content)
        assertEquals("Chromecast with Google TV", client["model"]!!.jsonPrimitive.content)

        server.on("GET /api/auth/sessions", json(SESSIONS))
        h.services.auth.sessions()
        assertEquals("Bearer tok-viewer", server.last("GET /api/auth/sessions").getHeader("Authorization"))

        val saved = h.services.servers.current.value!!
        assertTrue(saved.signedIn)
        assertEquals("viewer", saved.userName)
    }

    @Test
    fun `the sign-in survives a restart`() = runBlocking {
        h.signIn()
        val again = h.reopen()
        assertEquals(User(2, "viewer", false), again.auth.user.value)
        assertEquals("srv-test", again.servers.current.value!!.server.id)
    }

    @Test
    fun `a wrong password is a 401 with the server's message and signs nobody in`() = runBlocking {
        h.select()
        server.on("POST /api/auth/login", problem(401, "invalid name or password"))
        try {
            h.services.auth.signIn("viewer", "nope")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertFalse(e is SignedOutException)
            assertEquals(401, e.status)
            assertEquals("Invalid name or password.", e.message)
        }
        assertNull(h.services.auth.user.value)
    }

    @Test
    fun `throttled sign-in is a 429`() = runBlocking {
        h.select()
        server.on(
            "POST /api/auth/login",
            problem(429, "too many failed sign-ins; try again in 15 minutes").setHeader("Retry-After", "900"),
        )
        try {
            h.services.auth.signIn("viewer", "nope")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(429, e.status)
            assertEquals("Too many failed sign-ins; try again in 15 minutes.", e.message)
        }
    }

    @Test
    fun `a 401 on a signed-in call clears the sign-in and throws SignedOutException`() = runBlocking {
        h.signIn()
        server.on("GET /api/home", problem(401, "sign in required"))
        try {
            h.services.library.home()
            fail("expected SignedOutException")
        } catch (_: SignedOutException) {
        }
        assertNull(h.services.auth.user.value)
        val saved = h.services.servers.current.value!!
        assertFalse(saved.signedIn)
        assertEquals("viewer", saved.userName)

        // Signed out, calls fail at once without asking the server.
        val before = server.requests("GET /api/home").size
        try {
            h.services.library.home()
            fail("expected SignedOutException")
        } catch (_: SignedOutException) {
        }
        assertEquals(before, server.requests("GET /api/home").size)
        // And the cleared sign-in is what a restart sees, once it is written.
        withTimeout(5_000) { h.dataStore.data.first { LocalStore.decode(it).current?.account == null } }
        assertNull(h.reopen().auth.user.value)
    }

    @Test
    fun `a token the Keystore can no longer open signs that account out, as a 401 does`() = runBlocking<Unit> {
        val sealer = LosableSealer()
        val h = Harness(sealer)
        try {
            h.signIn()
            withTimeout(5_000) { h.dataStore.data.first { LocalStore.decode(it).current?.account != null } }
            // The Keystore loses its key; after a restart the stored token can't be opened.
            sealer.lost = true
            val restarted = h.reopen()
            withTimeout(5_000) { restarted.auth.user.first { it == null } }
            val saved = restarted.servers.current.value!!
            assertFalse(saved.signedIn)
            assertEquals("viewer", saved.userName)
            try {
                restarted.library.home()
                fail("expected SignedOutException")
            } catch (_: SignedOutException) {
            }
            assertTrue("nothing is asked without a token", h.server.requests("GET /api/home").isEmpty())
            withTimeout(5_000) { h.dataStore.data.first { LocalStore.decode(it).current?.account == null } }
        } finally {
            h.close()
        }
    }

    @Test
    fun `pairing shows the code, polls until approved and signs in`() = runBlocking {
        h.select()
        server.on("POST /api/auth/pair", json(PAIR_START))
        server.on(
            "POST /api/auth/pair/claim",
            json("""{"status":"pending"}"""),
            json("""{"status":"pending"}"""),
            json(
                """{"status":"approved","user":{"id":2,"name":"viewer","isAdmin":false},"sessionId":"9b4fb25394fb84df",""" +
                    """"token":"tok-paired","expiresAt":"2027-10-29T17:10:21Z"}""",
            ),
        )
        h.services.auth.pair().test {
            assertEquals(PairingState.Starting, awaitItem())
            val waiting = awaitItem() as PairingState.Waiting
            assertEquals("BPEULY", waiting.code.code)
            assertEquals("${server.baseUrl}/link?code=BPEULY", waiting.code.linkUrl)
            assertEquals(250L, waiting.code.pollIntervalMs)
            assertEquals(epochMs("2026-10-29T17:20:20Z"), waiting.code.expiresAtMs)
            assertEquals(PairingState.Approved(User(2, "viewer", false)), awaitItem())
            awaitComplete()
        }
        assertEquals(3, server.requests("POST /api/auth/pair/claim").size)
        assertEquals("""{"secret":"s3cret"}""", server.last("POST /api/auth/pair/claim").body.clone().readUtf8())
        assertEquals("androidtv", h.body(server.last("POST /api/auth/pair"))["client"]!!.jsonObject["platform"]!!.jsonPrimitive.content)
        assertEquals(User(2, "viewer", false), h.services.auth.user.value)

        server.on("GET /api/auth/sessions", json(SESSIONS))
        h.services.auth.sessions()
        assertEquals("Bearer tok-paired", server.last("GET /api/auth/sessions").getHeader("Authorization"))
    }

    @Test
    fun `the pairing link stays on the server`() {
        fun link(base: String, path: String) = RealAuthRepository.pairingLink(base, path, "BPEULY")
        assertEquals("http://192.168.1.20:27587/link?code=BPEULY", link("http://192.168.1.20:27587", "/link?code=BPEULY"))
        assertEquals("https://example.net/watch/link?code=BPEULY", link("https://example.net/watch", "/link?code=BPEULY"))
        // Text that would have changed the host as a string join stays a path on the server.
        assertEquals("http://192.168.1.20:27587/@evil.example/", link("http://192.168.1.20:27587", "@evil.example/"))
        assertEquals("http://192.168.1.20:27587/evil.example/x", link("http://192.168.1.20:27587", "//evil.example/x"))
        // Anything that leaves the server falls back to its own page.
        for (path in listOf("http://evil.example/link?code=BPEULY", "\\\\evil.example/link", "https://192.168.1.20:27587/link", "../../link")) {
            assertEquals(path, "https://example.net/watch/link?code=BPEULY", link("https://example.net/watch", path))
        }
    }

    @Test
    fun `pairing reports a code that ran out as expired`() = runBlocking {
        h.select()
        server.on("POST /api/auth/pair", json(PAIR_START))
        server.on(
            "POST /api/auth/pair/claim",
            json("""{"status":"pending"}"""),
            problem(404, "that code is not waiting to be linked; check it, or ask the TV for a new one"),
        )
        h.services.auth.pair().test {
            assertEquals(PairingState.Starting, awaitItem())
            assertTrue(awaitItem() is PairingState.Waiting)
            assertEquals(PairingState.Expired, awaitItem())
            awaitComplete()
        }
        assertNull(h.services.auth.user.value)
    }

    @Test
    fun `pairing that cannot start fails with the server's message`() = runBlocking {
        h.select()
        server.on("POST /api/auth/pair", problem(429, "too many pairings from this address"))
        h.services.auth.pair().test {
            assertEquals(PairingState.Starting, awaitItem())
            assertEquals(PairingState.Failed("Too many pairings from this address."), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `the phone looks up and approves a code`() = runBlocking {
        h.signIn()
        val info = """{"code":"BPEULY","name":"Living room TV","product":"WatchMyMedia for Android TV","platform":"androidtv",""" +
            """"version":"0.1.0","model":"Chromecast with Google TV","ip":"192.168.1.31","expiresAt":"2026-10-29T17:20:20Z","approved":%s}"""
        server.on("GET /api/auth/pair/BPEULY", json(info.format("false")))
        server.on("POST /api/auth/pair/BPEULY/approve", json(info.format("true")))

        val pending = h.services.auth.lookUpPairing("bpe-uly")
        assertEquals("Living room TV", pending.name)
        assertEquals("192.168.1.31", pending.ip)
        assertEquals("Chromecast with Google TV", pending.model)
        assertFalse(pending.approved)
        assertTrue(h.services.auth.approvePairing("BPEULY").approved)
    }

    @Test
    fun `sessions map, and revoking this device's own session signs it out`() = runBlocking {
        h.signIn()
        server.on("GET /api/auth/sessions", json(SESSIONS))
        val sessions = h.services.auth.sessions(all = true)
        assertEquals("true", server.last("GET /api/auth/sessions").requestUrl!!.queryParameter("all"))
        assertEquals(2, sessions.size)
        val tv = sessions[0]
        assertEquals(DeviceSession.Kind.Device, tv.kind)
        assertTrue(tv.current)
        assertEquals(epochMs("2026-10-29T17:09:01.982Z"), tv.createdAtMs)
        assertEquals("Chromecast with Google TV", tv.model)
        assertEquals(DeviceSession.Kind.Browser, sessions[1].kind)
        assertNull(sessions[1].product)
        assertNull(sessions[1].model)

        server.on("DELETE /api/auth/sessions/0123456789abcdef", noContent())
        h.services.auth.revoke("0123456789abcdef")
        assertEquals(User(2, "viewer", false), h.services.auth.user.value)

        server.on("DELETE /api/auth/sessions/86e0d90b273fc14a", noContent())
        h.services.auth.revoke("86e0d90b273fc14a")
        assertNull(h.services.auth.user.value)
    }

    @Test
    fun `sign-out tells the server and forgets the token`() = runBlocking {
        h.signIn()
        server.on("POST /api/auth/logout", noContent())
        h.services.auth.signOut()
        assertNull(h.services.auth.user.value)
        assertEquals("Bearer tok-viewer", server.last("POST /api/auth/logout").getHeader("Authorization"))
    }

    @Test
    fun `sign-out works offline`() = runBlocking {
        h.signIn()
        server.web.shutdown()
        h.services.auth.signOut()
        assertNull(h.services.auth.user.value)
    }

    @Test
    fun `the device name is kept and sent`() = runBlocking {
        h.services.auth.setDeviceName("  Den TV  ")
        assertEquals("Den TV", h.services.auth.deviceName.value)
        assertEquals("Den TV", h.services.client.name)
        h.signIn()
        assertEquals("Den TV", h.body(server.last("POST /api/auth/login"))["client"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Den TV", h.reopen().auth.deviceName.value)
    }

    private companion object {
        val PAIR_START = """{"code":"BPEULY","secret":"s3cret","expiresAt":"2026-10-29T17:20:20Z","intervalMs":20,"linkPath":"/link?code=BPEULY"}"""
        val SESSIONS = """{"items":[
            {"id":"86e0d90b273fc14a","userId":2,"userName":"viewer","kind":"device","name":"Living room TV","product":"WatchMyMedia for Android TV",
             "platform":"androidtv","version":"0.1.0","model":"Chromecast with Google TV","deviceId":"dev-1","lastIp":"127.0.0.1","createdAt":"2026-10-29T17:09:01.982Z",
             "lastSeenAt":"2026-10-29T17:09:01.982Z","expiresAt":"2027-10-29T17:09:01.981Z","current":true},
            {"id":"0123456789abcdef","userId":2,"userName":"viewer","kind":"browser","name":"Chrome on macOS","userAgent":"Mozilla/5.0",
             "createdAt":"2026-10-20T10:00:00Z","lastSeenAt":"2026-10-28T10:00:00Z","expiresAt":"2026-11-20T10:00:00Z","current":false}
        ]}"""
    }
    @Test
    fun `a token kept unwrapped while the Keystore failed is sealed once it works`() = runBlocking<Unit> {
        val sealer = RecoveringSealer()
        val h = Harness(sealer)
        try {
            h.signIn()
            withTimeout(5_000) { h.dataStore.data.first { LocalStore.decode(it).current?.account != null } }
            assertEquals("p1:tok-viewer", h.services.storedToken())
            // The Keystore works again: the next start seals the token, and keeps it that way.
            sealer.works = true
            val restarted = h.reopen()
            assertEquals("w1:tok-viewer", restarted.storedToken())
            assertEquals("viewer", restarted.auth.user.value?.name)
            withTimeout(5_000) { h.dataStore.data.first { LocalStore.decode(it).current?.account?.token == "w1:tok-viewer" } }
            h.server.on("GET /api/home", json(TestServer.fixture("home.json")))
            restarted.library.home()
            assertEquals("Bearer tok-viewer", h.server.last("GET /api/home").getHeader("Authorization"))
        } finally {
            h.close()
        }
    }

    @Test
    fun `the Keystore sealer leaves alone what it can't seal better`() {
        val sealer = KeystoreSealer()
        // No Keystore here: an unwrapped token stays unwrapped, and a wrapped one is never touched.
        assertNull(sealer.reseal("p1:tok"))
        assertNull(sealer.reseal("k1:abc"))
        assertNull(PlainSealer.reseal("p1:tok"))
    }

}

/** Keeps tokens unwrapped, as when the Keystore fails, until it [works]; then wraps them as "w1:". */
private class RecoveringSealer : TokenSealer {
    @Volatile var works = false

    override fun seal(token: String) = if (works) "w1:$token" else PlainSealer.seal(token)

    override fun open(sealed: String) = if (sealed.startsWith("w1:")) sealed.removePrefix("w1:") else PlainSealer.open(sealed)

    override fun reseal(sealed: String) = if (works && sealed.startsWith(PlainSealer.PREFIX)) seal(PlainSealer.open(sealed)!!) else null
}

/** Opens tokens as [PlainSealer] does until [lost], as when the Keystore drops its key. */
private class LosableSealer : TokenSealer {
    @Volatile var lost = false

    override fun seal(token: String) = PlainSealer.seal(token)

    override fun open(sealed: String) = if (lost) null else PlainSealer.open(sealed)
}
