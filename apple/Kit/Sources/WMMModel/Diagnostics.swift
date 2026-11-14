import Foundation

/// The kinds of system diagnostic the app keeps the last of (MetricKit on
/// iPhone and iPad; PLAN-APPLE-REVIEW.md §6.4).
public enum DiagnosticKind: String, Codable, Sendable, CaseIterable {
    case launch, hang, crash
    /// Times the system ended the app in the background other than normally
    /// (MetricKit's daily metrics, not a diagnostic).
    case exits

    /// "Hang", "Exits in the background", as the report and the Diagnostics page name it.
    public var title: String {
        switch self {
        case .launch: "Launch"
        case .hang: "Hang"
        case .crash: "Crash"
        case .exits: "Exits in the background"
        }
    }
}

/// A short, private summary of one system diagnostic: what happened and
/// where in the app's code, never what the person was watching.
public struct DiagnosticSummary: Codable, Sendable, Equatable {
    public var kind: DiagnosticKind
    /// When the system delivered it (diagnostics arrive on a later launch).
    public var receivedAt: Date
    /// The app version and OS the diagnostic came from, as the system says.
    public var appVersion: String?
    public var osVersion: String?
    /// "Hang 3.2 sec", "EXC_BAD_ACCESS (SIGSEGV)", then the top frames as
    /// "WatchMyMedia +0x1a2b3c": a binary and an offset, for symbolication.
    /// For exits, one line per reason: "1 for memory".
    public var lines: [String]

    /// What happened, in a few words, for the Diagnostics page: "3.2 sec",
    /// "EXC_BAD_ACCESS (SIGSEGV)", "1 for memory, 2 to free memory".
    public var headline: String {
        if kind == .exits { return lines.joined(separator: ", ") }
        guard let first = lines.first else { return "" }
        for prefix in ["Launch took ", "Hang "] where first.hasPrefix(prefix) { return String(first.dropFirst(prefix.count)) }
        return first
    }

    public init(kind: DiagnosticKind, receivedAt: Date, appVersion: String? = nil, osVersion: String? = nil, lines: [String]) {
        self.kind = kind
        self.receivedAt = receivedAt
        self.appVersion = appVersion
        self.osVersion = osVersion
        self.lines = lines
    }
}

/// What a "Copy diagnostics" row copies (PLAN-APPLE-REVIEW.md §6.4): the app
/// and device, the server's version and API level, the last launch, hang and
/// crash summaries, and the app's own recent log lines. It never holds the
/// server's name or address, a token, a password, an account's name or a
/// title's: `text` runs every line through `DiagnosticsRedactor`, and the
/// fields that would carry them aren't here at all.
public struct DiagnosticsReport: Sendable {
    public var appVersion: String
    /// The hardware identifier ("iPhone17,1"), not the device's name.
    public var deviceModel: String
    /// "iOS 18.2".
    public var system: String
    public var serverVersion: String?
    public var serverAPILevel: Int?
    public var summaries: [DiagnosticSummary]
    /// The app's log lines, oldest first.
    public var logLines: [String]
    public var generatedAt: Date

    public init(appVersion: String, deviceModel: String, system: String, serverVersion: String? = nil, serverAPILevel: Int? = nil,
                summaries: [DiagnosticSummary] = [], logLines: [String] = [], generatedAt: Date) {
        self.appVersion = appVersion
        self.deviceModel = deviceModel
        self.system = system
        self.serverVersion = serverVersion
        self.serverAPILevel = serverAPILevel
        self.summaries = summaries
        self.logLines = logLines
        self.generatedAt = generatedAt
    }

    /// The most log lines the text keeps, newest last, and their longest.
    public static let maxLogLines = 200
    public static let maxLineLength = 300

    /// The text to copy, every line redacted by `redactor`.
    public func text(redactor: DiagnosticsRedactor) -> String {
        let stamp = ISO8601DateFormatter()
        var out = ["WatchMyMedia diagnostics", "Made \(stamp.string(from: generatedAt))", ""]
        out.append("App: \(appVersion)")
        out.append("Device: \(deviceModel), \(system)")
        if let serverVersion {
            out.append("Server: \(serverVersion)" + (serverAPILevel.map { ", API level \($0)" } ?? ""))
        } else {
            out.append("Server: none chosen")
        }
        out.append("")
        out.append("Last reports from the system:")
        let latest = DiagnosticKind.allCases.compactMap { kind in summaries.filter { $0.kind == kind }.max { $0.receivedAt < $1.receivedAt } }
        if latest.isEmpty { out.append("  none") }
        for summary in latest {
            let from = [summary.appVersion.map { "app \($0)" }, summary.osVersion].compactMap { $0 }.joined(separator: ", ")
            out.append("  \(summary.kind.title), \(stamp.string(from: summary.receivedAt))" + (from.isEmpty ? "" : " (\(from))"))
            out.append(contentsOf: summary.lines.map { "    \($0)" })
        }
        out.append("")
        let lines = logLines.suffix(Self.maxLogLines)
        out.append("Log (\(lines.count == 1 ? "1 line" : "\(lines.count) lines")):")
        out.append(contentsOf: lines.map { "  " + String($0.prefix(Self.maxLineLength)) })
        return out.map(redactor.redact).joined(separator: "\n") + "\n"
    }
}

