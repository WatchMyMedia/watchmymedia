import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// A row's See all on iPhone and iPad (PLAN-SEE-ALL.md §4.4, S3): the card's
/// words, Home handing the row's link to the library's tab once, and the grid
/// taking it.
@MainActor
@Suite struct SeeAllTests {
    let navigator = Navigator()

    init() {
        FakeLatency.duration = .zero
    }

    private func fakes() -> AppServices {
        AppServices.fake(apiLevel: rowsAPILevel, rows: true)
    }

    private static func link(_ id: Int, _ sort: ItemSort?, filter: ItemFilter? = nil, genre: String? = nil) -> RowLink {
        .library(id: id, sort: sort, filter: filter, genre: genre, decade: nil)
    }

    // MARK: The words (§3)

    @Test func aLibraryLinkNamesTheLibraryAndTheSortAsTheSortMenuDoes() {
        let aired = SeeAllWords(library: .tv, sort: .aired)
        #expect(aired.title == "See all TV shows")
        #expect(aired.detail == "By last aired")
        #expect(aired.accessibilityLabel == "See all TV shows, by last aired")
        #expect(aired.destination == .library(.tv))

        #expect(SeeAllWords(library: .tv, sort: .added).detail == "By date added")
        let released = SeeAllWords(library: .movie, sort: .released)
        #expect(released.title == "See all movies")
        #expect(released.detail == "By release date")
        #expect(released.accessibilityLabel == "See all movies, by release date")
        #expect(SeeAllWords(library: .movie, sort: .added).accessibilityLabel == "See all movies, by date added")
    }

    @Test func aLinkWithNoSortTheLibraryOffersHasNoSecondLine() {
        let none = SeeAllWords(library: .movie, sort: nil)
        #expect(none.detail == nil)
        #expect(none.accessibilityLabel == "See all movies")
        // TV Shows has no Release date sort, so the grid keeps its own: the card doesn't promise it.
        #expect(SeeAllWords(library: .tv, sort: .released).detail == nil)
    }

    @Test func aListSaysHowManyTitlesItsPageHas() throws {
        let row = ScreenRow(id: "list:9001", style: .poster, title: "Top rated this year", subtitle: "18 of 50 · From TMDB",
                            items: [FakeData.movies[0]], seeAll: .list(id: 9001), listID: 9001)
        let words = try #require(SeeAllWords(row: row, libraryType: nil))
        #expect(words.destination == .list)
        #expect(words.title == "See all")
        #expect(words.detail == "50 titles, with the ones you don’t have")
        #expect(words.accessibilityLabel == "See all of Top rated this year, 50 titles")

        let counted = ScreenRow(id: "list:1", style: .poster, title: "Big", subtitle: "18 of 1,204 · From MDBList", seeAll: .list(id: 1), listID: 1)
        #expect(counted.listTotal == 1204)
        let uncounted = ScreenRow(id: "list:2", style: .poster, title: "Rainy Sunday films", seeAll: .list(id: 2), listID: 2)
        #expect(SeeAllWords(row: uncounted, libraryType: nil)?.detail == "With the ones you don’t have")
        #expect(SeeAllWords(row: uncounted, libraryType: nil)?.accessibilityLabel == "See all of Rainy Sunday films")
    }

