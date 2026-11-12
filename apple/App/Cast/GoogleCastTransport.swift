import Foundation
@preconcurrency import GoogleCast
import WMMCast
import WMMModel

/// The only Google SDK dependency is in the iOS app. Kit and tvOS stay SDK-free.
/// All callbacks and requests run on the main actor, as the SDK requires.
@MainActor
final class GoogleCastTransport: NSObject, CastTransport {
    var onEvent: (@MainActor (CastTransportEvent) -> Void)?
    private let context: GCKCastContext
    private var castSession: GCKCastSession?
    private var channel = GCKGenericChannel(namespace: CastProtocol.namespace)
    private var requests: [Int: LoadRequest] = [:]
    private var submittedLoad: SubmittedLoad?
    private var timer: Timer?
    private struct SubmittedLoad {
        var id: UUID
        var contentID: String
        var priorSessionID: Int?
        var accepted = false
        var acceptedSessionID: Int?
    }
    var deviceID: String? { castSession?.device.uniqueID }
    var deviceName: String { castSession?.device.friendlyName ?? "TV" }
    var connected: Bool { castSession != nil }
    var mediaSessionID: Int? { castSession?.remoteMediaClient?.mediaStatus?.mediaSessionID }

    override init() {
        let options = GCKCastOptions(discoveryCriteria: GCKDiscoveryCriteria(applicationID: watchMyMediaCastAppID))
        // The SDK's first-tap policy applies only when autostart is enabled.
        // It presents its explanation/permission UI before first discovery.
        options.disableDiscoveryAutostart = false
        options.startDiscoveryAfterFirstTapOnCastButton = true
        options.disableAnalyticsLogging = true
        options.stopReceiverApplicationWhenEndingSession = false
        let launch = GCKLaunchOptions()
        launch.androidReceiverCompatible = true
        options.launchOptions = launch
        if !GCKCastContext.isSharedInstanceInitialized() { GCKCastContext.setSharedInstanceWith(options) }
        context = GCKCastContext.sharedInstance()
        super.init()
        channel.delegate = self
        context.sessionManager.add(self)
        if let session = context.sessionManager.currentCastSession { attach(session) }
    }

    private func attach(_ session: GCKCastSession) {
        if let old = castSession { channel.delegate = nil; old.remove(channel); old.remoteMediaClient?.remove(self) }
        channel = GCKGenericChannel(namespace: CastProtocol.namespace)
        channel.delegate = self
        castSession = session
        session.add(channel)
        session.remoteMediaClient?.add(self)
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in self?.publish() }
        }
        onEvent?(.connected)
        publish()
    }

    private func detach() {
        timer?.invalidate(); timer = nil
        castSession?.remove(channel); castSession?.remoteMediaClient?.remove(self)
        castSession = nil
        submittedLoad = nil
        cancelLoads()
        onEvent?(.disconnected)
    }

    func load(_ load: CastLoad) async throws {
        guard let client = castSession?.remoteMediaClient else { throw APIError(status: nil, message: "The TV disconnected.") }
        submittedLoad = SubmittedLoad(id: load.id, contentID: load.contentID, priorSessionID: client.mediaStatus?.mediaSessionID)
        let data = try JSONSerialization.jsonObject(with: JSONEncoder().encode(load.data))
        let metadata = GCKMediaMetadata(metadataType: .generic)
        metadata.setString(load.title, forKey: kGCKMetadataKeyTitle)
        if let subtitle = load.subtitle { metadata.setString(subtitle, forKey: kGCKMetadataKeySubtitle) }
        // No authenticated image or stream URL enters shared MEDIA_STATUS metadata.
        let media = GCKMediaInformationBuilder(contentID: load.contentID)
        media.streamType = load.live ? .live : .buffered
        media.contentType = load.live ? "application/x-mpegurl" : "video/mp4"
        media.metadata = metadata
        media.customData = data
        let builder = GCKMediaLoadRequestDataBuilder()
        builder.mediaInformation = media.build()
        builder.autoplay = true
        builder.credentials = load.data.token
        builder.credentialsType = "wmm-cast"
        try await withCheckedThrowingContinuation { continuation in
            let request = client.loadMedia(with: builder.build())
            let waiter = LoadRequest(request: request, loadID: load.id, continuation: continuation) { [weak self, weak client] succeeded in
                guard let self else { return }
                requests.removeValue(forKey: request.requestID)
                guard succeeded, submittedLoad?.id == load.id else { return }
                submittedLoad?.accepted = true
                captureSubmittedSession(client?.mediaStatus)
            }
            requests[request.requestID] = waiter
            request.delegate = waiter
        }
    }

    func send(_ data: Data) {
        guard let message = String(data: data, encoding: .utf8), connected, channel.isConnected else { return }
        if !channel.sendTextMessage(message, error: nil) { onEvent?(.failed("Couldn’t send the command to the TV.")) }
    }
    func play() { castSession?.remoteMediaClient?.play() }
    func pause() { castSession?.remoteMediaClient?.pause() }
    func goLive() {
        let options = GCKMediaSeekOptions()
        options.seekToInfinite = true; options.resumeState = .play
        castSession?.remoteMediaClient?.seek(with: options)
    }
    func setVolume(_ volume: Float) { castSession?.setDeviceVolume(volume) }
    func stop() {
        let loadInFlight = !requests.isEmpty
        submittedLoad = nil
        cancelLoads()
        if loadInFlight {
            // GCKRequest.cancel only stops sender tracking. A media STOP can
            // fail before the first MEDIA_STATUS exists, so end this receiver
            // application to cancel an explicit Stop during its pending LOAD.
            context.sessionManager.endSessionAndStopCasting(true)
            detach()
        } else {
            castSession?.remoteMediaClient?.stop()
        }
    }
    func cancelLoad(_ id: UUID) {
        guard let load = submittedLoad, load.id == id else { return }
        submittedLoad = nil
        if requests.values.contains(where: { $0.loadID == id }) || (load.accepted && load.acceptedSessionID == nil) {
            // A newer local LOAD has a different binding. An observed external
            // replacement only cancels tracking, without ending its receiver.
            cancelLoads()
            if let status = castSession?.remoteMediaClient?.mediaStatus,
               status.mediaSessionID != load.priorSessionID,
               status.mediaInformation?.contentID != load.contentID { return }
            context.sessionManager.endSessionAndStopCasting(true)
            detach()
        } else if let sessionID = load.acceptedSessionID,
                  let status = castSession?.remoteMediaClient?.mediaStatus,
                  status.mediaSessionID == sessionID,
                  status.mediaInformation?.contentID == load.contentID {
            castSession?.remoteMediaClient?.stop()
        }
    }
    func disconnect() { context.sessionManager.endSessionAndStopCasting(false); detach() }

    private func cancelLoads() {
        let held = Array(requests.values); requests.removeAll()
        for request in held { request.cancel() }
    }

    private func publish() {
        guard let client = castSession?.remoteMediaClient, let status = client.mediaStatus else { return }
        captureSubmittedSession(status)
        let player: PlayerStatus = switch status.playerState {
        case .playing: .playing
        case .paused: .paused
        case .buffering, .loading: .buffering
        case .idle: status.idleReason == .finished ? .ended : .error
        default: .loading
        }
        func milliseconds(_ seconds: TimeInterval) -> Int {
            guard seconds.isFinite, seconds > 0 else { return 0 }
            return Int(min(seconds * 1000, Double(Int.max / 2)))
        }
        onEvent?(.status(CastMediaStatus(contentID: status.mediaInformation?.contentID, status: player,
                                       positionMs: milliseconds(client.approximateStreamPosition()),
                                       durationMs: milliseconds(status.mediaInformation?.streamDuration ?? 0),
                                       volume: castSession?.currentDeviceVolume ?? 1, sessionID: status.mediaSessionID)))
    }

    private func captureSubmittedSession(_ status: GCKMediaStatus?) {
        guard let load = submittedLoad, load.accepted, load.acceptedSessionID == nil,
              let status, status.mediaInformation?.contentID == load.contentID,
              status.mediaSessionID != load.priorSessionID else { return }
        submittedLoad?.acceptedSessionID = status.mediaSessionID
    }
}

