import Foundation

// How numbers, times, dates and media facts are written for people. Shared
// by every screen (and the Apple TV app), and worded as the Android app words
// them (android/core/model/.../Formats.kt and MediaLabels.kt), so the apps read alike.

/// "S03 E07".
public func episodeCode(season: Int, episode: Int) -> String {
    String(format: "S%02d E%02d", season, episode)
}

/// "1:02:03" or "52:10" for a player clock.
public func formatClock(ms: Int) -> String {
    let total = max(ms, 0) / 1000
    let h = total / 3600, m = total / 60 % 60, s = total % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
}

/// "1 h 12 min", "1 h" or "23 min".
public func formatDuration(ms: Int) -> String {
    let minutes = Int((Double(max(ms, 0)) / 60_000).rounded())
    let h = minutes / 60, m = minutes % 60
    if h > 0 && m > 0 { return "\(h) h \(m) min" }
    if h > 0 { return "\(h) h" }
    return "\(m) min"
}

/// "23 min left".
public func formatRemaining(ms: Int) -> String { "\(formatDuration(ms: ms)) left" }

/// "18.4 TB", "740 GB".
public func formatBytes(_ bytes: Int64) -> String {
    if bytes < 1024 { return "\(bytes) B" }
    let units = ["KB", "MB", "GB", "TB", "PB"]
    var v = Double(bytes)
    var i = -1
    while v >= 1024 && i < units.count - 1 {
        v /= 1024
        i += 1
    }
    return v >= 100 ? String(format: "%.0f %@", v, units[i]) : String(format: "%.1f %@", v, units[i])
}

/// "just now", "12 min ago", "3 h ago", "yesterday", "4 days ago", "2 weeks ago", "3 months ago".
public func formatAgo(_ then: Date, now: Date = .now) -> String {
    let diff = max(Int(now.timeIntervalSince(then)), 0)
    switch diff {
    case ..<60: return "just now"
    case ..<3600: return "\(diff / 60) min ago"
    case ..<86_400: return "\(diff / 3600) h ago"
    case ..<(2 * 86_400): return "yesterday"
    case ..<(14 * 86_400): return "\(diff / 86_400) days ago"
    case ..<(60 * 86_400): return "\(diff / (7 * 86_400)) weeks ago"
    default: return "\(diff / (30 * 86_400)) months ago"
    }
}

/// "3,418".
public func formatCount(_ n: Int) -> String { n.formatted(.number) }

/// "1 season", "3 seasons".
public func plural(_ n: Int, _ one: String, _ many: String? = nil) -> String {
    "\(formatCount(n)) \(n == 1 ? one : many ?? one + "s")"
}

/// "★ 8.1"; nil for no rating.
public func formatRating(_ rating: Double?) -> String? {
    guard let rating, rating > 0 else { return nil }
    return String(format: "★ %.1f", rating)
}

private let isoDay: DateFormatter = {
    let f = DateFormatter()
    f.calendar = Calendar(identifier: .gregorian)
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "yyyy-MM-dd"
    return f
}()

/// An ISO date ("2026-10-28", or the start of a date-time) as midnight that day in the device's time zone.
public func parseDay(_ iso: String?) -> Date? {
    guard let iso, iso.count >= 10 else { return nil }
    return isoDay.date(from: String(iso.prefix(10)))
}

/// "Oct 17, 2026".
public func formatDate(_ iso: String?) -> String? {
    parseDay(iso)?.formatted(.dateTime.month(.abbreviated).day().year())
}

/// A day relative to `today`: "today", "yesterday", "3 days ago" in the last
/// week, otherwise "Oct 21" (with the year when it is not this year). Future
/// days read the same way: "Nov 4".
public func formatDay(_ iso: String?, today: Date = .now, calendar: Calendar = .current) -> String? {
    guard let date = parseDay(iso) else { return nil }
    let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: today)).day ?? 0
    switch days {
    case 0: return "today"
    case 1: return "yesterday"
    case 2...6: return "\(days) days ago"
    default:
        let sameYear = calendar.component(.year, from: date) == calendar.component(.year, from: today)
        return sameYear
            ? date.formatted(.dateTime.month(.abbreviated).day())
            : date.formatted(.dateTime.month(.abbreviated).day().year())
    }
}

/// "Aired yesterday", "Aired Oct 21" or "Airs Nov 4"; nil without a date.
public func airedLabel(_ iso: String?, today: Date = .now, calendar: Calendar = .current) -> String? {
    guard let date = parseDay(iso), let day = formatDay(iso, today: today, calendar: calendar) else { return nil }
    return calendar.startOfDay(for: date) > calendar.startOfDay(for: today) ? "Airs \(day)" : "Aired \(day)"
}

