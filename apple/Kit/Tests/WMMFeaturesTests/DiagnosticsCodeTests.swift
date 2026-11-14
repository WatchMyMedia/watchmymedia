import Foundation
import Testing
@testable import WMMFeatures
import WMMModel

/// What Apple TV's Diagnostics shows (TvDiagnostics.dc.html): the code's
/// text within its size, oldest log lines left out first, the summary beside
/// it, and Show as text's pages.
@Suite struct DiagnosticsCodeTests {
    private func report(lines: Int, summaries: [DiagnosticSummary] = []) -> DiagnosticsReport {
        DiagnosticsReport(
            appVersion: "1.9.0 (10500012)", deviceModel: "AppleTV14,1", system: "tvOS 27.2 (24K150)", serverVersion: "1.5.0", serverAPILevel: 28,
            summaries: summaries,
            logLines: (0..<lines).map { String(format: "21:%02d:%02d info [live] tuned channel %d from 192.168.1.20 in 1.8 s", $0 / 60, $0 % 60, $0) },
            generatedAt: Date(timeIntervalSince1970: 1_800_000_000)
        )
    }

    @Test func aShortReportGoesWhole() {
        let code = DiagnosticsCode(report: report(lines: 3), redactor: DiagnosticsRedactor())
        #expect(code.text == code.fullText)
        #expect(code.logLines == 3)
        #expect(code.leftOut == 0)
        #expect(!code.text.contains("left out"))
    }

    @Test func aLongReportLeavesOutTheOldestLinesToFit() throws {
        let code = DiagnosticsCode(report: report(lines: 60), redactor: DiagnosticsRedactor())
        #expect(code.text.utf8.count <= DiagnosticsCode.maxBytes)
        #expect(code.fullText.utf8.count > DiagnosticsCode.maxBytes)
        #expect(code.leftOut > 0)
        #expect(code.logLines + code.leftOut == 60)
        #expect(code.text.contains("channel 59 "), "the newest line stays")
        #expect(!code.text.contains("channel 0 "), "the oldest goes first")
        #expect(code.text.contains("Log (\(code.logLines) lines):"))
        #expect(code.text.hasSuffix("… \(code.leftOut) older log lines left out to fit the code\n"))
        // As many as fit: one more line would not.
        let more = DiagnosticsCode(report: report(lines: 60), redactor: DiagnosticsRedactor(), maxBytes: code.text.utf8.count + 80)
        #expect(more.logLines > code.logLines)
    }

    @Test func theCodeIsRedacted() {
        let code = DiagnosticsCode(report: report(lines: 60), redactor: DiagnosticsRedactor(names: ["Living Room"]))
        #expect(!code.text.contains("192.168"))
        #expect(!code.fullText.contains("192.168"))
        #expect(code.text.contains("<address>"))
    }

    @Test func withoutRoomForAnyLogLineItCutsWholeLines() {
        let summary = DiagnosticSummary(kind: .hang, receivedAt: Date(timeIntervalSince1970: 1_800_000_000),
                                        lines: (0..<80).map { "WatchMyMedia +0x\(String($0, radix: 16)) frame of a long hang report" })
        let code = DiagnosticsCode(report: report(lines: 5, summaries: [summary]), redactor: DiagnosticsRedactor())
        #expect(code.text.utf8.count <= DiagnosticsCode.maxBytes)
        #expect(code.text.hasSuffix("… cut to fit the code\n"))
        #expect(code.text.hasPrefix("WatchMyMedia diagnostics\n"))
    }

    @Test func theSummaryShowsTheHeadAndTwoLogLines() {
        let code = DiagnosticsCode(report: report(lines: 8), redactor: DiagnosticsRedactor())
        #expect(code.leftOut == 0)
        let summary = code.summary()
        #expect(summary.hasPrefix("App: 1.9.0 (10500012)\nDevice: AppleTV14,1, tvOS 27.2 (24K150)\nServer: 1.5.0, API level 28\n\nLast reports from the system:\n  none\n\n"))
        #expect(summary.contains("21:00:00 info [live] tuned channel 0 from <address> in 1.8 s\n21:00:01"))
        #expect(summary.hasSuffix("… and \(code.logLines - 2) more log lines in the code"))
        #expect(!summary.contains("Made "))
        let empty = DiagnosticsCode(report: report(lines: 0), redactor: DiagnosticsRedactor())
        #expect(empty.summary().hasSuffix("\n\nNo log lines yet"))
    }

    @Test func theSizeLineSaysHowMuchTheCodeHolds() {
        let code = DiagnosticsCode(report: report(lines: 3), redactor: DiagnosticsRedactor())
        #expect(code.sizeLine == "Plain text · \(formatBytes(Int64(code.text.utf8.count)))")
        #expect(code.sizeLine.hasSuffix(" B") || code.sizeLine.hasSuffix(" KB"))
    }

    @Test func pagesFillRowsAndKeepLinesWhole() {
        let text = (1...10).map { "line \($0)" }.joined(separator: "\n") + "\n"
        #expect(DiagnosticsCode.pages(text, columns: 20, rows: 4) == ["line 1\nline 2\nline 3\nline 4", "line 5\nline 6\nline 7\nline 8", "line 9\nline 10"])
        // A line of 45 characters takes three rows of 20, so it starts the next page.
        let long = String(repeating: "x", count: 45)
        #expect(DiagnosticsCode.pages("a\nb\n\(long)\nc", columns: 20, rows: 4) == ["a\nb", "\(long)\nc"])
        // Longer than a page: split.
        let huge = String(repeating: "y", count: 100)
        #expect(DiagnosticsCode.pages(huge, columns: 20, rows: 2) == [String(repeating: "y", count: 40), String(repeating: "y", count: 40), String(repeating: "y", count: 20)])
        #expect(DiagnosticsCode.pages("", columns: 20, rows: 4) == [""])
    }

    @Test func wordsThePageLine() {
        #expect(DiagnosticsCode.pageLine(0, of: 3) == "Page 1 of 3 · swipe down for more")
        #expect(DiagnosticsCode.pageLine(2, of: 3) == "Page 3 of 3")
        #expect(DiagnosticsCode.pageLine(0, of: 1) == "")
    }
}
