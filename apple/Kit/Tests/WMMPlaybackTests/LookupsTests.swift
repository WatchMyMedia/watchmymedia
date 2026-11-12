import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// The next episode from the start, what ``PlaybackLookups`` adds, and the
/// decision's video (android/core/playback/.../LookupsTest.kt).
@MainActor
@Suite struct LookupsTests {
    @Test func theNextEpisodeIsKnownFromTheStartAndPlaysWithoutTheCard() async {
        let h = Harness()
        await h.start(decision(upNextMs: 48 * minute, next: nextEpisode, video: StreamVideo(width: 3840, height: 2160, hdr: "HDR10")))
        await h.advance(60_000)
        #expect(h.state.nextEpisode == nextEpisode)
        #expect(h.state.upNext == nil)
        #expect(h.state.video == StreamVideo(width: 3840, height: 2160, hdr: "HDR10"))

        h.gateway.answer = { _ in decision(playID: "b1", sessionID: "s2") }
        h.controller.playNext()
        await h.run()
        #expect(h.events == [.startedNext(fileID: 2)])
        #expect(h.gateway.decisions.last?.fileID == 2)
        #expect(h.state.subtitle == "S03 E08 · Squall Line")
        // The last episode has no next.
        #expect(h.state.nextEpisode == nil)
    }

    @Test func theCardCarriesTheLookedUpRuntimeAndFinaleAndItsCountdownLength() async throws {
        let h = Harness(lookups: RecordingLookups(described: { next in
            var n = next
            n.runtimeMs = 48 * minute
            n.seasonFinale = true
            return n
        }))
        await h.start(PlayRequest(fileID: 1, startMs: 48 * minute - 1_000, title: "Harbor Lights"), decision(upNextMs: 48 * minute, next: nextEpisode))
        #expect(h.state.nextEpisode?.runtimeMs == 48 * minute)
        #expect(h.state.nextEpisode?.seasonFinale == true)
        await h.advance(1_000)
        let card = try #require(h.state.upNext)
        #expect(card.next.runtimeMs == 48 * minute)
        #expect(card.totalMs == upNextCountdownMs)
    }

    @Test func aFailedLookupLeavesTheDecisionsNextEpisode() async {
        let h = Harness(lookups: RecordingLookups(described: { _ in throw APIError(status: nil, message: "offline") }))
        await h.start(decision(upNextMs: 48 * minute, next: nextEpisode))
        #expect(h.state.nextEpisode == nextEpisode)
    }

