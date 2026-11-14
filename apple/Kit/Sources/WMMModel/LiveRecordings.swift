import Foundation

public enum LiveRecordingSection: String, CaseIterable, Hashable, Sendable {
    case now, conflicts, upcoming, series, recorded
    public var title: String {
        switch self {
        case .now: "Recording now"
        case .conflicts: "Won’t record"
        case .upcoming: "Upcoming"
        case .series: "Series"
        case .recorded: "Recorded"
        }
    }
}

public enum LiveRecordingState: String, CaseIterable, Hashable, Sendable {
    case scheduled, conflict, skipped, recording, done, partial, failed, cancelled, gone, deleted, unknown
    /// The words without a reason; ``liveRecordingWords(state:reason:series:holders:)`` adds it.
    public var title: String {
        switch self {
        case .scheduled: "Will record"
        case .conflict: "Won’t record: the tuners are busy"
        case .skipped: "Won’t record"
        case .recording: "Recording now"
        case .done: "Recorded"
        case .partial: "Partly recorded"
        case .failed: "Didn’t record"
        case .cancelled: "Cancelled"
        case .gone: "No longer in the listings"
        case .deleted: "Deleted"
        case .unknown: "Recording status unavailable"
        }
    }
    public var mayCancel: Bool { [.scheduled, .conflict, .recording, .gone].contains(self) }
    /// The guide marks only these: a dot (ringed for a series), REC, or a hollow dot.
    /// They are also the states in which Record isn't offered again.
    public var marked: Bool { [.scheduled, .recording, .conflict].contains(self) }
}

/// A reason code's words, as Android and the web word them. Nil for a code
/// this app doesn't know, which callers word as "Didn’t record" or "Missing part".
/// A finished recording's (failed, partial or done, and its missing parts) are
/// in the past: "The tuners were busy", "Not enough free space".
public func liveRecordingReason(_ code: String?, finished: Bool = false) -> String? {
    switch code {
    case "tuners_busy" where finished: "The tuners were busy"
    case "disk_full" where finished: "Not enough free space"
    case "already_recorded": "Already recorded"
    case "already_scheduled": "Already set to record"
    case "in_library": "Already in your library"
    case "not_new": "Not a new episode"
    case "rule_filtered": "Left out by the series options"
    case "listing_gone": "No longer in the listings"
    case "tuners_busy": "Won’t record: the tuners are busy"
    case "no_tuners": "Won’t record: no tuner is free for recordings"
    case "missed": "Missed: the server wasn’t running"
    case "disk_full": "Stopped: the disk is nearly full"
    case "folder_unavailable": "The recordings folder isn’t available"
    case "output_changed": "Something else changed the recording’s file"
    case "output_missing": "The recording’s file is missing"
    case "server_restarted": "The server restarted during it"
    case "signal_lost": "The signal dropped"
    case "stopped": "Stopped early"
    case "stop_requested": "Stopping…"
    case "finish_pending": "Finishing…"
    case "started_late": "Started late"
    default: nil
    }
}

/// "A", "A and B", "A, B and C".
public func liveListed(_ items: [String]) -> String {
    guard let last = items.last else { return "" }
    return items.count == 1 ? last : items.dropLast().joined(separator: ", ") + " and " + last
}

/// A recording's or a mark's words: "Will record", "Will record · Every new
/// episode", "Recording now", "Won’t record: Inspector Vane and Conference
/// Football have the tuners", "Already in your library", "Not enough free space".
/// A code this app doesn't know never shows: a skipped one says "Won’t record",
/// a failed one "Didn’t record".
public func liveRecordingWords(state: LiveRecordingState, reason: String?, series: Bool = false, holders: [String] = []) -> String {
    switch state {
    case .scheduled: return series ? "Will record · Every new episode" : "Will record"
    case .conflict:
        if reason == "no_tuners", let words = liveRecordingReason(reason) { return words }
        if holders.isEmpty { return state.title }
        return holders.count == 1 ? "Won’t record: \(holders[0]) has the tuner" : "Won’t record: \(liveListed(holders)) have the tuners"
    case .skipped: return liveRecordingReason(reason) ?? state.title
    case .failed: return liveRecordingReason(reason, finished: true) ?? state.title
    default: return state.title
    }
}

/// A missing part of a recording: "Missing 9:12–9:14 PM: the signal dropped",
/// or "Missing part · 9:12–9:14 PM" for a reason this app doesn't know.
public func liveRecordingGapLine(_ gap: LiveRecordingGap, clock: LiveClock = .device) -> String {
    let range = clock.range(gap.start, gap.end)
    guard let words = liveRecordingReason(gap.reason, finished: true) else { return "Missing part · \(range)" }
    return "Missing \(range): \(words.prefix(1).lowercased() + words.dropFirst())"
}

