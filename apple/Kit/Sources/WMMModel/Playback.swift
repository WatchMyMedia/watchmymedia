import AVFoundation
import Foundation
import Observation

/// What to play.
public struct PlayRequest: Hashable, Sendable, Identifiable {
    public var recordingID: String?
    public var fileID: Int
    /// File position to start at; nil resumes where the user left off.
    public var startMs: Int?
    public var title: String
    /// "S03 E07 · The Long Dark".
    public var subtitle: String?
    public var backdropPath: String?
    /// An episode's still, which Now Playing shows before the backdrop.
    public var stillPath: String?
    /// The movie's or the show's poster, Now Playing's last choice.
    public var posterPath: String?
    /// The audio track to start with (ffprobe index); nil is the default.
    public var audioIndex: Int?
    /// The subtitle to start with; nil is none.
    public var subtitleID: String?
    /// A handback can preserve explicit Off instead of applying server defaults.
    public var subtitleMode: SubtitleSelectionMode
    /// This play's quality after a handover; does not change this device's default.
    public var qualityOverride: StreamQuality?
    /// Episode context for the tablet player's season list; absent for movies and unmatched files.
    public var showID: Int?
    public var seasonNumber: Int?
    /// What the page that played it already knows, for the Apple TV player's
    /// Info panel (PLAN-APPLE-REVIEW.md §6.2): the description, the year, the
    /// certification ("TV-14") and the genres. Nil or empty shows nothing.
    public var overview: String?
    public var year: Int?
    public var contentRating: String?
    public var genres: [String]

    public init(fileID: Int, startMs: Int? = nil, title: String, subtitle: String? = nil, backdropPath: String? = nil, stillPath: String? = nil, posterPath: String? = nil, audioIndex: Int? = nil, subtitleID: String? = nil, subtitleMode: SubtitleSelectionMode = .auto, qualityOverride: StreamQuality? = nil, showID: Int? = nil, seasonNumber: Int? = nil, recordingID: String? = nil,
                overview: String? = nil, year: Int? = nil, contentRating: String? = nil, genres: [String] = []) {
        self.recordingID = recordingID
        self.fileID = fileID
        self.startMs = startMs
        self.title = title
        self.subtitle = subtitle
        self.backdropPath = backdropPath
        self.stillPath = stillPath
        self.posterPath = posterPath
        self.audioIndex = audioIndex
        self.subtitleID = subtitleID
        self.subtitleMode = subtitleMode
        self.qualityOverride = qualityOverride
        self.showID = showID
        self.seasonNumber = seasonNumber
        self.overview = overview
        self.year = year
        self.contentRating = contentRating
        self.genres = genres
    }

    public var id: String { "\(recordingID ?? String(fileID))@\(startMs.map(String.init) ?? "resume")" }
}

/// A skippable part of the file. `endMs` nil runs to the end. `final` marks
/// the last credits block, after which nothing but previews follows.
public struct Segment: Hashable, Sendable {
    public var kind: SegmentKind
    public var startMs: Int
    public var endMs: Int?
    public var final: Bool

    public init(kind: SegmentKind, startMs: Int, endMs: Int?, final: Bool = false) {
        self.kind = kind
        self.startMs = startMs
        self.endMs = endMs
        self.final = final
    }
}

/// The episode after this one, for the Next Episode card.
public struct UpNext: Hashable, Sendable {
    public var fileID: Int
    public var episodeID: Int
    public var showID: Int
    public var showTitle: String
    public var season: Int
    public var episode: Int
    public var title: String
    public var stillPath: String?
    /// The episode's length; nil until looked up.
    public var runtimeMs: Int?
    /// The last episode of its season.
    public var seasonFinale: Bool

    public init(fileID: Int, episodeID: Int, showID: Int, showTitle: String, season: Int, episode: Int, title: String, stillPath: String? = nil, runtimeMs: Int? = nil, seasonFinale: Bool = false) {
        self.fileID = fileID
        self.episodeID = episodeID
        self.showID = showID
        self.showTitle = showTitle
        self.season = season
        self.episode = episode
        self.title = title
        self.stillPath = stillPath
        self.runtimeMs = runtimeMs
        self.seasonFinale = seasonFinale
    }

    /// The request that plays it from the start.
    public var playRequest: PlayRequest {
        PlayRequest(fileID: fileID, startMs: 0, title: showTitle, subtitle: "\(episodeCode(season: season, episode: episode)) · \(title)", showID: showID, seasonNumber: season)
    }
}

