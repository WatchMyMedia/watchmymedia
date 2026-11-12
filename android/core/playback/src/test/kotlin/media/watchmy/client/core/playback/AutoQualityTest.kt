package media.watchmy.client.core.playback

import kotlinx.coroutines.CompletableDeferred
import media.watchmy.client.core.playback.player.PlayerPhase
import media.watchmy.client.core.playback.player.Stalls
import media.watchmy.client.core.playback.player.TransferSample
import media.watchmy.client.core.playback.player.PlayerFailure
import media.watchmy.client.core.playback.profile.ProfileProvider
import media.watchmy.client.model.DecisionRequest
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlayState
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.ProgressReply
import media.watchmy.client.model.QualityChange
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.StreamProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automatic quality (PLAN-AUTO-QUALITY.md §2, §6): the request, the heartbeats, the server's step changes. */
class AutoQualityTest {
    private val h = Harness(quality = StreamQuality.Auto)

    @After
    fun tearDown() = h.release()

    /** The server's answer: the step it is at for Automatic, none for a fixed quality. */
    private var step = StreamQuality.Original

    private fun answer(r: DecisionRequest) = if (r.quality == StreamQuality.Auto) {
        decision(autoStep = step, mode = if (step == StreamQuality.Original) PlaybackMode.Direct else PlaybackMode.Transcode, bitrate = step.maxBitrate ?: 12_000_000)
    } else {
        decision(mode = PlaybackMode.Transcode)
    }

    private fun startPlaying() = h.start(PlayRequest(1, 0, "Harbor Lights"), ::answer)

    private fun lower(to: StreamQuality, message: String? = "Lowered to ${stepName(to)} for your connection.") {
        step = to
        h.gateway.reply = { ProgressReply(completed = false, stopMessage = null, newPlayId = null, qualityChange = QualityChange(to, message)) }
    }

    @Test
    fun automaticSendsTheDevicesOwnProfileAndShowsTheStep() {
        startPlaying()
        val r = h.gateway.decisions.single()
        assertEquals(StreamQuality.Auto, r.quality)
        assertEquals("no step's caps", TV_PROFILE, r.profile)
        assertEquals(StreamQuality.Auto, h.state.quality)
        assertEquals(StreamQuality.Original, h.state.autoStep)
        assertEquals(12_000_000L, h.state.bitrate)
        assertEquals(StreamQuality.entries.toList(), h.state.qualities)
    }

    @Test
    fun anOlderServerIsOfferedNoAutomatic() {
        val older = Harness()
        older.prefs.autoQuality.value = false
        older.start(PlayRequest(1, 0, "Harbor Lights"), decision())
        assertEquals(StreamQuality.offered(auto = false), older.state.qualities)
        assertTrue(StreamQuality.Auto !in older.state.qualities)
        older.release()
    }

    @Test
    fun automaticReportsEveryFiveSecondsWithWhatThePlayerMeasured() {
        h.player.readyOnLoad = false
        startPlaying()
        h.advance(1_000)
        h.player.ready()
        h.run()
        assertEquals(listOf(PlayState.Buffering, PlayState.Playing), h.states)
        assertEquals("time to the first frame, once", 1_000L, h.gateway.reports.last().startupMs)

        h.player.throughputBps = 31_000_000
        h.player.stalls = Stalls(1, 2_400)
        h.advance(4_750)
        assertEquals(2, h.gateway.reports.size)
        h.advance(250)
        val beat = h.gateway.reports.last()
        assertEquals(3, h.gateway.reports.size)
        assertEquals(31_000_000L, beat.throughputBps)
        assertEquals(1, beat.stalls)
        assertEquals(2_400L, beat.stallMs)
        assertNull(beat.startupMs)

        // Buffering: every 5 s too. Paused: every 30 s, as at a fixed quality.
        h.player.buffer()
        h.run()
        h.advance(5_000)
        assertEquals(listOf(PlayState.Buffering, PlayState.Buffering), h.states.takeLast(2))
        h.player.ready()
        h.controller.pause()
        h.run()
        val paused = h.gateway.reports.size
        h.advance(29_750)
        assertEquals(paused, h.gateway.reports.size)
        h.advance(250)
        assertEquals(paused + 1, h.gateway.reports.size)
    }

    @Test
    fun aFixedQualityReportsMeasurementsAtItsUsualPace() {
        val fixed = Harness(quality = StreamQuality.Hd)
        fixed.player.throughputBps = 9_000_000
        fixed.start(PlayRequest(1, 0, "Harbor Lights"), decision())
        fixed.advance(9_750)
        assertEquals(1, fixed.gateway.reports.size)
        fixed.advance(250)
        assertEquals(9_000_000L, fixed.gateway.reports.last().throughputBps)
        fixed.release()
    }

