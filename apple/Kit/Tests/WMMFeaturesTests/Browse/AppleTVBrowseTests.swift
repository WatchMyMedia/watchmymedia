import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// The browse screens as the Apple TV app uses them: the season tabs, the library's
/// Decade menu and header (TvLibrary.dc.html), and Menu on the Siri Remote.
@MainActor
@Suite struct AppleTVBrowseTests {
    let library = TestLibrary()
    let navigator = Navigator()

    init() { FakeLatency.duration = .zero }

    private func show(_ arrival: ShowArrival = .show) -> ShowPageModel {
        ShowPageModel(library: library, navigator: navigator, showID: 100, arrival: arrival, requests: FakeRequestsRepository(library: FakeLibraryRepository()))
    }

    // MARK: Show

    @Test func theTabsSayHowMuchIsHere() async throws {
        let model = show()
        await model.retry()
        #expect(model.seasonLine?.hasPrefix("8 episodes · 4 watched · E09 airs ") == true)
        let tabs = model.tabs
        #expect(tabs.first { $0.number == 3 }?.tabCount(missingShown: false) == "8")
        #expect(tabs.first { $0.number == 2 }?.tabCount(missingShown: true) == "5 of 8")
    }

    @Test func castAndDetails() {
        let show = FakeData.show(id: 100)
        #expect(show.details == [
            DetailRow("Status", "Continuing · next Nov 5"),
            DetailRow("Genres", "Drama, Crime"),
            DetailRow("Rated", "TV-14"),
            DetailRow("Episodes", "21 of 25 on disk"),
            DetailRow("Size", formatBytes(21 * 2_400_000_000)),
        ])
    }

    @Test func breadcrumbs() {
        #expect(breadcrumb(tab: .tvShows, title: "Harbor Lights") == "TV Shows › Harbor Lights")
        #expect(breadcrumb(tab: .home, title: "Harbor Lights", forEpisode: true) == "Home › Recently aired › Harbor Lights")
        #expect(breadcrumb(tab: .search, title: "Harbor Lights", forEpisode: true) == "Search › Harbor Lights")
    }

    // MARK: Library

    @Test func theDecadeMenuOffersEveryDecadeForShowsToo() async {
        let model = LibraryModel(library: library, navigator: navigator, type: .tv)
        await model.retry()
        #expect(model.decades.isEmpty, "the phone keeps decade chips for movies")
        #expect(model.decades(includingShows: true) == [2020, 2010])
        #expect(model.decadeChipLabel == "Decade")
        await model.toggleDecade(2010)
        #expect(model.decadeChipLabel == "2010s")
        #expect(library.queries.last?.decade == 2010)
        #expect(model.state.value?.items.allSatisfy { ($0.year ?? 0) / 10 == 201 } == true)
        await model.toggleDecade(2010)
        #expect(model.decadeChipLabel == "Decade")
    }

    @Test func theHeaderCountsTheShowsStillRunning() async {
        let model = LibraryModel(library: library, navigator: navigator, type: .tv)
        await model.retry()
        #expect(model.overviewLine == "16 shows · 312 episodes · 12 continuing")
        await model.toggleGenre("Sci-Fi")
        #expect(model.overviewLine == "3 shows in Sci-Fi")

        let movies = LibraryModel(library: library, navigator: navigator, type: .movie)
        await movies.retry()
        #expect(movies.overviewLine == "10 movies")
    }

    // MARK: Menu

    @Test func menuPopsThenGoesHomeThenLeavesItToTheSystem() {
        navigator.select(.tvShows)
        navigator.open(.show(id: 100))
        #expect(navigator.exitSection())
        #expect(navigator.path(for: .tvShows).isEmpty)
        #expect(navigator.tab == .tvShows)
        #expect(navigator.exitSection())
        #expect(navigator.tab == .home)
        #expect(!navigator.exitSection(), "Menu at Home's root is the system's")
    }
}
