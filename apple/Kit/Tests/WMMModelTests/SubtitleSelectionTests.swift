import Testing
@testable import WMMModel

@Suite struct SubtitleSelectionTests {
    private func track(_ id: String, language: String?, forced: Bool = false, image: Bool = false) -> SubtitleOption {
        SubtitleOption(id: id, label: id, language: language, forced: forced, burnInOnly: image)
    }

    @Test func emptyTracksAndOffNeverSelectAnything() {
        #expect(preferredSubtitleID(tracks: [], audioLanguage: "en", preferences: .init(languages: ["en"], mode: .on)) == nil)
        #expect(preferredSubtitleID(tracks: [track("en", language: "en")], audioLanguage: "en", preferences: .init(languages: ["en"], mode: .off)) == nil)
    }

    @Test func missingLanguagesAndUnknownAudioStayOff() {
        let tracks = [track("en", language: "en"), track("forced", language: "eng", forced: true)]
        #expect(preferredSubtitleID(tracks: tracks, audioLanguage: "en", preferences: .init(mode: .on)) == nil)
        #expect(preferredSubtitleID(tracks: tracks, audioLanguage: nil, preferences: .init()) == nil)
        #expect(preferredSubtitleID(tracks: tracks, audioLanguage: "und", preferences: .init()) == nil)
        #expect(preferredSubtitleID(tracks: tracks, audioLanguage: "en", preferences: .init(languages: ["ja"], mode: .on)) == nil)
    }

    @Test func orderedLanguagesChooseTheFirstAvailableFullTrack() {
        let tracks = [track("en", language: "eng"), track("es", language: "spa")]
        #expect(preferredSubtitleID(tracks: tracks, audioLanguage: "en", preferences: .init(languages: ["es", "en"], mode: .on)) == "es")
        #expect(preferredSubtitleID(tracks: tracks, audioLanguage: "en", preferences: .init(languages: ["ja", "en", "es"], mode: .on)) == "en")
    }

    @Test func fullTracksBeatForcedFallbacksAcrossPreferredLanguages() {
        let tracks = [track("fr-forced", language: "fra", forced: true), track("es-full", language: "spa")]
        #expect(preferredSubtitleID(tracks: tracks, audioLanguage: "en", preferences: .init(languages: ["fr", "es"], mode: .on)) == "es-full")
        #expect(preferredSubtitleID(tracks: tracks, audioLanguage: "en", preferences: .init(languages: ["fr"], mode: .on)) == "fr-forced")
    }

    @Test func forcedModeFollowsSelectedAudioRatherThanPreferredLanguages() {
        let tracks = [track("en-full", language: "en"), track("en-forced", language: "eng", forced: true), track("es-forced", language: "spa", forced: true)]
        #expect(preferredSubtitleID(tracks: tracks, audioLanguage: "eng", preferences: .init(languages: ["es"], mode: .forced)) == "en-forced")
        #expect(preferredSubtitleID(tracks: tracks, audioLanguage: "es", preferences: .init(languages: ["en"], mode: .forced)) == "es-forced")
    }

    @Test func imageTracksNeverBecomeAnOfflineDefault() {
        let tracks = [track("image", language: "en", image: true), track("text", language: "eng")]
        #expect(preferredSubtitleID(tracks: tracks, audioLanguage: "en", preferences: .init(languages: ["en"], mode: .on)) == "text")
        #expect(preferredSubtitleID(tracks: [track("image", language: "en", forced: true, image: true)], audioLanguage: "en", preferences: .init()) == nil)
    }

    @Test(arguments: [("eng", "en"), ("fre", "fr"), ("fra", "fr"), ("ger", "de"), ("deu", "de"), ("chi", "zh"), ("zho", "zh"), ("dut", "nl")])
    func iso639AliasesUseFoundationCanonicalization(_ codes: (String, String)) {
        let (mediaCode, preference) = codes
        #expect(preferredSubtitleID(tracks: [track("matched", language: mediaCode)], audioLanguage: nil, preferences: .init(languages: [preference], mode: .on)) == "matched")
    }

    @Test func exactRegionWinsBeforeBaseLanguageFallback() {
        let tracks = [track("pt", language: "por"), track("pt-pt", language: "pt-PT"), track("pt-br", language: "pt-BR")]
        #expect(preferredSubtitleID(tracks: tracks, audioLanguage: nil, preferences: .init(languages: ["pt-BR"], mode: .on)) == "pt-br")
        #expect(preferredSubtitleID(tracks: [track("pt", language: "por")], audioLanguage: nil, preferences: .init(languages: ["pt-BR"], mode: .on)) == "pt")
        #expect(preferredSubtitleID(tracks: [track("en-us", language: "en-US")], audioLanguage: nil, preferences: .init(languages: ["en-GB"], mode: .on)) == "en-us")
    }