    @Test
    fun theServersStepChangeReloadsHereInTheSamePlayWithoutSavingIt() {
        startPlaying()
        h.advance(20_000)
        lower(StreamQuality.Hd)
        h.advance(5_000)

        val again = h.gateway.decisions.last()
        assertEquals(2, h.gateway.decisions.size)
        assertEquals(StreamQuality.Auto, again.quality)
        assertEquals("a1", again.playId)
        assertEquals(TV_PROFILE, again.profile)
        assertEquals(25_000L, again.startMs)
        assertEquals(25_000L, h.player.loads.last().startPositionMs)
        assertEquals(StreamQuality.Hd, h.state.autoStep)
        assertEquals(StreamQuality.Auto, h.state.quality)
        assertEquals("the device's default stays", StreamQuality.Auto, h.prefs.quality.value)
        assertEquals("Lowered to 720p for your connection.", h.state.note)

        // The server sends it again until it hears of the new decision: nothing more happens.
        h.advance(5_000)
        assertEquals(2, h.gateway.decisions.size)

        // The note goes after a few seconds.
        h.advance(1_000)
        assertNull(h.state.note)
    }

    @Test
    fun aStepChangeWithoutAMessageSaysWhatItIs() {
        startPlaying()
        lower(StreamQuality.Uhd, message = null)
        h.advance(6_000)
        assertEquals("Lowered to 4K for your connection.", h.state.note)
    }

    @Test
    fun aFixedQualityIgnoresTheDirective() {
        val fixed = Harness(quality = StreamQuality.FullHd)
        fixed.start(PlayRequest(1, 0, "Harbor Lights"), decision())
        fixed.gateway.reply = { ProgressReply(completed = false, stopMessage = null, newPlayId = null, qualityChange = QualityChange(StreamQuality.Hd, null)) }
        fixed.advance(30_000)
        assertEquals(1, fixed.gateway.decisions.size)
        fixed.release()
    }

    @Test
    fun aReloadLeavesTheOldStreamPlayingUntilTheNewOneLoads() {
        startPlaying()
        h.advance(10_000)
        val reports = h.gateway.reports.size
        val hold = CompletableDeferred<Unit>()
        h.gateway.hold = hold
        lower(StreamQuality.Hd)
        h.advance(5_000)
        assertEquals(2, h.gateway.decisions.size)
        assertEquals(15_000L, h.gateway.decisions.last().startMs)

        // The decision is out: the old stream plays on, and no heartbeat claims the new one has started.
        h.advance(6_000)
        assertEquals(PlayerPhase.Ready, h.player.phase)
        assertEquals(PlayerStatus.Playing, h.state.status)
        assertEquals(21_000L, h.progress.positionMs)
        assertEquals(1, h.player.loads.size)
        assertEquals(reports + 1, h.gateway.reports.size)

        hold.complete(Unit)
        h.run()
        assertEquals("from where the old stream got to", 21_000L, h.player.loads.last().startPositionMs)
        assertEquals(StreamQuality.Hd, h.state.autoStep)
    }

    @Test
    fun aSeekWhileAReloadIsOutStartsTheNewStreamThere() {
        startPlaying()
        h.advance(10_000)
        val hold = CompletableDeferred<Unit>()
        h.gateway.hold = hold
        h.controller.selectAudio(COMMENTARY.index)
        h.run()
        h.controller.seekTo(30 * MIN)
        h.run()
        assertEquals(30 * MIN, h.progress.positionMs)
        assertEquals(PlayerStatus.Buffering, h.state.status)
        hold.complete(Unit)
        h.run()
        assertEquals(30 * MIN, h.player.loads.last().startPositionMs)
    }

    @Test
    fun pickingAFixedQualityEndsAutomaticAndIsSaved() {
        startPlaying()
        h.advance(5_000)
        h.controller.selectQuality(StreamQuality.FullHd)
        h.run()
        val r = h.gateway.decisions.last()
        assertEquals(StreamQuality.FullHd, r.quality)
        assertEquals(8_000_000L, r.profile.maxBitrate)
        assertEquals(StreamQuality.FullHd, h.state.quality)
        assertNull(h.state.autoStep)
        assertNull("not the server's switch", h.state.note)
        assertEquals(StreamQuality.FullHd, h.prefs.quality.value)

        h.controller.selectQuality(StreamQuality.Auto)
        h.run()
        assertEquals(StreamQuality.Auto, h.gateway.decisions.last().quality)
        assertEquals(StreamQuality.Auto, h.prefs.quality.value)
    }

