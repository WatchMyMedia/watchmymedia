import Foundation
import Observation
import WMMModel

/// A TV Shows or Movies grid (Library.dc.html): a title search, the filter
/// chips, the genre (and, for movies, decade) chips from the facets, a sort,
/// and the posters a page at a time.
@MainActor
@Observable
public final class LibraryModel {
    /// What the grid holds.
    public struct Grid: Hashable, Sendable {
        public var items: [BrowseItem]
        /// Items matching the query, across all pages.
        public var total: Int
        public var genres: [String]
        public var decades: [Int]
        /// The whole library's counts; nil from servers that do not send them.
        public var summary: LibrarySummary?
        public var letters: [LetterOffset]?
        public var genreCounts: [String: Int]?
        /// The first item held, after jumping directly to a letter.
        public var offset: Int

        public init(items: [BrowseItem] = [], total: Int = 0, genres: [String] = [], decades: [Int] = [], summary: LibrarySummary? = nil, letters: [LetterOffset]? = nil, genreCounts: [String: Int]? = nil, offset: Int = 0) {
            self.items = items
            self.total = total
            self.genres = genres
            self.decades = decades
            self.summary = summary
            self.letters = letters
            self.genreCounts = genreCounts
            self.offset = offset
        }

        public var hasMore: Bool { offset + items.count < total }
    }

    public static let pageSize = 100

    public let type: LibraryType
    public private(set) var state: Loadable<Grid> = .loading
    /// The server has no library of this type.
    public private(set) var noLibrary = false
    public private(set) var loadingMore = false
    /// Loading the next page failed; the grid offers Try again at its end.
    public private(set) var moreError: String?
    public private(set) var sort: ItemSort = .added
    public private(set) var filter: ItemFilter = .all
    public private(set) var genre: String?
    public private(set) var decade: Int?
    /// The search field; the grid follows it a moment after typing stops.
    public private(set) var searchText = ""
    public private(set) var selectedLetter: String?
    public private(set) var scrollTargetID: Int?
    /// Changes on every jump, including a repeated tap on the same letter.
    public private(set) var scrollRequestID = 0
    private var changingQuery = false
    public var message: String?
    public var notice: Notice?
    /// The library's rows in the person's order, above the grid (PLAN-ROWS.md
    /// Decision 11); empty below ``rowsAPILevel``.
    public private(set) var rows: [ScreenRow] = []

    @ObservationIgnored private let library: any LibraryRepository
    @ObservationIgnored private let rowsRepository: (any RowsRepository)?
    @ObservationIgnored private let navigator: Navigator
    @ObservationIgnored private let pageSize: Int
    @ObservationIgnored private let debounce: Duration
    @ObservationIgnored private(set) var libraryID: Int?
    @ObservationIgnored private var generation = 0
    /// The search waiting out the debounce; tests await it.
    @ObservationIgnored private(set) var pendingSearch: Task<Void, Never>?

    /// `libraryID` skips looking the library up (the Apple TV app knows it from Home).
    /// `rows` gives the screen the library's arranged rows above the grid.
    public init(library: any LibraryRepository, navigator: Navigator, type: LibraryType, libraryID: Int? = nil, rows: (any RowsRepository)? = nil, pageSize: Int = LibraryModel.pageSize, debounce: Duration = .milliseconds(300)) {
        self.library = library
        self.rowsRepository = rows
        self.navigator = navigator
        self.type = type
        self.libraryID = libraryID
        self.pageSize = pageSize
        self.debounce = debounce
    }

    // MARK: Wording

    public var title: String { type == .tv ? "TV Shows" : "Movies" }
    public var noun: String { type == .tv ? "show" : "movie" }
    public var searchPrompt: String { type == .tv ? "Search shows" : "Search movies" }
    public var filters: [ItemFilter] { ItemFilter.options(for: type) }
    public var sorts: [ItemSort] { ItemSort.options(for: type) }
    /// Decade chips are for movies on the iPhone's filter row.
    public var decades: [Int] { decades(includingShows: false) }

