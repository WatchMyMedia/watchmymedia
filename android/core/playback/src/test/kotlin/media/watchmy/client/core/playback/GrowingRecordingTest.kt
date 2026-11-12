package media.watchmy.client.core.playback

import media.watchmy.client.model.GrowingRecording
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlayerStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Exercises the existing file player, including its seek and heartbeat paths. */
class GrowingRecordingTest {
    private val h = Harness()
    private val growing = GrowingRecording(TestClock.WALL_START - 600_000, TestClock.WALL_START + 3_000_000)
    @After fun close() = h.release()

    @Test fun `growing recording uses event playback and heartbeat retains the recording identity`() {
        h.start(PlayRequest(0, title = "Inspector Vane", recordingId = "rec-1"), decision(vod = false, durationMs = 600_000).copy(growing = growing))
        assertFalse(h.gateway.decisions.single().vod)
        assertEquals("rec-1", h.gateway.decisions.single().recordingId)
        h.advance(12_000)
        assertEquals(612_000L, h.progress.durationMs)
        assertEquals(3_600_000L, h.state.durationMs)
        assertTrue(h.gateway.reports.isNotEmpty())
        assertTrue(h.gateway.reports.all { it.recordingId == "rec-1" })
        assertEquals("anchored on the decision's recorded length", growing.anchored(600_000, 0), h.state.growing)
        assertTrue(h.watchNext.updates.isEmpty())
    }

    @Test fun `a seek past now clamps to recorded data rather than the initial duration`() {
        h.start(PlayRequest(0, title = "Inspector Vane", recordingId = "rec-1")) { request ->
            decision(vod = false, durationMs = 600_000, startMs = request.startMs ?: 0).copy(growing = growing)
        }
        h.advance(60_000)
        h.controller.seekTo(3_000_000)
        h.run()
        assertEquals(657_000L, h.gateway.decisions.last().startMs)
        assertTrue(h.progress.positionMs <= 660_000)
    }

    @Test fun `a recording past its watched point keeps the usual heartbeat pace`() {
        h.start(PlayRequest(0, title = "Inspector Vane", recordingId = "rec-1"), decision(vod = false, durationMs = 600_000, watchedAtMs = 5_000).copy(growing = growing))
        h.advance(6_000)
        val before = h.gateway.reports.size
        h.advance(30_000)
        // One beat each 10 s while playing, not one each tick.
        assertTrue("sent ${h.gateway.reports.size - before}", h.gateway.reports.size - before <= 3)
        assertTrue(h.watchNext.updates.isEmpty())
        assertTrue(h.watchNext.removals.isEmpty())
    }

    @Test fun `the recorded length counts on from the decision, whatever this device's wall clock says`() {
        // The device's clock is an hour behind the server's: by it, the recording hasn't started.
        val skewed = GrowingRecording(TestClock.WALL_START + 3_600_000, TestClock.WALL_START + 7_200_000)
        h.start(PlayRequest(0, title = "Inspector Vane", recordingId = "rec-1"), decision(vod = false, durationMs = 600_000).copy(growing = skewed))
        h.advance(12_000)
        assertEquals(612_000L, h.progress.durationMs)
        assertEquals(skewed.plannedMs, h.state.durationMs)
    }

    @Test fun `once its end passes the recording label goes and the play carries on`() {
        val ending = GrowingRecording(TestClock.WALL_START - 600_000, TestClock.WALL_START + 20_000)
        h.start(PlayRequest(0, title = "Inspector Vane", recordingId = "rec-1"), decision(vod = false, durationMs = 600_000).copy(growing = ending))
        h.advance(5_000)
        assertNotNull(h.state.growing)
        h.advance(20_000)
        assertNull(h.state.growing)
        assertEquals(PlayerStatus.Playing, h.state.status)
        assertEquals(620_000L, h.state.durationMs)
        h.advance(30_000)
        assertEquals("no longer grows", 620_000L, h.progress.durationMs)
    }

    @Test fun `a playlist that gets its end tag early ends the growth at its listed length`() {
        h.start(PlayRequest(0, title = "Inspector Vane", recordingId = "rec-1"), decision(vod = false, durationMs = 600_000).copy(growing = growing))
        h.advance(5_000)
        // Stopped early: the server ended the playlist at 10:02.
        h.player.endedEventDurationMs = 602_000
        h.advance(1_000)
        assertNull(h.state.growing)
        assertEquals(602_000L, h.state.durationMs)
        assertEquals(PlayerStatus.Playing, h.state.status)
        h.advance(30_000)
        assertEquals(602_000L, h.progress.durationMs)
    }

    @Test fun `watch from the start starts at 0 even with earlier progress`() {
        val recording = media.watchmy.client.model.LiveRecording("rec-1", "Inspector Vane", "c1", "4.2", "Sleuth Hour", 0, 3_600_000, "recording")
        h.start(recording.playRequest()!!) { request -> decision(vod = false, durationMs = 600_000, resumeMs = 300_000, startMs = request.startMs ?: 0).copy(growing = growing) }
        assertEquals(0L, h.gateway.decisions.first().startMs)
        assertTrue(h.progress.positionMs < 5_000)
    }

    @Test fun `ordinary files keep their fixed duration and no recording identity`() {
        h.start(PlayRequest(1, title = "Lighthouse"), decision(durationMs = 600_000))
        h.advance(12_000)
        assertEquals(600_000L, h.state.durationMs)
        assertNull(h.state.growing)
        assertNull(h.gateway.decisions.single().recordingId)
    }
    @Test fun `recording stopped early freezes the duration at the event end instead of growing forever`() {
        h.start(PlayRequest(0, title = "Inspector Vane", recordingId = "rec-1"), decision(vod = false, durationMs = 600_000).copy(growing = growing))
        h.advance(12_000)
        val actual = h.progress.positionMs
        h.player.end()
        h.run()
        assertNull(h.state.growing)
        assertEquals(actual, h.state.durationMs)
        h.advance(60_000)
        assertEquals(actual, h.state.durationMs)
    }

}
