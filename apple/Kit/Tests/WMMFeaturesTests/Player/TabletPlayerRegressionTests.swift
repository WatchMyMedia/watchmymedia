import Foundation
import Testing
@testable import WMMFeatures
import WMMModel

/// These exercise the retained player models. View identity during an actual
/// rotation still needs a simulator test with an instrumented playback factory.
@MainActor
@Suite struct TabletPlayerRegressionTests {
    @Test(arguments: [PlayerStatus.playing, .paused])
    func refreshingEpisodeMetadataAndOpeningPanelsDoesNotRestartPlayback(_ status: PlayerStatus) async {
        let h = PlayerHarness(status: status)
        defer { h.model.dispose() }
        let library = TabletEpisodeLibrary()
        let episodes = PlayerEpisodesModel(library: library)
        h.spy.state.positionMs = 87_000

        for tab in PickerTab.allCases {
            // A view may request metadata again after a size-class transition.
            await episodes.load(for: h.model.request)
            h.model.openPicker(tab, pausesPlayback: false)
            h.model.closePicker()
        }

        #expect(episodes.season?.season.number == 3)
        #expect(h.spy.state.status == status)
        #expect(h.spy.state.positionMs == 87_000)
        #expect(h.spy.calls == ["start 9101"], "Metadata and tablet panels must not pause, resume, start, stop or release playback")
    }

    @Test func automaticAdvanceResolvesTheNewSeasonWithoutStartingTheFileTwice() async throws {
        let h = PlayerHarness()
        defer { h.model.dispose() }
        let library = TabletEpisodeLibrary()
        let episodes = PlayerEpisodesModel(library: library)
        await episodes.load(for: h.model.request)
        #expect(episodes.season?.season.number == 3)

        // The engine has already begun this file when the chrome consumes its
        // event. The new season's final episode deliberately has no Up Next.
        h.spy.state.title = "Harbor Lights"
        h.spy.state.subtitle = "S04 E01 · First Light"
        h.spy.state.positionMs = 0
        h.spy.state.upNext = nil
        h.spy.send(.startedNext(fileID: 9201))
        await h.time.settle()
        #expect(h.model.request.fileID == 9201)
        await episodes.load(for: h.model.request)

        let season = try #require(episodes.season)
        #expect(season.season.number == 4)
        #expect(season.episodes.map(\.fileID) == [9201])
        let episode = try #require(season.episodes.first)
        let nextRequest = try #require(episodes.request(for: episode))
        #expect(nextRequest.showID == 100)
        #expect(nextRequest.seasonNumber == 4)
        #expect(nextRequest.fileID == h.model.request.fileID)
        #expect(h.spy.calls == ["start 9101"], "The started-next event is a notification, not a second start command")

        h.model.retry()
        #expect(h.spy.calls == ["start 9101", "start 9201"], "Retry must target the episode now playing")
    }

    @Test(arguments: [false, true])
    func aLateOldSeasonSuccessOrFailureCannotReplaceTheCurrentSeason(fails: Bool) async throws {
        let library = TabletEpisodeLibrary()
        let episodes = PlayerEpisodesModel(library: library)
        var response: CheckedContinuation<SeasonDetail, any Error>?
        library.seasonHandler = { _, number in
            if number == 3 {
                return try await withCheckedThrowingContinuation { response = $0 }
            }
            return TabletEpisodeLibrary.detail(number)
        }
        let oldLoad = Task { await episodes.load(for: TabletEpisodeLibrary.request(9101, season: 3)) }
        #expect(await eventually { response != nil })
        await episodes.load(for: TabletEpisodeLibrary.request(9201, season: 4))
        #expect(episodes.season?.season.number == 4)

        // Deliberately deliver a response even though it belongs to an older
        // invocation: cancellation cooperation is not the only protection.
        let oldResponse = try #require(response)
        if fails {
            oldResponse.resume(throwing: APIError(status: 503, message: "Old season unavailable"))
        } else {
            oldResponse.resume(returning: TabletEpisodeLibrary.detail(3))
        }
        await oldLoad.value
        #expect(episodes.season?.season.number == 4)
        #expect(episodes.error == nil)
        #expect(!episodes.loading)
    }

    @Test func aLateFileLookupDoesNotFetchThePreviousEpisodesSeason() async throws {
        let library = TabletEpisodeLibrary()
        let episodes = PlayerEpisodesModel(library: library)
        var response: CheckedContinuation<PlayRequest, Never>?
        library.requestHandler = { _ in await withCheckedContinuation { response = $0 } }
        let oldLoad = Task { await episodes.load(for: PlayRequest(fileID: 9101, title: "Bare link")) }
        #expect(await eventually { response != nil })
        await episodes.load(for: TabletEpisodeLibrary.request(9201, season: 4))
        let oldResponse = try #require(response)
        oldResponse.resume(returning: TabletEpisodeLibrary.request(9101, season: 3))
        await oldLoad.value

        #expect(library.seasonCalls == [4], "An obsolete file lookup must stop before fetching another season")
        #expect(episodes.season?.season.number == 4)
    }