public struct LiveRecordingMark: Hashable, Sendable {
    public var id: String
    public var state: LiveRecordingState
    public var series: Bool
    public var canCancel: Bool
    /// Why it won't record or didn't, a code worded by ``liveRecordingReason(_:finished:)``.
    public var reason: String?
    public init(id: String, state: LiveRecordingState, series: Bool = false, canCancel: Bool = false, reason: String? = nil) {
        self.id = id; self.state = state; self.series = series; self.canCancel = canCancel; self.reason = reason
    }
    /// "Will record", "Recording now", "Won’t record: the tuners are busy", "Already recorded".
    public var title: String { liveRecordingWords(state: state, reason: reason, series: series) }
    /// Beside Record, for a programme with no mark that has something to say:
    /// "Already recorded", "Not a new episode", "No longer in the listings".
    /// Nil once cancelled or deleted, and for a mark the guide draws.
    public var note: String? {
        [.skipped, .gone, .failed, .partial, .done].contains(state) ? title : nil
    }
    /// The end of a guide cell's accessible name: "will record", "recording now",
    /// "won’t record: the tuners are busy"; nil when the cell has no mark.
    public var accessibilityLabel: String? {
        guard state.marked else { return nil }
        let words = title
        return words.prefix(1).lowercased() + words.dropFirst()
    }
}

/// A recording holding the tuners while a clashing one would run.
public struct LiveRecordingHolder: Hashable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var number: String
    public var channelName: String
    public var start: Date
    public var end: Date
    public var userName: String
    /// Already recording, so it can't give way.
    public var running: Bool
    public init(id: String, title: String, number: String, channelName: String = "", start: Date, end: Date, userName: String = "", running: Bool = false) {
        self.id = id; self.title = title; self.number = number; self.channelName = channelName
        self.start = start; self.end = end; self.userName = userName; self.running = running
    }
}

public struct LiveRecordingGap: Hashable, Sendable {
    public var start: Date
    public var end: Date
    public var reason: String
    public init(start: Date, end: Date, reason: String) { self.start = start; self.end = end; self.reason = reason }
}

public struct LiveRecording: Hashable, Sendable, Identifiable {
    public var id: String
    public var ruleID: String?
    public var userID: Int?
    public var userName: String
    public var channelID: String
    public var sourceID: String
    public var number: String
    public var channelName: String
    public var programmeID: String?
    public var title: String
    public var episodeTitle: String?
    public var season: Int?
    public var episode: Int?
    public var start: Date
    public var end: Date
    public var startedAt: Date?
    public var endedAt: Date?
    public var state: LiveRecordingState
    public var reason: String?
    public var series: Bool
    /// Minutes, matching the server and every client's Options.
    public var padBefore: Int
    public var padAfter: Int
    public var bytes: Int64
    public var gaps: [LiveRecordingGap]
    public var mediaFileID: Int?
    public var canCancel: Bool
    public var canDelete: Bool
    public var pictureURL: URL?
    /// A clashing recording's holders of the tuners while it would run (API level 19).
    public var conflictsWith: [LiveRecordingHolder]
    public init(id: String, ruleID: String? = nil, userID: Int? = nil, userName: String = "", channelID: String,
                sourceID: String = "", number: String, channelName: String = "", programmeID: String? = nil,
                title: String, episodeTitle: String? = nil, season: Int? = nil, episode: Int? = nil, start: Date, end: Date,
                startedAt: Date? = nil, endedAt: Date? = nil, state: LiveRecordingState = .scheduled, reason: String? = nil,
                series: Bool = false, padBefore: Int = 1, padAfter: Int = 2, bytes: Int64 = 0, gaps: [LiveRecordingGap] = [],
                mediaFileID: Int? = nil, canCancel: Bool = false, canDelete: Bool = false, pictureURL: URL? = nil,
                conflictsWith: [LiveRecordingHolder] = []) {
        self.id = id; self.ruleID = ruleID; self.userID = userID; self.userName = userName
        self.channelID = channelID; self.sourceID = sourceID; self.number = number; self.channelName = channelName
        self.programmeID = programmeID; self.title = title; self.episodeTitle = episodeTitle; self.season = season; self.episode = episode
        self.start = start; self.end = end; self.startedAt = startedAt; self.endedAt = endedAt; self.state = state; self.reason = reason
        self.series = series; self.padBefore = padBefore; self.padAfter = padAfter; self.bytes = bytes; self.gaps = gaps
        self.mediaFileID = mediaFileID; self.canCancel = canCancel; self.canDelete = canDelete; self.pictureURL = pictureURL
        self.conflictsWith = conflictsWith
    }
    public var mark: LiveRecordingMark { .init(id: id, state: state, series: series, canCancel: canCancel, reason: reason) }
    /// The row's words: "Will record", "Won’t record: Inspector Vane and Conference Football have the tuners", "Not enough free space".
    public var statusTitle: String {
        liveRecordingWords(state: state, reason: reason, series: series, holders: state == .conflict ? conflictsWith.map(\.title) : [])
    }
    public var episodeLine: String? {
        LiveProgramme(id: programmeID ?? id, start: start, end: end, title: title, episodeTitle: episodeTitle,
                      season: season, episode: episode).episodeLine
    }
    /// What else to say under ``statusTitle``: "Stopping…" or "Finishing…" while
    /// recording, why a partial one stopped, and its missing parts. A reason
    /// already in the title, or one this app doesn't know, adds nothing.
    public var reasonMessage: String? {
        var lines: [String] = []
        if [.recording, .partial, .done].contains(state), let words = liveRecordingReason(reason, finished: state != .recording) { lines.append(words) }
        if [.partial, .done].contains(state) { lines += gaps.map { liveRecordingGapLine($0) } }
        return lines.isEmpty ? nil : lines.joined(separator: "\n")
    }
    public var playable: Bool { state == .recording || ([.done, .partial].contains(state) && mediaFileID != nil) }
    public func playRequest(fromStart: Bool = false) -> PlayRequest {
        PlayRequest(fileID: mediaFileID ?? 0, startMs: fromStart ? 0 : nil, title: title, subtitle: episodeLine,
                    recordingID: state == .recording || mediaFileID == nil ? id : nil)
    }
    public func progress(at now: Date) -> Double {
        let from = startedAt ?? start, to = end.addingTimeInterval(Double(padAfter) * 60)
        guard to > from else { return 0 }
        return min(max(now.timeIntervalSince(from) / to.timeIntervalSince(from), 0), 1)
    }
}

