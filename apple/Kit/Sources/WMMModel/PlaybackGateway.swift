import Foundation

/// The API level that reads ``PlaybackProfile/hdrTypes`` and the dovi keys,
/// and copies HDR only to a screen that shows it (PLAN-HDR-PROFILE.md).
public let hdrProfileAPILevel = 8

/// What this device can play, sent with every playback request
/// (PLAN-CLIENTS.md §4.4; the server's `Profile`).
public struct PlaybackProfile: Hashable, Sendable {
    public var name: String
    /// mp4, mov.
    public var containers: [String]
    /// h264, hevc, hevc10, av1, and dovi5, dovi8: the Dolby Vision profiles
    /// the device decodes and its screen shows.
    public var videoCodecs: [String]
    /// aac, mp3, flac, alac, ac3, eac3.
    public var audioCodecs: [String]
    public var maxWidth: Int?
    public var maxHeight: Int?
    /// Bits per second; nil is no limit.
    public var maxBitrate: Int?
    /// Nil is any.
    public var maxAudioChannels: Int?
    public var hdr: Bool
    /// webvtt.
    public var subtitleFormats: [String]
    /// The HDR the screen shows: hdr10, hlg, dovi. Sent, even empty, it tells
    /// the server to copy HDR only when its kind is listed; nil for a server
    /// before ``hdrProfileAPILevel``, which refuses it (``forAPILevel(_:)``).
    public var hdrTypes: [String]?
    /// The player chooses among a direct-play file's audio tracks itself and
    /// starts on the decision's, so a track other than the file's default
    /// stays direct play (PLAN-LANGUAGES.md §5.2). Nil for a server before
    /// ``audioPreferencesAPILevel``, which refuses it.
    public var selectsAudioTrack: Bool?

    public init(name: String, containers: [String], videoCodecs: [String], audioCodecs: [String], maxWidth: Int? = nil, maxHeight: Int? = nil, maxBitrate: Int? = nil, maxAudioChannels: Int? = nil, hdr: Bool = false, subtitleFormats: [String] = ["webvtt"], hdrTypes: [String]? = nil, selectsAudioTrack: Bool? = nil) {
        self.name = name
        self.containers = containers
        self.videoCodecs = videoCodecs
        self.audioCodecs = audioCodecs
        self.maxWidth = maxWidth
        self.maxHeight = maxHeight
        self.maxBitrate = maxBitrate
        self.maxAudioChannels = maxAudioChannels
        self.hdr = hdr
        self.subtitleFormats = subtitleFormats
        self.hdrTypes = hdrTypes
        self.selectsAudioTrack = selectsAudioTrack
    }

    /// The profile for a server at `apiLevel` (nil: not known): no
    /// ``selectsAudioTrack`` below ``audioPreferencesAPILevel``. Below
    /// ``hdrProfileAPILevel`` it is the one the apps sent before: no
    /// ``hdrTypes``, which such a server refuses, no dovi keys, and hevc10
    /// only with ``hdr`` (`AVPlayer.eligibleForHDRPlayback`), since such a
    /// server copies 10-bit HEVC to any profile that has it, HDR or not
    /// (android/core/model's `PlaybackProfile.forApiLevel`).
    public func forAPILevel(_ apiLevel: Int?) -> PlaybackProfile {
        var p = self
        if (apiLevel ?? 0) < audioPreferencesAPILevel { p.selectsAudioTrack = nil }
        guard (apiLevel ?? 0) < hdrProfileAPILevel, hdrTypes != nil else { return p }
        p.videoCodecs = videoCodecs.filter { !$0.hasPrefix("dovi") && ($0 != "hevc10" || hdr) }
        p.hdrTypes = nil
        return p
    }

    /// This profile narrowed by a quality's caps.
    public func capped(by quality: StreamQuality) -> PlaybackProfile {
        var p = self
        if let w = quality.maxWidth { p.maxWidth = min(p.maxWidth ?? w, w) }
        if let h = quality.maxHeight { p.maxHeight = min(p.maxHeight ?? h, h) }
        if let b = quality.maxBitrate { p.maxBitrate = min(p.maxBitrate ?? b, b) }
        return p
    }
}