public struct AudioOption: Hashable, Sendable, Identifiable {
    /// ffprobe stream index, sent back as audioIndex.
    public var index: Int
    /// "English · TrueHD 7.1 (Default)".
    public var label: String
    public var language: String?
    public var isDefault: Bool
    /// ffprobe's codec ("aac", "truehd") and channels, and the track's
    /// title, which offline plays choose by (``preferredAudioIndex(tracks:profile:languages:)``).
    public var codec: String?
    public var channels: Int?
    public var title: String?

    public init(index: Int, label: String, language: String?, isDefault: Bool, codec: String? = nil, channels: Int? = nil, title: String? = nil) {
        self.index = index
        self.label = label
        self.language = language
        self.isDefault = isDefault
        self.codec = codec
        self.channels = channels
        self.title = title
    }

    public var id: Int { index }
}

/// Where a subtitle track comes from: the file itself, a file next to it, or OpenSubtitles.
public enum SubtitleSource: String, Hashable, Sendable { case embedded, sidecar, online }

public struct SubtitleOption: Hashable, Sendable, Identifiable {
    /// "s3" (embedded), "x0" (sidecar file) or "d12" (downloaded from OpenSubtitles).
    public var id: String
    public var label: String
    public var language: String?
    public var forced: Bool
    /// Image subtitles (PGS) are burned in, which restarts the stream.
    public var burnInOnly: Bool
    /// "SRT", "ASS", "PGS"; nil when unknown.
    public var format: String?
    public var source: SubtitleSource
    /// A download's release name ("Harbor.Lights.S03E07.1080p.WEB").
    public var release: String?
    public var hearingImpaired: Bool

    public init(id: String, label: String, language: String?, forced: Bool = false, burnInOnly: Bool = false, format: String? = nil, source: SubtitleSource = .embedded, release: String? = nil, hearingImpaired: Bool = false) {
        self.id = id
        self.label = label
        self.language = language
        self.forced = forced
        self.burnInOnly = burnInOnly
        self.format = format
        self.source = source
        self.release = release
        self.hearingImpaired = hearingImpaired
    }

    /// The picker's second line: "SRT", or "PGS · burned in, restarts playback".
    public var detail: String? {
        if burnInOnly { return [format, "burned in, restarts playback"].compactMap { $0 }.joined(separator: " · ") }
        if source == .online { return [release, hearingImpaired ? "HI" : nil].compactMap { $0 }.joined(separator: " · ") }
        return format
    }
}

/// What the server must show where subtitles from OpenSubtitles are picked, when it does not say.
public let openSubtitlesCredit = "Subtitles provided by OpenSubtitles.com"

/// A subtitle found online for the file playing, not downloaded yet unless `trackID` is set.
public struct OnlineSubtitle: Hashable, Sendable, Identifiable {
    public var id: String
    public var language: String?
    /// "English", "Portuguese (Brazil)".
    public var label: String
    public var release: String
    /// Made for this exact file, so in sync.
    public var hashMatch: Bool
    public var hearingImpaired: Bool
    public var forced: Bool
    public var aiTranslated: Bool
    public var downloads: Int
    /// The subtitle track to select when it was downloaded for this file before.
    public var trackID: String?

    public init(id: String, language: String?, label: String, release: String, hashMatch: Bool, hearingImpaired: Bool, forced: Bool, aiTranslated: Bool, downloads: Int, trackID: String? = nil) {
        self.id = id
        self.language = language
        self.label = label
        self.release = release
        self.hashMatch = hashMatch
        self.hearingImpaired = hearingImpaired
        self.forced = forced
        self.aiTranslated = aiTranslated
        self.downloads = downloads
        self.trackID = trackID
    }
}

/// The online subtitles for the file playing, and what this player is doing with them.
public struct OnlineSubtitles: Hashable, Sendable {
    public var results: [OnlineSubtitle]
    /// The credit to show under them.
    public var attribution: String
    /// This person may download (a server can keep downloads to admins).
    public var canDownload: Bool
    /// Downloads left today; nil when not known.
    public var remaining: Int?
    /// When downloads resume; nil when not known.
    public var resetAt: Date?
    /// The result being downloaded.
    public var downloading: String?
    /// Why the search or the last download failed.
    public var error: String?

    public init(results: [OnlineSubtitle] = [], attribution: String = openSubtitlesCredit, canDownload: Bool = true, remaining: Int? = nil, resetAt: Date? = nil, downloading: String? = nil, error: String? = nil) {
        self.results = results
        self.attribution = attribution
        self.canDownload = canDownload
        self.remaining = remaining
        self.resetAt = resetAt
        self.downloading = downloading
        self.error = error
    }
}