public struct LiveRecordingRule: Hashable, Sendable, Identifiable {
    public var id: String
    public var kind: String
    public var title: String
    public var userName: String
    public var channelID: String?
    public var number: String?
    public var seriesKey: String?
    public var newOnly: Bool
    public var anyChannel: Bool
    public var keep: Int
    public var padBefore: Int
    public var padAfter: Int
    public var repeatKind: String?
    public var zone: String?
    public var canCancel: Bool
    public init(id: String, kind: String = "series", title: String, userName: String = "", channelID: String? = nil,
                number: String? = nil, seriesKey: String? = nil, newOnly: Bool = true, anyChannel: Bool = false,
                keep: Int = 0, padBefore: Int = 1, padAfter: Int = 2, repeatKind: String? = nil, zone: String? = nil,
                canCancel: Bool = false) {
        self.id = id; self.kind = kind; self.title = title; self.userName = userName; self.channelID = channelID
        self.number = number; self.seriesKey = seriesKey; self.newOnly = newOnly; self.anyChannel = anyChannel
        self.keep = keep; self.padBefore = padBefore; self.padAfter = padAfter; self.repeatKind = repeatKind
        self.zone = zone; self.canCancel = canCancel
    }
    public var detail: String {
        if kind == "manual" { return [repeatKind?.capitalized, number, zone].compactMap(\.self).joined(separator: " · ") }
        return [newOnly ? "New episodes only" : "Every episode", anyChannel ? "Any channel" : number,
                keep == 0 ? "Keep all" : "Keep the latest \(keep)"].compactMap(\.self).joined(separator: " · ")
    }
}

public struct LiveRecordingPage: Hashable, Sendable {
    public var items: [LiveRecording]
    public var rules: [LiveRecordingRule]
    public var nextCursor: String?
    public var tuners: Int?
    public var freeBytes: Int64?
    public init(items: [LiveRecording] = [], rules: [LiveRecordingRule] = [], nextCursor: String? = nil, tuners: Int? = nil, freeBytes: Int64? = nil) {
        self.items = items; self.rules = rules; self.nextCursor = nextCursor; self.tuners = tuners; self.freeBytes = freeBytes
    }
}

