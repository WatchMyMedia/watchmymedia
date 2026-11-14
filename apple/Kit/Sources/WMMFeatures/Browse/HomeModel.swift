import Foundation
import Observation
import WMMModel

/// Home (Home.dc.html, HomeScrolled.dc.html): the hero, Continue watching,
/// Recently added shows, Recently aired, Recently added and Recently
/// released movies, and the library totals. From ``rowsAPILevel`` the rows
/// come in the person's order instead (`HomeData.rows`, PLAN-ROWS.md).
/// Refetches when the library or the rows change and on pull to refresh,
/// keeping what is on screen meanwhile.
@MainActor
@Observable
public final class HomeModel {
    public private(set) var state: Loadable<HomeData> = .loading
    public private(set) var emptyState: HomeEmptyState?
    /// A note to show for a moment: "Marked as watched", or why a refresh failed.
    public var message: String?
    /// Shown instead of navigating: an unmatched folder has nowhere to go.
    public var notice: Notice?

    @ObservationIgnored private let library: any LibraryRepository
    @ObservationIgnored private let auth: any AuthRepository
    @ObservationIgnored private let navigator: Navigator
    @ObservationIgnored private let rows: (any RowsRepository)?
    @ObservationIgnored private let changes: ScreenChanges
    @ObservationIgnored private let loads = LoadController<HomeData>()

    /// `rows` brings the person's other devices' changes to the layout and lists (rows.changed, lists.changed).
    public init(library: any LibraryRepository, auth: any AuthRepository, navigator: Navigator, rows: (any RowsRepository)? = nil,
                now: @escaping @MainActor () -> Date = { Date() }) {
        self.library = library
        self.auth = auth
        self.navigator = navigator
        self.rows = rows
        changes = ScreenChanges(now: now)
    }

    /// The signed-in user's name, for the avatar's label.
    public var userName: String? { auth.user?.name }

    /// The avatar's letter: "S".
    public var initial: String? { userName?.first.map { String($0).uppercased() } }

    public var isAdmin: Bool { auth.user?.isAdmin == true }

    public var offersSetup: Bool { isAdmin && emptyState?.offersSetup == true }

    /// The offline Home (HomeOffline.dc.html) with `downloads` to watch: Home's
    /// request failed, or, while it is still out, the server check
    /// (``ServerRepository/reachable``) found no server. That check gives up
    /// in seconds where Home's request takes 30 (PLAN-APPLE-REVIEW.md §4.1.5);
    /// the request carries on, and Home shows if it answers.
    public func showsOffline(reachable: Bool?, downloads: Bool) -> Bool {
        guard downloads else { return false }
        switch state {
        case .failed: return true
        case .loading: return reachable == false
        case .loaded: return false
        }
    }

    /// Loads Home, then reloads quietly whenever the library, the person's
    /// watch state, or their rows and lists change, until cancelled. Coming
    /// back to Home loads nothing unless something changed meanwhile
    /// (``ScreenChanges``). Scans matter only to an empty Home, which says
    /// what the scan is doing; `library.changed` brings what a scan found.
    public func run() async {
        await changes.run(
            subscribe: { merge(library.libraryChanges(), unknown: rows?.changes()) },
            hasContent: state.value != nil,
            load: { quiet in await load(quiet: quiet) },
            apply: { changes in
                let scansOnly = changes.allSatisfy { if case .scan = $0 { true } else { false } }
                if scansOnly && emptyState == nil { return }
                await load(quiet: true)
            }
        )
    }

    /// Home is arranged (``rowsAPILevel``): Edit Home is offered.
    public var canEditRows: Bool { state.value?.isArranged == true }

    public func retry() async {
        await load(quiet: false)
        changes.loaded()
    }

    /// Pull to refresh: keeps Home on screen, and says why if it fails.
    public func refresh() async {
        await load(quiet: true)
        changes.loaded()
    }

    // MARK: Taps

    public func playHero() {
        guard let home = state.value, let request = home.hero?.playRequest(episodes: home.allEpisodes) else { return }
        navigator.play(request)
    }

    public func openHero() {
        guard let route = state.value?.hero?.pageRoute else { return }
        navigator.open(route)
    }

    /// Continue watching resumes straight into the player.
    public func resume(_ item: ContinueItem) {
        navigator.play(item.playRequest)
    }

    public func openPage(of item: ContinueItem) {
        guard let route = item.pageRoute else { return }
        navigator.open(route)
    }

    public func open(_ item: BrowseItem) {
        notice = navigator.open(item)
    }

    /// A Recently aired card opens the episode's page.
    public func open(_ episode: EpisodeCard) {
        navigator.open(episode)
    }

    /// A row's See all: the list's page, or the library's tab.
    public func open(_ link: RowLink) {
        navigator.open(link, home: state.value)
    }

    /// Whether a row's See all goes anywhere: a list, or a library this Home knows.
    public func canOpen(_ link: RowLink) -> Bool {
        switch link {
        case .list: true
        case .library(let id, _, _, _, _): id == state.value?.tvLibraryID || id == state.value?.movieLibraryID
        }
    }

    public func play(_ episode: EpisodeCard) {
        guard let request = episode.playRequest else { return }
        navigator.play(request)
    }

    /// The mark's own notice reloads Home, as it does every screen showing it.
    public func setWatched(_ target: WatchTarget, _ watched: Bool) async {
        message = await markWatched(target, watched, in: library)
    }

    /// `quiet` keeps what is on screen: a failure then is a message, not an
    /// error page. A load still running when another starts is cancelled.
    func load(quiet: Bool) async {
        await loads.run { [weak self] load in await self?.fetch(load, quiet: quiet) }
    }

    private func fetch(_ load: LoadController<HomeData>.Load, quiet: Bool) async {
        loads.begin(&state, quiet: quiet)
        guard let result = await loads.fetch(load, { try await self.library.home() }) else { return }
        var empty: HomeEmptyState?
        if result.value?.isEmpty == true {
            do {
                empty = HomeEmptyState(libraries: try await library.libraries())
            } catch is CancellationError {
                return
            } catch {
                empty = .unavailable
            }
            guard loads.isCurrent(load) else { return }
        }
        if let reason = loads.quietFailure(result, quiet: quiet, over: state) {
            message = reason
        } else {
            state = result
            emptyState = empty
            if result.value != nil { PerformanceSignposts.shared.homeShowedContent() }
        }
    }
}
