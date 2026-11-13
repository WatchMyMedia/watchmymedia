import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@MainActor
@Suite struct SearchModelTests {
    let library = TestLibrary()
    let navigator = Navigator()
    let model: SearchModel

    init() {
        FakeLatency.duration = .zero
        navigator.select(.search)
        model = SearchModel(library: library, navigator: navigator, debounce: .milliseconds(50))
    }

    @Test func searchesAMomentAfterTypingStops() async throws {
        #expect(model.phase == .idle)
        model.setQuery("har")
        model.setQuery("harbor")
        #expect(model.phase == .loading)
        #expect(library.searches.isEmpty, "nothing is asked while typing")

        await model.pending?.value
        #expect(library.searches == ["harbor"], "one search, for the text it settled on")
        #expect(model.resultsFor == "harbor")
        #expect(!model.loading)
        guard case .results(let results) = model.phase else { Issue.record("no results"); return }
        #expect(results.shows.map(\.title) == ["Harbor Lights"])
        #expect(results.episodes.contains { $0.showTitle == "Harbor Lights" })
    }

    @Test func noMatchesIsItsOwnState() async {
        model.setQuery("zzzz")
        await model.pending?.value
        #expect(model.phase == .noMatches("zzzz"))
    }

    @Test func clearingTheFieldClearsTheResults() async {
        model.setQuery("kite")
        await model.pending?.value
        #expect(model.results?.movies.count == 1)
        model.clear()
        #expect(model.results == nil)
        #expect(model.phase == .idle)
        #expect(!model.loading)
    }

    @Test func aBlankQueryAsksNothing() async {
        model.setQuery("   ")
        await model.pending?.value
        #expect(library.searches.isEmpty)
        #expect(model.phase == .idle)
    }

    @Test func aFailedSearchOffersTryAgain() async {
        library.failure = APIError(status: 500, message: "Search is unavailable.")
        model.setQuery("kite")
        await model.pending?.value
        #expect(model.phase == .failed("Search is unavailable."))
        library.failure = nil
        await model.retry()
        #expect(model.results?.movies.single?.title == "The Paper Kite")
    }

    @Test func oldResultsStayWhileTheNextSearchRuns() async {
        model.setQuery("kite")
        await model.pending?.value
        model.setQuery("kit")
        #expect(model.loading)
        guard case .results = model.phase else { Issue.record("the old results went"); return }
        await model.pending?.value
        #expect(model.resultsFor == "kit")
    }

    @Test func recentQueriesKeepTheNewestEightDistinctSuccessfulSearches() async {
        let model = SearchModel(library: library, navigator: navigator, debounce: .zero)
        for number in 0..<10 {
            model.setQuery("  Query \(number)  ")
            await model.pending?.value
        }
        #expect(model.recentQueries == (2..<10).reversed().map { "Query \($0)" })
        model.setQuery(" query 5 ")
        await model.pending?.value
        #expect(model.recentQueries.first == "query 5")
        #expect(model.recentQueries.count == 8)
        #expect(!model.recentQueries.contains("Query 5"))
    }

    @Test func recentQueriesCanBeReplayedAndCleared() async {
        model.setQuery("harbor")
        await model.pending?.value
        model.clear()
        #expect(model.phase == .idle)
        #expect(model.recentQueries == ["harbor"])
        model.selectRecentQuery("harbor")
        await model.pending?.value
        #expect(model.query == "harbor")
        #expect(model.resultsFor == "harbor")
        #expect(library.searches == ["harbor", "harbor"])
        model.clearRecentQueries()
        #expect(model.recentQueries.isEmpty)
        #expect(model.resultsFor == "harbor", "clearing history does not discard current results")
        #expect(library.searches.count == 2)
        model.clear()
        #expect(model.phase == .idle)
        #expect(model.recentQueries.isEmpty)
    }

    @Test func recentQueriesAreKeptBetweenLaunchesForTheAccountOnTheServer() async {
        let name = "SearchModelTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let first = SearchModel(library: library, navigator: navigator, debounce: .zero, recents: RecentSearches(server: "server-a", user: 1, defaults: defaults))
        first.setQuery("harbor")
        await first.pending?.value
        first.setQuery("kite")
        await first.pending?.value

        let relaunched = SearchModel(library: library, navigator: navigator, debounce: .zero, recents: RecentSearches(server: "server-a", user: 1, defaults: defaults))
        #expect(relaunched.recentQueries == ["kite", "harbor"])
        let otherAccount = SearchModel(library: library, navigator: navigator, debounce: .zero, recents: RecentSearches(server: "server-a", user: 2, defaults: defaults))
        #expect(otherAccount.recentQueries.isEmpty)
        let otherServer = SearchModel(library: library, navigator: navigator, debounce: .zero, recents: RecentSearches(server: "server-b", user: 1, defaults: defaults))
        #expect(otherServer.recentQueries.isEmpty)

        relaunched.clearRecentQueries()
        #expect(RecentSearches(server: "server-a", user: 1, defaults: defaults).load().isEmpty, "Clear forgets them on the device too")
    }

    @Test func failedAndCancelledSearchesDoNotEnterHistory() async {
        library.failure = APIError(status: 500, message: "Unavailable")
        model.setQuery("harbor")
        await model.pending?.value
        #expect(model.recentQueries.isEmpty)
        library.failure = nil
        model.setQuery("kite")
        let cancelled = model.pending
        model.clear()
        await cancelled?.value
        #expect(model.recentQueries.isEmpty)
        #expect(library.searches.isEmpty)
    }

    @Test func rebuildingSearchForAnotherAccountStartsWithNoHistory() async {
        model.setQuery("harbor")
        await model.pending?.value
        #expect(model.recentQueries == ["harbor"])
        let nextAccount = SearchModel(library: library, navigator: navigator)
        #expect(nextAccount.recentQueries.isEmpty)
        #expect(nextAccount.phase == .idle)
        #expect(library.searches == ["harbor"])
    }

    @Test func resultsOpenTheirPages() async throws {
        model.setQuery("glass")
        await model.pending?.value
        let results = try #require(model.results)
        model.open(try #require(results.shows.first))
        model.open(try #require(results.episodes.first))
        #expect(navigator.path(for: .search) == [.show(id: 108), .episode(showID: 108, season: 1, id: 108016)])
        model.play(try #require(results.episodes.first))
        #expect(navigator.playing?.fileID == 9105)
    }

    @Test func unmatchedResultsPlayOrExplain() {
        model.open(unmatchedFilm)
        #expect(navigator.playing?.fileID == 7001)
        model.open(unmatchedFolder)
        #expect(model.notice == .notMatched)
        #expect(navigator.path(for: .search).isEmpty)
    }

    @Test func markingAResultWatchedUpdatesIt() async throws {
        model.setQuery("kite")
        await model.pending?.value
        let movie = try #require(model.results?.movies.single)
        await model.setWatched(movie, true)
        #expect(library.watchCalls == ["movie \(movie.id) true"])
        #expect(model.results?.movies.single?.watched == true)
        #expect(model.results?.movies.single?.progress == nil)
        #expect(model.message == "Marked as watched")
    }

    @Test func markingAnEpisodeResultUpdatesIt() async throws {
        model.setQuery("glass")
        await model.pending?.value
        let episode = try #require(model.results?.episodes.first)
        #expect(episode.watched)
        await model.setWatched(episode, false)
        #expect(library.watchCalls == ["episode 108016 false"])
        #expect(model.results?.episodes.first?.watched == false)
    }
}