/// How the server is sending the file.
public enum PlaybackMode: String, Hashable, Sendable {
    case direct, remux, audioTranscode = "audio-transcode", transcode

    public var label: String {
        switch self {
        case .direct: "Direct play"
        case .remux: "Remux"
        case .audioTranscode: "Audio transcode"
        case .transcode: "Transcode"
        }
    }
}

/// The video as the server sends it.
public struct StreamVideo: Hashable, Sendable {
    public var width: Int
    public var height: Int
    /// "HDR10", "HLG", "Dolby Vision"; nil for SDR, or HDR mapped to SDR.
    public var hdr: String?

    public init(width: Int, height: Int, hdr: String?) {
        self.width = width
        self.height = height
        self.hdr = hdr
    }

    public var quality: QualityLabel { qualityFor(width: width, height: height) }
}

/// The server's labels for a frame (4K from 2000 lines, 1080p from 1000, 720p
/// from 700), except that a wide one counts by its width.
public func qualityFor(width: Int, height: Int) -> QualityLabel {
    if height >= 2000 || width >= 3200 { return "4K" }
    if height >= 1000 || width >= 1800 { return "1080p" }
    if height >= 700 || width >= 1200 { return "720p" }
    return "SD"
}

public enum PlayerStatus: String, Hashable, Sendable { case loading, playing, paused, buffering, ended, error }

/// How long auto-delay fills the Skip button before skipping.
public let skipAutoDelayMs = 3_000

/// The Skip button: the segment offered and, in auto-delay mode, how far its 3 s fill has run (0–1).
public struct SkipPrompt: Hashable, Sendable {
    public var segment: Segment
    public var label: String
    public var autoProgress: Double?

    public init(segment: Segment, label: String, autoProgress: Double? = nil) {
        self.segment = segment
        self.label = label
        self.autoProgress = autoProgress
    }
}

/// The Next Episode card's countdown.
public let upNextCountdownMs = 10_000

/// The Next Episode card. `countdownMs` counts down from `totalMs` and holds
/// while paused; nil means autoplay is off and the card waits for a choice.
public struct UpNextPrompt: Hashable, Sendable {
    public var next: UpNext
    public var countdownMs: Int?
    public var totalMs: Int

    public init(next: UpNext, countdownMs: Int?, totalMs: Int = upNextCountdownMs) {
        self.next = next
        self.countdownMs = countdownMs
        self.totalMs = totalMs
    }

    /// How far the countdown has run, 0–1; 0 when autoplay is off.
    public var countdownProgress: Double {
        guard let countdownMs else { return 0 }
        return min(max(1 - Double(countdownMs) / Double(max(totalMs, 1)), 0), 1)
    }

    /// Whole seconds left, rounded up, for "Next episode in 7".
    public var secondsLeft: Int? { countdownMs.map { ($0 + 999) / 1000 } }
}

/// A subtitle cue on screen now, in file time.
public struct SubtitleCue: Hashable, Sendable {
    public var startMs: Int
    public var endMs: Int
    /// Plain text; lines separated by "\n".
    public var text: String

    public init(startMs: Int, endMs: Int, text: String) {
        self.startMs = startMs
        self.endMs = endMs
        self.text = text
    }
}

