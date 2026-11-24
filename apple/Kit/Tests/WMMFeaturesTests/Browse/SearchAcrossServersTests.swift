import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Search across the servers a device is signed in to: every one asked at
/// once, the server in use's rows first and the others' added after them, a
/// slow one named and then given up on, a failed one in one line, a
/// keystroke cancelling the calls, a title on several servers listed once,
/// and a result on another server opening in place without a switch.
@MainActor
@Suite struct SearchAcrossServersTests {
    let servers = FakeServerRepository(several: true)
    let library = TestLibrary()
    let navigator = Navigator()
    let time = ManualTime()
    /// A wall clock the test moves, for the half minute a failed server is left alone.
    final class Clock { var now = Date(timeIntervalSince1970: 1_800_000_000) }
    let clock = Clock()
    let switcher: ServersModel
    let model: SearchModel

    private let cabin = SearchSource(id: FakeServers.cabin.id, name: "Cabin server")
    private let den = SearchSource(id: FakeServers.den.id, name: "Den server")

    init() {
        FakeLatency.duration = .zero
        library.fake.servers = servers
        navigator.select(.search)
        switcher = ServersModel(servers: servers, navigator: navigator)
        let time = time, clock = clock
        model = SearchModel(library: library, navigator: navigator, debounce: .zero, servers: servers, switcher: switcher,
                            sleep: { try await time.sleep($0) }, now: { clock.now })
    }

    /// Runs the search and lets every server answer that is going to without the clock moving.
    private func search(_ text: String) async {
        model.setQuery(text)
        await model.pending?.value
        await time.settle()
    }

    private func listing() throws -> SearchListing {
        guard case .results(let listing) = model.phase else { throw Missing() }
        return listing
    }

    private struct Missing: Error {}

    // MARK: Merging

    @Test func everyServerIsAskedAtOnceEachForItself() async throws {
        await search("harbor")
        #expect(library.searches == ["harbor"], "the server in use, the usual way")
        #expect(library.otherSearches.map(\.server) == [FakeServers.cabin.id, FakeServers.den.id], "the others, each through its own API")
        #expect(library.otherSearches.allSatisfy { $0.text == "harbor" })
        #expect(model.serverCount == 3)
        #expect(model.acrossServers)
    }

    @Test func theServerInUseComesFirstAndTheOthersAreAddedAfterItInEachGroup() async throws {
        await search("harbor")
        let listing = try listing()
        // Harbor Lights is on both servers (the same TMDB id): one row, the server in use's, naming both.
        #expect(listing.shows.map(\.title) == ["Harbor Lights"])
        #expect(listing.shows.map(\.server) == [nil], "drawn from the server in use's copy")
        #expect(listing.shows.map(\.alsoOn) == [[cabin]])
        #expect(listing.shows.map(\.id) == ["/show/100"], "it keeps its own server's id")
        #expect(model.whereLine(listing.shows[0]) == "On Living room and Cabin")
        #expect(listing.movies.map(\.title) == ["Harbor Nights", "The Harbor Choir"])
        #expect(listing.movies.map(\.server) == [cabin, cabin])
        #expect(model.whereLine(listing.movies[0]) == "Cabin server", "a title only another server has names it")
        #expect(listing.episodes.first?.server == nil)
        #expect(listing.episodes.last?.server == cabin)
        #expect(model.results?.shows.map(\.title) == ["Harbor Lights"], "the server in use's own are kept apart, for marking watched")
        #expect(model.resultsFor == "harbor")
        #expect(model.recentQueries == ["harbor"])
    }

    @Test func anAnswerThatComesBeforeTheServerInUsesWaitsForIt() async throws {
        let gate = Gate()
        library.searchGate = gate
        model.setQuery("harbor")
        await time.settle()
        #expect(library.otherSearches.count == 2, "asked while the server in use is still out")
        #expect(model.phase == .loading, "nothing draws before the server in use's")
        gate.open()
        await model.pending?.value
        await time.settle()
        let listing = try listing()
        #expect(listing.shows.map(\.server) == [nil])
        #expect(listing.shows.map(\.alsoOn) == [[cabin]])
    }

