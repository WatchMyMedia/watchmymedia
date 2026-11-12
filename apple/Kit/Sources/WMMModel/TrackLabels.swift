import Foundation

// How tracks are named to people, shared by the data layer (decisions) and
// the pickers, as android/core/model/.../TrackLabels.kt names them.

/// "English · TrueHD 7.1 (Default)", with the track's own title when it adds something.
public func audioTrackLabel(language: String?, codec: String, channels: Int, title: String?, isDefault: Bool) -> String {
    let lang = languageName(language)
    let codecPart = "\(codecName(codec)) \(channelLayout(channels))".trimmingCharacters(in: .whitespaces)
    let extra = title.flatMap { $0.isEmpty || $0 == lang ? nil : $0 }
    return [lang, codecPart, extra].compactMap { $0 }.joined(separator: " · ") + (isDefault ? " (Default)" : "")
}

/// "English", "English · SDH", "Spanish (Forced)".
public func subtitleTrackLabel(language: String?, title: String?, forced: Bool) -> String {
    let lang = languageName(language)
    let cleanTitle = title.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
    let first = lang ?? cleanTitle ?? "Unknown"
    let second = lang != nil ? cleanTitle.flatMap { $0 == lang ? nil : $0 } : nil
    return [first, second].compactMap { $0 }.joined(separator: " · ") + (forced ? " (Forced)" : "")
}

/// ffprobe's subtitle codec as people know it: "subrip" → "SRT", "hdmv_pgs_subtitle" → "PGS"; nil for none.
public func subtitleFormatName(_ codec: String?) -> String? {
    let c = codec?.trimmingCharacters(in: .whitespaces).lowercased() ?? ""
    switch c {
    case "": return nil
    case "subrip", "srt": return "SRT"
    case "ass", "ssa": return "ASS"
    case "webvtt", "vtt": return "WebVTT"
    case "mov_text": return "TX3G"
    case "hdmv_pgs_subtitle", "pgssub", "pgs": return "PGS"
    case "dvd_subtitle", "dvdsub": return "VobSub"
    case "dvb_subtitle", "dvbsub": return "DVB"
    default: return c.uppercased()
    }
}

public func codecName(_ codec: String) -> String {
    let c = codec.lowercased()
    switch c {
    case "aac": return "AAC"
    case "ac3": return "AC3"
    case "eac3": return "EAC3"
    case "truehd": return "TrueHD"
    case "dts": return "DTS"
    case "flac": return "FLAC"
    case "opus": return "Opus"
    case "mp3": return "MP3"
    case "vorbis": return "Vorbis"
    case "alac": return "ALAC"
    default: return c.hasPrefix("pcm") ? "PCM" : codec.uppercased()
    }
}

public func channelLayout(_ channels: Int) -> String {
    switch channels {
    case 0: ""
    case 1: "Mono"
    case 2: "Stereo"
    case 3: "2.1"
    case 6: "5.1"
    case 7: "6.1"
    case 8: "7.1"
    default: "\(channels) ch"
    }
}

/// ISO 639-2 bibliographic codes that differ from the terminology codes Locale knows.
private let bibliographic: [String: String] = [
    "alb": "sq", "arm": "hy", "baq": "eu", "bur": "my", "chi": "zh", "cze": "cs", "dut": "nl",
    "fre": "fr", "geo": "ka", "ger": "de", "gre": "el", "ice": "is", "mac": "mk", "mao": "mi",
    "may": "ms", "per": "fa", "rum": "ro", "slo": "sk", "tib": "bo", "wel": "cy",
]

/// "eng" or "en" → "English" (in the device's language); nil for none or "und".
public func languageName(_ code: String?, locale: Locale = .current) -> String? {
    let c = code?.trimmingCharacters(in: .whitespaces).lowercased() ?? ""
    if c.isEmpty || ["und", "zxx", "mis"].contains(c) { return nil }
    let tag = bibliographic[c] ?? c
    guard let name = locale.localizedString(forLanguageCode: tag), !name.isEmpty, name.lowercased() != tag else { return code }
    return name.prefix(1).uppercased() + name.dropFirst()
}

/// A preferred language's name in a settings list: "Spanish",
/// "Portuguese (Brazil)", "Chinese, Simplified"; the tag when unknown.
public func languageTagName(_ tag: String, locale: Locale = .current) -> String {
    guard let name = locale.localizedString(forIdentifier: tag), !name.isEmpty else { return tag }
    return name.prefix(1).uppercased() + name.dropFirst()
}

/// The device's language as a list offers it ("Use English"): the first of
/// `preferred` (`Locale.preferredLanguages`) as its base language, keeping
/// only what tells written varieties apart, Chinese's script and
/// Portuguese's region ("en-US" → "en", "zh-Hant-TW" → "zh-Hant", "pt-BR"
/// stays). Nil when none is usable.
public func suggestedLanguage(_ preferred: [String]) -> String? {
    for identifier in preferred {
        let language = Locale.Language(identifier: identifier)
        guard let code = language.languageCode?.identifier, code != "und" else { continue }
        switch code {
        case "zh": return language.script.map { "zh-\($0.identifier)" } ?? "zh"
        case "pt": return language.region.map { "pt-\($0.identifier)" } ?? "pt"
        default: return code
        }
    }
    return nil
}

/// OpenSubtitles' language codes as names: "en" → "English", "pt-br" → "Portuguese (Brazil)"; the code when unknown.
public func onlineLanguageLabel(_ code: String?, locale: Locale = .current) -> String {
    let c = code?.trimmingCharacters(in: .whitespaces) ?? ""
    if c.isEmpty { return "Unknown" }
    let parts = c.split(separator: "-", maxSplits: 1).map(String.init)
    guard let name = languageName(parts[0], locale: locale), name != parts[0] else { return c }
    guard parts.count > 1 else { return name }
    let region = parts[1].uppercased()
    let place = locale.localizedString(forRegionCode: region) ?? region
    return "\(name) (\(place))"
}

extension OnlineSubtitles {
    /// Results worth listing: not the ones already among `tracks` (downloaded), at most `max`.
    public func offered(excluding tracks: [SubtitleOption], max: Int = 12) -> [OnlineSubtitle] {
        Array(results.filter { r in r.trackID == nil || !tracks.contains { $0.id == r.trackID } }.prefix(max))
    }

    /// Whether the viewer can download a result now.
    public var canDownloadNow: Bool { canDownload && remaining != 0 }

    /// The line under the results, before the credit: why they can't be
    /// downloaded, or what went wrong; nil when all is well.
    public func note(time: (Date) -> String = { $0.formatted(date: .omitted, time: .shortened) }) -> String? {
        if let error { return error }
        if !canDownload { return "Only admins can download subtitles on this server." }
        if remaining == 0 { return "No downloads left today" + (resetAt.map { "; they resume at \(time($0))." } ?? ".") }
        if results.isEmpty { return "Nothing found online in these languages." }
        return nil
    }
}

extension OnlineSubtitle {
    /// "Matches this file · HI · Harbor.Lights.S03E07.1080p".
    public func detail(downloading: Bool) -> String {
        [
            downloading ? "Downloading…" : nil,
            hashMatch ? "Matches this file" : nil,
            hearingImpaired ? "HI" : nil,
            forced ? "Forced" : nil,
            aiTranslated ? "AI translated" : nil,
            release.isEmpty ? nil : release,
        ].compactMap { $0 }.joined(separator: " · ")
    }
}
