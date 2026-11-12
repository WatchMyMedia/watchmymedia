package media.watchmy.client.core.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.problem
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.DeviceDownloadState
import media.watchmy.client.model.DownloadChoice
import media.watchmy.client.model.DownloadQuality
import media.watchmy.client.model.OfflineItem
import media.watchmy.client.model.OfflineOwner
import media.watchmy.client.model.OfflineProgress
import media.watchmy.client.model.OfflineRecordState
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.Server
import media.watchmy.client.model.SignedOutException
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.SyncOutcome
import media.watchmy.client.model.SyncedDownload
import media.watchmy.client.model.WatchProgress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The offline routes against a MockWebServer: what is sent, how replies read, and the account each call goes as. */
class OfflineGatewayTest {
    private val h = Harness()
    private val server get() = h.server
    private val gateway get() = h.services.offlineGateway
    private lateinit var owner: OfflineOwner

    private val profile = PlaybackProfile("android", listOf("mp4"), listOf("h264", "hevc"), listOf("aac"), maxWidth = 2400, maxHeight = 1080)

    @Before
    fun signIn() = runBlocking {
        h.services.servers.select(Server("srv-test", "Test server", server.baseUrl, "1.0.0", 10))
        server.on("POST /api/auth/login", json(TestServer.SIGN_IN))
        h.services.auth.signIn("viewer", "fixture-viewer-1")
        owner = gateway.owner()!!
    }

    @After
    fun tearDown() = h.close()

    @Test
    fun `the owner is the server and account in use`() {
        assertEquals("srv-test", owner.serverId)
        assertEquals(2L, owner.userId)
        assertEquals(10, owner.apiLevel)
        assertTrue(gateway.signedIn(owner))
        assertFalse(gateway.signedIn(owner.copy(userId = 9)))
    }

    @Test
    fun `downloads are allowed when the server's options say offline`() = runBlocking {
        server.on("GET /api/requests/options", json(OPTIONS.replace("OFFLINE", "true")), json(OPTIONS.replace("OFFLINE", "false")))
        assertTrue(gateway.allowed(owner))
        assertFalse(gateway.allowed(owner))
    }

    @Test
    fun `a server below level 10 offers no downloads`() = runBlocking {
        h.services.servers.select(Server("srv-old", "Old", server.baseUrl, "0.9.0", 9))
        server.on("POST /api/auth/login", json(TestServer.SIGN_IN))
        h.services.auth.signIn("viewer", "fixture-viewer-1")
        assertFalse(gateway.allowed(gateway.owner()!!))
        assertTrue(server.requests("GET /api/requests/options").isEmpty())
    }

    @Test
    fun `the plan reads each quality with its size and the tracks`() = runBlocking {
        server.always("POST /api/files/42/offline/plan", json(PLAN))
        val plan = gateway.plan(owner, 42, profile)
        assertEquals(listOf(DownloadQuality.Original, DownloadQuality.P1080, DownloadQuality.P720, DownloadQuality.P480), plan.options.map { it.quality })
        val original = plan.options.first()
        assertEquals(PlaybackMode.AudioTranscode, original.mode)
        assertEquals(StreamProtocol.Hls, original.protocol)
        assertTrue(original.estimated)
        assertTrue(original.audioConverted)
        assertEquals(PlaybackMode.Transcode, plan.options[2].mode)
        assertEquals(2, plan.audio.size)
        assertEquals(1, plan.imageSubtitles.size)
        assertEquals("s3", plan.imageSubtitles.single().id)
        val sent = h.body(server.last("POST /api/files/42/offline/plan"))["profile"]!!.jsonObject
        assertEquals(2400L, sent["maxWidth"]!!.jsonPrimitive.long)
        assertEquals("Bearer tok-viewer", server.last("POST /api/files/42/offline/plan").getHeader("Authorization"))
    }

