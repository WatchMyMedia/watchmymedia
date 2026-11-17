import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Related titles at the end of movie and show pages (PLAN-RELATED.md §6,
/// §7): asked once the page is in, only from API level 30, kept with the
/// page, and never in the page's way.
@MainActor
@Suite struct RelatedRowsTests {
    let library = TestLibrary()
    let playback = TestPlayback()
    let navigator = Navigator()

    init() { FakeLatency.duration = .zero }

    private func movie(_ id: Int) -> MovieModel {
        MovieModel(library: library, playback: playback, navigator: navigator, movieID: id)
    }

    private func show(_ id: Int) -> ShowPageModel {
        ShowPageModel(library: library, navigator: navigator, showID: id)
    }

    /// The fake server at `apiLevel`, signed in.
    private func serverAt(_ apiLevel: Int) {
        library.fake.servers = FakeServerRepository(apiLevel: apiLevel)
    }

    // MARK: Asking

    @Test func aMoviesPageEndsWithItsCollectionThenMoreLikeThis() async throws {
        serverAt(relatedAPILevel)
        let model = movie(500)
        await model.retry()

        #expect(model.movie?.title == "Dead Reckoning Bay")
        #expect(library.relatedCalls == [.movie(id: 500)])
        #expect(model.relatedRows.map(\.id) == ["collection", "similar"])
        let collection = model.relatedRows[0]
        #expect(collection.title == "Carrow Coast Collection")
        #expect(collection.items.map(\.title) == ["Cold Harbour Road", "Low Tide at Carrow"], "the collection's others, in release order")
        let similar = model.relatedRows[1]
        #expect(similar.title == "More like this")
        #expect(similar.items.count >= 3)
        #expect(model.relatedRows.allSatisfy { $0.style == .poster && $0.seeAll == nil })
        #expect(model.relatedRows.flatMap(\.items).allSatisfy { $0.kind == .movie && $0.id != 500 }, "movies only, never the page's own")
        #expect(Set(similar.items.map(\.id)).isDisjoint(with: collection.items.map(\.id)), "the collection isn't listed again")
    }

    @Test func aShowsPageEndsWithMoreLikeThis() async throws {
        serverAt(relatedAPILevel)
        let model = show(100)
        await model.retry()

        #expect(model.show?.title == "Harbor Lights")
        #expect(library.relatedCalls == [.show(id: 100)])
        #expect(model.relatedRows.map(\.id) == ["similar"])
        #expect(model.relatedRows[0].title == "More like this")
        #expect(model.relatedRows[0].items.allSatisfy { $0.kind == .show && $0.id != 100 }, "shows only, never the page's own")
    }

    @Test func aServerBelowLevel30IsAskedNothingAndThePageEndsAsBefore() async throws {
        serverAt(relatedAPILevel - 1)
        let movie = movie(500)
        await movie.retry()
        let show = show(100)
        await show.retry()

        #expect(movie.movie != nil)
        #expect(show.show != nil)
        #expect(library.relatedCalls.isEmpty)
        #expect(movie.relatedRows.isEmpty)
        #expect(show.relatedRows.isEmpty)
    }

    @Test func nothingRelatedIsNoRows() async throws {
        serverAt(relatedAPILevel)
        let movie = movie(507)
        await movie.retry()
        let show = show(108)
        await show.retry()

        #expect(library.relatedCalls == [.movie(id: 507), .show(id: 108)])
        #expect(movie.movie?.title == "Tin Sky")
        #expect(movie.relatedRows.isEmpty)
        #expect(show.relatedRows.isEmpty)
    }

    @Test func aFailedReplyLeavesThePageAsItWasAndTheNextLoadAsksAgain() async throws {
        serverAt(relatedAPILevel)
        library.relatedFailure = APIError(status: 500, message: "The related titles couldn’t be worked out.")
        let model = movie(500)
        await model.retry()

        #expect(model.movie?.title == "Dead Reckoning Bay")
        #expect(model.relatedRows.isEmpty)
        #expect(model.message == nil, "the page says nothing about rows it never showed")

        library.relatedFailure = nil
        await model.load(quiet: true)
        #expect(library.relatedCalls.count == 2)
        #expect(model.relatedRows.map(\.id) == ["collection", "similar"])

        // A failure after that keeps the rows the page has.
        library.relatedFailure = APIError(status: nil, message: "Couldn’t reach the server.")
        await model.refresh()
        #expect(library.relatedCalls.count == 3)
        #expect(model.relatedRows.map(\.id) == ["collection", "similar"])
        #expect(model.message == nil)
    }

    @Test func aShowsFailedReplyLeavesItsPageAsItWas() async throws {
        serverAt(relatedAPILevel)
        library.relatedFailure = APIError(status: nil, message: "Couldn’t reach the server.")
        let model = show(100)
        await model.retry()

        #expect(model.state.value != nil)
        #expect(model.episode != nil)
        #expect(model.relatedRows.isEmpty)
        #expect(model.message == nil)
    }

    @Test func thePageIsDrawnWithoutWaitingForTheRows() async throws {
        serverAt(relatedAPILevel)
        let (gate, open) = AsyncStream.makeStream(of: Void.self)
        library.relatedGate = { for await _ in gate { return } }
        let model = movie(500)
        let loading = Task { await model.retry() }

        #expect(await eventually { model.movie != nil && !model.plans.isEmpty && !library.relatedCalls.isEmpty })
        #expect(library.relatedCalls == [.movie(id: 500)])
        #expect(model.relatedRows.isEmpty, "still on its way")

        open.yield()
        await loading.value
        #expect(model.relatedRows.map(\.id) == ["collection", "similar"])
    }

    // MARK: Kept with the page

    @Test func reloadsKeepTheReplyAndPullToRefreshAsksAgain() async throws {
        serverAt(relatedAPILevel)
        let model = movie(500)
        await model.retry()
        #expect(library.relatedCalls.count == 1)

        // The player's progress, or a mark on this movie, reloads the page alone.
        await model.load(quiet: true)
        await model.setWatched(true)
        await model.load(quiet: true)
        #expect(library.movieCalls == 3)
        #expect(library.relatedCalls.count == 1)

        await model.refresh()
        #expect(library.relatedCalls.count == 2)
        #expect(model.relatedRows.map(\.id) == ["collection", "similar"])
    }

    @Test func aMarkOnARelatedTitleRedrawsItsCard() async throws {
        serverAt(relatedAPILevel)
        let model = movie(500)
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { !model.relatedRows.isEmpty })
        let card = try #require(model.relatedRows.last?.items.first { !$0.watched })
        let movieCalls = library.movieCalls

        // Its long press, on this page.
        await model.setWatched(.movie(id: card.id), true)
        #expect(model.message == "Marked as watched")
        #expect(await eventually { model.relatedRows.flatMap(\.items).first { $0.id == card.id }?.watched == true })
        #expect(library.relatedCalls.count == 2)
        #expect(library.movieCalls == movieCalls, "the page itself isn't asked again")

        // A movie that isn't on the page asks nothing.
        library.announceChange(.watched(.movie, ids: [9_999], watched: nil))
        try await Task.sleep(for: .milliseconds(50))
        #expect(library.relatedCalls.count == 2)
    }

    @Test func aShowMarkedElsewhereRedrawsItsCard() async throws {
        serverAt(relatedAPILevel)
        let model = show(100)
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { !model.relatedRows.isEmpty })
        let card = try #require(model.relatedRows.first?.items.first { !$0.watched })

        try await library.setShowWatched(showID: card.id, watched: true)
        #expect(await eventually { model.relatedRows.first?.items.first { $0.id == card.id }?.watched == true })
        #expect(library.relatedCalls.count == 2)
    }

    @Test func aMoviesPageFromADownloadHasNoRows() async throws {
        serverAt(relatedAPILevel)
        let downloads = FakeDownloadRepository()
        let model = MovieModel(library: library, playback: playback, navigator: navigator, movieID: 500, downloads: downloads)
        await model.retry()
        #expect(!model.relatedRows.isEmpty)

        library.failure = APIError(status: nil, message: "Couldn’t reach the server.")
        await model.load(quiet: true)
        #expect(model.offline)
        #expect(model.relatedRows.isEmpty, "with no server its cards would open nothing")
    }

    // MARK: Cards

    @Test func aCardOpensItsTitlesPageOverThisOne() async throws {
        serverAt(relatedAPILevel)
        navigator.open(.movie(id: 500))
        let model = movie(500)
        await model.retry()
        let card = try #require(model.relatedRows.first?.items.first)

        model.open(card)
        #expect(navigator.path(for: .home) == [.movie(id: 500), .movie(id: card.id)])

        let show = show(100)
        await show.retry()
        let other = try #require(show.relatedRows.first?.items.first)
        show.open(other)
        #expect(navigator.path(for: .home).last == .show(id: other.id))
    }

    // MARK: The rows a reply gives

    @Test func onlyRowsOfCardsWithCardsInThemAreDrawnAndNoneHasSeeAll() {
        let item = FakeData.movies[1]
        let reply = [
            ScreenRow(id: "collection", style: .poster, title: "Carrow Coast Collection", items: [item],
                      seeAll: .library(id: 1, sort: .released, filter: nil, genre: nil, decade: nil)),
            ScreenRow(id: "similar", style: .poster, title: "More like this", items: []),
            ScreenRow(id: "later", style: .continue, title: "A row from a later server", continueItems: FakeData.continueWatching),
            ScreenRow(id: "wide", style: .wide, title: "Wide", items: [item]),
        ]
        let drawn = RelatedRows.drawn(reply)
        #expect(drawn.map(\.id) == ["collection", "wide"])
        #expect(drawn.allSatisfy { $0.seeAll == nil })
    }

    @Test func aChangeTouchesTheRowsOnlyWhenItNamesOneOfTheirTitles() async throws {
        serverAt(relatedAPILevel)
        let rows = RelatedRows(.movie(id: 500), library: library)
        await rows.load()
        let shown = try #require(rows.rows.first?.items.first?.id)

        #expect(rows.touches(.watched(.movie, ids: [shown], watched: nil)))
        #expect(!rows.touches(.watched(.movie, ids: [500], watched: true)), "the page's own movie isn't in its rows")
        #expect(!rows.touches(.watched(.episode, ids: [shown], watched: true)))
        #expect(!rows.touches(.markedShow(id: shown, season: nil, watched: true)), "a show with a movie's number")
        #expect(!rows.touches(.library(id: nil)))
        #expect(!rows.touches(.unknown))
    }

    @Test func oneRequestAtATime() async throws {
        serverAt(relatedAPILevel)
        let (gate, open) = AsyncStream.makeStream(of: Void.self)
        library.relatedGate = { for await _ in gate { return } }
        let rows = RelatedRows(.show(id: 100), library: library)
        let first = Task { await rows.load() }
        let second = Task { await rows.load() }
        #expect(await eventually { library.relatedCalls.count == 1 })
        try await Task.sleep(for: .milliseconds(20))
        open.yield()
        await first.value
        await second.value
        #expect(library.relatedCalls.count == 1, "the second waits for the first's reply")
        #expect(rows.rows.map(\.id) == ["similar"])
    }
}
