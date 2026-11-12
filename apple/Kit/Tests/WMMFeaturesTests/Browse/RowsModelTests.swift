import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Arranged Home, the library rows, Edit Home and the list page (PLAN-ROWS.md HR6) on the fakes.
@MainActor
@Suite struct RowsModelTests {
    let navigator = Navigator()

    init() {
        FakeLatency.duration = .zero
    }

    /// The fakes at `apiLevel`, with the rows repository wired in as the app wires it.
    private func fakes(apiLevel: Int = rowsAPILevel, admin: Bool = true) -> AppServices {
        AppServices.fake(admin: admin, apiLevel: apiLevel, rows: true)
    }

    // MARK: Home

    @Test func fromLevel22HomeDrawsItsRowsInThePersonsOrder() async throws {
        let services = fakes()
        let model = HomeModel(library: services.library, auth: services.auth, navigator: navigator, rows: services.rows)
        await model.retry()
        let home = try #require(model.state.value)
        #expect(model.canEditRows)
        #expect(home.rows?.map(\.id) == ["continue", "live", "recentlyAddedShows", "list:9001", "recentlyAired", "recentlyAddedMovies", "list:9002", "recentlyReleased"])
        #expect(home.continueWatching.isEmpty, "the fixed arrays come empty")
        #expect(!home.isEmpty)
        let list = try #require(home.rows?.first { $0.id == "list:9001" })
        #expect(list.subtitle == "4 of 12 · From TMDB")
        #expect(list.seeAll == .list(id: FakeRowsRepository.sciFi))
        #expect(home.heroBadges == ["4K", "HDR"])
    }

    @Test func belowLevel22HomeKeepsItsFixedRows() async throws {
        let services = fakes(apiLevel: audioPreferencesAPILevel)
        let model = HomeModel(library: services.library, auth: services.auth, navigator: navigator, rows: services.rows)
        await model.retry()
        let home = try #require(model.state.value)
        #expect(home.rows == nil)
        #expect(!model.canEditRows)
        #expect(home.continueWatching.count == 5)
        #expect(!home.recentlyAddedShows.isEmpty)
    }

    @Test func withoutLiveTVTheLiveRowIsLeftOut() {
        let continueRow = ScreenRow(id: "continue", style: .continue, title: "Continue watching", continueItems: FakeData.continueWatching)
        let live = ScreenRow(id: "live", style: .live, title: "Live TV")
        var home = FakeData.home()
        home.rows = [live, continueRow]
        #expect(home.drawnRows(liveTV: true).map(\.id) == ["live", "continue"])
        #expect(home.drawnRows(liveTV: false).map(\.id) == ["continue"])
        // On iPad Continue sits beside the hero only while it is the first row drawn.
        #expect(home.continueBesideHero(liveTV: true) == nil)
        #expect(home.continueBesideHero(liveTV: false)?.count == 5)
        home.hero = nil
        #expect(home.continueBesideHero(liveTV: false) == nil, "no hero, nothing beside it")
    }

    @Test func aListRowsMovieAndShowWithTheSameIDAreDifferentCards() {
        let movie = BrowseItem(kind: .movie, id: 1, libraryID: 1, title: "Paper Moons")
        let show = BrowseItem(kind: .show, id: 1, libraryID: 2, title: "Night Shift Diner")
        #expect(movie.cardID != show.cardID)
    }

    @Test func anArrangedHomeWithOnlyLiveTVIsEmpty() {
        var home = FakeData.home()
        home.hero = nil
        home.rows = [ScreenRow(id: "live", style: .live, title: "Live TV")]
        #expect(home.isEmpty)
    }

    @Test func theHerosPlayFindsItsEpisodeInTheArrangedRows() async throws {
        let services = fakes()
        let model = HomeModel(library: services.library, auth: services.auth, navigator: navigator, rows: services.rows)
        await model.retry()
        model.playHero()
        #expect(navigator.playing?.fileID == 9101)
    }

    @Test func aListRowsSeeAllOpensTheListAndALibraryRowsItsTab() async throws {
        let services = fakes()
        let model = HomeModel(library: services.library, auth: services.auth, navigator: navigator, rows: services.rows)
        await model.retry()
        model.open(.list(id: 9001))
        #expect(navigator.path(for: .home) == [.mediaList(id: 9001)])
        #expect(model.canOpen(.library(id: FakeData.movieLibrary, sort: .added, filter: nil, genre: nil, decade: nil)))
        #expect(!model.canOpen(.library(id: 99, sort: nil, filter: nil, genre: nil, decade: nil)))
        model.open(.library(id: FakeData.movieLibrary, sort: .added, filter: nil, genre: nil, decade: nil))
        #expect(navigator.tab == .movies)
    }

