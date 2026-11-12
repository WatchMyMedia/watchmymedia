import Foundation
import WMMModel

/// Mirrors web/src/cast/protocol.ts version 1. Unknown additive fields are ignored.
public enum CastProtocol {
    public static let namespace = "urn:x-cast:media.watchmy.cast"
    public static let version = 1
    public static let liveAutomaticFeature = "live-auto"
    public static func contentID(fileID: Int) -> String { "wmm:file:\(fileID)" }
    public static func liveContentID(_ channelID: String) -> String { "wmm:live:\(channelID)" }

    public static func decode(_ data: Data) -> CastReceiverState? {
        guard data.count <= 131_072, let state = try? JSONDecoder().decode(CastReceiverState.self, from: data),
              state.type == "state", state.v == version,
              ["idle", "loading", "playing", "ended", "stopped", "error"].contains(state.phase) else { return nil }
        return state
    }
}

public struct CastLoadData: Codable, Sendable {
    public struct Server: Codable, Sendable { public var id: String; public var urls: [String] }
    public struct Device: Codable, Sendable { public var id: String?; public var name: String? }
    public struct Sender: Codable, Sendable { public var name: String }
    /// Live quality is auto on a capable receiver/server, or 1080, 720, 480, 360.
    public struct Live: Codable, Sendable { public var channelId: String; public var quality: String }
    public var v = CastProtocol.version
    public var server: Server
    public var token: String
    public var tokenExpiresAt: String
    public var fileId: Int?
    public var live: Live?
    public var startMs: Int?
    public var audioIndex: Int?
    public var subtitleId: String?
    /// Additive: newer receivers pass explicit Off to servers with subtitle preferences.
    public var subtitleMode: String?
    public var quality: String?
    public var device: Device
    public var sender: Sender

    public init(grant: CastGrant, request: PlayRequest?, live: LivePlayRequest?, quality: StreamQuality, deviceID: String?, deviceName: String, senderName: String) {
        server = Server(id: grant.capability.identity.serverID, urls: grant.urls.map(\.absoluteString))
        token = grant.token; tokenExpiresAt = ISO8601DateFormatter().string(from: grant.expiresAt)
        fileId = request?.fileID
        self.live = live.map { .init(channelId: $0.channel.id, quality: $0.quality.rawValue) }
        startMs = request?.startMs.map { max(0, $0) }; audioIndex = request?.audioIndex; subtitleId = request?.subtitleID
        subtitleMode = request?.subtitleMode.rawValue
        self.quality = live == nil ? (request?.qualityOverride ?? quality).playable(apiLevel: grant.capability.apiLevel).rawValue : nil
        device = Device(id: deviceID, name: deviceName); sender = Sender(name: senderName)
    }
}

public struct CastLoad: Sendable {
    /// Sender-local request identity; never sent to the receiver.
    public let id = UUID()
    public var contentID: String
    public var title: String
    public var subtitle: String?
    public var live: Bool
    public var data: CastLoadData
    public init(contentID: String, title: String, subtitle: String?, live: Bool, data: CastLoadData) {
        self.contentID = contentID; self.title = title; self.subtitle = subtitle; self.live = live; self.data = data
    }
}

public enum CastCommand: Sendable {
    case getState, skip, playNext, watchCredits, input, stillWatching(Bool)
    case seek(Int), audio(Int), subtitle(String?), quality(String), channel(String), channelStep(Int)
    public func data() -> Data {
        let object: [String: Any]
        switch self {
        case .getState: object = ["type": "getState"]
        case .skip: object = ["type": "skip"]
        case .playNext: object = ["type": "playNext"]
        case .watchCredits: object = ["type": "watchCredits"]
        case .input: object = ["type": "input"]
        case .stillWatching(let keep): object = ["type": "stillWatching", "keep": keep]
        case .seek(let ms): object = ["type": "seek", "positionMs": max(0, ms)]
        case .audio(let index): object = ["type": "audio", "index": index]
        case .subtitle(let id): object = ["type": "subtitle", "id": id as Any? ?? NSNull()]
        case .quality(let id): object = ["type": "quality", "id": id]
        case .channel(let id): object = ["type": "channel", "channelId": id]
        case .channelStep(let delta): object = ["type": "channelStep", "by": delta < 0 ? -1 : 1]
        }
        return (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data()
    }
}

public struct CastReceiverState: Decodable, Sendable {
    public struct SegmentInfo: Decodable, Sendable { var kind: String; var startMs: Int; var endMs: Int?; var final: Bool? }
    public struct SkipInfo: Decodable, Sendable { var kind: String; var endMs: Int; var auto: Bool }
    public struct Audio: Decodable, Sendable { var index: Int; var codec: String; var channels: Int; var language: String?; var title: String?; var `default`: Bool }
    public struct Subtitle: Decodable, Sendable { var id: String; var language: String?; var title: String?; var format: String; var forced: Bool?; var burnInOnly: Bool? }
    public struct Next: Decodable, Sendable {
        var fileId: Int; var showTitle: String; var seasonNumber: Int; var episodeNumber: Int; var title: String; var secondsLeft: Int?
        var model: UpNext { UpNext(fileID: fileId, episodeID: 0, showID: 0, showTitle: showTitle, season: seasonNumber, episode: episodeNumber, title: title) }
    }
    public struct Programme: Decodable, Sendable { public var title: String; public var episodeTitle: String?; public var start: String; public var end: String }
    public struct Live: Decodable, Sendable {
        public var channelId: String; public var number: String; public var name: String; public var quality: String
        public var now: Programme?; public var next: Programme?; public var paused: Bool?; public var pauseLeftSeconds: Int?; public var reason: String?
    }
    public var type: String
    public var v: Int
    public var features: [String]?
    public var phase: String
    public var fileId: Int?
    public var playId: String?
    public var title: String?
    public var subtitle: String?
    public var mode: String?
    public var quality: String?
    public var autoStep: String?
    public var durationMs: Int?
    public var offsetMs: Int?
    public var watchedAtMs: Int?
    var segments: [SegmentInfo]?
    var skip: SkipInfo?
    var upNext: Next?
    var nextEpisode: Next?
    public var stillWatching: Bool?
    var audio: [Audio]?
    public var selectedAudio: Int?
    var subtitles: [Subtitle]?
    public var selectedSubtitle: String?
    public var stopMessage: String?
    public var error: String?
    public var deviceName: String?
    public var live: Live?

