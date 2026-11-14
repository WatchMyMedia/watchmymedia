import Foundation
import os
import WMMModel

/// The last launch, hang and crash summaries, kept in a small file in the
/// app's own container (Application Support/Diagnostics), excluded from
/// backups so it never leaves the device. Safe from any thread.
public final class DiagnosticsStore: Sendable {
    public static let shared = DiagnosticsStore(
        directory: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
            .appendingPathComponent("Diagnostics", isDirectory: true)
    )

    private let file: URL?
    private let lock = OSAllocatedUnfairLock()

    /// `directory` nil keeps nothing (no container).
    public init(directory: URL?) {
        file = directory?.appendingPathComponent("last-reports.json")
    }

    /// The kept summaries, at most one of each kind.
    public func latest() -> [DiagnosticSummary] {
        lock.withLock { read() }
    }

    /// Keeps `summaries`, each replacing the kept one of its kind; the last of a kind wins.
    public func record(_ summaries: [DiagnosticSummary]) {
        guard !summaries.isEmpty, let file else { return }
        lock.withLock {
            var kept = Dictionary(read().map { ($0.kind, $0) }, uniquingKeysWith: { _, new in new })
            for summary in summaries { kept[summary.kind] = summary }
            let ordered = DiagnosticKind.allCases.compactMap { kept[$0] }
            do {
                var directory = file.deletingLastPathComponent()
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                var values = URLResourceValues()
                values.isExcludedFromBackup = true
                try? directory.setResourceValues(values)
                let encoder = JSONEncoder()
                encoder.dateEncodingStrategy = .iso8601
                try encoder.encode(ordered).write(to: file, options: [.atomic])
            } catch {
                diagnosticsLog.error("could not keep the diagnostic summaries: \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    /// Forgets every kept summary.
    public func clear() {
        guard let file else { return }
        lock.withLock { try? FileManager.default.removeItem(at: file) }
    }

    private func read() -> [DiagnosticSummary] {
        guard let file, let data = try? Data(contentsOf: file) else { return [] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return (try? decoder.decode([DiagnosticSummary].self, from: data)) ?? []
    }
}

let diagnosticsLog = Logger(subsystem: "media.watchmy.client", category: "diagnostics")
