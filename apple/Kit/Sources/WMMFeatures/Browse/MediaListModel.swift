import Foundation
import Observation
import WMMModel

/// A list's page, from a list row's See all:
/// its name, where it is from and how much of it the library has, then
/// every title in rank order. What the library has opens as usual; a missing
/// title is dimmed, with Request where the person may ask for it (the
/// existing request sheet), or Not found for an imported title TMDB couldn't
/// find. Whoever may change the list can refresh it now or delete it; it is
/// changed on the web.
///
/// A watchlist's page has no source, no "Updated" and no ranks: its titles
/// are newest saved first, or by title or release year, with All, Movies
/// and TV shows over them. Each can be removed, and the watchlist renamed,
/// deleted or told to keep watched titles, through the app's
/// ``WatchlistStore``.
@MainActor
@Observable
public final class MediaListModel {
    public struct Page: Hashable, Sendable {
        public var list: MediaList
        public var entries: [MediaListEntry]
        public var total: Int
        public var inLibrary: Int
    }

    public let listID: Int
    public private(set) var state: Loadable<Page> = .loading
    public var message: String?
    public var notice: Notice?
    /// The request sheet, while it is up.
    public var requestSheet: RequestSheetModel?
    public private(set) var working = false

    /// The server sends at most 250 entries a page; a list keeps at most 250
    /// and a watchlist 500, so two pages hold them all.
    public static let pageSize = 250
    static let maxPages = 2

    /// A watchlist's chips: every title, or only movies or only shows.
    public var filter: MediaType?
    /// A watchlist's order; changing it reads the page again.
    public private(set) var sort: ListSort = .added

    @ObservationIgnored private let rows: any RowsRepository
    @ObservationIgnored private let library: any LibraryRepository
    @ObservationIgnored private let requests: (any RequestsRepository)?
    @ObservationIgnored private let navigator: Navigator
    @ObservationIgnored private let changes: ScreenChanges
    @ObservationIgnored private let loads = LoadController<Page>()
    /// The person's watchlists: a watchlist's own actions, and Add to Watchlist on another list's titles.
    @ObservationIgnored public let watchlists: WatchlistStore?

    public init(listID: Int, rows: any RowsRepository, library: any LibraryRepository, requests: (any RequestsRepository)?, navigator: Navigator,
                watchlists: WatchlistStore? = nil, now: @escaping @MainActor () -> Date = { Date() }) {
        changes = ScreenChanges(now: now)
        self.listID = listID
        self.rows = rows
        self.library = library
        self.requests = requests
        self.navigator = navigator
        self.watchlists = watchlists
    }

    // MARK: A watchlist

    /// The page is one of the person's watchlists.
    public var isWatchlist: Bool { state.value?.list.isWatchlist == true }

    /// The watchlist as the store has it, once loaded: its name and switch follow a change here at once.
    private var kept: Watchlist? { isWatchlist ? watchlists?.watchlist(listID) : nil }

    /// Remove titles once watched.
    public var removesWatched: Bool { kept?.removeWatched ?? state.value?.list.removeWatched ?? true }

    /// The titles the page draws: a watchlist's, those still on it and of the chosen kind.
    public var entries: [MediaListEntry] {
        guard let page = state.value else { return [] }
        guard isWatchlist else { return page.entries }
        return kept(page).filter { filter == nil || $0.mediaType == filter }
    }

    /// A watchlist's titles that are still on it, whatever the chips say.
    private func kept(_ page: Page) -> [MediaListEntry] {
        guard let watchlists else { return page.entries }
        return page.entries.filter { entry in entry.watchlistTitle.map { watchlists.isOn($0.key, listID: listID) } ?? true }
    }

    /// Sort by Date added, Title or Release year.
    public func setSort(_ sort: ListSort) async {
        guard sort != self.sort else { return }
        self.sort = sort
        await load(quiet: true)
    }

    /// Remove from the watchlist, from a card's menu.
    public func remove(_ entry: MediaListEntry) async {
        guard isWatchlist, let key = entry.watchlistTitle?.key else { return }
        await watchlists?.remove(key, from: listID)
    }

