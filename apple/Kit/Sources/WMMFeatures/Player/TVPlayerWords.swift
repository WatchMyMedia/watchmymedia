import Foundation
import WMMModel

// The Apple TV player's words: the tracks and the quality in the viewer's
// terms, what is playing in a sentence, and the prompts over the video. The
// file's formats and the stream's numbers are never on the picture: they
// are in the Quality tab, after a sentence that says how it plays. The
// phone's and iPad's players keep the words in PlayerLabels.swift.

/// The tabs the app adds to the system player's info panel, after the
/// system's Info and, for an episode, Episodes.
public enum TVPlayerTab: String, CaseIterable, Hashable, Sendable {
    case tracks, quality

    public var title: String {
        switch self {
        case .tracks: "Audio & subtitles"
        case .quality: "Quality"
        }
    }
}

// MARK: - Sound and subtitles

/// How a sound track sounds: "5.1 surround", "7.1 surround", "Stereo",
/// "Mono"; nil when it isn't known.
public func soundLayoutWords(channels: Int?) -> String? {
    switch channels {
    case 1?: "Mono"
    case 2?: "Stereo"
    case 3?: "2.1 sound"
    case 6?: "5.1 surround"
    case 7?: "6.1 surround"
    case 8?: "7.1 surround"
    default: nil
    }
}

/// A sound format as people know it from their TV or receiver: "Dolby
/// Digital Plus", "Dolby TrueHD", "DTS"; nil when unknown.
public func soundFormatWords(codec: String?) -> String? {
    switch codec?.lowercased() {
    case "eac3"?: "Dolby Digital Plus"
    case "ac3"?: "Dolby Digital"
    case "truehd"?: "Dolby TrueHD"
    case "dts"?: "DTS"
    case "aac"?: "AAC"
    case "flac"?: "FLAC"
    case "alac"?: "Apple Lossless"
    case "opus"?: "Opus"
    case "mp3"?: "MP3"
    default: nil
    }
}

/// A sound track's parts, from its own fields where the server sent them
/// and from its label ("English · EAC3 5.1 · Commentary (Default)") where
/// not: the language, the format, the channels and the track's own title.
private struct SoundParts {
    var language: String
    var codec: String?
    var channels: Int?
    var title: String?

    init(_ option: AudioOption) {
        var label = option.label
        if label.hasSuffix(" (Default)") { label.removeLast(" (Default)".count) }
        let parts = label.components(separatedBy: " · ")
        language = languageName(option.language) ?? parts.first.flatMap { $0.isEmpty ? nil : $0 } ?? "Unknown"
        let format = parts.count > 1 ? parts[1].components(separatedBy: " ") : []
        codec = option.codec ?? format.first?.lowercased()
        channels = option.channels ?? format.last.flatMap(Self.channels)
        let extra = option.title ?? (parts.count > 2 ? parts.dropFirst(2).joined(separator: " · ") : nil)
        title = extra.flatMap { $0.isEmpty || $0 == language ? nil : $0 }
    }

    private static func channels(_ layout: String) -> Int? {
        ["Mono": 1, "Stereo": 2, "2.1": 3, "5.1": 6, "6.1": 7, "7.1": 8][layout]
    }
}

/// A row of the Audio list: the language, then how it sounds, or the
/// track's own name when it has one ("Director's commentary"). No codecs.
public func tvAudioRow(_ option: AudioOption) -> OptionLabel {
    let parts = SoundParts(option)
    return OptionLabel(parts.language, detail: parts.title ?? soundLayoutWords(channels: parts.channels))
}

/// The sound playing, for What is playing: "English · Dolby Digital Plus
/// 5.1", or "English · Stereo".
public func tvSoundLine(_ option: AudioOption) -> String {
    let parts = SoundParts(option)
    let layout = parts.channels.flatMap { channelLayout($0) }.flatMap { $0.isEmpty ? nil : $0 }
    let format = soundFormatWords(codec: parts.codec)
    let how: String? = switch (format, layout) {
    case let (format?, layout?) where layout == "Stereo" || layout == "Mono": "\(format), \(layout.lowercased())"
    case let (format?, layout?): "\(format) \(layout)"
    case let (format?, nil): format
    case let (nil, layout?): soundLayoutWords(channels: parts.channels) ?? layout
    case (nil, nil): nil
    }
    return [parts.language, how].compactMap { $0 }.joined(separator: " · ")
}

/// A row of the Subtitles list.
public struct TVSubtitleRow: Hashable, Sendable, Identifiable {
    public enum Kind: Hashable, Sendable {
        /// A track to turn on; nil turns subtitles off.
        case track(String?)
        /// Find more subtitles: the results from the server's online provider.
        case findMore
    }

