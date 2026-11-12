import Foundation
import WMMModel

// The player's wording, worded as android/core/model/.../PlayerLabels.kt words
// it and built from WMMModel's helpers, so the views only lay it out.

/// The tabs of the audio, subtitles and quality picker.
public enum PickerTab: String, CaseIterable, Hashable, Sendable {
    case audio, subtitles, quality

    public var title: String {
        switch self {
        case .audio: "Audio"
        case .subtitles: "Subtitles"
        case .quality: "Quality"
        }
    }
}

/// One row of the picker: the name and a muted detail.
public struct OptionLabel: Hashable, Sendable {
    public var label: String
    public var detail: String?

    public init(_ label: String, detail: String? = nil) {
        self.label = label
        self.detail = detail.flatMap { $0.isEmpty ? nil : $0 }
    }
}

/// "English · EAC3 5.1 (Default)" → English, with "EAC3 5.1 (Default)" as the detail.
public func audioOptionLabel(_ option: AudioOption) -> OptionLabel {
    let parts = option.label.components(separatedBy: " · ")
    return OptionLabel(parts[0], detail: parts.count > 1 ? parts.dropFirst().joined(separator: " · ") : nil)
}

/// "Original", or "1080p" with "8 Mbps" as the detail.
public func qualityOptionLabel(_ quality: StreamQuality) -> OptionLabel {
    OptionLabel(quality.shortLabel, detail: quality.detail)
}

/// A quality row in the picker; the one chosen also says what the server
/// sends now: "Original" with "now remux · 4K HDR10" (PlayerPicker.dc.html),
/// and Automatic the step it is at: "Now 720p · 4 Mbps".
public func qualityOptionLabel(_ quality: StreamQuality, in state: PlayerState) -> OptionLabel {
    let row = qualityOptionLabel(quality)
    if quality == .auto { return OptionLabel(row.label, detail: state.quality == .auto ? state.autoStep.map(autoNow) : nil) }
    guard quality == state.quality, let line = streamLine(state) else { return row }
    let now = "now " + line.prefix(1).lowercased() + line.dropFirst()
    return OptionLabel(row.label, detail: [row.detail, now].compactMap { $0 }.joined(separator: " · "))
}

/// The step Automatic is at: "Now 720p · 4 Mbps", "Now original quality".
public func autoNow(_ step: StreamQuality) -> String {
    step == .original || step == .auto ? "Now original quality" : "Now \(step.label)"
}

/// The chip by the stream line at Automatic: "Auto · 720p", "Auto ·
/// Original", or "Auto" until the server says; nil at a fixed quality.
public func autoChip(_ state: PlayerState) -> String? {
    guard !state.isOffline, state.quality == .auto else { return nil }
    return state.autoStep.map { "Auto · \($0.shortLabel)" } ?? "Auto"
}

/// A line of the Subtitles list.
public enum SubtitleEntry: Hashable, Sendable {
    /// A group's name: "OpenSubtitles", "In this file".
    case heading(String)
    /// A track to turn on (nil id: Off).
    case track(id: String?, label: OptionLabel, selected: Bool)
    /// An online result: choosing it downloads it, then turns it on.
    case online(id: String, label: OptionLabel, enabled: Bool, downloading: Bool)
    /// A muted line: the group's note and the credit OpenSubtitles asks for.
    case note(String)
}

/// Off; then the file's own subtitles under "In this file"; then
/// OpenSubtitles (downloaded for this file, what the search found, the note
/// and the credit). Without online subtitles the list has no group names.
public func subtitleEntries(_ state: PlayerState) -> [SubtitleEntry] {
    let off = SubtitleEntry.track(id: nil, label: OptionLabel("Off"), selected: state.selectedSubtitle == nil)
    func track(_ o: SubtitleOption) -> SubtitleEntry {
        .track(id: o.id, label: OptionLabel(o.label, detail: o.detail), selected: o.id == state.selectedSubtitle)
    }
    let downloaded = state.subtitles.filter { $0.source == .online }
    let own = state.subtitles.filter { $0.source != .online }
    guard state.online != nil || !downloaded.isEmpty else { return [off] + own.map(track) }
    var entries = [off]
    if !own.isEmpty { entries.append(.heading("In this file")) }
    entries += own.map(track)
    entries.append(.heading("OpenSubtitles"))
    entries += downloaded.map(track)
    if let online = state.online {
        entries += online.offered(excluding: state.subtitles).map { r in
            let downloading = online.downloading == r.id
            return .online(id: r.id, label: OptionLabel(r.label, detail: r.detail(downloading: downloading)), enabled: online.canDownloadNow, downloading: downloading)
        }
        if let note = online.note() { entries.append(.note(note)) }
    }
    entries.append(.note(onlineCredit(state.online)))
    return entries
}

/// "Subtitles provided by OpenSubtitles.com · 17 downloads left today".
public func onlineCredit(_ online: OnlineSubtitles?) -> String {
    let credit = online?.attribution ?? openSubtitlesCredit
    guard let remaining = online?.remaining, remaining > 0, online?.canDownload == true else { return credit }
    return "\(credit) · \(plural(remaining, "download")) left today"
}