    @Test func traditionalChineseCannotSelectSimplifiedChinese() {
        let tracks = [track("simplified", language: "zh-CN"), track("traditional", language: "zh-TW")]
        #expect(preferredSubtitleID(tracks: tracks, audioLanguage: nil, preferences: .init(languages: ["zh-Hant"], mode: .on)) == "traditional")
        #expect(preferredSubtitleID(tracks: [track("simplified", language: "zh-Hans")], audioLanguage: nil, preferences: .init(languages: ["zh-TW"], mode: .on)) == nil)
        #expect(preferredSubtitleID(tracks: [track("unspecified", language: "zh")], audioLanguage: nil, preferences: .init(languages: ["zh-Hant"], mode: .on)) == nil)
        #expect(preferredSubtitleID(tracks: [track("traditional", language: "zh-Hant")], audioLanguage: nil, preferences: .init(languages: ["zh-TW"], mode: .on)) == "traditional")
    }

    @Test(arguments: [nil, "", "und", "zzzz", "not a tag", "en--US", "en-$invalid"])
    func unknownOrMalformedLanguagesNeverMatchThemselves(_ code: String?) {
        #expect(preferredSubtitleID(tracks: [track("unknown", language: code)], audioLanguage: nil, preferences: .init(languages: [code ?? ""], mode: .on)) == nil)
    }

    @Test func equivalentTracksKeepTheirExistingOrder() {
        let tracks = [track("first", language: "en"), track("second", language: "eng")]
        #expect(preferredSubtitleID(tracks: tracks, audioLanguage: nil, preferences: .init(languages: ["en"], mode: .on)) == "first")
    }
}

/// The Go tables in server/internal/playback/audio_preferences_test.go, for offline plays.
@Suite struct AudioSelectionTests {
    private static func audio(_ index: Int, _ codec: String, _ channels: Int, _ language: String, title: String? = nil, isDefault: Bool = false) -> AudioOption {
        AudioOption(index: index, label: "\(index)", language: language, isDefault: isDefault, codec: codec, channels: channels, title: title)
    }

    // The API doesn't send dispositions, so the Go table's Descriptive (7)
    // and Commentary (9) tracks carry the titles a release would.
    private static let tracks = [
        audio(1, "ac3", 6, "eng", isDefault: true),
        audio(2, "truehd", 8, "eng", title: "TrueHD Atmos"),
        audio(3, "aac", 2, "spa"),
        audio(4, "aac", 2, "spa", title: "Director's Commentary"),
        audio(5, "aac", 2, "por"),
        audio(6, "aac", 2, "pt-BR"),
        audio(7, "aac", 2, "jpn", title: "Audio Description"),
        audio(8, "aac", 2, "chi"),
        audio(9, "aac", 2, "fre", title: "Commentary"),
        audio(10, "aac", 2, "fre", title: "Director's Cut"),
        audio(11, "aac", 2, "und"),
    ]

    private static func profile(_ codecs: [String], maxChannels: Int? = nil) -> PlaybackProfile {
        PlaybackProfile(name: "test", containers: ["mp4"], videoCodecs: ["h264"], audioCodecs: codecs, maxAudioChannels: maxChannels)
    }

    private static let ac3 = profile(["aac", "ac3"])
    private static let truehd = profile(["aac", "ac3", "truehd"], maxChannels: 8)

    @Test(arguments: [
        ("no languages", [String](), ac3, nil),
        ("first language the file has", ["de", "es", "en"], ac3, 3),
        ("ISO 639-2 against BCP 47", ["en"], ac3, 1),
        ("copyable before more channels", ["en"], profile(["aac", "truehd"]), 2),
        ("default before more channels when both copy", ["en"], truehd, 1),
        ("commentary never by language", ["es"], profile(["aac"]), 3),
        ("exact region before the base language", ["pt-BR"], ac3, 6),
        ("base language when the region is missing", ["pt-PT"], ac3, 5),
        ("audio description skipped", ["ja"], ac3, nil),
        ("script does not matter for speech", ["zh-Hant"], ac3, 8),
        ("commentary skipped, Director's Cut kept", ["fr"], ac3, 10),
        ("und never matches", ["und"], ac3, nil),
    ] as [(String, [String], PlaybackProfile, Int?)])
    func preferredAudio(_ name: String, _ languages: [String], _ profile: PlaybackProfile, _ want: Int?) {
        #expect(preferredAudioIndex(tracks: Self.tracks, profile: profile, languages: languages) == want, "\(name)")
    }

    @Test func theDefaultIsTheFlaggedTrackElseTheFirst() {
        #expect(defaultAudioIndex([]) == nil)
        #expect(defaultAudioIndex([Self.audio(1, "aac", 2, "eng"), Self.audio(2, "aac", 2, "spa", isDefault: true)]) == 2)
        #expect(defaultAudioIndex([Self.audio(3, "aac", 2, "eng"), Self.audio(4, "aac", 2, "spa")]) == 3)
    }