/// "4K", "1080p", "720p" or "SD" from a video size; nil when unknown.
public func resolutionLabel(width: Int?, height: Int?) -> String? {
    let w = width ?? 0, h = height ?? 0
    if w == 0 && h == 0 { return nil }
    return qualityFor(width: w, height: h)
}

/// "Dolby Vision", "HDR10", "HLG"; nil for SDR.
public func hdrLabel(_ hdr: String?) -> String? {
    guard let h = hdr?.lowercased().trimmingCharacters(in: .whitespaces), !h.isEmpty, h != "sdr" else { return nil }
    switch h {
    case "dolbyvision", "dolby vision", "dovi", "dv": return dolbyVision
    case "hdr10": return "HDR10"
    case "hdr10plus", "hdr10+": return "HDR10+"
    case "hlg": return "HLG"
    default: return h.uppercased()
    }
}

/// "HEVC", "H.264", "AV1".
public func codecLabel(_ codec: String?) -> String? {
    guard let c = codec?.lowercased().trimmingCharacters(in: .whitespaces), !c.isEmpty else { return nil }
    switch c {
    case "hevc", "h265", "h.265": return "HEVC"
    case "h264", "avc", "h.264": return "H.264"
    case "mpeg2video": return "MPEG-2"
    default: return c.uppercased()
    }
}

/// "TrueHD 7.1" from "TrueHD 7.1 · English".
public func audioLabel(_ summary: String?) -> String? {
    guard let first = summary?.components(separatedBy: " · ").first?.trimmingCharacters(in: .whitespaces), !first.isEmpty else { return nil }
    return first
}

/// One line of a version's Tracks section; `kind` ("Audio", "Subtitles") is on the first line of each group.
public struct TrackLine: Hashable, Sendable {
    public var kind: String?
    public var text: String
    public var isDefault: Bool
    public var burnIn: Bool

    public init(kind: String?, text: String, isDefault: Bool = false, burnIn: Bool = false) {
        self.kind = kind
        self.text = text
        self.isDefault = isDefault
        self.burnIn = burnIn
    }
}

extension MediaVersion {
    /// "4K", "1080p"… from the frame size; nil when not probed.
    public var quality: QualityLabel? { resolutionLabel(width: width, height: height) }

    /// A version's heading: "4K Dolby Vision", "1080p".
    public var title: String {
        let parts = [quality, hdrLabel(hdr)].compactMap { $0 }
        return parts.isEmpty ? (container ?? "Version") : parts.joined(separator: " ")
    }

    /// Container, codec, HDR, audio and size.
    public var tags: [String] {
        [container?.uppercased(), codecLabel(videoCodec), hdrTag, audioLabel(audioSummary), formatBytes(sizeBytes)].compactMap { $0 }
    }

    /// The audio and subtitle tracks, a few of each with "+ 3 more" after.
    public func trackLines(perKind: Int = 3) -> [TrackLine] {
        var lines: [TrackLine] = []
        if audio.isEmpty {
            if let s = audioSummary { lines.append(TrackLine(kind: "Audio", text: s, isDefault: true)) }
        } else {
            for (i, a) in audio.prefix(perKind).enumerated() {
                let text = a.label.hasSuffix(" (Default)") ? String(a.label.dropLast(" (Default)".count)) : a.label
                lines.append(TrackLine(kind: i == 0 ? "Audio" : nil, text: text, isDefault: a.isDefault))
            }
            if audio.count > perKind { lines.append(TrackLine(kind: nil, text: "+ \(audio.count - perKind) more")) }
        }
        for (i, s) in subtitles.prefix(perKind).enumerated() {
            lines.append(TrackLine(kind: i == 0 ? "Subtitles" : nil, text: [s.label, s.format].compactMap { $0 }.joined(separator: " · "), burnIn: s.burnInOnly))
        }
        if subtitles.count > perKind { lines.append(TrackLine(kind: nil, text: "+ \(subtitles.count - perKind) more")) }
        return lines
    }
}

extension DeviceSession {
    /// "Apple TV", "Web browser": what kind of device this is.
    public var kindLabel: String {
        if kind == .browser { return "Web browser" }
        switch platform {
        case "androidtv": return "Android TV"
        case "android": return "Android"
        case "tvos": return "Apple TV"
        case "ios": return "iPhone or iPad"
        default: return product ?? "Device"
        }
    }
}
