import Foundation
import os
import WMMModel

private let log = Logger(subsystem: "media.watchmy.client", category: "Playback")

// The reports a file play sends the server: heartbeats, the stops and the
// session ends, in order through one queue that outlives `release()`, and
// what the server's replies change.

extension RealPlaybackController {
    enum Outgoing {
        case report(ProgressReport, generation: Int)
        case endSession(String)
    }

    func heartbeat(_ v: Viewing, _ status: PlayerStatus, _ pos: Int, _ buffered: Int, force: Bool) {
        let s = playState(for: status)
        guard force || heartbeatDue(s, lastReported: v.lastReported, sinceLastBeatMs: clock.elapsedMs - v.lastBeatAt, auto: asked == .auto) else { return }
        report(v, s, pos, buffered)
    }

    /// Queues a heartbeat; false when none may be sent.
    @discardableResult
    func report(_ v: Viewing, _ playState: PlayState, _ pos: Int, _ buffered: Int) -> Bool {
        guard let d = v.decision, let playID = v.playID, !v.stopped, !v.adminStopped else { return false }
        // After "closed", nothing but the final "stopped" until the app is back.
        if backgrounded && playState != .closed && playState != .stopped { return false }
        v.lastReported = playState
        v.lastBeatAt = clock.elapsedMs
        let position = v.finished ? duration(v, d) : pos
        let now = clock.elapsedMs
        let transfer = media?.transfer
        send(.report(ProgressReport(
            fileID: v.request.fileID, positionMs: position, durationMs: duration(v, d), playID: playID, state: playState, bufferedMs: buffered,
            throughputBps: transfer?.bps ?? media?.throughputBps,
            throughputAgeMs: transfer?.ageMs,
            switchGeneration: playState == .playing ? v.switches.accepted : nil,
            switchFailedGeneration: v.switches.failed,
            stalls: v.measure.stalls, stallMs: v.measure.stallMs(at: now), startupMs: v.measure.takeStartup(), recordingID: v.request.recordingID
        ), generation: v.generation))
        return true
    }

    /// Queues `message`. The pump holds the controller until the queue is
    /// empty, so the last reports go out after release.
    func send(_ message: Outgoing) {
        reports.submit(message)
    }

    /// Adds `message` to the queue. While the server can't be reached each
    /// report waits for its time-out (`behindDelivery`: one is out), so a
    /// progress report replaces the one of the same viewing still waiting:
    /// only the newest position is worth sending (PLAN-APPLE-REVIEW.md
    /// §3.1.4). Stops, "closed", session ends and reports that acknowledge or
    /// refuse a quality switch are never dropped, and the rest keep their
    /// order. A dropped report's startup time goes with its replacement.
    static func enqueue(_ message: Outgoing, into outbox: inout [Outgoing], behindDelivery: Bool = true) {
        guard behindDelivery, case .report(var new, let generation) = message, new.isProgress else {
            outbox.append(message)
            return
        }
        outbox.removeAll { queued in
            guard case let .report(old, g) = queued, g == generation, old.isProgress,
                  old.switchGeneration == nil || old.switchGeneration == new.switchGeneration,
                  old.switchFailedGeneration == nil || old.switchFailedGeneration == new.switchFailedGeneration
            else { return false }
            if new.startupMs == nil { new.startupMs = old.startupMs }
            return true
        }
        outbox.append(.report(new, generation: generation))
    }

    func deliver(_ message: Outgoing) async {
        switch message {
        case let .report(report, generation):
            guard let reply = await attempt("send a heartbeat", { try await gateway.reportProgress(report) }) else { return }
            onReply(generation, report, reply)
        case let .endSession(id):
            _ = await attempt("stop the session") { try await gateway.stopSession(id) }
        }
    }

    func onReply(_ generation: Int, _ report: ProgressReport, _ reply: ProgressReply) {
        guard let v = viewing, v.generation == generation else { return }
        if v.switches.acknowledge(accepted: report.switchGeneration) {
            if let pending = v.pendingSwitch, let current = v.decision { endReplacedSession(pending.previous, current) }
            v.pendingSwitch = nil
            if let message = v.stepMessage { showNote(v, message) }
            v.stepMessage = nil
            refresh(0)
        }
        v.switches.acknowledge(failed: report.switchFailedGeneration)
        if reply.completed { v.watched = true }
        guard !v.stopped else { return }
        // The play had ended (timed out, server restarted): the server carries on with a new one.
        if let id = reply.newPlayID { v.playID = id }
        if let message = reply.stopMessage {
            adminStop(v, message)
        } else if let step = reply.lowerTo {
            changeStep(v, to: step, reply: reply)
        }
    }

    /// Ends `d`'s server session unless the viewing plays it, or keeps it to go back to.
    func endUnused(_ d: PlaybackDecision, of v: Viewing) {
        guard let id = d.sessionID, id != v.decision?.sessionID, id != v.pendingSwitch?.previous.sessionID else { return }
        send(.endSession(id))
    }

    func endReplacedSession(_ old: PlaybackDecision, _ new: PlaybackDecision) {
        guard let id = old.sessionID, id != new.sessionID else { return }
        send(.endSession(id))
    }
}

private extension ProgressReport {
    /// Where the play is, which a newer report of the same viewing says better.
    var isProgress: Bool { state == .playing || state == .paused || state == .buffering }
}
