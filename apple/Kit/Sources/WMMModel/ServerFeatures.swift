import Foundation

/// What a server at an API level offers, by name, so a check says what it
/// gates rather than a number (PLAN-APPLE-REVIEW.md §5.6; Android's
/// `ServerFeatures`, named alike). The levels are the server's (`APILevel` in
/// server/internal/api/system.go, whose comment says what each one brought):
/// a feature is offered from its level and hidden below it. A feature that
/// also needs a capability the server reports (Live TV listings, recording)
/// checks that beside this.
///
/// ```swift
/// guard server.features.rows else { return .unavailable }
/// ```
public struct ServerFeatures: Hashable, Sendable {
    /// The level each feature starts at: the one table of levels.
    public enum Level {
        /// The lowest level this app works with; servers below it are refused (GET /api/server).
        public static let required = 1
        /// Cast tokens and castAppId.
        public static let castTokens = 2
        /// Automatic quality: the server picks the step.
        public static let automaticQuality = 3
        /// Requests, problem reports and missing episodes' state.
        public static let requests = 5
        /// Deleting from the apps.
        public static let delete = 6
        /// Profile.hdrTypes and the dovi keys; HDR copied only to a screen that shows it (PLAN-HDR-PROFILE.md).
        public static let hdrProfile = 8
        /// Seek thumbnails (PLAN-TRICKPLAY.md).
        public static let trickplay = 9
        /// Offline downloads (PLAN-DOWNLOADS.md).
        public static let offline = 10
        /// Live TV.
        public static let liveTV = 11
        /// Live TV listings (PLAN-LIVE-TV-GUIDE.md).
        public static let liveGuide = 13
        /// Casting Live TV.
        public static let liveCast = 14
        /// Live TV favourites, listings search and reminders (the Live TV extras).
        public static let livePersonal = 15
        /// Per-person subtitle preferences and the automatic subtitle download.
        public static let subtitlePreferences = 18
        /// Recordings (DVR) and growing playback.
        public static let liveRecordings = 19
        /// Movie trailers (PLAN-TRAILERS.md).
        public static let trailers = 20
        /// Audio languages, the foreign-audio subtitle choice and Profile.selectsAudioTrack (PLAN-LANGUAGES.md).
        public static let audioLanguages = 21
        /// Rows people arrange, and lists (PLAN-ROWS.md).
        public static let rows = 22
        /// Opt-in Automatic recovery, the transfer's age and acknowledged quality switches.
        public static let autoRecovery = 24
        /// Live TV device profiles.
        public static let liveProfile = 25
        /// Opt-in Live TV Automatic.
        public static let liveAutomatic = 26
        /// A play's decision carries its media token (`Decision.mediaToken`), and the
        /// Live TV status call waits while a channel tunes (PLAN-APPLE-REVIEW.md §4.2).
        public static let tokenWithDecision = 28
    }

    /// The server's API level; 0 when it isn't known.
    public let apiLevel: Int

    /// - Parameter apiLevel: the server's level; nil (no server, or not known yet) offers nothing.
    public init(apiLevel: Int?) {
        self.apiLevel = apiLevel ?? 0
    }