/// A request for POST /api/files/{id}/playback.
public struct DecisionRequest: Hashable, Sendable {
    public var recordingID: String?
    public var fileID: Int
    public var profile: PlaybackProfile
    public var client: ClientInfo
    /// Where the server starts ffmpeg; with VOD the player still seeks itself.
    public var startMs: Int?
    public var audioIndex: Int?
    public var subtitleID: String?
    /// Automatic preference selection or an explicit Off. Sent only at API 18+.
    public var subtitleMode: SubtitleSelectionMode
    public var burnIn: Bool
    public var quality: StreamQuality
    /// The play this request continues (track or quality change); nil starts a new one.
    public var playID: String?
    /// Ask for a VOD playlist (the whole file); the server may still answer with an event playlist.
    public var vod: Bool
    /// API 24: opt in only for Automatic file playback.
    public var autoRecovery: Bool
    /// The quality directive accepted by this decision, when any.
    public var switchGeneration: Int64?

    public init(fileID: Int, profile: PlaybackProfile, client: ClientInfo, startMs: Int? = nil, audioIndex: Int? = nil, subtitleID: String? = nil, subtitleMode: SubtitleSelectionMode = .auto, burnIn: Bool = false, quality: StreamQuality = .original, playID: String? = nil, vod: Bool = true, recordingID: String? = nil, autoRecovery: Bool = false, switchGeneration: Int64? = nil) {
        self.recordingID = recordingID
        self.fileID = fileID
        self.profile = profile
        self.client = client
        self.startMs = startMs
        self.audioIndex = audioIndex
        self.subtitleID = subtitleID
        self.subtitleMode = subtitleMode
        self.burnIn = burnIn
        self.quality = quality
        self.playID = playID
        self.vod = vod
        self.autoRecovery = autoRecovery
        self.switchGeneration = switchGeneration
    }
}

public enum SubtitleSelectionMode: String, Hashable, Sendable { case auto, off }

public enum StreamProtocol: String, Hashable, Sendable { case file, hls }

/// A subtitle track as the decision lists it, with where to fetch it.
public struct DecisionSubtitle: Hashable, Sendable {
    public var option: SubtitleOption
    /// webvtt, ass or image.
    public var format: String
    /// Absolute URL without the media token; nil for image formats (burn-in only).
    public var url: URL?

    public init(option: SubtitleOption, format: String, url: URL?) {
        self.option = option
        self.format = format
        self.url = url
    }
}

/// The server's answer: how to play the file, and what the player needs to know about it.
public struct PlaybackDecision: Hashable, Sendable {
    public var growing: GrowingRecording?
    public var mode: PlaybackMode
    public var reasons: [String]
    public var `protocol`: StreamProtocol
    /// True for a VOD playlist: file position = player time. False (event): file position = `startMs` + player time.
    public var vod: Bool
    /// Absolute URL to play, without the media token.
    public var url: URL
    public var mimeType: String?
    public var playID: String
    public var sessionID: String?
    public var durationMs: Int
    public var startMs: Int
    /// The user's saved position, if partway through.
    public var resumeMs: Int
    public var audio: [AudioOption]
    public var selectedAudio: Int?
    public var subtitles: [DecisionSubtitle]
    /// The starting track selected from preferences by a server at API 18+.
    public var selectedSubtitleID: String?
    /// The subtitle being burned in, if any.
    public var burnInSubtitleID: String?
    public var segments: [Segment]
    /// Where the final credits start; 0 when unknown.
    public var creditsMs: Int
    /// Past this position the item counts as watched.
    public var watchedAtMs: Int
    /// When to offer the next episode; 0 means do not.
    public var upNextMs: Int
    public var next: UpNext?
    /// The video being sent; nil when the server did not say.
    public var video: StreamVideo?
    /// Asked for at Automatic: the step the server picked; nil otherwise.
    public var autoStep: StreamQuality?
    /// The file's seek thumbnails; nil until the server has made them, and
    /// from servers before ``trickplayAPILevel``.
    public var trickplay: Trickplay?

