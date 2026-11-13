import Foundation
import Observation
import WMMModel

/// Search (Search.dc.html): the server's search by title, a moment after
/// typing stops, with results grouped as TV Shows, Movies and Episodes.
/// From API level 5, for someone who may request (SearchRequest.dc.html),
/// TMDB's matches that are not in the library follow as Not in your
/// library, fetched beside the library's results and never holding them up;
/// one opens the request sheet.
@MainActor
@Observable
public final class SearchModel {
    /// What the screen shows.
    public enum Phase: Equatable, Sendable {
        /// Nothing typed: "Search your libraries".
        case idle
        /// The first results are on their way.
        case loading
        case results(SearchResults)
        /// Nothing matched the query.
        case noMatches(String)
        case failed(String)
    }

    public private(set) var query = ""
    /// Session-only successful queries, newest first. Rebuilding the account's
    /// search model discards them; nothing is persisted or shared across models.
    public private(set) var recentQueries: [String] = []
    /// Nil before the first search, and while the query is empty.
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

    @ObservationIgnored private let library: any LibraryRepository
    @ObservationIgnored private let requests: (any RequestsRepository)?
    @ObservationIgnored private let navigator: Navigator
    @ObservationIgnored private let debounce: Duration
    /// The search waiting out the debounce or running; tests await it.
    @ObservationIgnored private(set) var pending: Task<Void, Never>?
    /// The search for what can be requested, beside ``pending``.
    @ObservationIgnored private(set) var pendingRequestables: Task<Void, Never>?

    public init(library: any LibraryRepository, navigator: Navigator, debounce: Duration = .milliseconds(300), requests: (any RequestsRepository)? = nil) {
        self.library = library
        self.requests = requests
        self.navigator = navigator
        self.debounce = debounce
    }

    /// Not in your library: what TMDB has that the library does not, for the
    /// query on screen; empty for someone who may not request.
    public var notInLibrary: [Requestable] {
        guard requests?.options.canRequest == true, !trimmedQuery.isEmpty else { return [] }
        return requestables.filter { $0.state != .available && requests?.options.canRequest($0.mediaType) == true }
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
        guard let results else { return .loading }
        return results.isEmpty ? .noMatches(resultsFor) : .results(results)
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

    public func retry() async {
        schedule(after: nil)
        await pending?.value
        await pendingRequestables?.value
    }

    // MARK: Taps

    public func open(_ item: BrowseItem) {
        notice = navigator.open(item)
    }

    /// An episode opens its page.
    public func open(_ episode: EpisodeCard) {
        navigator.open(episode)
    }

    public func play(_ episode: EpisodeCard) {
        guard let request = episode.playRequest else { return }
        navigator.play(request)
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

    private func schedule(after delay: Duration?) {
        pending?.cancel()
        pendingRequestables?.cancel()
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

    private func search(_ text: String) async {
        do {
            let found = try await library.search(text)
            guard !Task.isCancelled else { return }
            results = found
            resultsFor = text
            recentQueries = Array(([text] + recentQueries.filter { $0.caseInsensitiveCompare(text) != .orderedSame }).prefix(8))
            loading = false
        } catch {
            guard !Task.isCancelled, !(error is CancellationError) else { return }
            loading = false
            self.error = error.displayMessage
        }
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
