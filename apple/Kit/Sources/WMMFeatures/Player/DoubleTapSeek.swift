import Foundation

// Double-tap to seek, as android/mobile/.../DoubleTapSeek.kt does it.

/// Which side of the screen a tap landed on. The middle fifth does not seek.
public enum SeekZone: Hashable, Sendable { case back, none, forward }

public func seekZone(x: Double, width: Double) -> SeekZone {
    guard width > 0 else { return .none }
    if x < width * 0.4 { return .back }
    if x > width * 0.6 { return .forward }
    return .none
}

/// A run of seeks on one side: `steps` taps' worth, the last at `lastTapMs`.
public struct SeekBurst: Hashable, Sendable {
    public var zone: SeekZone
    public var steps: Int
    public var lastTapMs: Int

    public init(zone: SeekZone, steps: Int, lastTapMs: Int) {
        self.zone = zone
        self.steps = steps
        self.lastTapMs = lastTapMs
    }

    /// One tap's seek, negative going back.
    public var stepMs: Int { zone == .back ? -DoubleTapSeek.stepMs : DoubleTapSeek.stepMs }

    /// The whole run's seek.
    public var totalMs: Int { steps * stepMs }

    /// "+30 s" or "−20 s", shown on that side while the run lasts.
    public var label: String {
        let seconds = abs(totalMs) / 1000
        return zone == .back ? "−\(seconds) s" : "+\(seconds) s"
    }
}

/// A double tap on the left or right seeks 10 s back or forward, and each
/// further tap on the same side within ``windowMs`` of the last adds another
/// 10 s without waiting for a second tap. A double tap in the middle toggles
/// the controls like a tap.
public struct DoubleTapSeek: Hashable, Sendable {
    public static let stepMs = 10_000
    public static let windowMs = 700

    public private(set) var burst: SeekBurst?

    public init() {}

    /// A single tap. Returns the seek to apply when it continues a burst on
    /// the same side, or nil when it is an ordinary tap (the caller then
    /// waits to see whether a second tap follows).
    public mutating func onTap(x: Double, width: Double, nowMs: Int) -> Int? {
        guard var b = burst, b.zone == seekZone(x: x, width: width), nowMs - b.lastTapMs <= Self.windowMs else {
            burst = nil
            return nil
        }
        b.steps += 1
        b.lastTapMs = nowMs
        burst = b
        return b.stepMs
    }

    /// A double tap. Returns the seek to apply, or nil in the middle (toggle the controls instead).
    public mutating func onDoubleTap(x: Double, width: Double, nowMs: Int) -> Int? {
        let zone = seekZone(x: x, width: width)
        guard zone != .none else {
            burst = nil
            return nil
        }
        let b = SeekBurst(zone: zone, steps: 1, lastTapMs: nowMs)
        burst = b
        return b.stepMs
    }

    /// True while ``burst`` still takes single taps at `nowMs`.
    public func isActive(nowMs: Int) -> Bool {
        burst.map { nowMs - $0.lastTapMs <= Self.windowMs } ?? false
    }

    public mutating func reset() { burst = nil }
}