    public init(mode: PlaybackMode, reasons: [String] = [], protocol: StreamProtocol, vod: Bool, url: URL, mimeType: String? = nil, playID: String, sessionID: String? = nil, durationMs: Int, startMs: Int = 0, resumeMs: Int = 0, audio: [AudioOption] = [], selectedAudio: Int? = nil, subtitles: [DecisionSubtitle] = [], selectedSubtitleID: String? = nil, burnInSubtitleID: String? = nil, segments: [Segment] = [], creditsMs: Int = 0, watchedAtMs: Int = 0, upNextMs: Int = 0, next: UpNext? = nil, video: StreamVideo? = nil, autoStep: StreamQuality? = nil, trickplay: Trickplay? = nil, growing: GrowingRecording? = nil) {
        self.growing = growing
        self.mode = mode
        self.reasons = reasons
        self.protocol = `protocol`
        self.vod = vod
        self.url = url
        self.mimeType = mimeType
        self.playID = playID
        self.sessionID = sessionID
        self.durationMs = durationMs
        self.startMs = startMs
        self.resumeMs = resumeMs
        self.audio = audio
        self.selectedAudio = selectedAudio
        self.subtitles = subtitles
        self.selectedSubtitleID = selectedSubtitleID
        self.burnInSubtitleID = burnInSubtitleID
        self.segments = segments
        self.creditsMs = creditsMs
        self.watchedAtMs = watchedAtMs
        self.upNextMs = upNextMs
        self.next = next
        self.video = video
        self.autoStep = autoStep
        self.trickplay = trickplay
    }
}

/// How a file would play on this device at the chosen quality (POST
/// /api/playback/plans), made without starting anything.
public struct PlaybackPlan: Hashable, Sendable {
    public var fileID: Int
    public var mode: PlaybackMode
    public var video: StreamVideo?
    /// What the video would be encoded to ("HEVC", "H.264"); nil when it is sent as it is.
    public var videoCodec: String?
    /// What the audio would be converted to ("AAC"); nil when it is sent as it is.
    public var audioCodec: String?
    public var audioChannels: Int?
    public var reasons: [String]

    public init(fileID: Int, mode: PlaybackMode, video: StreamVideo? = nil, videoCodec: String? = nil, audioCodec: String? = nil, audioChannels: Int? = nil, reasons: [String] = []) {
        self.fileID = fileID
        self.mode = mode
        self.video = video
        self.videoCodec = videoCodec
        self.audioCodec = audioCodec
        self.audioChannels = audioChannels
        self.reasons = reasons
    }

    /// "Direct play", "Remux to fMP4", "Remux to fMP4 · audio to AAC 5.1",
    /// "Transcode to 1080p HEVC · audio to AAC".
    public var label: String {
        let audio = audioCodec.map { codec in
            (["audio to", codec] + [audioChannels.flatMap(channelsShortLabel)].compactMap { $0 }).joined(separator: " ")
        }
        let first: String
        switch mode {
        case .direct: return "Direct play"
        case .remux, .audioTranscode: first = "Remux to fMP4"
        case .transcode: first = (["Transcode to", video?.quality, videoCodec].compactMap { $0 }).joined(separator: " ")
        }
        return [first, audio].compactMap { $0 }.joined(separator: " · ")
    }
}

/// "Stereo", "5.1", "7.1"; nil for mono and odd counts.
func channelsShortLabel(_ channels: Int) -> String? {
    switch channels {
    case 2: "Stereo"
    case 6: "5.1"
    case 8: "7.1"
    default: nil
    }
}

public enum PlayState: String, Hashable, Sendable { case playing, paused, buffering, stopped, closed }

/// A heartbeat for PUT /api/files/{id}/progress.
public struct ProgressReport: Hashable, Sendable {
    public var recordingID: String?
    public var fileID: Int
    public var positionMs: Int
    public var durationMs: Int
    public var playID: String?
    public var state: PlayState
    public var bufferedMs: Int
    /// What the player measures for Automatic quality (PLAN-AUTO-QUALITY.md
    /// §3.1), sent for every play: its estimate of the link in bits per
    /// second, nil where it has none.
    public var throughputBps: Int?
    /// Age of a completed transfer measurement; absent estimates cannot raise quality.
    public var throughputAgeMs: Int?
    public var switchGeneration: Int64?
    public var switchFailedGeneration: Int64?
    /// Rebuffers since this decision that no seek, reload or resume caused, and the time they took.
    public var stalls: Int?
    public var stallMs: Int?
    /// From asking for this decision to its first frame; sent once.
    public var startupMs: Int?

    public init(fileID: Int, positionMs: Int, durationMs: Int, playID: String?, state: PlayState, bufferedMs: Int, throughputBps: Int? = nil, throughputAgeMs: Int? = nil, switchGeneration: Int64? = nil, switchFailedGeneration: Int64? = nil, stalls: Int? = nil, stallMs: Int? = nil, startupMs: Int? = nil, recordingID: String? = nil) {
        self.fileID = fileID
        self.positionMs = positionMs
        self.durationMs = durationMs
        self.playID = playID
        self.state = state
        self.bufferedMs = bufferedMs
        self.throughputBps = throughputBps
        self.throughputAgeMs = throughputAgeMs
        self.switchGeneration = switchGeneration
        self.switchFailedGeneration = switchFailedGeneration
        self.stalls = stalls
        self.stallMs = stallMs
        self.startupMs = startupMs
        self.recordingID = recordingID
    }
}