    @Test func rowsOnScreenNeverMoveWhenASlowerServerAnswers() async throws {
        let gate = Gate()
        library.otherGates[FakeServers.cabin.id] = gate
        await search("harbor")
        let before = try listing()
        #expect(before.shows.map(\.server) == [nil], "Cabin server hasn't answered")
        #expect(before.movies.isEmpty)

        gate.open()
        await time.settle()
        let after = try listing()
        #expect(Array(after.shows.map(\.id).prefix(before.shows.count)) == before.shows.map(\.id), "what was there stays where it was")
        #expect(after.shows.map(\.server) == [nil])
        #expect(after.shows.map(\.alsoOn) == [[cabin]], "Cabin's copy joins the row already there")
        #expect(after.movies.map(\.server) == [cabin, cabin])
    }

    /// The order is the Servers list's, whichever answers first: a server's
    /// rows wait for every server before it in the list.
    @Test func aServerLaterInTheListWaitsForTheOnesBeforeIt() async throws {
        let gate = Gate()
        library.otherGates[FakeServers.cabin.id] = gate
        library.otherAnswers[FakeServers.den.id] = .success(SearchResults(movies: [BrowseItem(kind: .movie, id: 9, libraryID: 1, title: "Harbor Fog")]))
        await search("harbor")
        #expect(try listing().movies.isEmpty, "Den server answered, but Cabin server comes before it and is still out")

        gate.open()
        await time.settle()
        #expect(try listing().movies.map(\.title) == ["Harbor Nights", "The Harbor Choir", "Harbor Fog"])
        #expect(try listing().movies.map(\.server) == [cabin, cabin, den])
    }

    @Test func aServerThatGivesUpLetsTheOnesAfterItIn() async throws {
        library.otherGates[FakeServers.cabin.id] = Gate()
        library.otherAnswers[FakeServers.den.id] = .success(SearchResults(movies: [BrowseItem(kind: .movie, id: 9, libraryID: 1, title: "Harbor Fog")]))
        await search("harbor")
        #expect(try listing().movies.isEmpty)
        await time.advance(byMs: 4_000)
        #expect(try listing().movies.map(\.server) == [den], "Cabin server's four seconds are up")
        #expect(model.unlisted.map(\.id) == [FakeServers.cabin.id])
    }

    @Test func aResultOnAnotherServerGetsItsPictureFromThatServer() async throws {
        await search("harbor")
        let listing = try listing()
        let near = try #require(listing.shows.first), far = try #require(listing.movies.last)
        #expect(library.imageURL("/p.jpg", size: .w342, on: far.server)?.host() == "srv-cabin.example")
        #expect(library.imageURL("/p.jpg", size: .w342, on: near.server) == library.imageURL("/p.jpg", size: .w342))
    }

    // MARK: Slow and failed servers

    @Test func aSlowServerIsNamedAfterASecondAndGivenUpOnAfterFour() async throws {
        library.otherGates[FakeServers.cabin.id] = Gate()
        await search("harbor")
        #expect(model.stillAsking == nil, "not yet a second")
        await time.advance(byMs: 1_000)
        #expect(model.stillAsking == "Still asking Cabin server…")
        #expect(try listing().shows.count == 1, "the server in use's rows are up meanwhile")

        await time.advance(byMs: 3_000)
        #expect(model.stillAsking == nil)
        #expect(library.otherCancelled == [FakeServers.cabin.id], "its call is given up on")
        let line = try #require(model.unlisted.first { $0.id == FakeServers.cabin.id })
        #expect(line.line == "Cabin server didn’t answer, so its titles aren’t listed.")
        #expect(line.canRetry)
    }

