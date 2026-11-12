import Foundation
import Observation
import WMMModel

/// A remote control, never a second playback engine. The receiver owns progress,
/// Skip, Next Episode and Still watching. A local player survives a failed handover.
@MainActor @Observable
public final class CastService {
    public private(set) var available = false
    public private(set) var connected = false
    public private(set) var loading = false
    public private(set) var receiver: CastReceiverState?
    public private(set) var media: CastMediaStatus?
    public private(set) var request: PlayRequest?
    public private(set) var message: String?
    public var remotePresented = false
    public var continuingOnDevice: PlayRequest?
    public private(set) var continuingQuality: StreamQuality?
    public private(set) var capability: CastCapability?

    @ObservationIgnored public let transport: any CastTransport
    @ObservationIgnored private let gateway: any CastGateway
    @ObservationIgnored private let client: ClientInfo
    @ObservationIgnored private let sleep: @MainActor (Duration) async throws -> Void
    @ObservationIgnored private var owner: CastIdentity?
    @ObservationIgnored private var ownedPlayID: String?
    @ObservationIgnored private var loadGeneration = 0
    @ObservationIgnored private var pending: Pending?
    @ObservationIgnored private var timeout: Task<Void, Never>?

    private final class Pending {
        let generation: Int
        let identity: CastIdentity
        let contentID: String
        let loadID: UUID
        let priorMediaSessionID: Int?
        let priorPlayID: String?
        let continuation: CheckedContinuation<Bool, Never>
        var accepted = false
        init(generation: Int, identity: CastIdentity, contentID: String, loadID: UUID, priorMediaSessionID: Int?, priorPlayID: String?, continuation: CheckedContinuation<Bool, Never>) {
            self.generation = generation; self.identity = identity; self.contentID = contentID; self.continuation = continuation
            self.loadID = loadID
            self.priorMediaSessionID = priorMediaSessionID; self.priorPlayID = priorPlayID
        }
    }

    public init(gateway: any CastGateway, transport: any CastTransport, client: ClientInfo, sleep: (@MainActor (Duration) async throws -> Void)? = nil) {
        self.gateway = gateway; self.transport = transport; self.client = client
        self.sleep = sleep ?? { try await Task.sleep(for: $0) }
        connected = transport.connected
        transport.onEvent = { [weak self] in self?.receive($0) }
        if connected { transport.send(CastCommand.getState.data()) }
        gateway.onSessionChange { [weak self] in
            guard let self else { return }
            clearSession()
            Task { await self.refresh() }
        }
    }

    public var deviceName: String { transport.deviceName }
    public var ownsMedia: Bool { owner != nil && owner == gateway.identity }
    public var supportsLive: Bool { connected && (capability?.apiLevel ?? 0) >= liveCastAPILevel && receiver?.features?.contains("live") == true }
    public var supportsLiveAutomatic: Bool {
        supportsLive && (capability?.apiLevel ?? 0) >= liveAutomaticAPILevel
            && receiver?.features?.contains(CastProtocol.liveAutomaticFeature) == true
    }
    public var active: Bool { connected && (loading || receiver.map { $0.phase != "idle" } == true) }
    public var state: PlayerState {
        var state = receiver?.playerState(status: media, request: ownsMedia ? request : nil, apiLevel: capability?.apiLevel) ?? PlayerState()
        if loading { state.status = .loading; state.title = request?.title ?? "Connecting to TV" }
        if let message, receiver == nil || receiver?.phase == "error" { state.error = message }
        return state
    }

    public func refresh() async {
        let identity = gateway.identity
        do {
            let result = try await gateway.capability()
            guard gateway.identity == identity else { return }
            capability = result
            available = result != nil
        } catch {
            guard gateway.identity == identity else { return }
            available = false
            // Existing controls still use the last known server level while it is unreachable.
            if capability?.identity != identity { capability = nil }
        }
    }

