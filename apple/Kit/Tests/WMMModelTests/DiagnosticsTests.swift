import Foundation
import Testing
@testable import WMMModel

/// The redaction rule for copied diagnostics (apple/README.md, "Diagnostics").
@Suite struct DiagnosticsRedactorTests {
    let redactor = DiagnosticsRedactor(names: ["Harbor Lights", "Sam", "Sam’s iPad", " ", "x"])

    @Test(arguments: [
        // Addresses, with and without a scheme or port.
        ("Playback request failed: http://192.168.1.20:27587/api/play/9101?x=1", "Playback request failed: <address>"),
        ("could not reach 192.168.1.20:27587", "could not reach <address>"),
        ("could not reach 10.0.0.5", "could not reach <address>"),
        ("connect to [fe80::1c2a:3bff:fe4d:5e6f%en0]:27587 failed", "connect to <address> failed"),
        ("route fe80:0:0:0:1c2a:3bff:fe4d:5e6f down", "route <address> down"),
        ("A server with the host name wmm.example.org:443 could not be found", "A server with the host name <address> could not be found"),
        ("Bonjour found nas.local", "Bonjour found <address>"),
        ("mail sam@example.com", "mail <address>"),
        // Secrets.
        ("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.e30.abc", "Authorization: <redacted>"),
        ("sent bearer abc123", "sent bearer <redacted>"),
        ("token=AbCdEf123456 and password: hunter2", "token=<redacted> and password: <redacted>"),
        ("device 3F2504E0-4F89-11D3-9A0C-0305E82C3301 paired", "device <redacted> paired"),
        ("session k7m4pq9xw2n8b5v3c6z1a0 expired", "session <redacted> expired"),
        // Names and quoted text.
        ("could not download Harbor Lights S03E07", "could not download <name> S03E07"),
        ("Sam’s iPad signed in as sam", "<name> signed in as <name>"),
        ("The file “Copper Valley.mkv” couldn’t be opened.", "The file \"<text>\" couldn’t be opened."),
        ("no item named \"The Quiet Year\"", "no item named \"<text>\""),
        // Paths.
        ("could not read /var/mobile/Containers/Data/Application/ABC/Library/Downloads/x.movpkg", "could not read <path>"),
        // What stays.
        ("Server 1.4.3 (10400399), API level 28", "Server 1.4.3 (10400399), API level 28"),
        ("EXC_BAD_ACCESS (SIGSEGV), code 1", "EXC_BAD_ACCESS (SIGSEGV), code 1"),
        ("WatchMyMedia +0x1a2b3c", "WatchMyMedia +0x1a2b3c"),
        ("libswiftCore.dylib +0x44", "libswiftCore.dylib +0x44"),
        ("Device: iPhone17,1, iOS 18.2 (22C152)", "Device: iPhone17,1, iOS 18.2 (22C152)"),
        ("15:02:11 info [downloads] download 42 starts over: the server's session changed",
         "15:02:11 info [downloads] download 42 starts over: the server's session changed"),
        ("Keychain read failed (-25300)", "Keychain read failed (-25300)"),
        ("Audio options don't line up with the decision's 2 tracks", "Audio options don't line up with the decision's 2 tracks"),
    ])
    func redacts(_ input: String, _ expected: String) {
        #expect(redactor.redact(input) == expected)
    }

    @Test func shortAndBlankNamesAreIgnored() {
        // A one-letter name would take letters out of every word.
        #expect(redactor.names.allSatisfy { $0.count >= 2 })
        #expect(redactor.redact("x marks the spot") == "x marks the spot")
    }
}

@Suite struct DiagnosticsReportTests {
    let at = Date(timeIntervalSince1970: 1_800_000_000)

    @Test func theTextHoldsVersionsSummariesAndLogWithoutNamesOrAddresses() {
        let report = DiagnosticsReport(
            appVersion: "1.8.0 (10400099)", deviceModel: "iPhone17,1", system: "iOS 18.2 (22C152)",
            serverVersion: "1.4.3", serverAPILevel: 28,
            summaries: [
                DiagnosticSummary(kind: .hang, receivedAt: at, appVersion: "1.8.0 (10400099)", osVersion: "iPhone OS 18.2", lines: ["Hang 3.2 sec", "WatchMyMedia +0x1a2b"]),
                DiagnosticSummary(kind: .hang, receivedAt: at.addingTimeInterval(-60), lines: ["Hang 9 sec"]),
                DiagnosticSummary(kind: .crash, receivedAt: at, lines: ["EXC_BAD_ACCESS (SIGSEGV)"]),
            ],
            logLines: ["15:02:11 warning [Playback] Playback request failed: http://192.168.1.20:27587/api/play token=abc", "15:02:12 info [x] Home Server is busy"],
            generatedAt: at
        )
        let text = report.text(redactor: DiagnosticsRedactor(names: ["Home Server"]))
        #expect(text.contains("App: 1.8.0 (10400099)"))
        #expect(text.contains("Device: iPhone17,1, iOS 18.2 (22C152)"))
        #expect(text.contains("Server: 1.4.3, API level 28"))
        #expect(text.contains("Hang 3.2 sec"))
        #expect(!text.contains("Hang 9 sec"), "only the last of each kind")
        #expect(text.contains("EXC_BAD_ACCESS (SIGSEGV)"))
        #expect(text.contains("Log (2 lines):"))
        #expect(!text.contains("192.168"))
        #expect(!text.contains("abc"))
        #expect(!text.contains("Home Server"))
        #expect(text.contains("<name> is busy"))
    }

    @Test func noServerAndNoReports() {
        let text = DiagnosticsReport(appVersion: "1.0", deviceModel: "AppleTV14,1", system: "tvOS 18.2", generatedAt: at)
            .text(redactor: DiagnosticsRedactor())
        #expect(text.contains("Server: none chosen"))
        #expect(text.contains("Last reports from the system:\n  none"))
        #expect(text.contains("Log (0 lines):"))
    }

    @Test func theLogKeepsItsNewestLinesAndShortensLongOnes() {
        let lines = (1...250).map { "line \($0) " + String(repeating: "a ", count: 200) }
        let text = DiagnosticsReport(appVersion: "1.0", deviceModel: "m", system: "s", logLines: lines, generatedAt: at)
            .text(redactor: DiagnosticsRedactor())
        #expect(text.contains("Log (200 lines):"))
        #expect(!text.contains("line 50 "))
        #expect(text.contains("line 51 "))
        #expect(text.contains("line 250 "))
        let longest = text.split(separator: "\n").map(\.count).max() ?? 0
        #expect(longest <= DiagnosticsReport.maxLineLength + 2)
    }
}
