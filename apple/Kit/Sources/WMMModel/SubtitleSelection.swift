import Foundation

// The device's own choices for offline plays, mirroring the server's
// (server/internal/playback/audio_preferences.go and subtitle_preferences.go)
// and their Go test tables. Streams don't use them: the server chooses.

/// The starting subtitle among text tracks actually held on this device.
/// The caller supplies only finished local tracks; image tracks still require
/// a server burn-in and are never picked here. Manual choices take precedence
/// at the caller. No provider or server access is needed. `audioLanguages`
/// are the person's, which the foreign-audio choice reads.
public func preferredSubtitleID(tracks: [SubtitleOption], audioLanguage: String?, audioLanguages: [String] = [], preferences: SubtitlePreferences) -> String? {
    let (mode, languages) = startingSubtitles(audioLanguage: audioLanguage, audioLanguages: audioLanguages, preferences: preferences)
    switch mode {
    case .off:
        return nil
    case .forced:
        return bestSubtitleID(tracks: tracks, language: audioLanguage, forced: true)
    case .on:
        // A complete track in any preferred language beats a forced-only
        // fallback. Within each group, preserve the person's language order.
        for forced in [false, true] {
            for language in languages {
                if let id = bestSubtitleID(tracks: tracks, language: language, forced: forced) {
                    return id
                }
            }
        }
        return nil
    }
}

/// The foreign-audio choice against the audio track (Go's
/// `StartingSubtitles`): forced while the person follows the audio, on when
/// they don't, in their subtitle languages or else their audio languages.
public func startingSubtitles(audioLanguage: String?, audioLanguages: [String], preferences p: SubtitlePreferences) -> (mode: SubtitleMode, languages: [String]) {
    if p.mode == .forced && p.foreignAudio && !understoodAudio(audioLanguage, audioLanguages: audioLanguages, subtitleLanguages: p.languages) {
        return (.on, p.languages.isEmpty ? audioLanguages : p.languages)
    }
    return (p.mode, p.languages)
}

/// Whether the person follows an audio track: its language is one of their
/// audio languages or, when they have none, one of their subtitle
/// languages. An untagged track, or a person with no languages at all,
/// counts as followed, so files with und audio don't start with subtitles.
public func understoodAudio(_ audioLanguage: String?, audioLanguages: [String], subtitleLanguages: [String]) -> Bool {
    guard let audio = SubtitleLanguage(audioLanguage) else { return true }
    let known = audioLanguages.isEmpty ? subtitleLanguages : audioLanguages
    if known.isEmpty { return true }
    return known.contains { SubtitleLanguage($0).map(audio.speechMatch) ?? 0 > 0 }
}

/// The audio track to start on for the person's languages (Go's
/// `PreferredAudio`): the best track in the first language the file has;
/// nil when none matches, and the file's default stays. Within a language
/// it prefers the exact tag to the same base language, a track `profile`
/// can copy to one that needs converting, the file's default flag, more
/// channels, then the lower index. Commentary and audio description are
/// only ever picked by hand.
public func preferredAudioIndex(tracks: [AudioOption], profile: PlaybackProfile?, languages: [String]) -> Int? {
    for want in languages {
        guard let wanted = SubtitleLanguage(want) else { continue }
        var best: (track: AudioOption, match: Int)?
        for track in tracks where !secondaryAudio(track) {
            guard let found = SubtitleLanguage(track.language) else { continue }
            let match = found.speechMatch(wanted)
            guard match > 0 else { continue }
            if let b = best, !betterAudio(track, match, than: b.track, b.match, profile: profile) { continue }
            best = (track, match)
        }
        if let best { return best.track.index }
    }
    return nil
}

/// The track a play starts on when it names none and no preference
/// matches: the first default-flagged, else the first.
public func defaultAudioIndex(_ tracks: [AudioOption]) -> Int? {
    (tracks.first(where: \.isDefault) ?? tracks.first)?.index
}