    /// A browser Play while connected, or a snapshot from the current local player.
    @discardableResult
    public func play(_ request: PlayRequest, quality: StreamQuality, subtitleMode: String? = nil) async -> Bool {
        guard request.recordingID == nil else { message = "This recording can be cast once it has finished."; return false }
        if ownsMedia, receiver?.fileId == request.fileID, request.startMs == nil, receiver?.phase == "playing" {
            remotePresented = true
            return true
        }
        return await load(request: request, live: nil, quality: quality, subtitleMode: subtitleMode)
    }

    @discardableResult
    public func playLive(_ request: LivePlayRequest) async -> Bool {
        guard supportsLive else { message = "This TV does not support Live TV casting yet."; return false }
        var supported = request
        if supported.quality == .auto && !supportsLiveAutomatic { supported.quality = .p1080 }
        return await load(request: nil, live: supported, quality: .original, subtitleMode: nil)
    }

    private func load(request: PlayRequest?, live: LivePlayRequest?, quality: StreamQuality, subtitleMode: String?) async -> Bool {
        guard connected else { message = "Choose a TV first."; return false }
        let previousLoadID = pending?.loadID
        finishPending(false)
        if let previousLoadID { transport.cancelLoad(previousLoadID) }
        guard connected else { message = "The previous transfer was cancelled. Choose the TV again to start this item."; return false }
        loadGeneration += 1
        let generation = loadGeneration
        guard let identity = gateway.identity else { message = "Sign in before casting."; return false }
        loading = true; message = nil
        do {
            let grant = try await gateway.grant()
            guard generation == loadGeneration, connected, gateway.identity == identity, grant.capability.identity == identity else {
                if generation == loadGeneration { loading = false }
                return false
            }
            let contentID = request.map { CastProtocol.contentID(fileID: $0.fileID) } ?? CastProtocol.liveContentID(live!.channel.id)
            var data = CastLoadData(grant: grant, request: request, live: live, quality: quality,
                                    deviceID: transport.deviceID, deviceName: deviceName, senderName: client.name)
            if let subtitleMode { data.subtitleMode = subtitleMode }
            let load = CastLoad(contentID: contentID, title: request?.title ?? live!.channel.title,
                                subtitle: request?.subtitle, live: live != nil, data: data)
            let priorMediaSessionID = transport.mediaSessionID
            let priorPlayID = receiver?.fileId == request?.fileID ? receiver?.playId : nil
            self.request = request; receiver = nil; media = nil; owner = nil; ownedPlayID = nil
            capability = grant.capability
            return await withCheckedContinuation { continuation in
                pending = Pending(generation: generation, identity: identity, contentID: contentID, loadID: load.id,
                                  priorMediaSessionID: priorMediaSessionID, priorPlayID: priorPlayID, continuation: continuation)
                timeout = Task { [weak self, sleep] in
                    do { try await sleep(.seconds(25)) } catch { return }
                    guard let self, pending?.generation == generation else { return }
                    transport.cancelLoad(load.id)
                    message = "The TV did not start playing. Try again; playback on this device can continue."
                    finishPending(false)
                }
                Task { [weak self] in
                    guard let self, pending?.generation == generation, connected else { return }
                    do {
                        try await transport.load(load)
                        guard pending?.generation == generation else { return }
                        pending?.accepted = true
                        acknowledge()
                    } catch {
                        guard pending?.generation == generation else { return }
                        message = "Couldn’t start playback on the TV. Try again."
                        finishPending(false)
                    }
                }
            }
        } catch {
            guard generation == loadGeneration else { return false }
            loading = false; message = error.displayMessage
            return false
        }
    }

    private func acknowledge() {
        guard let pending, pending.accepted, connected, pending.identity == gateway.identity,
              let receiver, receiver.phase == "playing", let media, let sessionID = media.sessionID,
              sessionID != pending.priorMediaSessionID, media.contentID == pending.contentID,
              [.playing, .paused, .buffering].contains(media.status),
              pending.priorPlayID == nil || (receiver.playId != nil && receiver.playId != pending.priorPlayID) else { return }
        let content = receiver.live.map { CastProtocol.liveContentID($0.channelId) } ?? receiver.fileId.map(CastProtocol.contentID(fileID:))
        guard content == pending.contentID else { return }
        owner = pending.identity; ownedPlayID = receiver.playId
        finishPending(true)
    }

