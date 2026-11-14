import Foundation
import Testing
import WMMModel
@testable import WMMData

/// MetricKit's daily background exit counts, kept as one summary
/// (PLAN-APPLE-REVIEW.md §6.4, "Exits in the background" on Diagnostics.dc.html).
@Suite struct DiagnosticsExitsTests {
    let at = Date(timeIntervalSince1970: 1_800_000_000)

    @Test func eachReasonWithExitsIsALineInAFixedOrder() throws {
        let summary = try #require(MetricKitSummaries.exits([.watchdog: 2, .memoryLimit: 1, .cpuLimit: 0], receivedAt: at, appVersion: "1.9.0", osVersion: "iPhone OS 18.2"))
        #expect(summary.kind == .exits)
        #expect(summary.lines == ["1 for memory", "2 by the watchdog"])
        #expect(summary.headline == "1 for memory, 2 by the watchdog")
        #expect(summary.receivedAt == at)
    }

    @Test func aDayOfNormalExitsKeepsNothing() {
        #expect(MetricKitSummaries.exits([:], receivedAt: at) == nil)
        #expect(MetricKitSummaries.exits([.memoryPressure: 0], receivedAt: at) == nil)
    }

    @Test func logLinesKeepTheDevicesTime() throws {
        let newYork = try #require(TimeZone(identifier: "America/New_York"))
        // 2027-01-15 08:00:00 UTC.
        let line = Diagnostics.logLine(date: Date(timeIntervalSince1970: 1_800_000_000), level: .info, category: "home", message: "loaded", timeZone: newYork)
        #expect(line == "03:00:00 info [home] loaded")
    }

    @Test func theStoreKeepsTheNewestExitsBesideTheDiagnostics() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("DiagnosticsExitsTests-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = DiagnosticsStore(directory: directory)
        store.record([DiagnosticSummary(kind: .hang, receivedAt: at, lines: ["Hang 2 sec"])])
        store.record([try #require(MetricKitSummaries.exits([.memoryLimit: 1], receivedAt: at))])
        store.record([try #require(MetricKitSummaries.exits([.abnormal: 3], receivedAt: at.addingTimeInterval(86_400)))])
        let kept = store.latest()
        #expect(kept.map(\.kind) == [.hang, .exits])
        #expect(kept.last?.lines == ["3 abnormally"])
    }
}