    @Test
    fun `creating a download sends the choice and the device, and keeps the reply to read again`() = runBlocking {
        server.always("POST /api/files/42/offline", json(CREATED, code = 201))
        val created = gateway.create(owner, DownloadChoice(42, DownloadQuality.P720, audioIndex = 2, burnInSubtitleId = "s3"), profile, h.services.client)
        val body = h.body(server.last("POST /api/files/42/offline"))
        assertEquals("720", body["quality"]!!.jsonPrimitive.content)
        assertEquals(2L, body["audioIndex"]!!.jsonPrimitive.long)
        assertEquals("s3", body["subtitleId"]!!.jsonPrimitive.content)
        assertTrue(body["burnIn"]!!.jsonPrimitive.boolean)
        assertEquals("dev-1", body["client"]!!.jsonObject["deviceId"]!!.jsonPrimitive.content)

        val r = created.record
        assertEquals("abcdefghijklmnop", r.id)
        assertEquals(OfflineRecordState.Ready, r.state)
        assertEquals(StreamProtocol.Hls, r.protocol)
        assertEquals("${server.baseUrl}/api/offline/abcdefghijklmnop/hls/master.m3u8", r.url)
        assertEquals(DownloadQuality.P720, r.quality)
        assertEquals(OfflineItem.Kind.Episode, r.item.kind)
        assertEquals(3, r.item.season)
        assertEquals("Harbor Lights", r.item.showTitle)
        val d = r.decision!!
        assertEquals("The decision's URL is the offline route's", r.url, d.url)
        assertEquals(1_200_000L, d.creditsMs)
        assertEquals("${server.baseUrl}/api/offline/abcdefghijklmnop/subtitles/s2.vtt", d.subtitles.single().url)

        // Kept as it came, and read again offline.
        assertEquals(r, gateway.parse(owner, created.json))
    }

    @Test
    fun `a state this app doesn't know reads as revoked`() {
        val r = gateway.parse(owner, CREATED.replace("\"state\":\"ready\"", "\"state\":\"frozen\""))
        assertEquals(OfflineRecordState.Revoked, r.state)
    }

    @Test
    fun `a token comes for the download, on its owner's server`() = runBlocking {
        server.always("POST /api/offline/downloads/abcdefghijklmnop/token", json("""{"token":"o1.abc","expiresAt":"2026-11-02T00:00:00Z","param":"access_token"}"""))
        val t = gateway.token(owner, "abcdefghijklmnop")
        assertEquals("o1.abc", t.token)
        assertEquals("access_token", t.param)
        assertEquals(server.baseUrl, t.serverUrl)
        assertEquals(java.time.Instant.parse("2026-11-02T00:00:00Z").toEpochMilli(), t.expiresAtMs)
    }

    @Test
    fun `a revoked download's token is refused with the server's status`() = runBlocking {
        server.always("POST /api/offline/downloads/abcdefghijklmnop/token", problem(403, "this download was revoked"))
        try {
            gateway.token(owner, "abcdefghijklmnop")
            fail("expected a refusal")
        } catch (e: ApiException) {
            assertEquals(403, e.status)
        }
    }

