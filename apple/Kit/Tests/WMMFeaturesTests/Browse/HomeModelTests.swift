import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@MainActor
@Suite struct HomeModelTests {
    let library = TestLibrary()
    let navigator = Navigator()
    let model: HomeModel

    init() {
        FakeLatency.duration = .zero
        model = HomeModel(library: library, auth: FakeAuthRepository(), navigator: navigator)
    }

    @Test func loadsHome() async throws {
        #expect(model.state == .loading)
        await model.retry()
        let home = try #require(model.state.value)
        #expect(home.hero?.title == "Harbor Lights")
        #expect(home.continueWatching.count == 5)
        #expect(model.initial == "S")
        #expect(model.userName == "Sam")
    }

    @Test func aFailedLoadShowsWhyAndTryAgainRecovers() async {
        library.failure = APIError(status: 503, message: "The server is starting up.")
        await model.retry()
        #expect(model.state == .failed("The server is starting up."))
        library.failure = nil
        await model.retry()
        #expect(model.state.value != nil)
    }

    @Test func aFailedPullToRefreshKeepsHomeAndSaysWhy() async {
        await model.retry()
        library.failure = APIError(status: nil, message: "Couldn't reach the server.")
        await model.refresh()
        #expect(model.state.value != nil)
        #expect(model.message == "Couldn't reach the server.")
    }

