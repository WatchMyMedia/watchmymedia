import Foundation
import Synchronization

/// Sleeps that end only when the test says (`DataTuning.eventSleep`), so a
/// test waits for a state, never on the real clock (apple/AGENTS.md): the
/// event stream's backoff and linger wait here until ``advance(_:)``.
final class ManualSleeps: Sendable {
    private struct Sleeper {
        let id: Int
        let duration: Duration
        let continuation: CheckedContinuation<Void, any Error>
    }

    private struct State {
        var next = 0
        var waiting: [Sleeper] = []
        var cancelled: Set<Int> = []
        var asked: [Duration] = []
    }

    private let state = Mutex(State())

    /// What is sleeping now, oldest first.
    var waiting: [Duration] { state.withLock { $0.waiting.map(\.duration) } }

    /// Every sleep asked for, in order.
    var asked: [Duration] { state.withLock { $0.asked } }

    func sleep(_ duration: Duration) async throws {
        let id = state.withLock { s in
            defer { s.next += 1 }
            s.asked.append(duration)
            return s.next
        }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                let cancelled = state.withLock { s in
                    if s.cancelled.remove(id) != nil { return true }
                    s.waiting.append(Sleeper(id: id, duration: duration, continuation: continuation))
                    return false
                }
                if cancelled { continuation.resume(throwing: CancellationError()) }
            }
        } onCancel: {
            let sleeper = state.withLock { s -> Sleeper? in
                guard let i = s.waiting.firstIndex(where: { $0.id == id }) else {
                    s.cancelled.insert(id)
                    return nil
                }
                return s.waiting.remove(at: i)
            }
            sleeper?.continuation.resume(throwing: CancellationError())
        }
    }

    /// Ends the oldest sleep of `duration` (of any length when nil); false when none is waiting.
    @discardableResult
    func advance(_ duration: Duration? = nil) -> Bool {
        let sleeper = state.withLock { s -> Sleeper? in
            guard let i = s.waiting.firstIndex(where: { duration == nil || $0.duration == duration }) else { return nil }
            return s.waiting.remove(at: i)
        }
        sleeper?.continuation.resume()
        return sleeper != nil
    }
}