    @Test func aCancelledSeasonLookupCannotPublishAfterTheTabletViewLeaves() async throws {
        let library = TabletEpisodeLibrary()
        let episodes = PlayerEpisodesModel(library: library)
        var response: CheckedContinuation<SeasonDetail, Never>?
        library.seasonHandler = { _, _ in await withCheckedContinuation { response = $0 } }
        let load = Task { await episodes.load(for: TabletEpisodeLibrary.request(9101, season: 3)) }
        #expect(await eventually { response != nil })
        load.cancel()
        let cancelledResponse = try #require(response)
        cancelledResponse.resume(returning: TabletEpisodeLibrary.detail(3))
        await load.value

        #expect(episodes.season == nil)
        #expect(episodes.error == nil)
        #expect(!episodes.loading)
    }

    @Test func selectingAnEpisodeEndsThePreviousFilesDoubleTapSeekRun() async {
        let h = PlayerHarness()
        defer { h.model.dispose() }
        h.model.tap(x: 880, width: 915)
        h.model.tap(x: 880, width: 915)
        #expect(h.spy.calls.filter { $0.hasPrefix("seek") } == ["seekBy 10000"])

        h.model.play(TabletEpisodeLibrary.request(9201, season: 4))
        h.spy.state.positionMs = 0
        // The first tap on the new episode should show/hide chrome, not count
        // as the third tap of the old episode's seek gesture.
        h.model.tap(x: 880, width: 915)
        await h.time.advance(byMs: PlayerChromeModel.doubleTapMs)
        #expect(h.spy.calls.filter { $0.hasPrefix("seek") } == ["seekBy 10000"])
        #expect(h.spy.state.positionMs == 0)
    }

    @Test func selectingAnEpisodeCancelsThePreviousFilesPendingSingleTap() async {
        let h = PlayerHarness()
        defer { h.model.dispose() }
        h.model.tap(x: 450, width: 915)
        h.model.play(TabletEpisodeLibrary.request(9201, season: 4))
        await h.time.advance(byMs: PlayerChromeModel.doubleTapMs)

        #expect(h.model.showsControls, "An old tap timer must not hide controls just shown for a different episode")
        #expect(h.spy.calls == ["start 9101", "start 9201"])
    }

    @Test func automaticAdvanceDiscardsTheOldFilesScrubPreviewAndDragCompletion() async {
        let h = PlayerHarness()
        defer { h.model.dispose() }
        h.model.scrub(x: 0.9, width: 1)
        h.spy.state.positionMs = 0
        h.spy.send(.startedNext(fileID: 9201))
        await h.time.settle()

        #expect(h.model.shownPositionMs == 0, "The old episode's preview is not a position in the new file")
        h.model.endScrub()
        #expect(h.spy.calls == ["start 9101"], "The end of the previous drag must not seek inside the new episode")
    }
}

/// Deterministic metadata with independently controllable file/season latency.
/// Other repository operations fail rather than silently supplying fake data.
@MainActor
private final class TabletEpisodeLibrary: LibraryRepository {
    var requestHandler: ((Int) async throws -> PlayRequest)?
    var seasonHandler: ((Int, Int) async throws -> SeasonDetail)?
    private(set) var seasonCalls: [Int] = []

    static func request(_ fileID: Int, season: Int) -> PlayRequest {
        PlayRequest(fileID: fileID, title: "Harbor Lights", showID: 100, seasonNumber: season)
    }

    static func detail(_ number: Int) -> SeasonDetail {
        let fileID = number == 3 ? 9101 : 9201
        return SeasonDetail(
            showID: 100, showTitle: "Harbor Lights",
            season: SeasonSummary(id: number, number: number, name: "Season \(number)", episodeCount: 1, availableCount: 1, watchedCount: 0),
            episodes: [EpisodeDetail(
                id: fileID + 100_000, season: number, number: 1, title: "First Light", hasFile: true, aired: true,
                versions: [MediaVersion(fileID: fileID, label: "1080p", sizeBytes: 1_000)]
            )]
        )
    }

    func playRequest(fileID: Int) async throws -> PlayRequest {
        if let requestHandler { return try await requestHandler(fileID) }
        return Self.request(fileID, season: fileID == 9101 ? 3 : 4)
    }

    func season(showID: Int, number: Int) async throws -> SeasonDetail {
        seasonCalls.append(number)
        if let seasonHandler { return try await seasonHandler(showID, number) }
        return Self.detail(number)
    }

    private enum UnusedOperation: Error { case unexpectedCall }
    func home() async throws -> HomeData { throw UnusedOperation.unexpectedCall }
    func libraries() async throws -> [Library] { throw UnusedOperation.unexpectedCall }
    func items(_ query: ItemQuery) async throws -> ItemPage { throw UnusedOperation.unexpectedCall }
    func show(id: Int) async throws -> ShowDetail { throw UnusedOperation.unexpectedCall }
    func movie(id: Int) async throws -> MovieDetail { throw UnusedOperation.unexpectedCall }
    func search(_ text: String) async throws -> SearchResults { throw UnusedOperation.unexpectedCall }
    func setWatched(_ kind: WatchKind, id: Int, watched: Bool) async throws { throw UnusedOperation.unexpectedCall }
    func setSeasonWatched(showID: Int, season: Int, watched: Bool) async throws { throw UnusedOperation.unexpectedCall }
    func setShowWatched(showID: Int, watched: Bool) async throws { throw UnusedOperation.unexpectedCall }
    func imageURL(_ path: String?, size: ImageSize) -> URL? { nil }
    func changes() -> AsyncStream<Void> { AsyncStream { $0.finish() } }
}
