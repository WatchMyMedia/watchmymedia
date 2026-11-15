import Foundation
import Observation

/// Each row's saving state (§3.1): Saving while a change is sent, Saved
/// with what it did for a few seconds, or why it failed until the next
/// choice. Only that row waits; a newer choice in the row wins.
@MainActor
@Observable
final class RowSaves {
    private(set) var notes: [String: RowNote] = [:]
    @ObservationIgnored private var runs: [String: Int] = [:]
    @ObservationIgnored private let savedFor: Duration

    init(savedFor: Duration = .seconds(5)) {
        self.savedFor = savedFor
    }

    func note(_ key: String) -> RowNote? { notes[key] }

    /// Runs `work`, which says whether it saved, and shows the outcome in the row.
    func run(_ key: String, saved: String, failed: @escaping () -> String, _ work: () async -> Bool) async {
        let run = (runs[key] ?? 0) + 1
        runs[key] = run
        notes[key] = .saving
        let ok = await work()
        guard runs[key] == run else { return }
        notes[key] = ok ? .saved(saved) : .failed(failed())
        guard ok else { return }
        try? await Task.sleep(for: savedFor)
        if runs[key] == run { notes[key] = nil }
    }

    /// Says a change saved that couldn't fail (one kept on this device).
    func saved(_ key: String, _ line: String) {
        Task { await run(key, saved: line, failed: { "" }) { true } }
    }
}