    @Test func aServerThatFailsGetsOneLineAndIsLeftAloneForHalfAMinute() async throws {
        await search("harbor")
        // Den server doesn't answer.
        #expect(model.unlisted.map(\.line) == ["Den server didn’t answer, so its titles aren’t listed."])
        #expect(model.unlisted.first?.canRetry == true)
        #expect(try listing().movies.count == 2, "the others' titles are listed all the same")

        // Typing on doesn't ask it again, so no timeouts queue up; its line stays.
        clock.now += 10
        await search("harbor l")
        #expect(library.otherSearches.filter { $0.server == FakeServers.den.id }.count == 1)
        #expect(library.otherSearches.filter { $0.server == FakeServers.cabin.id }.count == 2, "Cabin server is asked each time")
        #expect(model.unlisted.map(\.id) == [FakeServers.den.id])

        // Half a minute on, it is asked again.
        clock.now += 25
        await search("harbor li")
        #expect(library.otherSearches.filter { $0.server == FakeServers.den.id }.count == 2)
    }

    @Test func tryAgainOnAServersLineAsksThatServerAgainAndAddsItsRows() async throws {
        library.otherAnswers[FakeServers.den.id] = .failure(APIError(status: nil, message: "Couldn’t reach the server."))
        await search("harbor")
        let line = try #require(model.unlisted.first)
        let before = try listing()

        library.otherAnswers[FakeServers.den.id] = .success(SearchResults(movies: [BrowseItem(kind: .movie, id: 9, libraryID: 1, title: "Harbor Fog")]))
        model.retry(line)
        await time.settle()
        #expect(model.unlisted.isEmpty)
        let after = try listing()
        #expect(Array(after.movies.prefix(before.movies.count)) == before.movies)
        #expect(after.movies.last?.title == "Harbor Fog")
        #expect(after.movies.last?.server == den)
        #expect(library.searches == ["harbor"], "the server in use wasn't asked again")
    }

