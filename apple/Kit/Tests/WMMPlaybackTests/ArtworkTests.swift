import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// Now Playing's picture: which image, loaded through the lookups, kept in
/// step with the play through Next Episode and restarts.
@MainActor
@Suite struct ArtworkTests {
    let lookups = RecordingLookups()
    let h: Harness

    init() {
        h = Harness(lookups: lookups)
        lookups.pictures = ["/e7.jpg": Data("e7".utf8), "/e8.jpg": Data("e8".utf8), "/harbor.jpg": Data("backdrop".utf8)]
    }

    private let episode = PlayRequest(
        fileID: 1, startMs: 0, title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark",
        backdropPath: "/harbor.jpg", stillPath: "/e7.jpg", posterPath: "/poster.jpg"
    )

    private var shown: String? { h.system.nowPlaying.last?.artwork?.id }

    @Test func theStillComesFirstThenTheBackdropThenThePoster() {
        #expect(nowPlayingImage(episode) == ArtworkImage(path: "/e7.jpg", size: .w300))
        var r = episode
        r.stillPath = " "
        #expect(nowPlayingImage(r) == ArtworkImage(path: "/harbor.jpg", size: .w1280))
        r.backdropPath = nil
        #expect(nowPlayingImage(r) == ArtworkImage(path: "/poster.jpg", size: .w342))
        r.posterPath = nil
        #expect(nowPlayingImage(r) == nil)
    }

    @Test func nowPlayingShowsThePictureOnceItHasLoaded() async throws {
        await h.start(episode, decision())
        #expect(lookups.images == [ArtworkImage(path: "/e7.jpg", size: .w300)])
        let info = try #require(h.system.nowPlaying.last)
        #expect(info.title == "Harbor Lights")
        #expect(info.artwork == NowPlayingArtwork(id: "w300/e7.jpg", data: Data()))
        #expect(info.artwork?.data == Data("e7".utf8))
    }

    @Test func aPictureThatFailsToLoadLeavesNoneAndThePlayGoesOn() async {
        var r = episode
        r.stillPath = "/missing.jpg"
        await h.start(r, decision())
        #expect(lookups.images.map(\.path) == ["/missing.jpg"])
        #expect(h.system.nowPlaying.last?.artwork == nil)
        #expect(h.state.status == .playing)
    }

    @Test func theNextEpisodeBringsItsOwnStill() async {
        let next = UpNext(fileID: 2, episodeID: 12, showID: 100, showTitle: "Harbor Lights", season: 3, episode: 8, title: "Squall Line", stillPath: "/e8.jpg")
        await h.start(episode, decision(upNextMs: 48 * minute, next: next))
        #expect(shown == "w300/e7.jpg")

        h.gateway.answer = { _ in decision(playID: "b1", sessionID: "s2") }
        h.controller.playNext()
        await h.run()
        #expect(h.state.subtitle == "S03 E08 · Squall Line")
        #expect(h.state.stillPath == "/e8.jpg")
        #expect(h.state.posterPath == "/poster.jpg", "the show's poster carries over")
        #expect(shown == "w300/e8.jpg")
        #expect(lookups.images.map(\.path) == ["/e7.jpg", "/e8.jpg"])
    }

    @Test func aPictureForAPlayAlreadyReplacedIsDropped() async {
        // The next play starts while this one's picture is still loading.
        var next = episode
        next.fileID = 2
        next.stillPath = nil
        lookups.loading = { [h] image in
            if image.path == "/e7.jpg" { h.controller.start(next) }
        }
        await h.start(episode, decision())
        #expect(lookups.images.map(\.path) == ["/e7.jpg", "/harbor.jpg"])
        #expect(h.system.nowPlaying.allSatisfy { $0.artwork?.id != "w300/e7.jpg" })
        #expect(shown == "w1280/harbor.jpg")
    }

    @Test func restartsKeepThePictureWithoutLoadingItAgain() async {
        await h.start(episode, decision())
        #expect(shown == "w300/e7.jpg")

        // A quality change restarts the stream; a retry starts the same play again.
        h.gateway.answer = { _ in decision(playID: "a1", sessionID: "s2") }
        h.controller.selectQuality(.hd)
        await h.run()
        #expect(h.gateway.decisions.count == 2)
        #expect(shown == "w300/e7.jpg")
        h.controller.start(episode)
        await h.run()
        #expect(shown == "w300/e7.jpg")
        #expect(lookups.images.count == 1)
    }

    @Test func aBarePlayLinkGetsThePictureOfWhatItNames() async {
        lookups.named = PlayRequest(fileID: 1, title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark", backdropPath: "/harbor.jpg", stillPath: "/e7.jpg")
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: ""), decision())
        #expect(h.state.stillPath == "/e7.jpg")
        #expect(shown == "w300/e7.jpg")
    }
}
