import Foundation
import Observation
import WMMModel

/// The server a search result is on, when it isn't the one in use.
public struct SearchSource: Hashable, Sendable {
    /// The server's ID.
    public var id: String
    public var name: String

    public init(id: String, name: String) {
        self.id = id
        self.name = name
    }
}

/// A show or movie search found, with the server it is on when that isn't
/// the one in use. Its id is that server's, so two servers' results never
/// stand for each other. Reads as its ``item``: `found.title`.
@dynamicMemberLookup
public struct FoundTitle: Hashable, Sendable, Identifiable {
    public var item: BrowseItem
    /// Nil for the server in use; a row from another names it.
    public var server: SearchSource?

    public init(_ item: BrowseItem, server: SearchSource? = nil) {
        self.item = item
        self.server = server
    }

    public var id: String { "\(server?.id ?? "")/\(item.kind)/\(item.id)" }

    public subscript<T>(dynamicMember keyPath: KeyPath<BrowseItem, T>) -> T { item[keyPath: keyPath] }
}

/// An episode search found, with the server it is on when that isn't the one in use.
@dynamicMemberLookup
public struct FoundEpisode: Hashable, Sendable, Identifiable {
    public var episode: EpisodeCard
    public var server: SearchSource?

    public init(_ episode: EpisodeCard, server: SearchSource? = nil) {
        self.episode = episode
        self.server = server
    }

    public var id: String { "\(server?.id ?? "")/\(episode.episodeID)" }

    public subscript<T>(dynamicMember keyPath: KeyPath<EpisodeCard, T>) -> T { episode[keyPath: keyPath] }
}

/// What Search lists: the three groups, the server in use's results first
/// in each and every other server's after them.
public struct SearchListing: Hashable, Sendable {
    public var shows: [FoundTitle] = []
    public var movies: [FoundTitle] = []
    public var episodes: [FoundEpisode] = []

    public init(shows: [FoundTitle] = [], movies: [FoundTitle] = [], episodes: [FoundEpisode] = []) {
        self.shows = shows
        self.movies = movies
        self.episodes = episodes
    }

    public var isEmpty: Bool { shows.isEmpty && movies.isEmpty && episodes.isEmpty }
}

/// A registered server whose titles a search doesn't list, in one line.
public struct UnlistedServer: Hashable, Sendable, Identifiable {
    public var server: SearchSource
    /// "Den server didn’t answer, so its titles aren’t listed."
    public var line: String
    /// It was asked and didn't answer, so Try again asks it again.
    public var canRetry: Bool

    public var id: String { server.id }
}

/// Search: the server's search by title, a moment after
/// typing stops, with results grouped as TV Shows, Movies and Episodes.
/// From API level 5, for someone who may request,
/// TMDB's matches that are not in the library follow as Not in your
/// library, fetched beside the library's results and never holding them up;
/// one opens the request sheet.
///
/// With two or more servers signed in, every query goes to each of them at
/// once, each to its own address with its own sign-in
/// (``LibraryRepository/search(_:serverID:)``). The server in use's results
/// draw first; each other server's are added to the end of each group in
/// the Servers list's order, once every server before it has answered or
/// failed, so rows on screen never move and the order doesn't depend on
/// which answered first. Each server gets
/// ``serverTimeout``; one still out after ``stillAskingAfter`` is named at
/// the end of the list (``stillAsking``), and one that doesn't answer gets a
/// line with Try again (``unlisted``) and isn't asked again for
/// ``retryAfter`` on this visit, so typing doesn't queue timeouts. A new
/// keystroke cancels every call still out. Opening a result on another
/// server switches the app to it (``ServersModel``) and shows Home, Search
/// with the same words and the title, there. Not in your library, Live TV
/// and marking watched stay the server in use's.
@MainActor
@Observable
public final class SearchModel {
    /// What the screen shows.
    public enum Phase: Equatable, Sendable {
        /// Nothing typed: "Search your libraries".
        case idle
        /// The first results are on their way.
        case loading
        case results(SearchListing)
        /// Nothing matched the query.
        case noMatches(String)
        case failed(String)
    }

