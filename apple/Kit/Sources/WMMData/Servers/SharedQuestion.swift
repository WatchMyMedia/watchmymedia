import Foundation

/// One question to a server, out once for everyone who needs its answer.
/// Whoever waits and is cancelled (a keystroke cancels a search) leaves at
/// once with ``CancellationError``, and the question carries on: the next
/// one to wait hears the same answer instead of asking again.
@MainActor
final class SharedQuestion {
    private var outcome: Result<Void, any Error>?
    private var waiting: [Int: CheckedContinuation<Void, any Error>] = [:]
    private var count = 0

    /// The question was answered, or failed: everyone waiting hears it.
    func finish(_ outcome: Result<Void, any Error>) {
        self.outcome = outcome
        let all = waiting
        waiting = [:]
        for continuation in all.values { continuation.resume(with: outcome) }
    }

    /// Returns when the question is answered, throws what it failed with,
    /// or throws ``CancellationError`` as soon as the caller is cancelled.
    func wait() async throws {
        if let outcome { return try outcome.get() }
        count += 1
        let id = count
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                if Task.isCancelled {
                    continuation.resume(throwing: CancellationError())
                } else {
                    waiting[id] = continuation
                }
            }
        } onCancel: {
            Task { @MainActor in self.leave(id) }
        }
    }

    private func leave(_ id: Int) {
        waiting.removeValue(forKey: id)?.resume(throwing: CancellationError())
    }
}
