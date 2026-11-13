import Foundation
import Observation
import WMMModel

/// The tab bar's tabs, in order.
public enum AppTab: String, CaseIterable, Hashable, Sendable {
    case home, tvShows, movies, search, settings, liveTV

    public var title: String {
        switch self {
        case .home: "Home"
        case .tvShows: "TV Shows"
        case .movies: "Movies"
        case .search: "Search"
        case .settings: "Settings"
        case .liveTV: "Live TV"
        }
    }

    /// Keep established navigation on older servers. Live TV replaces Settings
    /// only when the signed-in viewer can watch; the profile opens Settings.
    public static func phoneTabs(liveTV: Bool) -> [AppTab] {
        liveTV ? [.home, .tvShows, .movies, .liveTV, .search] : [.home, .tvShows, .movies, .search, .settings]
    }

    /// The SF Symbol for the tab.
    public var systemImage: String {
        switch self {
        case .home: "house"
        case .tvShows: "tv"
        case .movies: "film"
        case .search: "magnifyingglass"
        case .settings: "gearshape"
        case .liveTV: "antenna.radiowaves.left.and.right"
        }
    }
}

/// A screen pushed onto a tab's stack.
public enum Route: Hashable, Sendable, Codable {
    /// A show's page (PLAN-SHOW-PAGE.md) with its up-next episode on top, or
    /// on `season` with that season's first unfinished one. With `episode`
    /// it is pushed as ``episode(showID:season:id:)``.
    case show(id: Int, season: Int? = nil, episode: Int? = nil)
    /// The same page with an episode on top; `season` is nil when not known
    /// (Home's hero), and the page looks for it.
    case episode(showID: Int, season: Int?, id: Int)
    case movie(id: Int)
    /// Approve a TV's pairing code; `code` is prefilled from a scanned link.
    case linkTV(code: String? = nil)
    /// Settings › Your requests.
    case myRequests
    /// Settings › Inbox, for admins.
    case inbox
    /// Settings › Downloads, on iPhone and iPad (PLAN-DOWNLOADS.md D3).
    case downloads
    /// Live TV › Reminders (PLAN-LIVE-TV-GUIDE.md §6.5), from the guide's toolbar or Settings.
    case liveRecordings
    case liveReminders
    /// Search listings, from the guide's search button on iPhone: listings only.
    case liveSearch
    /// A list's page (PLAN-ROWS.md §5): every title on it, in rank order, from a list row's See all.
    case mediaList(id: Int)
}

/// One player presentation, with separate domain targets and lifecycles.
public enum PlaybackPresentation: Hashable, Sendable, Identifiable {
    case file(PlayRequest)
    case live(LivePlayRequest)
    public var id: String {
        switch self {
        case .file(let request): "file:" + request.id
        case .live(let request): "live:" + request.id
        }
    }
}

/// Where the person is: the tab, each tab's stack, and what is playing.
/// Screens call ``open(_:)`` and ``play(_:)`` instead of holding the
/// navigation themselves, so they stay previewable and the tvOS app can
/// drive the same models with its own layout.
@MainActor
@Observable
public final class Navigator {
    public var tab: AppTab = .home
    public private(set) var paths: [AppTab: [Route]] = [:]
    /// The play in the full-screen player; nil when it is closed.
    public var playing: PlayRequest?
    /// Live channels never become file play requests.
    public var livePlaying: LivePlayRequest?
    private var liveIntent = 0
    public var playbackPresentation: PlaybackPresentation? {
        if let livePlaying { return .live(livePlaying) }
        if let playing { return .file(playing) }
        return nil
    }
    /// A note for whichever screen is showing ("Deleted"), after the one that
    /// had it has gone.
    public var toast: String?

    /// ⌘F asked for Search: the Search screen puts the cursor in its field, then clears this.
    public var searchFieldRequested = false

    public init() {}

    /// ⌘F on a hardware keyboard (PLAN-APPLE-REVIEW.md §6.3): the Search tab,
    /// with the cursor in its field. Nothing while a player is open.
    public func findInSearch() {
        guard playbackPresentation == nil else { return }
        liveIntent += 1
        tab = .search
        searchFieldRequested = true
    }

    public func path(for tab: AppTab) -> [Route] { paths[tab] ?? [] }

    public func setPath(_ path: [Route], for tab: AppTab) { paths[tab] = path }

    /// Pushes `route` onto the current tab's stack. A show, a season and an
    /// episode are one page (PLAN-SHOW-PAGE.md), pushed once, so Back leaves
    /// the show in one step.
    public func open(_ route: Route) {
        switch route {
        case .show(let id, let season, let episode?):
            open(.episode(showID: id, season: season, id: episode))
        default:
            paths[tab, default: []].append(route)
        }
    }

