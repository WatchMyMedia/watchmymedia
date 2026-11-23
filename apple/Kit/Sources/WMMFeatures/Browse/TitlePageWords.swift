import Foundation
import WMMModel

// Apple TV's movie and show pages, in a viewer's words: what the picture and
// sound are, how a file plays, and the sound and subtitles to start with.
// The formats (codecs, sizes, what the server converts) are said in one
// place only, File details, a press away behind More.

// MARK: At a glance

/// One quiet line under a title's buttons: "In 4K with Dolby Vision and 5.1
/// sound", "In HD with stereo sound", "With 5.1 sound"; nil with nothing to say.
public func pictureAndSoundLine(quality: QualityLabel?, hdr: String?, audioSummary: String?) -> String? {
    let picture: String? = switch quality {
    case "4K"?: "4K"
    case "1080p"?, "720p"?: "HD"
    default: nil
    }
    let range: String? = hdrLabel(hdr).map { $0 == dolbyVision ? $0 : "HDR" }
    let sound = soundWords(audioSummary)
    var line: String
    if let picture {
        line = "In \(picture)"
        if let range { line += " with \(range)" }
        if let sound { line += (range == nil ? " with " : " and ") + sound }
    } else if let range {
        line = "With \(range)"
        if let sound { line += " and \(sound)" }
    } else if let sound {
        line = "With \(sound)"
    } else {
        return nil
    }
    return line
}

/// "5.1 sound", "7.1 sound", "stereo sound", from "TrueHD 7.1 · English".
private func soundWords(_ summary: String?) -> String? {
    guard let summary else { return nil }
    if let channels = channelsLabel(summary) {
        return channels == "2.0" ? "stereo sound" : channels == "1.0" ? nil : "\(channels) sound"
    }
    return summary.localizedCaseInsensitiveContains("stereo") ? "stereo sound" : nil
}

extension MovieDetail {
    /// The line under the buttons, from the version Play plays.
    public func pictureAndSound(version: MediaVersion?) -> String? {
        guard let v = version ?? versions.first else {
            return pictureAndSoundLine(quality: quality, hdr: hdr ? "hdr10" : nil, audioSummary: nil)
        }
        return pictureAndSoundLine(quality: v.quality ?? quality, hdr: v.hdr, audioSummary: v.audioSummary)
    }

    /// Facts at Body size, the rating drawn in its box apart: "2024", "1 h 52 min" before it, "Thriller, Mystery", "★ 7.8" after.
    public var tvFacts: (before: [String], after: [String]) {
        (
            [year.map(String.init), runtimeMin.map { formatDuration(ms: $0 * 60_000) }].compactMap { $0 },
            [genres.isEmpty ? nil : genres.prefix(2).joined(separator: ", "), formatRating(rating)].compactMap { $0 }
        )
    }
}

/// Partway through: "48 min left" and "Ends at 10:12 PM"; not begun, "Start
/// now and it ends at 10:20 PM"; nothing when the length isn't known.
public struct WhenItEnds: Hashable, Sendable {
    /// Gold: "48 min left"; nil when not begun.
    public var left: String?
    /// "Ends at 10:12 PM", or "Start now and it ends at 10:20 PM".
    public var ends: String
    /// How far along, for the bar beside them; nil when not begun.
    public var progress: Double?

    public init(left: String? = nil, ends: String, progress: Double? = nil) {
        self.left = left
        self.ends = ends
        self.progress = progress
    }

    /// For something `durationMs` long, watched as far as `watch` says.
    public static func line(watch: WatchProgress?, durationMs: Int?, now: Date = .now, clock: LiveClock = .device) -> WhenItEnds? {
        if let watch, watch.resumable {
            return WhenItEnds(left: formatRemaining(ms: watch.remainingMs), ends: endsAtLine(remainingMs: watch.remainingMs, now: now, clock: clock), progress: watch.progress)
        }
        guard let duration = durationMs ?? watch?.durationMs, duration > 0 else { return nil }
        return WhenItEnds(ends: startNowEndsLine(durationMs: duration, now: now, clock: clock))
    }
}

// MARK: File details

/// How a file plays on this device, in one sentence, from its plan: "The
/// picture and sound play as they are.", "The picture plays as it is. The
/// sound is converted to a kind this Apple TV can play."
public func playsSentence(_ plan: PlaybackPlan?, device: String = "this Apple TV") -> String {
    guard let plan else { return "How it plays is worked out when it starts." }
    let soundConverted = "The picture plays as it is. The sound is converted to a kind \(device) can play."
    switch plan.mode {
    case .direct, .remux:
        return plan.audioCodec != nil ? soundConverted : "The picture and sound play as they are."
    case .audioTranscode:
        return soundConverted
    case .transcode:
        let picture = plan.video.map { "The picture is converted for \(device), at \($0.quality)." } ?? "The picture is converted to a kind \(device) can play."
        return picture + (plan.audioCodec != nil ? " So is the sound." : " The sound plays as it is.")
    }
}

