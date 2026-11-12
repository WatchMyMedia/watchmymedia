package media.watchmy.client.core.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.problem
import media.watchmy.client.model.AUTO_API_LEVEL
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.DecisionRequest
import media.watchmy.client.model.HDR_PROFILE_API_LEVEL
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.TRICKPLAY_API_LEVEL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CastDataTest {
    private val h = Harness()
    private val server get() = h.server

    @After
    fun tearDown() = h.close()

    private fun info(level: Int, casting: Boolean) =
        """{"id":"srv-test","name":"Test server","version":"1.1.0","apiLevel":$level,"setupRequired":false,"turnstile":false,"casting":$casting}"""

    @Test
    fun `the server in use casts from API level 2 when it says so`() = runBlocking {
        assertNull("no server yet", h.services.castGateway.castServer())
        h.signIn()
        // The sign-in brings the server's level up to date in the background; let that go first.
        withTimeout(5_000) { while (server.requests("GET /api/server").isEmpty()) delay(5) }
        server.on("GET /api/server", json(info(2, true)), json(info(2, false)), json(info(1, true)))
        val s = h.services.castGateway.castServer()!!
        assertEquals("srv-test", s.id)
        assertEquals(server.baseUrl, s.baseUrl)
        assertTrue(s.casting)
        assertFalse(h.services.castGateway.castServer()!!.casting)
        assertFalse("too old", h.services.castGateway.castServer()!!.casting)
    }

    @Test
    fun `a cast token comes from this device's sign-in`() = runBlocking {
        h.signIn()
        server.on(
            "POST /api/auth/cast-token",
            json("""{"token":"c1.abc","expiresAt":"2027-01-01T00:00:00Z","param":"access_token","urls":["https://media.example.com","http://192.168.1.20:27587"]}"""),
        )
        val t = h.services.castGateway.castToken()
        assertEquals("c1.abc", t.token)
        assertEquals(1_798_761_600_000L, t.expiresAtMs)
        assertEquals(listOf("https://media.example.com", "http://192.168.1.20:27587"), t.urls)
        assertEquals("Bearer tok-viewer", server.last("POST /api/auth/cast-token").getHeader("Authorization"))
    }

    @Test
    fun `a cast plays with the caster's token, renews it, and leaves this TV's sign-in alone`() = runBlocking {
        // This TV is signed in as viewer; the cast is someone else's token.
        h.signIn()
        val cast = h.services.castPlayback(ReachedServer(server.baseUrl + "/", 2), "c1.first", 1_900_000_000_000, StreamQuality.Hd)
        server.always("POST /api/files/4/playback", json(TestServer.fixture("decision-hls.json")))
        val profile = PlaybackProfile("androidtv", listOf("mp4"), listOf("h264"), listOf("aac"))
        cast.gateway.decide(DecisionRequest(4, profile, h.client))
        assertEquals("Bearer c1.first", server.last("POST /api/files/4/playback").getHeader("Authorization"))

        server.on("POST /api/auth/cast-token", json("""{"token":"c1.second","expiresAt":"2027-01-01T00:00:00Z","param":"access_token","urls":[]}"""))
        assertEquals("${server.baseUrl}/api/images/w342/poster.jpg?access_token=c1.first", cast.imageUrl("/poster.jpg"))
        val media = cast.gateway.mediaToken()
        assertEquals("c1.second", media.token)
        assertEquals(server.baseUrl, media.serverUrl)
        assertEquals("artwork follows the renewed token", "${server.baseUrl}/api/images/w342/poster.jpg?access_token=c1.second", cast.imageUrl("poster.jpg"))
        assertNull(cast.imageUrl(null))
        assertEquals("Bearer c1.first", server.last("POST /api/auth/cast-token").getHeader("Authorization"))
        cast.gateway.decide(DecisionRequest(4, profile, h.client))
        assertEquals("renewed token in use", "Bearer c1.second", server.last("POST /api/files/4/playback").getHeader("Authorization"))

        // The caster's preferences, read with their token; the sender's quality; changes stay on this play.
        cast.preferences.refresh()
        assertEquals("Bearer c1.second", server.last("GET /api/me/preferences").getHeader("Authorization"))
        assertEquals(StreamQuality.Hd, cast.preferences.quality.value)
        cast.preferences.setSkip(SegmentKind.Intro, SkipMode.Auto)
        cast.preferences.setQuality(StreamQuality.Sd)
        assertTrue(server.requests("PUT /api/me/preferences").isEmpty())
        assertEquals(StreamQuality.Original, h.services.preferences.quality.value)

        // A revoked caster: the play fails, and this TV stays signed in.
        server.on("POST /api/files/4/playback", problem(401, "sign in required"))
        try {
            cast.gateway.decide(DecisionRequest(4, profile, h.client))
            fail("expected 401")
        } catch (e: ApiException) {
            assertEquals(401, e.status)
        }
        assertEquals("viewer", h.services.auth.user.value?.name)
    }

    @Test
    fun `a cast plays Automatic only where the caster's server can do it`() = runBlocking {
        h.signIn()
        val auto = h.services.castPlayback(ReachedServer(server.baseUrl, AUTO_API_LEVEL), "c1.a", 1_900_000_000_000, StreamQuality.Auto)
        assertTrue(auto.preferences.autoQuality.value)
        assertEquals(StreamQuality.Auto, auto.preferences.quality.value)
        auto.preferences.setQuality(StreamQuality.Low)
        assertEquals(StreamQuality.Low, auto.preferences.quality.value)

        val older = h.services.castPlayback(ReachedServer(server.baseUrl, 2), "c1.b", 1_900_000_000_000, StreamQuality.Auto)
        assertFalse(older.preferences.autoQuality.value)
        assertEquals(StreamQuality.Original, older.preferences.quality.value)
        older.preferences.setQuality(StreamQuality.Auto)
        assertEquals(StreamQuality.Original, older.preferences.quality.value)

        // A sender that names no quality: this TV's own.
        val unnamed = h.services.castPlayback(ReachedServer(server.baseUrl, AUTO_API_LEVEL), "c1.c", 1_900_000_000_000, null)
        assertEquals(h.services.preferences.quality.value, unnamed.preferences.quality.value)
    }

    @Test
    fun `a cast sends this TV's HDR only to a caster's server that reads it`() = runBlocking {
        h.signIn()
        server.always("POST /api/files/4/playback", json(TestServer.fixture("decision-hls.json")))
        val profile = PlaybackProfile("androidtv", listOf("mkv"), listOf("hevc", "hevc10", "dovi8"), listOf("aac"), hdr = true, hdrTypes = listOf("hdr10", "dovi"))
        fun sent() = h.body(server.last("POST /api/files/4/playback"))["profile"]!!.jsonObject

        h.services.castPlayback(ReachedServer(server.baseUrl, HDR_PROFILE_API_LEVEL), "c1.a", 1_900_000_000_000, null).gateway.decide(DecisionRequest(4, profile, h.client))
        assertEquals(listOf("hdr10", "dovi"), sent()["hdrTypes"]!!.jsonArray.map { it.jsonPrimitive.content })

        // This TV may be signed in to a newer server than the caster's.
        h.services.servers.select(h.testServer.copy(apiLevel = HDR_PROFILE_API_LEVEL))
        h.services.castPlayback(ReachedServer(server.baseUrl, HDR_PROFILE_API_LEVEL - 1), "c1.b", 1_900_000_000_000, null).gateway.decide(DecisionRequest(4, profile, h.client))
        assertFalse("hdrTypes" in sent())
        assertEquals(listOf("hevc", "hevc10"), sent()["videoCodecs"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a cast's seek thumbnails carry its token, as the image loader signs in only to this TV's own server`() = runBlocking {
        h.signIn()
        val manifest = """{"intervalMs":10000,"width":320,"height":180,"columns":10,"rows":10,"count":21,"sheets":1,"url":"/api/files/4/trickplay/9c1e07a2/{sheet}.jpg"}"""
        server.always("POST /api/files/4/playback", json(TestServer.fixture("decision-hls.json").replaceFirst("{", """{"trickplay":$manifest,""")))
        val profile = PlaybackProfile("androidtv", listOf("mkv"), listOf("h264"), listOf("aac"))
        val cast = h.services.castPlayback(ReachedServer(server.baseUrl, TRICKPLAY_API_LEVEL), "c1.a+b", 1_900_000_000_000, null)
        val tiles = cast.gateway.decide(DecisionRequest(4, profile, h.client)).trickplay!!
        assertEquals("${server.baseUrl}/api/files/4/trickplay/9c1e07a2/3.jpg?access_token=c1.a%2Bb", tiles.sheetUrl(3))
        assertEquals("cached without it", "${server.baseUrl}/api/files/4/trickplay/9c1e07a2/3.jpg", tiles.sheetKey(3))

        val older = h.services.castPlayback(ReachedServer(server.baseUrl, TRICKPLAY_API_LEVEL - 1), "c1.b", 1_900_000_000_000, null)
        assertNull(older.gateway.decide(DecisionRequest(4, profile, h.client)).trickplay)
    }

    @Test
    fun `a cast token in an artwork URL is encoded`() {
        assertEquals(
            "http://192.168.1.20:27587/api/images/w342/a.jpg?access_token=c1.a%2Bb%2Fc%3D%26x%3D1",
            castImageUrl("http://192.168.1.20:27587", "c1.a+b/c=&x=1", "/a.jpg"),
        )
    }

    @Test
    fun `Cast Connect finds which address reaches the caster's server`() = runBlocking {
        server.always("GET /api/server", json(info(2, true)))
        val unreachable = "http://127.0.0.1:9"
        assertEquals(ReachedServer(server.baseUrl, 2), h.services.reachCastServer("srv-test", listOf(unreachable, server.baseUrl + "/")))
        assertNull("another server at the address", h.services.reachCastServer("srv-other", listOf(server.baseUrl)))
        assertNull(h.services.reachCastServer("srv-test", emptyList()))
        assertNull("only http and https", h.services.reachCastServer("srv-test", listOf("ftp://127.0.0.1:21", "javascript:alert(1)")))
    }

    @Test
    fun `a sender's addresses are tried once each, http and https only, eight at most`() {
        assertEquals(
            listOf("http://192.168.1.20:27587", "https://media.example.com", "http://[fd7a:115c::1]:27587"),
            castServerUrls(
                listOf(
                    "http://192.168.1.20:27587/", " http://192.168.1.20:27587 ", "HTTP://192.168.1.20:27587/?x=1",
                    "https://media.example.com", "ftp://192.168.1.20", "file:///sdcard/x", "not a url",
                    "https://me:secret@media.example.com", "http://[fd7a:115c::1]:27587",
                ),
            ),
        )
        val many = (1..20).map { "http://10.0.0.$it:27587" }
        assertEquals(MAX_CAST_URLS, castServerUrls(many).size)
        assertEquals(many.take(MAX_CAST_URLS), castServerUrls(many))
    }
}