    public private(set) var query = ""
    /// Successful queries, newest first, kept between launches for this
    /// account on this server when the model has a ``RecentSearches``;
    /// without one, for this model only.
    public private(set) var recentQueries: [String] = [] {
        didSet { if recentQueries != oldValue { recents?.save(recentQueries) } }
    }
    /// The server in use's results. Nil before the first search, and while the query is empty.
    public private(set) var results: SearchResults?
    /// The query `results` are for.
    public private(set) var resultsFor = ""
    /// A search is running (the old results stay on screen meanwhile).
    public private(set) var loading = false
    public private(set) var error: String?
    public var message: String?
    public var notice: Notice?
    /// TMDB's matches for ``requestablesFor``, with where each stands.
    public private(set) var requestables: [Requestable] = []
    public private(set) var requestablesFor = ""
    /// The request sheet, while it is up.
    public var requestSheet: RequestSheetModel?
    /// The registered servers whose titles aren't listed for the results on
    /// screen, each in a line at the end of the list.
    public private(set) var unlisted: [UnlistedServer] = []

    /// How long each server has to answer a search across servers.
    public static let serverTimeout: Duration = .seconds(4)
    /// How long a server is out before the list says it is still being asked.
    public static let stillAskingAfter: Duration = .seconds(1)
    /// How long a server that didn't answer is left alone on this visit to Search.
    public static let retryAfter: TimeInterval = 30

    /// The other servers' results for the query on screen, in the order they were added.
    private var added: [(server: SearchSource, results: SearchResults)] = []
    /// The other servers asked this round that haven't answered yet.
    private var out: Set<String> = []
    /// ``stillAskingAfter`` has passed in this round.
    private var slow = false
    /// The server in use has answered this round, or failed.
    private var currentDone = false

    @ObservationIgnored private let library: any LibraryRepository
    @ObservationIgnored private let requests: (any RequestsRepository)?
    @ObservationIgnored private let navigator: Navigator
    @ObservationIgnored private let debounce: Duration
    @ObservationIgnored private let recents: RecentSearches?
    @ObservationIgnored private let servers: (any ServerRepository)?
    @ObservationIgnored private let switcher: ServersModel?
    @ObservationIgnored private let sleep: @MainActor (Duration) async throws -> Void
    @ObservationIgnored private let now: @MainActor () -> Date
    /// The search waiting out the debounce or running; tests await it.
    @ObservationIgnored private(set) var pending: Task<Void, Never>?
    /// The search for what can be requested, beside ``pending``.
    @ObservationIgnored private(set) var pendingRequestables: Task<Void, Never>?
    /// The calls to the other servers still out, by server ID; tests await them.
    @ObservationIgnored private(set) var asking: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var stillAskingTimer: Task<Void, Never>?
    /// Counts searches, so an answer to an earlier one is dropped.
    @ObservationIgnored private var round = 0
    /// The other servers asked this round, in the Servers list's order.
    @ObservationIgnored private var asked: [SearchSource] = []
    /// Answers not added yet, by server ID: the server in use, or a server
    /// earlier in the list, is still out.
    @ObservationIgnored private var early: [String: SearchResults] = [:]
    /// How many of ``asked`` have had their turn: added, or failed.
    @ObservationIgnored private var turns = 0
    /// This round's lines, shown once the server in use has answered.
    @ObservationIgnored private var roundUnlisted: [UnlistedServer] = []
    /// A server answered this round.
    @ObservationIgnored private var anyAnswered = false
    /// Why the server in use didn't answer this round, while others may.
    @ObservationIgnored private var currentFailure: String?
    /// When each server last failed to answer, by server ID.
    @ObservationIgnored private var failedAt: [String: Date] = [:]
    /// The servers that answered, on this visit, that they are too old for
    /// this app: not asked again on it, so typing doesn't ask one each time.
    @ObservationIgnored private var tooOldNow: Set<String> = []
    /// When each other server last answered a search on this visit, by
    /// server ID: a switch to one that just answered doesn't ask it which
    /// server it is again.
    @ObservationIgnored private var answeredAt: [String: Date] = [:]
    /// The visit to Search ended and no new one has begun.
    @ObservationIgnored private var visitOver = false

