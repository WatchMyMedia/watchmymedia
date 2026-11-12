import Foundation
import WMMModel

// Where things sit on the player's scrubber, as fractions of the track's
// width, so the view only multiplies (android/mobile/.../ScrubberModel.kt).

/// How a band is drawn: gold for the parts before the episode (intro, recap), light for the rest (credits…).
public enum BandStyle: Hashable, Sendable { case opening, closing }

/// A segment as a band on the scrubber, in fractions of the track's width.
public struct ScrubberBand: Hashable, Sendable {
    public var kind: SegmentKind
    public var start: Double
    public var end: Double
    public var style: BandStyle

    public init(kind: SegmentKind, start: Double, end: Double, style: BandStyle) {
        self.kind = kind
        self.start = start
        self.end = end
        self.style = style
    }

    /// "Intro from 1:00 to 1:45", "Credits from 49:30", for VoiceOver.
    public func accessibilityLabel(durationMs: Int) -> String {
        let from = formatClock(ms: Int((start * Double(durationMs)).rounded()))
        if end >= 1 { return "\(kind.settingsLabel) from \(from)" }
        return "\(kind.settingsLabel) from \(from) to \(formatClock(ms: Int((end * Double(durationMs)).rounded())))"
    }
}

/// The faint bands drawn over the track, one per segment that lies at least
/// partly inside the file. A segment without an end runs to the end of the
/// file; none before the duration is known.
public func scrubberBands(_ segments: [Segment], durationMs: Int) -> [ScrubberBand] {
    guard durationMs > 0 else { return [] }
    return segments.compactMap { s in
        let start = fraction(ofMs: s.startMs, durationMs: durationMs)
        let end = fraction(ofMs: s.endMs ?? durationMs, durationMs: durationMs)
        guard end > start else { return nil }
        let style: BandStyle = s.kind == .intro || s.kind == .recap ? .opening : .closing
        return ScrubberBand(kind: s.kind, start: start, end: end, style: style)
    }
}

/// `ms` as a fraction of `durationMs`, 0–1; 0 while the duration is unknown.
public func fraction(ofMs ms: Int, durationMs: Int) -> Double {
    guard durationMs > 0 else { return 0 }
    return min(max(Double(ms) / Double(durationMs), 0), 1)
}

/// How far the buffer reaches, as a fraction: `bufferedMs` is ahead of `positionMs`.
public func bufferedFraction(positionMs: Int, bufferedMs: Int, durationMs: Int) -> Double {
    fraction(ofMs: positionMs + max(bufferedMs, 0), durationMs: durationMs)
}

/// The file position under `x` on a track `width` wide, clamped to the file.
public func position(atX x: Double, width: Double, durationMs: Int) -> Int {
    guard width > 0, durationMs > 0 else { return 0 }
    return Int((min(max(x / width, 0), 1) * Double(durationMs)).rounded())
}

/// "−51:22": the time left, with a real minus sign.
public func remainingLabel(positionMs: Int, durationMs: Int) -> String {
    "−" + formatClock(ms: max(durationMs - positionMs, 0))
}

/// What VoiceOver reads for the scrubber: "0:48 of 52:10".
public func scrubberAccessibilityValue(positionMs: Int, durationMs: Int) -> String {
    "\(formatClock(ms: positionMs)) of \(formatClock(ms: durationMs))"
}
