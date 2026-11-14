import Foundation
import os
import Testing
import WMMModel
@testable import WMMData

/// MetricKit's payloads reduced to summaries, the store that keeps the last of
/// each, and the copied text (PLAN-APPLE-REVIEW.md §6.4).
@Suite struct DiagnosticsDataTests {
    let at = Date(timeIntervalSince1970: 1_800_000_000)

    /// A diagnostic payload in MetricKit's JSON shape, with the fields a real
    /// one carries that the summary must leave out.
    static let payload = Data(#"""
    {
      "timeStampBegin": "2026-11-12 00:00:00",
      "timeStampEnd": "2026-11-13 00:00:00",
      "crashDiagnostics": [{
        "diagnosticMetaData": {
          "appVersion": "1.8.0", "appBuildVersion": "10400099", "osVersion": "iPhone OS 18.2 (22C152)",
          "deviceType": "iPhone17,1", "exceptionType": 1, "exceptionCode": 0, "signal": 11,
          "terminationReason": "Namespace SIGNAL, Code 11",
          "virtualMemoryRegionInfo": "0x0 is not in any region",
          "objectiveCexceptionReason": {
            "composedMessage": "no item named Harbor Lights at http://192.168.1.20:27587",
            "exceptionName": "NSInvalidArgumentException", "className": "NSException"
          }
        },
        "callStackTree": {
          "callStackPerThread": true,
          "callStacks": [
            {"threadAttributed": false, "callStackRootFrames": [{"binaryName": "Other", "offsetIntoBinaryTextSegment": 1}]},
            {"threadAttributed": true, "callStackRootFrames": [{
              "binaryName": "WatchMyMedia", "offsetIntoBinaryTextSegment": 6699836, "binaryUUID": "3F2504E0-4F89-11D3-9A0C-0305E82C3301",
              "subFrames": [{"binaryName": "SwiftUI", "offsetIntoBinaryTextSegment": 255}]
            }]}
          ]
        }
      }],
      "hangDiagnostics": [{
        "diagnosticMetaData": {"hangDuration": "3.2 sec", "appVersion": "1.8.0"},
        "callStackTree": {"callStacks": [{"threadAttributed": true, "callStackRootFrames": [{"binaryName": "WatchMyMedia", "offsetIntoBinaryTextSegment": 4096}]}]}
      }],
      "appLaunchDiagnostics": [{"diagnosticMetaData": {"launchDuration": "4100 ms"}}],
      "cpuExceptionDiagnostics": [{"diagnosticMetaData": {"totalCPUTime": "90 sec"}}]
    }
    """#.utf8)

    @Test func payloadsBecomeSummariesWithoutMessages() {
        let summaries = MetricKitSummaries.summaries(json: Self.payload, receivedAt: at)
        #expect(summaries.map(\.kind) == [.launch, .hang, .crash])
        #expect(summaries[0].lines == ["Launch took 4100 ms"])
        #expect(summaries[1].lines == ["Hang 3.2 sec", "WatchMyMedia +0x1000"])
        let crash = summaries[2]
        #expect(crash.appVersion == "1.8.0 (10400099)")
        #expect(crash.osVersion == "iPhone OS 18.2 (22C152)")
        #expect(crash.lines == [
            "EXC_BAD_ACCESS (SIGSEGV), Objective-C NSInvalidArgumentException, Namespace SIGNAL, Code 11",
            "WatchMyMedia +0x663b3c", "SwiftUI +0xff",
        ])
        let all = crash.lines.joined()
        #expect(!all.contains("Harbor"), "an exception's message is never kept")
        #expect(!all.contains("region"))
        #expect(MetricKitSummaries.summaries(json: Data("not json".utf8), receivedAt: at).isEmpty)
    }

    @Test func theStoreKeepsTheLastOfEachKind() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("diagnostics-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = DiagnosticsStore(directory: directory)
        #expect(store.latest().isEmpty)
        store.record([DiagnosticSummary(kind: .hang, receivedAt: at, lines: ["Hang 1 sec"])])
        store.record([
            DiagnosticSummary(kind: .crash, receivedAt: at, lines: ["EXC_CRASH"]),
            DiagnosticSummary(kind: .hang, receivedAt: at, lines: ["Hang 2 sec"]),
            DiagnosticSummary(kind: .hang, receivedAt: at, lines: ["Hang 3 sec"]),
        ])
        // A second store on the same folder reads the file: it outlives the process.
        let again = DiagnosticsStore(directory: directory)
        #expect(again.latest().map(\.kind) == [.hang, .crash])
        #expect(again.latest().first?.lines == ["Hang 3 sec"])
        #expect(try directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
        again.clear()
        #expect(store.latest().isEmpty)
        #expect(DiagnosticsStore(directory: nil).latest().isEmpty)
    }

    @Test @MainActor func theCopiedTextNamesNoServerAccountOrAddress() {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("diagnostics-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = DiagnosticsStore(directory: directory)
        store.record(MetricKitSummaries.summaries(json: Self.payload, receivedAt: at))
        let server = Server(id: "s1", name: "Riverside Media", baseURL: URL(string: "http://nas:27587")!, version: "1.4.3", apiLevel: 28)
        let saved = [SavedServer(server: server, userName: "jordan", signedIn: true, lastUsedAt: at)]
        // The app's own log line, as a release would write it.
        Logger(subsystem: Diagnostics.subsystem, category: "test")
            .error("Riverside Media at http://nas:27587 refused jordan with token=\("abc123def456ghi789jkl", privacy: .public)")
        let text = Diagnostics.text(server: server, servers: saved, names: ["Jordan’s iPhone"], store: store, now: at)
        #expect(text.contains("Server: 1.4.3, API level 28"))
        #expect(text.contains("Hang 3.2 sec"))
        #expect(text.contains("EXC_BAD_ACCESS (SIGSEGV)"))
        // The log store usually has the line by now; when it has, it's redacted whole.
        if text.contains("[test]") {
            #expect(text.contains("error [test] <name> at <address> refused <name> with token=<redacted>"))
        }
        for secret in ["Riverside", "nas:27587", "jordan", "Jordan", "abc123", "192.168"] {
            #expect(!text.contains(secret), "\(secret) leaked")
        }
    }

    @Test func logLinesAreOneLineWithTimeLevelAndCategory() {
        let line = Diagnostics.logLine(date: at, level: .error, category: "storage", message: "could not save\nthe servers")
        #expect(line.hasSuffix(" error [storage] could not save the servers"))
        #expect(!line.contains("\n"))
    }

    @Test func theDeviceIsAModelNotAName() {
        let model = Diagnostics.hardwareModel()
        #expect(!model.isEmpty)
        #expect(Diagnostics.systemName().hasPrefix("macOS ") || Diagnostics.systemName().hasPrefix("iOS ") || Diagnostics.systemName().hasPrefix("tvOS "))
    }
}
