import Foundation
import Observation
import WMMModel

/// Movies or TV Shows with every signed-in server's titles, one card a
/// title, in the person's sort. Each other server is asked for its pages of
/// the same library type, with the same sort and filters, and the pages are
/// merged in order a page at a time: a card shows only once every server
/// that may still have a title before it has been asked that far, so cards
/// on screen never move when a slower server answers. A title more than one
/// server has is the server in use's card when it has one. Counts are every
/// server's added up, less the titles found on two, and read "about" until
/// every server has answered.
@MainActor
@Observable
public final class MergedGrid {
    /// One other server's pages.
    public struct Feed: Sendable {
        public var server: CardServer
        public var libraryID: Int?
        public var items: [BrowseItem] = []
        public var total = 0
        public var summary: LibrarySummary?
        /// Asked, and answered: its first page is in.
        public var answered = false

        public var hasMore: Bool { answered && items.count < total }
    }

    /// The other servers' feeds, by server id.
    public private(set) var feeds: [String: Feed] = [:]
    /// The servers still out, failed, and left out because they can't apply a filter.
    public private(set) var out: [CardServer] = []
    public private(set) var failed: [CardServer] = []
    public private(set) var cantFilter: [CardServer] = []
    public private(set) var slow = false

    @ObservationIgnored private let services: AppServices
    @ObservationIgnored private let type: LibraryType
    @ObservationIgnored private let pageSize: Int
    @ObservationIgnored private let sleep: @MainActor (Duration) async throws -> Void
    @ObservationIgnored private var query: ItemQuery?
    @ObservationIgnored private var round = 0
    @ObservationIgnored private var loadingMore: Set<String> = []