public struct LiveRecordingOptions: Hashable, Sendable {
    public static let paddingChoices = [0, 1, 2, 5, 15, 30, 60]
    public var useServerPadding: Bool
    public var series: Bool
    public var newOnly: Bool
    public var anyChannel: Bool
    public var keep: Int
    public var padBefore: Int
    public var padAfter: Int
    public init(series: Bool = false, newOnly: Bool = true, anyChannel: Bool = false, keep: Int = 0, padBefore: Int = 1, padAfter: Int = 2, useServerPadding: Bool = true) {
        self.useServerPadding = useServerPadding
        self.series = series; self.newOnly = newOnly; self.anyChannel = anyChannel; self.keep = keep
        self.padBefore = padBefore; self.padAfter = padAfter
    }
}

public enum LiveManualRepeat: String, CaseIterable, Hashable, Sendable {
    case once, daily, weekdays, weekly
    public var title: String {
        switch self { case .once: "Once"; case .daily: "Every day"; case .weekdays: "Weekdays"; case .weekly: "Every week" }
    }
}
public struct LiveManualRecording: Hashable, Sendable {
    public var channelID: String
    public var start: Date
    public var end: Date
    public var repeatKind: LiveManualRepeat
    public var zone: String
    public var name: String
    public init(channelID: String = "", start: Date = .now.addingTimeInterval(300), end: Date = .now.addingTimeInterval(3900),
                repeatKind: LiveManualRepeat = .once, zone: String = TimeZone.current.identifier, name: String = "") {
        self.channelID = channelID; self.start = start; self.end = end; self.repeatKind = repeatKind; self.zone = zone; self.name = name
    }
    public var validationMessage: String? {
        if channelID.isEmpty { return "Choose a channel." }
        if name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "Name this recording." }
        if name.count > 200 { return "Use a name of 200 characters or fewer." }
        if end <= start { return "The end must be after the start." }
        if end.timeIntervalSince(start) > 12 * 3600 { return "A recording can last up to 12 hours." }
        if TimeZone(identifier: zone) == nil { return "Choose a valid time zone." }
        return nil
    }
}

public struct GrowingRecording: Hashable, Sendable {
    public var startedAt: Date
    public var endsAt: Date
    public init(startedAt: Date, endsAt: Date) { self.startedAt = startedAt; self.endsAt = endsAt }
    public var plannedDurationMs: Int { max(Int(endsAt.timeIntervalSince(startedAt) * 1000), 0) }
    public func durationMs(at now: Date) -> Int { max(Int(min(now, endsAt).timeIntervalSince(startedAt) * 1000), 0) }
    public func seekLimitMs(at now: Date) -> Int { max(durationMs(at: now) - 3000, 0) }
}

extension LiveTVCapabilities {
    public var recordingsAvailable: Bool { available && recording && canRecord }
}

extension LiveTVRepository {
    public func recordings(section: LiveRecordingSection, cursor: String?) async throws -> LiveRecordingPage { throw LiveTVError(reason: "unavailable") }
    public func record(programmeID: String, options: LiveRecordingOptions) async throws -> LiveRecording { throw LiveTVError(reason: "unavailable") }
    public func recordManual(_ request: LiveManualRecording) async throws -> LiveRecording { throw LiveTVError(reason: "unavailable") }
    public func editRecording(id: String, padBefore: Int?, padAfter: Int?, prefer: Bool) async throws -> LiveRecording { throw LiveTVError(reason: "unavailable") }
    public func preferRecording(id: String, over: String?) async throws -> LiveRecording { throw LiveTVError(reason: "unavailable") }
    public func cancelRecording(id: String) async throws { throw LiveTVError(reason: "unavailable") }
    public func editRecordingRule(id: String, options: LiveRecordingOptions) async throws -> LiveRecordingRule { throw LiveTVError(reason: "unavailable") }
    public func deleteRecordingFile(id: String, recordAgain: Bool) async throws { throw LiveTVError(reason: "unavailable") }
    public func removeRecordingRule(id: String) async throws { throw LiveTVError(reason: "unavailable") }
}

/// Carried with a library version, so recorded media keeps ordinary playback,
/// downloads and watched state while showing its broadcast origin.
public struct RecordingProvenance: Hashable, Sendable {
    public var channel: String
    public var number: String
    public var startedAt: Date
    public var gaps: [LiveRecordingGap]
    public var ruleID: String?
    public var adBreaks: String
    public init(channel: String, number: String, startedAt: Date, gaps: [LiveRecordingGap] = [], ruleID: String? = nil, adBreaks: String = "none") {
        self.channel = channel; self.number = number; self.startedAt = startedAt; self.gaps = gaps; self.ruleID = ruleID; self.adBreaks = adBreaks
    }
    public func line(clock: LiveClock = .device) -> String { "Recorded from \(number) on \(clock.weekdayName(startedAt)) \(clock.time(startedAt))" }
}
