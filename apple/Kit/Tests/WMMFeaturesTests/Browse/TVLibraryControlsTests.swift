import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// The Apple TV grid's controls: Sort, Unwatched, Genre and More filters on
/// one line with the heading, and what each one does to the grid.
@MainActor
@Suite struct TVLibraryControlsTests {
    let library = TestLibrary()
    let navigator = Navigator()

    init() { FakeLatency.duration = .zero }

    private func model(_ type: LibraryType = .tv) -> LibraryModel {
        LibraryModel(library: library, navigator: navigator, type: type)
    }

    @Test func theHeadingCountsTitlesNotEpisodesOrSizes() async {
        let model = model()
        await model.retry()
        #expect(model.gridHeading == "TV Shows", "no rows above it: the tab's own name")
        #expect(model.gridCountLine == "16 shows")
        await model.chooseGenre("Sci-Fi")
        #expect(model.gridCountLine == "3 shows in Sci-Fi", "filtered, it says what matches")
    }

    @Test func unwatchedIsAChipOfItsOwn() async {
        let model = model()
        await model.retry()
        #expect(!model.unwatchedOnly)
        await model.setUnwatchedOnly(true)
        #expect(model.unwatchedOnly)
        #expect(library.queries.last?.filter == .unwatched)
        #expect(!model.moreFiltersOn, "Unwatched is not one of More filters")
        await model.setUnwatchedOnly(false)
        #expect(model.filter == .all)
    }

    @Test func moreFiltersHoldsTheRestAndTheDecade() async {
        let shows = model()
        await shows.retry()
        #expect(shows.moreFilters == [.continuing, .ended, .fourK, .hdr])
        await shows.setMoreFilter(.continuing, on: true)
        #expect(shows.filter == .continuing)
        #expect(shows.moreFiltersOn)
        // Unwatched and a filter in More are one choice: turning one on turns the other off.
        await shows.setUnwatchedOnly(true)
        #expect(shows.filter == .unwatched)
        #expect(!shows.moreFiltersOn)
        // Turning off a filter that isn't on changes nothing.
        await shows.setMoreFilter(.ended, on: false)
        #expect(shows.filter == .unwatched)

        await shows.chooseDecade(2010)
        #expect(shows.decade == 2010)
        #expect(shows.moreFiltersOn, "a decade counts as one of More filters")
        await shows.chooseDecade(2010)
        #expect(shows.decade == 2010, "choosing the decade again keeps it")
        await shows.chooseDecade(nil)
        #expect(shows.decade == nil)

        let movies = model(.movie)
        await movies.retry()
        #expect(movies.moreFilters == [.fourK, .hdr])
    }

    @Test func genreIsChosenFromAMenu() async {
        let model = model()
        await model.retry()
        #expect(model.genreChipLabel == "Genre")
        await model.chooseGenre("Sci-Fi")
        #expect(model.genreChipLabel == "Sci-Fi")
        await model.chooseGenre("Sci-Fi")
        #expect(model.genre == "Sci-Fi", "choosing it again keeps it, unlike a chip")
        await model.chooseGenre("Drama")
        #expect(model.genre == "Drama")
        await model.chooseGenre(nil)
        #expect(model.genre == nil)
        #expect(model.genreChipLabel == "Genre")
    }
}
