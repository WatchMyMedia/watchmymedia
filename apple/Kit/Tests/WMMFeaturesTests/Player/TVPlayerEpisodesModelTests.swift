import Foundation
import Testing
import WMMFakes
import WMMModel
@testable import WMMFeatures

/// The fake library, counting what the Episodes tab asks of it.
@MainActor
private final class CountingLibrary: LibraryRepository {
    let fake = FakeLibraryRepository()
    var failure: (any Error)?
    private(set) var showCalls: [Int] = []
    private(set) var seasonCalls: [Int] = []
    private(set) var playRequestCalls: [Int] = []

    func show(id: Int) async throws -> ShowDetail {
        showCalls.append(id)
        if let failure { throw failure }
        return try await fake.show(id: id)
    }

    func season(showID: Int, number: Int) async throws -> SeasonDetail {
        seasonCalls.append(number)
        if let failure { throw failure }
        return try await fake.season(showID: showID, number: number)
    }

    func playRequest(fileID: Int) async throws -> PlayRequest {
        playRequestCalls.append(fileID)
        if let failure { throw failure }
        return try await fake.playRequest(fileID: fileID)
    }

    var requests: Int { showCalls.count + seasonCalls.count + playRequestCalls.count }

    func home() async throws -> HomeData { try await fake.home() }
    func libraries() async throws -> [Library] { try await fake.libraries() }
    func items(_ query: ItemQuery) async throws -> ItemPage { try await fake.items(query) }
    func movie(id: Int) async throws -> MovieDetail { try await fake.movie(id: id) }
    func search(_ text: String) async throws -> SearchResults { try await fake.search(text) }
    func setWatched(_ kind: WatchKind, id: Int, watched: Bool) async throws {}
    func setSeasonWatched(showID: Int, season: Int, watched: Bool) async throws {}
    func setShowWatched(showID: Int, watched: Bool) async throws {}
    func imageURL(_ path: String?, size: ImageSize) -> URL? { nil }
    func changes() -> AsyncStream<Void> { AsyncStream { $0.finish() } }
    func libraryChanges() -> AsyncStream<LibraryChange> { AsyncStream { $0.finish() } }
}