    @Test func homeRefetchesWhenTheRowsChange() async throws {
        let services = fakes()
        let library = try #require(services.library as? FakeLibraryRepository)
        let model = HomeModel(library: library, auth: services.auth, navigator: navigator, rows: services.rows)
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { model.state.value?.rows?.first?.id == "continue" })
        let editor = RowEditorModel(rows: services.rows, screen: .home)
        await editor.load()
        editor.moveDown("continue")
        #expect(await editor.save())
        #expect(await eventually { model.state.value?.rows?.first?.id == "live" }, "the saved order, from rows.changed")
    }

    // MARK: The library's rows

    @Test func theMoviesScreenHasItsRowsAboveTheGrid() async throws {
        let services = fakes()
        let model = LibraryModel(library: services.library, navigator: navigator, type: .movie, rows: services.rows)
        await model.retry()
        #expect(model.shownRows.map(\.id) == ["recentlyAdded", "list:9004", "recentlyReleased"])
        #expect(model.canEditRows)
        #expect(model.screen == .library(FakeData.movieLibrary))
        model.setSearchText("tide")
        #expect(model.shownRows.isEmpty, "a title search hides the rows")
    }

    @Test func belowLevel22TheLibraryHasNoRows() async throws {
        let services = fakes(apiLevel: audioPreferencesAPILevel)
        let model = LibraryModel(library: services.library, navigator: navigator, type: .movie, rows: services.rows)
        await model.retry()
        #expect(model.state.value?.items.isEmpty == false)
        #expect(model.rows.isEmpty)
        #expect(!model.canEditRows)
    }

    @Test func aLibraryRowsSeeAllSortsTheGrid() async throws {
        let services = fakes()
        let model = LibraryModel(library: services.library, navigator: navigator, type: .movie, rows: services.rows)
        await model.retry()
        await model.open(.library(id: FakeData.movieLibrary, sort: .released, filter: .fourK, genre: nil, decade: nil))
        #expect(model.sort == .released)
        #expect(model.filter == .fourK)
        await model.open(.list(id: FakeRowsRepository.best2026))
        #expect(navigator.path(for: .home) == [.mediaList(id: FakeRowsRepository.best2026)])
    }

    // MARK: Edit Home

    @Test func theEditorListsEveryRowAndOffersTheHiddenOnes() async throws {
        let services = fakes()
        let editor = RowEditorModel(rows: services.rows, screen: .home)
        #expect(editor.title == "Home rows")
        #expect(editor.groupTitle == "Rows on Home")
        await editor.load()
        #expect(editor.rows.map(\.id) == ["continue", "live", "recentlyAddedShows", "list:9001", "recentlyAired", "recentlyAddedMovies", "list:9002", "recentlyReleased", "list:9003"])
        #expect(editor.offered.map(\.id) == ["list:9003"])
        #expect(editor.addGroups(editor.offered.map(\.id)).map(\.title) == ["For everyone"])
        #expect(editor.addLine(editor.offered[0]) == "4 of 6 · From TMDB")
        #expect(editor.line(editor.rows[0]) == "Built in")
        #expect(editor.line(editor.rows[1]) == "Built in · On now")
        #expect(editor.line(editor.rows[3]) == "For everyone · 4 of 12 · From TMDB")
        #expect(editor.line(editor.rows[6]) == "Your list · 3 of 7 · From an import")
        #expect(editor.line(editor.rows[8]) == "Hidden · For everyone · 4 of 6 · From TMDB")
        #expect(editor.summary == "8 shown · 1 hidden")
        #expect(editor.isBesideHero(editor.rows[0]))
        #expect(!editor.changed)
        #expect(!editor.canMoveUp("continue"))
        #expect(editor.canMoveDown("continue"))
    }

    @Test func continueIsBesideTheHeroOnlyWhileItIsTheFirstRowShown() async throws {
        let services = fakes()
        let editor = RowEditorModel(rows: services.rows, screen: .home)
        await editor.load()
        editor.moveDown("continue")
        #expect(!editor.isBesideHero(editor.rows[1]))
        editor.setHidden("live", true)
        #expect(editor.isBesideHero(editor.rows[1]), "Live TV is hidden, so Continue watching is first again")
    }

    @Test func movingHidingAndAddingSaveTheWholeOrder() async throws {
        let services = fakes()
        let rows = try #require(services.rows as? FakeRowsRepository)
        let editor = RowEditorModel(rows: rows, screen: .home)
        await editor.load()
        editor.move(fromOffsets: [0], toOffset: 3)
        #expect(editor.rows.map(\.id).prefix(3) == ["live", "recentlyAddedShows", "continue"])
        editor.moveUp("continue")
        #expect(editor.rows.map(\.id).prefix(3) == ["live", "continue", "recentlyAddedShows"])
        editor.setHidden("live", true)
        #expect(editor.rows.first?.hidden == true, "a hidden row stays in its place, greyed")
        #expect(editor.offered.map(\.id) == ["live", "list:9003"])
        editor.add("list:9003")
        #expect(editor.rows.last?.id == "list:9003", "an added row shows in its place")
        #expect(editor.rows.last?.hidden == false)
        #expect(editor.addGroups(["live", "list:9003"]).map(\.title) == ["Built-in rows", "For everyone"], "a row just added stays in Add a row, as Added")
        #expect(editor.changed)

        #expect(await editor.save())
        let saved = try #require(rows.saved[.home])
        #expect(saved.prefix(3) == [RowPlacement(id: "live", hidden: true), RowPlacement(id: "continue"), RowPlacement(id: "recentlyAddedShows")])
        #expect(saved.last == RowPlacement(id: "list:9003"))
        #expect(saved.count == 9, "every row is sent, so none comes back by itself")
        #expect(!editor.changed)
        #expect(editor.customized)
    }

    @Test func rowsStillHiddenAreSavedHidden() async throws {
        let services = fakes()
        let rows = try #require(services.rows as? FakeRowsRepository)
        let editor = RowEditorModel(rows: rows, screen: .home)
        await editor.load()
        editor.moveDown("continue")
        #expect(await editor.save())
        #expect(rows.saved[.home]?.last == RowPlacement(id: "list:9003", hidden: true))
    }

    @Test func doneWithNothingChangedSavesNothing() async throws {
        let services = fakes()
        let rows = try #require(services.rows as? FakeRowsRepository)
        let editor = RowEditorModel(rows: rows, screen: .home)
        await editor.load()
        #expect(await editor.save())
        #expect(rows.saved[.home] == nil)
    }

    @Test func resetPutsTheHouseholdsOrderBack() async throws {
        let services = fakes()
        let rows = try #require(services.rows as? FakeRowsRepository)
        let editor = RowEditorModel(rows: rows, screen: .home)
        await editor.load()
        editor.moveDown("continue")
        #expect(await editor.save())
        await editor.reset()
        #expect(rows.saved[.home] == nil)
        #expect(editor.rows.first?.id == "continue")
        #expect(!editor.customized)
        #expect(!editor.changed)
    }

    @Test func onAppleTVEachChangeIsSavedAsItIsMade() async throws {
        let services = fakes()
        let rows = try #require(services.rows as? FakeRowsRepository)
        let editor = RowEditorModel(rows: rows, screen: .library(FakeData.movieLibrary), screenName: "Movies", autosave: true)
        #expect(editor.title == "Movies rows")
        #expect(editor.tvIntro.hasSuffix("All movies stays below them."))
        await editor.load()
        #expect(editor.addGroups(editor.offered.map(\.id)).map(\.title) == ["For everyone", "Your lists"])
        editor.moveDown("recentlyAdded")
        editor.setHidden("recentlyReleased", true)
        await editor.pendingSave?.value
        let saved = try #require(rows.saved[.library(FakeData.movieLibrary)])
        #expect(saved.prefix(3) == [RowPlacement(id: "list:9004"), RowPlacement(id: "recentlyAdded"), RowPlacement(id: "recentlyReleased", hidden: true)])
    }

    @Test func aRefusedSaveSaysWhyAndKeepsTheEdits() async throws {
        let editor = RowEditorModel(rows: RefusingRows(), screen: .home)
        await editor.load()
        editor.moveDown("continue")
        #expect(await editor.save() == false)
        #expect(editor.message == "This screen has no row live.")
        #expect(editor.changed)
    }

    // MARK: The list page

    @Test func theListPageShowsEveryTitleInRankOrder() async throws {
        let services = fakes()
        let model = MediaListModel(listID: FakeRowsRepository.sciFi, rows: services.rows, library: services.library, requests: services.requests, navigator: navigator)
        await model.retry()
        let page = try #require(model.state.value)
        #expect(model.title == "Top rated science fiction")
        #expect(model.sourceLine == "From TMDB")
        #expect(model.countsLine == "4 of 12 in your library · For everyone")
        #expect(model.whenLine(now: page.list.fetchedAt! + 3 * 3600) == "Updated 3 h ago")
        #expect(model.failureLine() == nil)
        #expect(page.entries.map(\.rank) == Array(1...12))
        #expect(page.entries.filter(\.inLibrary).count == 4)
        #expect(model.canRefresh, "Sam is an admin, and TMDB lists are fetched")
        #expect(model.canDelete)

        let perihelion = page.entries[1]
        #expect(!perihelion.inLibrary)
        #expect(model.canRequest(perihelion))
        let cinder = page.entries[5]
        #expect(cinder.requestState == .requested)
        #expect(!model.canRequest(cinder), "asked for already")
    }

    @Test func requestingAMissingTitleMarksItRequested() async throws {
        let services = fakes()
        let model = MediaListModel(listID: FakeRowsRepository.sciFi, rows: services.rows, library: services.library, requests: services.requests, navigator: navigator)
        await model.retry()
        let entry = try #require(model.state.value?.entries[1])
        model.openRequest(entry)
        let sheet = try #require(model.requestSheet)
        await sheet.loadOnce()
        #expect(sheet.title == "Perihelion Drift")
        await sheet.send()
        #expect(sheet.sent != nil)
        model.requestSheetClosed(sheet)
        #expect(model.state.value?.entries[1].requestState == .requested)
        #expect(model.state.value.map { !model.canRequest($0.entries[1]) } == true)
    }

    @Test func anImportedListsTitleTMDBCouldNotFindIsNotFound() async throws {
        let services = fakes()
        let model = MediaListModel(listID: FakeRowsRepository.rainySunday, rows: services.rows, library: services.library, requests: services.requests, navigator: navigator)
        await model.retry()
        let kiln = try #require(model.state.value?.entries.first { !$0.found })
        #expect(kiln.missingLine == "1996 · Not found")
        #expect(!model.canRequest(kiln))
        #expect(!model.canRefresh, "imports aren't fetched again")
        #expect(model.sourceLine == "From an import")
        #expect(model.countsLine == "3 of 7 in your library · 1 not found · Your list")
        #expect(model.whenLine()?.hasPrefix("Imported ") == true)
    }

    @Test func aMemberCantChangeTheHouseholdsList() async throws {
        let services = fakes(admin: false)
        let model = MediaListModel(listID: FakeRowsRepository.best2026, rows: services.rows, library: services.library, requests: services.requests, navigator: navigator)
        await model.retry()
        #expect(!model.canDelete)
        #expect(!model.canRefresh)
        #expect(model.failureLine() == "Couldn’t update this list: MDBList refused the key. This is the list from 3 days ago.")
    }

    @Test func deletingAListLeavesItsPage() async throws {
        let services = fakes()
        navigator.open(.mediaList(id: FakeRowsRepository.rainySunday))
        let model = MediaListModel(listID: FakeRowsRepository.rainySunday, rows: services.rows, library: services.library, requests: services.requests, navigator: navigator)
        await model.retry()
        await model.delete()
        #expect(navigator.path(for: .home).isEmpty)
        #expect(navigator.toast == "Deleted “Rainy Sunday films”")
        let home = try await services.library.home()
        #expect(home.rows?.contains { $0.id == "list:9002" } == false, "its row goes from Home")
    }

    @Test func aTitleTheLibraryHasOpensItsPage() async throws {
        let services = fakes()
        let model = MediaListModel(listID: FakeRowsRepository.sciFi, rows: services.rows, library: services.library, requests: services.requests, navigator: navigator)
        await model.retry()
        let meridian = try #require(model.state.value?.entries.first)
        model.open(meridian)
        #expect(navigator.path(for: .home) == [.show(id: 101)])
    }
}

/// A server that refuses every save, with the fakes' layout.
@MainActor
private final class RefusingRows: RowsRepository {
    let fake = FakeRowsRepository()
    var available: Bool { true }
    func libraryRows(libraryID: Int) async throws -> [ScreenRow] { [] }
    func layout(_ screen: RowScreen) async throws -> RowLayout { try await fake.layout(screen) }
    func saveLayout(_ rows: [RowPlacement], for screen: RowScreen) async throws -> RowLayout {
        throw APIError(status: 422, message: "This screen has no row live.")
    }
    func resetLayout(_ screen: RowScreen) async throws -> RowLayout { try await fake.resetLayout(screen) }
    func lists() async throws -> [MediaList] { [] }
    func list(id: Int) async throws -> MediaList { try await fake.list(id: id) }
    func entries(listID: Int, offset: Int, limit: Int) async throws -> MediaListEntries { try await fake.entries(listID: listID, offset: offset, limit: limit) }
    func refresh(listID: Int) async throws -> MediaList { try await fake.refresh(listID: listID) }
    func delete(listID: Int) async throws {}
    func changes() -> AsyncStream<Void> { AsyncStream { _ in } }
}