    private func finishPending(_ succeeded: Bool) {
        timeout?.cancel(); timeout = nil
        let held = pending; pending = nil
        loading = false
        held?.continuation.resume(returning: succeeded)
    }

    private func receive(_ event: CastTransportEvent) {
        switch event {
        case .connected:
            connected = true
            transport.send(CastCommand.getState.data())
        case .disconnected:
            loadGeneration += 1
            connected = false; owner = nil; request = nil; ownedPlayID = nil
            finishPending(false)
            message = "Connection to the TV was lost. Reconnect to control it."
        case .failed(let error):
            if let pending { transport.cancelLoad(pending.loadID) }
            message = error; finishPending(false)
        case .status(let status):
            guard connected else { return }
            media = status
            acknowledge()
        case .message(let data):
            guard connected else { return }
            guard let next = CastProtocol.decode(data) else { return }
            if pending == nil, ownsMedia, let previous = receiver {
                let advanced = next.fileId != nil && next.fileId == previous.nextEpisode?.fileId
                let replaced = next.fileId != previous.fileId || (next.playId != nil && ownedPlayID != nil && next.playId != ownedPlayID)
                if replaced && !advanced { owner = nil; request = nil; ownedPlayID = nil }
                else if advanced, let n = previous.nextEpisode {
                    request = PlayRequest(fileID: n.fileId, title: n.showTitle, subtitle: "\(episodeCode(season: n.seasonNumber, episode: n.episodeNumber)) · \(n.title)")
                    ownedPlayID = next.playId
                }
            }
            receiver = next
            if next.phase == "error" || next.phase == "stopped" {
                if let pending {
                    transport.cancelLoad(pending.loadID)
                    message = next.error ?? next.stopMessage ?? "The TV could not play this item."
                    finishPending(false)
                }
            } else { acknowledge() }
        }
    }

    public func command(_ command: CastCommand) {
        guard connected else { return }
        // A channel ID belongs to one bound server, and cannot be sent after joining another sender.
        if case .channel = command, !ownsMedia { return }
        transport.send(command.data())
    }
    public func togglePlayPause() {
        guard connected else { return }
        command(.input)
        if state.status == .playing { transport.pause() } else { transport.play() }
    }
    public func setVolume(_ volume: Float) { if connected { command(.input); transport.setVolume(min(max(volume, 0), 1)) } }
    public func goLive() { if connected && receiver?.live != nil { transport.goLive() } }
    public func stop() {
        loadGeneration += 1; finishPending(false)
        if connected { transport.stop() }
        owner = nil; request = nil; receiver = nil; media = nil; message = nil
    }
    public func disconnect() {
        loadGeneration += 1; finishPending(false)
        transport.disconnect(); connected = false
        owner = nil; request = nil; receiver = nil; media = nil; message = nil
    }
    public func clearMessage() { message = nil }

    /// Deliberate handback only for media this sign-in loaded. Joined sessions
    /// never turn another server's file ID into a request on this server.
    public func continueOnDevice() {
        guard ownsMedia, var request, receiver?.live == nil, receiver?.fileId == request.fileID else { return }
        request.startMs = state.positionMs; request.audioIndex = state.selectedAudio; request.subtitleID = state.selectedSubtitle
        request.qualityOverride = state.quality
        request.subtitleMode = state.selectedSubtitle == nil ? .off : .auto
        continuingQuality = state.quality
        transport.stop()
        transport.disconnect(); connected = false
        continuingOnDevice = request
        remotePresented = false
        owner = nil; self.request = nil; receiver = nil; media = nil; message = nil
    }

    private func clearSession() {
        loadGeneration += 1
        finishPending(false)
        owner = nil; ownedPlayID = nil; request = nil; receiver = nil; media = nil
        capability = nil; available = false; remotePresented = false; continuingOnDevice = nil; continuingQuality = nil; message = nil
        // Disconnect without stopping someone else's TV; discard all old-account state.
        transport.disconnect(); connected = false
    }
}
