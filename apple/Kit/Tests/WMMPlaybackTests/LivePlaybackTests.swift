import AVFoundation
import Foundation
import Observation
import Testing
@testable import WMMPlayback
import WMMModel

@MainActor @Observable
private final class LiveRepositorySpy: LiveTVRepository {
    var contextID: String? = "account-A"
    var capabilities: LiveTVCapabilities? = .init(enabled: true, allowed: true, playback: true)
    var channels = [LiveChannel(id: "one", number: "2.1", name: "Cedar One"), LiveChannel(id: "two", number: "5.1", name: "Northline"), LiveChannel(id: "three", number: "5.2", name: "Northline Extra")]
    let gateway: LiveGatewaySpy
    init(gateway: LiveGatewaySpy) { self.gateway = gateway }
    func refresh() async throws {}
    func makeSessionGateway() throws -> any LiveSessionGateway { gateway }
    func guide(start: Date, hours: Int) async throws -> LiveGuide { throw LiveTVError(reason: "unavailable") }
}

@MainActor
private final class LiveGatewaySpy: LiveSessionGateway {
    var calls: [String] = []
    var sessions: [String: LiveSession] = [:]
    var starts: [(String, LiveQuality)] = []
    var requestIDs: [String] = []
    var loseFirstStartResponse = false
    var lostResponsesRemaining = 0
    var stopFailure: Error?
    var beats: [(paused: Bool, interacted: Bool)] = []
    var profiles: [PlaybackProfile?] = []
    var measurements: [LiveMeasurement?] = []
    var nextReply: LiveSession?
    var holdStart = false
    var heldStart: CheckedContinuation<Void, Never>?
    var holdStop = false
    var heldStop: CheckedContinuation<Void, Never>?
    var failure: Error?
    var heartbeatFailure: Error?
    var holdHeartbeat = false
    var heldHeartbeat: CheckedContinuation<Void, Never>?
    var terminalReason: String?
    var nextState: LiveSessionState = .playing
    var tokenExpiry = TestClock.wallStart.addingTimeInterval(24 * 3600)
    var tokens = 0
    func start(channelID: String, quality: LiveQuality, requestID: String) async throws -> LiveSession {
        starts.append((channelID, quality)); requestIDs.append(requestID); calls.append("start:\(channelID)")
        if let existing = sessions[requestID] {
            if lostResponsesRemaining > 0 { lostResponsesRemaining -= 1; throw APIError(status: nil, message: "Response lost") }
            return existing
        }
        if holdStart { await withCheckedContinuation { heldStart = $0 } }
        if let failure { throw failure }
        let session = LiveSession(id: requestID, channel: .init(id: channelID, number: "2.1", name: "Cedar One"), state: nextState, quality: quality, streamURL: URL(string: "http://localhost:28281/api/live-tv/sessions/\(requestID)/hls/live.m3u8"))
        sessions[requestID] = session
        if loseFirstStartResponse { loseFirstStartResponse = false; throw APIError(status: nil, message: "Response lost") }
        if lostResponsesRemaining > 0 { lostResponsesRemaining -= 1; throw APIError(status: nil, message: "Response lost") }
        return session
    }
    func start(channelID: String, quality: LiveQuality, requestID: String, profile: PlaybackProfile?) async throws -> LiveSession {
        profiles.append(profile)
        var session = try await start(channelID: channelID, quality: quality, requestID: requestID)
        if quality == .auto { session.automatic = true; session.actualQuality = .p1080; sessions[requestID] = session }
        return session
    }
    func status(sessionID: String) async throws -> LiveSession { sessions[sessionID]! }
    func heartbeat(sessionID: String, paused: Bool, interacted: Bool) async throws -> LiveSession {
        beats.append((paused, interacted))
        if holdHeartbeat { await withCheckedContinuation { heldHeartbeat = $0 } }
        if let heartbeatFailure { throw heartbeatFailure }
        var session = sessions[sessionID]!
        session.paused = paused
        session.state = nextState
        if let terminalReason { session.state = .stopped; session.reason = terminalReason }
        return session
    }
    func heartbeat(sessionID: String, paused: Bool, interacted: Bool, measurement: LiveMeasurement?) async throws -> LiveSession {
        measurements.append(measurement)
        let current = try await heartbeat(sessionID: sessionID, paused: paused, interacted: interacted)
        if let nextReply { self.nextReply = nil; sessions[sessionID] = nextReply; return nextReply }
        return current
    }
    func stop(sessionID: String) async throws {
        calls.append("stop:\(sessions[sessionID]?.channel.id ?? "unknown")")
        if holdStop { await withCheckedContinuation { heldStop = $0 } }
        if let stopFailure { throw stopFailure }
        sessions.removeValue(forKey: sessionID)
    }
    func mediaToken() async throws -> MediaToken { tokens += 1; return .init(token: "token-\(tokens)", expiresAt: tokenExpiry) }
}

