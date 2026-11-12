package media.watchmy.client.core.playback

import media.watchmy.client.model.DecisionRequest
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlayState
import media.watchmy.client.model.PlaybackDecision
import media.watchmy.client.model.PlaybackPreferences
import media.watchmy.client.model.PlayerEvent
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.Segment
import media.watchmy.client.model.SegmentKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodeTest {
    private val upNextAt = 48 * MIN
    private var h = Harness()

    @After
    fun tearDown() = h.release()

    /** An episode whose final credits (and the Next Episode card) start at 48 min; the next one is file 2. */
    private fun episode(request: DecisionRequest): PlaybackDecision = if (request.fileId == 2L) {
        decision(playId = "b1", sessionId = "s2")
    } else {
        decision(
            segments = listOf(Segment(SegmentKind.Intro, 60_000, 105_000), Segment(SegmentKind.Credits, upNextAt, null)),
            creditsMs = upNextAt,
            watchedAtMs = upNextAt,
            upNextMs = upNextAt,
            next = NEXT,
        )
    }

    private fun play(from: Long = upNextAt - 5_000, preferences: PlaybackPreferences = PlaybackPreferences()) {
        h.release()
        h = Harness(preferences)
        h.start(PlayRequest(1, from, "Harbor Lights", "S03 E07 · The Long Dark", backdropPath = "/img/b.jpg"), ::episode)
    }

    @Test
    fun countdownHoldsWhilePausedThenStartsTheNextEpisode() {
        play()
        h.advance(4_750)
        assertNull(h.state.upNext)
        h.advance(250)
        assertEquals(NEXT, h.state.upNext!!.next)
        assertEquals(10_000L, h.state.upNext!!.countdownMs)
        // The final credits have the card instead of a Skip button.
        assertNull(h.state.skip)
        h.advance(4_000)
        assertEquals(6_000L, h.state.upNext!!.countdownMs)

        h.controller.pause()
        h.advance(20_000)
        assertEquals(6_000L, h.state.upNext!!.countdownMs)
        assertTrue(h.events.isEmpty())

        h.controller.play()
        h.advance(5_750)
        assertEquals(250L, h.state.upNext!!.countdownMs)
        assertTrue(h.events.isEmpty())
        h.advance(250)
        assertEquals(listOf<PlayerEvent>(PlayerEvent.StartedNext(2)), h.events)

        // This episode finished: watched, reported at its end, its session ended.
        val last = h.gateway.reports.last { it.fileId == 1L }
        assertEquals(PlayState.Stopped, last.state)
        assertEquals(50 * MIN, last.positionMs)
        assertTrue("s1" in h.gateway.stoppedSessions)
        assertEquals(listOf(1L), h.watchNext.removals)

        // The next is a new play, with a new token, in place.
        val next = h.gateway.decisions.last()
        assertEquals(2L, next.fileId)
        assertNull(next.playId)
        assertNull(next.startMs)
        assertEquals(2, h.gateway.tokens)
        assertEquals("Harbor Lights", h.state.title)
        assertEquals("S03 E08 · Squall Line", h.state.subtitle)
        assertEquals("/img/b.jpg", h.state.backdropPath)
        assertEquals(PlayerStatus.Playing, h.state.status)
        assertEquals("b1", h.gateway.reports.last().playId)
    }

    @Test
    fun finishingAnEpisodePutsTheNextOnTheLauncherRowAsTheOneToWatchNext() {
        play()
        h.advance(5_000)
        assertEquals(listOf(1L), h.watchNext.removals)
        val next = h.watchNext.updates.single()
        assertTrue(next.next)
        assertEquals(NEXT.fileId, next.fileId)
        assertEquals("Harbor Lights", next.title)
        assertEquals("S03 E08 · Squall Line", next.subtitle)
        assertEquals(0L, next.positionMs)
        assertEquals(listOf(NEXT.episodeId, NEXT.showId, NEXT.season.toLong()), listOf(next.itemId, next.showId, next.season?.toLong()))
    }

    @Test
    fun autoplayOffWaitsForAChoice() {
        play(preferences = PlaybackPreferences(autoplayNext = false))
        h.advance(5_000)
        assertNotNull(h.state.upNext)
        assertNull(h.state.upNext!!.countdownMs)
        h.advance(60_000)
        assertTrue(h.events.isEmpty())
        // At the end it stays: no Exit while a next episode is on offer.
        h.player.end()
        h.advance(30_000)
        assertEquals(PlayerStatus.Ended, h.state.status)
        assertNotNull(h.state.upNext)
        assertTrue(h.events.isEmpty())
        h.controller.playNext()
        h.run()
        assertEquals(listOf<PlayerEvent>(PlayerEvent.StartedNext(2)), h.events)
    }

    @Test
    fun watchCreditsHidesTheCardUntilTheEndOrASeekBack() {
        play()
        h.advance(5_000)
        assertNotNull(h.state.upNext)
        h.controller.watchCredits()
        h.run()
        assertNull(h.state.upNext)
        h.advance(30_000)
        assertNull(h.state.upNext)

        // Back before the credits: the card is offered again when they come round.
        h.controller.seekTo(upNextAt - 60_000)
        h.advance(59_750)
        assertNull(h.state.upNext)
        h.advance(250)
        assertEquals(10_000L, h.state.upNext!!.countdownMs)

        // Watch credits again, to the end: there the card comes back and counts down.
        h.controller.watchCredits()
        h.advance(30_000)
        assertNull(h.state.upNext)
        h.player.end()
        h.run()
        assertEquals(10_000L, h.state.upNext!!.countdownMs)
        h.advance(9_750)
        assertTrue(h.events.isEmpty())
        h.advance(250)
        assertEquals(listOf<PlayerEvent>(PlayerEvent.StartedNext(2)), h.events)
    }

    @Test
    fun theEndWithoutANextEpisodeLeaves() {
        h.start(PlayRequest(1, 49 * MIN, "Lighthouse"), decision())
        h.advance(30_000)
        h.player.end()
        h.run()
        assertEquals(listOf<PlayerEvent>(PlayerEvent.Exit), h.events)
        assertEquals(PlayState.Stopped, h.states.last())
        assertEquals(50 * MIN, h.gateway.reports.last().positionMs)
        assertEquals(listOf(1L), h.watchNext.removals)
    }

    @Test
    fun reachingWatchedAtCountsAsWatched() {
        h.start(PlayRequest(1, 46 * MIN - 5_000, "Lighthouse"), decision(watchedAtMs = 46 * MIN))
        h.advance(4_750)
        assertTrue(h.watchNext.removals.isEmpty())
        assertEquals(1, h.gateway.reports.size)
        // Crossing it reports at once (not a 10 s beat) and takes it off Watch Next.
        h.advance(250)
        assertEquals(listOf(1L), h.watchNext.removals)
        assertEquals(2, h.gateway.reports.size)
        assertEquals(46 * MIN, h.gateway.reports.last().positionMs)
        // A watched item is not put back on the row by a pause or a stop.
        h.controller.pause()
        h.controller.stop()
        h.run()
        assertTrue(h.watchNext.updates.isEmpty())
    }

    @Test
    fun theServerSayingCompletedCountsAsWatched() {
        h.gateway.reply = { media.watchmy.client.model.ProgressReply(completed = it.positionMs >= 10_000, stopMessage = null, newPlayId = null) }
        h.start(PlayRequest(1, 0, "Lighthouse"), decision())
        h.advance(10_000)
        assertEquals(listOf(1L), h.watchNext.removals)
    }

    @Test
    fun stillWatchingAfterNinetyMinutesWithoutInput() {
        h.release()
        h = Harness()
        val long: (DecisionRequest) -> PlaybackDecision = { r ->
            if (r.fileId == 2L) decision(playId = "b1") else decision(durationMs = 100 * MIN, watchedAtMs = 92 * MIN, upNextMs = 90 * MIN, next = NEXT)
        }
        h.start(PlayRequest(1, 0, "Harbor Lights"), long)
        h.advance(90 * MIN)
        assertEquals(10_000L, h.state.upNext!!.countdownMs)
        assertFalse(h.state.askStillWatching)
        h.advance(10_000)
        // Ask instead of moving on.
        assertTrue(h.state.askStillWatching)
        assertNull(h.state.upNext)
        assertTrue(h.events.isEmpty())
        h.advance(60_000)
        assertTrue(h.events.isEmpty())

        h.controller.answerStillWatching(keepWatching = true)
        h.run()
        assertEquals(listOf<PlayerEvent>(PlayerEvent.StartedNext(2)), h.events)
        assertFalse(h.state.askStillWatching)
    }

    @Test
    fun notStillWatchingLeaves() {
        h.release()
        h = Harness()
        h.start(PlayRequest(1, 0, "Harbor Lights"), decision(durationMs = 100 * MIN, watchedAtMs = 92 * MIN, upNextMs = 90 * MIN, next = NEXT))
        h.advance(90 * MIN + 10_000)
        assertTrue(h.state.askStillWatching)
        h.controller.answerStillWatching(keepWatching = false)
        h.run()
        assertEquals(listOf<PlayerEvent>(PlayerEvent.Exit), h.events)
        val last = h.gateway.reports.last()
        assertEquals(PlayState.Stopped, last.state)
        assertEquals(90 * MIN + 10_000, last.positionMs)
    }

    @Test
    fun anAnswerWithoutAQuestionIsIgnored() {
        play()
        h.controller.answerStillWatching(keepWatching = false)
        h.controller.answerStillWatching(keepWatching = true)
        h.run()
        assertTrue(h.events.isEmpty())
        assertEquals(PlayerStatus.Playing, h.state.status)
    }

    @Test
    fun inputInTheMeantimeMeansNoQuestion() {
        h.release()
        h = Harness()
        val long: (DecisionRequest) -> PlaybackDecision = { r ->
            if (r.fileId == 2L) decision(playId = "b1") else decision(durationMs = 100 * MIN, watchedAtMs = 92 * MIN, upNextMs = 90 * MIN, next = NEXT)
        }
        h.start(PlayRequest(1, 0, "Harbor Lights"), long)
        h.advance(45 * MIN)
        h.controller.noteUserInput()
        h.advance(45 * MIN + 10_000)
        assertFalse(h.state.askStillWatching)
        assertEquals(listOf<PlayerEvent>(PlayerEvent.StartedNext(2)), h.events)
    }
}
