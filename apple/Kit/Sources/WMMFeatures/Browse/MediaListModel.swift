import Foundation
import Observation
import WMMModel

/// A list's page, from a list row's See all (PLAN-ROWS.md §5, Decision 8):
/// its name, where it is from and how much of it the library has, then
/// every title in rank order. What the library has opens as usual; a missing
/// title is dimmed, with Request where the person may ask for it (the
/// existing request sheet), or Not found for an imported title TMDB couldn't
/// find. Whoever may change the list can refresh it now or delete it; it is
/// changed on the web.
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

    /// The server keeps at most 250 entries a list, so one page holds them all.
    public static let pageSize = 250

    @ObservationIgnored private let rows: any RowsRepository
    @ObservationIgnored private let library: any LibraryRepository
    @ObservationIgnored private let requests: (any RequestsRepository)?
    @ObservationIgnored private let navigator: Navigator
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private let changes: ScreenChanges
    @ObservationIgnored private let latest = LatestLoad()

    public init(listID: Int, rows: any RowsRepository, library: any LibraryRepository, requests: (any RequestsRepository)?, navigator: Navigator,
                now: @escaping @MainActor () -> Date = { Date() }) {
        changes = ScreenChanges(now: now)
        self.listID = listID
        self.rows = rows
        self.library = library
        self.requests = requests
        self.navigator = navigator
    }

    // MARK: Wording

    public var title: String { state.value?.list.name ?? "" }

    /// "From TMDB", in gold, with a link out when the source has one.
    public var sourceLine: String? { state.value?.list.sourceLine }

    public var sourceURL: URL? { state.value?.list.url }

    /// "Updated 3 h ago", "Refreshing…", or an import's "Imported Nov 2".
    public func whenLine(now: Date = .now) -> String? { state.value?.list.whenLine(now: now) }

    /// "4 of 12 in your library · For everyone".
    public var countsLine: String? {
        guard let page = state.value else { return nil }
        return page.list.countsLine(total: page.total, inLibrary: page.inLibrary)
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
        await latest.run { [weak self] in await self?.fetch(quiet: quiet) }
    }

    private func fetch(quiet: Bool) async {
        generation += 1
        let mine = generation
        if !quiet || state.value == nil { state = .loading }
        let result = await Loadable.run { () async throws -> Page in
            // The list and its entries at once.
            let list = Task { try await self.rows.list(id: self.listID) }
            defer { list.cancel() }
            let entries = try await self.rows.entries(listID: self.listID, offset: 0, limit: Self.pageSize)
            return Page(list: try await list.value, entries: entries.items, total: entries.total, inLibrary: entries.inLibrary)
        }
        guard let result, mine == generation else { return }
        if case .failed(let reason) = result, state.value != nil {
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
        do {
            try await library.mark(target, watched: watched)
            message = markedMessage(watched: watched)
        } catch {
            message = error.displayMessage
        }
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
            try await rows.delete(listID: listID)
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
    public var deletePrompt: String { "Delete “\(title)”?" }

    public var deleteMessage: String {
        state.value?.list.owner == .everyone
            ? "Its row goes from everyone’s screens. This can’t be undone."
            : "Its row goes from your screens. This can’t be undone."
    }
}