    @Test(arguments: [
        ("eng", ["en"], [String](), true),
        ("kor", ["en"], [], false),
        ("kor", [], ["en"], false),
        ("kor", [], [], true),
        ("und", ["en"], [], true),
        ("", ["en"], [], true),
        ("spa", ["en", "es"], ["fr"], true),
        ("fra", ["en"], ["fr"], false),
        ("zho", ["zh-Hant"], [], true),
    ] as [(String, [String], [String], Bool)])
    func understood(_ audio: String, _ audioLanguages: [String], _ subtitles: [String], _ want: Bool) {
        #expect(understoodAudio(audio, audioLanguages: audioLanguages, subtitleLanguages: subtitles) == want)
    }

    private static let subtitleTracks = [
        SubtitleOption(id: "en-forced", label: "", language: "eng", forced: true, burnInOnly: false),
        SubtitleOption(id: "en-full", label: "", language: "eng", forced: false, burnInOnly: false),
        SubtitleOption(id: "es-full", label: "", language: "spa", forced: false, burnInOnly: false),
    ]

    private static let foreign = SubtitlePreferences(mode: .forced, foreignAudio: true)

    private static func with(_ p: SubtitlePreferences, _ languages: String...) -> SubtitlePreferences {
        var p = p
        p.languages = languages
        return p
    }

    @Test(arguments: [
        ("followed audio: forced only", with(foreign, "es"), "eng", ["en"], "en-forced"),
        ("foreign audio: subtitle languages", with(foreign, "es", "en"), "kor", ["en"], "es-full"),
        ("foreign audio: audio languages when no subtitle languages", foreign, "kor", ["en"], "en-full"),
        ("foreign by subtitle languages when no audio languages", with(foreign, "en"), "kor", [], "en-full"),
        ("untagged audio counts as followed", with(foreign, "en"), "und", ["en"], nil),
        ("no languages at all: forced only", foreign, "kor", [], nil),
        ("on ignores the flag", SubtitlePreferences(languages: ["en"], mode: .on, foreignAudio: true), "eng", ["en"], "en-full"),
        ("off ignores the flag", SubtitlePreferences(languages: ["en"], mode: .off, foreignAudio: true), "kor", ["en"], nil),
        ("forced without the flag stays forced", SubtitlePreferences(languages: ["en"], mode: .forced), "kor", ["en"], nil),
    ] as [(String, SubtitlePreferences, String, [String], String?)])
    func foreignAudioSubtitles(_ name: String, _ prefs: SubtitlePreferences, _ audio: String, _ audioLanguages: [String], _ want: String?) {
        #expect(preferredSubtitleID(tracks: Self.subtitleTracks, audioLanguage: audio, audioLanguages: audioLanguages, preferences: prefs) == want, "\(name)")
    }
}

@Suite struct SubtitleChoiceTests {
    @Test func choicesSaveAsAModeAndAFlag() {
        var p = SubtitlePreferences()
        #expect(p.choice == .forced)
        p.choice = .foreign
        #expect(p.mode == .forced && p.foreignAudio)
        p.choice = .on
        #expect(p.mode == .on && !p.foreignAudio)
        #expect(SubtitlePreferences(mode: .on, foreignAudio: true).choice == .on)
    }

    @Test func theFourChoicesInTheBoardsOrderAndWords() {
        #expect(SubtitleChoice.allCases.map(\.label) == ["Off", "Forced only", "When the audio isn’t in my languages", "On"])
        #expect(SubtitleChoice.foreign.shortLabel == "For foreign audio")
        #expect(SubtitleChoice.offered(apiLevel: audioPreferencesAPILevel - 1) == [.off, .forced, .on])
        #expect(SubtitleChoice.offered(apiLevel: audioPreferencesAPILevel) == SubtitleChoice.allCases)
    }

    @Test func aChangeAppliesOnlyItsFields() {
        var p = PlaybackPreferences(subtitles: SubtitlePreferences(languages: ["en"], mode: .on, automaticDownload: true), audio: AudioPreferences(languages: ["es"]))
        LanguageChange(audioLanguages: ["ja"]).apply(to: &p)
        #expect(p.audio.languages == ["ja"])
        #expect(p.subtitles == SubtitlePreferences(languages: ["en"], mode: .on, automaticDownload: true))
        #expect(LanguageChange().isEmpty)
        #expect(LanguageChange(choice: .foreign).needsAudioLanguages)
        #expect(!LanguageChange(choice: .on).needsAudioLanguages)
    }

    @Test(arguments: [
        (["en-US"], "en"), (["pt-BR", "en"], "pt-BR"), (["zh-Hant-TW"], "zh-Hant"), (["zh-Hans-CN"], "zh-Hans"),
        (["es-419"], "es"), ([], nil),
    ] as [([String], String?)])
    func theDevicesLanguageAsAListOffersIt(_ preferred: [String], _ want: String?) {
        #expect(suggestedLanguage(preferred) == want)
    }
}