    @Test
    fun `sync sends what the device holds and its progress, and reads every record and outcome`() = runBlocking {
        server.always("POST /api/offline/sync", json(SYNC))
        val reply = gateway.sync(
            owner, h.services.client, sentAtMs = 1_800_000_000_000L,
            downloads = listOf(SyncedDownload("abcdefghijklmnop", DeviceDownloadState.Downloaded, 1_000_000), SyncedDownload("qrstuvwxyzabcdef", DeviceDownloadState.Removed)),
            progress = listOf(
                OfflineProgress(42, "abcdefghijklmnop", 1_799_999_000_000L, positionMs = 600_000, durationMs = 3_000_000),
                OfflineProgress(42, "abcdefghijklmnop", 1_799_999_500_000L, watched = true),
            ),
        )
        val body = h.body(server.last("POST /api/offline/sync"))
        assertEquals("2027-01-15T08:00:00Z", body["sentAt"]!!.jsonPrimitive.content)
        val downloads = body["downloads"]!!.jsonArray
        assertEquals("downloaded", downloads[0].jsonObject["deviceState"]!!.jsonPrimitive.content)
        assertEquals(1_000_000L, downloads[0].jsonObject["sizeBytes"]!!.jsonPrimitive.long)
        assertEquals("removed", downloads[1].jsonObject["deviceState"]!!.jsonPrimitive.content)
        assertNull("No size for a removed one", downloads[1].jsonObject["sizeBytes"])
        val progress = body["progress"]!!.jsonArray
        assertEquals(600_000L, progress[0].jsonObject["positionMs"]!!.jsonPrimitive.long)
        assertNull("A position sends no mark", progress[0].jsonObject["watched"])
        assertTrue(progress[1].jsonObject["watched"]!!.jsonPrimitive.boolean)
        assertNull("A mark sends no position", progress[1].jsonObject["positionMs"])

        assertTrue(reply.offline)
        assertEquals(listOf(OfflineRecordState.Ready, OfflineRecordState.Gone, OfflineRecordState.Replaced, OfflineRecordState.Revoked, OfflineRecordState.Restart), reply.downloads.map { it.state })
        assertEquals(77L, reply.downloads[2].replacedBy)
        assertEquals(listOf(SyncOutcome.Applied, SyncOutcome.Newer, SyncOutcome.Unavailable), reply.results.map { it.outcome })
        assertEquals(WatchProgress(0, 3_000_000, true), reply.results[0].watch)
        assertNull(reply.results[1].watch)
    }

    @Test
    fun `a 401 from sync signs the account out`() = runBlocking {
        server.always("POST /api/offline/sync", problem(401, "signed out"))
        try {
            gateway.sync(owner, h.services.client, 0, emptyList(), emptyList())
            fail("expected a sign-out")
        } catch (_: SignedOutException) {
        }
        assertFalse(gateway.signedIn(owner))
        assertNull(h.services.auth.user.value)
        try {
            gateway.token(owner, "abcdefghijklmnop")
            fail("a signed-out owner makes no calls")
        } catch (_: SignedOutException) {
        }
    }

    @Test
    fun `a download's calls go to its own server while another is in use`() = runBlocking {
        h.services.servers.select(Server("srv-other", "Other", "http://127.0.0.1:9", "1.0.0", 10))
        server.always("POST /api/offline/downloads/abcdefghijklmnop/token", json("""{"token":"o1.abc","expiresAt":"2026-11-02T00:00:00Z","param":"access_token"}"""))
        assertEquals("o1.abc", gateway.token(owner, "abcdefghijklmnop").token)
        assertEquals("Bearer tok-viewer", server.last("POST /api/offline/downloads/abcdefghijklmnop/token").getHeader("Authorization"))
    }

    @Test
    fun `sign-out runs its hooks while the session still stands`() = runBlocking {
        var signedInDuringHook: Boolean? = null
        gateway.beforeSignOut { signedInDuringHook = gateway.signedIn(owner) }
        h.services.auth.signOut()
        assertEquals(true, signedInDuringHook)
        assertFalse(gateway.signedIn(owner))
    }

