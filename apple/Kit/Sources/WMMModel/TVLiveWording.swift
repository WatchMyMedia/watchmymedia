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

/// "38 hours recorded · room for about 70 more": how much is recorded and,
/// at the rate the recordings so far took, how many more hours the disk
/// holds. Nil when nothing has been recorded.
public func liveRecordedHoursLine(_ recorded: [LiveRecording], freeBytes: Int64?) -> String? {
    let finished = recorded.filter { [.done, .partial].contains($0.state) }
    let seconds = finished.reduce(0.0) { $0 + max($1.end.timeIntervalSince($1.start), 0) }
    guard seconds > 0 else { return nil }
    let hours = seconds / 3600
    let done = hours < 1 ? "\(max(Int((seconds / 60).rounded()), 1)) min recorded" : "\(plural(Int(hours.rounded()), "hour")) recorded"
    let bytes = finished.reduce(Int64(0)) { $0 + max($1.bytes, 0) }
    guard let freeBytes, freeBytes > 0, bytes > 0 else { return done }
    let room = Double(freeBytes) / (Double(bytes) / seconds) / 3600
    let more = room < 1 ? "room for less than an hour more" : "room for about \(Int(room.rounded())) more"
    return "\(done) · \(more)"
}

/// A recording's length: "1 h", "52 min".
public func liveRecordingLength(_ recording: LiveRecording) -> String {
    formatDuration(ms: Int(max(recording.end.timeIntervalSince(recording.start), 60) * 1000))
}

/// A Ready to watch card's line: "Until 9:30 PM · watch from the start"
/// while it records, "S1 E3 · 1 h" for one recording, "3 episodes" for a
/// title recorded several times.
public func liveReadyLine(_ recordings: [LiveRecording], clock: LiveClock = .device) -> String {
    guard let first = recordings.first else { return "" }
    if recordings.count > 1 { return plural(recordings.count, "episode") }
    if first.state == .recording { return "Until \(clock.time(first.end)) · watch from the start" }
    return [first.episodeLine, liveRecordingLength(first)].compactMap { $0 }.joined(separator: " · ")
}

/// A Coming up card's tag: "Tonight 9:00 PM", "Tomorrow 8:00 PM", or "Won’t record".
public func liveComingTag(_ recording: LiveRecording, now: Date, clock: LiveClock = .device) -> String {
    if recording.state == .conflict { return "Won’t record" }
    return "\(liveDayWord(recording.start, now: now, clock: clock)) \(clock.time(recording.start))"
}

/// A Coming up card's line: "2.1 HRBR · every new episode", "7.1 LKSD ·
/// this episode", or for one that won't record "Tonight 9:00 PM · no tuner free".
public func liveComingLine(_ recording: LiveRecording, rule: LiveRecordingRule?, now: Date, clock: LiveClock = .device) -> String {
    if recording.state == .conflict {
        return "\(liveDayWord(recording.start, now: now, clock: clock)) \(clock.time(recording.start)) · no tuner free"
    }
    let channel = [recording.number, recording.channelName].filter { !$0.isEmpty }.joined(separator: " ")
    let which = recording.ruleID == nil && !recording.series ? "this episode" : (rule?.newOnly ?? true) ? "every new episode" : "every episode"
    return [channel, which].filter { !$0.isEmpty }.joined(separator: " · ")
}

/// The clash's message on Apple TV, when one can be left out: "Tonight at
/// 9:00 PM these overlap. Choose the one to leave out."
public func liveClashChoose(_ clash: LiveRecordingClash, now: Date, clock: LiveClock = .device) -> String {
    "\(liveDayWord(clash.recording.start, now: now, clock: clock)) at \(clock.time(clash.recording.start)) these overlap. Choose the one to leave out."
}