/// The Episodes tab of the Apple TV player (TvPlayerEpisodes.dc.html): Harbor
/// Lights season 3 on the fakes has episodes 1–8 on disk, and file 9101 is
/// episode 7, The Long Dark.
@MainActor
@Suite struct TVPlayerEpisodesModelTests {
    private let longDark = PlayRequest(fileID: 9101, title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark", showID: 100, seasonNumber: 3)

    private func model() -> (TVPlayerEpisodesModel, CountingLibrary) {
        FakeLatency.duration = .zero
        let library = CountingLibrary()
        return (TVPlayerEpisodesModel(library: library), library)
    }

    @Test func anEpisodeFromAPageHasTheTabWithoutAskingAndOpeningAsksTheShowAndItsSeasonOnce() async throws {
        let (model, library) = model()
        await model.follow(longDark)
        #expect(model.hasTab)
        #expect(library.requests == 0, "the request says which show and season")
        await model.open()
        #expect(library.showCalls == [100])
        #expect(library.seasonCalls == [3])
        #expect(model.status == .loaded)
        #expect(model.seasons.map(\.number) == [1, 2, 3])
        #expect(model.chosenSeason == 3)
        #expect(model.episodes.map(\.number) == Array(1...8), "only the episodes on disk")
        await model.open()
        #expect(library.requests == 2, "opening again asks nothing")
    }

    @Test func theTabOpensOnTheOnePlayingWhichIsMarkedAndTheOneAfterItIsNext() async throws {
        let (model, _) = model()
        await model.follow(longDark)
        await model.open()
        let playing = try #require(model.episodes.first { $0.number == 7 })
        let next = try #require(model.episodes.first { $0.number == 8 })
        let earlier = try #require(model.episodes.first { $0.number == 2 })
        #expect(model.initialFocusID == playing.id)
        #expect(model.isPlaying(playing))
        #expect(model.nextEpisodeID == next.id)
        #expect(model.focusLine(playing) == "Now playing · S03 E07 · The Long Dark · 52 min")
        #expect(model.focusLine(next) == "Next · S03 E08 · Squall Line · 48 min")
        #expect(model.focusLine(earlier).hasPrefix("S03 E02 · The Ferryman"))
        #expect(model.accessibilityLabel(playing) == "Episode 7, The Long Dark, now playing")
        #expect(model.accessibilityLabel(earlier) == "Episode 2, The Ferryman, watched")
        #expect(model.showsWatched(earlier))
        #expect(model.panelLine == "Season 3 · 8 episodes · you are on episode 7")
        #expect(model.progress(playing, playingPositionMs: 750_000, playingDurationMs: 3_000_000) == 0.25)
    }

    @Test func choosingAnotherPlaysItResumingAndTheOnePlayingHasNothingToChoose() async throws {
        let (model, _) = model()
        await model.follow(longDark)
        await model.open()
        let playing = try #require(model.episodes.first { $0.number == 7 })
        let next = try #require(model.episodes.first { $0.number == 8 })
        #expect(model.request(for: playing) == nil)
        let request = try #require(model.request(for: next))
        #expect(request.fileID == next.fileID)
        #expect(request.startMs == nil, "from where the person left it")
        #expect(request.showID == 100)
        #expect(request.seasonNumber == 3)
        #expect(request.title == "Harbor Lights")
        #expect(request.subtitle == "S03 E08 · Squall Line")
    }

    @Test func aSeasonChosenLoadsOnceAndTheRowStartsOnItsFirstEpisode() async throws {
        let (model, library) = model()
        await model.follow(longDark)
        await model.open()
        await model.choose(season: 2)
        #expect(model.chosenSeason == 2)
        #expect(model.episodes.allSatisfy { $0.season == 2 })
        #expect(model.initialFocusID == model.episodes.first?.id, "nothing plays in season 2")
        #expect(model.nextEpisodeID != nil, "Next stays the playing season's")
        let season2 = try #require(model.seasons.first { $0.number == 2 })
        #expect(model.seasonLine(season2) == "Season 2 · \(model.episodes.count) episodes", "the episodes on disk")
        await model.choose(season: 3)
        await model.choose(season: 2)
        #expect(library.seasonCalls == [3, 2], "each season is asked once")
    }

    @Test func theNextEpisodeStartedInPlaceIsPlacedFromTheLoadedSeasonAndItsMarksAskedAgainOnOpen() async throws {
        let (model, library) = model()
        await model.follow(longDark)
        await model.open()
        let next = try #require(model.episodes.first { $0.number == 8 })
        // PlayerSession's request for an episode started in place carries no show.
        await model.follow(PlayRequest(fileID: try #require(next.fileID), title: "Harbor Lights", subtitle: "S03 E08 · Squall Line"))
        #expect(library.playRequestCalls.isEmpty, "the loaded season already places it")
        #expect(model.hasTab)
        #expect(model.isPlaying(next))
        #expect(model.initialFocusID == next.id)
        await model.open()
        #expect(library.seasonCalls == [3, 3], "the watched marks moved on")
        #expect(library.showCalls == [100], "the show is not asked again")
    }

    @Test func anEpisodeWithoutItsShowIsLookedUpOnlyAfterAnotherEpisode() async throws {
        let (model, library) = model()
        // A film (or anything that isn't placed) asks nothing and has no tab.
        await model.follow(PlayRequest(fileID: 9900, title: "Glass Tide", subtitle: "2023"))
        #expect(!model.hasTab)
        #expect(library.requests == 0)
        await model.follow(longDark)
        // Before the tab was opened nothing is loaded, so the next file is looked up once.
        await model.follow(PlayRequest(fileID: 9101, title: "Harbor Lights"))
        #expect(library.playRequestCalls == [9101])
        #expect(model.place == .init(showID: 100, season: 3))
    }

    @Test func aRecordingHasNoTab() async {
        let (model, library) = model()
        await model.follow(longDark)
        await model.follow(PlayRequest(fileID: 0, title: "Inspector Vane", recordingID: "r1"))
        #expect(!model.hasTab)
        #expect(library.requests == 0)
    }

    @Test func aFailedLoadSaysSoAndTryAgainLoads() async {
        let (model, library) = model()
        await model.follow(longDark)
        library.failure = APIError(status: 503, message: "Try again later")
        await model.open()
        #expect(model.status == .failed("Try again later"))
        #expect(model.episodes.isEmpty)
        library.failure = nil
        await model.retry()
        #expect(model.status == .loaded)
        #expect(!model.episodes.isEmpty)
    }

    @Test func anotherShowStartsAfresh() async {
        let (model, library) = model()
        await model.follow(longDark)
        await model.open()
        await model.follow(PlayRequest(fileID: 9103, title: "The Quiet Meridian", showID: 101, seasonNumber: 2))
        #expect(model.status == .idle)
        #expect(model.seasons.isEmpty)
        await model.open()
        #expect(library.showCalls == [100, 101])
        #expect(model.episodes.allSatisfy { $0.season == 2 })
    }
}

/// An episode chosen in the tab plays in place, on the same player.
@MainActor
@Suite struct TVPlayerChosenEpisodeTests {
    @Test func aChosenEpisodeStartsInPlaceAndBecomesTheRequest() {
        let spy = SpyPlaybackController()
        let exits = Counter()
        let model = TVPlayerModel(controller: spy, request: PlayRequest(fileID: 9101, title: "Harbor Lights", showID: 100, seasonNumber: 3), onExit: { exits.count += 1 })
        let chosen = PlayRequest(fileID: 9102, title: "Harbor Lights", subtitle: "S03 E08 · Squall Line", showID: 100, seasonNumber: 3)
        model.play(chosen)
        #expect(spy.calls.filter { $0.hasPrefix("start") }.isEmpty, "nothing plays before the player started")
        model.start()
        model.play(chosen)
        #expect(spy.calls.filter { $0.hasPrefix("start") } == ["start 9101", "start 9102"])
        #expect(model.request == chosen)
        #expect(exits.count == 0)
        model.dispose()
        model.play(PlayRequest(fileID: 9103, title: "Harbor Lights"))
        #expect(model.request == chosen, "nothing after the screen went away")
    }
}