@MainActor
private final class LiveMediaSpy: LiveMediaPlayer {
    var player: AVPlayer? { nil }
    var playing = false
    var ready = true
    var failed = false
    var position: Double = 58
    var seekable: ClosedRange<Double>? = 0...60
    var loads: [URL] = []
    var firstFrameRendered = false
    var bufferedMs: Int? = 4_000
    var transfer: MediaTransfer? = .init(bps: 4_000_000, ageMs: 300)
    var stalls = 1
    var goLives = 0
    func load(url: URL, title: String, play: Bool) { loads.append(url); playing = play; firstFrameRendered = false; failed = false }
    func play() { playing = true }
    func pause() { playing = false }
    func goLive() { goLives += 1; position = (seekable?.upperBound ?? 60) - 2 }
    func stop() { playing = false }
}

/// Jumping monotonic time exercises deadlines without thousands of real task yields.
@MainActor
private final class LiveClock: PlaybackClock {
    var elapsedMs = 0
    var now: Date { TestClock.wallStart.addingTimeInterval(Double(elapsedMs) / 1000) }
    private var count = 0
    private var timers: [Int: (Int, @MainActor () -> Void)] = [:]
    func after(ms: Int, _ action: @escaping @MainActor () -> Void) -> TimerHandle {
        count += 1; let id = count
        timers[id] = (elapsedMs + ms, action)
        return TimerHandle { [weak self] in self?.timers[id] = nil }
    }
    func advance(by ms: Int, settle: () async -> Void) async {
        elapsedMs += ms
        let due = timers.filter { $0.value.0 <= elapsedMs }
        for (id, value) in due { timers[id] = nil; value.1() }
        await settle()
    }
}

@MainActor
private final class MutableLivePath { var cellular = false }
@MainActor private final class MutableLiveProfile {
    var current = PlaybackProfile(name: "Test TV", containers: ["mpegts"], videoCodecs: ["h264"], audioCodecs: ["aac"], maxHeight: 1080)
}

@MainActor
private final class LiveRig {
    let gateway = LiveGatewaySpy()
    let media = LiveMediaSpy()
    let clock = LiveClock()
    let repository: LiveRepositorySpy
    let controller: RealLivePlaybackController
    init(cellular: @escaping @MainActor () -> Bool = { false }, profile: @escaping @MainActor () -> PlaybackProfile = { PlaybackProfile(name: "Apple Live TV", containers: ["mpegts"], videoCodecs: ["h264"], audioCodecs: ["aac"], maxHeight: 1080) }) {
        repository = LiveRepositorySpy(gateway: gateway)
        controller = RealLivePlaybackController(repository: repository, media: media, clock: clock, cellular: cellular, profile: profile)
    }
    func start(_ index: Int = 0) async { controller.start(.init(channel: repository.channels[index])); await settle() }
    func settle() async { for _ in 0..<25 { await Task.yield() } }
    /// Waits for `condition`, a few milliseconds at a time, for a step that
    /// takes more turns of the main actor than `settle()` gives when the
    /// runner is busy. The controller's clock doesn't move meanwhile.
    func settle(until condition: () -> Bool, timeout: Duration = .seconds(5)) async {
        let deadline = ContinuousClock.now.advanced(by: timeout)
        while !condition(), ContinuousClock.now < deadline { try? await Task.sleep(for: .milliseconds(2)) }
    }
    func advance(_ ms: Int) async { await clock.advance(by: ms) { await self.settle() } }
    func finish() async { controller.release(); await controller.waitUntilStopped() }
}

