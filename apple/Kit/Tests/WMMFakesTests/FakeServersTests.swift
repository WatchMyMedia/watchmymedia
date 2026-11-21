import Foundation
import Testing
import WMMFakes
import WMMModel

/// The fakes with several servers: each has a library of its own, so a test
/// can tell whose title it opened, and one doesn't answer.
@MainActor
@Suite struct FakeServersTests {
    init() { FakeLatency.duration = .zero }

    @Test func eachFakeServerHasItsOwnLibrary() async throws {
        let services = AppServices.fake(several: true)
        let library = services.library
        #expect(services.servers.saved.map(\.server.name) == ["Living room server", "Cabin server", "Den server"])
        #expect(services.servers.saved.allSatisfy { $0.signedIn })

        let home = try await library.home()
        #expect(home.hero?.title == "Harbor Lights")
        #expect(try await library.movie(id: 500).title == "Dead Reckoning Bay")

        try await services.servers.select(FakeServers.cabin)
        #expect(services.auth.user?.name == "Sam", "signed in there already")
        let cabin = try await library.home()
        #expect(cabin.hero?.title == "Harbour Run")
        #expect(cabin.recentlyAddedShows.map(\.title) == ["Harbour Run", "Harbor Lights", "Tideline", "Lowland Radio"])
        #expect(try await library.movie(id: 800).title == "Harbor Nights")
        #expect(try await library.show(id: 700).title == "Harbour Run")
        #expect(try await library.season(showID: 700, number: 1).episodes.count == 6)
        // Living room server's ids mean nothing here.
        await #expect(throws: APIError.self) { _ = try await library.movie(id: 500) }
        await #expect(throws: APIError.self) { _ = try await library.show(id: 100) }
        let movies = try await library.items(ItemQuery(libraryID: FakeCabin.movieLibrary))
        #expect(Set(movies.items.map(\.title)) == ["Harbor Nights", "Safe Harbour", "The Ferry Line"])

        // A file's page and name come from the server it is on.
        let file = try #require(try await library.movie(id: 800).versions.first?.fileID)
        #expect(try await library.playRequest(fileID: file).title == "Harbor Nights")
        #expect(try await library.filePage(fileID: file) == .movie(id: 800))
    }

    @Test func eachIsSearchedForItselfWhicheverIsInUse() async throws {
        let services = AppServices.fake(several: true)
        let library = services.library
        #expect(try await library.search("harbor").shows.map(\.id) == [100])
        let cabin = try await library.search("harbor", serverID: FakeServers.cabin.id)
        #expect(cabin.shows.map(\.id) == [701], "Harbor Lights is on both, with each server's own id")
        #expect(cabin.movies.map(\.title) == ["Harbor Nights"])

        try await services.servers.select(FakeServers.cabin)
        #expect(try await library.search("harbor").shows.map(\.id) == [701])
        #expect(try await library.search("harbor", serverID: FakeServers.livingRoom.id).shows.map(\.id) == [100])
    }

    @Test func oneFakeServerDoesNotAnswer() async throws {
        let services = AppServices.fake(downloads: true, several: true)
        await #expect(throws: APIError.self) { _ = try await services.servers.probe(FakeServers.den.baseURL.absoluteString) }
        await #expect(throws: APIError.self) { _ = try await services.library.search("harbor", serverID: FakeServers.den.id) }
        #expect(services.servers.reachable == nil)
        #expect(services.downloads.elsewhere == [ServerDownloads(serverID: FakeServers.den.id, count: 3, bytes: 13_314_398_618)])

        // Chosen all the same, to watch its downloads: it is in use and nothing loads.
        try await services.servers.select(FakeServers.den)
        #expect(services.servers.reachable == false)
        await #expect(throws: APIError.self) { _ = try await services.library.home() }
    }

    @Test func aServerNotSignedInToIsNotSearched() async {
        let services = AppServices.fake()
        await #expect(throws: SignedOutError.self) { _ = try await services.library.search("harbor", serverID: FakeServers.remoteHome.id) }
        await #expect(throws: SignedOutError.self) { _ = try await services.library.search("harbor", serverID: FakeServers.cabin.id) }
    }

    @Test func theDownloadsShownFollowTheServerInUse() async throws {
        let services = AppServices.fake(downloads: true, several: true)
        #expect(services.downloads.downloads.count == 3)
        try await services.servers.select(FakeServers.cabin)
        #expect(services.downloads.downloads.isEmpty)
        #expect(services.downloads.elsewhere.map(\.serverID) == [FakeServers.livingRoom.id, FakeServers.den.id])
        try await services.servers.select(FakeServers.livingRoom)
        #expect(services.downloads.downloads.count == 3)
    }
}
