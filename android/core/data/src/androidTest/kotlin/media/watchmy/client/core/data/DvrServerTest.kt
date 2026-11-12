package media.watchmy.client.core.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import media.watchmy.client.model.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Opt-in acceptance against cmd/livetvfixture -dvr only, never a discovered or personal server. */
@RunWith(AndroidJUnit4::class)
class DvrServerTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val address = InstrumentationRegistry.getArguments().getString("wmmDvrServer")
    private val plain = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    @Test fun recordWatchWhileGrowingFinishAndFindInLibrary(): Unit = runBlocking {
        assumeTrue("Pass wmmDvrServer for the disposable DVR fixture", address != null)
        val base = address!!
        val fixture = plain.newCall(Request.Builder().url("$base/_fixture/status").build()).execute().use { it.body.string() }
        assertTrue("Refuse every server except the explicit DVR fixture", fixture.contains("dvr-fixture-v1"))
        val data = DataServices(context, DataServices.clientInfo(context, tv = false, version = "0.1.0-dvr-test"))
        data.auth.user.value?.let { data.auth.signOut() }
        data.servers.select(data.servers.probe(base))
        data.auth.signIn("Fixture Admin", "fixture-password")
        var recording: LiveRecording? = null
        var session: String? = null
        try {
            val caps = data.live.capabilities()
            assertTrue(caps.recording)
            assertTrue(caps.canRecord)
            val channel = data.live.channels().first { it.available }
            val now = System.currentTimeMillis()
            recording = data.live.recordManual(ManualRecording(channel.id, "Android fixture recording", now + 2_000, now + 75_000, zone = "UTC"))!!
            val id = recording.id
            withTimeout(45_000) {
                while (true) {
                    val row = data.live.recordings(RecordingSection.Now).items.firstOrNull { it.id == id }
                    if (row != null) { recording = row; break }
                    delay(250)
                }
            }
            assertTrue(recording!!.active)
            val profile = PlaybackProfile("android-dvr", listOf("mp4"), listOf("h264"), listOf("aac"), maxWidth = 1280, maxHeight = 720)
            val decision = data.playbackGateway.decide(DecisionRequest(0, profile, data.client, recordingId = id))
            session = decision.sessionId
            assertFalse(decision.vod)
            assertNotNull(decision.growing)
            assertEquals(StreamProtocol.Hls, decision.protocol)
            val token = data.playbackGateway.mediaToken()
            plain.newCall(Request.Builder().url("${decision.url}?${token.param}=${token.token}").build()).execute().use {
                assertEquals(200, it.code)
                assertTrue(it.body.string().startsWith("#EXTM3U"))
            }
            data.playbackGateway.reportProgress(ProgressReport(0, 1_000, decision.durationMs, decision.playId, PlayState.Playing, 1_000, recordingId = id))
            session?.let { data.playbackGateway.stopSession(it) }
            session = null
            data.live.cancelRecording(id)
            withTimeout(90_000) {
                while (true) {
                    val row = data.live.recordings(RecordingSection.Recorded).items.firstOrNull { it.id == id }
                    if (row?.mediaFileId != null) { recording = row; break }
                    delay(500)
                }
            }
            assertTrue(recording!!.bytes > 0)
            assertTrue(recording!!.mediaFileId!! > 0)
            assertNull(recording!!.playRequest()!!.recordingId)
            val finished = data.playbackGateway.decide(DecisionRequest(recording!!.mediaFileId!!, profile, data.client))
            assertNull(finished.growing)
            finished.sessionId?.let { data.playbackGateway.stopSession(it) }
            assertTrue(recording!!.canDelete)
            data.live.deleteRecordingFile(id)
            assertTrue(data.live.recordings(RecordingSection.Recorded).items.none { it.id == id })
        } finally {
            session?.let { runCatching { data.playbackGateway.stopSession(it) } }
            recording?.takeIf { it.active || it.state == "scheduled" }?.let { runCatching { data.live.cancelRecording(it.id) } }
            data.auth.signOut()
        }
    }
}
