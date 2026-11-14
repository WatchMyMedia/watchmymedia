import Foundation
import Observation

public enum SegmentKind: String, CaseIterable, Hashable, Sendable {
    case intro, recap, credits, preview, commercial

    /// "Skip Intro", the button's label.
    public var skipLabel: String {
        switch self {
        case .intro: "Skip Intro"
        case .recap: "Skip Recap"
        case .credits: "Skip Credits"
        case .preview: "Skip Preview"
        case .commercial: "Skip Ad"
        }
    }

    /// "Intro", the Settings row.
    public var settingsLabel: String {
        switch self {
        case .intro: "Intro"
        case .recap: "Recap"
        case .credits: "Credits"
        case .preview: "Previews"
        case .commercial: "Ads"
        }
    }
}

/// How a kind of segment is handled: no button, a Skip button, skip at once, or skip after 3 s.
public enum SkipMode: String, CaseIterable, Hashable, Sendable {
    case none, ask, auto, autoDelay = "auto-delay"

    public var label: String {
        switch self {
        case .none: "Never"
        case .ask: "Ask"
        case .auto: "Auto"
        case .autoDelay: "Auto after 3 s"
        }
    }
}

/// Per-user playback settings, kept on the server (GET/PUT /api/me/preferences).
public struct PlaybackPreferences: Hashable, Sendable {
    public var skip: [SegmentKind: SkipMode]
    public var autoplayNext: Bool
    public var subtitles: SubtitlePreferences
    public var audio: AudioPreferences

    public init(skip: [SegmentKind: SkipMode] = PlaybackPreferences.defaultSkip, autoplayNext: Bool = true, subtitles: SubtitlePreferences = SubtitlePreferences(), audio: AudioPreferences = AudioPreferences()) {
        self.skip = skip
        self.autoplayNext = autoplayNext
        self.subtitles = subtitles
        self.audio = audio
    }

    public func skipMode(_ kind: SegmentKind) -> SkipMode {
        skip[kind] ?? Self.defaultSkip[kind] ?? .none
    }

    public static let defaultSkip: [SegmentKind: SkipMode] = [
        .intro: .ask, .recap: .ask, .credits: .ask, .preview: .none, .commercial: .ask,
    ]
}

/// Per-person audio languages, from API level 21 (PLAN-LANGUAGES.md): a
/// play starts on the best track in the first of them the file has.
public struct AudioPreferences: Hashable, Sendable {
    /// Ordered BCP 47 tags; empty plays the file's default track.
    public var languages: [String]

    public init(languages: [String] = []) {
        self.languages = languages
    }
}

/// Per-person subtitle choices, shared by clients from API level 18.
public struct SubtitlePreferences: Hashable, Sendable {
    public var languages: [String]
    public var mode: SubtitleMode
    /// With ``SubtitleMode/forced``: full subtitles when the audio isn't in
    /// the person's languages (API level 21). A flag beside the mode, so
    /// older apps read and save Forced only and leave it alone.
    public var foreignAudio: Bool
    public var automaticDownload: Bool

    public init(languages: [String] = [], mode: SubtitleMode = .forced, foreignAudio: Bool = false, automaticDownload: Bool = false) {
        self.languages = languages
        self.mode = mode
        self.foreignAudio = foreignAudio
        self.automaticDownload = automaticDownload
    }

    /// How subtitles start, as the settings pages list it.
    public var choice: SubtitleChoice {
        get {
            switch mode {
            case .off: .off
            case .on: .on
            case .forced: foreignAudio ? .foreign : .forced
            }
        }
        set {
            mode = newValue.mode
            foreignAudio = newValue.foreignAudio
        }
    }
}

public enum SubtitleMode: String, CaseIterable, Hashable, Sendable {
    case off, forced, on
}

/// How subtitles start (PLAN-LANGUAGES.md §5.6). ``foreign`` is saved as
/// mode forced with foreignAudio.
public enum SubtitleChoice: String, CaseIterable, Hashable, Sendable {
    case off, forced, foreign, on

    public var label: String {
        switch self {
        case .off: "Off"
        case .forced: "Forced only"
        case .foreign: "When the audio isn’t in my languages"
        case .on: "On"
        }
    }

    /// The Settings row's value: "For foreign audio".
    public var shortLabel: String {
        switch self {
        case .foreign: "For foreign audio"
        default: label
        }
    }

    public var line: String {
        switch self {
        case .off: "Start without subtitles."
        case .forced: "Only parts the file marks as forced, such as a line in another language."
        case .foreign: "Forced only when you follow the audio. Full subtitles when it isn’t in your audio languages."
        case .on: "Always start with a subtitle in your first language that has one."
        }
    }

    public var mode: SubtitleMode {
        switch self {
        case .off: .off
        case .forced, .foreign: .forced
        case .on: .on
        }
    }

    public var foreignAudio: Bool { self == .foreign }

    /// Can start full subtitles, so Automatic download has something to do.
    public var startsSubtitles: Bool { self == .on || self == .foreign }

    /// The choices a server at `apiLevel` keeps: the foreign-audio one from ``audioPreferencesAPILevel``.
    public static func offered(apiLevel: Int?) -> [SubtitleChoice] {
        ServerFeatures(apiLevel: apiLevel).audioLanguages ? allCases : allCases.filter { $0 != .foreign }
    }
}