    private companion object {
        const val OPTIONS = """{"movies":false,"shows":false,"needsApproval":false,"problems":true,"manage":false,"backend":"none","inbox":0,"offline":OFFLINE}"""

        const val PLAN = """{"fileId":42,"durationMs":3000000,
            "qualities":[
              {"quality":"original","mode":"audio","protocol":"hls","sizeBytes":4200000000,"sizeEstimated":true,"reasons":["client cannot decode truehd audio"],"video":{"copy":true,"codec":"hevc","width":3840,"height":2160,"hdr":"hdr10","toneMap":false,"bitrate":0},"audio":{"index":1,"copy":false,"codec":"aac","channels":6}},
              {"quality":"1080","mode":"transcode","protocol":"hls","sizeBytes":3300000000,"sizeEstimated":true,"reasons":[],"video":{"copy":false,"codec":"h264","width":1920,"height":1080,"hdr":"","toneMap":true,"bitrate":8000000},"audio":{"index":1,"copy":false,"codec":"aac","channels":2}},
              {"quality":"720","mode":"transcode","protocol":"hls","sizeBytes":1700000000,"sizeEstimated":true,"reasons":[],"video":{"copy":false,"codec":"h264","width":1280,"height":720,"hdr":"","toneMap":true,"bitrate":4000000},"audio":{"index":1,"copy":false,"codec":"aac","channels":2}},
              {"quality":"480","mode":"transcode","protocol":"hls","sizeBytes":900000000,"sizeEstimated":true,"reasons":[],"video":{"copy":false,"codec":"h264","width":854,"height":480,"hdr":"","toneMap":true,"bitrate":2000000},"audio":{"index":1,"copy":false,"codec":"aac","channels":2}}],
            "audioTracks":[{"index":1,"language":"eng","codec":"truehd","channels":8,"default":true},{"index":2,"language":"eng","codec":"ac3","channels":2,"title":"Commentary","default":false}],
            "subtitles":[{"id":"s2","language":"eng","codec":"subrip","format":"webvtt","forced":false,"default":false,"burnInOnly":false,"external":false},
                         {"id":"s3","language":"eng","codec":"hdmv_pgs_subtitle","format":"image","forced":false,"default":false,"burnInOnly":true,"external":false}]}"""

        const val ITEM = """{"type":"episode","showId":100,"episodeId":100037,"title":"The Long Dark","showTitle":"Harbor Lights","seasonNumber":3,"episodeNumber":7,"posterPath":"/p.jpg","artPath":"/s.jpg"}"""

        const val CREATED = """{"id":"abcdefghijklmnop","fileId":42,"state":"ready","mode":"transcode","protocol":"hls",
            "url":"/api/offline/abcdefghijklmnop/hls/master.m3u8","sizeBytes":1700000000,"sizeEstimated":true,"quality":"720",
            "createdAt":"2026-11-01T10:00:00Z","item":$ITEM,
            "decision":{"mode":"transcode","reasons":[],"protocol":"hls","playlist":"vod","url":"/api/offline/abcdefghijklmnop/hls/master.m3u8",
              "mimeType":"application/vnd.apple.mpegurl","playId":"","durationMs":3000000,"startMs":0,"resumeMs":0,
              "audio":{"index":1,"copy":false,"codec":"aac","channels":2,"index":1},"audioTracks":[{"index":1,"language":"eng","codec":"truehd","channels":8,"default":true}],
              "video":{"copy":false,"codec":"h264","width":1280,"height":720,"hdr":"","toneMap":true,"bitrate":4000000},
              "subtitles":[{"id":"s2","language":"eng","codec":"subrip","format":"webvtt","url":"/api/offline/abcdefghijklmnop/subtitles/s2.vtt","forced":false,"default":false,"burnInOnly":false,"external":false}],
              "segments":[],"creditsMs":1200000,"watchedAtMs":1300000,"upNextMs":0}}"""

        fun record(id: String, state: String, extra: String = "") = """{"id":"$id","fileId":42,"state":"$state"$extra,"mode":"direct","protocol":"file",
            "url":"/api/offline/$id/stream","sizeBytes":1000000,"sizeEstimated":false,"quality":"original","createdAt":"2026-11-01T10:00:00Z","item":$ITEM}"""

        val SYNC = """{"serverTime":"2027-01-15T08:00:01Z","offline":true,
            "downloads":[${record("abcdefghijklmnop", "ready")},${record("bbbbbbbbbbbbbbbb", "gone")},${record("cccccccccccccccc", "replaced", ",\"replacedBy\":77")},
                         ${record("dddddddddddddddd", "revoked", ",\"revokedBy\":\"admin\"")},${record("eeeeeeeeeeeeeeee", "restart")}],
            "progress":[{"downloadId":"abcdefghijklmnop","fileId":42,"outcome":"applied","watchState":{"positionMs":0,"durationMs":3000000,"completed":true,"updatedAt":"2027-01-15T08:00:01Z"}},
                        {"downloadId":"abcdefghijklmnop","fileId":42,"outcome":"newer"},
                        {"fileId":43,"outcome":"unavailable"}]}"""
    }
}
