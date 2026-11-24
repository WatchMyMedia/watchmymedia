import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Titles from another server signed in to, opened in place and played from there, without switching (one card). On the fakes: Living room server in use, Cabin server with a
/// library of its own (Harbor Lights and Ninety Fathoms on both, The Harbor
/// Choir only there), and Den server, which doesn't answer.
@MainActor
@Suite struct OneCardInPlaceTests {
    let services = AppServices.fake(several: true, redesign: true)
    let navigator = Navigator()
    let defaults = UserDefaults(suiteName: "one-card-\(UUID().uuidString)")!
    var oneCard: OneCardContext { OneCardContext(services: services, choices: CopyChoices(defaults: defaults)) }
    private let cabin = FakeServers.cabin.id
    private let den = FakeServers.den.id

    init() { FakeLatency.duration = .zero }

    @Test func titlesAreTheSameByTMDBIDAndEpisodesByTheirShowsSeasonAndNumber() {
        let movie = BrowseItem(kind: .movie, id: 1, libraryID: 1, title: "A", tmdbID: 603)
        #expect(TitleKey(movie) == .movie(tmdbID: 603))
        #expect(TitleKey(BrowseItem(kind: .show, id: 1, libraryID: 2, title: "B", tmdbID: 603)) == .show(tmdbID: 603), "a show never matches a movie")
        #expect(TitleKey(BrowseItem(kind: .movie, id: 1, libraryID: 1, title: "C")) == nil, "a title TMDB didn't match is never merged")
        #expect(TitleKey(BrowseItem(kind: .unmatched, id: 1, libraryID: 1, title: "D", tmdbID: 5)) == nil)
        let episode = EpisodeCard(episodeID: 9, showID: 1, showTitle: "S", season: 2, episode: 4, title: "E", hasFile: true, fileID: 3, isNew: false, showTMDBID: 77)
        #expect(TitleKey(episode) == .episode(showTMDBID: 77, season: 2, number: 4))
        let resume = ContinueItem(kind: .episode, title: "S", fileID: 3, season: 2, episode: 4, positionMs: 1, durationMs: 2, tmdbID: 77)
        #expect(TitleKey(resume) == TitleKey(episode))
    }

    @Test func aServerAwayFromHomeIsOneReachedOverTheInternet() {
        #expect(!isAwayFromHome(URL(string: "http://192.168.1.20:27587")!))
        #expect(!isAwayFromHome(URL(string: "http://nas.local:27587")!))
        #expect(!isAwayFromHome(URL(string: "http://127.0.0.1:27650")!))
        #expect(isAwayFromHome(URL(string: "https://cabin.example.net")!))
        #expect(cardServerShortName("Cabin server") == "Cabin")
        #expect(cardServerShortName("Media") == "Media")
    }

    @Test func aTitleLinkFromAnotherServerOpensInPlace() async throws {
        let switcher = ServersModel(servers: services.servers, navigator: navigator)
        let link = DeepLink.movie(id: 804).url(publishedFor: cabin)
        await switcher.open(link, library: services.library, libraryOn: { services.on($0).library })
        #expect(navigator.path(for: .home) == [.onServer(cabin, .movie(id: 804))])
        #expect(services.servers.current?.server.id == FakeServers.livingRoom.id, "no switch")

        let live = DeepLink.liveRecordings.url(publishedFor: cabin)
        await switcher.open(live, library: services.library, libraryOn: { services.on($0).library })
        #expect(switcher.note == "That’s for Cabin server. Switch to it in Servers to open it.")
    }

    @Test func aCardFromAnotherServerOpensAndPlaysThere() {
        var item = BrowseItem(kind: .show, id: 701, libraryID: 2, title: "Harbor Lights")
        item.server = cabin
        #expect(item.cardID == "show-701@\(cabin)", "two servers' ids can be equal")
        #expect(item.watchTarget == .onServer(cabin, .show(id: 701)))
        navigator.open(item)
        #expect(navigator.path(for: .home) == [.onServer(cabin, .show(id: 701))])
        var episode = EpisodeCard(episodeID: 9, showID: 701, showTitle: "Harbor Lights", season: 2, episode: 5, title: "Slack Water", hasFile: true, fileID: 6_701_025, isNew: false)
        episode.server = cabin
        #expect(episode.playRequest?.serverID == cabin)
        #expect(episode.route == .onServer(cabin, .episode(showID: 701, season: 2, id: 9)))
    }

}
