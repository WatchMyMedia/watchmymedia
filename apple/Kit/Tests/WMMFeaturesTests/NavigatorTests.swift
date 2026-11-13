import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@MainActor
@Suite struct NavigatorTests {
    init() { FakeLatency.duration = .zero }

    @Test func openPushesOntoTheCurrentTab() {
        let nav = Navigator()
        nav.select(.tvShows)
        nav.open(.show(id: 100))
        nav.open(.movie(id: 500))
        #expect(nav.path(for: .tvShows) == [.show(id: 100), .movie(id: 500)])
        #expect(nav.path(for: .home).isEmpty)
    }

    @Test func aShowSeasonOrEpisodeIsOnePagePushedOnce() {
        let nav = Navigator()
        nav.open(.episode(showID: 100, season: 3, id: 100037))
        #expect(nav.path(for: .home) == [.episode(showID: 100, season: 3, id: 100037)], "no show page under it: Back leaves the show")

        // A show route with an episode is the same page.
        let other = Navigator()
        other.open(.show(id: 100, season: 2, episode: 100025))
        #expect(other.path(for: .home) == [.episode(showID: 100, season: 2, id: 100025)])

        let season = Navigator()
        season.open(.show(id: 100, season: 2))
        #expect(season.path(for: .home) == [.show(id: 100, season: 2)])
    }

    @Test func choosingTheShownTabGoesBackToItsRoot() {
        let nav = Navigator()
        nav.open(.movie(id: 500))
        nav.select(.search)
        #expect(nav.path(for: .home) == [.movie(id: 500)])
        nav.select(.home)
        #expect(nav.path(for: .home) == [.movie(id: 500)])
        nav.select(.home)
        #expect(nav.path(for: .home).isEmpty)
    }

    @Test func aPlayLinkIsNamedFromTheLibrary() async {
        let nav = Navigator()
        await nav.open(.play(fileID: 9001), library: FakeLibraryRepository())
        #expect(nav.playing?.title == "The Quiet Meridian")
        #expect(nav.playing?.subtitle == "S02 E04 · Drift")
    }

    @Test func launchArgumentsPickATabAndOpenALink() async {
        let nav = Navigator()
        await nav.applyLaunchArguments(["app", "-tab", "tvShows", "-open", "watchmymedia://shows/100?season=3&episode=100037"], library: FakeLibraryRepository())
        #expect(nav.tab == .tvShows)
        #expect(nav.path(for: .tvShows) == [.episode(showID: 100, season: 3, id: 100037)])
    }

    @Test func aLinkTVLinkOpensSettingsWithTheCode() async {
        let nav = Navigator()
        nav.play(PlayRequest(fileID: 1, title: "x"))
        await nav.open(.linkTV(code: "K7M4PQ"), library: FakeLibraryRepository())
        #expect(nav.playing == nil)
        #expect(nav.tab == .settings)
        #expect(nav.path(for: .settings) == [.linkTV(code: "K7M4PQ")])
    }
}

@Suite struct DeepLinkTests {
    @Test(arguments: [
        ("watchmymedia://play/42", DeepLink.play(fileID: 42)),
        ("watchmymedia://shows/7", .show(id: 7, season: nil)),
        ("watchmymedia://shows/7?season=2", .show(id: 7, season: 2)),
        ("watchmymedia://shows/7?season=2&episode=7027", .show(id: 7, season: 2, episode: 7027)),
        ("WatchMyMedia://movies/9", .movie(id: 9)),
        ("watchmymedia://link?code=K7M4PQ", .linkTV(code: "K7M4PQ")),
        ("watchmymedia://link", .linkTV(code: nil)),
        ("watchmymedia://lists/9001", .mediaList(id: 9001)),
    ])
    func parses(_ text: String, _ expected: DeepLink) throws {
        #expect(DeepLink(url: try #require(URL(string: text))) == expected)
    }

    @Test(arguments: ["https://play/42", "watchmymedia://play/abc", "watchmymedia://shows", "watchmymedia://settings", "watchmymedia://lists"])
    func refuses(_ text: String) throws {
        #expect(DeepLink(url: try #require(URL(string: text))) == nil)
    }

    @Test(arguments: [DeepLink.play(fileID: 1), .show(id: 2, season: 3), .show(id: 2, season: 3, episode: 2037), .movie(id: 4), .linkTV(code: "ABC234"), .mediaList(id: 9)])
    func roundTrips(_ link: DeepLink) {
        #expect(DeepLink(url: link.url) == link)
    }
}

@MainActor @Suite struct LiveNavigationTests {
    @Test func olderServersKeepEstablishedTabs() {
        #expect(AppTab.phoneTabs(liveTV: false) == [.home, .tvShows, .movies, .search, .settings])
        #expect(AppTab.phoneTabs(liveTV: true) == [.home, .tvShows, .movies, .liveTV, .search])
        #expect(requiredAPILevel == 1)
    }

    @Test func liveAndFileTargetsAreMutuallyExclusiveAndCloseTogether() {
        let navigator = Navigator()
        let channel = LiveChannel(id: "channel-7", number: "7.1", name: "Meadow News")
        navigator.play(.init(fileID: 9001, title: "Paper Moons"))
        navigator.playLive(.init(channel: channel))
        #expect(navigator.playing == nil)
        #expect(navigator.livePlaying?.channel == channel)
        navigator.play(.init(fileID: 9001, title: "Paper Moons"))
        #expect(navigator.livePlaying == nil)
        navigator.closePlayer()
        #expect(navigator.livePlaying == nil && navigator.playing == nil)
    }

