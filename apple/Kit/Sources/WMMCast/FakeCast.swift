import Foundation
import WMMModel

/// Deterministic fake transport for simulator boards; it never starts discovery.
@MainActor
public final class FakeCastTransport: CastTransport {
    public var onEvent: (@MainActor (CastTransportEvent) -> Void)?
    public let deviceID: String? = "fictional-cove-tv"
    public let deviceName = "Cove TV"
    public private(set) var connected = false
    public var mediaSessionID: Int? { status.sessionID }
    public var failLoads = false
    public var automaticallyAcknowledge = true
    public private(set) var loads: [CastLoad] = []
    public private(set) var commands: [Data] = []
    public private(set) var stopped = 0
    public private(set) var cancelledLoads: [UUID] = []
    private var status = CastMediaStatus(contentID: nil, status: .paused, positionMs: 0)
    private var nextSessionID = 0
    private var state: [String: Any] = ["type": "state", "v": 1, "features": ["live"], "phase": "idle"]
    public init() {}
    public func connect() { connected = true; onEvent?(.connected); publish() }
    public func emit(_ event: CastTransportEvent) { onEvent?(event) }
    public func load(_ load: CastLoad) async throws {
        loads.append(load)
        if failLoads { throw APIError(status: nil, message: "The fake TV refused the load.") }
        state = ["type": "state", "v": 1, "features": ["live"], "phase": "playing", "title": load.title,
                 "durationMs": 5_400_000, "quality": load.data.quality ?? "original", "offsetMs": 0,
                 "audio": [["index": 1, "codec": "aac", "channels": 2, "default": true, "language": "eng"]],
                 "selectedAudio": load.data.audioIndex ?? 1,
                 "subtitles": [["id": "s2", "language": "eng", "format": "webvtt"]]]
        state["selectedSubtitle"] = load.data.subtitleId
        if let live = load.data.live {
            state["live"] = ["channelId": live.channelId, "number": "7.1", "name": "Cove One", "quality": live.quality]
        } else {
            state["fileId"] = load.data.fileId; state["playId"] = UUID().uuidString
        }
        nextSessionID += 1
        status = CastMediaStatus(contentID: load.contentID, status: .playing, positionMs: load.data.startMs ?? 0, durationMs: 5_400_000, sessionID: nextSessionID)
        if automaticallyAcknowledge { publish() }
    }
    public func send(_ data: Data) {
        commands.append(data)
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let type = object["type"] as? String else { return }
        switch type {
        case "seek": status.positionMs = object["positionMs"] as? Int ?? status.positionMs
        case "audio": state["selectedAudio"] = object["index"]
        case "subtitle": state["selectedSubtitle"] = object["id"]
        case "quality": state["quality"] = object["id"]
        case "stillWatching": state["stillWatching"] = false
        default: break
        }
        publish()
    }
    public func play() { status.status = .playing; publish() }
    public func pause() { status.status = .paused; publish() }
    public func goLive() { status.status = .playing; publish() }
    public func setVolume(_ volume: Float) { status.volume = volume; publish() }
    public func stop() { stopped += 1; state["phase"] = "idle"; publish() }
    public func cancelLoad(_ id: UUID) {
        cancelledLoads.append(id)
        if loads.last?.id == id { stop() }
    }
    public func acknowledgeLoad() { publish() }
    public func disconnect() { connected = false; onEvent?(.disconnected) }
    private func publish() {
        if let data = try? JSONSerialization.data(withJSONObject: state) { onEvent?(.message(data)) }
        onEvent?(.status(status))
    }
}

@MainActor
public final class FakeCastGateway: CastGateway {
    public var identity: CastIdentity?
    public var apiLevel = 18
    public var issue: (@MainActor () async throws -> CastGrant)?
    private var listeners: [@MainActor () -> Void] = []
    public init(identity: CastIdentity = CastIdentity(serverID: "fictional-cove-server", userID: 1, origin: URL(string: "https://cove.example.test")!, revision: 1)) { self.identity = identity }
    public func capability() async throws -> CastCapability? { identity.map { CastCapability(identity: $0, apiLevel: apiLevel) } }
    public func grant() async throws -> CastGrant {
        if let issue { return try await issue() }
        guard let capability = try await capability() else { throw SignedOutError() }
        return CastGrant(capability: capability, token: "c1.fictional", expiresAt: .distantFuture, urls: [capability.identity.origin])
    }
    public func onSessionChange(_ listener: @escaping @MainActor () -> Void) { listeners.append(listener) }
    public func changeIdentity(_ identity: CastIdentity?) { self.identity = identity; for listener in listeners { listener() } }
}
