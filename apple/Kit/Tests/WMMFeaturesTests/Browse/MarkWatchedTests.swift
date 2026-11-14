import Foundation
import Testing
import WMMFakes
import WMMModel
@testable import WMMFeatures

/// Mark watched, the one way every screen does it (PLAN-APPLE-REVIEW.md §5.5).
@Suite @MainActor
struct MarkWatchedTests {
    init() { FakeLatency.duration = .zero }

    @Test func aMarkTellsTheServerDrawsItAndSaysSo() async {
        let library = TestLibrary()
        var drawn = 0
        let note = await markWatched(.movie(id: 500), true, in: library, drawn: { drawn += 1 })
        #expect(library.watchCalls == ["movie 500 true"])
        #expect(drawn == 1)
        #expect(note == "Marked as watched")
        #expect(await markWatched(.show(id: 100), false, in: library) == "Marked as unwatched")
        #expect(library.homeCalls == 0, "nothing loads again here: the mark's notice does that")
    }

    @Test func aRefusedMarkSaysWhyAndDrawsNothing() async {
        let library = TestLibrary()
        library.failure = APIError(status: 500, message: "The server is busy.")
        var drawn = 0, offline = 0
        let note = await markWatched(.movie(id: 500), true, in: library, drawn: { drawn += 1 }, offline: { offline += 1; return true })
        #expect(note == "The server is busy.")
        #expect(drawn == 0)
        #expect(offline == 0, "a server that answered isn't away")
    }

    @Test func withNoServerADownloadKeepsTheMarkWhenItCan() async {
        let library = TestLibrary()
        library.failure = APIError(status: nil, message: "Couldn't reach the server.")
        var offline = 0
        #expect(await markWatched(.episode(id: 7), true, in: library, offline: { offline += 1; return true }) == "Marked as watched")
        #expect(offline == 1)
        #expect(await markWatched(.episode(id: 7), true, in: library, offline: { false }) == "Couldn't reach the server.",
                "no download to keep it: the reason")
        #expect(await markWatched(.episode(id: 7), true, in: library) == "Couldn't reach the server.")
    }
}