    /// The decade chips from the facets: for movies always, and for shows too
    /// where the filter row has the room for them (the iPad's, PadLibrary.dc.html)
    /// or a Decade menu takes them (Apple TV's, TvLibrary.dc.html).
    public func decades(includingShows: Bool) -> [Int] {
        type == .movie || includingShows ? state.value?.decades ?? [] : []
    }
    public var genres: [String] { state.value?.genres ?? [] }
    /// Exact totals supplied by the server; older servers leave unknown counts blank.
    public var genreCounts: [String: Int] { changingQuery ? [:] : state.value?.genreCounts ?? [:] }
    public var activeFilterCount: Int { (filter == .all ? 0 : 1) + (genre == nil ? 0 : 1) + (decade == nil ? 0 : 1) }
    public var letters: [LetterOffset] { sort == .title && !changingQuery ? state.value?.letters ?? [] : [] }
    public var sortLabel: String { "Sort: \(sort.label)" }

    /// The Decade chip: "Decade", or the decade chosen ("1990s").
    public var decadeChipLabel: String { decade.map(decadeLabel) ?? "Decade" }

    /// Anything narrowing the grid: a filter, genre, decade or title search.
    public var isFiltered: Bool { filter != .all || genre != nil || decade != nil || queryText != nil }

    /// "142 shows", the whole library, beside the title; "0 movies · 4 not
    /// matched yet" while files wait for a match.
    public var headerCount: String? {
        guard let grid = state.value, !noLibrary else { return nil }
        guard let summary = grid.summary else { return plural(grid.total, noun) }
        return [plural(summary.items, noun), unmatchedCount(summary.unmatched)].compactMap { $0 }.joined(separator: " · ")
    }

    /// Over the grid: "16 shows · 312 episodes" for the whole library, or
    /// what matches: "3 shows in Sci-Fi", "1 movie from the 2010s".
    public var resultLine: String? {
        guard let grid = state.value, !noLibrary else { return nil }
        if !isFiltered, let summary = grid.summary {
            return [plural(summary.items, noun), summary.episodes > 0 ? plural(summary.episodes, "episode") : nil, unmatchedCount(summary.unmatched)]
                .compactMap { $0 }.joined(separator: " · ")
        }
        var parts = [plural(grid.total, noun)]
        if let genre { parts.append("in \(genre)") }
        if let decade { parts.append("from the \(decadeLabel(decade))") }
        if let text = queryText { parts.append("matching “\(text)”") }
        return parts.joined(separator: " ")
    }

    /// Under the title on Apple TV: the whole library with the shows still
    /// running ("142 shows · 3,418 episodes · 12 continuing"), or what matches.
    public var overviewLine: String? {
        guard let line = resultLine else { return nil }
        guard !isFiltered, type == .tv, let continuing = state.value?.summary?.continuing, continuing > 0 else { return line }
        return "\(line) · \(formatCount(continuing)) continuing"
    }

    /// The empty grid's words: no library, nothing matching, or nothing yet.
    public var emptyMessage: (title: String, message: String) {
        if noLibrary {
            return (type == .tv ? "No TV library" : "No movie library", "This server has no \(title) library yet. Add one in the web app.")
        }
        if isFiltered { return ("Nothing matches", "No \(noun)s match these filters.") }
        return ("No \(noun)s yet", "When the server has scanned the library, its \(noun)s appear here.")
    }

    // MARK: Loading

    /// Loads the grid, then reloads quietly whenever the library (or the
    /// person's rows and lists) changes, until cancelled.
    public func run() async {
        await followChanges(of: library, also: rowsRepository?.changes(), firstQuiet: state.value != nil) { quiet in await load(quiet ? .quiet : .first) }
    }

    // MARK: Rows

    /// The screen the rows are arranged on, once the library is known.
    public var screen: RowScreen? { libraryID.map(RowScreen.library) }

    /// The rows above the grid: none while a title search narrows the grid.
    public var shownRows: [ScreenRow] { queryText == nil && !noLibrary ? rows.filter { $0.style != .live && !$0.isEmpty } : [] }

    /// A title search narrows the grid, so the rows step aside.
    public var isSearching: Bool { queryText != nil }

    /// The grid's heading below the rows: "All movies", "All shows".
    public var allTitle: String { type == .tv ? "All shows" : "All movies" }

    /// Edit rows is offered: the server arranges rows and this library is known.
    public var canEditRows: Bool { rowsRepository?.available == true && libraryID != nil && !noLibrary }