    @Test
    fun stallingAtTheLowestStepSaysSoOnce() {
        step = StreamQuality.Low
        startPlaying()
        h.advance(5_000)
        assertNull(h.state.note)
        h.player.stalls = Stalls(1, 3_000)
        h.advance(250)
        assertEquals("Your connection is too slow to play this smoothly.", h.state.note)
        h.advance(6_000)
        assertNull(h.state.note)
        h.player.stalls = Stalls(2, 6_000)
        h.advance(250)
        assertNull("once", h.state.note)
    }

    @Test
    fun theNotesStepIsLowerThanTheOneBefore() {
        startPlaying()
        lower(StreamQuality.Sd)
        h.advance(5_000)
        assertNotEquals(null, h.state.note)
        assertEquals(StreamQuality.Sd, h.state.autoStep)
    }

    @Test
    fun negotiatedUpgradeWaitsForFirstFrameAndKeepsTheOldSession() {
        step = StreamQuality.Hd
        h.player.renderOnReady = false
        h.start(PlayRequest(1, 0, "Harbor Lights")) { r ->
            answer(r).copy(sessionId = if (step == StreamQuality.Hd) "s720" else "s1080")
        }
        assertTrue(h.gateway.decisions.single().autoRecovery)
        h.player.transfer = TransferSample(40_000_000, 200)
        h.advance(5_000)
        assertEquals(200L, h.gateway.reports.last().throughputAgeMs)

        step = StreamQuality.FullHd
        h.gateway.reply = { ProgressReply(false, null, null, QualityChange(step, "Raised to 1080p for your connection.", 2)) }
        h.advance(5_000)
        assertEquals(2L, h.gateway.decisions.last().switchGeneration)
        assertEquals(StreamQuality.FullHd, h.state.autoStep)
        assertTrue("old source remains through first frame", "s720" !in h.gateway.stoppedSessions)
        assertNull("no premature success note", h.state.note)
        h.advance(5_000)
        assertNull(h.gateway.reports.last().switchGeneration)

        h.player.renderOnReady = true
        h.player.ready()
        h.run()
        h.advance(5_000)
        assertTrue(h.gateway.reports.any { it.switchGeneration == 2L })
        assertEquals("Raised to 1080p for your connection.", h.state.note)
        assertEquals(StreamQuality.Auto, h.prefs.quality.value)
        h.gateway.reply = { ProgressReply(false, null, null, QualityChange(StreamQuality.Hd, "old directive", 1)) }
        h.advance(5_000)
        assertEquals("stale generation cannot reload", 2, h.gateway.decisions.size)
    }

    @Test
    fun failedUpgradeRestoresPreviousSourceAndReportsItsGeneration() {
        step = StreamQuality.Hd
        h.start(PlayRequest(1, 0, "Harbor Lights")) { r ->
            answer(r).copy(sessionId = if (step == StreamQuality.Hd) "s720" else "s1080")
        }
        step = StreamQuality.FullHd
        h.player.renderOnReady = false
        h.gateway.reply = { ProgressReply(false, null, null, QualityChange(step, null, 3)) }
        h.advance(5_000)
        assertEquals(StreamQuality.FullHd, h.state.autoStep)
        h.player.fail(PlayerFailure(PlayerFailure.Kind.Decoder, "new rendition failed"))
        h.run()
        assertEquals(StreamQuality.Hd, h.state.autoStep)
        assertEquals(StreamQuality.Auto, h.state.quality)
        assertTrue("new source released", "s1080" in h.gateway.stoppedSessions)
        h.advance(5_000)
        assertTrue(h.gateway.reports.any { it.switchFailedGeneration == 3L })
    }

    @Test
    fun refusedUpgradeKeepsTheOldSourceAndReportsFailure() {
        step = StreamQuality.Hd
        h.start(PlayRequest(1, 0, "Harbor Lights")) { r ->
            if (r.switchGeneration == 4L) throw ApiException(429, "No encoder slot")
            answer(r).copy(sessionId = "s720")
        }
        val oldLoads = h.player.loads.size
        h.gateway.reply = { ProgressReply(false, null, null, QualityChange(StreamQuality.FullHd, "Raised to 1080p", 4)) }
        h.advance(5_000)
        assertEquals(oldLoads, h.player.loads.size)
        assertEquals(StreamQuality.Hd, h.state.autoStep)
        assertEquals(PlayerStatus.Playing, h.state.status)
        h.advance(5_000)
        assertTrue(h.gateway.reports.any { it.switchFailedGeneration == 4L })
        assertTrue("old source was not stopped", "s720" !in h.gateway.stoppedSessions)
    }

