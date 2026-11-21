import Foundation
import WMMFakes
import WMMModel

/// The fake library with every call recorded, a switch that makes calls
/// fail, and changes announced the way the real repository announces them.
@MainActor
final class TestLibrary: LibraryRepository {
    let fake = FakeLibraryRepository()
    /// Thrown by every call while set.
    var failure: (any Error)?
    /// Replaces the fake's libraries, for a server without one of a type.
    var librariesOverride: [Library]?
    var librariesFailure: (any Error)?
    /// Replaces the fake's Home.
    var homeOverride: HomeData?
    /// Replaces the fake's grid pages.
    var itemsOverride: [BrowseItem]?
    var lettersOverride: [LetterOffset]?
    var itemsHandler: ((ItemQuery) async throws -> ItemPage)?

    private(set) var homeCalls = 0
    private(set) var librariesCalls = 0
    private(set) var showCalls = 0
    private(set) var seasonCalls: [Int] = []
    private(set) var movieCalls = 0
    private(set) var queries: [ItemQuery] = []
    private(set) var searches: [String] = []
    private(set) var watchCalls: [String] = []
    /// Each page whose related titles were asked for, in order.
    private(set) var relatedCalls: [RelatedTitle] = []
    /// Thrown by relatedRows alone while set.
    var relatedFailure: (any Error)?
    /// Holds relatedRows until it returns, for a reply still on its way.
    var relatedGate: (@MainActor () async -> Void)?
    private var listeners: [UUID: AsyncStream<Void>.Continuation] = [:]
    private var typedListeners: [UUID: AsyncStream<LibraryChange>.Continuation] = [:]

    private func check() throws {
        if let failure { throw failure }
    }

    func home() async throws -> HomeData {
        homeCalls += 1
        try check()
        if let homeOverride { return homeOverride }
        return try await fake.home()
    }

    func libraries() async throws -> [Library] {
        librariesCalls += 1
        try check()
        if let librariesFailure { throw librariesFailure }
        if let librariesOverride { return librariesOverride }
        return try await fake.libraries()
    }

    func items(_ query: ItemQuery) async throws -> ItemPage {
        try check()
        queries.append(query)
        if let itemsHandler { return try await itemsHandler(query) }
        if let itemsOverride {
            return ItemPage(items: Array(itemsOverride.dropFirst(query.offset).prefix(query.limit)), total: itemsOverride.count, offset: query.offset, genres: [], decades: [], letters: query.sort == .title ? lettersOverride : nil)
        }
        return try await fake.items(query)
    }

    func show(id: Int) async throws -> ShowDetail {
        showCalls += 1
        try check()
        return try await fake.show(id: id)
    }

    func season(showID: Int, number: Int) async throws -> SeasonDetail {
        seasonCalls.append(number)
        try check()
        return try await fake.season(showID: showID, number: number)
    }

    func movie(id: Int) async throws -> MovieDetail {
        movieCalls += 1
        try check()
        return try await fake.movie(id: id)
    }

    func search(_ text: String) async throws -> SearchResults {
        try check()
        searches.append(text)
        if let searchGate { try await searchGate.wait() }
        return try await fake.search(text)
    }

    /// Holds the server in use's search until opened, for an answer still on its way.
    var searchGate: Gate?
    /// What each other server answers a search with, by server ID; one with
    /// none answers from the fake's servers. A gate holds its answer.
    var otherAnswers: [String: Result<SearchResults, any Error>] = [:]
    var otherGates: [String: Gate] = [:]
    /// Each search asked of another server, in order.
    private(set) var otherSearches: [(server: String, text: String)] = []
    /// The searches of other servers that were given up on before they answered.
    private(set) var otherCancelled: [String] = []
    /// How many visits to Search were said to have ended.
    private(set) var visitsEnded = 0

    func searchVisitEnded() { visitsEnded += 1 }

    func search(_ text: String, serverID: String) async throws -> SearchResults {
        otherSearches.append((serverID, text))
        if let gate = otherGates[serverID] {
            do { try await gate.wait() } catch {
                otherCancelled.append(serverID)
                throw error
            }
        }
        if let answer = otherAnswers[serverID] { return try answer.get() }
        return try await fake.search(text, serverID: serverID)
    }

    func imageURL(_ path: String?, size: ImageSize, serverID: String) -> URL? {
        path.flatMap { URL(string: "https://\(serverID).example\($0)") }
    }

    func setWatched(_ kind: WatchKind, id: Int, watched: Bool) async throws {
        try check()
        watchCalls.append("\(kind) \(id) \(watched)")
        try await fake.setWatched(kind, id: id, watched: watched)
        announceChange(.watched(kind, ids: [id], watched: watched))
    }

