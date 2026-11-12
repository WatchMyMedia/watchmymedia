package media.watchmy.client.core.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import media.watchmy.client.core.data.TestServer.Companion.fixture
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.noContent
import media.watchmy.client.core.data.TestServer.Companion.problem
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.DecisionRequest
import media.watchmy.client.model.HDR_PROFILE_API_LEVEL
import media.watchmy.client.model.PlayState
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.ProgressReport
import media.watchmy.client.model.QualityChange
import media.watchmy.client.model.Segment
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.StreamVideo
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.TRICKPLAY_API_LEVEL
import media.watchmy.client.model.Trickplay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaybackGatewayTest {
    private val h = Harness()
    private val server get() = h.server
    private val gateway get() = h.services.playbackGateway

    private val profile = PlaybackProfile(
        name = "androidtv",
        containers = listOf("mp4", "mpegts"),
        videoCodecs = listOf("h264", "hevc"),
        audioCodecs = listOf("aac", "eac3"),
        maxWidth = 3840,
        maxHeight = 2160,
        maxAudioChannels = 6,
    )

    @Before
    fun signIn() {
        h.signIn()
    }

    @After
    fun tearDown() = h.close()

    @Test
    fun `a VOD decision request sends the playlist kind, quality caps, client and play`() = runBlocking {
        server.always("POST /api/files/4/playback", json(fixture("decision-hls.json")))
        gateway.decide(
            DecisionRequest(4, profile, h.services.client, startMs = 30_000, audioIndex = 1, subtitleId = "s2", quality = StreamQuality.FullHd, playId = "d2081dfed30c2b6c"),
        )
        val body = h.body(server.last("POST /api/files/4/playback"))
        assertEquals("vod", body["playlist"]!!.jsonPrimitive.content)
        assertEquals("1080", body["quality"]!!.jsonPrimitive.content)
        assertEquals("d2081dfed30c2b6c", body["playId"]!!.jsonPrimitive.content)
        assertEquals(30_000L, body["startMs"]!!.jsonPrimitive.long)
        assertEquals(1L, body["audioIndex"]!!.jsonPrimitive.long)
        assertEquals("s2", body["subtitleId"]!!.jsonPrimitive.content)
        val sent = body["profile"]!!.jsonObject
        assertEquals("The quality narrows the device's caps", 1920L, sent["maxWidth"]!!.jsonPrimitive.long)
        assertEquals(1080L, sent["maxHeight"]!!.jsonPrimitive.long)
        assertEquals(8_000_000L, sent["maxBitrate"]!!.jsonPrimitive.long)
        assertEquals(6L, sent["maxAudioChannels"]!!.jsonPrimitive.long)
        assertEquals("dev-1", body["client"]!!.jsonObject["deviceId"]!!.jsonPrimitive.content)
        assertEquals("Bearer tok-viewer", server.last("POST /api/files/4/playback").getHeader("Authorization"))

        gateway.decide(DecisionRequest(4, profile, h.services.client, vod = false))
        val plain = h.body(server.last("POST /api/files/4/playback"))
        assertFalse("no playlist asks for the default (event)", "playlist" in plain)
        assertEquals("original", plain["quality"]!!.jsonPrimitive.content)
        assertEquals(3840L, plain["profile"]!!.jsonObject["maxWidth"]!!.jsonPrimitive.long)
    }

    @Test
    fun `subtitle mode is omitted for older servers and explicit Off reaches API 18`() = runBlocking {
        server.always("POST /api/files/4/playback", json(fixture("decision-hls.json")))
        gateway.decide(DecisionRequest(4, profile, h.services.client, subtitleMode = media.watchmy.client.model.SubtitleRequestMode.Off))
        assertFalse("subtitleMode" in h.body(server.last("POST /api/files/4/playback")))
        server.always("GET /api/server", json("""{"id":"srv-test","name":"Test server","version":"0.9.0","apiLevel":18,"setupRequired":false,"turnstile":false}"""))
        h.services.servers.select(h.testServer.copy(apiLevel = 18))
        gateway.decide(DecisionRequest(4, profile, h.services.client, subtitleMode = media.watchmy.client.model.SubtitleRequestMode.Off))
        assertEquals("off", h.body(server.last("POST /api/files/4/playback"))["subtitleMode"]!!.jsonPrimitive.content)
    }

    /** A box on a Dolby Vision TV (PLAN-HDR-PROFILE.md §2). */
    private val dolbyVisionTv = profile.copy(videoCodecs = listOf("h264", "hevc", "hevc10", "dovi5", "dovi8"), hdr = true, hdrTypes = listOf("hdr10", "hlg", "dovi"))

    private fun sentProfile(route: String) = h.body(server.last(route))["profile"]!!.jsonObject

    private fun JsonObject.strings(key: String) = this[key]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `a server below the HDR profile's level gets the profile apps sent before`() = runBlocking {
        server.always("POST /api/files/4/playback", json(fixture("decision-hls.json")))
        gateway.decide(DecisionRequest(4, dolbyVisionTv, h.services.client))
        val tv = sentProfile("POST /api/files/4/playback")
        assertFalse("it refuses the field, even as null", "hdrTypes" in tv)
        assertEquals(listOf("h264", "hevc", "hevc10"), tv.strings("videoCodecs"))
        assertTrue(tv["hdr"]!!.jsonPrimitive.content.toBoolean())

        // An SDR phone with a Main 10 decoder: no hevc10, which such a server would send HDR10 to.
        gateway.decide(DecisionRequest(4, dolbyVisionTv.copy(videoCodecs = listOf("h264", "hevc", "hevc10"), hdr = false, hdrTypes = emptyList()), h.services.client))
        val phone = sentProfile("POST /api/files/4/playback")
        assertFalse("hdrTypes" in phone)
        assertEquals(listOf("h264", "hevc"), phone.strings("videoCodecs"))
    }

    @Test
    fun `from the HDR profile's level decisions and plans carry hdrTypes and the dovi keys`() = runBlocking {
        h.services.servers.select(h.testServer.copy(apiLevel = HDR_PROFILE_API_LEVEL))
        server.always("POST /api/files/4/playback", json(fixture("decision-hls.json")))
        gateway.bind().decide(DecisionRequest(4, dolbyVisionTv, h.services.client))
        val tv = sentProfile("POST /api/files/4/playback")
        assertEquals(listOf("hdr10", "hlg", "dovi"), tv.strings("hdrTypes"))
        assertEquals(listOf("h264", "hevc", "hevc10", "dovi5", "dovi8"), tv.strings("videoCodecs"))

        // An SDR screen still says so: an empty list, not none.
        val sdr = dolbyVisionTv.copy(videoCodecs = listOf("h264", "hevc", "hevc10"), hdr = false, hdrTypes = emptyList())
        gateway.plans(sdr, StreamQuality.Original, listOf(9))
        val planned = sentProfile("POST /api/playback/plans")
        assertEquals(emptyList<String>(), planned.strings("hdrTypes"))
        assertEquals(listOf("h264", "hevc", "hevc10"), planned.strings("videoCodecs"))
    }

    @Test
    fun `plans send the capped profile once per file and map how each would play`() = runBlocking {
        server.on(
            "POST /api/playback/plans",
            json(
                """{"items":[
                {"fileId":9,"mode":"audio","protocol":"hls","reasons":["client cannot decode truehd audio"],
                 "video":{"codec":"hevc","copy":true,"width":3840,"height":2160,"hdr":"dolbyvision","toneMap":false},"audio":{"index":1,"codec":"aac","copy":false,"channels":6}},
                {"fileId":8,"mode":"transcode","protocol":"hls","reasons":[],
                 "video":{"codec":"hevc","copy":false,"width":1920,"height":1080,"hdr":"","toneMap":true},"audio":{"index":1,"codec":"eac3","copy":true,"channels":6}},
                {"fileId":7,"mode":"direct","protocol":"file","reasons":[],"video":{"codec":"h264","copy":true,"width":1920,"height":1080,"toneMap":false},"audio":{"index":1,"codec":"aac","copy":true}}]}""",
            ),
        )
        val plans = gateway.plans(profile, StreamQuality.FullHd, listOf(9, 8, 9, 7))
        val body = h.body(server.last("POST /api/playback/plans"))
        assertEquals(listOf(9L, 8L, 7L), body["fileIds"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals(1080L, body["profile"]!!.jsonObject["maxHeight"]!!.jsonPrimitive.long)
        val (remux, transcode, direct) = plans
        assertEquals(PlaybackMode.AudioTranscode, remux.mode)
        assertEquals(StreamVideo(3840, 2160, "Dolby Vision"), remux.video)
        assertEquals("Remux to fMP4 · audio to AAC 5.1", remux.label)
        assertEquals(listOf("client cannot decode truehd audio"), remux.reasons)
        assertEquals("Transcode to 1080p HEVC", transcode.label)
        assertNull("tone-mapped is SDR", transcode.video?.hdr)
        assertEquals("Direct play", direct.label)
    }

    @Test
    fun `servers without plans give none`() = runBlocking {
        assertTrue(gateway.plans(profile, StreamQuality.Original, listOf(9)).isEmpty())
        server.on("POST /api/playback/plans", problem(405, "Method Not Allowed"))
        assertTrue(gateway.plans(profile, StreamQuality.Original, listOf(9)).isEmpty())
        assertTrue("no IDs, no call", gateway.plans(profile, StreamQuality.Original, emptyList()).isEmpty())
        assertEquals(2, server.requests("POST /api/playback/plans").size)
    }

    @Test
    fun `the decision's next episode carries its runtime and finale, and the request the model`() = runBlocking {
        val next = """"nextEpisode":{"fileId":12,"episodeId":402,"showId":11,"showTitle":"Harbor Lights","seasonNumber":1,"episodeNumber":2,"title":"Tide","runtimeMin":48,"seasonFinale":true},"""
        server.on("POST /api/files/4/playback", json(fixture("decision-hls.json").replaceFirst("{", "{$next")))
        val d = gateway.decide(DecisionRequest(4, profile, h.services.client.copy(model = "Pixel 9")))
        assertEquals(48 * 60_000L, d.next?.runtimeMs)
        assertTrue(d.next!!.seasonFinale)
        assertEquals("Pixel 9", h.body(server.last("POST /api/files/4/playback"))["client"]!!.jsonObject["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an HLS VOD decision maps to absolute URLs, segments and file time`() = runBlocking {
        server.on("POST /api/files/4/playback", json(fixture("decision-hls.json")))
        val d = gateway.decide(DecisionRequest(4, profile, h.services.client))
        assertEquals(PlaybackMode.Remux, d.mode)
        assertEquals(listOf("client cannot play the mkv container"), d.reasons)
        assertEquals(StreamProtocol.Hls, d.protocol)
        assertTrue(d.vod)
        assertEquals("${server.baseUrl}/api/playback/a75ff047261dcc3e/master.m3u8", d.url)
        assertEquals("a75ff047261dcc3e", d.sessionId)
        assertEquals("d2081dfed30c2b6c", d.playId)
        assertEquals(200_021L, d.durationMs)
        assertEquals(listOf(Segment(SegmentKind.Intro, 30_000, 55_000), Segment(SegmentKind.Credits, 170_000, 200_000, final = true)), d.segments)
        assertEquals(StreamVideo(1280, 720, null), d.video)
        assertEquals(170_000L, d.creditsMs)
        assertEquals(170_000L, d.watchedAtMs)
        assertNull(d.next)
        assertEquals("AAC Stereo", d.audio.single().label)
        assertEquals(1, d.selectedAudio)
    }

    @Test
    fun `a direct decision lists subtitles with absolute URLs`() = runBlocking {
        server.on("POST /api/files/8/playback", json(fixture("decision-direct.json")))
        val d = gateway.decide(DecisionRequest(8, profile, h.services.client))
        assertEquals(PlaybackMode.Direct, d.mode)
        assertEquals(StreamProtocol.File, d.protocol)
        assertTrue("a file's time is file time", d.vod)
        assertEquals("${server.baseUrl}/api/files/8/stream?play=e0b6acbb09202287", d.url)
        assertNull(d.sessionId)
        assertEquals("video/x-matroska", d.mimeType)
        assertEquals(138_019L, d.watchedAtMs)
        assertEquals(2, d.subtitles.size)
        val en = d.subtitles[0]
        assertEquals("s2", en.option.id)
        assertEquals("English", en.option.label)
        assertEquals("webvtt", en.format)
        assertEquals("${server.baseUrl}/api/files/8/subtitles/s2.vtt", en.url)
        assertEquals("Spanish", d.subtitles[1].option.label)
        assertFalse(en.option.burnInOnly)
        assertEquals("SRT", en.option.format)
        assertEquals("1080p", d.video?.quality)
    }

    @Test
    fun `progress sends the heartbeat and reads a stop directive and a new play`() = runBlocking {
        server.on(
            "PUT /api/files/4/progress",
            json("""{"positionMs":5000,"durationMs":200000,"completed":false,"updatedAt":"2026-10-29T13:09:35.67713-04:00"}"""),
            json(
                """{"positionMs":5000,"durationMs":200000,"completed":false,"updatedAt":"2026-10-29T13:09:35Z",
                "playId":"aaaaaaaaaaaaaaaa","directive":{"action":"stop","message":"Server maintenance"}}""",
            ),
        )
        val first = gateway.reportProgress(ProgressReport(4, 5_000, 200_000, "d2081dfed30c2b6c", PlayState.Paused, 3_000))
        val body = h.body(server.last("PUT /api/files/4/progress"))
        assertEquals(5_000L, body["positionMs"]!!.jsonPrimitive.long)
        assertEquals(200_000L, body["durationMs"]!!.jsonPrimitive.long)
        assertEquals(3_000L, body["bufferedMs"]!!.jsonPrimitive.long)
        assertEquals("paused", body["state"]!!.jsonPrimitive.content)
        assertEquals("d2081dfed30c2b6c", body["playId"]!!.jsonPrimitive.content)
        assertFalse(first.completed)
        assertNull(first.stopMessage)
        assertNull(first.newPlayId)

        val second = gateway.reportProgress(ProgressReport(4, 5_000, 200_000, "d2081dfed30c2b6c", PlayState.Playing, 0))
        assertEquals("Server maintenance", second.stopMessage)
        assertEquals("aaaaaaaaaaaaaaaa", second.newPlayId)
    }

    @Test
    fun `Automatic sends the device's own profile and reads the step the server picked`() = runBlocking {
        val auto = fixture("decision-hls.json").replaceFirst("{", """{"auto":{"step":"720","reason":"3.1 Mbps measured"},"bitrate":4128000,""")
        server.on("POST /api/files/4/playback", json(auto), json(fixture("decision-hls.json")))
        val d = gateway.decide(DecisionRequest(4, profile, h.services.client, quality = StreamQuality.Auto, playId = "d2081dfed30c2b6c"))
        val body = h.body(server.last("POST /api/files/4/playback"))
        assertEquals("auto", body["quality"]!!.jsonPrimitive.content)
        assertEquals("no step's caps", 3840L, body["profile"]!!.jsonObject["maxWidth"]!!.jsonPrimitive.long)
        assertNull(body["profile"]!!.jsonObject["maxBitrate"]?.takeIf { it != JsonNull })
        assertEquals(StreamQuality.Hd, d.autoStep)
        assertEquals(4_128_000L, d.bitrate)

        val fixed = gateway.decide(DecisionRequest(4, profile, h.services.client))
        assertNull(fixed.autoStep)
        assertEquals(0L, fixed.bitrate)
    }

    @Test
    fun `heartbeats carry the player's measurements and read a quality directive`() = runBlocking {
        val reply = """{"positionMs":5000,"durationMs":200000,"completed":false,"updatedAt":"2026-10-29T13:09:35Z","directive":%s}"""
        server.on(
            "PUT /api/files/4/progress",
            json(reply.format("""{"action":"quality","quality":"720","message":"Lowered to 720p for your connection."}""")),
            json(reply.format("""{"action":"quality","quality":"240"}""")),
            json(reply.format("""{"action":"rewind"}""")),
        )
        val report = ProgressReport(4, 5_000, 200_000, "d2081dfed30c2b6c", PlayState.Buffering, 1_200, throughputBps = 3_100_000, stalls = 1, stallMs = 2_400, startupMs = 900)
        val first = gateway.reportProgress(report)
        val body = h.body(server.last("PUT /api/files/4/progress"))
        assertEquals(3_100_000L, body["throughputBps"]!!.jsonPrimitive.long)
        assertEquals(1L, body["stalls"]!!.jsonPrimitive.long)
        assertEquals(2_400L, body["stallMs"]!!.jsonPrimitive.long)
        assertEquals(900L, body["startupMs"]!!.jsonPrimitive.long)
        assertEquals(QualityChange(StreamQuality.Hd, "Lowered to 720p for your connection."), first.qualityChange)
        assertNull(first.stopMessage)

        assertNull("a step this app doesn't know", gateway.reportProgress(report).qualityChange)
        val unknown = gateway.reportProgress(ProgressReport(4, 5_000, 200_000, "d2081dfed30c2b6c", PlayState.Playing, 0))
        assertNull("an action this app doesn't know", unknown.qualityChange)
        assertNull(unknown.stopMessage)
        val plain = h.body(server.last("PUT /api/files/4/progress"))
        // Left out rather than null: a server older than a field refuses it even as null.
        assertTrue("no measurements, no fields", listOf("throughputBps", "stalls", "stallMs", "startupMs").none { it in plain })
    }

    @Test
    fun `recovery contract is gated and recognizes Original`() = runBlocking {
        server.always("POST /api/files/4/playback", json(fixture("decision-hls.json")))
        val request = DecisionRequest(4, profile, h.services.client, quality = StreamQuality.Auto,
            autoRecovery = true, switchGeneration = 7)
        gateway.decide(request)
        assertFalse("autoRecovery" in h.body(server.last("POST /api/files/4/playback")))
        assertFalse("switchGeneration" in h.body(server.last("POST /api/files/4/playback")))

        server.always("GET /api/server", json("""{"id":"srv-test","name":"Test server","version":"0.9.0","apiLevel":24,"setupRequired":false,"turnstile":false}"""))
        h.services.servers.select(h.testServer.copy(apiLevel = 24))
        gateway.decide(request)
        val body = h.body(server.last("POST /api/files/4/playback"))
        assertEquals("true", body["autoRecovery"]!!.jsonPrimitive.content)
        assertEquals(7L, body["switchGeneration"]!!.jsonPrimitive.long)

        server.always("PUT /api/files/4/progress", json("""{"positionMs":5000,"durationMs":200000,"completed":false,"updatedAt":"2026-10-29T13:09:35Z","directive":{"action":"quality","quality":"original","generation":8,"message":"Connection recovered"}}"""))
        val reply = gateway.reportProgress(ProgressReport(4, 5_000, 200_000, "d2081dfed30c2b6c", PlayState.Playing, 30_000,
            throughputBps = 40_000_000, throughputAgeMs = 200, switchGeneration = 7))
        val progress = h.body(server.last("PUT /api/files/4/progress"))
        assertEquals(200L, progress["throughputAgeMs"]!!.jsonPrimitive.long)
        assertEquals(7L, progress["switchGeneration"]!!.jsonPrimitive.long)
        assertEquals(QualityChange(StreamQuality.Original, "Connection recovered", 8), reply.qualityChange)
    }

    @Test
    fun `media tokens and stopping a session`() = runBlocking {
        server.on("POST /api/auth/media-token", json(fixture("media-token.json")))
        val token = gateway.mediaToken()
        assertTrue(token.token.startsWith("m1."))
        assertEquals("access_token", token.param)
        assertEquals(epochMs("2026-10-29T23:09:35Z"), token.expiresAtMs)
        assertEquals("the token is for this server's URLs only", server.baseUrl, token.serverUrl)

        server.on("DELETE /api/playback/a75ff047261dcc3e", noContent())
        gateway.stopSession("a75ff047261dcc3e")
        server.on("DELETE /api/playback/gone", problem(404, "no such session"))
        gateway.stopSession("gone")
        assertEquals(1, server.requests("DELETE /api/playback/gone").size)
    }

    @Test
    fun `online subtitles search and download, and an older server has none`() = runBlocking {
        assertNull("a server without the route has no provider", gateway.searchSubtitles(4))
        server.always(
            "GET /api/files/4/subtitles/search",
            json(
                """{"available":true,"provider":"opensubtitles","attribution":"Subtitles provided by OpenSubtitles.com","languages":["en"],""" +
                    """"results":[{"id":"77","language":"en","release":"Film.2020","hashMatch":true,"hearingImpaired":false,"forced":false,"trusted":true,"aiTranslated":false,"downloads":5}],""" +
                    """"canDownload":true,"quota":{"signedIn":true,"remaining":19}}""",
            ),
        )
        val found = gateway.searchSubtitles(4)!!
        assertEquals("77", found.results.single().id)
        assertEquals(19, found.remaining)
        server.always("GET /api/files/5/subtitles/search", json("""{"available":false,"provider":"opensubtitles","attribution":"","languages":[],"results":[],"canDownload":false,"quota":{"signedIn":false,"remaining":-1}}"""))
        assertNull("no API key in that build", gateway.searchSubtitles(5))

        server.always(
            "POST /api/files/4/subtitles/download",
            json("""{"id":"d3","codec":"subrip","format":"webvtt","url":"/api/files/4/subtitles/d3.vtt","forced":false,"default":false,"burnInOnly":false,"external":true,"source":"opensubtitles"}"""),
        )
        assertEquals("d3", gateway.downloadSubtitle(4, "77"))
        assertEquals("77", h.body(server.last("POST /api/files/4/subtitles/download"))["resultId"]!!.jsonPrimitive.content)
        server.always("POST /api/files/4/subtitles/download", problem(429, "No subtitle downloads left today."))
        val e = runCatching { gateway.downloadSubtitle(4, "78") }.exceptionOrNull() as ApiException
        assertEquals(429, e.status)
        assertEquals("No subtitle downloads left today.", e.message)
    }

    @Test
    fun `from the seek thumbnails' level a decision carries the manifest, with an absolute URL`() = runBlocking {
        h.services.servers.select(h.testServer.copy(apiLevel = TRICKPLAY_API_LEVEL))
        server.always("POST /api/files/4/playback", json(withTrickplay(MANIFEST)))
        val d = gateway.bind().decide(DecisionRequest(4, profile, h.services.client))
        assertEquals(Trickplay(10_000, 320, 180, 10, 10, 21, 1, "${server.baseUrl}/api/files/4/trickplay/9c1e07a2/{sheet}.jpg"), d.trickplay)
        assertEquals("${server.baseUrl}/api/files/4/trickplay/9c1e07a2/0.jpg", d.trickplay!!.sheetUrl(0))

        // Not made yet: none, and the scrubber shows the time alone.
        server.always("POST /api/files/4/playback", json(fixture("decision-hls.json")))
        assertNull(gateway.bind().decide(DecisionRequest(4, profile, h.services.client)).trickplay)

        // One the app can't draw from is none too.
        server.always("POST /api/files/4/playback", json(withTrickplay(MANIFEST.replace("\"columns\":10", "\"columns\":0"))))
        assertNull(gateway.bind().decide(DecisionRequest(4, profile, h.services.client)).trickplay)
    }

    @Test
    fun `a server below the seek thumbnails' level shows none`() = runBlocking {
        h.services.servers.select(h.testServer.copy(apiLevel = TRICKPLAY_API_LEVEL - 1))
        server.always("POST /api/files/4/playback", json(withTrickplay(MANIFEST)))
        assertNull(gateway.bind().decide(DecisionRequest(4, profile, h.services.client)).trickplay)
    }

    private companion object {
        const val MANIFEST = """{"intervalMs":10000,"width":320,"height":180,"columns":10,"rows":10,"count":21,"sheets":1,"url":"/api/files/4/trickplay/9c1e07a2/{sheet}.jpg"}"""

        /** The HLS decision with [manifest] as its trickplay. */
        fun withTrickplay(manifest: String) = fixture("decision-hls.json").replaceFirst("{", """{"trickplay":$manifest,""")
    }
}