    @Test
    fun failedLowerStepRestoresThePreviousSource() {
        step = StreamQuality.FullHd
        h.start(PlayRequest(1, 0, "Harbor Lights")) { r ->
            answer(r).copy(sessionId = if (step == StreamQuality.FullHd) "s1080" else "s720")
        }
        step = StreamQuality.Hd
        h.player.renderOnReady = false
        h.gateway.reply = { ProgressReply(false, null, null, QualityChange(step, null, 6)) }
        h.advance(5_000)
        assertEquals(StreamQuality.Hd, h.state.autoStep)
        assertTrue("old source remains while switching", "s1080" !in h.gateway.stoppedSessions)
        h.player.fail(PlayerFailure(PlayerFailure.Kind.Decoder, "lower rendition failed"))
        h.run()
        assertEquals(StreamQuality.FullHd, h.state.autoStep)
        assertEquals(PlayerStatus.Playing, h.state.status)
        assertTrue("failed source released", "s720" in h.gateway.stoppedSessions)
        h.advance(5_000)
        assertTrue(h.gateway.reports.any { it.switchFailedGeneration == 6L })
    }

    @Test
    fun synchronousPlayerLoadFailureRestoresThePreviousSource() {
        step = StreamQuality.Hd
        h.start(PlayRequest(1, 0, "Harbor Lights")) { r ->
            answer(r).copy(sessionId = if (step == StreamQuality.Hd) "s720" else "s1080")
        }
        step = StreamQuality.FullHd
        h.player.failNextLoad = IllegalStateException("renderer rejected source")
        h.gateway.reply = { ProgressReply(false, null, null, QualityChange(step, null, 7)) }
        h.advance(5_000)
        assertEquals(StreamQuality.Hd, h.state.autoStep)
        assertEquals(PlayerStatus.Playing, h.state.status)
        assertTrue("failed source released", "s1080" in h.gateway.stoppedSessions)
        h.advance(5_000)
        assertTrue(h.gateway.reports.any { it.switchFailedGeneration == 7L })
    }

    @Test
    fun negotiatedSwitchUsesTheCurrentDisplayProfile() {
        var device = TV_PROFILE
        val session = Harness(quality = StreamQuality.Auto, profileProvider = ProfileProvider { device })
        try {
            session.start(PlayRequest(1, 0, "Harbor Lights")) { r ->
                decision(autoStep = if (r.switchGeneration == null) StreamQuality.Hd else StreamQuality.FullHd)
            }
            device = TV_PROFILE.copy(maxWidth = 1920, maxHeight = 1080, hdr = false)
            session.gateway.reply = { ProgressReply(false, null, null, QualityChange(StreamQuality.FullHd, null, 8)) }
            session.advance(5_000)
            assertEquals(1920, session.gateway.decisions.last().profile.maxWidth)
            assertEquals(1080, session.gateway.decisions.last().profile.maxHeight)
            assertEquals(false, session.gateway.decisions.last().profile.hdr)
            assertEquals(StreamQuality.Auto, session.state.quality)
        } finally {
            session.release()
        }
    }

    @Test
    fun recoveryCanReturnToDirectPlayWithoutStoppingTheOldHlsFirst() {
        step = StreamQuality.Hd
        h.start(PlayRequest(1, 0, "Harbor Lights")) { r ->
            if (step == StreamQuality.Original) decision(protocol = StreamProtocol.File, mode = PlaybackMode.Direct,
                autoStep = StreamQuality.Original, playId = "a1", bitrate = 6_000_000)
            else answer(r).copy(sessionId = "s720")
        }
        step = StreamQuality.Original
        h.gateway.reply = { ProgressReply(false, null, null, QualityChange(StreamQuality.Original, null, 5)) }
        h.advance(5_000)
        assertEquals(5L, h.gateway.decisions.last().switchGeneration)
        assertEquals(StreamQuality.Original, h.state.autoStep)
        assertEquals(PlaybackMode.Direct, h.state.mode)
        assertTrue("source switches to progressive", !h.player.loads.last().hls)
        assertTrue("old HLS remains until acknowledgement", "s720" !in h.gateway.stoppedSessions)
        h.advance(5_000)
        assertTrue(h.gateway.reports.any { it.switchGeneration == 5L })
        assertEquals("Connection recovered; playing the original file.", h.state.note)
    }
}