    public init(services: AppServices, type: LibraryType, pageSize: Int = LibraryModel.pageSize,
                sleep: @escaping @MainActor (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.services = services
        self.type = type
        self.pageSize = pageSize
        self.sleep = sleep
    }

    /// Something to merge: another server is signed in to.
    public var applies: Bool { !services.otherCardServers.isEmpty }

    /// Asks every other server for the first page of `query` (its library
    /// of this type found first), all at once.
    public func start(_ query: ItemQuery) async {
        round += 1
        let mine = round
        self.query = query
        feeds = [:]
        failed = []
        cantFilter = []
        loadingMore = []
        let servers = services.otherCardServers
        var asking: [(CardServer, AppServices)] = []
        for (server, scoped) in servers {
            // A filter a server is too old for leaves its titles out, and says so.
            if !query.contentRatings.isEmpty, !ServerFeatures(apiLevel: level(server.id)).ratingFilter {
                cantFilter.append(server)
                continue
            }
            asking.append((server, scoped))
        }
        out = asking.map(\.0)
        slow = false
        let timer = Task { [weak self, sleep] in
            try? await sleep(cardStillAskingAfter)
            guard let self, self.round == mine, !self.out.isEmpty else { return }
            self.slow = true
        }
        defer { timer.cancel() }
        var tasks: [Task<Void, Never>] = []
        for (server, scoped) in asking {
            let type = self.type, size = pageSize
            tasks.append(Task { @MainActor [weak self, sleep] in
                let feed = await withinLimit(cardServerTimeout, sleep: sleep) { () -> Feed in
                    let library = scoped.library
                    guard let id = try await library.libraries().first(where: { $0.type == type })?.id else {
                        return Feed(server: server, libraryID: nil, answered: true)
                    }
                    var page = query
                    page.libraryID = id
                    page.offset = 0
                    page.limit = size
                    let first = try await library.items(page)
                    return Feed(server: server, libraryID: id, items: first.items.map { Self.tagged($0, server.id) }, total: first.total, summary: first.summary, answered: true)
                }
                guard let self, self.round == mine else { return }
                self.out.removeAll { $0.id == server.id }
                if let feed { self.feeds[server.id] = feed } else { self.failed.append(server) }
            })
        }
        for task in tasks { await task.value }
        if round == mine { slow = false }
    }

    /// Try again on a server that didn't answer.
    public func retry(_ server: CardServer) async {
        guard let query else { return }
        failed.removeAll { $0.id == server.id }
        let current = feeds
        await start(query)
        // Keep what the others had loaded: only the one asked again starts over.
        for (id, feed) in current where id != server.id && feeds[id] != nil && feed.items.count > feeds[id]!.items.count { feeds[id] = feed }
    }

    /// The next page of every other server whose cards bound the grid now.
    public func loadMore(own: [BrowseItem], ownHasMore: Bool) async {
        guard let query else { return }
        let mine = round
        let bound = self.bound(own: own, ownHasMore: ownHasMore)
        for (id, feed) in feeds where feed.hasMore && !loadingMore.contains(id) {
            // Only a server whose last card is at the edge of what shows holds the grid back.
            if let last = feed.items.last, let bound, Self.before(bound, last, sort: query.sort) { continue }
            guard let libraryID = feed.libraryID else { continue }
            loadingMore.insert(id)
            var page = query
            page.libraryID = libraryID
            page.offset = feed.items.count
            page.limit = pageSize
            let request = page
            let scoped = services.on(id)
            let next = await withinLimit(cardServerTimeout, sleep: sleep) { try await scoped.library.items(request) }
            loadingMore.remove(id)
            guard round == mine, var current = feeds[id] else { return }
            if let next {
                let known = Set(current.items.map(\.id))
                current.items += next.items.filter { !known.contains($0.id) }.map { Self.tagged($0, id) }
                current.total = next.total
                if next.items.isEmpty { current.total = current.items.count }
            } else {
                // A page that didn't come: the server's titles stop where they are.
                current.total = current.items.count
            }
            feeds[id] = current
        }
    }

    // MARK: What shows

    /// The cards to show: the server in use's `own` (its pages so far) with
    /// every other server's, in the sort, one card a title, up to where
    /// every server still holding titles has been asked.
    public func items(own: [BrowseItem], ownHasMore: Bool) -> [BrowseItem] {
        guard let query else { return own }
        var seen = Set(own.compactMap(TitleKey.init))
        var others: [BrowseItem] = []
        for feed in orderedFeeds {
            for item in feed.items {
                if let key = TitleKey(item) {
                    guard seen.insert(key).inserted else { continue }
                }
                others.append(item)
            }
        }
        guard !others.isEmpty else { return own }
        let bound = self.bound(own: own, ownHasMore: ownHasMore)
        let merged = Self.sorted(own, others, sort: query.sort)
        guard let bound else { return merged }
        return merged.filter { !Self.before(bound, $0, sort: query.sort) }
    }

    /// The last card that may show: the earliest of the last cards of the
    /// servers that have more to give; nil when none has.
    private func bound(own: [BrowseItem], ownHasMore: Bool) -> BrowseItem? {
        guard let query else { return nil }
        var lasts: [BrowseItem] = feeds.values.filter(\.hasMore).compactMap(\.items.last)
        if ownHasMore, let last = own.last { lasts.append(last) }
        return lasts.min { Self.before($0, $1, sort: query.sort) }
    }

    /// More cards are to come, from any server.
    public func hasMore(ownHasMore: Bool) -> Bool { ownHasMore || feeds.values.contains(where: \.hasMore) }

    /// The feeds in the Servers list's order.
    private var orderedFeeds: [Feed] {
        let order = services.otherCardServers.map(\.0.id)
        return feeds.values.sorted { (order.firstIndex(of: $0.server.id) ?? .max) < (order.firstIndex(of: $1.server.id) ?? .max) }
    }

    /// Every server's count added up, less the titles found on two: the
    /// header's number. Exact only once every server has answered.
    public func total(own: Int, ownItems: [BrowseItem]) -> Int {
        let theirs = feeds.values.reduce(0) { $0 + $1.total }
        let ownKeys = Set(ownItems.compactMap(TitleKey.init))
        var shared = 0
        var seen = ownKeys
        for feed in feeds.values {
            for item in feed.items {
                guard let key = TitleKey(item) else { continue }
                if !seen.insert(key).inserted { shared += 1 }
            }
        }
        return own + theirs - shared
    }

    /// Every server's unwatched count added up, where each counts it.
    public func unwatched(own: Int?) -> Int? {
        guard let own else { return nil }
        return feeds.values.reduce(own) { $0 + ($1.summary?.unwatched ?? 0) }
    }

    /// The count isn't final: a server is still out.
    public var counting: Bool { !out.isEmpty }

    /// The line under the header's count, or nil: "Still asking Den
    /// server…", "Den server didn’t answer, so its titles aren’t listed.",
    /// "Cabin server can’t filter by rating, so its movies aren’t listed."
    public var line: String? {
        if slow, !out.isEmpty { return SearchWords.stillAsking(out.map(\.name)) }
        if let server = failed.first { return OneCardWords.libraryDidNotAnswer(server.name) }
        if let server = cantFilter.first { return OneCardWords.cantFilter(server.name, by: "rating", noun: type == .tv ? "show" : "movie") }
        return nil
    }

    /// Try again beside the line, for a server that didn't answer.
    public var retryable: CardServer? { failed.first }

    // MARK: Order

    private func level(_ serverID: String) -> Int? {
        services.servers.saved.first { $0.server.id == serverID }?.server.apiLevel
    }

    /// `item` as another server's card.
    static func tagged(_ item: BrowseItem, _ server: String) -> BrowseItem {
        var copy = item
        copy.server = server
        return copy
    }

    /// Both lists merged in the sort; each is already in it.
    static func sorted(_ own: [BrowseItem], _ others: [BrowseItem], sort: ItemSort) -> [BrowseItem] {
        (own + others).enumerated().sorted { a, b in
            if before(a.element, b.element, sort: sort) { return true }
            if before(b.element, a.element, sort: sort) { return false }
            return a.offset < b.offset
        }.map(\.element)
    }

    /// Whether `a` comes before `b` in `sort`, as the server orders them.
    static func before(_ a: BrowseItem, _ b: BrowseItem, sort: ItemSort) -> Bool {
        switch sort {
        case .title: return sortTitle(a.title) < sortTitle(b.title)
        case .added: return (a.addedAt ?? .distantPast) > (b.addedAt ?? .distantPast)
        case .year:
            if (a.year ?? 0) != (b.year ?? 0) { return (a.year ?? 0) > (b.year ?? 0) }
            return sortTitle(a.title) < sortTitle(b.title)
        case .rating: return (a.rating ?? 0) > (b.rating ?? 0)
        case .released: return (a.releaseDate ?? "") > (b.releaseDate ?? "")
        case .runtime: return (a.runtimeMin ?? 0) > (b.runtimeMin ?? 0)
        case .aired: return (a.lastAirDate ?? "") > (b.lastAirDate ?? "")
        }
    }

    /// A title as Title sorts it: without a leading "The", "A" or "An", in any case.
    static func sortTitle(_ title: String) -> String {
        let lower = title.lowercased()
        for article in ["the ", "a ", "an "] where lower.hasPrefix(article) && lower.count > article.count {
            return String(lower.dropFirst(article.count))
        }
        return lower
    }
}

extension LibraryModel {
    /// "Cabin" for a server signed in to, as a poster's line names it.
    public func serverName(_ id: String) -> String? {
        merged?.feeds[id].map { $0.server.shortName }
    }
}
