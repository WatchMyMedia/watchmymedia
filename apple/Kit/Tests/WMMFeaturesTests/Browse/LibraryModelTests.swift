import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@MainActor
@Suite struct LibraryModelTests {
    let library = TestLibrary()
    let navigator = Navigator()

    init() { FakeLatency.duration = .zero }

    private func model(_ type: LibraryType = .tv, pageSize: Int = LibraryModel.pageSize, debounce: Duration = .milliseconds(20)) -> LibraryModel {
        LibraryModel(library: library, navigator: navigator, type: type, pageSize: pageSize, debounce: debounce)
    }

    private func titles(_ model: LibraryModel) -> [String] { model.state.value?.items.map(\.title) ?? [] }

    @Test func loadsTheTVLibraryByDateAdded() async throws {
        let model = model()
        #expect(model.state == .loading)
        await model.retry()
        let grid = try #require(model.state.value)
        #expect(model.libraryID == FakeData.tvLibrary)
        #expect(grid.total == 16)
        #expect(grid.items.first?.title == "Harbor Lights")
        #expect(library.queries.single?.sort == .added)
        #expect(library.queries.single?.limit == 100)
        #expect(grid.genres.contains("Drama"))
        #expect(model.title == "TV Shows")
        #expect(model.sortLabel == "Sort: Date added")
        #expect(model.searchPrompt == "Search shows")
    }

    @Test func theHeaderCountsTheWholeLibraryAndTheLineWhatMatches() async {
        let model = model()
        await model.retry()
        #expect(model.headerCount == "16 shows")
        #expect(model.resultLine == "16 shows · 312 episodes")
        await model.setFilter(.fourK)
        #expect(model.headerCount == "16 shows")
        #expect(model.resultLine == "7 shows")
        await model.setFilter(.all)
        await model.toggleGenre("Sci-Fi")
        #expect(model.resultLine == "3 shows in Sci-Fi")
    }

    @Test func filtersAndGenreGoIntoTheQueryAndNarrowTheGrid() async {
        let model = model()
        await model.retry()
        await model.setFilter(.fourK)
        #expect(library.queries.last?.filter == .fourK)
        #expect(model.state.value?.items.allSatisfy { $0.quality == "4K" } == true)
        #expect(model.isFiltered)

        await model.toggleGenre("Sci-Fi")
        #expect(titles(model) == ["The Quiet Meridian", "Orbital Decay", "Kestrel Station"])
        await model.toggleGenre("Sci-Fi")
        #expect(model.genre == nil, "picking the genre again clears it")

        await model.clearFilters()
        #expect(!model.isFiltered)
        #expect(model.state.value?.items.count == 16)
    }

    @Test func moviesHaveDecadeChipsAndShowsDoNot() async {
        let shows = model(.tv)
        await shows.retry()
        #expect(shows.decades.isEmpty)
        #expect(shows.filters.contains(.continuing))

        let movies = model(.movie)
        await movies.retry()
        #expect(movies.decades == [2020])
        #expect(!movies.filters.contains(.continuing))
        #expect(movies.sorts.contains(.runtime))
        await movies.toggleDecade(2020)
        #expect(library.queries.last?.decade == 2020)
        #expect(movies.resultLine == "10 movies from the 2020s")
        await movies.toggleDecade(2020)
        #expect(movies.decade == nil)
    }

    @Test func showsGetDecadeChipsWhereTheRowHasRoom() async throws {
        let shows = model(.tv)
        await shows.retry()
        let facets = try #require(shows.state.value?.decades)
        #expect(!facets.isEmpty)
        #expect(shows.decades(includingShows: false).isEmpty, "the iPhone's row has no room")
        #expect(shows.decades(includingShows: true) == facets, "the iPad's has")
        let decade = facets[0]
        await shows.toggleDecade(decade)
        #expect(library.queries.last?.decade == decade)
        #expect(shows.resultLine?.hasSuffix("from the \(decadeLabel(decade))") == true)

        let movies = model(.movie)
        await movies.retry()
        #expect(movies.decades(includingShows: false) == movies.decades(includingShows: true))
    }

    @Test func sortingAsksAgain() async {
        let model = model()
        await model.retry()
        await model.setSort(.title)
        #expect(library.queries.last?.sort == .title)
        #expect(titles(model).first == "Copperline")
        let calls = library.queries.count
        await model.setSort(.title)
        #expect(library.queries.count == calls, "the same sort does not ask again")
    }

    @Test func theSearchFieldFiltersByTitleAMomentAfterTypingStops() async {
        let model = model(debounce: .milliseconds(50))
        await model.retry()
        model.setSearchText("ha")
        model.setSearchText("har")
        #expect(library.queries.count == 1, "nothing is asked while typing")
        await model.pendingSearch?.value
        #expect(library.queries.count == 2, "one query for the text it settled on")
        #expect(library.queries.last?.text == "har")
        #expect(titles(model) == ["Harbor Lights"])
        #expect(model.resultLine == "1 show matching “har”")

        model.setSearchText("har ")
        await model.pendingSearch?.value
        #expect(library.queries.count == 2, "a trailing space is the same search")
        model.setSearchText("")
        await model.pendingSearch?.value
        #expect(library.queries.last?.text == nil)
        #expect(model.state.value?.items.count == 16)
    }