    /// A row's See all: a list's page, or the grid below with the row's sort and filters.
    public func open(_ link: RowLink) async {
        switch link {
        case .list(let id):
            navigator.open(.mediaList(id: id))
        case .library(_, let sort, let filter, let genre, let decade):
            self.sort = sort.flatMap { sorts.contains($0) ? $0 : nil } ?? self.sort
            self.filter = filter.flatMap { filters.contains($0) ? $0 : nil } ?? .all
            self.genre = genre
            self.decade = decade
            searchText = ""
            pendingSearch?.cancel()
            await load(.change)
        }
    }

    /// The library's rows; nil when they couldn't be read (the grid still
    /// works, and the rows come back with the next change or refresh).
    private func fetchRows(_ libraryID: Int) async -> [ScreenRow]? {
        guard let rowsRepository, rowsRepository.available else { return [] }
        return try? await rowsRepository.libraryRows(libraryID: libraryID)
    }

    public func retry() async { await load(.first) }

    /// Pull to refresh: keeps the grid, and says why if it fails.
    public func refresh() async { await load(.quiet) }

    public func setSort(_ sort: ItemSort) async {
        guard sort != self.sort else { return }
        self.sort = sort
        await load(.change)
    }

    public func setFilter(_ filter: ItemFilter) async {
        guard filter != self.filter else { return }
        self.filter = filter
        await load(.change)
    }

    /// Picking the selected genre again clears it.
    public func toggleGenre(_ genre: String) async {
        self.genre = self.genre == genre ? nil : genre
        await load(.change)
    }

    /// Picking the selected decade again clears it.
    public func toggleDecade(_ decade: Int) async {
        self.decade = self.decade == decade ? nil : decade
        await load(.change)
    }

    public func clearFilters() async {
        filter = .all
        genre = nil
        decade = nil
        searchText = ""
        pendingSearch?.cancel()
        await load(.change)
    }

    /// The search field changed: the grid follows a moment after typing stops.
    public func setSearchText(_ text: String) {
        guard text != searchText else { return }
        let before = queryText
        searchText = text
        guard queryText != before else { return }
        pendingSearch?.cancel()
        changingQuery = true
        pendingSearch = Task { [weak self, debounce] in
            try? await Task.sleep(for: debounce)
            guard !Task.isCancelled else { return }
            await self?.load(.change)
        }
    }

    /// A poster came on screen: near the end, the next page loads.
    public func itemAppeared(_ item: BrowseItem) async {
        guard let grid = state.value, grid.items.suffix(12).contains(where: { $0.id == item.id }) else { return }
        await loadMore()
    }

    /// Loads the next page, when there is one and nothing else is loading.
    public func loadMore() async {
        guard let grid = state.value, grid.hasMore, !changingQuery, !loadingMore, moreError == nil, let id = libraryID else { return }
        let mine = generation
        loadingMore = true
        do {
            let page = try await library.items(query(id, offset: grid.offset + grid.items.count, limit: pageSize))
            guard mine == generation, var current = state.value else { return }
            let known = Set(current.items.map(\.id))
            current.items += page.items.filter { !known.contains($0.id) }
            current.total = page.total
            current.genreCounts = page.genreCounts
            state = .loaded(current)
        } catch is CancellationError {
        } catch {
            if mine == generation { moreError = error.displayMessage }
        }
        if mine == generation { loadingMore = false }
    }

    /// Uses the server's whole-list offset, so letters outside the loaded page work too.
    public func jump(to letter: LetterOffset) async {
        guard sort == .title, let grid = state.value, let id = libraryID,
              letters.contains(letter), letter.offset >= 0, letter.offset < grid.total else { return }
        // Even an in-memory jump supersedes a pending page or another letter's request.
        generation += 1
        let mine = generation
        loadingMore = false
        moreError = nil
        if letter.offset >= grid.offset && letter.offset < grid.offset + grid.items.count {
            selectedLetter = letter.letter
            scrollTargetID = grid.items[letter.offset - grid.offset].id
            scrollRequestID += 1
            return
        }
        loadingMore = true
        do {
            let page = try await library.items(query(id, offset: letter.offset, limit: pageSize))
            guard mine == generation else { return }
            state = .loaded(Grid(items: page.items, total: page.total, genres: page.genres, decades: page.decades,
                                 summary: page.summary, letters: page.letters, genreCounts: page.genreCounts, offset: page.offset))
            selectedLetter = letter.letter
            scrollTargetID = page.items.first?.id
            scrollRequestID += 1
        } catch is CancellationError {
        } catch {
            if mine == generation { message = error.displayMessage }
        }
        if mine == generation { loadingMore = false }
    }

