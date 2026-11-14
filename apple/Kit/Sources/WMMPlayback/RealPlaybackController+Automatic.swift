import Foundation
import WMMModel

// Automatic quality's switches: going back to the stream that played when a
// new step fails, and the note over the video that says what changed.
// `changeStep(_:to:reply:)` stays in RealPlaybackController.swift beside the
// server-level check it holds.

extension RealPlaybackController {
    func restorePrevious(_ v: Viewing, _ pending: Viewing.PendingSwitch, showFailureNote: Bool = true) {
        v.pendingSwitch = nil
        v.awaitingFrameGeneration = nil
        v.switches.forgetAccepted()
        v.switches.fail(pending.generation)
        v.pendingPositionMs = pending.atMs
        v.stepMessage = nil
        apply(v, pending.previous, requestedStartMs: pending.atMs)
        if showFailureNote {
            showNote(v, "Still playing at \(pending.previous.autoStep?.shortLabel ?? "Original"). Quality will retry later.")
        }
        refresh(0)
    }

    /// Shows `text` over the video for a few seconds; the next refresh publishes it.
    func showNote(_ v: Viewing, _ text: String) {
        v.qualityNote = text
        v.qualityNoteUntil = clock.elapsedMs + qualityNoteMs
    }
}