    public func playerState(status: CastMediaStatus?, request: PlayRequest?, apiLevel: Int?) -> PlayerState {
        let maximumMs = 7 * 24 * 3_600_000
        var state = PlayerState()
        state.status = phase == "loading" ? .loading : phase == "ended" ? .ended : phase == "error" || phase == "stopped" ? .error : status?.status ?? .buffering
        state.title = title ?? request?.title ?? "Playing on TV"; state.subtitle = subtitle ?? request?.subtitle
        state.backdropPath = request?.backdropPath; state.stillPath = request?.stillPath; state.posterPath = request?.posterPath
        state.durationMs = min(maximumMs, max(0, durationMs ?? status?.durationMs ?? 0))
        let offset = min(maximumMs, max(0, offsetMs ?? 0)), position = min(maximumMs, max(0, status?.positionMs ?? 0))
        state.positionMs = min(maximumMs, offset + position)
        state.quality = quality.flatMap(StreamQuality.init(rawValue:)) ?? .original
        state.qualities = StreamQuality.offered(apiLevel: apiLevel)
        state.autoStep = autoStep.flatMap(StreamQuality.init(rawValue:))
        state.mode = mode == "audio" ? .audioTranscode : mode.flatMap(PlaybackMode.init(rawValue:))
        state.segments = (segments ?? []).prefix(500).compactMap { s in
            SegmentKind(rawValue: s.kind).map { Segment(kind: $0, startMs: max(0, s.startMs), endMs: s.endMs, final: s.final ?? false) }
        }
        if let skip, let kind = SegmentKind(rawValue: skip.kind) {
            state.skip = SkipPrompt(segment: Segment(kind: kind, startMs: state.positionMs, endMs: skip.endMs), label: kind.skipLabel, autoProgress: skip.auto ? 0 : nil)
        }
        state.nextEpisode = nextEpisode?.model ?? upNext?.model
        if let upNext { state.upNext = UpNextPrompt(next: upNext.model, countdownMs: upNext.secondsLeft.map { min(max(0, $0), 3600) * 1000 }) }
        state.askStillWatching = stillWatching ?? false
        state.audio = (audio ?? []).prefix(100).map { .init(index: $0.index, label: audioTrackLabel(language: $0.language, codec: $0.codec, channels: $0.channels, title: $0.title, isDefault: $0.default), language: $0.language, isDefault: $0.default) }
        state.selectedAudio = selectedAudio
        state.subtitles = (subtitles ?? []).prefix(300).map { .init(id: $0.id, label: subtitleTrackLabel(language: $0.language, title: $0.title, forced: $0.forced ?? false), language: $0.language, forced: $0.forced ?? false, burnInOnly: $0.burnInOnly ?? false, format: $0.format, source: $0.id.hasPrefix("d") ? .online : .embedded) }
        state.selectedSubtitle = selectedSubtitle
        state.stopMessage = phase == "stopped" ? stopMessage ?? "" : nil; state.error = error
        return state
    }
}

public struct CastMediaStatus: Sendable {
    public var sessionID: Int?
    public var contentID: String?
    public var status: PlayerStatus
    public var positionMs: Int
    public var durationMs: Int
    public var volume: Float
    public init(contentID: String?, status: PlayerStatus, positionMs: Int, durationMs: Int = 0, volume: Float = 1, sessionID: Int? = nil) {
        self.sessionID = sessionID
        self.contentID = contentID; self.status = status; self.positionMs = max(0, positionMs); self.durationMs = max(0, durationMs); self.volume = min(max(volume, 0), 1)
    }
}

@MainActor
public protocol CastTransport: AnyObject {
    var deviceID: String? { get }
    var deviceName: String { get }
    var connected: Bool { get }
    /// The SDK's actual current media session, sampled immediately before LOAD.
    var mediaSessionID: Int? { get }
    var onEvent: (@MainActor (CastTransportEvent) -> Void)? { get set }
    func load(_ load: CastLoad) async throws
    /// Cancel only this sender's submitted load, leaving replaced/joined media alone.
    func cancelLoad(_ id: UUID)
    func send(_ data: Data)
    func play()
    func pause()
    func goLive()
    func setVolume(_ volume: Float)
    func stop()
    func disconnect()
}

public enum CastTransportEvent: Sendable {
    case connected, disconnected, message(Data), status(CastMediaStatus), failed(String)
}
