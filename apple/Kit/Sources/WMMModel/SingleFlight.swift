import Foundation

/// One request of a kind in flight at a time (PLAN-APPLE-REVIEW.md §4.1.3):
/// a caller while one runs waits for that one's answer, unless it needs an
/// answer newer than that one's start, which a different `stamp` says (an
/// event came, or the sign-in changed), when it asks again itself.
@MainActor
public final class SingleFlight<Value: Sendable> {
    private var running: (stamp: String, id: Int, task: Task<Value, any Error>)?
    private var count = 0

    public init() {}

    /// Whether a request is in flight.
    public var busy: Bool { running != nil }

    public func run(stamp: String, _ operation: @escaping @MainActor () async throws -> Value) async throws -> Value {
        if let running, running.stamp == stamp { return try await running.task.value }
        count += 1
        let id = count
        // Cleared by the request itself as it ends, before anyone waiting
        // resumes, so a caller after it never takes its answer.
        let task = Task { [weak self] in
            defer { self?.finished(id) }
            return try await operation()
        }
        running = (stamp, id, task)
        return try await task.value
    }

    private func finished(_ id: Int) {
        if running?.id == id { running = nil }
    }
}

extension SingleFlight where Value == Void {
    /// For a request that keeps its own failures.
    public func run(stamp: String, _ operation: @escaping @MainActor () async -> Void) async {
        try? await run(stamp: stamp) { () async throws in await operation() }
    }
}

/// Runs `operations` side by side on the main actor and returns when all
/// have: the foreground refresh's independent requests (PLAN-APPLE-REVIEW.md §4.1.4).
@MainActor
public func together(_ operations: @Sendable @MainActor () async -> Void...) async {
    await withTaskGroup(of: Void.self) { group in
        for operation in operations { group.addTask { await operation() } }
    }
}
