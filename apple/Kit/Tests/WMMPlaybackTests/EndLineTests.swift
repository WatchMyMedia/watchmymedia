import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// Apple TV's title view says when the play would end, after the episode:
/// the controller adds what it is told to the item's subtitle.
@MainActor
@Suite struct EndLineTests {
    @Test func theEndGoesAfterTheSubtitleAndOnlyWhenItChanges() async {
        let h = Harness()
        await h.start(decision())
        h.controller.describeEnd("Ends at 9:41 PM")
        #expect(h.player.subtitles.last == "S03 E07 · The Long Dark · Ends at 9:41 PM")
        let told = h.player.subtitles.count
        h.controller.describeEnd("Ends at 9:41 PM")
        #expect(h.player.subtitles.count == told, "the same line again sends nothing")
        h.controller.describeEnd(nil)
        #expect(h.player.subtitles.last == "S03 E07 · The Long Dark")
    }

    @Test func aNewPlayStartsWithoutTheOldEnd() async {
        let h = Harness()
        await h.start(decision())
        h.controller.describeEnd("Ends at 9:41 PM")
        await h.start(PlayRequest(fileID: 2, title: "Harbor Lights", subtitle: "S03 E08 · Low Water"), decision())
        #expect(h.player.loads.last?.subtitle == "S03 E08 · Low Water")
    }

    @Test func nothingIsAddedUntilTheScreenSays() async {
        let h = Harness()
        await h.start(decision())
        #expect(h.player.loads.last?.subtitle == "S03 E07 · The Long Dark")
    }
}