/// One version's facts in File details: a heading ("4K · Dolby Vision ·
/// plays unless you choose") over its rows.
public struct FileFacts: Hashable, Sendable {
    public var heading: String
    public var rows: [DetailRow]

    public init(heading: String, rows: [DetailRow]) {
        self.heading = heading
        self.rows = rows
    }
}

extension MediaVersion {
    /// File details' facts for this version: the picture, the sound, what it
    /// plays as here, the subtitles and the file. `isDefault` among several
    /// says it plays unless another is chosen.
    public func fileFacts(plan: PlaybackPlan?, isDefault: Bool, several: Bool) -> FileFacts {
        var heading = [quality, hdrLabel(hdr)].compactMap { $0 }.joined(separator: " · ")
        if heading.isEmpty { heading = container?.uppercased() ?? "Version" }
        if several && isDefault { heading += " · plays unless you choose" }
        let size = [width, height].allSatisfy { ($0 ?? 0) > 0 } ? "\(width ?? 0) × \(height ?? 0)" : nil
        let video = [size, codecLabel(videoCodec), dvProfile.map { "Dolby Vision profile \($0)" }].compactMap { $0 }
        let sound = audioSummary.map { summary in
            summary.components(separatedBy: " · ").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.reversed().joined(separator: " · ")
        }
        var languages: [String] = []
        for subtitle in subtitles {
            let name = subtitle.label.components(separatedBy: " · ").first ?? subtitle.label
            if !name.isEmpty && !languages.contains(name) { languages.append(name) }
        }
        let rows: [DetailRow?] = [
            video.isEmpty ? nil : DetailRow("Video", video.joined(separator: " · ")),
            sound.flatMap { $0.isEmpty ? nil : DetailRow("Sound", $0) },
            plan.map { DetailRow("Plays as", $0.playsAs) },
            languages.isEmpty ? nil : DetailRow("Subtitles", languages.prefix(4).joined(separator: ", ") + (languages.count > 4 ? " and \(languages.count - 4) more" : "")),
            DetailRow("File", [container?.uppercased(), formatBytes(sizeBytes)].compactMap { $0 }.joined(separator: " · ")),
        ]
        return FileFacts(heading: heading, rows: rows.compactMap { $0 })
    }
}

extension PlaybackPlan {
    /// File details' "Plays as": "It is, nothing converted", "AAC 5.1", "1080p HEVC · AAC".
    public var playsAs: String {
        let audio = audioCodec.map { codec in [codec, audioChannels.flatMap(channelWords)].compactMap { $0 }.joined(separator: " ") }
        switch mode {
        case .direct, .remux:
            return audio ?? "It is, nothing converted"
        case .audioTranscode:
            return audio ?? "The sound converted"
        case .transcode:
            let video = [video?.quality, videoCodec].compactMap { $0 }.joined(separator: " ")
            return [video.isEmpty ? "The picture converted" : video, audio].compactMap { $0 }.joined(separator: " · ")
        }
    }
}

/// "Stereo", "5.1", "7.1"; nil for mono and odd counts.
func channelWords(_ channels: Int) -> String? {
    switch channels {
    case 2: "Stereo"
    case 6: "5.1"
    case 8: "7.1"
    default: nil
    }
}

// MARK: Sound and subtitles before playing

