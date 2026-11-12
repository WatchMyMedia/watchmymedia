package media.watchmy.client.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.noContent
import media.watchmy.client.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LiveRecordingsTest {
    private val h = Harness()
    private val server get() = h.server
    @After fun close() = h.close()
    private suspend fun signIn(level: Int = LIVE_RECORDING_API_LEVEL) {
        server.always("GET /api/server", json("""{"id":"srv-test","name":"Test server","version":"0.5.8","apiLevel":$level,"setupRequired":false,"turnstile":false}"""))
        h.services.servers.select(h.testServer.copy(apiLevel = level))
        server.on("POST /api/auth/login", json(TestServer.SIGN_IN))
        h.services.auth.signIn("viewer", "fixture-viewer-1")
        withTimeout(5_000) { h.services.servers.current.first { it?.server?.apiLevel == level } }
        h.now = recordingTime("2026-11-04T20:00:00Z")!!
    }

    @Test fun `older server is never asked any recording endpoint`() = runBlocking {
        signIn(18)
        assertTrue(h.services.live.recordings(RecordingSection.Now).items.isEmpty())
        assertNull(h.services.live.record(LiveProgramme("p1", 0, 1, "Evening")))
        assertNull(h.services.live.recordManual(ManualRecording("c1", "Evening", 0, 1)))
        h.services.live.cancelRecording("r1")
        h.services.live.updateRecording("r1", prefer = true)
        h.services.live.updateRecordingRule("s1", RecordingOptions())
        h.services.live.deleteRecordingRule("s1")
        assertTrue(server.requests.none { it.path.orEmpty().contains("recording") })
    }

    @Test fun `household list is paged and unknown states stay inert`() = runBlocking {
        signIn()
        server.on("GET /api/live-tv/recordings", json("""{"items":[$ROW,${ROW.replace("r1", "r2").replace("scheduled", "unknown-new-state")}],"nextCursor":"next-page","tuners":2,"freeBytes":50000000000}"""))
        val page = h.services.live.recordings(RecordingSection.Upcoming, "first-page")
        assertEquals(2, page.items.size)
        assertEquals("next-page", page.nextCursor)
        assertEquals("upcoming", server.last("GET /api/live-tv/recordings").requestUrl!!.queryParameter("section"))
        assertEquals("first-page", server.last("GET /api/live-tv/recordings").requestUrl!!.queryParameter("cursor"))
        assertFalse(page.items.last().playable)
        assertEquals("Recording status unavailable", page.items.last().label)
    }

    @Test fun `record episode and series options reach the generated client exactly once`() = runBlocking {
        signIn()
        server.on("POST /api/live-tv/recordings", json(ROW))
        val programme = LiveProgramme("p1", h.now + 60_000, h.now + 3_600_000, "Inspector Vane")
        val row = h.services.live.record(programme, RecordingOptions(series = true, newOnly = false, anyChannel = true, keep = 3, padBefore = 2, padAfter = 15))!!
        assertEquals("r1", row.id)
        val asked = h.body(server.last("POST /api/live-tv/recordings"))
        assertEquals("\"series\"", asked["scope"].toString())
        assertEquals("false", asked["newOnly"].toString())
        assertEquals("true", asked["anyChannel"].toString())
        assertEquals("3", asked["keep"].toString())
        assertEquals("2", asked["padBefore"].toString())
        assertEquals("15", asked["padAfter"].toString())
        assertEquals(1, server.requests("POST /api/live-tv/recordings").size)
    }

    @Test fun `manual preserves instants and recurrence zone and rejects invalid input locally`() = runBlocking {
        signIn()
        server.on("POST /api/live-tv/recordings/manual", json(ROW))
        h.services.live.recordManual(ManualRecording("channel-a", "Evening news", h.now + 60_000, h.now + 3_600_000, "weekdays", "America/New_York"))
        val body = h.body(server.last("POST /api/live-tv/recordings/manual"))
        assertEquals("\"weekdays\"", body["repeat"].toString())
        assertEquals("\"America/New_York\"", body["zone"].toString())
        assertEquals("\"2026-11-04T20:01:00Z\"", body["start"].toString())
        try { h.services.live.recordManual(ManualRecording("", "", 0, 0)); fail("should reject") } catch (_: ApiException) { }
        assertEquals(1, server.requests("POST /api/live-tv/recordings/manual").size)
    }

    @Test fun `cancel and prefer use generated mutation routes and preserve true only`() = runBlocking {
        signIn()
        server.on("PATCH /api/live-tv/recordings/r1", json(ROW))
        server.on("DELETE /api/live-tv/recordings/r1", noContent())
        h.services.live.updateRecording("r1", prefer = true)
        assertEquals("true", h.body(server.last("PATCH /api/live-tv/recordings/r1"))["prefer"].toString())
        assertFalse("over" in h.body(server.last("PATCH /api/live-tv/recordings/r1")))
        server.on("PATCH /api/live-tv/recordings/r1", json(ROW))
        h.services.live.updateRecording("r1", prefer = true, over = "r7")
        assertEquals("\"r7\"", h.body(server.last("PATCH /api/live-tv/recordings/r1"))["over"].toString())
        server.on("PATCH /api/live-tv/recordings/r1", json(ROW))
        h.services.live.updateRecording("r1", RecordingOptions(padBefore = 5), over = "r7")
        assertFalse("over without prefer is never sent", "over" in h.body(server.last("PATCH /api/live-tv/recordings/r1")))
        h.services.live.cancelRecording("r1")
        assertEquals(1, server.requests("DELETE /api/live-tv/recordings/r1").size)
    }

    @Test fun `programme marks and takeover fields are optional when decoding older replies`() = runBlocking {
        signIn()
        server.on("GET /api/live-tv/capabilities", json("""{"enabled":true,"allowed":true,"playback":true,"guide":true,"recording":true,"canRecord":true}"""))
        assertTrue(h.services.live.capabilities().canRecord)
        server.on("GET /api/live-tv/channels", json("""{"items":[{"id":"channel-a","sourceId":"source-a","number":"4.2","name":"Sleuth Hour","available":true,"enabled":true,"now":{"id":"p1","start":"2026-11-04T20:00:00Z","end":"2026-11-04T21:00:00Z","title":"Inspector Vane","seriesKey":"SH0123","recording":{"id":"r1","state":"recording","series":true,"canCancel":true}},"next":{"id":"p2","start":"2026-11-04T21:00:00Z","end":"2026-11-04T23:00:00Z","title":"The Lighthouse Keeper","kind":"movie","seriesKey":""}}]}"""))
        val channel = h.services.live.channels().single()
        assertEquals("SH0123", channel.now!!.seriesKey)
        assertTrue(channel.now!!.offersSeries)
        assertNull("a blank series id is none", channel.next!!.seriesKey)
        assertFalse(channel.next!!.offersSeries)
        val mark = channel.now!!.recording!!
        assertEquals("r1", mark.id)
        assertTrue(mark.series)
        assertTrue(mark.canCancel)
    }

    @Test fun `a clash keeps the recordings holding the tuners and a mark keeps its reason`() = runBlocking {
        signIn()
        val clash = ROW.replace("\"state\":\"scheduled\"", "\"state\":\"conflict\",\"reason\":\"tuners_busy\",\"conflictsWith\":[" +
            "{\"id\":\"r7\",\"title\":\"Coastline Rescue\",\"number\":\"2.1\",\"channelName\":\"HRBR-HD\",\"start\":\"2026-11-04T20:00:00Z\",\"end\":\"2026-11-04T21:00:00Z\",\"userName\":\"Alex\",\"running\":false}," +
            "{\"id\":\"r8\",\"title\":\"Harbor Evening News\",\"number\":\"7.1\",\"channelName\":\"LKSD-HD\",\"start\":\"2026-11-04T19:30:00Z\",\"end\":\"2026-11-04T20:30:00Z\",\"userName\":\"Sam\",\"running\":true}," +
            "{\"id\":\"bad\",\"title\":\"No times\",\"number\":\"9.1\",\"channelName\":\"PNVL-HD\",\"start\":\"later\",\"end\":\"\",\"userName\":\"\",\"running\":false}]")
        server.on("GET /api/live-tv/recordings", json("""{"items":[$clash]}"""))
        val row = h.services.live.recordings(RecordingSection.Conflicts).items.single()
        assertEquals(listOf("r7", "r8"), row.conflictsWith.map { it.id })
        assertEquals("Alex", row.conflictsWith.first().userName)
        assertTrue(row.conflictsWith.last().running)
        assertEquals(listOf("r7"), row.yielding.map { it.id })
        assertEquals("Won’t record: Coastline Rescue and Harbor Evening News have the tuners", row.label)
        server.on("GET /api/live-tv/channels", json("""{"items":[{"id":"channel-a","sourceId":"source-a","number":"4.2","name":"Sleuth Hour","available":true,"enabled":true,"now":{"id":"p1","start":"2026-11-04T20:00:00Z","end":"2026-11-04T21:00:00Z","title":"Inspector Vane","recording":{"id":"r1","state":"conflict","series":false,"reason":"no_tuners"}}}]}"""))
        val mark = h.services.live.channels().single().now!!.recording!!
        assertEquals("no_tuners", mark.reason)
        assertEquals("Won’t record: no tuner is free for recordings", mark.label)
    }

    @Test fun `growing playback and progress use recording routes and never a fabricated file id`() = runBlocking {
        signIn()
        val fixture = TestServer.fixture("decision-hls.json")
        val reply = kotlinx.serialization.json.Json.parseToJsonElement(fixture).let { parsed ->
            val fields = parsed as kotlinx.serialization.json.JsonObject
            kotlinx.serialization.json.JsonObject(fields + ("playlist" to kotlinx.serialization.json.JsonPrimitive("event")) + ("growing" to kotlinx.serialization.json.buildJsonObject {
                put("startedAt", kotlinx.serialization.json.JsonPrimitive("2026-11-04T20:00:00Z"))
                put("endsAt", kotlinx.serialization.json.JsonPrimitive("2026-11-04T21:00:00Z"))
            })).toString()
        }
        server.on("POST /api/live-tv/recordings/r1/playback", json(reply))
        val profile = PlaybackProfile("android", listOf("mp4"), listOf("h264"), listOf("aac"))
        val decision = h.services.playbackGateway.decide(DecisionRequest(0, profile, h.services.client, recordingId = "r1"))
        assertFalse(decision.vod)
        assertEquals(3_600_000L, decision.growing!!.plannedMs)
        assertFalse(h.body(server.last("POST /api/live-tv/recordings/r1/playback")).containsKey("playlist"))
        server.on("PUT /api/live-tv/recordings/r1/progress", json("""{"completed":false,"positionMs":60000,"durationMs":600000,"updatedAt":"2026-11-04T20:01:00Z"}"""))
        h.services.playbackGateway.reportProgress(ProgressReport(0, 60_000, 600_000, "a1", PlayState.Playing, 5_000, recordingId = "r1"))
        assertEquals(1, server.requests("PUT /api/live-tv/recordings/r1/progress").size)
        assertTrue(server.requests.none { it.path.orEmpty().contains("/files/0") })
    }

    @Test fun `delete a finished recording uses its owned route and does not clear the repeat guard by default`() = runBlocking {
        signIn()
        server.on("DELETE /api/live-tv/recordings/r1/file", noContent())
        h.services.live.deleteRecordingFile("r1")
        assertEquals("false", server.last("DELETE /api/live-tv/recordings/r1/file").requestUrl!!.queryParameter("recordAgain"))
        server.on("DELETE /api/live-tv/recordings/r1/file", noContent())
        h.services.live.deleteRecordingFile("r1", recordAgain = true)
        assertEquals("true", server.last("DELETE /api/live-tv/recordings/r1/file").requestUrl!!.queryParameter("recordAgain"))
    }

    @Test fun `owned episode deletion works without any managing app and leaves new option out for old servers`() = runBlocking {
        signIn()
        server.on("GET /api/delete/preview", json("""{"managed":true,"recording":true,"apps":[],"files":1,"size":1024,"kept":0,"choices":{"airing":false,"exclude":false,"keep":false,"keepMonitored":false}}"""))
        val preview = h.services.requests.previewDeleteEpisode(42)
        assertTrue(preview.canDelete)
        assertTrue(preview.recording)
        server.on("POST /api/episodes/42/delete", json("""{"apps":[],"requests":0}"""))
        h.services.requests.deleteEpisode(42, emptyList(), recordAgain = false)
        assertEquals("false", h.body(server.last("POST /api/episodes/42/delete"))["recordAgain"].toString())
        signIn(18)
        server.on("POST /api/episodes/42/delete", json("""{"apps":[],"requests":0}"""))
        h.services.requests.deleteEpisode(42, emptyList(), recordAgain = false)
        assertFalse("recordAgain" in h.body(server.last("POST /api/episodes/42/delete")))
    }

    @Test fun `simple record inherits server padding until options explicitly changes it`() = runBlocking {
        signIn()
        server.on("POST /api/live-tv/recordings", json(ROW))
        h.services.live.record(LiveProgramme("p1", h.now + 60_000, h.now + 3_600_000, "Inspector Vane"))
        val body = h.body(server.last("POST /api/live-tv/recordings"))
        assertFalse("padBefore" in body)
        assertFalse("padAfter" in body)
        server.on("POST /api/live-tv/recordings", json(ROW))
        h.services.live.record(LiveProgramme("p2", h.now + 60_000, h.now + 3_600_000, "Inspector Vane"), RecordingOptions(customPadding = true))
        assertEquals("1", h.body(server.last("POST /api/live-tv/recordings"))["padBefore"].toString())
    }

    private companion object {
        const val ROW = """{"id":"r1","title":"Inspector Vane","channelId":"channel-a","sourceId":"source-a","number":"4.2","channelName":"Sleuth Hour","start":"2026-11-04T20:00:00Z","end":"2026-11-04T21:00:00Z","state":"scheduled","userName":"Sam","series":false,"padBefore":1,"padAfter":2,"bytes":0,"gaps":[],"canCancel":true}"""
    }
}
