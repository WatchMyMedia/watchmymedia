import Foundation
import Observation
import WMMModel

/// Listings for the guide, fetched in 6-hour windows on fixed boundaries and
/// merged. Asking for a span loads the windows it touches and prefetches the
/// one after, so paging later doesn't wait. A window is asked for again once
/// it is half an hour old, so a day's new listings arrive. A new server or
/// sign-in drops everything, and replies for the previous one are thrown away
/// (PLAN-LIVE-TV-GUIDE.md §6.3–§6.4, the same rules as Android's).
@MainActor @Observable
public final class LiveGuideStore {
    nonisolated public static let windowHours = 6
    nonisolated static let window: TimeInterval = Double(windowHours) * 3600
    nonisolated static let maxAge: TimeInterval = 30 * 60

    public private(set) var state: LiveGuideState?
    /// The last failure, while the span asked for has nothing to show.
    public private(set) var error: String?
    public private(set) var loading = false
    /// Channels in the server's order, with every loaded programme.
    public private(set) var channels: [LiveGuideChannel] = []

    @ObservationIgnored private let repository: any LiveTVRepository
    @ObservationIgnored private var context: String?
    /// When each window was fetched.
    @ObservationIgnored private var loaded: [Date: Date] = [:]
    @ObservationIgnored private let now: @MainActor () -> Date
    @ObservationIgnored private var inFlight: [Date: Task<Result<LiveGuide, any Error>, Never>] = [:]

    public init(repository: any LiveTVRepository, now: @escaping @MainActor () -> Date = { Date() }) {
        self.repository = repository
        self.now = now
    }

    /// Where the 6-hour window holding `date` starts.
    nonisolated public static func windowStart(for date: Date) -> Date {
        Date(timeIntervalSince1970: (date.timeIntervalSince1970 / window).rounded(.down) * window)
    }

    /// Loads the windows `from`–`to` touches, then the next one in the background.
    public func load(from: Date, to: Date) async {
        resetIfContextChanged()
        guard repository.capabilities?.listings == true else { return }
        var starts: [Date] = []
        var start = Self.windowStart(for: from)
        while start < to { starts.append(start); start += Self.window }
        let missing = starts.filter { !isFresh($0) }
        if !missing.isEmpty {
            loading = true
            defer { loading = !inFlight.isEmpty }
            for start in missing {
                if case .failure(let failure) = await fetch(start) {
                    if !(failure is CancellationError) { error = failure.displayMessage }
                    return
                }
            }
        }
        error = nil
        let after = start
        if !isFresh(after), inFlight[after] == nil, state?.listedThrough.map({ after < $0 }) ?? true {
            Task { _ = await fetch(after) }
        }
    }

    /// Whether every window up to `date` is loaded since `from`, so a gap at the end of a row is the end of its listings.
    public func isLoaded(from: Date, through date: Date) -> Bool {
        var start = Self.windowStart(for: from)
        while start < date {
            guard loaded[start] != nil else { return false }
            start += Self.window
        }
        return true
    }

    /// Drops everything, as after a new sign-in.
    public func reset() {
        inFlight.values.forEach { $0.cancel() }
        inFlight = [:]; loaded = [:]; channels = []; state = nil; error = nil; loading = false
    }

    /// Keeps what is shown but asks for every window again on the next load, as a pull to refresh does.
    public func invalidate() { loaded = [:] }

    private func isFresh(_ window: Date) -> Bool {
        guard let fetched = loaded[window] else { return false }
        return now().timeIntervalSince(fetched) < Self.maxAge
    }

    private func resetIfContextChanged() {
        if context != repository.contextID { reset(); context = repository.contextID }
    }

    private func fetch(_ start: Date) async -> Result<LiveGuide, any Error> {
        if let running = inFlight[start] { return await running.value }
        let identity = context
        let repository = repository
        let task = Task { @MainActor in
            do { return Result<LiveGuide, any Error>.success(try await repository.guide(start: start, hours: Self.windowHours)) }
            catch { return .failure(error) }
        }
        inFlight[start] = task
        let result = await task.value
        // A reply for the previous server or sign-in never lands.
        guard identity == context, identity == repository.contextID else { return .failure(CancellationError()) }
        inFlight[start] = nil
        if case .success(let guide) = result { merge(guide, window: start) }
        return result
    }

    private func merge(_ guide: LiveGuide, window: Date) {
        loaded[window] = now()
        state = guide.state
        var byID = Dictionary(channels.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        for incoming in guide.channels {
            var row = incoming
            if let existing = byID[incoming.id] {
                var programmes = Dictionary(existing.programmes.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
                for p in incoming.programmes { programmes[p.id] = p }
                row.programmes = programmes.values.sorted { $0.start < $1.start }
            }
            byID[incoming.id] = row
        }
        // The newest reply's order and channels; a channel it dropped goes.
        channels = guide.channels.compactMap { byID[$0.id] }
    }
}
