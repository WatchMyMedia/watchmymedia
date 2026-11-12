package media.watchmy.client.core.downloads

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.DecisionRequest
import media.watchmy.client.model.DownloadQuality
import media.watchmy.client.model.DownloadStatus
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.OfflineDownload
import media.watchmy.client.model.OfflineItem
import media.watchmy.client.model.PlayState
import media.watchmy.client.model.PlaybackDecision
import media.watchmy.client.model.PlaybackGateway
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.ProgressReply
import media.watchmy.client.model.ProgressReport
import media.watchmy.client.model.StreamProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A downloaded item plays from the device with its heartbeats queued; anything else goes to the server as before. */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflinePlaybackGatewayTest {
    private val decision = PlaybackDecision(
        mode = PlaybackMode.Direct, reasons = emptyList(), protocol = StreamProtocol.File, vod = true, url = "http://s/api/offline/abcdefghijklmnop/stream",
        mimeType = null, playId = "offline", sessionId = null, durationMs = 3_000_000, startMs = 0, resumeMs = 0, audio = emptyList(), selectedAudio = null,
        subtitles = emptyList(), burnInSubtitleId = null, segments = emptyList(), creditsMs = 0, watchedAtMs = 2_700_000, upNextMs = 0, next = null,
    )
    private val download = OfflineDownload(
        "abcdefghijklmnop", 42, OfflineItem(OfflineItem.Kind.Movie, "Dead Reckoning Bay"), DownloadQuality.Original, PlaybackMode.Direct, StreamProtocol.File,
        1, false, DownloadStatus.Downloaded,
    )
    private val reports = mutableListOf<ProgressReport>()
    private val plays = object : OfflinePlays {
        override fun offlinePlay(fileId: Long) = if (fileId == 42L) OfflinePlay(download, decision) else null
        override fun progress(play: OfflinePlay, report: ProgressReport) {
            reports += report
        }
    }
    private val online = object : PlaybackGateway {
        val calls = mutableListOf<String>()
        override suspend fun decide(request: DecisionRequest): PlaybackDecision {
            calls += "decide ${request.fileId}"
            return decision.copy(url = "http://s/api/files/${request.fileId}/stream", playId = "0123456789abcdef")
        }
        override suspend fun reportProgress(report: ProgressReport): ProgressReply {
            calls += "progress ${report.fileId}"
            return ProgressReply(false, null, null)
        }
        override suspend fun mediaToken(): MediaToken {
            calls += "token"
            return MediaToken("m1.x", Long.MAX_VALUE, serverUrl = "http://s")
        }
        override suspend fun stopSession(sessionId: String) = Unit
    }

    private fun request(fileId: Long) = DecisionRequest(fileId, PlaybackProfile("android", emptyList(), emptyList(), emptyList()), ClientInfo("d", "n", "p", "android", "1"))

    @Test
    fun `a downloaded item plays from the device, with no token and no server`() = runTest {
        val g = OfflinePlaybackGateway(online, plays, UnconfinedTestDispatcher(testScheduler)).bind()
        assertSame(decision, g.decide(request(42)))
        val token = g.mediaToken()
        assertNull("The placeholder goes on no URL", token.serverUrl)
        val reply = g.reportProgress(ProgressReport(42, 2_800_000, 3_000_000, "offline", PlayState.Playing, 0))
        assertTrue("Past the credits counts as watched", reply.completed)
        assertFalse(g.reportProgress(ProgressReport(42, 60_000, 3_000_000, "offline", PlayState.Paused, 0)).completed)
        assertEquals(2, reports.size)
        assertTrue("Nothing reached the server", online.calls.isEmpty())
        assertNull(g.searchSubtitles(42))
    }

    @Test
    fun `anything not downloaded goes to the server as before`() = runTest {
        val g = OfflinePlaybackGateway(online, plays, UnconfinedTestDispatcher(testScheduler))
        g.decide(request(42))
        g.decide(request(7))
        assertEquals("m1.x", g.mediaToken().token)
        g.reportProgress(ProgressReport(7, 1, 2, "0123456789abcdef", PlayState.Playing, 0))
        assertEquals(listOf("decide 7", "token", "progress 7"), online.calls)
        assertTrue(reports.isEmpty())
    }
}
