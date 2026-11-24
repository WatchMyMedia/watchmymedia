import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Movies and TV Shows with every server's titles, merged a page at a time (one card). On the fakes: Living room server in use, Cabin server with a
/// library of its own (Harbor Lights and Ninety Fathoms on both, The Harbor
/// Choir only there), and Den server, which doesn't answer.
@MainActor
@Suite struct MergedLibraryTests {
    let services = AppServices.fake(several: true, redesign: true)
    let navigator = Navigator()
    let defaults = UserDefaults(suiteName: "one-card-\(UUID().uuidString)")!
    var oneCard: OneCardContext { OneCardContext(services: services, choices: CopyChoices(defaults: defaults)) }
    private let cabin = FakeServers.cabin.id
    private let den = FakeServers.den.id

    init() { FakeLatency.duration = .zero }

    @Test func moviesAreEveryServersOneCardATitleWithTheirCountsAddedUp() async throws {
        let model = LibraryModel(library: services.library, navigator: navigator, type: .movie, libraryID: FakeData.movieLibrary, oneCard: oneCard)
        await model.retry()
        #expect(await eventually { model.merged?.feeds[cabin] != nil && model.merged?.counting == false })
        let items = model.gridItems
        #expect(items.filter { $0.title == "Ninety Fathoms" }.count == 1)
        #expect(items.contains { $0.title == "The Harbor Choir" && $0.server == cabin })
        #expect(items.count == FakeData.movies.count + FakeCabin.movies.count - 1)
        #expect(model.headerCount == "\(items.count) movies", "every server's added up, less the one they share")
        #expect(model.resultLine == model.headerCount, "the line over the grid counts the same titles")
        #expect(model.otherServersLine == "Den server didn’t answer, so its titles aren’t listed.")
        #expect(model.merged?.retryable?.id == den)
        #expect(model.letters.isEmpty, "the server's letter offsets aren't the merged list's")
        let dates = items.map { $0.addedAt ?? .distantPast }
        #expect(dates == dates.sorted(by: >), "newest first across servers")
    }

    /// The line over the grid said the server in use's count ("10 movies")
    /// under a header of every server's ("12 movies"), with the grid
    /// showing all 12. Both now say every server's, "About" while one is out.
    @Test func theLineOverTheGridSaysAboutWhileAServerIsStillOut() async throws {
        let fakes = try #require(services.library as? FakeLibraryRepository)
        fakes.denWaits = .seconds(60)
        let model = LibraryModel(library: services.library, navigator: navigator, type: .movie, libraryID: FakeData.movieLibrary, oneCard: oneCard)
        let loading = Task { await model.retry() }
        defer { loading.cancel() }
        #expect(await eventually { model.merged?.feeds[cabin] != nil })
        #expect(model.merged?.counting == true, "Den server is still out")
        let about = "About \(FakeData.movies.count + FakeCabin.movies.count - 1) movies"
        #expect(model.headerCount == about)
        #expect(model.resultLine == about)
    }

    /// TV Shows' line has no episode count with other servers merged in: a
    /// show on two servers would count its episodes twice.
    @Test func showsCountEveryServersShowsOverTheGrid() async throws {
        let model = LibraryModel(library: services.library, navigator: navigator, type: .tv, libraryID: FakeData.tvLibrary, oneCard: oneCard)
        await model.retry()
        #expect(await eventually { model.merged?.feeds[cabin] != nil && model.merged?.counting == false })
        let count = model.gridItems.count
        #expect(count > FakeData.shows.count, "Cabin server's own shows are in")
        #expect(model.headerCount == "\(count) shows")
        #expect(model.resultLine == "\(count) shows")
    }

    @Test func theMergedGridSortsAsTheServerDoes() {
        #expect(MergedGrid.sortTitle("The Harbor Choir") == "harbor choir")
        let apple = BrowseItem(kind: .movie, id: 1, libraryID: 1, title: "An Apple"), banana = BrowseItem(kind: .movie, id: 2, libraryID: 1, title: "Banana")
        #expect(MergedGrid.before(apple, banana, sort: .title))
        var older = apple, newer = banana
        older.addedAt = Date(timeIntervalSince1970: 1)
        newer.addedAt = Date(timeIntervalSince1970: 2)
        #expect(MergedGrid.before(newer, older, sort: .added), "newest first")
    }

    @Test func aGridCardIsKnownByWhatTheGridScrollsTo() {
        // A letter jump and Page Down scroll to the server in use's id
        // (scrollTargetID); keyed by cardID alone, they found nothing to scroll to.
        let own = BrowseItem(kind: .movie, id: 7, libraryID: 1, title: "Cold Harbour Road")
        #expect(own.gridID == AnyHashable(7))
        #expect(own.gridID == AnyHashable(AnyHashable(7)), "as a scroll view compares it")
        var theirs = own
        theirs.server = cabin
        #expect(theirs.gridID != own.gridID, "another server's card with the same id is another card")
        #expect(theirs.gridID == AnyHashable(theirs.cardID))
    }

}