    @Test func pagesLoadAsTheGridScrolls() async throws {
        let model = model(pageSize: 13)
        await model.retry()
        #expect(model.state.value?.items.count == 13)
        #expect(model.state.value?.hasMore == true)

        // The first poster is more than 12 from the end: nothing more yet.
        await model.itemAppeared(try #require(model.state.value?.items.first))
        #expect(library.queries.count == 1)
        await model.itemAppeared(try #require(model.state.value?.items.last))
        #expect(model.state.value?.items.count == 16)
        #expect(library.queries.last?.offset == 13)
        #expect(library.queries.last?.limit == 13)
        #expect(model.state.value?.hasMore == false)
        let calls = library.queries.count
        await model.loadMore()
        #expect(library.queries.count == calls, "nothing more to load")
    }

    @Test func pagesComeAHundredAtATime() async {
        library.itemsOverride = (0..<250).map { BrowseItem(kind: .movie, id: 10_000 + $0, libraryID: FakeData.movieLibrary, title: "Movie \($0)") }
        let model = model(.movie)
        await model.retry()
        #expect(model.state.value?.items.count == 100)
        await model.loadMore()
        await model.loadMore()
        #expect(model.state.value?.items.count == 250)
        #expect(library.queries.map(\.offset) == [0, 100, 200])
    }

    @Test func aChangeRefetchesAsManyAsTheGridHolds() async {
        let model = model(pageSize: 5)
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { model.state.value?.items.count == 5 })
        await model.loadMore()
        library.announceChange()
        #expect(await eventually { library.queries.count == 3 })
        #expect(library.queries.last?.limit == 10)
        #expect(library.queries.last?.offset == 0)
        #expect(model.state.value?.items.count == 10)
    }

    @Test func aFailedPageOffersTryAgainWithoutLosingTheGrid() async {
        let model = model(pageSize: 5)
        await model.retry()
        library.failure = APIError(status: 500, message: "Database is locked.")
        await model.loadMore()
        #expect(model.moreError == "Database is locked.")
        #expect(model.state.value?.items.count == 5)
        library.failure = nil
        await model.retryMore()
        #expect(model.moreError == nil)
        #expect(model.state.value?.items.count == 10)
    }

    @Test func aFailedFirstLoadIsAnErrorWithTryAgain() async {
        let model = model()
        library.failure = APIError(status: nil, message: "Couldn't reach the server.")
        await model.retry()
        #expect(model.state == .failed("Couldn't reach the server."))
        library.failure = nil
        await model.retry()
        #expect(model.state.value?.items.count == 16)
    }

    @Test func aFailedRefreshKeepsTheGrid() async {
        let model = model()
        await model.retry()
        library.failure = APIError(status: nil, message: "Couldn't reach the server.")
        await model.refresh()
        #expect(model.state.value?.items.count == 16)
        #expect(model.message == "Couldn't reach the server.")
    }

    @Test func aLibraryMissingFromTheListIsFoundFromHome() async {
        library.librariesOverride = []
        let model = model()
        await model.retry()
        #expect(model.libraryID == FakeData.tvLibrary)
        #expect(!model.noLibrary)
    }

    @Test func noLibraryOfTheTypeIsItsOwnEmptyState() async {
        library.librariesOverride = [Library(id: 1, name: "Movies", type: .movie)]
        library.homeOverride = HomeData(hero: nil, continueWatching: [], recentlyAddedShows: [], recentlyAired: [], recentlyAddedMovies: [], recentlyReleased: [], totals: LibraryTotals(movies: 0, shows: 0, episodes: 0, files: 0, totalSizeBytes: 0), lastScannedAt: nil, movieLibraryID: 1, tvLibraryID: nil)
        let model = model(.tv)
        await model.retry()
        #expect(model.noLibrary)
        #expect(model.state.value?.items.isEmpty == true)
        #expect(model.headerCount == nil)
        #expect(model.emptyMessage.title == "No TV library")
    }

    @Test func markingWatchedUpdatesThePosterInPlace() async throws {
        let model = model(.movie)
        await model.retry()
        let item = try #require(model.state.value?.items.first { $0.progress != nil })
        await model.setWatched(item, true)
        #expect(library.watchCalls == ["movie \(item.id) true"])
        let updated = try #require(model.state.value?.items.first { $0.id == item.id })
        #expect(updated.watched)
        #expect(updated.progress == nil)
        #expect(model.message == "Marked as watched")
    }

    @Test func postersOpenTheirPagesAndUnmatchedOnesPlayOrExplain() async throws {
        library.itemsOverride = [FakeData.movies[0], unmatchedFilm, unmatchedFolder]
        navigator.select(.movies)
        let model = model(.movie)
        await model.retry()
        let items = try #require(model.state.value?.items)
        #expect(items[1].gridSubtitle == "Not looked up yet")

        model.open(items[0])
        #expect(navigator.path(for: .movies) == [.movie(id: 500)])
        model.open(items[1])
        #expect(navigator.playing == PlayRequest(fileID: 7001, title: "Glass Tide (2023).mkv"))
        model.open(items[2])
        #expect(model.notice == .notMatched)
        #expect(navigator.path(for: .movies) == [.movie(id: 500)])

        await model.setWatched(items[2], true)
        #expect(library.watchCalls.isEmpty, "an unmatched item has nothing to mark")
    }

    @Test func emptyStatesSayWhy() async {
        library.itemsOverride = []
        let model = model()
        await model.retry()
        #expect(model.emptyMessage.title == "No shows yet")
        await model.setFilter(.unwatched)
        #expect(model.emptyMessage.title == "Nothing matches")
        #expect(model.emptyMessage.message == "No shows match these filters.")
    }
}

extension Array {
    /// The only element, or nil when there are none or several.
    var single: Element? { count == 1 ? first : nil }
}

@Suite struct UnmatchedCountTests {
    @Test func countsNameFilesWaitingForAMatch() {
        #expect(unmatchedCount(0) == nil)
        #expect(unmatchedCount(4) == "4 not matched yet")
    }
}