    public var kind: Kind
    public var label: OptionLabel
    public var selected: Bool

    public var id: String {
        switch kind {
        case .track(let id): "subtitle:\(id ?? "off")"
        case .findMore: "subtitles:more"
        }
    }
}

/// "With sounds described" for subtitles that say the sounds too; the rest
/// in words: shown only for foreign speech, burned into the picture.
public func tvSubtitleRow(_ option: SubtitleOption, selected: Bool) -> TVSubtitleRow {
    var label = option.label
    var forced = option.forced
    if label.hasSuffix(" (Forced)") {
        label.removeLast(" (Forced)".count)
        forced = true
    }
    let parts = label.components(separatedBy: " · ")
    let name = parts.first.flatMap { $0.isEmpty ? nil : $0 } ?? languageName(option.language) ?? "Unknown"
    let described = ["SDH", "CC", "HI"]
    let extras = parts.dropFirst().filter { !described.contains($0.uppercased()) }
    let saysSounds = option.hearingImpaired || parts.dropFirst().contains { described.contains($0.uppercased()) }
    let detail = [
        saysSounds ? "With sounds described" : nil,
        forced ? "Only for speech in another language" : nil,
        extras.isEmpty ? nil : extras.joined(separator: " · "),
        option.source == .online ? "Found online" : nil,
        option.burnInOnly ? "Restarts the video to turn on" : nil,
    ].compactMap { $0 }.joined(separator: " · ")
    return TVSubtitleRow(kind: .track(option.id), label: OptionLabel(name, detail: detail), selected: selected)
}

/// The Subtitles list: Off, the file's own and any found online before,
/// then Find more subtitles where the server can look online.
public func tvSubtitleRows(_ state: PlayerState) -> [TVSubtitleRow] {
    var rows = [TVSubtitleRow(kind: .track(nil), label: OptionLabel("Off"), selected: state.selectedSubtitle == nil)]
    rows += state.subtitles.map { tvSubtitleRow($0, selected: $0.id == state.selectedSubtitle) }
    if state.online != nil {
        rows.append(TVSubtitleRow(kind: .findMore, label: OptionLabel("Find more subtitles"), selected: false))
    }
    return rows
}

/// What Find more subtitles lists: the online results, their note and the
/// credit the provider asks for (``subtitleEntries(_:)``'s online part).
public func tvOnlineSubtitleEntries(_ state: PlayerState) -> [SubtitleEntry] {
    let entries = subtitleEntries(state)
    guard let start = entries.firstIndex(of: .heading("OpenSubtitles")) else { return [.note(onlineCredit(state.online))] }
    return entries[entries.index(after: start)...].filter {
        if case .track(_, _, _) = $0 { return false }
        return true
    }
}

// MARK: - Quality

/// The quality step playing now: the one chosen, or Automatic's step.
func playingStep(_ state: PlayerState) -> StreamQuality {
    state.quality == .auto ? state.autoStep ?? .original : state.quality
}

/// A row of the Quality list, by name: "Automatic" with what it is at now,
/// "Original" with the file's size of picture while it plays, and the
/// lowest step "Uses least data". No bitrates.
public func tvQualityRow(_ quality: StreamQuality, in state: PlayerState) -> OptionLabel {
    switch quality {
    case .auto:
        guard state.quality == .auto, let step = state.autoStep else { return OptionLabel("Automatic") }
        return OptionLabel("Automatic", detail: step == .original || step == .auto ? "Now the original" : "Now \(step.shortLabel)")
    case .original:
        let playing = playingStep(state) == .original
        return OptionLabel("Original", detail: playing ? state.video.map { $0.quality } : nil)
    default:
        let lowest = state.qualities.last { $0 != .auto && $0 != .original }
        return OptionLabel(quality.shortLabel, detail: quality == lowest ? "Uses least data" : nil)
    }
}

/// Under the Quality list on Apple TV.
public let tvQualityKeptNote = "Kept for this Apple TV, not for your other devices"

/// The quality below the one playing, for Play at 1080p after a failure; nil at the lowest.
public func tvLowerQuality(_ state: PlayerState) -> StreamQuality? {
    let order: [StreamQuality] = [.fullHD, .hd, .sd, .low]
    let lower: StreamQuality? = switch playingStep(state) {
    case .auto, .original, .uhd: .fullHD
    case .fullHD: .hd
    case .hd: .sd
    case .sd: .low
    case .low: nil
    }
    guard let lower, let from = order.firstIndex(of: lower) else { return nil }
    return order[from...].first { state.qualities.contains($0) }
}

/// "Play at 1080p".
public func tvPlayAtLabel(_ quality: StreamQuality) -> String { "Play at \(quality.shortLabel)" }

// MARK: - What is playing

