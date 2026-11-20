import Foundation
import Observation
import WMMModel

/// What this device keeps for new-episode notifications:
/// this device's switch, and the cursor into the server's feed
/// for one server and person, keyed by their ids.
/// One record: another server or person, or nobody signed in, forgets it, so
/// a new sign-in starts from now. In UserDefaults, as the place each tab was
/// left is.
@MainActor @Observable
public final class NewEpisodeMemory {
    struct Record: Codable, Equatable {
        /// "<server id>:<user id>" (``FollowsRepository/contextID``).
        var context: String
        var cursor: String?
    }

    static let switchKey = "newEpisodes.notifyHere"
    static let recordKey = "newEpisodes.record"
    static let explainedKey = "newEpisodes.explained"

    @ObservationIgnored private let defaults: UserDefaults
    private var record: Record?
    private var on: Bool
    private var explainedHere: Bool

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        on = defaults.object(forKey: Self.switchKey) as? Bool ?? true
        explainedHere = defaults.bool(forKey: Self.explainedKey)
        record = defaults.data(forKey: Self.recordKey).flatMap { try? JSONDecoder().decode(Record.self, from: $0) }
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

    /// The cursor kept for `context`; nil when none is, or it is another's.
    public func cursor(for context: String) -> String? {
        record?.context == context ? record?.cursor : nil
    }

    public func keep(cursor: String, for context: String) {
        update(context) { $0.cursor = cursor }
    }

    /// Drops the cursor, so the next check starts from now.
    public func forgetCursor() {
        guard record?.cursor != nil else { return }
        record?.cursor = nil
        save()
    }

    /// Forgets everything kept for anyone other than `context` (nil: nobody is signed in).
    public func forget(unlessFor context: String?) {
        guard let record, record.context != context else { return }
        self.record = nil
        save()
    }

    private func update(_ context: String, _ change: (inout Record) -> Void) {
        var next = record?.context == context ? record! : Record(context: context)
        change(&next)
        guard next != record else { return }
        record = next
        save()
    }

    private func save() {
        if let record, let data = try? JSONEncoder().encode(record) {
            defaults.set(data, forKey: Self.recordKey)
        } else {
            defaults.removeObject(forKey: Self.recordKey)
        }
    }
}
