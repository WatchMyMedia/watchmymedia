package media.watchmy.client.core.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.problem
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.LIVE_CAST_API_LEVEL
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.StreamQuality
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Cast Connect's Live TV calls (PLAN-LIVE-TV.md §4.1): the caster's cast token, never this TV's sign-in. */
class CastLiveDataTest {
    private val h = Harness()
    private val server get() = h.server

    @After
    fun tearDown() = h.close()

    @Test
    fun `a cast channel plays on the caster's token, with media tokens from its renewal`() = runBlocking {
        h.signIn()
        val cast = h.services.castLivePlayback(ReachedServer(server.baseUrl + "/", LIVE_CAST_API_LEVEL), "c1.first", 1_900_000_000_000, StreamQuality.Hd)
        server.always("GET /api/live-tv/capabilities", json("""{"enabled":true,"allowed":true,"playback":true,"guide":true,"cast":true}"""))
        server.always("GET /api/live-tv/channels", json("""{"items":[$LISTED]}"""))
        val caps = cast.live.capabilities()
        assertTrue(caps.available)
        assertTrue(caps.guide)
        assertTrue(caps.cast)
        val channel = cast.live.channels().single()
        assertEquals("4.1", channel.number)
        assertEquals("The Sleuth Files", channel.now?.title)
        assertEquals("Bearer c1.first", server.last("GET /api/live-tv/channels").getHeader("Authorization"))

        server.always("POST /api/live-tv/sessions", json(SESSION))
        val session = cast.gateway.start(LiveTarget(channel), cast.preferences.quality.value, "cast-1", h.client)
        assertEquals("the sender's quality", "720", h.body(server.last("POST /api/live-tv/sessions"))["quality"]!!.jsonPrimitive.content)
        assertEquals("Bearer c1.first", server.last("POST /api/live-tv/sessions").getHeader("Authorization"))
        assertEquals(server.baseUrl + "/api/live-tv/sessions/session-a/hls/live.m3u8", session.url)

        // Cast tokens can't call createMediaToken: the player's tokens come from renewing the cast token.
        server.on("POST /api/auth/cast-token", json("""{"token":"c1.second","expiresAt":"2027-01-01T00:00:00Z","param":"access_token","urls":[]}"""))
        val media = cast.gateway.mediaToken()
        assertEquals("c1.second", media.token)
        assertEquals("access_token", media.param)
        assertTrue(server.requests("POST /api/auth/media-token").isEmpty())
        server.always("PUT /api/live-tv/sessions/session-a", json(SESSION))
        cast.gateway.heartbeat("session-a", paused = false, interacted = true)
        assertEquals("the renewed token in use", "Bearer c1.second", server.last("PUT /api/live-tv/sessions/session-a").getHeader("Authorization"))

        // Logos and pictures carry the latest cast token, on the caster's server only.
        assertEquals("${server.baseUrl}/api/live-tv/art/logo-a?access_token=c1.second", cast.live.artUrl(channel.logo))
        assertNull(cast.live.artUrl("https://elsewhere.example/logo.png"))
        assertNull(cast.live.artUrl(null))

        // A quality changed here is the play's, not this TV's.
        cast.preferences.setQuality(StreamQuality.Sd)
        assertEquals(StreamQuality.Original, h.services.preferences.quality.value)

        // A revoked caster: the play fails, and this TV stays signed in.
        server.on("PUT /api/live-tv/sessions/session-a", problem(401, "sign in required"))
        try {
            cast.gateway.heartbeat("session-a", paused = false, interacted = false)
            fail("expected 401")
        } catch (e: ApiException) {
            assertEquals(401, e.status)
        }
        assertEquals("viewer", h.services.auth.user.value?.name)
    }

    @Test
    fun `casting a channel is offered only from level 14 with capabilities cast`() = runBlocking {
        h.signIn()
        server.always("GET /api/live-tv/capabilities", json("""{"enabled":true,"allowed":true,"playback":true,"guide":true,"cast":true}"""))
        val older = h.services.castLivePlayback(ReachedServer(server.baseUrl, 13), "c1.a", 1_900_000_000_000, null)
        assertFalse(older.live.capabilities().cast)
        assertEquals("a sender that names no quality plays at 1080", StreamQuality.FullHd, older.preferences.quality.value)
        server.always("GET /api/live-tv/capabilities", json("""{"enabled":true,"allowed":true,"playback":true,"guide":true}"""))
        val current = h.services.castLivePlayback(ReachedServer(server.baseUrl, LIVE_CAST_API_LEVEL), "c1.b", 1_900_000_000_000, null)
        assertFalse("no cast field, no casting", current.live.capabilities().cast)
    }

    companion object {
        private const val SLEUTH = """{"id":"p1","start":"2026-11-02T20:00:00Z","end":"2026-11-02T21:00:00Z","title":"The Sleuth Files","episodeTitle":"The Low Tide","season":2,"episode":4}"""
        private const val LISTED = """{"id":"channel-a","sourceId":"source-a","number":"4.1","name":"NFLD-HD","enabled":true,"available":true,"logoUrl":"/api/live-tv/art/logo-a","now":$SLEUTH}"""
        private const val CHANNEL = """{"id":"channel-a","sourceId":"source-a","number":"4.1","name":"NFLD-HD","enabled":true,"available":true}"""
        private const val SESSION = """{"id":"session-a","channel":$CHANNEL,"state":"playing","reason":"","quality":"720","url":"/api/live-tv/sessions/session-a/hls/live.m3u8","userId":2,"userName":"viewer","clientName":"Den TV","startedAt":"2026-11-02T12:00:00Z","lastSeenAt":"2026-11-02T12:00:00Z","paused":false,"pauseRemainingSeconds":300,"bufferSeconds":60,"bufferBytes":128}"""
    }
}