/// The Quality tab's What is playing: a sentence that says how the file
/// reaches this Apple TV, then the picture, the sound, the speed and the
/// server, each a line where it is known.
public struct TVWhatIsPlaying: Hashable, Sendable {
    public var sentence: String
    /// "Picture", "4K · Dolby Vision"; "Sound", "English · Dolby Digital Plus 5.1"; "Speed"; "From".
    public var lines: [DetailRow]

    public init(_ state: PlayerState, serverName: String?) {
        let step = playingStep(state)
        let capped = step != .original && step != .auto
        switch state.mode {
        case nil:
            sentence = "Asking the server how best to play this."
        case .direct?:
            sentence = "The original file, as it is. Nothing is being converted."
        case .remux?:
            sentence = "The original picture and sound, repackaged so this Apple TV can play them. Nothing is being converted."
        case .audioTranscode?:
            sentence = "The picture plays as it is. The sound is converted to a kind this Apple TV can play."
        case .transcode?:
            if capped && state.quality == .auto {
                sentence = "The server is converting the picture to \(step.shortLabel) to suit your connection."
            } else if capped {
                sentence = "The server is converting the picture to \(step.shortLabel), the quality chosen here."
            } else {
                sentence = "The server is converting the picture so this Apple TV can play it."
            }
        }
        var lines: [DetailRow] = []
        if let video = state.video {
            lines.append(DetailRow("Picture", [video.quality, video.hdr].compactMap { $0 }.joined(separator: " · ")))
        }
        if let audio = state.audio.first(where: { $0.index == state.selectedAudio }) ?? (state.audio.count == 1 ? state.audio.first : nil) {
            let sound = tvSoundLine(audio)
            lines.append(DetailRow("Sound", state.mode == .audioTranscode ? "\(sound), converted" : sound))
        }
        if capped, let rate = step.detail {
            lines.append(DetailRow("Speed", "About \(rate)"))
        }
        if let serverName, !serverName.isEmpty {
            lines.append(DetailRow("From", serverName))
        }
        self.lines = lines
    }
}

// MARK: - Over the video

/// The system player's subtitle line: "S03 E07 · The Long Dark · Ends at 9:41 PM".
public func tvPlayerSubtitle(_ subtitle: String?, endsAt: String?) -> String? {
    let parts = [subtitle, endsAt].compactMap { $0 }.filter { !$0.isEmpty }
    return parts.isEmpty ? nil : parts.joined(separator: " · ")
}

/// Can't play this, in the viewer's words: what happened and what to try.
/// The server's own message is kept for whoever runs it, a press away.
public struct TVCantPlay: Hashable, Sendable {
    public var title: String
    public var message: String

    public init(error: String, serverName: String?) {
        let server = serverName.flatMap { $0.isEmpty ? nil : $0 }
        if error.hasPrefix("Lost the connection") {
            title = "Lost the connection"
            message = "This Apple TV stopped hearing from \(server ?? "the server"). Check that both are on the network, then try again."
        } else if error.contains("Try signing in again") {
            title = "Can’t play this"
            message = "\(server ?? "The server") didn’t let this Apple TV play it. Signing in again usually fixes that."
        } else {
            title = "Can’t play this"
            message = "\(server ?? "The server") couldn’t prepare this file for your Apple TV. A lower quality often works."
        }
    }
}

/// The admin stopped the play: "The admin of Harbour House stopped this:", before the message.
public func tvStoppedLine(serverName: String?) -> String {
    guard let serverName, !serverName.isEmpty else { return "The server’s admin stopped this:" }
    return "The admin of \(serverName) stopped this:"
}

/// Under the stopped message: "At 8:42 PM · 23 min watched", the second
/// part once that is a minute or more.
public func tvStoppedNote(at date: Date, watchedMs: Int? = nil, time: (Date) -> String = { $0.formatted(date: .omitted, time: .shortened) }) -> String {
    let watched = watchedMs.flatMap { $0 >= 60_000 ? " · \(formatDuration(ms: $0)) watched" : nil } ?? ""
    return "At \(time(date))\(watched)"
}

/// Under Still watching's buttons: nothing goes on until a button is pressed.
public let tvStillWatchingNote = "Nothing plays until you answer."

extension UpNextPrompt {
    /// "Play in 8" while the countdown runs; "Play now" with autoplay off.
    public var tvButtonLabel: String { secondsLeft.map { "Play in \($0)" } ?? "Play now" }
}

extension UpNext {
    /// "S03 E08 · 49 min", under the next episode's title.
    public var tvMeta: String {
        [episodeCode(season: season, episode: episode), runtimeMs.map { formatDuration(ms: $0) }].compactMap { $0 }.joined(separator: " · ")
    }
}
