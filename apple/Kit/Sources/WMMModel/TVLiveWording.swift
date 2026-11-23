import Foundation

// Apple TV's Live TV words, built on LiveWording: the guide's lines for the
// focused programme and its sheet, the On now cards, and Recordings' rows.
// They answer what a viewer asks (what is on, how long is left, what is
// next) and leave out the tuner and the stream.

/// Under an On now card: "Until 9:00 PM".
public func liveUntil(_ programme: LiveProgramme, clock: LiveClock = .device) -> String {
    "Until \(clock.time(programme.end))"
}

/// When something still to come is on: "Tonight" (today from 5 PM),
/// "Today", "Tomorrow", or the weekday ("Wed").
public func liveDayWord(_ date: Date, now: Date, clock: LiveClock = .device) -> String {
    let day = clock.dayName(date, now: now)
    guard day == "Today" else { return day }
    return clock.calendar.component(.hour, from: date) >= 17 ? "Tonight" : "Today"
}

/// Above the focused programme in the guide: "On now · 4.1 NFLD",
/// "Tonight · 7.1 LKSD", "Ended · 2.1 HRBR"; the channel alone without a programme.
public func liveGuideWhen(_ programme: LiveProgramme?, channel: LiveChannel, now: Date, clock: LiveClock = .device) -> String {
    let name = "\(channel.number) \(channel.name)"
    guard let programme else { return name }
    if programme.isOn(at: now) { return "On now · \(name)" }
    if programme.hasEnded(at: now) { return "Ended · \(name)" }
    return "\(liveDayWord(programme.start, now: now, clock: clock)) · \(name)"
}

/// After the times in the guide's lines: what follows while it is on
/// ("Next: Tide Tables"), or for one still to come "New episode" or "Repeat".
public func liveGuideAfter(_ programme: LiveProgramme, next: LiveProgramme?, now: Date) -> String? {
    if programme.isOn(at: now) { return next.map { "Next: \($0.title)" } }
    if programme.hasEnded(at: now) { return nil }
    return programme.isNew ? "New episode" : programme.isRepeat ? "Repeat" : nil
}

/// A programme's sheet, first line: "Tonight · 9:00–10:00 PM · 7.1 LKSD".
public func liveSheetWhen(_ programme: LiveProgramme, channel: LiveChannel, now: Date, clock: LiveClock = .device) -> String {
    let day = programme.isOn(at: now) ? "On now" : liveDayWord(programme.start, now: now, clock: clock)
    return "\(day) · \(clock.range(programme.start, programme.end)) · \(channel.number) \(channel.name)"
}

/// A programme's sheet, under its title: "S2 E5 · Slack Water · New episode"; nil when there is nothing to say.
public func liveSheetLine(_ programme: LiveProgramme) -> String? {
    let parts = [programme.episodeLine, programme.isNew ? "New episode" : programme.isRepeat ? "Repeat" : nil].compactMap { $0 }
    return parts.isEmpty ? nil : parts.joined(separator: " · ")
}

/// The sheet's reminder row: "Remind me when it starts", "Remind me 5 min before".
public func liveRemindMeRow(leadMinutes: Int) -> String { "Remind me \(liveLeadBefore(leadMinutes))" }

/// The sheet's last row: "Watch 7.1 LKSD now".
public func liveWatchNowRow(_ channel: LiveChannel) -> String { "Watch \(channel.number) \(channel.name) now" }

/// Beside it, what that channel is showing: "Lake Report is on".
public func liveIsOn(_ programme: LiveProgramme) -> String { "\(programme.title) is on" }

/// The sheet's rows for recording a programme of a series: just this one, or every new one.
public let liveRecordThisEpisode = "Record this episode"
public let liveRecordTheRestOfThisEpisode = "Record the rest of this episode"
public let liveRecordEveryNewEpisodeRow = "Record every new episode"
public let liveRecordWithOptionsRow = "Record with options…"

// MARK: Recordings

/// The clash's message on Apple TV, when one can be left out: "Tonight at
/// 9:00 PM these overlap. Choose the one to leave out."
public func liveClashChoose(_ clash: LiveRecordingClash, now: Date, clock: LiveClock = .device) -> String {
    "\(liveDayWord(clash.recording.start, now: now, clock: clock)) at \(clock.time(clash.recording.start)) these overlap. Choose the one to leave out."
}