    /// - Parameters:
    ///   - servers: the registered servers; with it, search looks in every one signed in to.
    ///   - switcher: switches to the server of a result that isn't on the one in use.
    ///   - sleep: how the per-server time limit and the still-asking wait are timed; tests move it by hand.
    public init(library: any LibraryRepository, navigator: Navigator, debounce: Duration = .milliseconds(300), requests: (any RequestsRepository)? = nil,
                recents: RecentSearches? = nil, servers: (any ServerRepository)? = nil, switcher: ServersModel? = nil,
                sleep: @escaping @MainActor (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
                now: @escaping @MainActor () -> Date = { Date() }) {
        self.library = library
        self.requests = requests
        self.navigator = navigator
        self.debounce = debounce
        self.recents = recents
        self.servers = servers
        self.switcher = switcher
        self.sleep = sleep
        self.now = now
        recentQueries = recents?.load() ?? []
    }

    // MARK: Across servers

    /// How long a server's answer to a search stands for a switch to it
    /// without asking which server it is: the device may have changed
    /// networks since, and a kept address is someone else's machine there.
    static let answerStands: TimeInterval = 300

    /// The visit to Search ended: another tab was chosen, or the app left
    /// the screen. Each other server is asked which server it is once for a
    /// visit, before its sign-in is sent, and what they said is forgotten
    /// here (``LibraryRepository/searchVisitEnded()``).
    public func visitEnded() {
        guard !visitOver else { return }
        visitOver = true
        // A server that didn't answer is left alone for the rest of a visit's half minute, not the next visit's.
        failedAt = [:]
        tooOldNow = []
        answeredAt = [:]
        library.searchVisitEnded()
    }

    /// Search is on screen again after ``visitEnded()``. With other
    /// servers' titles listed, the search is asked again, so each server
    /// says which it is before its pictures load with its sign-in.
    public func visitBegan() {
        guard visitOver else { return }
        visitOver = false
        if acrossServers, !resultsFor.isEmpty, !trimmedQuery.isEmpty { schedule(after: nil) }
    }

    /// The registered servers besides the one in use, in the Servers list's order.
    private var otherServers: [SavedServer] {
        guard let servers, let current = servers.current?.server.id else { return [] }
        return servers.registered.filter { $0.server.id != current }
    }

    /// How many servers a search looks in: the one in use and every other signed in to.
    public var serverCount: Int { 1 + otherServers.filter { $0.signedIn && !$0.server.tooOld }.count }

    /// Search looks in two or more servers.
    public var acrossServers: Bool { serverCount > 1 }

    /// The three groups as listed: the server in use's results, then each other server's.
    public var listing: SearchListing {
        var listing = SearchListing()
        if let results {
            listing.shows = results.shows.map { FoundTitle($0) }
            listing.movies = results.movies.map { FoundTitle($0) }
            listing.episodes = results.episodes.map { FoundEpisode($0) }
        }
        for (server, found) in added {
            listing.shows += found.shows.map { FoundTitle($0, server: server) }
            listing.movies += found.movies.map { FoundTitle($0, server: server) }
            listing.episodes += found.episodes.map { FoundEpisode($0, server: server) }
        }
        return listing
    }

    /// "Still asking Cabin server…", at the end of the list while a server
    /// asked more than ``stillAskingAfter`` ago hasn't answered; nil otherwise.
    public var stillAsking: String? {
        guard slow, currentDone else { return nil }
        let names = asked.filter { out.contains($0.id) }.map(\.name)
        return names.isEmpty ? nil : SearchWords.stillAsking(names)
    }

    /// Before anything is typed: "Search your libraries", or "Search your servers".
    public var idleWords: (title: String, message: String) {
        acrossServers ? (SearchWords.idleTitleAcross, SearchWords.idleMessage(servers: serverCount)) : (SearchWords.idleTitle, SearchWords.idleMessage)
    }

    /// Nothing matched: "No matches", or "No matches on your 3 servers".
    public func noMatchesWords(_ query: String) -> (title: String, message: String) {
        acrossServers ? (SearchWords.noMatches(servers: serverCount), SearchWords.noMatchesMessageAcross(query)) : (SearchWords.noMatches, noMatchesMessage(query))
    }

    /// Not in your library: what TMDB has that the library does not, for the
    /// query on screen; empty for someone who may not request.
    public var notInLibrary: [Requestable] {
        guard requests?.options.canRequest == true, !trimmedQuery.isEmpty else { return [] }
        return requestables.filter { $0.state != .available && requests?.options.canRequest($0.mediaType) == true }
    }

    /// Beside Not in your library's heading: how many, and across servers
    /// the server they would be requested on, since those stay the server
    /// in use's ("4 · Living room server").
    public var notInLibraryDetail: String {
        let count = formatCount(notInLibrary.count)
        guard acrossServers, let name = servers?.current?.server.name else { return count }
        return "\(count) · \(name)"
    }

    /// Opens the request sheet for a result that can be asked for.
    public func openRequest(_ item: Requestable) {
        guard let requests, item.state == .requestable else { return }
        let sheet = RequestSheetModel(requests: requests, mediaType: item.mediaType, tmdbID: item.tmdbID, placeholder: item)
        requestSheet = sheet
        Task { await sheet.loadOnce() }
    }

    /// The sheet has gone: a result asked for reads Requested.
    public func requestSheetClosed(_ sheet: RequestSheetModel) {
        guard sheet.sent != nil else { return }
        requestables = requestables.map { r in
            guard r.mediaType == sheet.mediaType, r.tmdbID == sheet.tmdbID else { return r }
            var copy = r
            copy.state = .requested
            return copy
        }
    }

    public var phase: Phase {
        if trimmedQuery.isEmpty { return .idle }
        if let error { return .failed(error) }
        guard results != nil else { return .loading }
        let listing = listing
        if !listing.isEmpty { return .results(listing) }
        // Nothing on the server in use, and another is still to answer.
        return out.isEmpty ? .noMatches(resultsFor) : .loading
    }

    /// The field changed: searches a moment after typing stops.
    public func setQuery(_ text: String) {
        guard text != query else { return }
        query = text
        schedule(after: debounce)
    }

    public func clear() { setQuery("") }

    public func clearRecentQueries() { recentQueries = [] }

    /// A recent query runs immediately, including when it is already in the field.
    public func selectRecentQuery(_ text: String) {
        query = text.trimmingCharacters(in: .whitespacesAndNewlines)
        schedule(after: nil)
    }

    /// Try again, when the search failed: asks every server, those that didn't answer lately too.
    public func retry() async {
        failedAt = [:]
        tooOldNow = []
        schedule(after: nil)
        await pending?.value
        await pendingRequestables?.value
    }

    /// Try again on a server's line: asks that server again, for the results
    /// on screen, and adds its rows after them.
    public func retry(_ server: UnlistedServer) {
        guard server.canRetry, currentDone, !resultsFor.isEmpty else { return }
        failedAt[server.id] = nil
        guard server.id != servers?.current?.server.id,
              let saved = otherServers.first(where: { $0.server.id == server.id && $0.signedIn && !$0.server.tooOld }) else {
            // The server in use: the whole search again.
            schedule(after: nil)
            return
        }
        roundUnlisted.removeAll { $0.id == server.id }
        unlisted = roundUnlisted
        // It had its turn: asked again, its rows go after everything listed.
        if let index = asked.firstIndex(where: { $0.id == server.id }) {
            asked.remove(at: index)
            if index < turns { turns -= 1 }
        }
        asked.append(server.server)
        ask(saved, resultsFor, round: round)
    }

    // MARK: Taps

    /// A result on the server in use opens its page; one on another server
    /// switches the app to that server first.
    public func open(_ found: FoundTitle) {
        guard let server = found.server else {
            notice = navigator.open(found.item)
            return
        }
        // An unmatched file has no page: the search is asked again there, and it is found again.
        let route: Route? = if case .page(let route) = found.item.opening { route } else { nil }
        leave(for: server, route: route)
    }

    public func open(_ item: BrowseItem) { open(FoundTitle(item)) }

    /// An episode opens its page.
    public func open(_ found: FoundEpisode) {
        guard let server = found.server else {
            navigator.open(found.episode)
            return
        }
        leave(for: server, route: found.episode.route)
    }

    public func open(_ episode: EpisodeCard) { open(FoundEpisode(episode)) }

    public func play(_ episode: EpisodeCard) {
        guard let request = episode.playRequest else { return }
        navigator.play(request)
    }

    /// Switches to `server`: Home there, Search with the same words, and
    /// the title over it. A server that just answered this search isn't
    /// asked which server it is again; one whose answer is older than
    /// ``answerStands`` is, since its rows may have been on screen a while.
    private func leave(for server: SearchSource, route: Route?) {
        guard let switcher else { return }
        let words = resultsFor.isEmpty ? trimmedQuery : resultsFor
        let justAnswered = answeredAt[server.id].map { now().timeIntervalSince($0) < Self.answerStands } ?? false
        cancelCalls()
        Task {
            await switcher.switchTo(server.id, arriving: ServerArrival(serverID: server.id, tab: .search, search: words, route: route), asking: !justAnswered)
        }
    }

    public func setWatched(_ item: BrowseItem, _ watched: Bool) async {
        guard let target = item.watchTarget else { return }
        await mark(target, watched) { results in
            func update(_ list: [BrowseItem]) -> [BrowseItem] {
                list.map { i in
                    guard i.id == item.id, i.kind == item.kind else { return i }
                    var copy = i
                    copy.watched = watched
                    if watched { copy.progress = nil }
                    return copy
                }
            }
            results.movies = update(results.movies)
            results.shows = update(results.shows)
        }
    }

    public func setWatched(_ episode: EpisodeCard, _ watched: Bool) async {
        await mark(.episode(id: episode.episodeID), watched) { results in
            results.episodes = results.episodes.map { e in
                guard e.episodeID == episode.episodeID else { return e }
                var copy = e
                copy.watched = watched
                return copy
            }
        }
    }

    // MARK: Internals

    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Every call still out is cancelled: a keystroke, or leaving for another server.
    private func cancelCalls() {
        pending?.cancel()
        pendingRequestables?.cancel()
        for task in asking.values { task.cancel() }
        asking = [:]
        stillAskingTimer?.cancel()
        stillAskingTimer = nil
        round += 1
        out = []
    }

    private func schedule(after delay: Duration?) {
        cancelCalls()
        let text = trimmedQuery
        guard !text.isEmpty else {
            results = nil
            resultsFor = ""
            loading = false
            error = nil
            pending = nil
            requestables = []
            requestablesFor = ""
            pendingRequestables = nil
            added = []
            unlisted = []
            slow = false
            currentDone = false
            return
        }
        if let requests, requests.options.canRequest {
            pendingRequestables = Task { [weak self] in
                if let delay {
                    try? await Task.sleep(for: delay)
                    guard !Task.isCancelled else { return }
                }
                // Quiet: a failure leaves the section out rather than spoiling the library's results.
                guard let found = try? await requests.search(text), !Task.isCancelled else { return }
                self?.requestables = found
                self?.requestablesFor = text
            }
        }
        loading = true
        error = nil
        pending = Task { [weak self] in
            if let delay {
                try? await Task.sleep(for: delay)
                guard !Task.isCancelled else { return }
            }
            await self?.search(text)
        }
    }

    private struct TimedOut: Error {}

    /// Runs `operation`, cancelling it and throwing ``TimedOut`` after ``serverTimeout``.
    private func timed<T: Sendable>(_ operation: @escaping @MainActor () async throws -> T) async throws -> T {
        let call = Task { @MainActor in try await operation() }
        let timer = Task { @MainActor [sleep] in
            guard (try? await sleep(Self.serverTimeout)) != nil else { return }
            call.cancel()
        }
        defer { timer.cancel() }
        do {
            return try await withTaskCancellationHandler { try await call.value } onCancel: { call.cancel() }
        } catch is CancellationError {
            if Task.isCancelled { throw CancellationError() }
            throw TimedOut()
        }
    }

    /// Asks every server at once: the others first, then the one in use, which this waits for.
    private func search(_ text: String) async {
        let round = round
        let across = acrossServers
        asked = []
        early = [:]
        turns = 0
        roundUnlisted = []
        anyAnswered = false
        currentFailure = nil
        currentDone = false
        slow = false
        if across {
            for saved in otherServers {
                let source = SearchSource(id: saved.server.id, name: saved.server.name)
                if saved.server.tooOld || tooOldNow.contains(source.id) {
                    roundUnlisted.append(UnlistedServer(server: source, line: SearchWords.tooOld(source.name), canRetry: false))
                } else if !saved.signedIn {
                    roundUnlisted.append(UnlistedServer(server: source, line: SearchWords.signedOut(source.name), canRetry: false))
                } else if let at = failedAt[source.id], now().timeIntervalSince(at) < Self.retryAfter {
                    roundUnlisted.append(UnlistedServer(server: source, line: SearchWords.didNotAnswer(source.name), canRetry: true))
                } else {
                    asked.append(source)
                    ask(saved, text, round: round)
                }
            }
            stillAskingTimer = Task { [weak self, sleep] in
                guard (try? await sleep(Self.stillAskingAfter)) != nil, !Task.isCancelled, let self, self.round == round else { return }
                self.slow = true
            }
        }
        let outcome: Result<SearchResults, any Error>
        do {
            let library = library
            outcome = .success(try await across ? timed { try await library.search(text) } : library.search(text))
        } catch {
            outcome = .failure(error)
        }
        guard !Task.isCancelled, self.round == round else { return }
        loading = false
        switch outcome {
        case .success(let found):
            results = found
            resultsFor = text
            anyAnswered = true
            remember(text)
        case .failure(let error):
            guard !(error is CancellationError) else { return }
            guard across else {
                self.error = error.displayMessage
                return
            }
            // The others may still list theirs; this one gets its line.
            results = SearchResults()
            resultsFor = text
            currentFailure = error is TimedOut ? SearchWords.noneAnswered : error.displayMessage
            if let current = servers?.current?.server {
                roundUnlisted.insert(UnlistedServer(server: SearchSource(id: current.id, name: current.name),
                                                    line: SearchWords.didNotAnswer(current.name), canRetry: true), at: 0)
            }
        }
        // The last search's rows from other servers go, and this one's are
        // added in the Servers list's order as far as they have answered.
        currentDone = true
        added = []
        unlisted = roundUnlisted
        addInOrder()
        settle()
    }

    /// Adds the other servers' answers in the Servers list's order, stopping
    /// at the first that is still out: its rows, and those of the servers
    /// after it, wait for it (``serverTimeout`` at most).
    private func addInOrder() {
        guard currentDone else { return }
        while turns < asked.count, !out.contains(asked[turns].id) {
            let source = asked[turns]
            if let found = early.removeValue(forKey: source.id) { added.append((source, found)) }
            turns += 1
        }
    }

    /// Asks one other server, through its own API, within ``serverTimeout``.
    private func ask(_ saved: SavedServer, _ text: String, round: Int) {
        let source = SearchSource(id: saved.server.id, name: saved.server.name)
        out.insert(source.id)
        asking[source.id]?.cancel()
        asking[source.id] = Task { [weak self, library] in
            guard let self else { return }
            let outcome: Result<SearchResults, any Error>
            do {
                outcome = .success(try await self.timed { try await library.search(text, serverID: source.id) })
            } catch {
                outcome = .failure(error)
            }
            guard !Task.isCancelled, self.round == round else { return }
            self.answered(source, outcome)
        }
    }

    private func answered(_ source: SearchSource, _ outcome: Result<SearchResults, any Error>) {
        out.remove(source.id)
        asking[source.id] = nil
        switch outcome {
        case .success(let found):
            if !anyAnswered { remember(resultsFor.isEmpty ? trimmedQuery : resultsFor) }
            anyAnswered = true
            early[source.id] = found
            answeredAt[source.id] = now()
        case .failure(let error):
            guard !(error is CancellationError) else { return }
            let line: UnlistedServer
            switch error {
            case is SignedOutError: line = UnlistedServer(server: source, line: SearchWords.signedOut(source.name), canRetry: false)
            case is ServerTooOldError:
                // It said so itself, asked which server it is: not asked again on this visit.
                tooOldNow.insert(source.id)
                line = UnlistedServer(server: source, line: SearchWords.tooOld(source.name), canRetry: false)
            default:
                failedAt[source.id] = now()
                line = UnlistedServer(server: source, line: SearchWords.didNotAnswer(source.name), canRetry: true)
            }
            roundUnlisted.append(line)
            if currentDone { unlisted = roundUnlisted }
        }
        addInOrder()
        settle()
    }

    /// Once every server has answered or failed: with none answering, the search failed.
    private func settle() {
        guard currentDone, out.isEmpty else { return }
        stillAskingTimer?.cancel()
        if !anyAnswered, let currentFailure { error = currentFailure }
    }

    private func remember(_ text: String) {
        guard !text.isEmpty else { return }
        recentQueries = Array(([text] + recentQueries.filter { $0.caseInsensitiveCompare(text) != .orderedSame }).prefix(RecentSearches.limit))
    }

    private func mark(_ target: WatchTarget, _ watched: Bool, update: (inout SearchResults) -> Void) async {
        message = await markWatched(target, watched, in: library, drawn: {
            if var r = results {
                update(&r)
                results = r
            }
        })
    }
}

/// Search's words across servers, the same on every app.
public enum SearchWords {
    public static let idleTitle = "Search your libraries"
    public static let idleMessage = "Find a show, a movie or an episode by its title."
    public static let idleTitleAcross = "Search your servers"
    public static let noMatches = "No matches"
    public static let tryAgain = "Try again"
    /// When no server answered in time.
    public static let noneAnswered = "None of your servers answered. Check the connection and try again."

