import Foundation
import Observation
import WMMModel

// What one card across servers shares: the choices kept on the device,
// a page's context, the words, and the time limit each server is given.

/// The copy chosen for each title, kept on this device (UserDefaults), so
/// Play plays it next time.
@MainActor
public final class CopyChoices {
    private let defaults: UserDefaults
    private let key: String

    /// `scope` keeps one person's choices apart from another's on a shared device.
    public init(defaults: UserDefaults = .standard, scope: String = "") {
        self.defaults = defaults
        key = "oneCard.chosenCopies" + (scope.isEmpty ? "" : ".\(scope)")
    }

    /// The copy chosen for `title`, as "server/file".
    public func chosen(_ title: TitleKey) -> String? {
        (defaults.dictionary(forKey: key) as? [String: String])?[title.storageKey]
    }

    public func choose(_ copyID: String, for title: TitleKey) {
        var all = (defaults.dictionary(forKey: key) as? [String: String]) ?? [:]
        all[title.storageKey] = copyID
        // A few hundred titles at most: the oldest choices go first.
        if all.count > 500 { all = Dictionary(uniqueKeysWithValues: all.suffix(400).map { ($0.key, $0.value) }) }
        defaults.set(all, forKey: key)
    }
}

/// What a title page needs to offer other servers' copies: the page's
/// services (the server it loaded from is theirs) and the choices kept on
/// this device. Without one, a page offers its own versions only.
public struct OneCardContext {
    public let services: AppServices
    public let choices: CopyChoices

    public init(services: AppServices, choices: CopyChoices) {
        self.services = services
        self.choices = choices
    }

    /// One card applies: this device is signed in to another server.
    @MainActor public var applies: Bool { !services.servers.otherCardServers.isEmpty || services.scope != nil }
}

/// One card's words, shared by iPhone, iPad and Apple TV.
public enum OneCardWords {
    public static let notStarted = "not started"
    public static let watched = "watched"
    /// Under the heading of a server reached away from home.
    public static let awayNote = "Away from home, so it may be converted to keep up."
    /// A copy's button in Versions.
    public static let playThis = "Play this"

    /// "Resume starts at 1:06:12, where you got to on Cabin, whichever you choose."
    public static func resumeNote(at ms: Int, on server: String) -> String {
        "Resume starts at \(formatClock(ms: ms)), where you got to on \(server), whichever you choose."
    }

    /// A card for a title the server in use doesn't have: "On Cabin".
    public static func on(_ server: String) -> String { "On \(server)" }

    /// A card whose Resume plays from another server: "from Cabin".
    public static func from(_ server: String) -> String { "from \(server)" }

    /// Search's line for a title on several servers: "On Home and Cabin".
    public static func onServers(_ names: [String]) -> String { "On \(liveListed(names))" }

    /// A link to what belongs to one server, opened while another is in use.
    public static func forAnotherServer(_ name: String) -> String {
        "That’s for \(name). Switch to it in Servers to open it."
    }

    /// A row on Home whose server didn't answer.
    public static func homeDidNotAnswer(_ name: String) -> String { "\(name) didn’t answer, so its titles aren’t on Home." }

    /// The libraries' line for a server that didn't answer.
    public static func libraryDidNotAnswer(_ name: String) -> String { SearchWords.didNotAnswer(name) }

    /// The libraries' line for a server too old for a filter: "Cabin server can’t filter by rating, so its movies aren’t listed."
    public static func cantFilter(_ name: String, by what: String, noun: String) -> String {
        "\(name) can’t filter by \(what), so its \(noun)s aren’t listed."
    }
}


/// Runs `work`, giving it `limit`: its answer, or nil when it fails or
/// isn't in by then (it is cancelled). For one card's questions to other
/// servers, each of which gets ``cardServerTimeout``.
@MainActor
func withinLimit<T: Sendable>(_ limit: Duration, sleep: @escaping @MainActor (Duration) async throws -> Void,
                              _ work: @escaping @MainActor () async throws -> T) async -> T? {
    let once = Once()
    var job: Task<Void, Never>?
    var timer: Task<Void, Never>?
    let value: T? = await withCheckedContinuation { (continuation: CheckedContinuation<T?, Never>) in
        job = Task { @MainActor in
            let value = try? await work()
            if once.first() { continuation.resume(returning: value) }
        }
        timer = Task { @MainActor in
            try? await sleep(limit)
            if once.first() { continuation.resume(returning: nil) }
        }
    }
    job?.cancel()
    timer?.cancel()
    return value
}

/// True the first time it is asked.
@MainActor
private final class Once {
    private var done = false

    func first() -> Bool {
        defer { done = true }
        return !done
    }
}

extension OneCardWords {
    /// A page from another server this device is no longer signed in to.
    public static func unavailable(_ name: String) -> (title: String, message: String) {
        ("\(name) isn’t available", "Sign in to \(name) again in Servers to open its titles here.")
    }

    /// The name of a server for a page's words, "another server" when it isn't known.
    @MainActor public static func name(of serverID: String, in servers: any ServerRepository) -> String {
        servers.saved.first { $0.server.id == serverID }?.server.name ?? "That server"
    }
}
