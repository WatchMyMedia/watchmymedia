import Foundation

/// What Settings › About › Diagnostics shows on iPhone and iPad
/// (design/mockups/ios/Diagnostics.dc.html): the parts of a
/// ``DiagnosticsReport`` laid out in groups, and ``text``, which Copy puts on
/// the clipboard. Every value on the page went through the same
/// ``DiagnosticsRedactor`` as the text, so the page never shows what Copy
/// would leave out: no account, server name or address, or title.
public struct DiagnosticsPage: Sendable, Equatable {
    public struct Row: Sendable, Equatable, Identifiable {
        public var label: String
        public var value: String
        public var id: String { label }

        public init(label: String, value: String) {
            self.label = label
            self.value = value
        }
    }

    /// "1.9.0 (10500012)".
    public var app: String
    /// "iPhone" or "iPad", the device row's label.
    public var deviceLabel: String
    /// "iPhone17,1 · iOS 18.2 (22C152)".
    public var device: String
    /// "1.4.3 · API 28", or "None chosen".
    public var server: String
    /// The last report of each kind: "Hang", "3.2 sec · 12 Nov, 21:14".
    public var reports: [Row]
    /// Under the reports: how often iOS hands them over.
    public var reportsNote: String
    /// The app's own log lines, oldest first, at most ``DiagnosticsReport/maxLogLines``.
    public var log: [String]
    /// Exactly what Copy puts on the clipboard.
    public var text: String

    /// The order the page lists reports in.
    public static let reportOrder: [DiagnosticKind] = [.crash, .hang, .launch, .exits]

    public init(report: DiagnosticsReport, redactor: DiagnosticsRedactor, deviceLabel: String,
                locale: Locale = .current, timeZone: TimeZone = .current) {
        let r = redactor.redact
        app = r(report.appVersion)
        self.deviceLabel = deviceLabel
        device = r("\(report.deviceModel) · \(report.system)")
        server = report.serverVersion.map { version in r(version + (report.serverAPILevel.map { " · API \($0)" } ?? "")) } ?? "None chosen"
        // "12 Nov, 21:14" in Britain, "Nov 12, 9:14 PM" in the US.
        let day = DateFormatter()
        let time = DateFormatter()
        for formatter in [day, time] {
            formatter.locale = locale
            formatter.timeZone = timeZone
        }
        day.setLocalizedDateFormatFromTemplate("dMMM")
        time.setLocalizedDateFormatFromTemplate("jmm")
        let latest = Self.latest(report.summaries)
        reports = Self.reportOrder.compactMap { kind in
            latest[kind].map { summary in
                let when = "\(day.string(from: summary.receivedAt)), \(time.string(from: summary.receivedAt))"
                return Row(label: kind.title, value: r([summary.headline, when].filter { !$0.isEmpty }.joined(separator: " · ")))
            }
        }
        reportsNote = reports.isEmpty
            ? "iOS hands reports to the app about once a day, so a new install has none."
            : "iOS gathers these and hands them to the app about once a day."
        log = report.logLines.suffix(DiagnosticsReport.maxLogLines).map { r(String($0.prefix(DiagnosticsReport.maxLineLength))) }
        text = report.text(redactor: redactor)
    }

    /// The About row's value: "3 reports", or nil with none kept.
    public static func countLabel(_ summaries: [DiagnosticSummary]) -> String? {
        let count = latest(summaries).count
        return count == 0 ? nil : count == 1 ? "1 report" : "\(count) reports"
    }

    private static func latest(_ summaries: [DiagnosticSummary]) -> [DiagnosticKind: DiagnosticSummary] {
        Dictionary(summaries.map { ($0.kind, $0) }, uniquingKeysWith: { $0.receivedAt >= $1.receivedAt ? $0 : $1 })
    }
}
