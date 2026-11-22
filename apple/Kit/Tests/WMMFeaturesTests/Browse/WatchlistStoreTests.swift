import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// The Watchlist button, the long-press menus and Save to watchlist on the
/// fakes: one watchlist toggles and several open the picker; a change shows
/// at once and goes back when refused; a removal waits out the banner on
/// phones, so Undo sends nothing, and Apple TV removes at once.
@MainActor
@Suite(.timeLimit(.minutes(1)))
struct WatchlistStoreTests {
    /// Ninety Fathoms, which the seeded Watchlist holds, and Dead Reckoning Bay, which no watchlist does.
    let ninety = WatchlistTitle(key: .init(mediaType: .movie, tmdbID: FakeData.tmdbID(movieID: 502)), title: "Ninety Fathoms", year: 2026)
    let deadReckoning = WatchlistTitle(key: .init(mediaType: .movie, tmdbID: FakeData.tmdbID(movieID: 500)), title: "Dead Reckoning Bay", year: 2026)
    let clock = BannerClock()

    init() {
        FakeLatency.duration = .zero
    }

    /// A store over the fakes, with the banner's wait in the test's hands.
    private func store(_ repository: FakeWatchlistsRepository, confirms: Bool = true) -> WatchlistStore {
        WatchlistStore(repository: repository, confirms: confirms, sleep: clock.sleep)
    }

    @Test func withNoWatchlistTheFirstPressMakesWatchlistAndSavesToIt() async {
        let repository = FakeWatchlistsRepository(seeded: false)
        let watchlists = store(repository)
        await watchlists.refresh()
        await watchlists.press(deadReckoning, said: [])
        #expect(repository.calls == ["saveToOnly"])
        #expect(watchlists.lists.map(\.name) == ["Watchlist"])
        #expect(watchlists.buttonWords(deadReckoning.key, said: []) == "On Watchlist")
        #expect(watchlists.isSaved(deadReckoning.key, said: false), "cards elsewhere follow without a refetch")
        #expect(watchlists.banner?.text == "Saved to Watchlist")
    }

    @Test func withSeveralWatchlistsThePressOpensThePickerWithTheTitlesChecks() async throws {
        let watchlists = store(FakeWatchlistsRepository())
        await watchlists.refresh()
        await watchlists.press(ninety, said: nil, fromButton: true)
        let picker = try #require(watchlists.picker)
        #expect(picker.fromButton)
        #expect(picker.rows.map(\.name) == ["Watchlist", "Halloween", "With the kids"])
        #expect(picker.rows.map(\.checked) == [true, false, false])
        await picker.toggle(picker.rows[1])
        #expect(watchlists.buttonWords(ninety.key, said: nil) == "On 2 watchlists")
        #expect(picker.rows[1].count == "4 titles", "Halloween's count follows the check")
        #expect(watchlists.banner == nil, "the checks are the confirmation")
    }

    @Test func aCountGoneStaleOpensThePickerInsteadOfSaving() async {
        // Not loaded yet, so the store thinks there is at most one; the server has three.
        let repository = FakeWatchlistsRepository()
        let watchlists = store(repository)
        await watchlists.press(deadReckoning, said: [])
        #expect(watchlists.picker != nil)
        #expect(watchlists.lists.count == 3)
        #expect(!watchlists.isSaved(deadReckoning.key, said: false), "nothing was saved")
        #expect(watchlists.banner == nil)
    }

    @Test func aRefusedSaveGoesBackAndSaysWhy() async {
        let repository = FakeWatchlistsRepository(seeded: false)
        let watchlists = store(repository)
        await watchlists.refresh()
        repository.failNext = true
        await watchlists.press(deadReckoning, said: [])
        #expect(watchlists.buttonWords(deadReckoning.key, said: []) == "Watchlist")
        #expect(watchlists.message == "The server can’t be reached.")
        #expect(watchlists.banner == nil)
    }

    @Test func undoAfterARemovalSendsNothing() async {
        let repository = onlyWatchlist()
        let watchlists = store(repository)
        await watchlists.refresh()
        await watchlists.press(ninety, said: [FakeWatchlistsRepository.watchlist])
        #expect(watchlists.banner?.text == "Removed from Watchlist")
        #expect(!watchlists.isSaved(ninety.key, said: true), "off at once")
        await watchlists.undoBanner()
        clock.end()
        #expect(watchlists.isSaved(ninety.key, said: true))
        #expect(await eventually { clock.asleep == 0 })
        #expect(repository.calls.isEmpty)
    }

    @Test func aRemovalIsSentOnceTheBannerGoes() async {
        let repository = onlyWatchlist()
        let watchlists = store(repository)
        await watchlists.refresh()
        await watchlists.press(ninety, said: [FakeWatchlistsRepository.watchlist])
        #expect(repository.calls.isEmpty, "held while Undo is offered")
        #expect(await eventually { clock.asleep == 1 })
        clock.end()
        #expect(await eventually { repository.calls == ["removeFromAll"] })
        #expect(watchlists.banner == nil)
        #expect(repository.holding(ninety.key).isEmpty)
    }

