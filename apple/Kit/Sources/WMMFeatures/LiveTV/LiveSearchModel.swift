import Foundation
import Observation
import WMMModel

/// Search listings (PLAN-LIVE-TV-GUIDE.md §6.5): programmes on now and later
/// whose title or episode title has every word typed, soonest first, a moment
/// after typing stops. On the Search tab its On Live TV group follows the
/// library's; from the guide's search button it is the whole page. Only from
/// servers at ``liveExtrasAPILevel`` with listings, and from two characters.
@MainActor @Observable
public final class LiveSearchModel {
    public enum Phase: Equatable, Sendable {
        /// Nothing to search for yet, or no listings search on this server.
        case idle
        case loading
        case results([LiveSearchHit])
        /// Nothing in the listings matched this query.
        case empty(String)
        case failed(String)
    }

    public private(set) var query = ""
    public private(set) var hits: [LiveSearchHit] = []
    /// The query ``hits`` are for.
    public private(set) var hitsFor = ""
    public private(set) var loading = false
    public private(set) var error: String?

    @ObservationIgnored private let repository: any LiveTVRepository
    @ObservationIgnored public let extras: LiveExtrasStore?
    @ObservationIgnored private let debounce: Duration
    @ObservationIgnored private let now: @MainActor () -> Date
    @ObservationIgnored private var generation = 0
    /// The search waiting out the debounce or running; tests await it.
    @ObservationIgnored public private(set) var pending: Task<Void, Never>?

    public init(repository: any LiveTVRepository, extras: LiveExtrasStore? = nil, debounce: Duration = .milliseconds(300),
                now: @escaping @MainActor () -> Date = { Date() }) {
        self.repository = repository
        self.extras = extras
        self.debounce = debounce
        self.now = now
    }

    public var available: Bool { repository.capabilities?.extrasAvailable == true }
    /// Where listings end, for the notes: "until the listings end Wed 7 PM".
    public var listedThrough: Date? { extras?.listedThrough }
    private var trimmed: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    public var phase: Phase {
        guard available, trimmed.count >= liveSearchMinimumLength else { return .idle }
        if let error { return .failed(error) }
        if hitsFor.isEmpty { return .loading }
        return hits.isEmpty ? .empty(hitsFor) : .results(hits)
    }

    /// The field changed: searches a moment after typing stops.
    public func setQuery(_ text: String) {
        guard text != query else { return }
        query = text
        schedule(after: debounce)
    }

    public func retry() async {
        schedule(after: nil)
        await pending?.value
    }

    /// The server's Live TV became known (after launch or a sign-in): a query
    /// typed before then is searched now.
    public func availabilityChanged() {
        guard available, trimmed.count >= liveSearchMinimumLength, hitsFor != trimmed, !loading else { return }
        schedule(after: nil)
    }

    private func schedule(after delay: Duration?) {
        pending?.cancel()
        generation += 1
        let mine = generation
        let q = trimmed
        guard available, q.count >= liveSearchMinimumLength else {
            hits = []; hitsFor = ""; loading = false; error = nil; pending = nil
            return
        }
        pending = Task { [weak self] in
            if let delay { guard (try? await Task.sleep(for: delay)) != nil else { return } }
            await self?.search(q, generation: mine)
        }
    }

    private func search(_ q: String, generation mine: Int) async {
        loading = true
        defer { if mine == generation { loading = false } }
        let identity = repository.contextID
        // Search's notes say where listings end; ask once if no guide has said.
        async let state: Date? = listingsEnd()
        do {
            let found = try await repository.searchListings(q, limit: 50)
            guard mine == generation, identity == repository.contextID else { return }
            hits = found
            hitsFor = q
            error = nil
            extras?.noteProgrammes(found.map(\.programme))
        } catch {
            guard mine == generation, !(error is CancellationError) else { return }
            self.error = error.displayMessage
        }
        if let end = await state, extras?.listedThrough == nil { extras?.listedThrough = end }
    }

    private func listingsEnd() async -> Date? {
        guard let extras, extras.listedThrough == nil else { return nil }
        return try? await repository.guide(start: LiveGuideStore.windowStart(for: now()), hours: 1).state.listedThrough
    }
}