/// A change on the Audio & subtitles page: only the fields that change.
/// The server reads a subtitle mode in a save as the person choosing one,
/// and gives someone who saves their first audio language without ever
/// having chosen subtitles for foreign audio (PLAN-LANGUAGES.md §5.3), so a
/// save never sends what the person didn't touch.
public struct LanguageChange: Hashable, Sendable {
    public var audioLanguages: [String]?
    public var subtitleLanguages: [String]?
    public var choice: SubtitleChoice?
    public var automaticDownload: Bool?

    public init(audioLanguages: [String]? = nil, subtitleLanguages: [String]? = nil, choice: SubtitleChoice? = nil, automaticDownload: Bool? = nil) {
        self.audioLanguages = audioLanguages
        self.subtitleLanguages = subtitleLanguages
        self.choice = choice
        self.automaticDownload = automaticDownload
    }

    public var isEmpty: Bool { audioLanguages == nil && subtitleLanguages == nil && choice == nil && automaticDownload == nil }

    /// Needs a server at ``audioPreferencesAPILevel``.
    public var needsAudioLanguages: Bool { audioLanguages != nil || choice == .foreign }

    public func apply(to p: inout PlaybackPreferences) {
        if let audioLanguages { p.audio.languages = audioLanguages }
        if let subtitleLanguages { p.subtitles.languages = subtitleLanguages }
        if let choice { p.subtitles.choice = choice }
        if let automaticDownload { p.subtitles.automaticDownload = automaticDownload }
    }
}

/// Streaming quality, remembered per device. The caps narrow the device's
/// profile. Automatic has none: the server picks the step and lowers it when
/// the connection cannot keep up (PLAN-AUTO-QUALITY.md).
public enum StreamQuality: String, CaseIterable, Hashable, Sendable {
    case auto, original, uhd = "2160", fullHD = "1080", hd = "720", sd = "480", low = "360"

    public var label: String {
        switch self {
        case .auto: "Automatic"
        case .original: "Original quality"
        case .uhd: "4K · 20 Mbps"
        case .fullHD: "1080p · 8 Mbps"
        case .hd: "720p · 4 Mbps"
        case .sd: "480p · 2 Mbps"
        case .low: "360p · 1 Mbps"
        }
    }

    /// The picker's two lines: "1080p" and "8 Mbps".
    public var shortLabel: String {
        switch self {
        case .auto: "Automatic"
        case .original: "Original"
        case .uhd: "4K"
        case .fullHD: "1080p"
        case .hd: "720p"
        case .sd: "480p"
        case .low: "360p"
        }
    }

    public var detail: String? {
        switch self {
        case .auto, .original: nil
        case .uhd: "20 Mbps"
        case .fullHD: "8 Mbps"
        case .hd: "4 Mbps"
        case .sd: "2 Mbps"
        case .low: "1 Mbps"
        }
    }

    public var maxWidth: Int? {
        switch self {
        case .auto, .original: nil
        case .uhd: 3840
        case .fullHD: 1920
        case .hd: 1280
        case .sd: 854
        case .low: 640
        }
    }

    public var maxHeight: Int? {
        switch self {
        case .auto, .original: nil
        case .uhd: 2160
        case .fullHD: 1080
        case .hd: 720
        case .sd: 480
        case .low: 360
        }
    }

    /// Bits per second.
    public var maxBitrate: Int? {
        switch self {
        case .auto, .original: nil
        case .uhd: 20_000_000
        case .fullHD: 8_000_000
        case .hd: 4_000_000
        case .sd: 2_000_000
        case .low: 1_000_000
        }
    }

    /// The lowest step, where Automatic stops lowering.
    public static let lowest = StreamQuality.low

    /// What a server at `apiLevel` can be asked for: Automatic only from
    /// ``autoQualityAPILevel``; nil is a server not known.
    public static func offered(apiLevel: Int?) -> [StreamQuality] {
        supportsAuto(apiLevel) ? allCases : allCases.filter { $0 != .auto }
    }

    /// This quality as a server at `apiLevel` plays it: a saved Automatic is
    /// Original on a server that cannot pick the step.
    public func playable(apiLevel: Int?) -> StreamQuality {
        self == .auto && !Self.supportsAuto(apiLevel) ? .original : self
    }

    private static func supportsAuto(_ apiLevel: Int?) -> Bool { ServerFeatures(apiLevel: apiLevel).automaticQuality }
}

@MainActor
public protocol PreferencesRepository: AnyObject, Observable {
    /// The signed-in user's playback settings; defaults until loaded.
    var playback: PlaybackPreferences { get }

    /// Loads the settings from the server. Throws ``APIError``.
    func refresh() async throws

    func setSkip(_ kind: SegmentKind, mode: SkipMode) async throws

    func setAutoplayNext(_ enabled: Bool) async throws

    /// Saves an Audio & subtitles change. The reply can turn on more than
    /// was sent (the foreign-audio choice, §5.3), so read ``playback`` after.
    func setLanguages(_ change: LanguageChange) async throws

    /// This device's streaming quality (kept on the device).
    var quality: StreamQuality { get }

    func setQuality(_ quality: StreamQuality)

    /// Whether a show's page lists its missing episodes too (Show missing),
    /// remembered per show on this device.
    func showsMissing(showID: Int) -> Bool

    func setShowsMissing(_ on: Bool, showID: Int)
}

extension PreferencesRepository {
    public func setLanguages(_ change: LanguageChange) async throws {
        throw APIError(status: nil, message: "This server doesn’t support subtitle preferences yet.")
    }
    /// Remembers nothing: Show missing starts off.
    public func showsMissing(showID: Int) -> Bool { false }

    public func setShowsMissing(_ on: Bool, showID: Int) {}
}