    @Test func undoAfterASaveTakesTheTitleOffAgain() async {
        let repository = onlyWatchlist()
        let watchlists = store(repository)
        await watchlists.refresh()
        await watchlists.press(deadReckoning, said: [])
        await watchlists.undoBanner()
        #expect(repository.calls == ["saveToOnly", "remove:\(FakeWatchlistsRepository.watchlist)"])
        #expect(!watchlists.isSaved(deadReckoning.key, said: true))
    }

    @Test func appleTVRemovesAtOnceAndSaysNothing() async {
        let repository = onlyWatchlist()
        let watchlists = store(repository, confirms: false)
        await watchlists.refresh()
        await watchlists.press(ninety, said: [FakeWatchlistsRepository.watchlist])
        #expect(repository.calls == ["removeFromAll"])
        #expect(watchlists.banner == nil)
        #expect(clock.asleep == 0)
    }

    @Test(arguments: [
        (seeded: false, saved: false, words: "Add to Watchlist"),
        (seeded: true, saved: false, words: "Add to Watchlist"),
        (seeded: true, saved: true, words: "Remove from Watchlist"),
    ])
    func aCardsMenuTogglesWithOneWatchlistOrNone(_ c: (seeded: Bool, saved: Bool, words: String)) async {
        let watchlists = store(c.seeded ? onlyWatchlist() : FakeWatchlistsRepository(seeded: false))
        await watchlists.refresh()
        #expect(watchlists.menuItem(ninety.key, said: c.saved)?.words == c.words)
    }

    @Test func aCardsMenuOpensThePickerWithSeveral() async {
        let watchlists = store(FakeWatchlistsRepository())
        await watchlists.refresh()
        #expect(watchlists.menuItem(ninety.key, said: true) == .choose)
        #expect(WatchlistStore.MenuItem.choose.words == "Save to watchlist…")
    }

    @Test func newWatchlistRefusesANameInUseWithoutAsking() async throws {
        let repository = FakeWatchlistsRepository()
        let watchlists = store(repository)
        await watchlists.refresh()
        await watchlists.openPicker(deadReckoning, fromButton: false)
        let picker = try #require(watchlists.picker)
        picker.startNaming()
        #expect(!picker.canCreate, "Create waits for a name")
        picker.name = "halloween "
        await picker.create()
        #expect(picker.problem == "You already have a watchlist called Halloween.")
        #expect(!repository.calls.contains("create"))
        picker.name = "Rainy days"
        await picker.create()
        #expect(!picker.naming)
        #expect(picker.rows.last?.name == "Rainy days")
        #expect(picker.rows.last?.checked == true, "Create saves the title to it")
    }

    @Test func atTheLimitThePickerSaysSoInPlaceOfNewWatchlist() async throws {
        let repository = FakeWatchlistsRepository(seeded: false)
        for n in 1...25 { _ = try await repository.create(name: "List \(n)") }
        let watchlists = store(repository)
        await watchlists.refresh()
        await watchlists.openPicker(deadReckoning, fromButton: false)
        #expect(watchlists.picker?.atLimit == "You have 25 watchlists, the most you can make.")
    }

    @Test func aWatchlistsPageDropsARemovedTitleAtOnceAndOffersNoRefresh() async throws {
        let services = AppServices.fake(watchlists: true)
        let watchlists = store(try #require(services.watchlists as? FakeWatchlistsRepository))
        await watchlists.refresh()
        let page = MediaListModel(listID: FakeWatchlistsRepository.watchlist, rows: services.rows, library: services.library, requests: nil,
                                  navigator: Navigator(), watchlists: watchlists)
        await page.retry()
        #expect(page.isWatchlist)
        #expect(!page.canRefresh)
        #expect(page.whenLine() == nil)
        #expect(page.countsLine == "10 titles · 7 in your library")
        let first = try #require(page.entries.first { $0.inLibrary })
        await page.remove(first)
        #expect(!page.entries.contains(first))
        #expect(page.countsLine == "9 titles · 6 in your library")
        page.filter = .tv
        #expect(page.entries.allSatisfy { $0.mediaType == .tv })
    }

    /// The fakes with one watchlist: Watchlist, holding Ninety Fathoms.
    private func onlyWatchlist() -> FakeWatchlistsRepository {
        let repository = FakeWatchlistsRepository(seeded: false)
        repository.seedOnlyWatchlist(holding: ninety)
        return repository
    }
}

/// The banner's wait, ended by the test: nothing here waits on the clock.
@MainActor
final class BannerClock {
    private var waiting: [CheckedContinuation<Void, Never>] = []
    /// Banners waiting to go.
    var asleep: Int { waiting.count }

    func sleep(_ duration: Duration) async {
        await withCheckedContinuation { waiting.append($0) }
    }

    /// Every banner's time is up.
    func end() {
        let ended = waiting
        waiting = []
        for continuation in ended { continuation.resume() }
    }
}
