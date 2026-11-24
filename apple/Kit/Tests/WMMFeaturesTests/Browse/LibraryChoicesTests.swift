import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// A library keeps its sort and filters on each device (Apple TV): the grid
/// opens the way the person left it, for each server and person; a row's
/// See all applies for its visit only; without a store nothing is kept.
@MainActor
@Suite struct LibraryChoicesTests {
    let library = TestLibrary()
    let navigator = Navigator()
    let defaults: UserDefaults

    init() {
        FakeLatency.duration = .zero
        let suite = "LibraryChoicesTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
    }

    private func model(_ type: LibraryType = .tv, choices: LibraryChoices?) -> LibraryModel {
        LibraryModel(library: library, navigator: navigator, type: type, debounce: .milliseconds(20), choices: choices)
    }

    @Test func theGridOpensWithTheSortAndFiltersLeftOnIt() async {
        let choices = LibraryChoices(defaults: defaults, scope: "home/1")
        let first = model(choices: choices)
        await first.retry()
        await first.setSort(.title)
        await first.setFilter(.unwatched)
        await first.toggleGenre("Sci-Fi")
        await first.toggleDecade(2020)

        let again = model(choices: LibraryChoices(defaults: defaults, scope: "home/1"))
        #expect(again.sort == .title)
        #expect(again.filter == .unwatched)
        #expect(again.genre == "Sci-Fi")
        #expect(again.decade == 2020)
        await again.retry()
        let query = library.queries.last
        #expect(query?.sort == .title && query?.filter == .unwatched && query?.genre == "Sci-Fi" && query?.decade == 2020)
    }

    @Test func clearingTheFiltersKeepsTheSort() async {
        let choices = LibraryChoices(defaults: defaults, scope: "home/1")
        let first = model(choices: choices)
        await first.retry()
        await first.setSort(.title)
        await first.setFilter(.fourK)
        await first.clearFilters()
        let kept = choices.kept(.tv)
        #expect(kept == KeptLibraryChoices(sort: .title))
    }

    @Test func eachLibraryServerAndPersonKeepsItsOwn() async {
        let shows = model(.tv, choices: LibraryChoices(defaults: defaults, scope: "home/1"))
        await shows.retry()
        await shows.setSort(.title)
        #expect(model(.movie, choices: LibraryChoices(defaults: defaults, scope: "home/1")).sort == .added, "Movies keeps its own")
        #expect(model(.tv, choices: LibraryChoices(defaults: defaults, scope: "cabin/1")).sort == .added, "another server's library keeps its own")
        #expect(model(.tv, choices: LibraryChoices(defaults: defaults, scope: "home/2")).sort == .added, "another person keeps their own")
    }

    @Test func aSeeAllAppliesForItsVisitAndKeepsNothing() async {
        let choices = LibraryChoices(defaults: defaults, scope: "home/1")
        let grid = model(choices: choices)
        await grid.retry()
        await grid.setSort(.title)
        await grid.open(.library(id: FakeData.tvLibrary, sort: .aired, filter: .continuing, genre: "Drama", decade: nil))
        #expect(grid.sort == .aired && grid.filter == .continuing)
        #expect(choices.kept(.tv) == KeptLibraryChoices(sort: .title))
        // A choice made after it keeps only what was chosen.
        await grid.setFilter(.unwatched)
        #expect(choices.kept(.tv) == KeptLibraryChoices(sort: .title, filter: .unwatched))
    }

    @Test func withoutAStoreTheGridOpensByDateAddedAsBefore() async {
        let first = model(choices: nil)
        await first.retry()
        await first.setSort(.title)
        #expect(model(choices: nil).sort == .added)
        #expect(LibraryChoices(defaults: defaults, scope: "home/1").kept(.tv) == nil)
    }

    @Test func aKeptChoiceTheLibraryDoesNotOfferIsLeftOut() {
        let choices = LibraryChoices(defaults: defaults, scope: "home/1")
        // Last aired and Continuing are TV Shows' alone.
        choices.keep(KeptLibraryChoices(sort: .aired, filter: .continuing, genre: "Drama"), for: .movie)
        let movies = model(.movie, choices: choices)
        #expect(movies.sort == .added)
        #expect(movies.filter == .all)
        #expect(movies.genre == "Drama")
    }
}
