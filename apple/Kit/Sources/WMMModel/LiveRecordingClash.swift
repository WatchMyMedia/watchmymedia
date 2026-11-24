import Foundation

/// The day a repeat airs again, in "on again Friday": "today", "tomorrow",
/// the weekday within the week, else the date ("Nov 30").
func liveAgainDay(_ date: Date, now: Date, clock: LiveClock) -> String {
    let calendar = clock.calendar
    let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: now), to: calendar.startOfDay(for: date)).day ?? 0
    switch days {
    case ...0: return "today"
    case 1: return "tomorrow"
    case 2...6:
        var style = Date.FormatStyle.dateTime.weekday(.wide)
        style.timeZone = calendar.timeZone
        return date.formatted(style)
    default:
        var style = Date.FormatStyle.dateTime.month(.abbreviated).day()
        style.timeZone = calendar.timeZone
        return date.formatted(style)
    }
}

/// Record's answer when the tuners are booked while the programme would run:
/// what holds the tuners, and a choice of which one gives way. The
/// web draws it as a dialog; the iPhone and iPad show
/// it as a sheet and Apple TV full screen. Keep leaves the new one under
/// Won't record in Recordings.
public struct LiveRecordingClash: Hashable, Sendable, Identifiable {
    /// The recording that won't record.
    public var recording: LiveRecording
    public var id: String { recording.id }

    /// Nil unless `recording` is a clash.
    public init?(_ recording: LiveRecording) {
        guard recording.state == .conflict else { return nil }
        self.recording = recording
    }

    /// What holds the tuners, as the server planned it.
    public var holders: [LiveRecordingHolder] { recording.conflictsWith }

    /// The holders that can give way: not recording yet. Choosing one needs the
    /// right to change the new recording (the setter or an admin).
    public var choices: [LiveRecordingHolder] {
        recording.canCancel && recording.reason != "no_tuners" ? holders.filter { !$0.running } : []
    }

    /// "The tuners are busy at 9:00 PM", or why none is free for recordings.
    public func title(clock: LiveClock = .device) -> String {
        recording.reason == "no_tuners" ? recording.statusTitle : "The tuners are busy at \(clock.time(recording.start))"
    }
    public var title: String { title() }

    /// What to do about it, or why nothing can be done.
    public var message: String {
        if recording.reason == "no_tuners" {
            return "Every tuner for \(recording.number) is kept for watching live TV, or none is working, so nothing can record from it. An admin can change this in the server’s web app, under Settings › Live TV › Recordings."
        }
        let stays = kept
        if holders.isEmpty { return "Other recordings have the tuners while \(recording.title) is on. \(stays)" }
        if choices.isEmpty {
            let names = liveListed(holders.map(\.title))
            let running = holders.allSatisfy(\.running)
            return running
                ? "\(names) \(holders.count == 1 ? "is" : "are") recording already and can’t give way. \(stays)"
                : "Only whoever set \(recording.title), or an admin, can choose which one gives way. \(stays)"
        }
        return "To record \(recording.title) on \(recording.number), choose one that won’t record."
    }

    /// A holder's channel and time: "4.1 · 9:00–10:30 PM", then, where the
    /// server says (API level 34), "new episode" or "a repeat, on again
    /// Friday at 2:00 PM".
    public func line(_ holder: LiveRecordingHolder, clock: LiveClock = .device, now: Date = .now) -> String {
        ["\(holder.number) · \(clock.range(holder.start, holder.end))", airingNote(holder, clock: clock, now: now)].compactMap { $0 }.joined(separator: " · ")
    }

    /// "new episode", "a repeat, on again Friday at 2:00 PM" (or "a repeat"
    /// when the listings have it on no other time); nil when the server doesn't say.
    public func airingNote(_ holder: LiveRecordingHolder, clock: LiveClock = .device, now: Date = .now) -> String? {
        switch holder.isRepeat {
        case true?:
            guard let next = holder.nextAiring else { return "a repeat" }
            return "a repeat, on again \(liveAgainDay(next.start, now: now, clock: clock)) at \(clock.time(next.start))"
        case false?: return "new episode"
        case nil: return nil
        }
    }

    /// "Recording now" for one that can't give way, else "Set by Sam".
    public func status(_ holder: LiveRecordingHolder) -> String? {
        if holder.running { return "Recording now" }
        return holder.userName.isEmpty ? nil : "Set by \(holder.userName)"
    }

    /// After Keep: "Coastline Rescue stays under Won’t record in Recordings."
    public var kept: String { "\(recording.title) stays under Won’t record in Recordings." }

    /// A holder's choice on iPhone and iPad: "Record Coastline Rescue instead".
    public var recordInstead: String { "Record \(recording.title) instead" }

    /// A holder's choice on Apple TV, where it sits beside the holder: "Record this instead of Inspector Vane".
    public func recordInsteadOf(_ holder: LiveRecordingHolder) -> String { "Record this instead of \(holder.title)" }

    /// The way out that changes nothing: "Keep Inspector Vane and Conference Football", or OK.
    public var keep: String { choices.isEmpty ? "OK" : "Keep \(liveListed(holders.map(\.title)))" }

    /// A holder's choice, spoken: "Record Coastline Rescue instead of Inspector Vane".
    public func recordInsteadLabel(_ holder: LiveRecordingHolder) -> String {
        "Record \(recording.title) instead of \(holder.title)"
    }
}
