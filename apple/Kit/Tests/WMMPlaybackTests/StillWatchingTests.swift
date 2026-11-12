import Foundation
import Testing
@testable import WMMPlayback

/// android/core/playback/.../StillWatchingTest.kt.
@MainActor
@Suite struct StillWatchingTests {
    let clock = TestClock()
    let tracker: StillWatching

    init() {
        tracker = StillWatching(clock: clock)
    }

    private func at(_ ms: Int) async {
        await clock.advance(by: ms - clock.elapsedMs) {}
    }

    @Test func neverBeforeAPlay() async {
        await at(200 * minute)
        #expect(!tracker.shouldAsk())
    }

    @Test func ninetyMinutesWithoutInput() async {
        tracker.notePlay()
        await at(89 * minute)
        #expect(!tracker.shouldAsk())
        await at(90 * minute)
        #expect(tracker.shouldAsk())
        tracker.noteInput()
        #expect(!tracker.shouldAsk())
    }

    @Test func threeEpisodesWithoutInputOnceTheSessionIsLong() async {
        tracker.notePlay()
        await at(30 * minute)
        tracker.noteInput()
        for _ in 0..<3 { tracker.noteAutoAdvance() }
        // Three in a row, but the session is not 90 min old yet.
        await at(80 * minute)
        #expect(!tracker.shouldAsk())
        await at(90 * minute)
        #expect(tracker.shouldAsk())
    }

    @Test func inputResetsTheEpisodeCount() async {
        tracker.notePlay()
        await at(100 * minute)
        tracker.noteInput()
        for _ in 0..<2 { tracker.noteAutoAdvance() }
        #expect(!tracker.shouldAsk())
        tracker.noteAutoAdvance()
        #expect(tracker.shouldAsk())
        tracker.noteInput()
        #expect(!tracker.shouldAsk())
    }

    @Test func theSessionStartsWithTheFirstPlay() async {
        await at(500 * minute)
        tracker.notePlay()
        await at(550 * minute)
        tracker.notePlay()
        await at(589 * minute)
        #expect(!tracker.shouldAsk())
        await at(590 * minute)
        #expect(tracker.shouldAsk())
    }
}