    @Test func continueWatchingLiveTVAndRowsWithoutALinkHaveNone() {
        let link = Self.link(FakeData.tvLibrary, .added)
        #expect(SeeAllWords(row: ScreenRow(id: "continue", style: .continue, title: "Continue watching", seeAll: link), libraryType: .tv) == nil)
        #expect(SeeAllWords(row: ScreenRow(id: "live", style: .live, title: "Live TV", seeAll: link), libraryType: .tv) == nil)
        #expect(SeeAllWords(row: ScreenRow(id: "recentlyAddedMovies", style: .poster, title: "Recently added · Movies"), libraryType: .movie) == nil)
        #expect(SeeAllWords(row: ScreenRow(id: "recentlyAired", style: .episode, title: "Recently aired · Episodes", seeAll: link), libraryType: nil) == nil,
                "a library the screen doesn't know")
    }

    @Test func theSeeAllCardNeverLeadsTheRow() {
        #expect(RowPanel.leadingCard(settledOn: SeeAllWords.cardKey, was: "movie-512") == "movie-512")
        #expect(RowPanel.leadingCard(settledOn: SeeAllWords.cardKey, was: nil) == nil)
        #expect(RowPanel.leadingCard(settledOn: "movie-513", was: "movie-512") == "movie-513")
        #expect(RowPanel.leadingCard(settledOn: nil, was: "movie-512") == nil)
    }

    // MARK: Home's rows

    @Test func everyBuiltInRowOnHomeHasItsSeeAll() async throws {
        let services = fakes()
        let model = HomeModel(library: services.library, auth: services.auth, navigator: navigator, rows: services.rows)
        await model.retry()
        let rows = try #require(model.state.value?.rows)
        func words(_ id: String) -> SeeAllWords? { rows.first { $0.id == id }.flatMap(model.seeAll) }
        #expect(words("recentlyAddedShows")?.accessibilityLabel == "See all TV shows, by date added")
        #expect(words("recentlyAired")?.accessibilityLabel == "See all TV shows, by last aired")
        #expect(words("recentlyAddedMovies")?.accessibilityLabel == "See all movies, by date added")
        #expect(words("recentlyReleased")?.accessibilityLabel == "See all movies, by release date")
        #expect(words("list:9001")?.destination == .list)
        #expect(words("continue") == nil)
        #expect(words("live") == nil)
    }

    @Test func homeRowsMixingTwoMovieLibrariesHaveNone() async throws {
        let services = fakes()
        try #require(services.rows as? FakeRowsRepository).twoMovieLibraries = true
        let model = HomeModel(library: services.library, auth: services.auth, navigator: navigator, rows: services.rows)
        await model.retry()
        let rows = try #require(model.state.value?.rows)
        func words(_ id: String) -> SeeAllWords? { rows.first { $0.id == id }.flatMap(model.seeAll) }
        #expect(words("recentlyAddedMovies") == nil)
        #expect(words("recentlyReleased") == nil)
        #expect(words("recentlyAddedShows") != nil, "the one TV library still links")
    }

    @Test func aLinkToALibraryHomeDoesntKnowHasNone() async throws {
        let services = fakes()
        let model = HomeModel(library: services.library, auth: services.auth, navigator: navigator, rows: services.rows)
        await model.retry()
        let row = ScreenRow(id: "recentlyAddedMovies", style: .poster, title: "Recently added · Movies", items: [FakeData.movies[0]], seeAll: Self.link(99, .added))
        #expect(model.seeAll(row) == nil)
    }

    // MARK: Home → the tab, once

    @Test func homesSeeAllSelectsTheTabAndKeepsTheLinkForItOnce() {
        let home = FakeData.home()
        navigator.select(.tvShows)
        navigator.open(.show(id: 100))
        navigator.select(.home)
        let aired = Self.link(FakeData.tvLibrary, .aired)
        navigator.open(aired, home: home)
        #expect(navigator.tab == .tvShows)
        #expect(navigator.path(for: .tvShows).isEmpty, "a page left open on the tab is closed, so the grid shows")
        #expect(navigator.takeBrowseLink(for: .movies) == nil)
        #expect(navigator.takeBrowseLink(for: .tvShows) == aired)
        #expect(navigator.takeBrowseLink(for: .tvShows) == nil, "used once")
    }

    @Test func aNewerSeeAllReplacesOneNotTakenYet() {
        let home = FakeData.home()
        navigator.open(Self.link(FakeData.movieLibrary, .added), home: home)
        navigator.select(.home)
        navigator.open(Self.link(FakeData.movieLibrary, .released), home: home)
        #expect(navigator.takeBrowseLink(for: .movies) == Self.link(FakeData.movieLibrary, .released))
    }

    @Test func aLibraryHomeDoesntKnowGoesNowhere() {
        navigator.open(Self.link(99, .added), home: FakeData.home())
        #expect(navigator.tab == .home)
        #expect(navigator.takeBrowseLink(for: .movies) == nil)
        #expect(navigator.takeBrowseLink(for: .tvShows) == nil)
    }

    // MARK: The grid takes it

    @Test func theTabsGridTakesTheLinkWhenItFirstLoads() async throws {
        let services = fakes()
        navigator.open(Self.link(FakeData.tvLibrary, .aired), home: FakeData.home())
        let model = LibraryModel(library: services.library, navigator: navigator, type: .tv, rows: services.rows)
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { model.gridJump == 1 })
        #expect(model.sort == .aired)
        #expect(model.filter == .all)
        #expect(model.state.value?.items.isEmpty == false)
        #expect(!model.shownRows.isEmpty, "the tab's own rows are still above the grid")
        #expect(navigator.takeBrowseLink(for: .tvShows) == nil, "taken")
    }

    @Test func theLinkSetsTheGridAsAPersonsOwnChoiceOverWhatWasPicked() async throws {
        let services = fakes()
        let model = LibraryModel(library: services.library, navigator: navigator, type: .movie, rows: services.rows)
        var running = Task { await model.run() }
        #expect(await eventually { model.state.value != nil })
        await model.setFilter(.unwatched)
        let genre = try #require(model.genres.first)
        await model.toggleGenre(genre)
        model.setSearchText("tide")
        running.cancel()
        await running.value

        // Home's Recently released · Movies, while the tab is away; then the tab shows again.
        navigator.open(Self.link(FakeData.movieLibrary, .released), home: FakeData.home())
        running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { model.gridJump == 1 })
        #expect(model.sort == .released)
        #expect(model.filter == .all)
        #expect(model.genre == nil)
        #expect(model.searchText.isEmpty)
        #expect(!model.isFiltered)
    }

    @Test func selectingTheTabAgainLaterLeavesTheGridAsThePersonHasIt() async throws {
        let services = fakes()
        navigator.open(Self.link(FakeData.tvLibrary, .aired), home: FakeData.home())
        let model = LibraryModel(library: services.library, navigator: navigator, type: .tv, rows: services.rows)
        var running = Task { await model.run() }
        #expect(await eventually { model.gridJump == 1 })
        await model.setSort(.title)
        running.cancel()
        await running.value

        navigator.select(.home)
        navigator.select(.tvShows)
        running = Task { await model.run() }
        defer { running.cancel() }
        await model.refresh()
        #expect(model.sort == .title, "the person's later choice sticks")
        #expect(model.gridJump == 1, "nothing to go to")
    }

    @Test func aLinkForTheOtherTabIsLeftForIt() async throws {
        let services = fakes()
        navigator.open(Self.link(FakeData.movieLibrary, .released), home: FakeData.home())
        let model = LibraryModel(library: services.library, navigator: navigator, type: .tv, rows: services.rows)
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { model.state.value != nil })
        #expect(model.sort == .added)
        #expect(model.gridJump == 0)
        #expect(navigator.takeBrowseLink(for: .movies) != nil)
    }
}
