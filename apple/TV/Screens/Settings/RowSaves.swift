import Foundation
import Observation

/// The Saved line for a change kept on this Apple TV (Quality, its name),
/// which can't fail and which the Kit's save states don't cover yet: shown
/// in its row for a few seconds, then the row's usual line again. Server-kept
/// controls use `SettingsModel.saveState(_:)` and `LiveExtrasStore.leadSave`.
@MainActor
@Observable
final class RowSaves {
    private(set) var notes: [String: RowNote] = [:]
    @ObservationIgnored private var runs: [String: Int] = [:]
    @ObservationIgnored private let savedFor: Duration

    init(savedFor: Duration = .seconds(4)) {
        self.savedFor = savedFor
    }

    func note(_ key: String) -> RowNote? { notes[key] }

    /// Says `line` ("Saved. …") in the row for a while; a newer one in the row wins.
    func saved(_ key: String, _ line: String) {
        let run = (runs[key] ?? 0) + 1
        runs[key] = run
        notes[key] = .saved(line)
        Task {
            try? await Task.sleep(for: savedFor)
            if runs[key] == run { notes[key] = nil }
        }
    }
}
