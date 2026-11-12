import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// android/core/playback/.../NextEpisodeTest.kt.
@MainActor
@Suite struct NextEpisodeTests {
    let upNextAt = 48 * minute

    /// An episode whose final credits (and the Next Episode card) start at 48 min; the next one is file 2.
    private func episode(_ request: DecisionRequest) -> PlaybackDecision {
        if request.fileID == 2 { return decision(playID: "b1", sessionID: "s2") }
        return decision(
            segments: [Segment(kind: .intro, startMs: 60_000, endMs: 105_000), Segment(kind: .credits, startMs: upNextAt, endMs: nil)],
            creditsMs: upNextAt,
            watchedAtMs: upNextAt,
            upNextMs: upNextAt,
            next: nextEpisode
        )
    }

    private func play(from: Int? = nil, preferences: PlaybackPreferences = PlaybackPreferences()) async -> Harness {
        let h = Harness(preferences: preferences)
        await h.start(PlayRequest(fileID: 1, startMs: from ?? upNextAt - 5_000, title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark", backdropPath: "/img/b.jpg"), answer: episode)
        return h
    }

    /// A long episode whose card comes at 90 min, for Still watching.
    private func long(_ r: DecisionRequest) -> PlaybackDecision {
        r.fileID == 2 ? decision(playID: "b1") : decision(durationMs: 100 * minute, watchedAtMs: 92 * minute, upNextMs: 90 * minute, next: nextEpisode)
    }

    @Test func countdownHoldsWhilePausedThenStartsTheNextEpisode() async {
        let h = await play()
        await h.advance(4_750)
        #expect(h.state.upNext == nil)
        await h.advance(250)
        #expect(h.state.upNext?.next == nextEpisode)
        #expect(h.state.upNext?.countdownMs == 10_000)
        // The final credits have the card instead of a Skip button.
        #expect(h.state.skip == nil)
        await h.advance(4_000)
        #expect(h.state.upNext?.countdownMs == 6_000)

        h.controller.pause()
        await h.advance(20_000)
        #expect(h.state.upNext?.countdownMs == 6_000)
        #expect(h.events.isEmpty)

        h.controller.play()
        await h.advance(5_750)
        #expect(h.state.upNext?.countdownMs == 250)
        #expect(h.events.isEmpty)
        await h.advance(250)
        #expect(h.events == [.startedNext(fileID: 2)])

        // This episode finished: reported at its end, its session ended.
        let last = h.gateway.reports.last { $0.fileID == 1 }
        #expect(last?.state == .stopped)
        #expect(last?.positionMs == 50 * minute)
        #expect(h.gateway.stoppedSessions.contains("s1"))

        // The next is a new play, with a new token, in place.
        let next = h.gateway.decisions.last
        #expect(next?.fileID == 2)
        #expect(next?.playID == nil)
        #expect(next?.startMs == nil)
        #expect(h.gateway.tokens == 2)
        #expect(h.state.title == "Harbor Lights")
        #expect(h.state.subtitle == "S03 E08 · Squall Line")
        #expect(h.state.backdropPath == "/img/b.jpg")
        #expect(h.state.status == .playing)
        #expect(h.gateway.reports.last?.playID == "b1")
    }

    @Test func autoplayOffWaitsForAChoice() async {
        let h = await play(preferences: PlaybackPreferences(autoplayNext: false))
        await h.advance(5_000)
        #expect(h.state.upNext != nil)
        #expect(h.state.upNext?.countdownMs == nil)
        await h.advance(60_000)
        #expect(h.events.isEmpty)
        // At the end it stays: no exit while a next episode is on offer.
        h.player.end()
        await h.advance(30_000)
        #expect(h.state.status == .ended)
        #expect(h.state.upNext != nil)
        #expect(h.events.isEmpty)
        h.controller.playNext()
        await h.run()
        #expect(h.events == [.startedNext(fileID: 2)])
    }

    @Test func watchCreditsHidesTheCardUntilTheEndOrASeekBack() async {
        let h = await play()
        await h.advance(5_000)
        #expect(h.state.upNext != nil)
        h.controller.watchCredits()
        await h.run()
        #expect(h.state.upNext == nil)
        await h.advance(30_000)
        #expect(h.state.upNext == nil)

        // Back before the credits: the card is offered again when they come round.
        h.controller.seek(toMs: upNextAt - 60_000)
        await h.advance(59_750)
        #expect(h.state.upNext == nil)
        await h.advance(250)
        #expect(h.state.upNext?.countdownMs == 10_000)

        // Watch credits again, to the end: there the card comes back and counts down.
        h.controller.watchCredits()
        await h.advance(30_000)
        #expect(h.state.upNext == nil)
        h.player.end()
        await h.run()
        #expect(h.state.upNext?.countdownMs == 10_000)
        await h.advance(9_750)
        #expect(h.events.isEmpty)
        await h.advance(250)
        #expect(h.events == [.startedNext(fileID: 2)])
    }

    @Test func aTransportBarSeekBackBeforeTheCreditsOffersTheCardAgain() async {
        let h = await play()
        await h.advance(5_000)
        h.controller.watchCredits()
        await h.advance(1_000)
        #expect(h.state.upNext == nil)
        h.controller.noteSeek(playerMs: upNextAt - 10_000)
        h.player.jump(toMs: upNextAt - 10_000)
        await h.advance(9_750)
        #expect(h.state.upNext == nil)
        await h.advance(250)
        #expect(h.state.upNext?.countdownMs == 10_000)
        #expect(h.player.seeks.isEmpty)
    }

    @Test func theEndWithoutANextEpisodeLeaves() async {
        let h = Harness()
        await h.start(PlayRequest(fileID: 1, startMs: 49 * minute, title: "Lighthouse"), decision())
        await h.advance(30_000)
        h.player.end()
        await h.run()
        #expect(h.events == [.exit])
        #expect(h.states.last == .stopped)
        #expect(h.gateway.reports.last?.positionMs == 50 * minute)
    }

    @Test func reachingWatchedAtReportsAtOnce() async {
        let h = Harness()
        await h.start(PlayRequest(fileID: 1, startMs: 46 * minute - 5_000, title: "Lighthouse"), decision(watchedAtMs: 46 * minute))
        await h.advance(4_750)
        #expect(h.gateway.reports.count == 1)
        // Crossing it reports at once (not a 10 s beat), so the server marks it watched.
        await h.advance(250)
        #expect(h.gateway.reports.count == 2)
        #expect(h.gateway.reports.last?.positionMs == 46 * minute)
        await h.advance(250)
        #expect(h.gateway.reports.count == 2)
    }

    @Test func theServerSayingCompletedCountsAsWatched() async {
        // Watched, playing the next episode reports this one at its end.
        let h = Harness()
        h.gateway.reply = { ProgressReply(completed: $0.positionMs >= 10_000) }
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Harbor Lights"), decision(upNextMs: 48 * minute, next: nextEpisode))
        await h.advance(10_000)
        h.gateway.answer = { _ in decision(playID: "b1", sessionID: "s2") }
        h.controller.playNext()
        await h.run()
        let left = h.gateway.reports.last { $0.fileID == 1 }
        #expect(left?.state == .stopped)
        #expect(left?.positionMs == 50 * minute)
    }

    @Test func playNextBeforeTheCardLeavesThisEpisodeWhereItWas() async {
        let h = Harness()
        await h.start(decision(upNextMs: 48 * minute, next: nextEpisode))
        await h.advance(60_000)
        h.gateway.answer = { _ in decision(playID: "b1", sessionID: "s2") }
        h.controller.playNext()
        await h.run()
        #expect(h.events == [.startedNext(fileID: 2)])
        let left = h.gateway.reports.last { $0.fileID == 1 }
        #expect(left?.state == .stopped)
        #expect(left?.positionMs == 60_000)
    }

    @Test func stillWatchingAfterNinetyMinutesWithoutInput() async {
        let h = Harness()
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Harbor Lights"), answer: long)
        await h.advance(90 * minute)
        #expect(h.state.upNext?.countdownMs == 10_000)
        #expect(!h.state.askStillWatching)
        await h.advance(10_000)
        // Ask instead of moving on.
        #expect(h.state.askStillWatching)
        #expect(h.state.upNext == nil)
        #expect(h.events.isEmpty)
        await h.advance(60_000)
        #expect(h.events.isEmpty)

        h.controller.answerStillWatching(keepWatching: true)
        await h.run()
        #expect(h.events == [.startedNext(fileID: 2)])
        #expect(!h.state.askStillWatching)
    }

    @Test func notStillWatchingLeaves() async {
        let h = Harness()
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Harbor Lights"), decision(durationMs: 100 * minute, watchedAtMs: 92 * minute, upNextMs: 90 * minute, next: nextEpisode))
        await h.advance(90 * minute + 10_000)
        #expect(h.state.askStillWatching)
        h.controller.answerStillWatching(keepWatching: false)
        await h.run()
        #expect(h.events == [.exit])
        let last = h.gateway.reports.last
        #expect(last?.state == .stopped)
        #expect(last?.positionMs == 90 * minute + 10_000)
    }

    @Test func anAnswerWithoutAQuestionIsIgnored() async {
        let h = await play()
        h.controller.answerStillWatching(keepWatching: false)
        h.controller.answerStillWatching(keepWatching: true)
        await h.run()
        #expect(h.events.isEmpty)
        #expect(h.state.status == .playing)
    }

    @Test func inputInTheMeantimeMeansNoQuestion() async {
        let h = Harness()
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Harbor Lights"), answer: long)
        await h.advance(45 * minute)
        h.controller.noteUserInput()
        await h.advance(45 * minute + 10_000)
        #expect(!h.state.askStillWatching)
        #expect(h.events == [.startedNext(fileID: 2)])
    }
}
