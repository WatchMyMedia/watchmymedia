import Foundation

// When something would end, in the device's clock: what a viewer asks
// before pressing Play or Resume. Worded with `LiveClock`, so a time reads
// as the guide's do ("9:41 PM", or "21:41" on a 24-hour clock).

/// "Ends at 9:41 PM": the end of something with `remainingMs` left, started at `now`.
public func endsAtLine(remainingMs: Int, now: Date, clock: LiveClock = .device) -> String {
    "Ends at \(clock.time(endTime(remainingMs: remainingMs, now: now)))"
}

/// "Start now and it ends at 10:20 PM": a film or episode not begun, `durationMs` long.
public func startNowEndsLine(durationMs: Int, now: Date, clock: LiveClock = .device) -> String {
    "Start now and it ends at \(clock.time(endTime(remainingMs: durationMs, now: now)))"
}

/// `now` plus what is left, to the minute: a time a clock on the wall will show.
func endTime(remainingMs: Int, now: Date) -> Date {
    let end = now.addingTimeInterval(Double(max(remainingMs, 0)) / 1000)
    return Date(timeIntervalSinceReferenceDate: (end.timeIntervalSinceReferenceDate / 60).rounded() * 60)
}