    /// The level this app needs: below it the server is refused.
    public var supported: Bool { apiLevel >= Level.required }
    /// 2: cast tokens.
    public var castTokens: Bool { apiLevel >= Level.castTokens }
    /// 3: Automatic quality.
    public var automaticQuality: Bool { apiLevel >= Level.automaticQuality }
    /// 5: requests, problem reports and missing episodes' state.
    public var requests: Bool { apiLevel >= Level.requests }
    /// 6: deleting from the apps.
    public var delete: Bool { apiLevel >= Level.delete }
    /// 8: Profile.hdrTypes and the dovi keys.
    public var hdrProfile: Bool { apiLevel >= Level.hdrProfile }
    /// 9: seek thumbnails.
    public var trickplay: Bool { apiLevel >= Level.trickplay }
    /// 10: offline downloads.
    public var offline: Bool { apiLevel >= Level.offline }
    /// 11: Live TV.
    public var liveTV: Bool { apiLevel >= Level.liveTV }
    /// 13: Live TV listings (with `capabilities.guide`).
    public var liveGuide: Bool { apiLevel >= Level.liveGuide }
    /// 14: casting Live TV.
    public var liveCast: Bool { apiLevel >= Level.liveCast }
    /// 15: Live TV favourites, listings search and reminders.
    public var livePersonal: Bool { apiLevel >= Level.livePersonal }
    /// 18: per-person subtitle preferences.
    public var subtitlePreferences: Bool { apiLevel >= Level.subtitlePreferences }
    /// 19: recordings (DVR) and growing playback.
    public var liveRecordings: Bool { apiLevel >= Level.liveRecordings }
    /// 20: movie trailers.
    public var trailers: Bool { apiLevel >= Level.trailers }
    /// 21: audio languages.
    public var audioLanguages: Bool { apiLevel >= Level.audioLanguages }
    /// 22: rows people arrange, and lists.
    public var rows: Bool { apiLevel >= Level.rows }
    /// 24: opt-in Automatic recovery and acknowledged quality switches.
    public var autoRecovery: Bool { apiLevel >= Level.autoRecovery }
    /// 25: Live TV device profiles.
    public var liveProfile: Bool { apiLevel >= Level.liveProfile }
    /// 26: opt-in Live TV Automatic.
    public var liveAutomatic: Bool { apiLevel >= Level.liveAutomatic }
    /// 28: a play's decision carries its media token, so the player needn't ask for one.
    public var tokenWithDecision: Bool { apiLevel >= Level.tokenWithDecision }
    /// 28: the Live TV status call waits while the channel tunes, so the player needn't poll.
    public var liveStatusWaits: Bool { apiLevel >= Level.tokenWithDecision }
}

extension Server {
    /// What this server offers at its API level as last refreshed.
    public var features: ServerFeatures { ServerFeatures(apiLevel: apiLevel) }
}

// The levels under the names the code outside WMMData and WMMModel still
// compares (the apps' fake launch arguments, WMMCast, WMMDownloads and their
// tests). New checks use ``ServerFeatures``; these read the table above.

/// ``ServerFeatures/Level/required``.
public let requiredAPILevel = ServerFeatures.Level.required
/// ``ServerFeatures/Level/castTokens``.
public let castAPILevel = ServerFeatures.Level.castTokens
/// ``ServerFeatures/Level/automaticQuality``.
public let autoQualityAPILevel = ServerFeatures.Level.automaticQuality
/// ``ServerFeatures/Level/requests``.
public let requestsAPILevel = ServerFeatures.Level.requests
/// ``ServerFeatures/Level/delete``.
public let deleteAPILevel = ServerFeatures.Level.delete
/// ``ServerFeatures/Level/hdrProfile``.
public let hdrProfileAPILevel = ServerFeatures.Level.hdrProfile
/// ``ServerFeatures/Level/trickplay``.
public let trickplayAPILevel = ServerFeatures.Level.trickplay
/// ``ServerFeatures/Level/offline``.
public let offlineAPILevel = ServerFeatures.Level.offline
/// ``ServerFeatures/Level/liveTV``.
public let liveTVAPILevel = ServerFeatures.Level.liveTV
/// ``ServerFeatures/Level/liveGuide``.
public let liveGuideAPILevel = ServerFeatures.Level.liveGuide
/// ``ServerFeatures/Level/liveCast``.
public let liveCastAPILevel = ServerFeatures.Level.liveCast
/// ``ServerFeatures/Level/livePersonal``.
public let liveExtrasAPILevel = ServerFeatures.Level.livePersonal
/// ``ServerFeatures/Level/subtitlePreferences``.
public let subtitlePreferencesAPILevel = ServerFeatures.Level.subtitlePreferences
/// ``ServerFeatures/Level/liveRecordings``.
public let liveRecordingAPILevel = ServerFeatures.Level.liveRecordings
/// ``ServerFeatures/Level/audioLanguages``.
public let audioPreferencesAPILevel = ServerFeatures.Level.audioLanguages
/// ``ServerFeatures/Level/rows``.
public let rowsAPILevel = ServerFeatures.Level.rows
/// ``ServerFeatures/Level/liveProfile``.
public let liveProfileAPILevel = ServerFeatures.Level.liveProfile
/// ``ServerFeatures/Level/liveAutomatic``.
public let liveAutomaticAPILevel = ServerFeatures.Level.liveAutomatic
/// ``ServerFeatures/Level/tokenWithDecision``.
public let decisionTokenAPILevel = ServerFeatures.Level.tokenWithDecision
