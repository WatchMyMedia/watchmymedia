import Foundation
import OSLog
import WMMModel
#if os(iOS)
import MetricKit
#endif

/// Diagnostics a person can copy (PLAN-APPLE-REVIEW.md §6.4): MetricKit's
/// launch, hang and crash reports on iPhone and iPad, kept as summaries in
/// `DiagnosticsStore`, and the text a "Copy diagnostics" row copies. Nothing
/// is sent anywhere and no SDK is added. MetricKit is not on tvOS, so on
/// Apple TV `start()` does nothing and the text has no system reports.
public enum Diagnostics {
    /// The subsystem the app logs under; only its lines are copied.
    public static let subsystem = "media.watchmy.client"

    #if os(iOS)
    @MainActor private static var subscriber: MetricKitSubscriber?
    #endif

    /// Starts keeping MetricKit's reports, including any delivered while the
    /// app wasn't running. Call once at launch; later calls do nothing.
    @MainActor
    public static func start(store: DiagnosticsStore = .shared) {
        #if os(iOS)
        guard subscriber == nil else { return }
        let subscriber = MetricKitSubscriber(store: store)
        Self.subscriber = subscriber
        let manager = MXMetricManager.shared
        manager.add(subscriber)
        subscriber.didReceive(manager.pastDiagnosticPayloads)
        #endif
    }

    /// Whether this platform delivers system reports (false on Apple TV).
    public static var keepsSystemReports: Bool {
        #if os(iOS)
        true
        #else
        false
        #endif
    }

    /// What the report holds now: the app and device, `server`'s version and
    /// API level (never its name or address), the kept summaries and this
    /// run's log lines.
    public static func report(server: Server?, store: DiagnosticsStore = .shared, now: Date = Date(), bundle: Bundle = .main) -> DiagnosticsReport {
        DiagnosticsReport(
            appVersion: ClientIdentity.version(bundle),
            deviceModel: hardwareModel(),
            system: systemName(),
            serverVersion: server?.version,
            serverAPILevel: server?.apiLevel,
            summaries: store.latest(),
            logLines: recentLogLines(),
            generatedAt: now
        )
    }

    /// The text to copy. `servers` are every remembered server (their names,
    /// addresses and account names are redacted wherever they appear), and
    /// `names` anything else that must not show (this device's name, titles).
    public static func text(server: Server?, servers: [SavedServer] = [], names: [String] = [], store: DiagnosticsStore = .shared,
                            now: Date = Date(), bundle: Bundle = .main) -> String {
        report(server: server, store: store, now: now, bundle: bundle)
            .text(redactor: redactor(servers: servers, current: server, names: names))
    }

    /// A redactor that knows the servers' names, hosts and accounts.
    public static func redactor(servers: [SavedServer], current: Server?, names: [String]) -> DiagnosticsRedactor {
        var known = names
        for server in servers.map(\.server) + [current].compactMap({ $0 }) {
            known.append(server.name)
            known.append(server.displayAddress)
            if let host = server.baseURL.host() { known.append(host) }
        }
        known.append(contentsOf: servers.compactMap(\.userName))
        return DiagnosticsRedactor(names: known)
    }

    /// This run's log lines from the app's own subsystem, oldest first:
    /// "15:02:11 error [storage] could not save the servers". On iPhone, iPad
    /// and Apple TV a process can read only its own entries, so these start at
    /// this launch. Arguments logged as private stay `<private>`.
    public static func recentLogLines(limit: Int = DiagnosticsReport.maxLogLines) -> [String] {
        guard let store = try? OSLogStore(scope: .currentProcessIdentifier) else { return [] }
        let predicate = NSPredicate(format: "subsystem == %@", subsystem)
        guard let entries = try? store.getEntries(matching: predicate) else { return [] }
        var lines: [String] = []
        for case let entry as OSLogEntryLog in entries {
            lines.append(logLine(date: entry.date, level: entry.level, category: entry.category, message: entry.composedMessage))
            if lines.count > limit * 2 { lines.removeFirst(lines.count - limit) }
        }
        return Array(lines.suffix(limit))
    }

    static func logLine(date: Date, level: OSLogEntryLog.Level, category: String, message: String) -> String {
        let time = date.formatted(.iso8601.time(includingFractionalSeconds: false))
        let word: String
        switch level {
        case .debug: word = "debug"
        case .info: word = "info"
        case .notice: word = "notice"
        case .error: word = "error"
        case .fault: word = "fault"
        default: word = "log"
        }
        return "\(time) \(word) [\(category)] \(message.replacingOccurrences(of: "\n", with: " "))"
    }

    /// "iPhone17,1", or the simulated model on a simulator; never the device's name.
    static func hardwareModel() -> String {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] { return simulated + " (simulator)" }
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { raw in String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self) }
        return machine.isEmpty ? "unknown" : machine
    }

    /// "iOS 18.2 (22C152)".
    static func systemName() -> String {
        #if os(tvOS)
        let name = "tvOS"
        #elseif os(iOS)
        let name = "iOS"
        #else
        let name = "macOS"
        #endif
        let v = ProcessInfo.processInfo.operatingSystemVersion
        let version = v.patchVersion == 0 ? "\(v.majorVersion).\(v.minorVersion)" : "\(v.majorVersion).\(v.minorVersion).\(v.patchVersion)"
        let build = ProcessInfo.processInfo.operatingSystemVersionString
            .split(separator: "(").last.map { $0.replacingOccurrences(of: "Build ", with: "").replacingOccurrences(of: ")", with: "") }
        return build.map { "\(name) \(version) (\($0))" } ?? "\(name) \(version)"
    }
}

#if os(iOS)
/// MetricKit's subscriber: each diagnostic payload becomes summaries in the store.
private final class MetricKitSubscriber: NSObject, MXMetricManagerSubscriber, Sendable {
    let store: DiagnosticsStore

    init(store: DiagnosticsStore) {
        self.store = store
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        let now = Date()
        store.record(payloads.flatMap { MetricKitSummaries.summaries(json: $0.jsonRepresentation(), receivedAt: now) })
    }
}
#endif
