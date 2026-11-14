import Foundation

/// A controller's beat on its ``PlaybackClock``: the file player's every
/// ``tickMs`` and Live TV's every half second (PLAN-APPLE-REVIEW.md §5.1).
/// The next beat is set before `tick` runs, so a tick that stops the ticker,
/// or starts it again, has the last word.
@MainActor
final class Ticker {
    private let clock: any PlaybackClock
    private let everyMs: Int
    private var handle: TimerHandle?
    private var lastAt = 0

    init(clock: any PlaybackClock, everyMs: Int) {
        self.clock = clock
        self.everyMs = everyMs
    }

    var running: Bool { handle != nil }

    /// Starts beating unless it already is. `tick` gets the milliseconds since the last beat.
    func start(_ tick: @escaping @MainActor (_ dtMs: Int) -> Void) {
        guard handle == nil else { return }
        lastAt = clock.elapsedMs
        schedule(tick)
    }

    /// Starts again from now, whether it was beating or not.
    func restart(_ tick: @escaping @MainActor (_ dtMs: Int) -> Void) {
        stop()
        start(tick)
    }

    func stop() {
        handle?.cancel()
        handle = nil
    }

    private func schedule(_ tick: @escaping @MainActor (Int) -> Void) {
        handle = clock.after(ms: everyMs) { [weak self] in
            guard let self else { return }
            let now = clock.elapsedMs
            let dt = now - lastAt
            lastAt = now
            schedule(tick)
            tick(dt)
        }
    }
}