    @Test func liveLinksKeepOpaqueChannelIDs() throws {
        let link = DeepLink.liveTV(channelID: "a07f09")
        #expect(DeepLink(url: link.url) == link)
        #expect(DeepLink(url: try #require(URL(string: "watchmymedia://live-tv"))) == .liveTV(channelID: nil))
    }

    @Test func oldServersCannotTuneFromALiveLink() async {
        let navigator = Navigator()
        await navigator.open(.liveTV(channelID: "channel-7"), library: FakeLibraryRepository(), liveTV: UnavailableLiveTVRepository())
        #expect(navigator.tab == .liveTV)
        #expect(navigator.livePlaying == nil)
    }
}

/// Links that start playback or take a tuner only when the app published
/// them for the server signed in (PLAN-APPLE-REVIEW.md §2.5, §3.4.7), as
/// android/core/model's DeepLinkTest checks the same rule.
@MainActor @Suite struct PublishedLinkTests {
    init() { FakeLatency.duration = .zero }

    private func url(_ text: String) -> URL { URL(string: text)! }

    @Test func aPublishedLinkNamesTheServerItWasPublishedFor() {
        #expect(DeepLink.publishedServer(url("watchmymedia://play/4?source=reminder&server=srv-a")) == "srv-a")
        #expect(DeepLink.publishedServer(url("watchmymedia://play/4?source=reminder")) == "")
        #expect(DeepLink.publishedServer(url("watchmymedia://play/4?server=")) == "")
        #expect(DeepLink.publishedServer(url("watchmymedia://play/4")) == nil)
        #expect(DeepLink.publishedServer(url("https://example.com/?source=reminder")) == nil)
        #expect(DeepLink.play(fileID: 4).url(publishedFor: "srv-a", source: "reminder") == url("watchmymedia://play/4?source=reminder&server=srv-a"))
    }

    @Test func aPlayLinkPlaysOnlyWhenTheAppPublishedItForTheServerSignedIn() {
        let published = DeepLink.play(fileID: 4).url(publishedFor: "srv-a")
        #expect(DeepLink.opening(published, signedInServer: "srv-a") == .play(fileID: 4))
        #expect(DeepLink.opening(published, signedInServer: "srv-b") == nil, "another server")
        #expect(DeepLink.opening(published, signedInServer: nil) == nil, "no one signed in")
        #expect(DeepLink.opening(url("watchmymedia://play/4?source=reminder"), signedInServer: "srv-a") == nil, "a published link naming no server")
        // From a web page, a QR code or another app: the file's page, with Play ready.
        #expect(DeepLink.opening(url("watchmymedia://play/4"), signedInServer: "srv-a") == .filePage(fileID: 4))
        #expect(DeepLink.opening(url("watchmymedia://play/4"), signedInServer: nil) == .filePage(fileID: 4))
        // Every other link opens as it reads.
        #expect(DeepLink.opening(url("watchmymedia://shows/100?season=3&episode=100035"), signedInServer: nil) == .show(id: 100, season: 3, episode: 100035))
        #expect(DeepLink.opening(url("watchmymedia://movies/500"), signedInServer: "srv-a") == .movie(id: 500))
        #expect(DeepLink.opening(url("watchmymedia://link?code=KQ7F2W"), signedInServer: nil) == .linkTV(code: "KQ7F2W"))
        #expect(DeepLink.opening(url("https://example.com/play/4"), signedInServer: "srv-a") == nil)
    }

    @Test func aChannelLinkTunesOnlyWhenTheAppPublishedIt() {
        let reminder = DeepLink.liveTV(channelID: "a07f09").url(publishedFor: "srv-a", source: "reminder")
        #expect(DeepLink.opening(reminder, signedInServer: "srv-a") == .liveTV(channelID: "a07f09"))
        #expect(DeepLink.opening(reminder, signedInServer: "srv-b") == nil)
        // Taking a tuner is the app's to do: anyone else's link opens the guide.
        #expect(DeepLink.opening(url("watchmymedia://live-tv/a07f09"), signedInServer: "srv-a") == .liveTV(channelID: nil))
        #expect(DeepLink.opening(url("watchmymedia://live-tv/recordings"), signedInServer: "srv-a") == .liveRecordings)
    }

    @Test func aPlayLinkFromOutsideOpensTheEpisodesPageAndPlaysNothing() async throws {
        let nav = Navigator()
        await nav.open(.filePage(fileID: 9001), library: FakeLibraryRepository())
        #expect(nav.playing == nil)
        let route = try #require(nav.path(for: .home).single)
        guard case .episode(_, let season, _) = route else { Issue.record("not an episode page: \(route)"); return }
        #expect(season == 2)
    }

    @Test func aPlayLinkFromOutsideForAMovieOpensTheMoviesPage() async {
        let nav = Navigator()
        await nav.open(.filePage(fileID: 9900), library: FakeLibraryRepository())
        #expect(nav.playing == nil)
        #expect(nav.path(for: .home) == [.movie(id: 500)])
    }

    @Test func theAppsOwnLaunchArgumentsStillPlay() async {
        let nav = Navigator()
        await nav.applyLaunchArguments(["app", "-open", "watchmymedia://play/9101"], library: FakeLibraryRepository())
        #expect(nav.playing?.fileID == 9101)
    }
}