/// Takes out of diagnostics text what must not leave the device with it
/// (apple/README.md, "Diagnostics"). Every line is redacted, in this order:
///
/// 1. Known names (the server's, the accounts', this device's, any titles the
///    caller knows of) become `<name>`, ignoring case, as whole words (so
///    "Sam" leaves "sample"); a port after one goes too.
/// 2. Text in quotes (`"…"`, `“…”`, `‘…’`, `'…'`) becomes `"<text>"`: error
///    messages quote file and item names.
/// 3. Addresses become `<address>`: anything with a scheme (`http://…`,
///    `file://…`), e-mail addresses, IPv4 and IPv6 addresses, and host names
///    (`nas.local`, `media.example.org`), with any port.
/// 4. File paths (two or more `/` components) become `<path>`.
/// 5. Secrets become `<redacted>`: the word after `Bearer` or `Basic`, the
///    value after `token`, `password`, `secret`, `authorization`, `cookie`,
///    `session`, `api key` or `key` with `=` or `:`, UUIDs, and any run of 20
///    or more letters, digits and `-_+/=.` that mixes letters and digits.
///
/// Version numbers ("1.4.3"), API levels, binary offsets ("+0x1a2b"), code's
/// own file names (`libswiftCore.dylib`) and the system's own words
/// (`EXC_BAD_ACCESS`) stay. The rule set is deliberately greedy: a line that
/// loses a harmless word is better than one that keeps an address.
public struct DiagnosticsRedactor: Sendable {
    public var names: [String]

    public init(names: [String] = []) {
        // Longest first, so "Sam’s iPad" goes before "Sam".
        self.names = Array(Set(names.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { $0.count >= 2 }))
            .sorted { $0.count != $1.count ? $0.count > $1.count : $0 < $1 }
    }

    private static func regex(_ pattern: String) -> NSRegularExpression {
        // The patterns are constants, tested in DiagnosticsTests.
        try! NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
    }

    /// NSRegularExpression is safe to share between threads once made; older
    /// SDKs don't mark it Sendable.
    private struct Rules: @unchecked Sendable { let list: [(NSRegularExpression, String)] }

    private static let rules = Rules(list: [
        // Quoted text.
        (regex(#""[^"\n]*"|“[^”\n]*”|‘[^’\n]*’|(?<![A-Za-z])'[^'\n]*'"#), "\"<text>\""),
        // Secrets named before their value, before addresses so a token in a query goes whole.
        (regex(#"\b(access[_-]?token|refresh[_-]?token|media[_-]?token|token|password|passwd|secret|authorization|cookie|session|api[_ -]?key|key)(\s*[=:]\s*)(?:(?:bearer|basic)\s+)?[^\s,;&]+"#), "$1$2<redacted>"),
        (regex(#"\b(bearer|basic)\s+[^\s,;]+"#), "$1 <redacted>"),
        // Anything with a scheme.
        (regex(#"\b[a-z][a-z0-9+.-]*://[^\s"<>]*"#), "<address>"),
        // E-mail addresses.
        (regex(#"[^\s@<>"]+@[^\s@<>"]+\.[a-z]{2,}\b"#), "<address>"),
        // File paths, before host names so a file's name goes with its folder.
        (regex(#"(?<![\w<>])(?:~|\.{1,2})?(?:/[^\s/"<>]+){2,}/?"#), "<path>"),
        // UUIDs (device IDs, sessions).
        (regex(#"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b"#), "<redacted>"),
        // IPv6: bracketed (with a colon, so a log line's "[downloads]" stays), or four or more hex groups.
        (regex(#"\[[0-9a-z.%]*:[0-9a-z:.%]*\](:\d+)?"#), "<address>"),
        (regex(#"(?<![\w:])(?:[0-9a-f]{0,4}:){3,7}[0-9a-f]{0,4}(?![\w:])"#), "<address>"),
        // IPv4, with any port.
        (regex(#"\b(?:\d{1,3}\.){3}\d{1,3}(:\d+)?\b"#), "<address>"),
        // Host names: labels ending in a label with a letter, with any port.
        // Code's own files (`libswiftCore.dylib`) are not hosts.
        (regex(#"\b(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+(?!(?:dylib|framework|appex|app|swift|mm|m|h|c)\b)[a-z][a-z0-9-]*[a-z0-9](:\d+)?\b"#), "<address>"),
        // Long opaque strings that mix letters and digits.
        (regex(#"(?<![\w<])(?=[A-Za-z0-9_\-+/=.]*[0-9])(?=[A-Za-z0-9_\-+/=.]*[A-Za-z])[A-Za-z0-9_\-+/=.]{20,}"#), "<redacted>"),
    ])

    /// Stands in for a name until the rules have run, so they treat it as text
    /// (`⟨name⟩@example.com` is still an address) and a name can't match inside
    /// another placeholder.
    private static let namePlaceholder = "\u{27E8}name\u{27E9}"

    public func redact(_ line: String) -> String {
        var s = line
        for name in names {
            // Whole words only, so "Sam" leaves "sample" alone but not "Sam’s".
            let pattern = #"(?<![\p{L}\p{N}])"# + NSRegularExpression.escapedPattern(for: name) + #"(?![\p{L}\p{N}])"#
            s = s.replacingOccurrences(of: pattern, with: Self.namePlaceholder, options: [.caseInsensitive, .regularExpression])
        }
        // A known host's port goes with it: "nas.local:27587" is all address.
        s = s.replacingOccurrences(of: Self.namePlaceholder + #":\d{1,5}\b"#, with: Self.namePlaceholder, options: [.regularExpression])
        for (rule, template) in Self.rules.list {
            s = rule.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: template)
        }
        return s.replacingOccurrences(of: Self.namePlaceholder, with: "<name>")
    }
}