extension GoogleCastTransport: @preconcurrency GCKSessionManagerListener {
    func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKSession) {
        if let session = session as? GCKCastSession { attach(session) }
    }
    func sessionManager(_ sessionManager: GCKSessionManager, didResumeSession session: GCKSession) {
        if let session = session as? GCKCastSession { attach(session) }
    }
    func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKSession, withError error: Error?) { if session === castSession { detach() } }
    func sessionManager(_ sessionManager: GCKSessionManager, didFailToStart session: GCKSession, withError error: Error) {
        onEvent?(.failed("Couldn’t connect to the TV. Try again."))
    }
    func sessionManager(_ sessionManager: GCKSessionManager, didSuspend session: GCKSession, with reason: GCKConnectionSuspendReason) { if session === castSession { detach() } }
}

extension GoogleCastTransport: @preconcurrency GCKGenericChannelDelegate {
    func cast(_ channel: GCKGenericChannel, didReceiveTextMessage message: String, withNamespace namespace: String) {
        guard channel === self.channel, namespace == CastProtocol.namespace, let data = message.data(using: .utf8) else { return }
        onEvent?(.message(data))
    }
    func castChannelDidConnect(_ channel: GCKGenericChannel) { send(CastCommand.getState.data()) }
}

extension GoogleCastTransport: @preconcurrency GCKRemoteMediaClientListener {
    func remoteMediaClient(_ client: GCKRemoteMediaClient, didUpdate mediaStatus: GCKMediaStatus?) { publish() }
}

@MainActor
private final class LoadRequest: NSObject, @preconcurrency GCKRequestDelegate {
    let loadID: UUID
    private let sdkRequest: GCKRequest
    private var continuation: CheckedContinuation<Void, Error>?
    private let completed: @MainActor (Bool) -> Void
    init(request: GCKRequest, loadID: UUID, continuation: CheckedContinuation<Void, Error>, completed: @escaping @MainActor (Bool) -> Void) {
        self.sdkRequest = request; self.loadID = loadID; self.continuation = continuation; self.completed = completed
    }
    func requestDidComplete(_ request: GCKRequest) { finish(nil) }
    func request(_ request: GCKRequest, didFailWithError error: GCKError) { fail() }
    func request(_ request: GCKRequest, didAbortWith abortReason: GCKRequestAbortReason) { fail() }
    func fail() { finish(APIError(status: nil, message: "The TV could not load this item.")) }
    func cancel() { sdkRequest.delegate = nil; sdkRequest.cancel(); fail() }
    private func finish(_ error: Error?) {
        guard let continuation else { return }; self.continuation = nil
        completed(error == nil)
        if let error { continuation.resume(throwing: error) } else { continuation.resume() }
    }
}
