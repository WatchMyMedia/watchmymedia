package media.watchmy.client.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.noContent
import media.watchmy.client.core.data.TestServer.Companion.problem
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveMeasurement
import media.watchmy.client.model.LivePlaybackState
import media.watchmy.client.model.LiveSessionState
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.Server
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.PlaybackProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LiveTVTest {
    private val h = Harness()
    private val server get() = h.server
    private val target = LiveTarget(LiveChannel("channel-a", "source-a", "5.1", "WMM News"))

    @After fun close() = h.close()

    private suspend fun signInAt(level: Int) {
        server.always("GET /api/server", json("""{"id":"srv-test","name":"Test server","version":"0.5.0","apiLevel":$level,"setupRequired":false,"turnstile":false}"""))
        h.services.servers.select(h.testServer.copy(apiLevel = level))
        server.on("POST /api/auth/login", json(TestServer.SIGN_IN))
        h.services.auth.signIn("viewer", "fixture-viewer-1")
        withTimeout(5_000) { h.services.servers.current.first { it?.server?.apiLevel == level } }
    }

    @Test fun `older servers keep Live TV hidden without any live request`() = runBlocking {
        signInAt(10)
        assertFalse(h.services.live.capabilities().available)
        assertTrue(h.services.live.channels().isEmpty())
        val failure = runCatching { h.services.livePlaybackGateway.bind().start(target, StreamQuality.Hd, "request-1", h.client) }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertTrue(server.requests.none { it.path!!.startsWith("/api/live-tv") })
    }

    @Test fun `capabilities require all three gates and optional timing fields have bounded defaults`() = runBlocking {
        signInAt(11)
        server.on("GET /api/live-tv/capabilities",
            json("""{"enabled":true,"allowed":false,"playback":true,"guide":false}"""),
            json("""{"enabled":true,"allowed":true,"playback":false,"guide":false}"""),
            json("""{"enabled":true,"allowed":true,"playback":true,"guide":false,"bufferSeconds":60,"pauseSeconds":300}"""),
        )
        val denied = h.services.live.capabilities()
        assertFalse(denied.available)
        assertEquals(60, denied.bufferSeconds)
        assertEquals(300, denied.pauseSeconds)
        assertFalse(h.services.live.capabilities().available)
        assertTrue(h.services.live.capabilities().available)
    }

    @Test fun `channels work without listings and unavailable channels retain their reason`() = runBlocking {
        signInAt(11)
        server.always("GET /api/live-tv/channels", json("""{"items":[$CHANNEL,${CHANNEL.replace("channel-a", "channel-b").replace("\"available\":true", "\"available\":false,\"reason\":\"no_signal\"")},${CHANNEL.replace("channel-a", "hidden").replace("\"enabled\":true", "\"enabled\":false")}]}"""))
        val channels = h.services.live.channels()
        assertEquals(listOf("channel-a", "channel-b"), channels.map { it.id })
        assertFalse(channels[1].available)
        assertEquals("no_signal", channels[1].reason)
        assertTrue(server.requests.none { it.path!!.contains("guide") })
    }

    @Test fun `start sends a typed live target idempotency key and fixed quality without file routes`() = runBlocking {
        signInAt(11)
        server.always("POST /api/live-tv/sessions", json(session()))
        val gateway = h.services.livePlaybackGateway.bind()
        val reply = gateway.start(target, StreamQuality.Auto, "request-1", h.client)
        assertEquals(LiveSessionState.Playing, reply.state)
        assertEquals(server.baseUrl + "/api/live-tv/sessions/session-a/hls/live.m3u8", reply.url)
        val body = h.body(server.last("POST /api/live-tv/sessions"))
        assertEquals(setOf("channelId", "requestId", "quality", "clientName"), body.keys)
        assertEquals("channel-a", body["channelId"]!!.jsonPrimitive.content)
        assertEquals("request-1", body["requestId"]!!.jsonPrimitive.content)
        assertEquals("1080", body["quality"]!!.jsonPrimitive.content)
        assertEquals("Bearer tok-viewer", server.last("POST /api/live-tv/sessions").getHeader("Authorization"))
        assertTrue(server.requests.none { it.path!!.contains("/api/files/") })
    }

    @Test fun `a unicode device name fits the shipped server byte limit without splitting a character`() = runBlocking {
        signInAt(11)
        server.always("POST /api/live-tv/sessions", json(session()))
        val name = "A" + "📺".repeat(64)
        h.services.livePlaybackGateway.bind().start(target, StreamQuality.Hd, "unicode-name", h.client.copy(name = name))
        val sent = h.body(server.last("POST /api/live-tv/sessions"))["clientName"]!!.jsonPrimitive.content
        assertEquals("A" + "📺".repeat(31), sent)
        assertEquals(125, sent.toByteArray(Charsets.UTF_8).size)
        assertFalse(sent.endsWith("\uD83D"))
    }

    @Test fun `Automatic sends the live profile and measured heartbeat only when advertised`() = runBlocking {
        signInAt(26)
        server.always("GET /api/live-tv/capabilities", json("""{"enabled":true,"allowed":true,"playback":true,"guide":false,"automatic":true}"""))
        server.always("POST /api/live-tv/sessions", json(session().replace("\"quality\":\"720\"", "\"quality\":\"auto\",\"automatic\":true,\"actualQuality\":\"720\"")))
        server.always("PUT /api/live-tv/sessions/session-a", json(session()))
        val gateway = h.services.livePlaybackGateway.bind()
        assertTrue(gateway.liveCapabilities().automatic)
        val profile = PlaybackProfile("Phone", listOf("mpegts"), listOf("h264", "av1"), listOf("aac", "opus"), maxHeight = 1080)
        val session = gateway.startNegotiated(target, StreamQuality.Auto, "auto-1", h.client, profile)
        assertTrue(session.automatic)
        val body = h.body(server.last("POST /api/live-tv/sessions"))
        assertEquals("auto", body["quality"]!!.jsonPrimitive.content)
        assertEquals("true", body["autoRecovery"]!!.jsonPrimitive.content)
        val sentProfile = body["profile"]!!.toString()
        assertTrue(sentProfile.contains("h264"))
        assertFalse(sentProfile.contains("av1"))
        gateway.heartbeatMeasured("session-a", false, false, LiveMeasurement(LivePlaybackState.Buffering,
            bufferedMs = 7_000, throughputBps = 3_200_000, throughputAgeMs = 900, stalls = 2,
            switchGeneration = 4))
        val beat = h.body(server.last("PUT /api/live-tv/sessions/session-a"))
        assertEquals("buffering", beat["state"]!!.jsonPrimitive.content)
        assertEquals("3200000", beat["throughputBps"]!!.jsonPrimitive.content)
        assertEquals("4", beat["switchGeneration"]!!.jsonPrimitive.content)
    }

    @Test fun `older live server does not receive Automatic or measurement fields`() = runBlocking {
        signInAt(25)
        server.always("GET /api/live-tv/capabilities", json("""{"enabled":true,"allowed":true,"playback":true,"guide":false,"automatic":true}"""))
        server.always("POST /api/live-tv/sessions", json(session()))
        server.always("PUT /api/live-tv/sessions/session-a", json(session()))
        val gateway = h.services.livePlaybackGateway.bind()
        assertFalse(gateway.liveCapabilities().automatic)
        val profile = PlaybackProfile("Phone", listOf("mpegts"), listOf("h264"), listOf("aac"))
        gateway.startNegotiated(target, StreamQuality.Auto, "fixed-1", h.client, profile)
        val body = h.body(server.last("POST /api/live-tv/sessions"))
        assertEquals("1080", body["quality"]!!.jsonPrimitive.content)
        assertFalse("autoRecovery" in body)
        assertTrue("profile" in body)
        gateway.heartbeatMeasured("session-a", false, false, LiveMeasurement(LivePlaybackState.Buffering, bufferedMs = 1_000))
        assertEquals(setOf("paused"), h.body(server.last("PUT /api/live-tv/sessions/session-a")).keys)
    }

    @Test fun `unsafe prepared output URL is rejected before playback`() = runBlocking {
        signInAt(26)
        val view = session().dropLast(1) + """, "automatic":true, "directive":{"generation":3,"url":"https://untrusted.example/live.m3u8","from":"1080","to":"720","reason":"bandwidth","mode":"video","modeReason":"bandwidth"}}"""
        server.always("GET /api/live-tv/sessions/session-a", json(view))
        val received = h.services.livePlaybackGateway.bind().status("session-a")
        assertEquals(3L, received.directive?.generation)
        assertEquals("", received.directive?.url)
    }

    @Test fun `heartbeat reports actual interaction and stop tolerates an already expired session`() = runBlocking {
        signInAt(11)
        server.always("PUT /api/live-tv/sessions/session-a", json(session(state = "stopped", reason = "pause_expired")))
        server.always("DELETE /api/live-tv/sessions/session-a", problem(404, "already stopped"))
        val gateway = h.services.livePlaybackGateway.bind()
        val ended = gateway.heartbeat("session-a", paused = true, interacted = false)
        assertTrue(ended.state.terminal)
        assertEquals("pause_expired", ended.reason)
        val idle = h.body(server.last("PUT /api/live-tv/sessions/session-a"))
        assertEquals("true", idle["paused"]!!.jsonPrimitive.content)
        assertFalse("interacted" in idle)
        gateway.heartbeat("session-a", paused = false, interacted = true)
        assertEquals("true", h.body(server.last("PUT /api/live-tv/sessions/session-a"))["interacted"]!!.jsonPrimitive.content)
        gateway.stop("session-a")
    }

    @Test fun `unknown states and cross-origin stream URLs are never playable`() = runBlocking {
        signInAt(11)
        server.on("GET /api/live-tv/sessions/session-a", json(session(state = "future_state")), json(session(url = "https://untrusted.example/live.m3u8")))
        val gateway = h.services.livePlaybackGateway.bind()
        assertEquals(LiveSessionState.Unknown, gateway.status("session-a").state)
        val rejected = gateway.status("session-a")
        assertEquals(LiveSessionState.Failed, rejected.state)
        assertNull(rejected.url)
        assertEquals("session-a", rejected.id) // Kept so the controller can release the tuner.
    }

    @Test fun `a switched account invalidates live binding but cleanup retains its original credentials`() = runBlocking {
        signInAt(11)
        TestServer().use { other ->
            val gateway = h.services.livePlaybackGateway.bind()
            assertTrue(gateway.isCurrent)
            h.services.servers.select(Server("other", "Other synthetic server", other.baseUrl, "0.5.0", 11))
            other.on("POST /api/auth/login", json(TestServer.SIGN_IN.replace("tok-viewer", "other-token")))
            h.services.auth.signIn("viewer", "fixture-viewer-1")
            assertFalse(gateway.isCurrent)
            assertFalse(gateway.active.first())
            val failure = runCatching { gateway.start(target, StreamQuality.Hd, "new-request", h.client) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            server.always("DELETE /api/live-tv/sessions/session-a", noContent())
            gateway.stop("session-a")
            assertEquals("Bearer tok-viewer", server.last("DELETE /api/live-tv/sessions/session-a").getHeader("Authorization"))
            assertTrue(other.requests.none { it.path!!.startsWith("/api/live-tv") })
        }
    }

    @Test fun `another sign-in on the same server invalidates the old session even for the same user`() = runBlocking {
        signInAt(11)
        val gateway = h.services.livePlaybackGateway.bind()
        server.on("POST /api/auth/login", json(TestServer.SIGN_IN.replace("tok-viewer", "new-sign-in-token").replace("86e0d90b273fc14a", "new-sign-in")))
        h.services.auth.signIn("viewer", "fixture-viewer-1")
        assertFalse(gateway.isCurrent)
        assertFalse(gateway.active.first())
        server.always("DELETE /api/live-tv/sessions/session-a", noContent())
        gateway.stop("session-a")
        assertEquals("Bearer tok-viewer", server.last("DELETE /api/live-tv/sessions/session-a").getHeader("Authorization"))
        val failure = runCatching { gateway.heartbeat("session-a", paused = false, interacted = false) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(server.requests("PUT /api/live-tv/sessions/session-a").isEmpty())
    }

    @Test fun `a late allocation retains its id for cleanup without acquiring the new account`() = runBlocking {
        signInAt(11)
        val gateway = h.services.livePlaybackGateway.bind()
        server.always("POST /api/live-tv/sessions", json(session()))
        server.always("DELETE /api/live-tv/sessions/session-a", noContent())
        val hold = server.hold("POST /api/live-tv/sessions")
        val pending = async(Dispatchers.IO) { gateway.start(target, StreamQuality.Hd, "request-1", h.client) }
        hold.awaitArrival()
        try {
            h.services.servers.select(Server("other", "Other synthetic server", "http://127.0.0.1:28269", "0.5.0", 11))
        } finally { hold.release() }
        val late = pending.await()
        assertFalse(gateway.isCurrent)
        assertEquals("session-a", late.id)
        gateway.stop(late.id)
        assertEquals("Bearer tok-viewer", server.last("DELETE /api/live-tv/sessions/session-a").getHeader("Authorization"))
    }

    @Test fun `late channel results are discarded after switching accounts`() = runBlocking {
        signInAt(11)
        server.always("GET /api/live-tv/channels", json("""{"items":[$CHANNEL]}"""))
        val hold = server.hold("GET /api/live-tv/channels")
        val pending = async(Dispatchers.IO) { runCatching { h.services.live.channels() } }
        hold.awaitArrival()
        try {
            h.services.servers.select(Server("other", "Other synthetic server", "http://127.0.0.1:28269", "0.5.0", 11))
        } finally { hold.release() }
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
    }

    @Test fun `servers below level 13 get no guide request and channels carry no listings`() = runBlocking {
        signInAt(12)
        server.always("GET /api/live-tv/channels", json("""{"items":[$LISTED]}"""))
        val channel = h.services.live.channels().single()
        assertNull(channel.now)
        assertNull(channel.logo)
        assertNull(h.services.live.guide(0L, 3_600_000L))
        assertTrue(server.requests.none { it.path!!.contains("guide") })
    }

    @Test fun `channels carry the logo and now and next with times as instants`() = runBlocking {
        signInAt(13)
        server.always("GET /api/live-tv/channels", json("""{"items":[$LISTED]}"""))
        val channel = h.services.live.channels().single()
        assertEquals("/api/live-tv/art/logo-a", channel.logo)
        assertEquals("The Sleuth Files", channel.now?.title)
        assertEquals(Instant.parse("2026-11-02T20:00:00Z").toEpochMilli(), channel.now?.startMs)
        assertEquals(2, channel.now?.season)
        assertEquals("Station 9", channel.next?.title)
        assertEquals(server.baseUrl + "/api/live-tv/art/logo-a", h.services.live.artUrl(channel.logo))
        assertNull(h.services.live.artUrl("https://img.hdhomerun.com/channels/US1.png"))
    }

    @Test fun `the guide asks for six-hour windows on fixed boundaries, fetches the next ahead and keeps them`() = runBlocking {
        signInAt(13)
        server.always("GET /api/live-tv/guide", json(GUIDE))
        val start = Instant.parse("2026-11-02T20:30:00Z").toEpochMilli()
        h.now = start
        val guide = h.services.live.guide(start, start + 2 * 3_600_000L)!!
        withTimeout(5_000) { while (server.requests("GET /api/live-tv/guide").size < 2) delay(20) }
        val asked = server.requests("GET /api/live-tv/guide").map { it.requestUrl!!.queryParameter("start") to it.requestUrl!!.queryParameter("hours") }
        assertEquals(setOf("2026-11-02T18:00:00Z" to "6", "2026-11-03T00:00:00Z" to "6"), asked.toSet())
        val row = guide.rows.single()
        assertTrue(row.listings)
        assertEquals(listOf("The Sleuth Files", "Station 9"), row.programmes.map { it.title })
        assertTrue(guide.state.stale)
        // A window already fetched is kept: moving later inside it asks nothing more.
        h.services.live.guide(start + 3_600_000L, start + 3 * 3_600_000L)
        assertEquals(2, server.requests("GET /api/live-tv/guide").size)
    }

    @Test fun `listings fetched for one account are not reused after signing in again`() = runBlocking {
        signInAt(13)
        server.always("GET /api/live-tv/guide", json(GUIDE))
        val start = Instant.parse("2026-11-02T20:30:00Z").toEpochMilli()
        h.now = start
        h.services.live.guide(start, start + 3_600_000L)
        withTimeout(5_000) { while (server.requests("GET /api/live-tv/guide").size < 2) delay(20) }
        server.on("POST /api/auth/login", json(TestServer.SIGN_IN.replace("tok-viewer", "new-sign-in-token").replace("86e0d90b273fc14a", "new-sign-in")))
        h.services.auth.signIn("viewer", "fixture-viewer-1")
        h.services.live.guide(start, start + 3_600_000L)
        assertEquals("Bearer new-sign-in-token", server.last("GET /api/live-tv/guide").getHeader("Authorization"))
    }

    companion object {
        private const val SLEUTH = """{"id":"p1","start":"2026-11-02T20:00:00Z","end":"2026-11-02T21:00:00Z","title":"The Sleuth Files","episodeTitle":"The Low Tide","season":2,"episode":4,"kind":"episode","new":true,"hd":true,"categories":["Series","Crime drama"],"pictureUrl":"/api/live-tv/art/title-a"}"""
        private const val STATION = """{"id":"p2","start":"2026-11-02T21:00:00Z","end":"2026-11-02T22:00:00Z","title":"Station 9","stillUrl":"/api/images/w780/still.jpg"}"""
        private const val LISTED = """{"id":"channel-a","sourceId":"source-a","number":"4.1","name":"NFLD-HD","enabled":true,"available":true,"logoUrl":"/api/live-tv/art/logo-a","now":$SLEUTH,"next":$STATION}"""
        private const val GUIDE = """{"start":"2026-11-02T18:00:00Z","end":"2026-11-03T00:00:00Z","state":{"enabled":true,"stale":true,"updatedAt":"2026-10-31T19:40:00Z","listedThrough":"2026-11-05T00:00:00Z"},"channels":[{"id":"channel-a","sourceId":"source-a","number":"4.1","name":"NFLD-HD","enabled":true,"available":true,"listings":true,"logoUrl":"/api/live-tv/art/logo-a","programmes":[$SLEUTH,$STATION,{"id":"bad","start":"2026-11-02T23:00:00Z","end":"2026-11-02T22:00:00Z","title":"Runs backwards"}]}]}"""
        private const val CHANNEL = """{"id":"channel-a","sourceId":"source-a","number":"5.1","name":"WMM News","enabled":true,"available":true}"""
        private fun session(state: String = "playing", reason: String = "", url: String = "/api/live-tv/sessions/session-a/hls/live.m3u8") =
            """{"id":"session-a","channel":$CHANNEL,"state":"$state","reason":"$reason","quality":"720","url":"$url","userId":2,"userName":"viewer","clientName":"Fixture phone","startedAt":"2026-11-02T12:00:00Z","lastSeenAt":"2026-11-02T12:00:00Z","paused":false,"pauseRemainingSeconds":300,"bufferSeconds":60,"bufferBytes":128}"""
    }
}
