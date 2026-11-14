import Foundation
import Testing
@testable import WMMFeatures
import WMMModel

/// android/mobile/src/test/.../ScrubberModelTest.kt, and the preview while dragging.
@Suite struct ScrubberGeometryTests {
    let duration = 52 * 60_000

    @Test func introAndCreditsBecomeBandsAndCreditsWithoutAnEndRunToTheEnd() throws {
        let bands = scrubberBands([Segment(kind: .intro, startMs: 60_000, endMs: 105_000), Segment(kind: .credits, startMs: 49 * 60_000 + 30_000, endMs: nil, final: true)], durationMs: duration)
        #expect(bands.count == 2)
        let intro = try #require(bands.first)
        #expect(intro.kind == .intro)
        #expect(intro.style == .opening)
        #expect(abs(intro.start - 60_000.0 / Double(duration)) < 1e-9)
        #expect(abs(intro.end - 105_000.0 / Double(duration)) < 1e-9)
        let credits = bands[1]
        #expect(credits.style == .closing)
        #expect(credits.end == 1)
    }

    @Test func recapIsDrawnLikeTheIntroPreviewsLikeTheCredits() {
        let bands = scrubberBands([Segment(kind: .recap, startMs: 0, endMs: 30_000), Segment(kind: .preview, startMs: 3_000_000, endMs: 3_100_000)], durationMs: duration)
        #expect(bands.map(\.style) == [.opening, .closing])
    }

    @Test func segmentsOutsideTheFileOrEmptyAreDroppedAndEndsAreClamped() {
        let bands = scrubberBands(
            [
                Segment(kind: .intro, startMs: 10_000, endMs: 10_000),
                Segment(kind: .credits, startMs: duration + 5_000, endMs: nil),
                Segment(kind: .commercial, startMs: duration - 1_000, endMs: duration + 60_000),
            ],
            durationMs: duration
        )
        #expect(bands.count == 1)
        #expect(bands.first?.kind == .commercial)
        #expect(bands.first?.end == 1)
    }

    @Test func noBandsBeforeTheDurationIsKnown() {
        #expect(scrubberBands([Segment(kind: .intro, startMs: 0, endMs: 30_000)], durationMs: 0).isEmpty)
        #expect(fraction(ofMs: 30_000, durationMs: 0) == 0)
    }

    @Test func bandsReadTheirRangeToVoiceOver() {
        let bands = scrubberBands([Segment(kind: .intro, startMs: 60_000, endMs: 105_000), Segment(kind: .credits, startMs: 49 * 60_000 + 30_000, endMs: nil)], durationMs: duration)
        #expect(bands.map { $0.accessibilityLabel(durationMs: duration) } == ["Intro from 1:00 to 1:45", "Credits from 49:30"])
    }

    @Test func bufferReachesFromThePositionAndStopsAtTheEnd() {
        #expect(abs(bufferedFraction(positionMs: 20_000, bufferedMs: 30_000, durationMs: 100_000) - 0.5) < 1e-9)
        #expect(bufferedFraction(positionMs: 90_000, bufferedMs: 30_000, durationMs: 100_000) == 1)
        #expect(abs(bufferedFraction(positionMs: 20_000, bufferedMs: -5_000, durationMs: 100_000) - 0.2) < 1e-9)
    }

    @Test func aTouchOnTheTrackMapsToAPositionClampedToTheFile() {
        #expect(position(atX: 100, width: 400, durationMs: 100_000) == 25_000)
        #expect(position(atX: -20, width: 400, durationMs: 100_000) == 0)
        #expect(position(atX: 500, width: 400, durationMs: 100_000) == 100_000)
        #expect(position(atX: 100, width: 0, durationMs: 100_000) == 0)
        #expect(position(atX: 100, width: 400, durationMs: 0) == 0)
    }

    @Test func timeLeftUsesARealMinusSign() {
        #expect(remainingLabel(positionMs: 48_000, durationMs: 52 * 60_000 + 10_000) == "−51:22")
        #expect(remainingLabel(positionMs: 70_000, durationMs: 60_000) == "−0:00")
    }

    @Test func theScrubberReadsItsPositionToVoiceOver() {
        #expect(scrubberAccessibilityValue(positionMs: 48_000, durationMs: 52 * 60_000 + 10_000) == "0:48 of 52:10")
    }
}

@MainActor
@Suite struct ScrubPreviewTests {
    @Test func draggingPreviewsThePositionUnderTheThumbAndSeeksOnRelease() async {
        let h = PlayerHarness()
        h.model.scrub(x: 100, width: 400)
        #expect(h.model.scrubMs == 13 * 60_000)
        #expect(h.model.shownPositionMs == 13 * 60_000)
        #expect(!h.spy.calls.contains { $0.hasPrefix("seek") })
        h.model.scrub(x: 200, width: 400)
        h.model.endScrub()
        #expect(h.spy.calls.last == "seek \(26 * 60_000)")
        #expect(h.model.scrubMs == nil)
        #expect(h.model.shownPositionMs == 26 * 60_000)
    }

    @Test func thePreviewIsClampedToTheFile() {
        let h = PlayerHarness()
        h.model.scrub(x: -50, width: 400)
        #expect(h.model.scrubMs == 0)
        h.model.scrub(x: 900, width: 400)
        #expect(h.model.scrubMs == 52 * 60_000)
    }

    @Test func theControlsStayWhileDraggingAndHideThreeSecondsAfterRelease() async {
        let h = PlayerHarness()
        await h.time.advance(byMs: 1_000)
        h.model.scrub(x: 100, width: 400)
        #expect(h.model.hideAtMs == nil)
        await h.time.advance(byMs: 10_000)
        #expect(h.model.controlsVisible)
        h.model.endScrub()
        #expect(h.model.hideAtMs == 14_000)
        await h.time.advance(byMs: 3_000)
        #expect(!h.model.controlsVisible)
    }

    @Test func aCancelledDragDoesNotSeek() {
        let h = PlayerHarness()
        h.model.scrub(x: 100, width: 400)
        h.model.cancelScrub()
        #expect(h.model.scrubMs == nil)
        #expect(!h.spy.calls.contains { $0.hasPrefix("seek") })
    }

    @Test func afterASeekTheThumbHoldsTheTargetUntilThePlayerGetsThere() async {
        let h = PlayerHarness()
        h.model.seek(toMs: 600_000)
        // A real player reports the old position for a moment.
        h.spy.state.positionMs = 50_000
        await h.time.settle()
        #expect(h.model.shownPositionMs == 600_000)
        h.spy.state.positionMs = 599_500
        await h.time.settle()
        #expect(h.model.shownPositionMs == 599_500)
        h.spy.state.positionMs = 50_000
        await h.time.advance(byMs: PlayerChromeModel.holdMs)
        #expect(h.model.shownPositionMs == 50_000)
    }
}

extension ScrubPreviewTests {
    @Test func aGrowingScrubberShowsTheWholeTimelineButCannotSeekIntoTheFuture() async {
        let h = PlayerHarness()
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        h.spy.state.growing = .init(startedAt: start, endsAt: start.addingTimeInterval(100))
        h.spy.state.durationMs = 25_000
        await h.time.settle()
        #expect(h.model.state.timelineDurationMs == 100_000)
        h.model.scrub(x: 50, width: 400)
        #expect(h.model.scrubMs == 12_500)
        h.model.scrub(x: 300, width: 400)
        #expect(h.model.scrubMs == 25_000)
        h.model.endScrub()
        #expect(h.spy.calls.last == "seek 25000")
    }
}
