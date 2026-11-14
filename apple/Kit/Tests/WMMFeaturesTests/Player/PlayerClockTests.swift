import Foundation
import Observation
import Testing
@testable import WMMFeatures
import WMMModel

/// What ticks stays out of the screen's state (PLAN-APPLE-REVIEW.md §4.3.3):
/// a position tick changes ``PlayerClock`` and not ``PlayerChromeModel/state``,
/// so the iPhone's player screen is not rebuilt ten times a second. Android
/// guards the same thing with recomposition tests.
@MainActor
@Suite struct PlayerClockTests {
    /// Fires when anything `read` reads changes.
    private final class Changed: @unchecked Sendable {
        var fired = false
    }

    private func watch(_ read: @escaping @MainActor () -> Void) -> Changed {
        let changed = Changed()
        withObservationTracking { read() } onChange: { changed.fired = true }
        return changed
    }

    private func upNext(countdownMs: Int?) -> UpNextPrompt {
        UpNextPrompt(next: UpNext(fileID: 9102, episodeID: 100038, showID: 100, showTitle: "Harbor Lights", season: 3, episode: 8, title: "Ebb"), countdownMs: countdownMs)
    }

    @Test func aPositionTickChangesTheClockAndNotTheScreensState() async {
        let h = PlayerHarness()
        await h.time.settle()
        let steady = watch { _ = h.model.state; _ = h.model.overlay; _ = h.model.showsControls; _ = h.model.isWaiting }
        let position = watch { _ = h.model.clock.positionMs }
        let cues = watch { _ = h.model.clock.cues }
        h.spy.state.positionMs += 100
        h.spy.state.bufferedMs = 30_000
        await h.time.settle()
        #expect(h.model.clock.positionMs == 50_100)
        #expect(h.model.clock.bufferedMs == 30_000)
        #expect(h.model.shownPositionMs == 50_100)
        #expect(position.fired)
        #expect(!steady.fired, "a tick must not rebuild the player screen")
        #expect(!cues.fired, "the cues view redraws only when the cues change")
    }

    @Test func theCountdownsAndCuesTickOnTheClock() async {
        let h = PlayerHarness()
        h.spy.state.upNext = upNext(countdownMs: 9_000)
        h.spy.state.skip = SkipPrompt(segment: Segment(kind: .credits, startMs: 2_970_000, endMs: nil), label: "Skip Credits", autoProgress: 0.1)
        await h.time.settle()
        #expect(h.model.state.upNext?.next.fileID == 9102)
        #expect(h.model.state.skip?.label == "Skip Credits")
        let steady = watch { _ = h.model.state }
        h.spy.state.upNext = upNext(countdownMs: 8_900)
        h.spy.state.skip?.autoProgress = 0.2
        h.spy.state.cues = [SubtitleCue(startMs: 50_000, endMs: 52_000, text: "Hello")]
        await h.time.settle()
        #expect(h.model.clock.upNextCountdownMs == 8_900)
        #expect(h.model.clock.skipProgress == 0.2)
        #expect(h.model.clock.cues.map(\.text) == ["Hello"])
        #expect(!steady.fired)
    }

    @Test func aGrowingRecordingsLengthTicksOnTheClock() async {
        let h = PlayerHarness()
        h.spy.state.growing = GrowingRecording(startedAt: Date(timeIntervalSince1970: 1_800_000_000), endsAt: Date(timeIntervalSince1970: 1_800_003_600))
        h.spy.state.durationMs = 20 * 60_000
        await h.time.settle()
        let steady = watch { _ = h.model.state }
        h.spy.state.durationMs += 100
        await h.time.settle()
        #expect(h.model.clock.durationMs == 20 * 60_000 + 100)
        #expect(!steady.fired)
        #expect(h.model.state.timelineDurationMs == 60 * 60_000, "the scrubber's bands still have the planned length")
    }

    @Test func anythingElseChangesTheScreensState() async {
        let h = PlayerHarness()
        await h.time.settle()
        let steady = watch { _ = h.model.state }
        h.spy.state.selectedSubtitle = "s4"
        await h.time.settle()
        #expect(steady.fired)
        #expect(h.model.state.selectedSubtitle == "s4")
        // Autoplay turning off is news: the card's button changes.
        h.spy.state.upNext = upNext(countdownMs: 9_000)
        await h.time.settle()
        h.spy.state.upNext = upNext(countdownMs: nil)
        await h.time.settle()
        #expect(h.model.state.upNext?.countdownMs == nil)
    }
}