@Suite @MainActor
struct LivePlaybackTests {
    @Test func automaticRequiresCapabilityAndSendsBoundedProfile() async {
        let rig = LiveRig()
        rig.controller.start(.init(channel: rig.repository.channels[0], quality: .auto))
        await rig.settle()
        #expect(rig.gateway.starts.first?.1 == .p1080)
        await rig.finish()

        let enabled = LiveRig()
        enabled.repository.capabilities?.automatic = true
        enabled.controller.start(.init(channel: enabled.repository.channels[0], quality: .auto))
        await enabled.settle()
        #expect(enabled.gateway.starts.first?.1 == .auto)
        #expect(enabled.gateway.profiles.first.flatMap { $0 }?.maxHeight == 1080)
        #expect(enabled.controller.state.qualityLabel == "Auto · 1080p")
        #expect(enabled.controller.state.qualities.first == .auto)
        await enabled.finish()
    }
    @Test func automaticSwitchAcknowledgesFirstFrameWithinOneTunerSession() async {
        let rig = LiveRig(); rig.repository.capabilities?.automatic = true
        rig.controller.start(.init(channel: rig.repository.channels[0], quality: .auto)); await rig.settle()
        let id = rig.gateway.requestIDs[0]
        var proposal = rig.gateway.sessions[id]!
        proposal.directive = .init(generation: 1, url: URL(string: "http://localhost:28281/api/live-tv/sessions/\(id)/hls/g1.m3u8"), reason: "bandwidth")
        proposal.switchStatus = "preparing"
        rig.gateway.nextReply = proposal
        await rig.advance(10_000)
        #expect(rig.media.loads.count == 2)
        #expect(rig.gateway.starts.count == 1)
        #expect(rig.gateway.measurements.last.flatMap { $0 }?.bufferedMs == 4_000)
        #expect(rig.gateway.measurements.last.flatMap { $0 }?.throughputBps == 4_000_000)
        await rig.advance(500)
        #expect(rig.gateway.measurements.last.flatMap { $0 }?.switchGeneration == nil)
        rig.media.firstFrameRendered = true
        await rig.advance(500)
        #expect(rig.gateway.measurements.last.flatMap { $0 }?.switchGeneration == 1)
        #expect(rig.gateway.starts.count == 1)
        #expect(rig.controller.state.switchMessage != nil)
        await rig.finish()
    }
    @Test func failedAutomaticSwitchRestoresPreviousOutputAndReportsFailure() async {
        let rig = LiveRig(); rig.repository.capabilities?.automatic = true
        rig.controller.start(.init(channel: rig.repository.channels[0], quality: .auto)); await rig.settle()
        let id = rig.gateway.requestIDs[0]
        var proposal = rig.gateway.sessions[id]!
        proposal.directive = .init(generation: 1, url: URL(string: "http://localhost:28281/api/live-tv/sessions/\(id)/hls/g1.m3u8"), reason: "bandwidth")
        rig.gateway.nextReply = proposal
        await rig.advance(10_000)
        rig.media.failed = true
        await rig.advance(500)
        #expect(rig.media.loads.count == 3)
        #expect(rig.media.loads.last?.path == rig.media.loads.first?.path)
        #expect(rig.gateway.measurements.contains { $0?.switchFailedGeneration == 1 })
        #expect(rig.gateway.starts.count == 1)
        await rig.finish()
    }
    @Test func pausingDuringPreparedSwitchKeepsPreviousOutputAndPauseLease() async {
        let rig = LiveRig(); rig.repository.capabilities?.automatic = true
        rig.controller.start(.init(channel: rig.repository.channels[0], quality: .auto)); await rig.settle()
        let id = rig.gateway.requestIDs[0]
        var proposal = rig.gateway.sessions[id]!
        proposal.directive = .init(generation: 1, url: URL(string: "http://localhost:28281/api/live-tv/sessions/\(id)/hls/g1.m3u8"), reason: "bandwidth")
        rig.gateway.nextReply = proposal
        await rig.advance(10_000)
        rig.controller.pause(); await rig.settle()
        #expect(rig.controller.state.status == .paused)
        #expect(rig.media.loads.last?.path == rig.media.loads.first?.path)
        #expect(rig.gateway.measurements.contains { $0?.switchFailedGeneration == 1 })
        #expect(rig.gateway.beats.last?.paused == true)
        rig.controller.goLive(); await rig.settle()
        #expect(rig.controller.state.status == .playing)
        #expect(rig.gateway.starts.count == 1)
        await rig.finish()
    }
    @Test func liveProfileChangesRetuneAfterReleasingTheOldTuner() async {
        let device = MutableLiveProfile()
        let rig = LiveRig(profile: { device.current }); rig.repository.capabilities?.automatic = true
        rig.controller.start(.init(channel: rig.repository.channels[0], quality: .auto)); await rig.settle()
        device.current.hdrTypes = ["hdr10"]
        await rig.advance(500)
        #expect(rig.gateway.calls == ["start:one", "stop:one", "start:one"])
        #expect(rig.gateway.profiles.last.flatMap { $0 }?.hdrTypes == ["hdr10"])
        #expect(rig.gateway.sessions.count == 1)
        await rig.finish()
    }
    @Test func mobileDataTransitionCapsAutomaticProfileAndWifiRestoresIt() async {
        let path = MutableLivePath()
        let rig = LiveRig(cellular: { path.cellular }); rig.repository.capabilities?.automatic = true
        rig.controller.start(.init(channel: rig.repository.channels[0], quality: .auto)); await rig.settle()
        path.cellular = true; await rig.advance(500)
        #expect(rig.gateway.starts.count == 2)
        #expect(rig.gateway.profiles.last.flatMap { $0 }?.maxHeight == 720)
        #expect(rig.gateway.profiles.last.flatMap { $0 }?.maxBitrate == 4_000_000)
        #expect(rig.controller.state.quality == .auto)
        path.cellular = false; await rig.advance(500)
        #expect(rig.gateway.starts.count == 3)
        #expect(rig.gateway.profiles.last.flatMap { $0 }?.maxHeight == 1080)
        #expect(rig.gateway.sessions.count == 1)
        await rig.finish()
    }
    @Test func trueLiveLoadDoesNotSeekBackToZero() async {
        let rig = LiveRig(); await rig.start()
        #expect(rig.media.loads.count == 1)
        #expect(rig.media.goLives == 0)
        #expect(rig.media.loads.first?.query?.contains("access_token=token-1") == true)
        #expect(rig.controller.state.status == .playing)
        await rig.finish()
    }
    @Test func stopsOldSessionBeforeLatestRapidChannelChange() async {
        let rig = LiveRig(); await rig.start()
        rig.gateway.holdStop = true
        rig.controller.start(.init(channel: rig.repository.channels[1]))
        await rig.settle()
        rig.controller.start(.init(channel: rig.repository.channels[2]))
        await rig.settle()
        #expect(rig.gateway.starts.count == 1)
        rig.gateway.holdStop = false; rig.gateway.heldStop?.resume(); rig.gateway.heldStop = nil
        await rig.settle()
        #expect(rig.gateway.calls == ["start:one", "stop:one", "start:three"])
        #expect(rig.gateway.sessions.count == 1)
        #expect(rig.controller.state.channel?.id == "three")
        await rig.finish()
    }
    @Test func lateStartIsStoppedAfterNavigationAway() async {
        let rig = LiveRig(); rig.gateway.holdStart = true
        await rig.start(); rig.controller.release()
        rig.gateway.holdStart = false; rig.gateway.heldStart?.resume(); rig.gateway.heldStart = nil
        await rig.controller.waitUntilStopped()
        #expect(rig.gateway.calls == ["start:one", "stop:one"])
        #expect(rig.media.loads.isEmpty)
        #expect(rig.gateway.sessions.isEmpty)
    }
    @Test func accountReplacementCannotLoadLateReplyOrReuseGateway() async throws {
        let rig = LiveRig(); rig.gateway.holdStart = true
        await rig.start()
        await rig.settle(until: { rig.gateway.heldStart != nil })
        let heldStart = try #require(rig.gateway.heldStart)
        rig.repository.contextID = "account-B"
        rig.gateway.holdStart = false; heldStart.resume(); rig.gateway.heldStart = nil
        await rig.controller.waitUntilStopped()
        #expect(rig.gateway.calls == ["start:one", "stop:one"])
        #expect(rig.media.loads.isEmpty)
        await rig.start(1)
        #expect(rig.gateway.starts.count == 1)
        await rig.finish()
    }
    @Test func serverReplacementStopsPlayingSessionWithoutViewCallback() async {
        let rig = LiveRig(); await rig.start(); rig.repository.contextID = "server-B"
        await rig.advance(500)
        #expect(rig.gateway.sessions.isEmpty)
        #expect(rig.controller.state.status == .stopped)
        #expect(rig.controller.state.message == LiveTVError.message("access_revoked"))
        await rig.finish()
    }
    @Test func pauseExpiresAndRetryUsesNewSession() async {
        let rig = LiveRig(); await rig.start(); rig.controller.pause(); await rig.settle()
        await rig.advance(300_000)
        #expect(rig.controller.state.status == .stopped)
        #expect(rig.gateway.sessions.isEmpty)
        #expect(rig.controller.state.message == LiveTVError.message("pause_expired"))
        rig.controller.play(); await rig.settle()
        #expect(rig.gateway.starts.count == 2)
        #expect(rig.gateway.sessions.count == 1)
        await rig.finish()
    }
    @Test func pauseWithinBufferResumesAndExpiredPositionGoesLive() async {
        let rig = LiveRig(); await rig.start(); rig.controller.pause()
        await rig.advance(10_000); rig.controller.play()
        #expect(rig.media.goLives == 0)
        rig.controller.pause(); await rig.advance(61_000); rig.controller.play()
        #expect(rig.media.goLives == 1)
        #expect(rig.controller.state.message?.contains("Resumed at live TV") == true)
        await rig.finish()
    }
    @Test func nativeTransportPauseIsHeartbeatedAndExpires() async {
        let rig = LiveRig(); await rig.start(); rig.media.pause()
        await rig.advance(500)
        #expect(rig.controller.state.status == .paused)
        #expect(rig.gateway.beats.last?.paused == true)
        await rig.advance(300_000)
        #expect(rig.gateway.sessions.isEmpty)
        await rig.finish()
    }
    @Test func briefNativePauseAheadOfSeekableEndResumesWithoutRewind() async {
        let rig = LiveRig(); await rig.start()
        rig.media.position = 62; rig.media.seekable = 0...60
        rig.media.pause(); await rig.advance(500)
        #expect(rig.controller.state.status == .paused)
        rig.media.play(); await rig.advance(500)
        #expect(rig.controller.state.status == .playing)
        #expect(rig.gateway.beats.last?.paused == false)
        #expect(rig.media.goLives == 0)
        #expect(rig.controller.state.message == nil)
        await rig.finish()
    }
    @Test func pauseBehindRollingSeekableStartRecoversBeforeAllowanceExpires() async {
        let rig = LiveRig(); await rig.start()
        rig.media.position = 30; rig.controller.pause(); await rig.advance(1_000)
        rig.media.seekable = 35...95
        rig.controller.play()
        #expect(rig.media.goLives == 1)
        #expect(rig.media.position == 93)
        #expect(rig.controller.state.message?.contains("Resumed at live TV") == true)
        await rig.finish()
    }
    @Test func nativeGoLiveDoesNotSeekBehindAnAlreadyLivePosition() {
        #expect(AVLiveMediaPlayer.forwardLiveEdgeTarget(position: 18.146, seekable: 0...16.005) == nil)
        #expect(AVLiveMediaPlayer.forwardLiveEdgeTarget(position: 58, seekable: 0...60) == nil)
    }
    @Test func nativeGoLiveSeeksForwardFromAnExpiredOrBehindPosition() {
        #expect(AVLiveMediaPlayer.forwardLiveEdgeTarget(position: 30, seekable: 35...95) == 93)
        #expect(AVLiveMediaPlayer.forwardLiveEdgeTarget(position: 50, seekable: 35...95) == 93)
    }
    @Test func heartbeatProgressDoesNotResetStillWatching() async {
        let rig = LiveRig(); await rig.start()
        await rig.advance(10_000); await rig.advance(10_000)
        await rig.advance(4 * 3_600_000 - 20_000)
        #expect(rig.controller.state.askStillWatching)
        #expect(rig.controller.state.stillWatchingSeconds == 60)
        #expect(rig.gateway.beats.dropFirst().allSatisfy { !$0.interacted })
        await rig.advance(60_000)
        #expect(rig.gateway.sessions.isEmpty)
        #expect(rig.controller.state.message == LiveTVError.message("inactive"))
        await rig.finish()
    }
    @Test func answerStillWatchingRenewsInputAndKeepsTuner() async {
        let rig = LiveRig(); await rig.start()
        await rig.advance(4 * 3_600_000)
        rig.controller.answerStillWatching(keepWatching: true); await rig.settle()
        #expect(!rig.controller.state.askStillWatching)
        #expect(rig.gateway.beats.last?.interacted == true)
        await rig.advance(60_000)
        #expect(rig.gateway.sessions.count == 1)
        await rig.finish()
    }
    @Test func unclassifiedOrCellularPathStartsCapped() async {
        let rig = LiveRig(cellular: { true }); await rig.start()
        #expect(rig.gateway.starts.map(\.1) == [.p720])
        #expect(rig.controller.state.quality == .p720)
        #expect(!rig.controller.state.qualities.contains(.p1080))
        await rig.finish()
    }
    @Test func qualityIsFixedAndCellularCapAlsoAppliesOnNetworkChange() async {
        let path = MutableLivePath()
        let rig = LiveRig(cellular: { path.cellular }); await rig.start()
        path.cellular = true; await rig.advance(500)
        #expect(rig.gateway.starts.map(\.1) == [.p1080, .p720])
        #expect(rig.controller.state.qualities == [.p720, .p480, .p360])
        rig.controller.selectQuality(.p1080); await rig.settle()
        #expect(rig.gateway.starts.count == 2)
        #expect(rig.controller.state.quality == .p720)
        await rig.finish()
    }
    @Test func adminStopAndRevocationStopMediaAndRelease() async {
        for reason in ["stopped_by_admin", "access_revoked", "no_signal", "source_offline"] {
            let rig = LiveRig(); await rig.start(); rig.gateway.terminalReason = reason
            await rig.advance(10_000)
            #expect(rig.controller.state.status == .stopped)
            #expect(rig.controller.state.message == LiveTVError.message(reason))
            #expect(rig.gateway.sessions.isEmpty)
            await rig.finish()
        }
    }
    @Test func onlyARecordingTakingTheTunerOffersItFromTheStart() async {
        let takeover = LiveRecordingTakeover(recordingID: "r1", title: "Inspector Vane", at: TestClock.wallStart.addingTimeInterval(120), canCancel: true)
        for reason in ["recording", "no_signal"] {
            let rig = LiveRig(); await rig.start()
            for id in rig.gateway.sessions.keys { rig.gateway.sessions[id]?.takeover = takeover }
            rig.gateway.terminalReason = reason
            await rig.advance(10_000)
            #expect(rig.controller.state.status == .stopped)
            #expect(rig.controller.state.stopReason == reason)
            if reason == "recording" {
                #expect(rig.controller.state.stoppedForRecording == takeover)
                #expect(rig.controller.state.stoppedTitle == "Stopped for a recording")
                #expect(rig.controller.state.stoppedMessage == "Inspector Vane is recording and needed this tuner.")
            } else {
                #expect(rig.controller.state.stoppedForRecording == nil, "A pending warning is no takeover")
                #expect(rig.controller.state.stoppedTitle == "Live TV stopped")
            }
            await rig.finish()
        }
    }
    @Test func startupWaitsForPlayableSessionAndUnknownStateFailsClosed() async {
        let rig = LiveRig(); rig.gateway.nextState = .starting; await rig.start()
        #expect(rig.media.loads.isEmpty)
        rig.gateway.nextState = .playing; await rig.advance(1_000)
        #expect(rig.media.loads.count == 1)
        rig.gateway.nextState = .unknown; await rig.advance(10_000)
        #expect(rig.controller.state.status == .stopped)
        #expect(rig.gateway.sessions.isEmpty)
        await rig.finish()
    }
    @Test func lostConnectionStopsBeforeLeaseExpires() async {
        let rig = LiveRig(); await rig.start()
        rig.gateway.heartbeatFailure = APIError(status: nil, message: "Connection lost")
        await rig.advance(50_000)
        #expect(rig.controller.state.status == .error)
        #expect(rig.gateway.sessions.isEmpty)
        await rig.finish()
    }
    @Test func resumeDuringPausedHeartbeatImmediatelySendsNewestControlState() async {
        let rig = LiveRig(); await rig.start()
        rig.gateway.holdHeartbeat = true
        rig.controller.pause(); await rig.settle()
        rig.controller.play(); await rig.settle()
        #expect(rig.gateway.beats.count == 1)
        rig.gateway.holdHeartbeat = false; rig.gateway.heldHeartbeat?.resume(); rig.gateway.heldHeartbeat = nil
        await rig.settle()
        #expect(rig.gateway.beats.map(\.paused) == [true, false])
        #expect(rig.gateway.beats.last?.interacted == true)
        await rig.finish()
    }
    @Test func failedStopCannotAcquireAnotherTuner() async {
        let rig = LiveRig(); await rig.start()
        rig.gateway.stopFailure = APIError(status: nil, message: "Connection lost")
        await rig.start(1)
        #expect(rig.gateway.starts.count == 1)
        #expect(rig.controller.state.status == .error)
        #expect(rig.gateway.sessions.count == 1)
        rig.gateway.stopFailure = nil
        rig.controller.play(); await rig.settle()
        #expect(rig.gateway.calls == ["start:one", "stop:one", "stop:one", "start:two"])
        #expect(rig.gateway.sessions.count == 1)
        await rig.finish()
    }
    @Test func twoLostStartResponsesMustReconcileBeforeAnotherChannel() async {
        let rig = LiveRig(); rig.gateway.lostResponsesRemaining = 2
        await rig.start()
        #expect(rig.controller.state.status == .error)
        #expect(rig.gateway.sessions.count == 1)
        #expect(rig.media.loads.isEmpty)
        await rig.start(1)
        #expect(rig.gateway.starts.map(\.0) == ["one", "one", "one", "two"])
        #expect(Set(rig.gateway.requestIDs.prefix(3)).count == 1)
        #expect(rig.gateway.calls == ["start:one", "start:one", "start:one", "stop:one", "start:two"])
        #expect(rig.gateway.sessions.count == 1)
        await rig.finish()
    }
    @Test func navigationAwayReconcilesTwoLostResponsesAndStopsTuner() async {
        let rig = LiveRig(); rig.gateway.lostResponsesRemaining = 2
        await rig.start(); await rig.finish()
        #expect(Set(rig.gateway.requestIDs).count == 1)
        #expect(rig.gateway.calls.last == "stop:one")
        #expect(rig.gateway.sessions.isEmpty)
        #expect(rig.media.loads.isEmpty)
    }
    @Test func revokedOriginalSignInCannotPermanentlyBlockReplacementAfterLease() async {
        let old = LiveRig(); await old.start()
        old.gateway.stopFailure = APIError(status: 401, message: "Signed out")
        await old.finish()
        let replacementGateway = LiveGatewaySpy()
        let replacementRepository = LiveRepositorySpy(gateway: replacementGateway)
        replacementRepository.contextID = "account-B"
        let replacement = RealLivePlaybackController(repository: replacementRepository, media: LiveMediaSpy(), clock: old.clock, beforeStart: { try await old.controller.ensureStopped() })
        replacement.start(.init(channel: replacementRepository.channels[1]))
        await old.settle(until: { replacement.state.status == .error })
        #expect(replacementGateway.starts.isEmpty)
        #expect(replacement.state.status == .error)
        await old.advance(65_000)
        replacement.play(); await old.settle(until: { !replacementGateway.starts.isEmpty })
        #expect(replacementGateway.starts.map(\.0) == ["two"])
        #expect(old.gateway.starts.count == 1)
        replacement.release(); await replacement.waitUntilStopped()
    }
    @Test func lostStartResponseUsesSameIdempotencyKey() async {
        let rig = LiveRig(); rig.gateway.loseFirstStartResponse = true
        await rig.start()
        #expect(rig.gateway.starts.count == 2)
        #expect(Set(rig.gateway.requestIDs).count == 1)
        #expect(rig.gateway.sessions.count == 1)
        #expect(rig.media.loads.count == 1)
        await rig.finish()
        #expect(rig.gateway.sessions.isEmpty)
    }
    @Test func typedRevocationStopsImmediately() async {
        let rig = LiveRig(); await rig.start()
        rig.gateway.heartbeatFailure = LiveTVError(reason: "access_revoked")
        await rig.advance(10_000)
        #expect(rig.controller.state.status == .stopped)
        #expect(rig.controller.state.message == LiveTVError.message("access_revoked"))
        #expect(rig.gateway.sessions.isEmpty)
        await rig.finish()
    }
    @Test func channelChangesKeepAValidToken() async {
        // PLAN-APPLE-REVIEW.md §4.2.2: tokens last six hours; a change of channel needs no new one.
        let rig = LiveRig(); await rig.start()
        rig.controller.start(.init(channel: rig.repository.channels[1])); await rig.settle()
        rig.controller.start(.init(channel: rig.repository.channels[2])); await rig.settle()
        #expect(rig.gateway.starts.count == 3)
        #expect(rig.gateway.tokens == 1)
        #expect(rig.media.loads.last?.query()?.contains("access_token=token-1") == true)
        await rig.finish()

        // One within the heartbeat's minute of running out is renewed.
        let late = LiveRig(); late.gateway.tokenExpiry = TestClock.wallStart.addingTimeInterval(30)
        await late.start()
        late.controller.start(.init(channel: late.repository.channels[1])); await late.settle()
        #expect(late.gateway.tokens == 2)
        #expect(late.media.loads.last?.query()?.contains("access_token=token-2") == true)
        await late.finish()
    }

    @Test func stallsAreCountedAsTheyHappenOncePerWait() {
        // PLAN-APPLE-REVIEW.md §3.1.7: the stall notification and the player starting to wait both say so.
        var c = LiveStallCounter()
        c.waiting()
        #expect(c.count == 0, "waiting before it ever played is the start, not a stall")
        c.playing()
        c.waiting()
        c.waiting()
        #expect(c.count == 1, "the notification and the wait are one stall")
        c.playing()
        c.waiting()
        #expect(c.count == 2)
        c.playing()
        c.excuse()
        c.waiting()
        #expect(c.count == 2, "the wait after a pause or a seek is the viewer's")
        c.playing()
        c.waiting()
        #expect(c.count == 3)
    }

    @Test func mediaTokenRenewsWithoutNewTuner() async {
        let rig = LiveRig(); rig.gateway.tokenExpiry = TestClock.wallStart.addingTimeInterval(65)
        await rig.start(); await rig.advance(10_000)
        #expect(rig.gateway.tokens == 2)
        #expect(rig.media.loads.count == 2)
        #expect(rig.gateway.starts.count == 1)
        await rig.finish()
    }
}
