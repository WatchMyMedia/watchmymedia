import Foundation
import WMMModel

/// Diagnostics on Apple TV (TvDiagnostics.dc.html, PLAN-APPLE-REVIEW.md
/// §6.4), which has no clipboard a person can paste from: the redacted text
/// as a QR code to scan with a phone, a summary of it beside the code, and
/// Show as text, all of it in pages to read out or photograph. Nothing is
/// sent from the Apple TV.
///
/// A QR code that scans from a sofa holds far less than the text can run to,
/// so the code holds at most ``maxBytes``: when the text is longer, the
/// oldest log lines are left out first, and a last line says how many.
public struct DiagnosticsCode: Sendable, Equatable {
    /// The most the code holds, in UTF-8 bytes: a version 25 QR code at
    /// error correction M (997 bytes in byte mode), 117 modules a side. In
    /// the board's 420-point card that is a module of about 3.4 points, some
    /// 2 mm on a 55-inch TV, which a phone's camera reads from a couple of
    /// metres; a bigger code's modules get too small to read from a sofa.
    public static let maxBytes = 997

    /// What the code holds, redacted.
    public let text: String
    /// Everything, redacted: what Show as text pages through.
    public let fullText: String
    /// The log lines the code holds, and the older ones it leaves out.
    public let logLines: Int
    public let leftOut: Int

    public init(report: DiagnosticsReport, redactor: DiagnosticsRedactor, maxBytes: Int = DiagnosticsCode.maxBytes) {
        let all = Array(report.logLines.suffix(DiagnosticsReport.maxLogLines))
        fullText = report.text(redactor: redactor)
        func shortened(keeping count: Int) -> String {
            var shortened = report
            shortened.logLines = Array(all.suffix(count))
            let text = shortened.text(redactor: redactor)
            let left = all.count - count
            return left == 0 ? text : text + Self.leftOutLine(left) + "\n"
        }
        if fullText.utf8.count <= maxBytes {
            text = fullText
            logLines = all.count
            leftOut = 0
            return
        }
        // The most log lines, newest kept, that fit.
        var low = 0, high = all.count - 1
        while low < high {
            let middle = (low + high + 1) / 2
            if shortened(keeping: middle).utf8.count <= maxBytes { low = middle } else { high = middle - 1 }
        }
        var fitted = shortened(keeping: low)
        // Without any log line it can still be too long (a long system report): cut whole lines from the end.
        if fitted.utf8.count > maxBytes {
            let cut = "… cut to fit the code"
            var lines = fitted.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
            while !lines.isEmpty, (lines + [cut]).joined(separator: "\n").utf8.count + 1 > maxBytes { lines.removeLast() }
            fitted = (lines + [cut]).joined(separator: "\n") + "\n"
        }
        text = fitted
        logLines = low
        leftOut = all.count - low
    }

    static func leftOutLine(_ count: Int) -> String {
        "… \(plural(count, "older log line")) left out to fit the code"
    }

    // MARK: Wording

    /// Under the code: "Plain text · 1.4 KB".
    public var sizeLine: String { "Plain text · \(formatBytes(Int64(text.utf8.count)))" }

    /// Beside the code: the app, device and server and the system's reports,
    /// then the code's first `logLines` log lines and how many more it holds.
    public func summary(logLines shown: Int = 2) -> String {
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        guard let log = lines.firstIndex(where: { $0.hasPrefix("Log (") }) else { return text }
        // After the title, the time it was made and a blank line.
        var head = Array(lines[min(3, log)..<log])
        while head.last?.isEmpty == true { head.removeLast() }
        let entries = lines[(log + 1)...].filter { $0.hasPrefix("  ") }
        var out = head + [""] + entries.prefix(shown).map { $0.trimmingCharacters(in: .whitespaces) }
        if entries.count > shown {
            out.append("… and \(plural(entries.count - shown, "more log line")) in the code")
        } else if entries.isEmpty {
            out.append("No log lines yet")
        }
        return out.joined(separator: "\n")
    }

    // MARK: Show as text

    /// `fullText` in pages of at most `rows` lines on screen, a line longer
    /// than `columns` characters taking a row for each `columns` it holds.
    /// A line never splits across pages unless it alone is longer than one.
    public func pages(columns: Int, rows: Int) -> [String] {
        Self.pages(fullText, columns: columns, rows: rows)
    }

    static func pages(_ text: String, columns: Int, rows: Int) -> [String] {
        guard columns > 0, rows > 0 else { return [text] }
        var lines = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        while lines.last?.isEmpty == true { lines.removeLast() }
        var pages: [[String]] = []
        var page: [String] = []
        var used = 0
        for line in lines {
            var rest = line
            repeat {
                let room = (rows - used) * columns
                let height = max(1, (rest.count + columns - 1) / columns)
                if height <= rows - used {
                    page.append(rest)
                    used += height
                    rest = ""
                } else if height > rows && used == 0 {
                    // Longer than a whole page: as much as fits, the rest on the next.
                    page.append(String(rest.prefix(room)))
                    rest = String(rest.dropFirst(room))
                    used = rows
                } else {
                    pages.append(page)
                    page = []
                    used = 0
                    continue
                }
                if used == rows {
                    pages.append(page)
                    page = []
                    used = 0
                }
            } while !rest.isEmpty
        }
        if !page.isEmpty || pages.isEmpty { pages.append(page) }
        return pages.map { $0.joined(separator: "\n") }
    }

    /// Over the pages: "Page 1 of 3 · swipe down for more", "Page 3 of 3".
    public static func pageLine(_ page: Int, of count: Int) -> String {
        count <= 1 ? "" : page + 1 < count ? "Page \(page + 1) of \(count) · swipe down for more" : "Page \(page + 1) of \(count)"
    }
}