public struct ProgressReply: Hashable, Sendable {
    public var completed: Bool
    /// An admin stopped this play: show the message and stop.
    public var stopMessage: String?
    /// The play had ended and a new one carries on: send this from now on.
    public var newPlayID: String?
    /// At Automatic quality the server requested another step. The field name
    /// remains for older clients; API 24 can raise quality and return Original.
    public var lowerTo: StreamQuality?
    /// What to tell the viewer after the new stream succeeds.
    public var lowerMessage: String?
    public var switchGeneration: Int64?

    public init(completed: Bool, stopMessage: String? = nil, newPlayID: String? = nil, lowerTo: StreamQuality? = nil, lowerMessage: String? = nil, switchGeneration: Int64? = nil) {
        self.completed = completed
        self.stopMessage = stopMessage
        self.newPlayID = newPlayID
        self.lowerTo = lowerTo
        self.lowerMessage = lowerMessage
        self.switchGeneration = switchGeneration
    }
}

/// A short-lived token for media URLs (`?access_token=`); HLS playlists pass it on to their segments.
public struct MediaToken: Hashable, Sendable {
    public var token: String
    public var expiresAt: Date
    public var param: String
    /// The server that issued it (its base URL): the token goes on its
    /// addresses only. Nil goes anywhere, for a token whose addresses were checked already.
    public var server: URL?

    public init(token: String, expiresAt: Date, param: String = "access_token", server: URL? = nil) {
        self.token = token
        self.expiresAt = expiresAt
        self.param = param
        self.server = server
    }

    /// `url` with this token added as a query parameter. A file on the
    /// device, and an address that isn't on ``server``, are left as they are.
    public func apply(to url: URL) -> URL {
        guard !url.isFileURL, covers(url) else { return url }
        return url.appending(queryItems: [URLQueryItem(name: param, value: token)])
    }

    /// `url` is on ``server``: the same scheme, host and port, under its
    /// path (as `ServerAPI.owns`, which this module can't see).
    public func covers(_ url: URL) -> Bool {
        guard let server else { return true }
        let path = server.path.hasSuffix("/") ? server.path : server.path + "/"
        return server.scheme?.lowercased() == url.scheme?.lowercased() && server.host?.lowercased() == url.host?.lowercased()
            && server.port == url.port && (url.path + "/").hasPrefix(path)
    }
}

/// The server calls the player makes. WMMData implements it; WMMPlayback
/// uses it (and fakes it in tests). Methods throw ``APIError``.
public protocol PlaybackGateway: Sendable {
    /// Freeze the server/account before a player queues any asynchronous work.
    @MainActor func bound() -> any PlaybackGateway
    func decide(_ request: DecisionRequest) async throws -> PlaybackDecision

    func reportProgress(_ report: ProgressReport) async throws -> ProgressReply

    func mediaToken() async throws -> MediaToken

    /// Ends an HLS session (DELETE /api/playback/{session}).
    func stopSession(_ sessionID: String) async throws

    /// How each file would play with `profile` at `quality`; empty from servers that cannot say.
    func plans(profile: PlaybackProfile, quality: StreamQuality, fileIDs: [Int]) async throws -> [PlaybackPlan]

    /// Subtitles found online for the file, in the server's languages; nil
    /// when the server has no provider or is too old to have one.
    func searchSubtitles(fileID: Int) async throws -> OnlineSubtitles?

    /// Downloads an online result and keeps it for the file: the new subtitle track's ID.
    func downloadSubtitle(fileID: Int, resultID: String) async throws -> String

    /// At most one opt-in hash-matched download for this active owned play.
    /// Called after its first frame, never on the playback-decision path.
    func automaticSubtitle(fileID: Int, playID: String) async throws -> DecisionSubtitle?
    func cancelAutomaticSubtitle(fileID: Int, playID: String) async throws

    /// The text of a subtitle track (WebVTT), fetched with the media token already on `url`.
    func subtitleText(_ url: URL) async throws -> String
}

extension PlaybackGateway {
    @MainActor public func bound() -> any PlaybackGateway { self }
    public func automaticSubtitle(fileID: Int, playID: String) async throws -> DecisionSubtitle? { nil }
    public func cancelAutomaticSubtitle(fileID: Int, playID: String) async throws {}
}