    @Test func anUntitledRequestIsNamedBeforeItPlays() async {
        let lookups = RecordingLookups(named: PlayRequest(fileID: 1, title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark", backdropPath: "/b.jpg"))
        let h = Harness(lookups: lookups)
        await h.start(PlayRequest(fileID: 1, title: ""), decision())
        #expect(lookups.names == [1])
        #expect(h.state.title == "Harbor Lights")
        #expect(h.state.subtitle == "S03 E07 · The Long Dark")
        #expect(h.state.backdropPath == "/b.jpg")
        #expect(h.player.loads.first?.title == "Harbor Lights")
        #expect(h.system.nowPlaying.last?.title == "Harbor Lights")
    }

    @Test func aTitledRequestIsNotLookedUp() async {
        let lookups = RecordingLookups(named: PlayRequest(fileID: 1, title: "Something else"))
        let h = Harness(lookups: lookups)
        await h.start(decision())
        #expect(lookups.names.isEmpty)
        #expect(h.state.title == "Harbor Lights")
    }

    @Test func theServersFinalCreditsGiveWayToTheCard() async {
        // No creditsMs: the card takes the credits by the server's final flag alone.
        let h = Harness()
        let credits = Segment(kind: .credits, startMs: 48 * minute, endMs: nil, final: true)
        await h.start(PlayRequest(fileID: 1, startMs: 48 * minute + 1_000, title: "Harbor Lights"), decision(segments: [credits], upNextMs: 48 * minute, next: nextEpisode))
        await h.advance(250)
        #expect(h.state.upNext?.next == nextEpisode)
        #expect(h.state.skip == nil)
    }

    // MARK: LibraryLookups

    private func episode(_ number: Int, fileID: Int, durationMs: Int?, runtimeMin: Int? = 50) -> EpisodeDetail {
        EpisodeDetail(id: 10 + number, season: 3, number: number, title: "E\(number)", runtimeMin: runtimeMin, hasFile: true, aired: true, versions: [MediaVersion(fileID: fileID, label: "e.mkv", sizeBytes: 1, durationMs: durationMs)])
    }

    @Test func theLibraryGivesTheNextEpisodesFileLengthAndWhetherItEndsTheSeason() async throws {
        let described = try await LibraryLookups(library: StubLibrary(episodes: [episode(7, fileID: 1, durationMs: 52 * minute), episode(8, fileID: 2, durationMs: 47 * minute)], seasonSize: 8)).describe(nextEpisode)
        #expect(described.runtimeMs == 47 * minute)
        #expect(described.seasonFinale)
        // A season TMDB says is longer: not the finale yet. No file length: TMDB's runtime.
        let longer = try await LibraryLookups(library: StubLibrary(episodes: [episode(8, fileID: 2, durationMs: nil, runtimeMin: 44)], seasonSize: 10)).describe(nextEpisode)
        #expect(!longer.seasonFinale)
        #expect(longer.runtimeMs == 44 * minute)
    }

    @Test func aNextEpisodeTheServerDescribedIsNotLookedUp() async throws {
        var described = nextEpisode
        described.runtimeMs = 48 * minute
        described.seasonFinale = true
        let library = StubLibrary(episodes: [], seasonSize: 0)
        #expect(try await LibraryLookups(library: library).describe(described) == described)
        #expect(library.seasonsAsked == 0)
    }

    @Test func theLibraryNamesAFileOrLeavesItAlone() async throws {
        let lookups = LibraryLookups(library: StubLibrary(episodes: [], seasonSize: 0))
        #expect(try await lookups.name(PlayRequest(fileID: 1, title: ""))?.title == "Harbor Lights")
        #expect(try await lookups.name(PlayRequest(fileID: 9, title: "")) == nil)
    }
}

/// A library that answers a season and play requests, and nothing else.
@MainActor
final class StubLibrary: LibraryRepository {
    let episodes: [EpisodeDetail]
    let seasonSize: Int
    private(set) var seasonsAsked = 0

    init(episodes: [EpisodeDetail], seasonSize: Int) {
        self.episodes = episodes
        self.seasonSize = seasonSize
    }

    func season(showID: Int, number: Int) async throws -> SeasonDetail {
        seasonsAsked += 1
        return SeasonDetail(showID: showID, showTitle: "Harbor Lights", season: SeasonSummary(id: 3, number: number, name: "Season \(number)", episodeCount: seasonSize, availableCount: episodes.count, watchedCount: 0), episodes: episodes)
    }

    func playRequest(fileID: Int) async throws -> PlayRequest { PlayRequest(fileID: fileID, title: fileID == 9 ? "" : "Harbor Lights") }

    func home() async throws -> HomeData { throw unused }
    func libraries() async throws -> [Library] { throw unused }
    func items(_ query: ItemQuery) async throws -> ItemPage { throw unused }
    func show(id: Int) async throws -> ShowDetail { throw unused }
    func movie(id: Int) async throws -> MovieDetail { throw unused }
    func search(_ text: String) async throws -> SearchResults { throw unused }
    func setWatched(_ kind: WatchKind, id: Int, watched: Bool) async throws { throw unused }
    func setSeasonWatched(showID: Int, season: Int, watched: Bool) async throws { throw unused }
    func setShowWatched(showID: Int, watched: Bool) async throws { throw unused }
    func imageURL(_ path: String?, size: ImageSize) -> URL? { nil }
    func changes() -> AsyncStream<Void> { AsyncStream { $0.finish() } }

    private var unused: APIError { APIError(status: nil, message: "unused") }
}