    @Test func refetchesWhenTheLibraryChanges() async {
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { model.state.value != nil && library.homeCalls == 1 })
        library.announceChange()
        #expect(await eventually { library.homeCalls == 2 })
        #expect(model.state.value != nil, "the refetch is quiet: Home stays on screen")
    }

    @Test func runningAgainRefetchesQuietly() async {
        await model.retry()
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(model.state.value != nil)
        #expect(await eventually { library.homeCalls == 2 })
    }

    @Test func markingWatchedCallsTheServerAndConfirms() async throws {
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { model.state.value != nil })
        await model.setWatched(.movie(id: 500), true)
        #expect(library.watchCalls == ["movie 500 true"])
        #expect(model.message == "Marked as watched")
        #expect(await eventually { library.homeCalls == 2 })
        await model.setWatched(.show(id: 100), false)
        #expect(library.watchCalls.last == "show 100 false")
        #expect(model.message == "Marked as unwatched")
        #expect(await eventually { library.homeCalls == 3 })
        try await Task.sleep(for: .milliseconds(50))
        #expect(library.homeCalls == 3, "each mark loads Home once, from its own notice")
    }

    @Test func scansReloadOnlyAnEmptyHome() async throws {
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { model.state.value != nil })
        library.announceChange(.scan(libraryID: 1))
        try await Task.sleep(for: .milliseconds(50))
        #expect(library.homeCalls == 1, "library.changed brings what a scan found")
        library.announceChange(.library(id: 1))
        #expect(await eventually { library.homeCalls == 2 })
    }

    @Test func aFailedMarkSaysWhy() async {
        await model.retry()
        library.failure = APIError(status: 500, message: "Database is locked.")
        await model.setWatched(.episode(id: 100037), true)
        #expect(model.message == "Database is locked.")
        #expect(model.state.value != nil)
    }

    @Test func continueWatchingResumesStraightIntoThePlayer() async throws {
        await model.retry()
        let item = try #require(model.state.value?.continueWatching.first)
        model.resume(item)
        let playing = try #require(navigator.playing)
        #expect(playing.fileID == 9001)
        #expect(playing.startMs == nil, "resumes where the viewer left off")
        #expect(playing.title == "The Quiet Meridian")
        #expect(playing.subtitle == "S02 E04 · Drift")

        model.openPage(of: item)
        #expect(navigator.path(for: .home) == [.show(id: 101)])
    }

    @Test func theHeroPlaysAndOpensItsEpisode() async throws {
        await model.retry()
        model.playHero()
        let playing = try #require(navigator.playing)
        #expect(playing.fileID == 9101)
        #expect(playing.title == "Harbor Lights")
        #expect(playing.subtitle == "S03 E07 · The Long Dark")
        model.openHero()
        #expect(navigator.path(for: .home) == [.episode(showID: 100, season: nil, id: 100037)])
    }

    @Test func aRecentlyAiredEpisodeOpensItsShowWithItOnTop() async throws {
        await model.retry()
        let aired = try #require(model.state.value?.recentlyAired)
        model.open(aired[0])
        #expect(navigator.path(for: .home) == [.episode(showID: 100, season: 3, id: 100037)])
        model.play(aired[0])
        #expect(navigator.playing?.fileID == 9101)
        navigator.closePlayer()
        model.play(aired[3])
        #expect(navigator.playing == nil, "Copperline S04 E02 is not in the library")
    }

    @Test func postersOpenTheirPages() async throws {
        await model.retry()
        let home = try #require(model.state.value)
        model.open(home.recentlyAddedShows[1])
        model.open(home.recentlyAddedMovies[0])
        #expect(navigator.path(for: .home) == [.show(id: 101), .movie(id: 500)])
    }

    @Test func aRecentlyAddedCardForOneEpisodeOpensItsPage() async throws {
        await model.retry()
        let home = try #require(model.state.value)
        model.open(home.recentlyAddedShows[0])
        #expect(navigator.path(for: .home) == [.episode(showID: 100, season: 3, id: 100037)])
    }

    @Test func unmatchedItemsPlayOrExplain() async {
        model.open(unmatchedFilm)
        #expect(navigator.playing == PlayRequest(fileID: 7001, title: "Glass Tide (2023).mkv"))
        #expect(model.notice == nil)
        model.open(unmatchedFolder)
        #expect(model.notice == .notMatched)
        #expect(navigator.path(for: .home).isEmpty, "a folder has no page to open")
    }

    @Test func anEmptyLibraryIsItsOwnState() async {
        library.homeOverride = HomeData(hero: nil, continueWatching: [], recentlyAddedShows: [], recentlyAired: [], recentlyAddedMovies: [], recentlyReleased: [], totals: LibraryTotals(movies: 0, shows: 0, episodes: 0, files: 0, totalSizeBytes: 0), lastScannedAt: nil, movieLibraryID: nil, tvLibraryID: nil)
        await model.retry()
        #expect(model.state.value?.isEmpty == true)
        #expect(model.emptyState == .emptyLibraries)
    }

    private func emptyHome() {
        library.homeOverride = HomeData(hero: nil, continueWatching: [], recentlyAddedShows: [], recentlyAired: [], recentlyAddedMovies: [], recentlyReleased: [], totals: LibraryTotals(movies: 0, shows: 0, episodes: 0, files: 0, totalSizeBytes: 0), lastScannedAt: nil, movieLibraryID: nil, tvLibraryID: nil)
    }

    @Test func populatedHomeDoesNotMakeAnExtraLibraryRequest() async {
        await model.retry()
        #expect(library.librariesCalls == 0)
        #expect(model.emptyState == nil)
    }

    @Test func noLibrariesExplainsSetupToAdminsAndMembers() async {
        emptyHome()
        library.librariesOverride = []
        await model.retry()
        #expect(model.emptyState == .noLibraries)
        #expect(model.offersSetup)
        #expect(model.emptyState?.message(isAdmin: false) == "Ask the person who runs it to add a library.")
        let member = HomeModel(library: library, auth: FakeAuthRepository(admin: false), navigator: navigator)
        await member.retry()
        #expect(!member.offersSetup)
    }

    @Test func aRunningScanDoesNotAskForLibrariesToBeAdded() async {
        emptyHome()
        library.librariesOverride = [Library(id: 1, name: "Movies", type: .movie, scanning: true, lastScanError: "An earlier failure")]
        await model.retry()
        #expect(model.emptyState == .scanning, "the current retry supersedes the saved failure")
        #expect(!model.offersSetup)
    }

    @Test func aFailedLibraryIsNotHiddenByAnotherRunningScan() async {
        emptyHome()
        let failed = Library(id: 1, name: "Movies", type: .movie, lastScanError: "Folder not found.")
        library.librariesOverride = [failed, Library(id: 2, name: "TV Shows", type: .tv, scanning: true)]
        await model.retry()
        #expect(model.emptyState == .scanFailed([failed], otherScanRunning: true))
        #expect(model.emptyState?.message(isAdmin: true).contains("Couldn’t read Movies: Folder not found.") == true)
        #expect(model.offersSetup)
    }

    @Test func anUnreadableLibraryListDoesNotPretendThereAreNoLibraries() async {
        emptyHome()
        library.librariesFailure = APIError(status: 503, message: "Try again later.")
        await model.retry()
        #expect(model.state.value?.isEmpty == true)
        #expect(model.emptyState == .unavailable)
        #expect(!model.offersSetup)
    }

    @Test func refreshedScanAndPopulatedHomeReplaceTheEmptyState() async {
        emptyHome()
        library.librariesOverride = []
        await model.retry()
        library.librariesOverride = [Library(id: 1, name: "Movies", type: .movie, scanning: true)]
        await model.refresh()
        #expect(model.emptyState == .scanning)
        library.homeOverride = nil
        await model.refresh()
        #expect(model.emptyState == nil)
        #expect(model.state.value?.isEmpty == false)
    }
}