extension AudioOption {
    /// A sound choice in words: "English · 7.1", "Commentary · English · Stereo".
    public var choiceLabel: String {
        var parts = label.components(separatedBy: " · ").map { $0.trimmingCharacters(in: .whitespaces) }
        if let last = parts.last, last.hasSuffix(" (Default)") { parts[parts.count - 1] = String(last.dropLast(" (Default)".count)) }
        let name = parts.first ?? label
        let extra = title.flatMap { $0.isEmpty || $0 == name ? nil : $0 } ?? parts.dropFirst(2).first
        let layout = channels.flatMap(channelWords) ?? channelsLabel(label) ?? (label.localizedCaseInsensitiveContains("stereo") ? "Stereo" : nil)
        return [extra, name, layout].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

extension SubtitleOption {
    /// A subtitle choice in words: "English", "French · Forced", "English · With sounds described".
    public var choiceLabel: String {
        var name = label.components(separatedBy: " · ").first ?? label
        if name.hasSuffix(" (Forced)") { name = String(name.dropLast(" (Forced)".count)) }
        let described = hearingImpaired || label.contains("SDH")
        return [name, forced ? "Forced" : nil, described ? "With sounds described" : nil].compactMap { $0 }.joined(separator: " · ")
    }

    /// The line under a subtitle choice: an image one is drawn into the picture.
    public var choiceLine: String? { burnInOnly ? "Drawn into the picture" : nil }
}

extension MediaVersion {
    /// More's Sound row: the track chosen, else the file's default; nil with no tracks known.
    public func soundValue(_ choice: TrackChoice) -> String? {
        let chosen = choice.audioIndex.flatMap { index in audio.first { $0.index == index } }
        return (chosen ?? audio.first(where: \.isDefault) ?? audio.first)?.choiceLabel ?? audioLabel(audioSummary)
    }

    /// More's Subtitles row: "Off", the track chosen, or "Automatic" (the person's languages).
    public func subtitlesValue(_ choice: TrackChoice) -> String {
        switch choice.subtitle {
        case .off?: "Off"
        case .track(let id)?: subtitles.first { $0.id == id }?.choiceLabel ?? "Automatic"
        case nil: "Automatic"
        }
    }

    /// Version's value and the version list's names: "4K · Dolby Vision", "1080p".
    public var choiceLabel: String {
        let parts = [quality, hdrLabel(hdr)].compactMap { $0 }
        return parts.isEmpty ? (container?.uppercased() ?? label) : parts.joined(separator: " · ")
    }
}

/// A subtitle choice that turns them off, first in the list.
public let subtitlesOffLabel = "Off"
/// The choice that leaves subtitles to the person's languages.
public let subtitlesAutomaticLabel = "Automatic"
/// The line under Automatic.
public let subtitlesAutomaticLine = "In the languages you chose in Settings"

// MARK: The show page

extension EpisodeDetail {
    /// Over the episode's title on Apple TV's show page: "Up next · Season
    /// 3, episode 7", "Season 3, episode 8", "Specials, episode 2", "Season
    /// 3, episode 9 · not aired yet".
    public func tvKicker(reason: OnTopReason?) -> String {
        let place = season == 0 ? "Specials, episode \(number)" : "Season \(season), episode \(number)"
        let lead: String? = switch reason {
        case .upNext?, .resume?: "Up next"
        case .startHere?: "Start here"
        case .watchedItAll?, nil: nil
        }
        return [lead, place, aired ? nil : "not aired yet"].compactMap { $0 }.joined(separator: " · ")
    }

    /// The facts under its title: "52 min", "Aired yesterday", or "Airs Nov 4", "52 min" for one yet to air.
    public func tvFacts(today: Date = .now) -> [String] {
        let day = airedLabel(airDate, today: today)
        return aired ? [runtimeLabel, day].compactMap { $0 } : [day ?? "Not aired yet", runtimeLabel].compactMap { $0 }
    }

    /// Under a card in the season row: "52 min · 29 min left", "51 min", "Nov 4" for one yet to air, or why it is missing.
    public func tvCardLine(today: Date = .now) -> String {
        if !aired { return formatDay(airDate, today: today).map { $0.prefix(1).uppercased() + $0.dropFirst() } ?? "Not aired yet" }
        if !hasFile { return rowCardLine(today: today) }
        if resumable, let watch { return [runtimeLabel, formatRemaining(ms: watch.remainingMs)].compactMap { $0 }.joined(separator: " · ") }
        return runtimeLabel ?? meta(today: today)
    }
}

/// What Apple TV's show page says while the episode on top hasn't aired.
public let notAiredYetLine = "It appears here, ready to play, once it has aired."

extension ShowDetail {
    /// Beside the show's name: "3 seasons · next episode Nov 5", "2 seasons · Ended".
    public var tvLine: String? {
        let count = seasons.filter { $0.number > 0 && $0.availableCount > 0 }.count
        let seasonWords = count > 0 ? plural(count, "season") : nil
        var status: String?
        if self.status != nil {
            if continuing {
                status = formatDay(nextAirDate).map { "next episode \($0)" } ?? "Continuing"
            } else {
                status = statusPill?.text
            }
        }
        let line = [seasonWords, status].compactMap { $0 }.joined(separator: " · ")
        return line.isEmpty ? nil : line
    }

    /// About the show's facts at Body size, the rating apart: "2021–", "3 seasons" before it, "Drama, Mystery", "★ 8.4" after.
    public var tvFacts: (before: [String], after: [String]) {
        let count = seasons.filter { $0.number > 0 && $0.availableCount > 0 }.count
        let ended = status != nil && !continuing
        return (
            [year.map { ended ? "\($0)" : "\($0)–" }, count > 0 ? plural(count, "season") : nil].compactMap { $0 },
            [genres.isEmpty ? nil : genres.prefix(2).joined(separator: ", "), formatRating(rating)].compactMap { $0 }
        )
    }
}

extension SeasonSummary {
    /// What a season's tab says after its name: "2 left" partway through,
    /// nothing when none or all of it is watched (all watched has a tick);
    /// with Show missing on, what it has of the whole ("4 of 10", "none").
    public func tvTabDetail(missingShown: Bool) -> String? {
        if missingShown && availableCount < episodeCount { return tabCount(missingShown: true) }
        let left = availableCount - watchedCount
        return watchedCount > 0 && left > 0 ? "\(formatCount(left)) left" : nil
    }
}