/// "4K HDR10", "1080p", "4K Dolby Vision".
public func videoLabel(_ video: StreamVideo) -> String {
    [video.quality, hdrLabel(video.hdr)].compactMap { $0 }.joined(separator: " ")
}

/// How the server is sending the file: "Remux · 4K HDR10" once it says what
/// it sends; before that "Direct play", or "Transcode · 720p" under a quality
/// cap or Automatic's step; nil until it answers.
public func streamLine(_ state: PlayerState) -> String? {
    if state.isOffline { return state.video.map { "Downloaded · \(videoLabel($0))" } ?? "Downloaded" }
    guard let mode = state.mode else { return nil }
    if let video = state.video { return "\(mode.label) · \(videoLabel(video))" }
    let step = state.quality == .auto ? state.autoStep ?? .original : state.quality
    if step == .original { return mode.label }
    return "\(mode.label) · \(step.shortLabel)"
}

/// Why the server is not sending the original as is: "Remux: client cannot play the mkv container."
public func streamReason(_ state: PlayerState) -> String? {
    guard !state.isOffline, let mode = state.mode, mode != .direct, !state.reasons.isEmpty else { return nil }
    return "\(mode.label): \(state.reasons.joined(separator: "; "))."
}

/// Over the video while the Apple TV's transport bar shows (TvPlayerControls.dc.html).
public let infoPanelHint = "Swipe down for info, audio and subtitles"

/// The line along the bottom of the Apple TV's info panel (TvPlayerPicker.dc.html).
public let infoPanelFootnote = "Swipe up or press Back to close. Changes apply at once; a burned-in subtitle restarts the stream from here. Subtitle size and style follow the Apple TV’s accessibility settings."

/// Under the quality steps on Apple TV: "Remembered on this Apple TV".
public func qualityRememberedNote(device: String) -> String { "Remembered on this \(device)" }

/// Under the Apple TV's Still watching buttons.
public let stillWatchingRemoteNote = "Any press on the remote counts as an answer."

/// The line along the bottom of the picker; `device` is "iPhone" or "iPad".
public func pickerFootnote(device: String, offline: Bool = false) -> String {
    if offline { return "Only tracks in this download are available · changes apply at once" }
    return "Changes apply at once · a burned-in subtitle restarts the stream from here · quality is remembered on this \(device)"
}

/// The title along the top: "Harbor Lights · S03 E07 The Long Dark", with
/// " · paused" while the picker holds the video.
public func playerTitleLine(title: String, subtitle: String?, paused: Bool = false) -> String {
    var parts: [String] = title.isEmpty ? [] : [title]
    if let subtitle, !subtitle.isEmpty {
        // "S03 E07 · The Long Dark" reads as one name after the show's.
        if let code = subtitle.range(of: #"^S\d+ E\d+ · "#, options: .regularExpression) {
            parts.append(String(subtitle[code].dropLast(3)) + " " + subtitle[code.upperBound...])
        } else {
            parts.append(subtitle)
        }
    }
    if paused { parts.append("paused") }
    return parts.joined(separator: " · ")
}

extension UpNext {
    /// "S03 E08 · Ebb", the card's heading.
    public var cardTitle: String { "\(episodeCode(season: season, episode: episode)) · \(title)" }

    /// "Harbor Lights · season finale", the card's second line.
    public var cardMeta: String { showTitle + (seasonFinale ? " · season finale" : "") }
}

extension UpNextPrompt {
    /// "Next episode in 7" while the countdown runs; "Play now" when autoplay is off.
    public var buttonLabel: String { secondsLeft.map { "Next episode in \($0)" } ?? "Play now" }
}

/// The Still watching prompt's text: what plays next, and why it waits.
public func stillWatchingMessage(next: UpNext?) -> String {
    let why = "Nothing plays until you answer, so the server does not keep streaming to an empty room."
    guard let next else { return why }
    return "\(episodeCode(season: next.season, episode: next.episode)) \(next.title) is up next. \(why)"
}

/// "Stopped by the server’s admin · 8:42 PM", and with where the video was,
/// "· 23 min watched" (TvPlayerStopped.dc.html) once that is a minute or more.
public func stoppedNote(at date: Date, watchedMs: Int? = nil, time: (Date) -> String = { $0.formatted(date: .omitted, time: .shortened) }) -> String {
    let watched = watchedMs.flatMap { $0 >= 60_000 ? " · \(formatDuration(ms: $0)) watched" : nil } ?? ""
    return "Stopped by the server’s admin · \(time(date))\(watched)"
}

/// The can't-play state's small print: "Remux · Living room v0.9.3"; nil when there is nothing to say.
public func failedNote(mode: PlaybackMode?, serverName: String?, serverVersion: String?) -> String? {
    let server = serverName.map { name in [name, serverVersion.map(versionLabel)].compactMap { $0 }.joined(separator: " ") }
    let parts = [mode?.label, server].compactMap { $0 }
    return parts.isEmpty ? nil : parts.joined(separator: " · ")
}
