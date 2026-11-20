import Foundation
import Observation
import WMMModel

/// What this device keeps for new-episode notifications:
/// this device's switch, and the cursor into each server's feed, one for
/// every server this device is signed in to and the person signed in there,
/// keyed by their ids. Coming back to a server finds its place again, so
/// what arrived there meanwhile is announced rather than skipped. A server
/// that is removed or signed out of, or signed in to as someone else, loses
/// its record, so a new sign-in starts from now. In UserDefaults, as the
/// place each tab was left is.
@MainActor @Observable
public final class NewEpisodeMemory {
    struct Record: Codable, Equatable {
        /// "<server id>:<user id>" (``FollowsRepository/contextID``).
        var context: String
        var cursor: String?

        /// The server's id: what comes before the last colon.
        var serverID: String { Self.serverID(of: context) }

        static func serverID(of context: String) -> String {
            context.lastIndex(of: ":").map { String(context[..<$0]) } ?? context
        }
    }

    static let switchKey = "newEpisodes.notifyHere"
    /// The one record kept before there was one a server; read once and moved to ``recordsKey``.
    static let recordKey = "newEpisodes.record"
    static let recordsKey = "newEpisodes.records"
    static let explainedKey = "newEpisodes.explained"

    @ObservationIgnored private let defaults: UserDefaults
    /// One record a server.
    private var records: [Record]
    private var on: Bool
    private var explainedHere: Bool

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        on = defaults.object(forKey: Self.switchKey) as? Bool ?? true
        explainedHere = defaults.bool(forKey: Self.explainedKey)
        if let kept = defaults.data(forKey: Self.recordsKey).flatMap({ try? JSONDecoder().decode([Record].self, from: $0) }) {
            records = kept
        } else if let old = defaults.data(forKey: Self.recordKey).flatMap({ try? JSONDecoder().decode(Record.self, from: $0) }) {
            records = [old]
            save()
        } else {
            records = []
        }
    }

    /// The explainer opened on this device: it opens once, whatever the
    /// answer, with its own mark, not Remind me's.
    public var explained: Bool {
        get { explainedHere }
        set {
            explainedHere = newValue
            defaults.set(newValue, forKey: Self.explainedKey)
        }
    }

    /// The first Notify me on this device opens the explainer while iOS hasn't asked yet.
    public func shouldExplain(_ permission: NotificationPermission) -> Bool {
        permission == .notAsked && !explained
    }

    /// This device's switch for new-episode notifications: on unless turned off here.
    public var notifyHere: Bool {
        get { on }
        set {
            on = newValue
            defaults.set(newValue, forKey: Self.switchKey)
        }
    }

    /// The cursor kept for `context`; nil when none is, or the server's is another person's.
    public func cursor(for context: String) -> String? {
        records.first { $0.context == context }?.cursor
    }

    /// Keeps `cursor` as `context`'s place, over whatever was kept for its server.
    public func keep(cursor: String, for context: String) {
        let next = Record(context: context, cursor: cursor)
        guard !records.contains(next) else { return }
        records.removeAll { $0.serverID == next.serverID }
        records.append(next)
        save()
    }

    /// Drops every cursor (the switch was turned off), so the next check on each server starts from now.
    public func forgetCursor() {
        guard !records.isEmpty else { return }
        records = []
        save()
    }

    /// Forgets what is kept for every server but `servers`, the ones this
    /// device is signed in to (nil: whichever they are, all are kept), and
    /// for the server of `context` (who is signed in on the server in use;
    /// nil: nobody) what was kept there for anyone else.
    public func forget(unlessFor context: String?, servers: Set<String>? = nil) {
        let current = context.map(Record.serverID(of:))
        let kept = records.filter { record in
            if let servers, !servers.contains(record.serverID) { return false }
            return record.serverID != current || record.context == context
        }
        guard kept != records else { return }
        records = kept
        save()
    }

    /// Forgets everything kept for anyone, as on a device that never checked.
    public func forgetAll() { forgetCursor() }

    private func save() {
        defaults.removeObject(forKey: Self.recordKey)
        if !records.isEmpty, let data = try? JSONEncoder().encode(records) {
            defaults.set(data, forKey: Self.recordsKey)
        } else {
            defaults.removeObject(forKey: Self.recordsKey)
        }
    }
}
