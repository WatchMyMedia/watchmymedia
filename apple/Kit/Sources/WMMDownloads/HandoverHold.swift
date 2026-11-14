import Foundation
#if os(iOS)
import UIKit
#endif

/// Keeps the app running, while it is away, until the next download is
/// handed to the system (PLAN-APPLE-REVIEW.md §3.1.5).
///
/// When a download finishes in the background the system wakes the app,
/// delivers the session's events, and suspends it again once the session's
/// completion handler is called. The next download starts at once, but an
/// HLS one is first made ready by the app itself (a token request and a
/// playlist probe), which a suspended app never finishes. So the completion
/// handler waits while a download is being made ready, for at most
/// ``longest``, and a background task covers the same time.
@MainActor
final class HandoverHold {
    /// The longest a completion handler waits: the system allows about 30 seconds.
    static let longest: Duration = .seconds(20)

    private let beginTask: @MainActor () -> @MainActor () -> Void
    private let sleep: @Sendable (Duration) async -> Void
    private var completions: [() -> Void] = []
    private var preparing = false
    private var endTask: (@MainActor () -> Void)?
    private var deadline: Task<Void, Never>?

    /// - Parameters:
    ///   - beginTask: starts a background task and returns what ends it.
    ///   - sleep: waits out ``longest`` (tests pass their own).
    init(beginTask: @escaping @MainActor () -> @MainActor () -> Void = HandoverHold.backgroundTask,
         sleep: @escaping @Sendable (Duration) async -> Void = { try? await Task.sleep(for: $0) }) {
        self.beginTask = beginTask
        self.sleep = sleep
    }

    /// Whether a download is being made ready to hand to the system; the
    /// engine says so at every change of its downloads' states.
    func setPreparing(_ now: Bool) {
        guard now != preparing else { return }
        preparing = now
        if now {
            endTask = beginTask()
        } else {
            release()
            endTask?()
            endTask = nil
        }
    }

    /// The system delivered a background session's events: `completion` is
    /// called once nothing is being made ready, or after ``longest``.
    func delivered(_ completion: @escaping () -> Void) {
        completions.append(completion)
        guard preparing else { return release() }
        guard deadline == nil else { return }
        deadline = Task { [weak self, sleep] in
            await sleep(Self.longest)
            guard !Task.isCancelled else { return }
            self?.release()
        }
    }

    /// Completion handlers still waiting.
    var waiting: Int { completions.count }

    private func release() {
        deadline?.cancel()
        deadline = nil
        let held = completions
        completions = []
        for completion in held { completion() }
    }

    /// A UIKit background task on iPhone and iPad; nothing elsewhere.
    static func backgroundTask() -> @MainActor () -> Void {
        #if os(iOS)
        var id = UIBackgroundTaskIdentifier.invalid
        let end: @MainActor () -> Void = {
            guard id != .invalid else { return }
            UIApplication.shared.endBackgroundTask(id)
            id = .invalid
        }
        id = UIApplication.shared.beginBackgroundTask(withName: "Download handover") {
            MainActor.assumeIsolated { end() }
        }
        return end
        #else
        return {}
        #endif
    }
}
