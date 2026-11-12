import Foundation
import Observation
import WMMModel

@MainActor @Observable
public final class LiveTVModel {
    public var query = ""
    public private(set) var loading = false
    public private(set) var error: String?
    @ObservationIgnored private let repository: any LiveTVRepository
    @ObservationIgnored private var generation = 0
    public init(repository: any LiveTVRepository) { self.repository = repository }
    public var channels: [LiveChannel] { repository.channels }
    public var available: Bool { repository.capabilities?.available == true }
    /// Programme listings show; otherwise the screens stay the channel list they were.
    public var listings: Bool { repository.capabilities?.listings == true }
    public var filteredChannels: [LiveChannel] {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return query.isEmpty ? channels : channels.filter { $0.name.localizedCaseInsensitiveContains(query) || $0.number.localizedCaseInsensitiveContains(query) }
    }
    public var unavailableMessage: String? {
        guard let capabilities = repository.capabilities else { return nil }
        if !capabilities.enabled { return "Live TV isn't set up on this server. Ask its admin to add a tuner in the web app." }
        if !capabilities.allowed { return "This account doesn't have Live TV access. Ask the server's admin for access." }
        if !capabilities.playback { return "Live TV playback isn't available on this server." }
        if channels.isEmpty { return "No channels are available. Ask the server's admin to refresh the tuner in the web app." }
        return nil
    }
    public func refresh() async {
        generation += 1; let current = generation, identity = repository.contextID
        loading = true; error = nil
        defer { if current == generation { loading = false } }
        do { try await repository.refresh() }
        catch { if current == generation, identity == repository.contextID, !Task.isCancelled { self.error = error.displayMessage } }
    }

    /// Keeps the channels' now and next current while a screen shows them:
    /// at the first programme's end the screen moves on to what follows by
    /// itself, and the channel list is asked again for what comes after.
    public func followSchedule(now: @escaping @MainActor () -> Date = { Date() }) async {
        await LiveSchedule.follow(repository, now: now)
    }
}

/// Moves listings on at programme ends rather than on a timer. Home, the
/// guide and the player may all follow at once; they share one refresh.
@MainActor
public enum LiveSchedule {
    private static var refreshing: Set<ObjectIdentifier> = []

    public static func follow(_ repository: any LiveTVRepository, now: @escaping @MainActor () -> Date = { Date() }) async {
        while !Task.isCancelled, repository.capabilities?.listings == true {
            let date = now()
            guard let end = repository.channels.compactMap({ $0.programme(at: date)?.end }).min() else { return }
            guard (try? await Task.sleep(for: .seconds(max(end.timeIntervalSince(date), 1)))) != nil else { return }
            let after = now()
            // The next programme is already known; ask again once a channel has nothing after it.
            let key = ObjectIdentifier(repository)
            if !refreshing.contains(key), repository.channels.contains(where: { $0.now != nil && $0.programme(after: after) == nil }) {
                refreshing.insert(key)
                try? await repository.refresh()
                refreshing.remove(key)
            }
        }
    }
}
