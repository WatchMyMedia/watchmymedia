import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// android/core/playback/.../SkipTest.kt.
@MainActor
@Suite struct SkipTests {
    let intro = Segment(kind: .intro, startMs: 60_000, endMs: 105_000)

    /// Plays from 55 s, 5 s before the intro, with the intro's skip mode set to `mode`.
    private func play(_ mode: SkipMode, segments: [Segment]? = nil, from: Int = 55_000) async -> Harness {
        var skip = PlaybackPreferences.defaultSkip
        skip[.intro] = mode
        let h = Harness(preferences: PlaybackPreferences(skip: skip))
        await h.start(PlayRequest(fileID: 1, startMs: from, title: "Harbor Lights"), decision(segments: segments ?? [intro]))
        return h
    }

    @Test func noneShowsNothing() async {
        let h = await play(.none)
        await h.advance(10_000)
        #expect(h.state.skip == nil)
        #expect(h.state.segments == [intro])
        #expect(h.player.seeks.isEmpty)
    }

    @Test func askShowsAButton() async throws {
        let h = await play(.ask)
        await h.advance(4_750)
        #expect(h.state.skip == nil)
        await h.advance(250)
        let skip = try #require(h.state.skip)
        #expect(skip.segment == intro)
        #expect(skip.label == "Skip Intro")
        #expect(skip.autoProgress == nil)
        await h.advance(20_000)
        #expect(h.player.seeks.isEmpty)
        h.controller.skip()
        await h.run()
        #expect(h.player.seeks == [105_000])
        #expect(h.state.skip == nil)
        #expect(h.events.isEmpty)
    }

    @Test func autoSkipsAtOnce() async {
        let h = await play(.auto)
        await h.advance(5_000)
        #expect(h.player.seeks == [105_000])
        #expect(h.state.skip == nil)
    }

    @Test func autoDelayFillsForThreeSecondsOfPlayThenSkips() async throws {
        let h = await play(.autoDelay)
        await h.advance(5_000)
        #expect(h.state.skip?.autoProgress == 0)
        await h.advance(1_500)
        #expect(h.state.skip?.autoProgress == 0.5)
        // The fill holds while paused.
        h.controller.pause()
        await h.advance(10_000)
        #expect(h.state.skip?.autoProgress == 0.5)
        #expect(h.player.seeks.isEmpty)
        h.controller.play()
        await h.advance(1_250)
        let progress = try #require(h.state.skip?.autoProgress)
        #expect(abs(progress - 2_750.0 / 3_000.0) < 0.001)
        #expect(h.player.seeks.isEmpty)
        await h.advance(250)
        #expect(h.player.seeks == [105_000])
        #expect(h.state.skip == nil)
    }

    @Test func aSegmentSeekedIntoIsNeverSkippedByItself() async throws {
        var h = await play(.auto, from: 10_000)
        h.controller.seek(toMs: 70_000)
        await h.run()
        let skip = try #require(h.state.skip)
        #expect(skip.autoProgress == nil)
        await h.advance(20_000)
        #expect(h.player.seeks == [70_000])
        await h.release()

        h = await play(.autoDelay, from: 10_000)
        h.controller.seek(toMs: 70_000)
        await h.advance(10_000)
        #expect(h.state.skip != nil)
        #expect(h.state.skip?.autoProgress == nil)
        #expect(h.player.seeks == [70_000])
        // The button still works.
        h.controller.skip()
        await h.run()
        #expect(h.player.seeks == [70_000, 105_000])
    }

    @Test func aSegmentTheTransportBarSeekedIntoIsNeverSkippedByItself() async throws {
        let h = await play(.auto, from: 10_000)
        // AVKit asks where to seek, then moves the AVPlayer itself.
        h.controller.noteSeek(playerMs: 70_000)
        h.player.jump(toMs: 70_000)
        await h.run()
        let skip = try #require(h.state.skip)
        #expect(skip.autoProgress == nil)
        await h.advance(20_000)
        #expect(h.player.seeks.isEmpty)
        h.controller.skip()
        await h.run()
        #expect(h.player.seeks == [105_000])
    }

    @Test func aSegmentUnderThreeSecondsGetsNoButton() async {
        let h = await play(.ask, segments: [Segment(kind: .intro, startMs: 60_000, endMs: 62_000)])
        await h.advance(6_000)
        #expect(h.state.skip == nil)
    }

    @Test func aSkipToTheEndMarksWatchedAndLeaves() async {
        let h = Harness()
        let credits = Segment(kind: .credits, startMs: 47 * minute, endMs: nil)
        // A film: no next episode, so the final credits get a Skip button.
        await h.start(PlayRequest(fileID: 1, startMs: 47 * minute + 10_000, title: "Lighthouse"), decision(segments: [credits], creditsMs: 47 * minute, watchedAtMs: 48 * minute))
        #expect(h.state.skip?.label == "Skip Credits")
        h.controller.skip()
        await h.run()
        #expect(h.events == [.exit])
        let last = h.gateway.reports.last
        #expect(last?.state == .stopped)
        // Reported at the end, which the server counts as watched.
        #expect(last?.positionMs == 50 * minute)
        // The screen's own stop afterwards reports nothing more.
        let sent = h.gateway.reports.count
        h.controller.stop()
        await h.run()
        #expect(h.gateway.reports.count == sent)
    }
}