private func betterAudio(_ a: AudioOption, _ aMatch: Int, than b: AudioOption, _ bMatch: Int, profile: PlaybackProfile?) -> Bool {
    if aMatch != bMatch { return aMatch > bMatch }
    let ac = copyableAudio(a, profile), bc = copyableAudio(b, profile)
    if ac != bc { return ac }
    if a.isDefault != b.isDefault { return a.isDefault }
    if (a.channels ?? 0) != (b.channels ?? 0) { return (a.channels ?? 0) > (b.channels ?? 0) }
    return a.index < b.index
}

private func copyableAudio(_ a: AudioOption, _ profile: PlaybackProfile?) -> Bool {
    guard let profile, let codec = a.codec?.lowercased() else { return false }
    let key = codec.hasPrefix("pcm_") ? "pcm" : codec
    let max = profile.maxAudioChannels ?? 0
    return profile.audioCodecs.contains { $0.lowercased() == key } && (max == 0 || (a.channels ?? 0) <= max)
}

/// Words that mark a track nobody wants by default. "Director's Cut" is a
/// main track, so "director" alone is not one of them.
private let secondaryAudioWords = ["commentary", "description", "descriptive", "described"]

/// A commentary or audio-description track, by its title: the API doesn't
/// send the dispositions the server also reads.
func secondaryAudio(_ a: AudioOption) -> Bool {
    let title = a.title?.lowercased() ?? ""
    return secondaryAudioWords.contains { title.contains($0) }
}

private func bestSubtitleID(tracks: [SubtitleOption], language: String?, forced: Bool) -> String? {
    guard let wanted = SubtitleLanguage(language) else { return nil }
    var best: (id: String, match: Int)?
    for track in tracks where !track.burnInOnly && track.forced == forced {
        guard let found = SubtitleLanguage(track.language) else { continue }
        let match = found.match(wanted)
        if match > (best?.match ?? 0) { best = (track.id, match) }
    }
    return best?.id
}

private struct SubtitleLanguage {
    private static let recognizedCodes = Set(Locale.LanguageCode.isoLanguageCodes)

    let canonical: String
    let language: Locale.Language
    let components: Locale.Language.Components

    init?(_ identifier: String?) {
        guard let identifier else { return nil }
        let trimmed = identifier.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.utf8.count <= 64 else { return nil }
        // Canonicalize first: ICU normalizes bibliographic ISO 639-2 codes
        // such as fre/ger/chi here, whereas Locale.Language alone does not.
        let canonical = Locale.canonicalLanguageIdentifier(from: trimmed)
        guard !canonical.split(separator: "-", omittingEmptySubsequences: false).contains(where: \.isEmpty),
              canonical.utf8.allSatisfy({ (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 })
        else { return nil }
        let language = Locale.Language(identifier: canonical)
        guard let code = language.languageCode, code.identifier != "und", Self.recognizedCodes.contains(code) else { return nil }
        self.canonical = canonical
        self.language = language
        components = Locale.Language.Components(identifier: canonical)
    }

    /// As on the server: exact tag first, then the same base language. An
    /// explicit wanted script or region must also agree on the inferred
    /// script, so Simplified Chinese cannot satisfy Traditional Chinese.
    func match(_ wanted: Self) -> Int {
        guard language.languageCode == wanted.language.languageCode else { return 0 }
        if canonical == wanted.canonical { return 2 }
        if wanted.components.script != nil || wanted.components.region != nil,
           language.script != wanted.language.script { return 0 }
        return 1
    }

    /// For speech the script doesn't matter, so a zh-Hant reader matches
    /// Chinese audio (Go's `AudioLanguageMatch`): the exact tag 2, the same
    /// base language 1.
    func speechMatch(_ wanted: Self) -> Int {
        guard language.languageCode == wanted.language.languageCode else { return 0 }
        return canonical == wanted.canonical ? 2 : 1
    }
}