    public func setRemovesWatched(_ on: Bool) async {
        await watchlists?.setRemoveWatched(listID, on)
    }

    /// Rename: nil once done, else why not.
    public func rename(to name: String) async -> String? {
        await watchlists?.rename(listID, to: name)
    }

    /// The card's long press on another list's title: Add to Watchlist and the rest.
    public func watchlistItem(_ entry: MediaListEntry) -> WatchlistStore.MenuItem? {
        guard !isWatchlist, let key = entry.watchlistTitle?.key else { return nil }
        return watchlists?.menuItem(key, said: entry.watchlisted)
    }

    public func pressWatchlist(_ entry: MediaListEntry) async {
        guard let title = entry.watchlistTitle else { return }
        await watchlists?.press(title, cardSaid: entry.watchlisted)
    }

    // MARK: Wording

    public var title: String { kept?.name ?? state.value?.list.name ?? "" }

    /// "From TMDB", in gold, with a link out when the source has one.
    public var sourceLine: String? { state.value?.list.sourceLine }

    public var sourceURL: URL? { state.value?.list.url }

    /// "Updated 3 h ago", "Refreshing…", or an import's "Imported Nov 2".
    public func whenLine(now: Date = .now) -> String? { state.value?.list.whenLine(now: now) }

    /// "4 of 12 in your library · For everyone"; a watchlist's "12 titles ·
    /// 9 in your library", nothing while it is empty.
    public var countsLine: String? {
        guard let page = state.value else { return nil }
        guard page.list.isWatchlist else { return page.list.countsLine(total: page.total, inLibrary: page.inLibrary) }
        let titles = kept(page)
        guard !titles.isEmpty else { return nil }
        return WatchlistWords.counts(titles: titles.count, inLibrary: titles.filter(\.inLibrary).count)
    }

    /// Why the last fetch failed, over the list it kept.
    public func failureLine(now: Date = .now) -> String? { state.value?.list.failureLine(now: now) }

    /// Refresh now: for whoever may change the list, when the server fetches it.
    public var canRefresh: Bool { state.value.map { $0.list.canEdit && $0.list.refreshable && !$0.list.fetching } ?? false }

    public var canDelete: Bool { state.value?.list.canEdit == true }

    /// Request on a missing title: the person may ask for this kind of title, and nobody has.
    public func canRequest(_ entry: MediaListEntry) -> Bool {
        guard let item = entry.requestable, item.state == .requestable else { return false }
        return requests?.options.canRequest(item.mediaType) == true
    }

    // MARK: Loading

    /// Loads the page, then again quietly when the list or the library
    /// changes or one of its titles is marked, until cancelled. A mark made
    /// here is drawn at once from its own notice; coming back to the page
    /// loads nothing unless something changed meanwhile (``ScreenChanges``).
    public func run() async {
        await changes.run(
            subscribe: { merge(library.libraryChanges(), unknown: rows.changes()) },
            hasContent: state.value != nil,
            load: { quiet in await load(quiet: quiet) },
            apply: { changes in await apply(changes) }
        )
    }

    public func retry() async {
        await load(quiet: false)
        changes.loaded()
    }

    /// Pull to refresh: keeps the page, and says why if it fails.
    public func reload() async {
        await load(quiet: true)
        changes.loaded()
    }

    /// Marks this device made, with their state, are drawn as they are; what
    /// else touches a title on the list loads the page again.
    private func apply(_ changes: [LibraryChange]) async {
        guard var page = state.value else { return await load(quiet: true) }
        let items = page.entries.compactMap(\.item)
        var reload = false
        for change in changes {
            switch change {
            case .watched(.movie, let ids, let watched?):
                let marked = Set(ids)
                page.entries = page.entries.map { entry in
                    guard let item = entry.item, item.kind == .movie, marked.contains(item.id) else { return entry }
                    var copy = entry
                    copy.item?.watched = watched
                    copy.item?.progress = nil
                    return copy
                }
            case .watched(.movie, let ids, nil):
                reload = reload || items.contains { $0.kind == .movie && ids.contains($0.id) }
            case .watched(.episode, _, _):
                reload = reload || items.contains { $0.kind == .show }
            case .markedShow(let id, _, _):
                reload = reload || items.contains { $0.kind == .show && $0.id == id }
            case .scan:
                break
            case .library, .unknown:
                reload = true
            }
        }
        if reload { await load(quiet: true) } else if page != state.value { state = .loaded(page) }
    }

