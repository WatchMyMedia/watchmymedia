import Foundation
import Testing
@testable import WMMModel

/// Settings › About › Diagnostics (Diagnostics.dc.html): what the page shows
/// is what Copy copies, and neither holds a name, an address or a secret.
@Suite struct DiagnosticsPageTests {
    let at = Date(timeIntervalSince1970: 1_794_517_440) // 2026-11-12 21:04 UTC
    let london = TimeZone(identifier: "UTC")!
    let britain = Locale(identifier: "en_GB")

    /// Everything that must not leave the device, as it might turn up in a report.
    static let secrets = [
        "Home Server", "Sam", "nas.local", "192.168.1.20", "27587", "wmm.example.org", "Harbor Lights",
        "abc123secret", "3F2504E0-4F89-11D3-9A0C-0305E82C3301", "hunter2", "Sam’s iPhone", "/var/mobile",
    ]

    private func report(server: Bool = true) -> DiagnosticsReport {
        DiagnosticsReport(
            appVersion: "1.9.0 (10500012)", deviceModel: "iPhone17,1", system: "iOS 18.2 (22C152)",
            serverVersion: server ? "1.5.0" : nil, serverAPILevel: server ? 28 : nil,
            summaries: [
                DiagnosticSummary(kind: .hang, receivedAt: at, lines: ["Hang 2.4 sec", "WatchMyMedia +0x1a2b"]),
                DiagnosticSummary(kind: .hang, receivedAt: at.addingTimeInterval(-86_400), lines: ["Hang 9 sec"]),
                DiagnosticSummary(kind: .launch, receivedAt: at, lines: ["Launch took 1.9 sec"]),
                DiagnosticSummary(kind: .exits, receivedAt: at, lines: ["1 for memory", "2 to free memory"]),
                // A crash summary whose termination reason quoted more than it should.
                DiagnosticSummary(kind: .crash, receivedAt: at, lines: ["EXC_CRASH (SIGABRT), no item named Harbor Lights at http://192.168.1.20:27587"]),
            ],
            logLines: [
                "21:14:02 info [home] loaded 9 rows in 0.84 s",
                "21:14:05 error [playback] Playback request failed: http://192.168.1.20:27587/api/play/9101?token=abc123secret",
                "21:14:06 info [servers] chose Home Server at nas.local:27587 as Sam",
                "21:14:07 info [auth] Authorization: Bearer abc123secret password: hunter2",
                "21:14:08 info [downloads] could not download \"Harbor Lights\" to /var/mobile/Containers/Data/x.movpkg",
                "21:14:09 info [devices] Sam’s iPhone paired as 3F2504E0-4F89-11D3-9A0C-0305E82C3301",
                "21:14:10 notice [events] reconnected to https://wmm.example.org after 4 s",
            ],
            generatedAt: at
        )
    }

    private var redactor: DiagnosticsRedactor {
        DiagnosticsRedactor(names: ["Home Server", "Sam", "Sam’s iPhone", "Harbor Lights", "nas.local", "wmm.example.org"])
    }

    private func page(server: Bool = true) -> DiagnosticsPage {
        DiagnosticsPage(report: report(server: server), redactor: redactor, deviceLabel: "iPhone", locale: britain, timeZone: london)
    }

    @Test func thePageShowsTheBoardsGroups() {
        let page = page()
        #expect(page.app == "1.9.0 (10500012)")
        #expect(page.deviceLabel == "iPhone")
        #expect(page.device == "iPhone17,1 · iOS 18.2 (22C152)")
        #expect(page.server == "1.5.0 · API 28")
        #expect(page.reports.map(\.label) == ["Crash", "Hang", "Launch", "Exits in the background"])
        #expect(page.reports[1].value == "2.4 sec · 12 Nov, 21:04", "the last hang only, with when iOS handed it over")
        #expect(page.reports[2].value == "1.9 sec · 12 Nov, 21:04")
        #expect(page.reports[3].value == "1 for memory, 2 to free memory · 12 Nov, 21:04")
        #expect(page.reportsNote == "iOS gathers these and hands them to the app about once a day.")
        #expect(page.log.count == 7)
        #expect(page.log[0] == "21:14:02 info [home] loaded 9 rows in 0.84 s")
    }

