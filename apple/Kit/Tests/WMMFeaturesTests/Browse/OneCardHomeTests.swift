import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Home's rows and Continue watching filled from every server, and the Top Shelf's (one card). On the fakes: Living room server in use, Cabin server with a
/// library of its own (Harbor Lights and Ninety Fathoms on both, The Harbor
/// Choir only there), and Den server, which doesn't answer.
@MainActor
@Suite struct OneCardHomeTests {
    let services = AppServices.fake(several: true, redesign: true)
    let navigator = Navigator()
    let defaults = UserDefaults(suiteName: "one-card-\(UUID().uuidString)")!
    var oneCard: OneCardContext { OneCardContext(services: services, choices: CopyChoices(defaults: defaults)) }
    private let cabin = FakeServers.cabin.id
    private let den = FakeServers.den.id

    init() { FakeLatency.duration = .zero }

    private func home() async -> HomeModel {
        let model = HomeModel(library: services.library, auth: services.auth, navigator: navigator, rows: services.rows, servers: services.servers, oneCard: oneCard)
        await model.retry()
        return model
    }

    private func row(_ model: HomeModel, _ id: String) -> ScreenRow? {
        guard let home = model.state.value else { return nil }
        return home.rows?.first { $0.id == id } ?? MergedHomeRow(rawValue: id).flatMap { home.cards($0) }
    }

    @Test func continueWatchingIsEveryServersOneCardATitleByWhenLastPlayed() async throws {
        let model = await home()
        #expect(await eventually { row(model, "continue")?.continueItems.contains { $0.server == cabin } == true })
        let items = try #require(row(model, "continue")?.continueItems)
        let dates = items.compactMap(\.updatedAt)
        #expect(dates == dates.sorted(by: >), "by when each was last played, on any server")
        let fathoms = try #require(items.first { $0.title == "Ninety Fathoms" })
        #expect(fathoms.server == cabin, "Resume plays from where it was last played")
        #expect(items.filter { $0.title == "Ninety Fathoms" }.count == 1)
        #expect(await eventually { model.whereLine(fathoms) == "from Cabin" }, "the server in use has it too")
        let nights = try #require(items.first { $0.title == "Harbor Nights" })
        #expect(model.whereLine(nights) == "On Cabin", "only Cabin server has it")
        model.resume(fathoms)
        #expect(navigator.playing?.serverID == cabin)

        // A copy chosen on this device for the title plays instead, from the same place.
        CopyChoices(defaults: defaults).choose("\(FakeServers.livingRoom.id)/9902", for: .movie(tmdbID: FakeData.tmdbID(movieID: 502)))
        model.resume(fathoms)
        #expect(navigator.playing?.serverID == nil)
        #expect(navigator.playing?.fileID == 9902)
        #expect(navigator.playing?.startMs == fathoms.positionMs)
    }

    @Test func recentlyAddedIsFilledFromEveryServerOneCardATitle() async throws {
        let model = await home()
        #expect(await eventually { row(model, "recentlyAddedMovies")?.items.contains { $0.server == cabin } == true })
        let items = try #require(row(model, "recentlyAddedMovies")?.items)
        #expect(items.filter { $0.title == "Ninety Fathoms" }.count == 1, "one card for a title on two servers")
        #expect(items.first { $0.title == "Ninety Fathoms" }?.server == nil, "the server in use's card")
        let choir = try #require(items.first { $0.title == "The Harbor Choir" })
        #expect(model.whereLine(choir) == "On Cabin")
        model.open(choir)
        #expect(navigator.path(for: .home) == [.onServer(cabin, .movie(id: 804))], "opened in place, from Cabin server")
    }

    @Test func aServerThatDoesntAnswerEndsTheRowsWithTryAgain() async throws {
        let model = await home()
        #expect(await eventually { model.unanswered.map(\.server.id) == [den] })
        #expect(model.unanswered.first?.line == "Den server didn’t answer, so its titles aren’t on Home.")
        let continuing = try #require(row(model, "continue"))
        #expect(model.isMerged(continuing))
        #expect(model.firstMergedRowID == "continue")
        await model.retry(try #require(model.unanswered.first?.server))
        #expect(model.unanswered.map(\.server.id) == [den], "still not answering")
    }

    @Test func markingATitleOnTwoServersMarksItOnEach() async throws {
        let model = await home()
        #expect(await eventually { row(model, "continue")?.continueItems.contains { $0.server == cabin } == true })
        let fathoms = try #require(row(model, "continue")?.continueItems.first { $0.title == "Ninety Fathoms" })
        await model.setWatched(try #require(fathoms.watchTarget), true)
        #expect(model.message == "Marked as watched")
        let fake = try #require(services.library as? FakeLibraryRepository)
        #expect(fake.marks[.movie(id: 502)] == true, "Living room server's copy too")
    }

    @Test func theTopShelfsContinueWatchingLinksNameTheirServer() {
        var item = ContinueItem(kind: .movie, title: "Ninety Fathoms", backdropPath: "/b.jpg", fileID: 80_803, movieID: 803, positionMs: 1, durationMs: 2)
        item.server = cabin
        let home = HomeData(hero: nil, continueWatching: [item], recentlyAddedShows: [], recentlyAired: [], recentlyAddedMovies: [], recentlyReleased: [],
                            totals: LibraryTotals(movies: 0, shows: 0, episodes: 0, files: 0, totalSizeBytes: 0), lastScannedAt: nil, movieLibraryID: nil, tvLibraryID: nil)
        let plan = TopShelfPlan(home, serverID: FakeServers.livingRoom.id)
        let card = plan.sections.first?.cards.first
        #expect(card?.server == cabin, "its picture comes from Cabin server")
        #expect(card?.item.open.absoluteString.contains("server=\(cabin)") == true)
        #expect(card?.item.id == "continue-\(cabin)/80803")
    }

}