    /// Switches tab; choosing the tab already shown goes back to its root.
    public func select(_ newTab: AppTab) {
        liveIntent += 1
        if newTab == tab {
            paths[tab] = []
        } else {
            tab = newTab
        }
    }

    /// Menu (Back) on the Siri Remote, past what the stacks handle: pops the
    /// current tab's stack, else goes from a section's root to Home. False at
    /// Home's root, where the system takes over (the tvOS Home screen).
    @discardableResult
    public func exitSection() -> Bool {
        if !path(for: tab).isEmpty {
            paths[tab]?.removeLast()
            return true
        }
        guard tab != .home else { return false }
        tab = .home
        return true
    }

    public func play(_ request: PlayRequest) {
        liveIntent += 1
        livePlaying = nil
        playing = request
    }

    public func playLive(_ request: LivePlayRequest) {
        liveIntent += 1
        playing = nil
        livePlaying = request
    }

    public func closePlayer() {
        liveIntent += 1
        livePlaying = nil
        playing = nil
    }

    /// Applies launch arguments: `-tab settings` picks a tab, `-open <url>`
    /// opens a `watchmymedia://` link. For screenshots and UI checks on a
    /// simulator, where nothing can tap. They are the app's own, so a play
    /// or channel link acts as it reads (``DeepLink/opening(_:signedInServer:)``
    /// is for links from outside).
    public func applyLaunchArguments(_ arguments: [String], library: any LibraryRepository, liveTV: (any LiveTVRepository)? = nil) async {
        func value(after flag: String) -> String? {
            guard let i = arguments.firstIndex(of: flag), arguments.indices.contains(i + 1) else { return nil }
            return arguments[i + 1]
        }
        if let name = value(after: "-tab"), let tab = AppTab(rawValue: name) { self.tab = tab }
        if let text = value(after: "-open"), let url = URL(string: text), let link = DeepLink(url: url) {
            await open(link, library: library, liveTV: liveTV)
        }
    }

    /// Opens a `watchmymedia://` link the app was opened with, as
    /// ``DeepLink/opening(_:signedInServer:)`` read it.
    public func open(_ link: DeepLink, library: any LibraryRepository, liveTV: (any LiveTVRepository)? = nil) async {
        switch link {
        case .filePage(let fileID):
            // Not this app's link: the page, with Play ready, rather than playing.
            liveIntent += 1
            let intent = liveIntent
            guard let page = try? await library.filePage(fileID: fileID), intent == liveIntent else { return }
            closePlayer()
            switch page {
            case .movie(let id): open(.movie(id: id))
            case .episode(let showID, let season, let episode?): open(.episode(showID: showID, season: season, id: episode))
            case .episode(let showID, let season, nil): open(.show(id: showID, season: season))
            }
        case .liveRecordings:
            closePlayer()
            tab = .liveTV
            guard let liveTV else { return }
            let intent = liveIntent, context = liveTV.contextID
            try? await liveTV.refresh()
            guard intent == liveIntent, context == liveTV.contextID, tab == .liveTV, liveTV.capabilities?.recordingsAvailable == true else { return }
            paths[.liveTV] = [.liveRecordings]
        case .liveTV(let channelID):
            closePlayer()
            tab = .liveTV
            guard let liveTV else { return }
            let intent = liveIntent, context = liveTV.contextID
            do { try await liveTV.refresh() } catch { return }
            guard intent == liveIntent, context == liveTV.contextID, tab == .liveTV, liveTV.capabilities?.available == true else { return }
            if let channelID, let channel = liveTV.channels.first(where: { $0.id == channelID }) {
                playLive(LivePlayRequest(channel: channel))
            }
        case .play(let fileID):
            let request = (try? await library.playRequest(fileID: fileID)) ?? PlayRequest(fileID: fileID, title: "")
            play(request)
        case .show(let id, let season, let episode):
            closePlayer()
            open(.show(id: id, season: season, episode: episode))
        case .movie(let id):
            closePlayer()
            open(.movie(id: id))
        case .playMovie(let id):
            // As Play/Pause on its card: loaded, then played; its page if it can't be.
            await playPause(.playMovie(id: id), library: library) {}
        case .mediaList(let id):
            closePlayer()
            open(.mediaList(id: id))
        case .linkTV(let code):
            closePlayer()
            tab = .settings
            paths[.settings] = [.linkTV(code: code)]
        case .myRequests:
            closePlayer()
            tab = .settings
            paths[.settings] = [.myRequests]
        case .inbox:
            closePlayer()
            tab = .settings
            paths[.settings] = [.inbox]
        case .downloads:
            closePlayer()
            tab = .settings
            paths[.settings] = [.downloads]
        case .search:
            closePlayer()
            findInSearch()
        case .home:
            closePlayer()
            tab = .home
            paths[.home] = []
        }
    }
}
