import Foundation
import os

/// The intervals PLAN-APPLE-REVIEW.md §4.3.1 measures, as signposts in
/// Instruments (subsystem `media.watchmy.client`, category `Performance`) and
/// for `XCTOSSignpostMetric` in the performance tests.
public enum PerformanceInterval: String, CaseIterable, Sendable {
    /// From the app starting to Home showing its first content.
    case launchToHome = "Launch to Home"
    /// From Play to the first frame on screen: the two below, end to end.
    case playToFirstFrame = "Play to first frame"
    /// From Play to the server's playback decision.
    case playToDecision = "Play to decision"
    /// From the decision to the first frame on screen.
    case decisionToFirstFrame = "Decision to first frame"
}

/// How an interval ended: `done` when it reached its end, `cancelled` when the
/// person left first or another Play replaced it, `failed` on an error.
public enum SignpostOutcome: String, Sendable {
    case done, cancelled, failed
}

/// Where the intervals go: the system's signposts in the app, a list in tests.
public protocol PerformanceRecorder: Sendable {
    /// Starts `interval` and returns a token for its end.
    func begin(_ interval: PerformanceInterval) -> Int
    func end(_ interval: PerformanceInterval, token: Int, outcome: SignpostOutcome)
}

/// The system's signposts, through `OSSignposter`. Cheap when nothing records.
public final class OSPerformanceRecorder: PerformanceRecorder {
    public static let subsystem = "media.watchmy.client"
    public static let category = "Performance"

    private let signposter = OSSignposter(subsystem: OSPerformanceRecorder.subsystem, category: OSPerformanceRecorder.category)
    private struct Open: @unchecked Sendable { var states: [Int: OSSignpostIntervalState] = [:]; var next = 0 }
    private let open = OSAllocatedUnfairLock(initialState: Open())

    public init() {}

    public func begin(_ interval: PerformanceInterval) -> Int {
        let id = signposter.makeSignpostID()
        // OSSignposter takes a StaticString name, so each interval is spelled out.
        let state: OSSignpostIntervalState
        switch interval {
        case .launchToHome: state = signposter.beginInterval("Launch to Home", id: id)
        case .playToFirstFrame: state = signposter.beginInterval("Play to first frame", id: id)
        case .playToDecision: state = signposter.beginInterval("Play to decision", id: id)
        case .decisionToFirstFrame: state = signposter.beginInterval("Decision to first frame", id: id)
        }
        return open.withLock { open in
            open.next += 1
            open.states[open.next] = state
            return open.next
        }
    }

    public func end(_ interval: PerformanceInterval, token: Int, outcome: SignpostOutcome) {
        guard let state = open.withLock({ $0.states.removeValue(forKey: token) }) else { return }
        let message = outcome.rawValue
        switch interval {
        case .launchToHome: signposter.endInterval("Launch to Home", state, "\(message, privacy: .public)")
        case .playToFirstFrame: signposter.endInterval("Play to first frame", state, "\(message, privacy: .public)")
        case .playToDecision: signposter.endInterval("Play to decision", state, "\(message, privacy: .public)")
        case .decisionToFirstFrame: signposter.endInterval("Decision to first frame", state, "\(message, privacy: .public)")
        }
    }
}

/// The app's performance signposts (PLAN-APPLE-REVIEW.md §4.3.1). Each call is
/// safe from any thread and in any order: an end without its begin, or a
/// second end, does nothing, so a call site can't unbalance an interval.
///
/// - Launch: `launchBegan()` once as the app starts, `homeShowedContent()`
///   when Home first draws its rows (later loads of Home are ignored).
/// - Play: `playStarted()` on Play, `playDecided()` when the server's decision
///   arrives, `playShowedFirstFrame()` at the first frame, `playEnded(_:)` when
///   the player closes or fails before that. A Play while one is open ends
///   the open one as cancelled.
public final class PerformanceSignposts: Sendable {
    public static let shared = PerformanceSignposts(recorder: OSPerformanceRecorder())

    private struct State: Sendable {
        var launch: Int?
        var launchDone = false
        var play: Int?
        var phase: (interval: PerformanceInterval, token: Int)?
    }

    private let recorder: any PerformanceRecorder
    private let state = OSAllocatedUnfairLock(initialState: State())

    public init(recorder: any PerformanceRecorder) {
        self.recorder = recorder
    }

    public func launchBegan() {
        state.withLock { s in
            guard s.launch == nil, !s.launchDone else { return }
            s.launch = recorder.begin(.launchToHome)
        }
    }

    public func homeShowedContent() {
        state.withLock { s in
            guard let token = s.launch else { return }
            recorder.end(.launchToHome, token: token, outcome: .done)
            s.launch = nil
            s.launchDone = true
        }
    }

    public func playStarted() {
        state.withLock { s in
            close(&s, .cancelled)
            s.play = recorder.begin(.playToFirstFrame)
            s.phase = (.playToDecision, recorder.begin(.playToDecision))
        }
    }

    public func playDecided() {
        state.withLock { s in
            guard s.play != nil, let phase = s.phase, phase.interval == .playToDecision else { return }
            recorder.end(.playToDecision, token: phase.token, outcome: .done)
            s.phase = (.decisionToFirstFrame, recorder.begin(.decisionToFirstFrame))
        }
    }

    public func playShowedFirstFrame() {
        state.withLock { s in
            // A first frame before the decision (an offline play) still ends Play.
            close(&s, .done)
        }
    }

    public func playEnded(_ outcome: SignpostOutcome = .cancelled) {
        state.withLock { s in close(&s, outcome) }
    }

    private func close(_ s: inout State, _ outcome: SignpostOutcome) {
        if let phase = s.phase { recorder.end(phase.interval, token: phase.token, outcome: outcome) }
        if let play = s.play { recorder.end(.playToFirstFrame, token: play, outcome: outcome) }
        s.phase = nil
        s.play = nil
    }
}
