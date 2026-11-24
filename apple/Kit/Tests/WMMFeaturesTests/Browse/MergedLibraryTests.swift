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
        #expect(model.otherServersLine == "Den server didn’t answer, so its titles aren’t listed.")
        #expect(model.merged?.retryable?.id == den)
        #expect(model.letters.isEmpty, "the server's letter offsets aren't the merged list's")
        let dates = items.map { $0.addedAt ?? .distantPast }
        #expect(dates == dates.sorted(by: >), "newest first across servers")
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

}
