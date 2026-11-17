import Testing
import WMMModel
@testable import WMMFeatures

/// PLAN-IPHONE-DUO.md §5: Live TV half open on a table, or like a book.
@Suite struct LiveFoldControlsTests {
    @Test func theMiddleButtonIsGoLiveOnlyBehindLive() {
        #expect(LiveFoldMiddle.of(status: .playing, atLiveEdge: false) == .goLive)
        #expect(LiveFoldMiddle.of(status: .buffering, atLiveEdge: false) == .goLive)
        #expect(LiveFoldMiddle.of(status: .playing, atLiveEdge: true) == .pause)
        #expect(LiveFoldMiddle.of(status: .tuning, atLiveEdge: true) == .pause)
        // Paused is behind live too, but Play goes on from there.
        #expect(LiveFoldMiddle.of(status: .paused, atLiveEdge: false) == .play)
        #expect(LiveFoldMiddle.of(status: .paused, atLiveEdge: true) == .play)
        #expect(LiveFoldMiddle.goLive.title == "Go live")
    }

    @Test func stoppedItTunesAgain() {
        #expect(LiveFoldMiddle.of(status: .stopped, atLiveEdge: true) == .watchLive)
        #expect(LiveFoldMiddle.of(status: .error, atLiveEdge: true) == .tryAgain)
        #expect(LiveFoldMiddle.watchLive.title == "Watch live")
        #expect(LiveFoldMiddle.tryAgain.title == "Try again")
    }

    @Test func favouritesShowsOnceThereIsOne() {
        #expect(LiveFoldList.tabs(hasFavourite: false) == [.onNow])
        #expect(LiveFoldList.tabs(hasFavourite: true) == [.onNow, .favourites])
        #expect(LiveFoldList.shown(.favourites, hasFavourite: true) == .favourites)
        #expect(LiveFoldList.shown(.favourites, hasFavourite: false) == .onNow)
    }

    @Test func theKickerNamesTheChannel() {
        let channel = LiveChannel(id: "live-4", number: "4.1", name: "NFLD-HD")
        #expect(liveFoldKicker(channel) == "4.1 NFLD-HD · On now")
    }
}