    @Test func noServerAndNoReportsSaySo() {
        let empty = DiagnosticsPage(
            report: DiagnosticsReport(appVersion: "1.0", deviceModel: "iPad16,3", system: "iOS 18.2", generatedAt: at),
            redactor: DiagnosticsRedactor(), deviceLabel: "iPad"
        )
        #expect(empty.server == "None chosen")
        #expect(empty.reports.isEmpty)
        #expect(empty.reportsNote == "iOS hands reports to the app about once a day, so a new install has none.")
        #expect(empty.log.isEmpty)
    }

    @Test func nothingOnThePageOrInTheCopyNamesTheServerTheAccountOrATitle() {
        let page = page()
        let shown = [page.app, page.device, page.server] + page.reports.flatMap { [$0.label, $0.value] } + page.log
        for secret in Self.secrets {
            for value in shown {
                #expect(!value.localizedCaseInsensitiveContains(secret), "\(secret) shows on the page in \(value)")
            }
            #expect(!page.text.localizedCaseInsensitiveContains(secret), "\(secret) is in the copied text")
        }
        // What was taken out says so, and the rest of each line stays.
        #expect(page.log[1] == "21:14:05 error [playback] Playback request failed: <address><redacted>")
        #expect(page.log[2] == "21:14:06 info [servers] chose <name> at <name> as <name>", "a known host goes with its port")
        #expect(page.log[3] == "21:14:07 info [auth] Authorization: <redacted> password: <redacted>")
        #expect(page.log[5] == "21:14:09 info [devices] <name> paired as <redacted>")
        #expect(page.reports[0].value.hasPrefix("EXC_CRASH (SIGABRT), no item named <name> at <address>"))
    }

    @Test func theCopyIsTheReportsTextWithTheExitsNamed() {
        let page = page()
        #expect(page.text == report().text(redactor: redactor))
        #expect(page.text.contains("  Exits in the background, "))
        #expect(page.text.contains("    1 for memory\n    2 to free memory"))
        #expect(page.text.contains("  Hang, "))
        // Every log line on the page is in the copy.
        for line in page.log { #expect(page.text.contains(line)) }
    }

    @Test func aKnownHostTakesItsPortWithIt() {
        // Diagnostics.redactor passes each server's host as a name, which goes before the address rules.
        let redactor = DiagnosticsRedactor(names: ["nas.local", "Home Server"])
        #expect(redactor.redact("Bonjour found nas.local:27587 (Home Server)") == "Bonjour found <name> (<name>)")
        #expect(redactor.redact("http://nas.local:27587/api") == "<address>")
        #expect(redactor.redact("Home Server: 3 streams") == "<name>: 3 streams", "a colon and a count after a name are not a port")
    }

    @Test func namesGoAsWholeWordsOnly() {
        let redactor = DiagnosticsRedactor(names: ["Sam", "Den"])
        #expect(redactor.redact("12:00:00 error [sample] Sam’s iPad and sam left the den") == "12:00:00 error [sample] <name>’s iPad and <name> left the <name>")
        #expect(redactor.redact("same, hidden, Samantha") == "same, hidden, Samantha")
    }

    @Test func theAboutRowCountsTheKeptReports() {
        #expect(DiagnosticsPage.countLabel([]) == nil)
        #expect(DiagnosticsPage.countLabel([DiagnosticSummary(kind: .hang, receivedAt: at, lines: [])]) == "1 report")
        #expect(DiagnosticsPage.countLabel(report().summaries) == "4 reports", "one of each kind")
    }

    @Test func headlinesDropTheKindsOwnWord() {
        #expect(DiagnosticSummary(kind: .hang, receivedAt: at, lines: ["Hang 3 sec", "x +0x1"]).headline == "3 sec")
        #expect(DiagnosticSummary(kind: .launch, receivedAt: at, lines: ["Launch took ? "]).headline == "? ")
        #expect(DiagnosticSummary(kind: .crash, receivedAt: at, lines: ["EXC_BAD_ACCESS (SIGSEGV)", "x +0x1"]).headline == "EXC_BAD_ACCESS (SIGSEGV)")
        #expect(DiagnosticSummary(kind: .crash, receivedAt: at, lines: []).headline == "")
    }
}