    func setSeasonWatched(showID: Int, season: Int, watched: Bool) async throws {
        try check()
        watchCalls.append("season \(showID) \(season) \(watched)")
        try await fake.setSeasonWatched(showID: showID, season: season, watched: watched)
        announceChange(.markedShow(id: showID, season: season, watched: watched))
    }

    func setShowWatched(showID: Int, watched: Bool) async throws {
        try check()
        watchCalls.append("show \(showID) \(watched)")
        try await fake.setShowWatched(showID: showID, watched: watched)
        announceChange(.markedShow(id: showID, season: nil, watched: watched))
    }

    func playRequest(fileID: Int) async throws -> PlayRequest {
        try check()
        return try await fake.playRequest(fileID: fileID)
    }

    /// The fake's: offered unless its server is below API level 30.
    var offersRelated: Bool { fake.offersRelated }

    func relatedRows(_ title: RelatedTitle) async throws -> [ScreenRow] {
        relatedCalls.append(title)
        try check()
        await relatedGate?()
        if let relatedFailure { throw relatedFailure }
        return try await fake.relatedRows(title)
    }

    func imageURL(_ path: String?, size: ImageSize) -> URL? { nil }

    func changes() -> AsyncStream<Void> {
        AsyncStream { continuation in
            let id = UUID()
            listeners[id] = continuation
            continuation.onTermination = { [weak self] _ in Task { @MainActor in self?.listeners[id] = nil } }
        }
    }

    func libraryChanges() -> AsyncStream<LibraryChange> {
        AsyncStream { continuation in
            let id = UUID()
            typedListeners[id] = continuation
            continuation.onTermination = { [weak self] _ in Task { @MainActor in self?.typedListeners[id] = nil } }
        }
    }

    /// What the real repository does on a change: every listener hears it,
    /// told what changed (by default, that anything may have).
    func announceChange(_ change: LibraryChange = .unknown) {
        for listener in listeners.values { listener.yield() }
        for listener in typedListeners.values { listener.yield(change) }
    }

    var listenerCount: Int { listeners.count + typedListeners.count }
}

/// A play-plan source that records what it was asked.
@MainActor
final class TestPlayback: PlaybackControllerFactory {
    let fake = FakePlaybackFactory()
    private(set) var asked: [[Int]] = []
    /// Answers nothing, as a server that cannot say.
    var unknown = false

    func make() -> any PlaybackController { fake.make() }

    func plans(fileIDs: [Int]) async -> [Int: PlaybackPlan] {
        asked.append(fileIDs)
        return unknown ? [:] : await fake.plans(fileIDs: fileIDs)
    }
}

/// Waits (briefly, a few milliseconds at a time) until `condition` holds; false after `timeout`.
@MainActor
func eventually(timeout: Duration = .seconds(2), _ condition: () -> Bool) async -> Bool {
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: timeout)
    while !condition() {
        if clock.now > deadline { return false }
        try? await Task.sleep(for: .milliseconds(2))
    }
    return true
}

/// A fixed day, so relative dates read the same whenever the tests run.
let testToday: Date = parseDay("2026-10-30")!

/// An unmatched film (one file) and an unmatched show folder, as a real server sends them.
let unmatchedFilm = BrowseItem(kind: .unmatched, id: 7001, libraryID: FakeData.movieLibrary, title: "Glass Tide (2023).mkv", subtitle: "Not looked up yet", fileName: "Glass Tide (2023)/Glass Tide (2023).mkv", fileCount: 1)
let unmatchedFolder = BrowseItem(kind: .unmatched, id: 7002, libraryID: FakeData.tvLibrary, title: "Night Shift Diner (2020)", subtitle: "Not looked up yet", fileName: "Night Shift Diner (2020)", fileCount: 6)

/// Holds a call until the test opens it, or until the caller gives up on it
/// (as a cancelled request ends), when it throws.
@MainActor
final class Gate {
    private var waiters: [Int: CheckedContinuation<Void, Never>] = [:]
    private var next = 0
    private var opened = false
    /// Calls held now.
    var waiting: Int { waiters.count }

    func wait() async throws {
        if opened { return }
        let id = next
        next += 1
        await withTaskCancellationHandler {
            await withCheckedContinuation { waiters[id] = $0 }
        } onCancel: {
            Task { @MainActor in self.waiters.removeValue(forKey: id)?.resume() }
        }
        try Task.checkCancellation()
    }

    /// Lets through the calls held and every later one.
    func open() {
        opened = true
        let held = waiters
        waiters = [:]
        for continuation in held.values { continuation.resume() }
    }
}