    /// A load still running when another starts is cancelled.
    func load(quiet: Bool) async {
        await loads.run { [weak self] load in await self?.fetch(load, quiet: quiet) }
    }

    private func fetch(_ load: LoadController<Page>.Load, quiet: Bool) async {
        loads.begin(&state, quiet: quiet)
        let result = await loads.fetch(load) { () async throws -> Page in
            // The list and its entries at once.
            let list = Task { try await self.rows.list(id: self.listID) }
            defer { list.cancel() }
            var entries = try await self.rows.entries(listID: self.listID, offset: 0, limit: Self.pageSize, sort: self.sort, mediaType: nil)
            // A watchlist holds up to 500: its second page.
            for _ in 1..<Self.maxPages where entries.items.count < entries.total {
                let more = try await self.rows.entries(listID: self.listID, offset: entries.items.count, limit: Self.pageSize, sort: self.sort, mediaType: nil)
                guard !more.items.isEmpty else { break }
                entries.items += more.items
            }
            return Page(list: try await list.value, entries: entries.items, total: entries.total, inLibrary: entries.inLibrary)
        }
        guard let result else { return }
        if let reason = loads.quietFailure(result, quiet: quiet, over: state) {
            message = reason
        } else {
            state = result
        }
    }

    // MARK: Taps

    /// A title the library has opens its page.
    public func open(_ entry: MediaListEntry) {
        guard let item = entry.item else { return }
        notice = navigator.open(item)
    }

    public func openRequest(_ entry: MediaListEntry) {
        guard let requests, canRequest(entry), let item = entry.requestable else { return }
        let sheet = RequestSheetModel(requests: requests, mediaType: item.mediaType, tmdbID: item.tmdbID, placeholder: item)
        requestSheet = sheet
        Task { await sheet.loadOnce() }
    }

    /// The sheet has gone: a title asked for reads Requested.
    public func requestSheetClosed(_ sheet: RequestSheetModel) {
        guard sheet.sent != nil, var page = state.value else { return }
        page.entries = page.entries.map { e in
            guard e.item == nil, e.mediaType == sheet.mediaType, e.tmdbID == sheet.tmdbID else { return e }
            var copy = e
            copy.requestState = .requested
            return copy
        }
        state = .loaded(page)
    }

    public func setWatched(_ target: WatchTarget, _ watched: Bool) async {
        message = await markWatched(target, watched, in: library)
    }

    /// Asks the server to fetch the list again; the page follows when it has.
    public func refreshNow() async {
        guard canRefresh, var page = state.value else { return }
        working = true
        defer { working = false }
        do {
            page.list = try await rows.refresh(listID: listID)
            state = .loaded(page)
        } catch {
            message = error.displayMessage
        }
    }

    /// Deletes the list and its row from every screen, and leaves its page.
    public func delete() async {
        guard canDelete, let name = state.value?.list.name else { return }
        working = true
        defer { working = false }
        do {
            if isWatchlist, let watchlists {
                guard await watchlists.delete(listID) else { return }
            } else {
                try await rows.delete(listID: listID)
            }
            let tab = navigator.tab
            var path = navigator.path(for: tab)
            if path.last == .mediaList(id: listID) { path.removeLast() }
            navigator.setPath(path, for: tab)
            navigator.toast = "Deleted “\(name)”"
        } catch {
            message = error.displayMessage
        }
    }

    /// The delete confirmation's words.
    public var deletePrompt: String { isWatchlist ? WatchlistWords.deletePrompt(title) : "Delete “\(title)”?" }

    public var deleteMessage: String {
        if isWatchlist, let page = state.value { return WatchlistWords.deleteMessage(titles: kept(page).count) }
        return state.value?.list.owner == .everyone
            ? "Its row goes from everyone’s screens. This can’t be undone."
            : "Its row goes from your screens. This can’t be undone."
    }
}
