import Foundation
import WMMModel

/// Reduces a MetricKit diagnostic payload (`MXDiagnosticPayload.jsonRepresentation()`)
/// to `DiagnosticSummary`s: the kind, how long a hang or launch took, how a
/// crash ended, and the top frames as a binary and an offset. It keeps no
/// message text (an Objective-C exception's message can quote anything) and no
/// memory or region details. Plain JSON, so it is tested on the Mac.
enum MetricKitSummaries {
    /// The most frames a summary keeps.
    static let maxFrames = 6

    static func summaries(json data: Data, receivedAt: Date) -> [DiagnosticSummary] {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return [] }
        var out: [DiagnosticSummary] = []
        for (key, kind) in [("appLaunchDiagnostics", DiagnosticKind.launch), ("hangDiagnostics", .hang), ("crashDiagnostics", .crash)] {
            for diagnostic in root[key] as? [[String: Any]] ?? [] {
                let meta = diagnostic["diagnosticMetaData"] as? [String: Any] ?? [:]
                var lines: [String] = []
                switch kind {
                case .launch:
                    lines.append("Launch took \(string(meta["launchDuration"]) ?? "?")")
                case .hang:
                    lines.append("Hang \(string(meta["hangDuration"]) ?? "?")")
                case .crash:
                    lines.append(crashLine(meta))
                case .exits:
                    continue // daily metrics, not a diagnostic (exits(_:receivedAt:))
                }
                lines.append(contentsOf: frames(diagnostic["callStackTree"] as? [String: Any]))
                let app = [string(meta["appVersion"]), string(meta["appBuildVersion"]).map { "(\($0))" }].compactMap { $0 }.joined(separator: " ")
                out.append(DiagnosticSummary(kind: kind, receivedAt: receivedAt, appVersion: app.isEmpty ? nil : app,
                                             osVersion: string(meta["osVersion"]), lines: lines))
            }
        }
        return out
    }

    /// Why the system ended the app in the background, as MetricKit counts
    /// it per day (`MXBackgroundExitData`), with what the page calls each.
    /// Normal exits are left out.
    enum ExitReason: CaseIterable {
        case memoryLimit, memoryPressure, cpuLimit, watchdog, badAccess, illegalInstruction, abnormal, lockedFile, taskTimeout

        var words: String {
            switch self {
            case .memoryLimit: "for memory"
            case .memoryPressure: "to free memory"
            case .cpuLimit: "for CPU use"
            case .watchdog: "by the watchdog"
            case .badAccess: "for a bad access"
            case .illegalInstruction: "for an illegal instruction"
            case .abnormal: "abnormally"
            case .lockedFile: "holding a locked file"
            case .taskTimeout: "for a background task that ran over"
            }
        }
    }

    /// One summary of a day's background exits ("1 for memory"), or none when
    /// every exit was normal.
    static func exits(_ counts: [ExitReason: Int], receivedAt: Date, appVersion: String? = nil, osVersion: String? = nil) -> DiagnosticSummary? {
        let lines = ExitReason.allCases.compactMap { reason in
            counts[reason].flatMap { $0 > 0 ? "\($0) \(reason.words)" : nil }
        }
        guard !lines.isEmpty else { return nil }
        return DiagnosticSummary(kind: .exits, receivedAt: receivedAt, appVersion: appVersion, osVersion: osVersion, lines: lines)
    }

    /// "EXC_BAD_ACCESS (SIGSEGV), Objective-C NSInvalidArgumentException, Namespace SPRINGBOARD, Code 0x8badf00d".
    static func crashLine(_ meta: [String: Any]) -> String {
        var parts: [String] = []
        let type = (meta["exceptionType"] as? NSNumber).map { exceptionNames[$0.intValue] ?? "exception \($0)" }
        let signal = (meta["signal"] as? NSNumber).map { signalNames[$0.intValue] ?? "signal \($0)" }
        switch (type, signal) {
        case let (t?, s?): parts.append("\(t) (\(s))")
        case let (t?, nil): parts.append(t)
        case let (nil, s?): parts.append(s)
        case (nil, nil): parts.append("Crash")
        }
        if let code = meta["exceptionCode"] as? NSNumber, code.intValue != 0 { parts.append("code \(code)") }
        if let objc = meta["objectiveCexceptionReason"] as? [String: Any], let name = string(objc["exceptionName"]) {
            parts.append("Objective-C \(name)")
        }
        if let reason = string(meta["terminationReason"]) { parts.append(String(reason.prefix(120))) }
        return parts.joined(separator: ", ")
    }

    /// The attributed thread's top frames ("WatchMyMedia +0x1a2b3c"), following
    /// each frame's first caller.
    static func frames(_ tree: [String: Any]?) -> [String] {
        let stacks = tree?["callStacks"] as? [[String: Any]] ?? []
        guard let stack = stacks.first(where: { ($0["threadAttributed"] as? Bool) == true }) ?? stacks.first else { return [] }
        var frame = (stack["callStackRootFrames"] as? [[String: Any]])?.first
        var out: [String] = []
        while let f = frame, out.count < maxFrames {
            let binary = string(f["binaryName"]) ?? "?"
            let offset = (f["offsetIntoBinaryTextSegment"] as? NSNumber).map { "+0x" + String($0.uint64Value, radix: 16) } ?? ""
            out.append("\(binary) \(offset)".trimmingCharacters(in: .whitespaces))
            frame = (f["subFrames"] as? [[String: Any]])?.first
        }
        return out
    }

    private static func string(_ value: Any?) -> String? {
        switch value {
        case let s as String: return s.isEmpty ? nil : s
        case let n as NSNumber: return n.stringValue
        default: return nil
        }
    }

    private static let exceptionNames = [
        1: "EXC_BAD_ACCESS", 2: "EXC_BAD_INSTRUCTION", 3: "EXC_ARITHMETIC", 4: "EXC_EMULATION", 5: "EXC_SOFTWARE",
        6: "EXC_BREAKPOINT", 10: "EXC_CRASH", 11: "EXC_RESOURCE", 12: "EXC_GUARD",
    ]
    private static let signalNames = [
        4: "SIGILL", 5: "SIGTRAP", 6: "SIGABRT", 8: "SIGFPE", 9: "SIGKILL", 10: "SIGBUS", 11: "SIGSEGV", 13: "SIGPIPE", 15: "SIGTERM",
    ]
}
