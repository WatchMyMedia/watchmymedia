import Foundation

/// Time for the player: `elapsedMs` for timers (never jumps), `now` for the
/// server's expiry times. Every timer the player runs goes through ``after(ms:_:)``,
/// so tests drive it with virtual time (android/core/playback/.../Clock.kt).
@MainActor
public protocol PlaybackClock: AnyObject {
    var elapsedMs: Int { get }

    var now: Date { get }

    /// Runs `action` once, `ms` from now, unless the handle is cancelled first.
    func after(ms: Int, _ action: @escaping @MainActor () -> Void) -> TimerHandle
}

/// A timer from ``PlaybackClock/after(ms:_:)``.
@MainActor
public final class TimerHandle {
    private var onCancel: (@MainActor () -> Void)?

    public init(onCancel: @escaping @MainActor () -> Void) {
        self.onCancel = onCancel
    }

    public func cancel() {
        onCancel?()
        onCancel = nil
    }
}

/// The device's clocks: monotonic time for timers, the wall clock for expiry times.
@MainActor
public final class SystemPlaybackClock: PlaybackClock {
    private let origin = ContinuousClock.now

    public init() {}

    public var elapsedMs: Int { Int((ContinuousClock.now - origin) / .milliseconds(1)) }

    public var now: Date { Date() }

    public func after(ms: Int, _ action: @escaping @MainActor () -> Void) -> TimerHandle {
        let task = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(ms))
            if !Task.isCancelled { action() }
        }
        return TimerHandle { task.cancel() }
    }
}