public struct PlayerState: Hashable, Sendable {
    public var status: PlayerStatus = .loading
    /// Playing a fixed copy on this device; streaming quality cannot change it.
    public var isOffline: Bool = false
    public var title: String = ""
    public var subtitle: String?
    public var backdropPath: String?
    /// The request's still and poster, so a play started again (after Next Episode) keeps its Now Playing picture.
    public var stillPath: String?
    public var posterPath: String?
    /// File position (not the HLS session's), ms.
    public var positionMs: Int = 0
    /// A recording still recording; nil once it reaches its end or its stream ends,
    /// when the Recording label and Go to now go and the play carries on.
    public var growing: GrowingRecording?
    /// A growing recording leaves the unrecorded remainder visible on the scrubber.
    public var timelineDurationMs: Int { growing?.plannedDurationMs ?? durationMs }
    /// Go to now shows only when the play is more than 10 s behind what has been recorded.
    public var showsGoToNow: Bool { growing != nil && durationMs - positionMs > Self.goToNowBehindMs }
    public static let goToNowBehindMs = 10_000
    public var durationMs: Int = 0
    /// How far ahead of `positionMs` is buffered.
    public var bufferedMs: Int = 0
    /// Drawn as faint bands on the scrubber (intro, credits…).
    public var segments: [Segment] = []
    public var skip: SkipPrompt?
    /// The episode after this one, from the start (the card shows only near the end).
    public var nextEpisode: UpNext?
    public var upNext: UpNextPrompt?
    /// Ask "Are you still watching?" before auto-advancing.
    public var askStillWatching: Bool = false
    public var audio: [AudioOption] = []
    public var selectedAudio: Int?
    public var subtitles: [SubtitleOption] = []
    /// Nil: subtitles off.
    public var selectedSubtitle: String?
    /// The selected text subtitle's cues on screen at `positionMs`, drawn over the video.
    public var cues: [SubtitleCue] = []
    /// Subtitles found online; nil while not known, or when the server has no provider.
    public var online: OnlineSubtitles?
    /// The quality the viewer chose, as this server plays it (Automatic is Original on an older one).
    public var quality: StreamQuality = .original
    /// The qualities to offer: Automatic only where the server can pick the step.
    public var qualities: [StreamQuality] = StreamQuality.offered(apiLevel: nil)
    /// At Automatic, the step the server sends now; nil until it says.
    public var autoStep: StreamQuality?
    /// A short note while Automatic lowers the quality, or once it cannot
    /// go lower ("Lowered to 720p for your connection."); nil when none shows.
    public var qualityNote: String?
    public var mode: PlaybackMode?
    /// What the server is sending, for the stream line ("Direct play · 4K HDR"); nil until it answers.
    public var video: StreamVideo?
    /// Why the server is not playing the original as is.
    public var reasons: [String] = []
    /// The file's seek thumbnails, drawn over the scrubber while it is dragged; nil when the server has none.
    public var trickplay: Trickplay?
    /// An admin stopped this play; show the message, then leave.
    public var stopMessage: String?
    public var error: String?

    public init() {}
}

public enum PlayerEvent: Hashable, Sendable {
    /// Leave the player (the file ended without a next episode, a skip reached the end, or the play was stopped).
    case exit
    /// The next episode started playing in place.
    case startedNext(fileID: Int)
}

/// One viewing at a time: asks the server how to play, drives the player,
/// reports progress and runs Skip, Next Episode and Still watching
/// (PLAN-CLIENTS.md §2.3). The screen renders `state`, shows `player`'s
/// video, and calls the methods below.
@MainActor
public protocol PlaybackController: AnyObject, Observable {
    var state: PlayerState { get }

    /// Exit and next-episode events, for the player screen (one listener).
    var events: AsyncStream<PlayerEvent> { get }

    /// The player to render video from: nil in fakes and until `start`, and again after `release`.
    var player: AVPlayer? { get }

    func start(_ request: PlayRequest)

    func play()

    func pause()

    func togglePlayPause()

    /// Seeks to a file position.
    func seek(toMs positionMs: Int)

    func seek(byMs deltaMs: Int)

    /// The viewer moved the player's own timeline to `playerMs`, player time
    /// (the tvOS transport bar seeks the AVPlayer itself): it counts as a seek
    /// for Skip, the Next Episode card and Still watching, without seeking again.
    func noteSeek(playerMs: Int)

    /// Skips the segment the Skip button offers.
    func skip()

    /// Plays ``PlayerState/nextEpisode`` now, with or without the card.
    func playNext()

    /// Hides the Next Episode card until the end, or a seek back before it.
    func watchCredits()

    func answerStillWatching(keepWatching: Bool)

    func selectAudio(_ index: Int)

    /// Nil turns subtitles off.
    func selectSubtitle(_ id: String?)

    /// Downloads an online result and turns it on; one downloaded before is just turned on.
    func downloadSubtitle(_ resultID: String)

    func selectQuality(_ quality: StreamQuality)

    /// Any tap or seek: resets the Still watching counters.
    func noteUserInput()

    /// The viewer left the player: reports "stopped" and releases the session.
    func stop()

    /// The app went to the background or is closing: reports "closed".
    func close()

    func release()
}

@MainActor
public protocol PlaybackControllerFactory: AnyObject {
    func make() -> any PlaybackController

    /// How each file would play on this device at the chosen quality, for
    /// version lists ("Direct play", "Remux to fMP4"), by file ID. Starts
    /// nothing; empty when unknown.
    func plans(fileIDs: [Int]) async -> [Int: PlaybackPlan]
}
