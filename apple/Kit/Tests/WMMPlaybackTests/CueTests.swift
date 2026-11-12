import Foundation
import Synchronization
import Testing
@testable import WMMPlayback
import WMMModel

/// Subtitles drawn by the app (PLAN-IOS.md §5): the selected text track's
/// WebVTT, its cues at the file position.
@MainActor
@Suite struct CueTests {
    let h = Harness()
    let vtt = """
    WEBVTT

    00:00:05.000 --> 00:00:08.000
    <i>First</i>

    00:00:07.000 --> 00:00:09.000
    Second
    """

    @Test func theSelectedTracksCuesFollowThePosition() async {
        h.gateway.subtitleFiles = ["/api/files/1/subtitles/s3.vtt": vtt]
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse", subtitleID: "s3"), decision(subtitles: [textSub("s3"), textSub("s4")]))
        #expect(h.state.selectedSubtitle == "s3")
        #expect(h.gateway.subtitleFetches.count == 1)
        await h.advance(5_000)
        #expect(h.state.cues == [SubtitleCue(startMs: 5_000, endMs: 8_000, text: "First")])
        await h.advance(2_000)
        #expect(h.state.cues.map(\.text) == ["First", "Second"])
        await h.advance(1_500)
        #expect(h.state.cues.map(\.text) == ["Second"])
        await h.advance(1_000)
        #expect(h.state.cues.isEmpty)
        // Seeking back shows them again, from the same fetch.
        h.controller.seek(toMs: 7_500)
        await h.run()
        #expect(h.state.cues.map(\.text) == ["First", "Second"])
        #expect(h.gateway.subtitleFetches.count == 1)
    }

    @Test func theTimeObserverUpdatesThemBetweenTicksOnlyWhenTheyChange() async {
        h.gateway.subtitleFiles = ["/api/files/1/subtitles/s3.vtt": vtt]
        await h.start(PlayRequest(fileID: 1, startMs: 4_900, title: "Lighthouse", subtitleID: "s3"), decision(subtitles: [textSub("s3")]))
        #expect(h.state.cues.isEmpty)
        await h.clock.advance(by: 100) {}
        let assigned = Mutex(0)
        withObservationTracking { _ = h.controller.state } onChange: { assigned.withLock { $0 += 1 } }
        h.player.tick()
        #expect(h.state.cues.map(\.text) == ["First"])
        #expect(assigned.withLock { $0 } == 1)
        // The same cues again: state is not assigned.
        withObservationTracking { _ = h.controller.state } onChange: { assigned.withLock { $0 += 1 } }
        h.player.tick()
        #expect(assigned.withLock { $0 } == 1)
    }

    @Test func anEventPlaylistsCuesAreShiftedByTheServerAndShownInFileTime() async {
        // The server moved the cues to the session's start (?shift=), so they are matched in player time.
        h.gateway.subtitleFiles = ["/api/files/1/subtitles/s3.vtt": vtt]
        let shifted = textSub("s3", url: "http://srv/api/files/1/subtitles/s3.vtt?shift=600000")
        await h.start(PlayRequest(fileID: 1, startMs: 600_000, title: "Lighthouse", subtitleID: "s3"), decision(vod: false, startMs: 600_000, subtitles: [shifted]))
        #expect(h.gateway.subtitleFetches == [url("http://srv/api/files/1/subtitles/s3.vtt?shift=600000&access_token=tok1")])
        await h.advance(5_000)
        #expect(h.state.positionMs == 605_000)
        #expect(h.state.cues == [SubtitleCue(startMs: 605_000, endMs: 608_000, text: "First")])
    }

    @Test func nothingIsFetchedForImageOrBurnedInSubtitlesOrNone() async {
        h.gateway.subtitleFiles = ["/api/files/1/subtitles/s3.vtt": vtt]
        // As the server does: an image subtitle asked for is burned in.
        await h.start(PlayRequest(fileID: 1, startMs: 5_000, title: "Lighthouse", subtitleID: "s4"), answer: { r in
            decision(subtitles: [textSub("s3"), textSub("s4", "image")], burnInSubtitleID: r.subtitleID == "s4" || r.burnIn ? r.subtitleID : nil)
        })
        #expect(h.gateway.decisions.first?.subtitleID == "s4")
        #expect(h.state.selectedSubtitle == "s4")
        await h.advance(1_000)
        #expect(h.gateway.subtitleFetches.isEmpty)
        #expect(h.state.cues.isEmpty)
        // Off: nothing either.
        h.controller.selectSubtitle(nil)
        await h.advance(1_000)
        #expect(h.gateway.subtitleFetches.isEmpty)
        #expect(h.state.selectedSubtitle == nil)
    }

    @Test func aTrackThatWillNotLoadIsNotAskedForEveryTick() async {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse", subtitleID: "s3"), decision(subtitles: [textSub("s3")]))
        await h.advance(5_000)
        #expect(h.gateway.subtitleFetches.count == 1)
        #expect(h.state.cues.isEmpty)
        #expect(h.state.error == nil)
    }
}
