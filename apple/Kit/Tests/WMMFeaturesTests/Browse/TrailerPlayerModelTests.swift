import Foundation
import Testing
@testable import WMMFeatures
import WMMModel

@MainActor
@Suite struct TrailerPlayerModelTests {
    private func trailers(_ keys: String...) -> [YouTubeTrailer] {
        keys.enumerated().map { YouTubeTrailer(name: "Trailer \($0.offset + 1)", key: $0.element)! }
    }

    @Test func thereIsNoPlayerWithoutAYouTubeTrailer() {
        #expect(TrailerPlayerModel(movieTitle: "Paper Moons", trailers: []) == nil)
    }

    @Test func aTrailerItsOwnerKeepsOnYouTubeMovesToTheNextThenIsRefused() throws {
        let model = try #require(TrailerPlayerModel(movieTitle: "Paper Moons", trailers: trailers("aaaaaaaaaaa", "bbbbbbbbbbb")))
        #expect(model.trailer.key == "aaaaaaaaaaa")
        model.playerFailed(key: "aaaaaaaaaaa", code: 150)
        #expect(model.state == .playing)
        #expect(model.trailer.key == "bbbbbbbbbbb")

        model.playerFailed(key: "aaaaaaaaaaa", code: 101)
        #expect(model.trailer.key == "bbbbbbbbbbb", "a late error for the one before is ignored")

        model.playerFailed(key: "bbbbbbbbbbb", code: 101)
        #expect(model.state == .refused)
        #expect(model.trailer.name == "Trailer 1", "Open in YouTube offers the one Trailer meant")
        #expect(model.trailer.watchURL.absoluteString == "https://www.youtube.com/watch?v=aaaaaaaaaaa")
    }

    @Test func aMissingVideoMovesOnButTheLastLeavesYouTubesOwnMessage() throws {
        let model = try #require(TrailerPlayerModel(movieTitle: "Paper Moons", trailers: trailers("aaaaaaaaaaa", "bbbbbbbbbbb")))
        model.playerFailed(key: "aaaaaaaaaaa", code: 100)
        #expect(model.trailer.key == "bbbbbbbbbbb")
        model.playerFailed(key: "bbbbbbbbbbb", code: 100)
        #expect(model.state == .playing)
        #expect(model.trailer.key == "bbbbbbbbbbb")
    }

    @Test func otherErrorsLeaveYouTubesOwnMessage() throws {
        let model = try #require(TrailerPlayerModel(movieTitle: "Paper Moons", trailers: trailers("aaaaaaaaaaa", "bbbbbbbbbbb")))
        for code in [2, 5, 152, 153] { model.playerFailed(key: "aaaaaaaaaaa", code: code) }
        #expect(model.state == .playing)
        #expect(model.trailer.key == "aaaaaaaaaaa")
    }

    @Test func theScreenClosesWhenATrailerThatPlayedEnds() throws {
        let model = try #require(TrailerPlayerModel(movieTitle: "Paper Moons", trailers: trailers("aaaaaaaaaaa", "bbbbbbbbbbb")))
        model.playerChanged(key: "aaaaaaaaaaa", state: 0)
        #expect(model.state == .playing, "ended before it played is the player starting up")
        model.playerChanged(key: "aaaaaaaaaaa", state: 1)
        model.playerChanged(key: "bbbbbbbbbbb", state: 0)
        #expect(model.state == .playing, "another trailer's end")
        model.playerChanged(key: "aaaaaaaaaaa", state: 0)
        #expect(model.state == .ended)
    }

    @Test func theNextTrailerHasToPlayBeforeItsEndCounts() throws {
        let model = try #require(TrailerPlayerModel(movieTitle: "Paper Moons", trailers: trailers("aaaaaaaaaaa", "bbbbbbbbbbb")))
        model.playerChanged(key: "aaaaaaaaaaa", state: 1)
        model.playerFailed(key: "aaaaaaaaaaa", code: 150)
        model.playerChanged(key: "bbbbbbbbbbb", state: 0)
        #expect(model.state == .playing)
    }
}