    /// "Find a show, a movie or an episode on any of your 3 servers."
    public static func idleMessage(servers: Int) -> String {
        "Find a show, a movie or an episode on any of your \(servers) servers."
    }

    /// "No matches on your 3 servers".
    public static func noMatches(servers: Int) -> String { "No matches on your \(servers) servers" }

    /// "Nothing is called “harbr” on any of them. Check the spelling, or try fewer words."
    public static func noMatchesMessageAcross(_ query: String) -> String {
        "Nothing is called “\(query)” on any of them. Check the spelling, or try fewer words."
    }

    /// "Still asking Cabin server…", or "Still asking Cabin server and Den server…".
    public static func stillAsking(_ names: [String]) -> String {
        let list = names.count > 1 ? "\(names.dropLast().joined(separator: ", ")) and \(names[names.count - 1])" : names.first ?? ""
        return "Still asking \(list)…"
    }

    /// A server that was asked and didn't answer in time.
    public static func didNotAnswer(_ name: String) -> String { "\(name) didn’t answer, so its titles aren’t listed." }

    /// A registered server this device is signed out of, which isn't asked.
    public static func signedOut(_ name: String) -> String { "You’re signed out of \(name) here, so its titles aren’t listed." }

    /// A registered server older than this app works with, which isn't asked.
    public static func tooOld(_ name: String) -> String { "\(name) is too old for this app, so its titles aren’t listed." }
}

extension LibraryRepository {
    /// A picture's URL on the server its title is on: `server` for a search
    /// result from another server, nil for the one in use.
    public func imageURL(_ path: String?, size: ImageSize, on server: SearchSource?) -> URL? {
        server.map { imageURL(path, size: size, serverID: $0.id) } ?? imageURL(path, size: size)
    }
}

/// An account's recent searches on one server, kept in `defaults` between
/// launches; another account, or the same one on another server, has its own.
public struct RecentSearches: Sendable {
    public static let limit = 8
    private let key: String
    private nonisolated(unsafe) let defaults: UserDefaults

    public init(server: String, user: Int, defaults: UserDefaults = .standard) {
        key = "recentSearches.\(server).\(user)"
        self.defaults = defaults
    }

    public func load() -> [String] {
        Array((defaults.stringArray(forKey: key) ?? []).prefix(Self.limit))
    }

    public func save(_ queries: [String]) {
        if queries.isEmpty { defaults.removeObject(forKey: key) } else { defaults.set(Array(queries.prefix(Self.limit)), forKey: key) }
    }
}