    public func retryMore() async {
        moreError = nil
        await loadMore()
    }

    // MARK: Taps

    public func open(_ item: BrowseItem) {
        notice = navigator.open(item)
    }

    /// A row's episode card opens the episode's page.
    public func open(_ episode: EpisodeCard) {
        navigator.open(episode)
    }

    public func play(_ episode: EpisodeCard) {
        guard let request = episode.playRequest else { return }
        navigator.play(request)
    }

    public func resume(_ item: ContinueItem) {
        navigator.play(item.playRequest)
    }

    /// A mark from a row's card; the rows and grid follow.
    public func setWatched(_ target: WatchTarget, _ watched: Bool) async {
        do {
            try await library.mark(target, watched: watched)
            message = markedMessage(watched: watched)
            await load(.quiet)
        } catch {
            message = error.displayMessage
        }
    }

    public func setWatched(_ item: BrowseItem, _ watched: Bool) async {
        guard let target = item.watchTarget else { return }
        do {
            try await library.mark(target, watched: watched)
            if var grid = state.value {
                grid.items = grid.items.map { i in
                    guard i.id == item.id else { return i }
                    var copy = i
                    copy.watched = watched
                    if watched { copy.progress = nil }
                    return copy
                }
                state = .loaded(grid)
            }
            message = markedMessage(watched: watched)
        } catch {
            message = error.displayMessage
        }
    }

    // MARK: Internals

    enum Reason {
        /// The first load, or Try again: a spinner, and an error page on failure.
        case first
        /// A new sort, filter or search: the grid stays until the new one arrives.
        case change
        /// The library changed, or pull to refresh: the grid stays, with as
        /// many items as it had, and a failure is a message.
        case quiet
    }

    private var queryText: String? {
        let t = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }

    private func query(_ libraryID: Int, offset: Int, limit: Int) -> ItemQuery {
        ItemQuery(libraryID: libraryID, sort: sort, filter: filter, genre: genre, decade: decade, text: queryText, offset: offset, limit: limit)
    }

    func load(_ reason: Reason) async {
        generation += 1
        let mine = generation
        loadingMore = false
        moreError = nil
        if reason == .first || state.value == nil { state = .loading }
        if reason != .quiet {
            changingQuery = true
            selectedLetter = nil
            scrollTargetID = nil
        }
        let offset = reason == .quiet ? state.value?.offset ?? 0 : 0
        let limit = reason == .quiet ? max(pageSize, state.value?.items.count ?? 0) : pageSize
        // The rows load beside the grid (not for a new sort or filter, which leaves them as they are).
        var fetchedRows: [ScreenRow]?
        let result = await Loadable.run { () async throws -> Grid? in
            guard let id = try await self.resolveLibrary() else { return nil }
            let rowsTask = reason == .change ? nil : Task { await self.fetchRows(id) }
            defer { rowsTask?.cancel() }
            let page = try await self.library.items(self.query(id, offset: offset, limit: limit))
            fetchedRows = await rowsTask?.value
            return Grid(items: page.items, total: page.total, genres: page.genres, decades: page.decades, summary: page.summary, letters: page.letters, genreCounts: page.genreCounts, offset: page.offset)
        }
        guard let result, mine == generation else { return }
        changingQuery = false
        switch result {
        case .loaded(let grid?):
            noLibrary = false
            state = .loaded(grid)
            if let fetchedRows { rows = fetchedRows }
        case .loaded(nil):
            noLibrary = true
            rows = []
            state = .loaded(Grid())
        case .failed(let text) where reason == .quiet && state.value != nil:
            message = text
        case .failed(let text):
            state = .failed(text)
        case .loading:
            break
        }
    }

    /// The library of this type: from the server's list, else from Home's IDs.
    private func resolveLibrary() async throws -> Int? {
        if let libraryID { return libraryID }
        var id = try await library.libraries().first { $0.type == type }?.id
        if id == nil {
            let home = try await library.home()
            id = type == .tv ? home.tvLibraryID : home.movieLibraryID
        }
        libraryID = id
        return id
    }
}