    @Test func aServerSignedOutOfOrTooOldIsNotAskedAndSaysWhy() async throws {
        // Signed out of Den server; Cabin server was downgraded below what this app works with.
        try await servers.select(FakeServers.den)
        await FakeAuthRepository(servers: servers).signOut()
        var old = FakeServers.cabin
        old.apiLevel = 0
        servers.remember(SavedServer(server: old, userName: "Sam", signedIn: true, lastUsedAt: .distantPast))
        try await servers.select(FakeServers.officeMini)
        _ = try await FakeAuthRepository(servers: servers).signIn(name: "Sam", password: "password")
        try await servers.select(FakeServers.livingRoom)

        await search("harbor")
        #expect(library.otherSearches.map(\.server) == [FakeServers.officeMini.id], "only the one signed in to and new enough")
        #expect(model.serverCount == 2)
        #expect(Set(model.unlisted.map(\.line)) == [
            "You’re signed out of Den server here, so its titles aren’t listed.",
            "Cabin server is too old for this app, so its titles aren’t listed.",
        ])
        #expect(model.unlisted.allSatisfy { !$0.canRetry })
    }

    @Test func whenTheServerInUseFailsTheOthersAreStillListed() async throws {
        library.failure = APIError(status: 500, message: "Search is unavailable.")
        await search("harbor")
        let listing = try listing()
        #expect(listing.shows.map(\.server) == [cabin])
        #expect(model.unlisted.first?.line == "Living room server didn’t answer, so its titles aren’t listed.")
        #expect(model.error == nil)
    }

    @Test func whenNoServerAnswersTheSearchFailed() async throws {
        library.failure = APIError(status: 500, message: "Search is unavailable.")
        library.otherAnswers[FakeServers.cabin.id] = .failure(APIError(status: nil, message: "Couldn’t reach the server."))
        await search("harbor")
        #expect(model.phase == .failed("Search is unavailable."))

        // Try again asks them all, the ones that failed a moment ago too.
        library.failure = nil
        library.otherAnswers = [:]
        await model.retry()
        await time.settle()
        #expect(try listing().movies.count == 2)
        #expect(library.otherSearches.filter { $0.server == FakeServers.den.id }.count == 2)
    }

    @Test func nothingAnywhereIsNoMatchesOnEveryServer() async throws {
        await search("zzzz")
        #expect(model.phase == .noMatches("zzzz"))
        #expect(model.noMatchesWords("harbr").title == "No matches on your 3 servers")
        #expect(model.noMatchesWords("harbr").message == "Nothing is called “harbr” on any of them. Check the spelling, or try fewer words.")
        #expect(model.idleWords.title == "Search your servers")
        #expect(model.idleWords.message == "Find a show, a movie or an episode on any of your 3 servers.")
    }

    @Test func nothingOnTheServerInUseWaitsForTheOthersBeforeSayingNoMatches() async throws {
        let gate = Gate()
        library.otherGates[FakeServers.cabin.id] = gate
        await search("nights")
        #expect(model.phase == .loading, "Cabin server may still have it")
        gate.open()
        await time.settle()
        #expect(try listing().movies.map(\.title) == ["Harbor Nights"])
    }

    @Test func notInYourLibraryNamesTheServerItWouldBeRequestedOn() async throws {
        let requests = FakeRequestsRepository(library: library.fake, admin: true)
        let model = SearchModel(library: library, navigator: navigator, debounce: .zero, requests: requests, servers: servers, switcher: switcher)
        model.setQuery("harbo")
        await model.pending?.value
        await model.pendingRequestables?.value
        #expect(!model.notInLibrary.isEmpty)
        #expect(model.notInLibraryDetail == "\(model.notInLibrary.count) · Living room server")
    }

    // MARK: A keystroke

    @Test func aKeystrokeCancelsEveryCallStillOut() async throws {
        let gate = Gate()
        library.otherGates[FakeServers.cabin.id] = gate
        await search("harbor")
        #expect(gate.waiting == 1)

        model.setQuery("harbor n")
        await time.settle()
        #expect(library.otherCancelled.contains(FakeServers.cabin.id), "the call for the old words was cancelled")
        await model.pending?.value
        // The answer to the old words, were it to arrive, is not listed for the new.
        gate.open()
        await time.settle()
        #expect(model.resultsFor == "harbor n")
        #expect(try listing().movies.map(\.title) == ["Harbor Nights"])
        #expect(try listing().shows.isEmpty)
    }

    @Test func clearingTheFieldCancelsTheCallsAndClearsEveryServersRows() async throws {
        library.otherGates[FakeServers.cabin.id] = Gate()
        await search("harbor")
        model.clear()
        await time.settle()
        #expect(model.phase == .idle)
        #expect(model.listing.isEmpty)
        #expect(model.unlisted.isEmpty)
        #expect(library.otherCancelled == [FakeServers.cabin.id])
    }

    // MARK: Opening a result

    @Test func aResultOnTheServerInUseOpensAsItDid() async throws {
        await search("harbor")
        model.open(try #require(try listing().shows.first))
        #expect(navigator.path(for: .search) == [.show(id: 100)])
        #expect(servers.current?.server.id == FakeServers.livingRoom.id)
    }

    @Test func aResultOnAnotherServerOpensInPlaceWithoutASwitch() async throws {
        await search("harbor")
        let movie = try #require(try listing().movies.first { $0.server == cabin })
        model.open(movie)
        #expect(navigator.path(for: .search) == [.onServer(FakeServers.cabin.id, .movie(id: movie.item.id))], "its page, from Cabin server")
        #expect(servers.current?.server.id == FakeServers.livingRoom.id, "the app stays where it is")
        #expect(switcher.note == nil)
    }

    @Test func aTitleOnSeveralServersOpensTheServerInUsesCopy() async throws {
        await search("harbor")
        let show = try #require(try listing().shows.first)
        #expect(show.alsoOn == [cabin])
        model.open(show)
        #expect(navigator.path(for: .search) == [.show(id: 100)])
    }

    // MARK: The visit

    /// A visit begins each time Search comes on screen. What the other
    /// servers said of which server they are is forgotten when it leaves;
    /// back on it, the servers whose titles are listed are asked again at
    /// once, so their pictures go on loading, and nothing is searched again.
    @Test func backOnSearchTheServersListedAreAskedAgainAndNothingIsSearched() async throws {
        await search("harbor")
        #expect(library.visitsEnded == 0)
        #expect(library.asked.isEmpty, "the first time on screen asks nothing by itself")
        model.visitEnded()
        model.visitEnded()
        #expect(library.visitsEnded == 1, "said once")
        #expect(!library.answerStands(serverID: FakeServers.cabin.id))

        model.visitBegan()
        for task in model.reasking.values { await task.value }
        #expect(library.asked == [FakeServers.cabin.id], "the one whose titles are showing; Den server listed none")
        #expect(library.answerStands(serverID: FakeServers.cabin.id))
        #expect(library.searches == ["harbor"], "nothing is searched again")
        #expect(library.otherSearches.count == 2)
        #expect(try listing().movies.map(\.server) == [cabin, cabin], "the rows stay as they were")

        model.visitBegan()
        #expect(library.asked.count == 1, "only after a visit ended")
    }

    @Test func comingBackWithNothingListedAsksNothing() async throws {
        model.visitEnded()
        model.visitBegan()
        #expect(model.reasking.isEmpty)
        #expect(library.asked.isEmpty)
        #expect(library.searches.isEmpty)
        #expect(library.visitsEnded == 1)
    }

    /// The half minute a failed server is left alone is that visit's: on
    /// the next visit it is asked again.
    @Test func aServerThatFailedIsAskedAgainOnTheNextVisit() async throws {
        await search("harbor")
        #expect(library.otherSearches.filter { $0.server == FakeServers.den.id }.count == 1)
        await search("harbor l")
        #expect(library.otherSearches.filter { $0.server == FakeServers.den.id }.count == 1, "left alone on this visit")
        model.visitEnded()
        model.visitBegan()
        await search("harbor li")
        #expect(library.otherSearches.filter { $0.server == FakeServers.den.id }.count == 2)
    }

    /// A server that says, asked which server it is, that it is now too old
    /// for this app: left out in the too-old words, and not asked again on
    /// this visit, whatever is typed.
    @Test func aServerThatTurnsOutTooOldIsNotAskedAgainOnThisVisit() async throws {
        library.otherAnswers[FakeServers.cabin.id] = .failure(ServerTooOldError(server: FakeServers.cabin))
        await search("harbor")
        #expect(model.unlisted.first { $0.id == FakeServers.cabin.id }?.line == "Cabin server is too old for this app, so its titles aren’t listed.")
        #expect(model.unlisted.first { $0.id == FakeServers.cabin.id }?.canRetry == false)
        await search("harbor l")
        #expect(library.otherSearches.filter { $0.server == FakeServers.cabin.id }.count == 1, "not asked for every keystroke")
        #expect(model.unlisted.contains { $0.id == FakeServers.cabin.id && !$0.canRetry })

        // A new visit asks it again: it may have been updated.
        library.otherAnswers[FakeServers.cabin.id] = nil
        model.visitEnded()
        model.visitBegan()
        await search("harbor li")
        #expect(library.otherSearches.filter { $0.server == FakeServers.cabin.id }.count == 2)
        #expect(!model.unlisted.contains { $0.id == FakeServers.cabin.id })
    }

    @Test func anEpisodeOnAnotherServerOpensInPlaceToo() async throws {
        await search("harbour run")
        let episode = try #require(try listing().episodes.first { $0.server == cabin })
        model.open(episode)
        #expect(navigator.path(for: .search) == [.onServer(FakeServers.cabin.id, episode.episode.route)])
        #expect(servers.current?.server.id == FakeServers.livingRoom.id)
    }

    // MARK: One server

    @Test func withOneServerSignedInSearchIsAsItWas() async throws {
        // The sample's servers: signed in on one, signed out of another, one too old.
        let servers = FakeServerRepository()
        let library = TestLibrary()
        library.fake.servers = servers
        let model = SearchModel(library: library, navigator: navigator, debounce: .zero, servers: servers)
        #expect(!model.acrossServers)
        #expect(model.idleWords.title == "Search your libraries")
        model.setQuery("harbor")
        await model.pending?.value
        #expect(library.otherSearches.isEmpty)
        #expect(model.unlisted.isEmpty, "no lines for servers that aren't searched")
        guard case .results(let listing) = model.phase else { Issue.record("no results"); return }
        #expect(listing.shows.map(\.server) == [nil])
        #expect(model.noMatchesWords("harbr").title == "No matches")
    }
}
