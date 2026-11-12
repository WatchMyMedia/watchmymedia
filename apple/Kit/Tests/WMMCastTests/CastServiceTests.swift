import AVFoundation
import Foundation
import Observation
import Testing
@testable import WMMCast
import WMMModel
import WMMFakes

@MainActor
@Suite struct CastServiceTests {
    let gateway = FakeCastGateway()
    let transport = FakeCastTransport()
    let client = ClientInfo.fakePhone
    var file: PlayRequest { PlayRequest(fileID: 9101, title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark") }

    func service() async -> CastService {
        let service = CastService(gateway: gateway, transport: transport, client: client)
        await service.refresh()
        transport.connect()
        return service
    }

    @Test func localPlaybackEndsOnlyAfterTheMatchingLoadIsAcknowledged() async throws {
        let service = await service()
        transport.automaticallyAcknowledge = false
        let local = CountingPlayback()
        let moved = Task { await CastHandover.move(local, request: file, to: service) }
        await until { !transport.loads.isEmpty }
        #expect(local.stops == 0)
        #expect(local.releases == 0)
        transport.emit(.message(Data(#"{"type":"state","v":1,"phase":"playing","fileId":99}"#.utf8)))
        await Task.yield()
        #expect(local.stops == 0, "another receiver item cannot acknowledge our load")
        transport.acknowledgeLoad()
        #expect(await moved.value)
        #expect(local.stops == 1 && local.releases == 1)
        let load = try #require(transport.loads.last)
        #expect(load.data.startMs == 123_000)
        #expect(load.data.audioIndex == 2)
        #expect(load.data.subtitleId == "s4")
        #expect(load.data.quality == "720")
        #expect(service.ownsMedia)
    }

    @Test func failedLoadKeepsLocalPlaybackAndItsSelectedTracks() async {
        let service = await service()
        transport.failLoads = true
        let local = CountingPlayback()
        #expect(await !CastHandover.move(local, request: file, to: service))
        #expect(local.stops == 0 && local.releases == 0)
        #expect(local.state.positionMs == 123_000)
        #expect(service.message != nil)
    }

    @Test func explicitOffIsNotLostDuringHandover() async throws {
        let service = await service()
        let local = CountingPlayback()
        local.state.selectedSubtitle = nil
        #expect(await CastHandover.move(local, request: file, to: service))
        #expect(transport.loads.last?.data.subtitleMode == "off")
        #expect(transport.loads.last?.data.subtitleId == nil)
    }

    @Test func pendingGrantCannotMoveCredentialsAcrossAnAccountChange() async throws {
        let service = await service()
        let grant = try await gateway.grant()
        var held: CheckedContinuation<CastGrant, Error>?
        gateway.issue = { try await withCheckedThrowingContinuation { held = $0 } }
        let moving = Task { await service.play(file, quality: .auto) }
        await until { held != nil }
        gateway.changeIdentity(nil)
        held?.resume(returning: grant)
        #expect(await !moving.value)
        #expect(transport.loads.isEmpty)
        #expect(!service.ownsMedia)
    }

    @Test func accountChangesCancelAnUnacknowledgedLoadAndDiscardLateState() async {
        let service = await service()
        transport.automaticallyAcknowledge = false
        let moving = Task { await service.play(file, quality: .original) }
        await until { !transport.loads.isEmpty }
        gateway.changeIdentity(nil)
        #expect(await !moving.value)
        transport.acknowledgeLoad()
        #expect(service.receiver == nil)
        #expect(service.request == nil)
        #expect(!service.connected)
    }

    @Test func joiningAnotherSendersSessionDoesNotClaimItsFileOrMintAToken() async {
        let service = await service()
        transport.emit(.message(Data(#"{"type":"state","v":1,"phase":"playing","fileId":9101,"playId":"other-play","title":"Playing on TV"}"#.utf8)))
        #expect(service.active)
        #expect(!service.ownsMedia)
        service.continueOnDevice()
        #expect(service.continuingOnDevice == nil)
        #expect(transport.loads.isEmpty)
        service.command(.skip)
        #expect(transport.commands.last == CastCommand.skip.data(), "permitted TV controls still work")
    }

    @Test func anotherSendersNewPlayOfTheSameFileLosesOurPrivateBinding() async {
        let service = await service()
        #expect(await service.play(file, quality: .hd))
        transport.emit(.message(Data(#"{"type":"state","v":1,"phase":"playing","fileId":9101,"playId":"different-owner"}"#.utf8)))
        #expect(!service.ownsMedia)
        #expect(service.request == nil)
        service.continueOnDevice()
        #expect(service.continuingOnDevice == nil)
    }

    @Test func handbackCarriesFilePositionTracksAndQualityAndStopsTheTV() async throws {
        let service = await service()
        var request = file
        request.startMs = 45_000; request.audioIndex = 2; request.subtitleID = "s4"
        #expect(await service.play(request, quality: .hd))
        service.command(.seek(87_000)); service.command(.subtitle(nil))
        service.continueOnDevice()
        let handback = try #require(service.continuingOnDevice)
        #expect(handback.fileID == file.fileID && handback.startMs == 87_000)
        #expect(handback.audioIndex == 2 && handback.subtitleID == nil)
        #expect(handback.qualityOverride == .hd && handback.subtitleMode == .off)
        #expect(service.continuingQuality == .hd)
        #expect(transport.stopped == 1)
        #expect(!service.connected)
    }

    @Test func disconnectLeavesTheTVPlayingWhileStopEndsIt() async {
        let service = await service()
        #expect(await service.play(file, quality: .original))
        service.disconnect()
        #expect(transport.stopped == 0)
        transport.connect()
        service.stop()
        #expect(transport.stopped == 1)
    }

    @Test func liveNeedsBothANewEnoughServerAndReceiverFeature() async {
        let service = await service()
        let live = LivePlayRequest(channel: LiveChannel(id: "ch-7.1", number: "7.1", name: "Cove One"), quality: .p720)
        gateway.apiLevel = 13
        await service.refresh()
        #expect(await !service.playLive(live))
        #expect(transport.loads.isEmpty)
        gateway.apiLevel = 18
        await service.refresh()
        transport.emit(.message(Data(#"{"type":"state","v":1,"phase":"idle"}"#.utf8)))
        #expect(await !service.playLive(live))
        transport.emit(.message(Data(#"{"type":"state","v":1,"phase":"idle","features":["live"]}"#.utf8)))
        #expect(await service.playLive(live))
        #expect(transport.loads.last?.contentID == "wmm:live:ch-7.1")
        #expect(transport.loads.last?.data.fileId == nil)
        #expect(transport.loads.last?.data.live?.quality == "720")
    }

    @Test func liveAutomaticRequiresBothTheServerAndTheReceiverFeature() async {
        let service = await service()
        let live = LivePlayRequest(channel: LiveChannel(id: "ch-7.1", number: "7.1", name: "Cove One"), quality: .auto)
        gateway.apiLevel = liveAutomaticAPILevel
        await service.refresh()
        transport.emit(.message(Data(#"{"type":"state","v":1,"phase":"idle","features":["live"]}"#.utf8)))
        #expect(!service.supportsLiveAutomatic)
        #expect(await service.playLive(live))
        #expect(transport.loads.last?.data.live?.quality == "1080")

        transport.emit(.message(Data(#"{"type":"state","v":1,"phase":"idle","features":["live","live-auto"]}"#.utf8)))
        #expect(service.supportsLiveAutomatic)
        #expect(await service.playLive(live))
        #expect(transport.loads.last?.data.live?.quality == "auto")
    }

    @Test func aLaterLoadCancelsTheEarlierTransferWithoutClaimingItsReply() async {
        let service = await service()
        transport.automaticallyAcknowledge = false
        let first = Task { await service.play(file, quality: .original) }
        await until { transport.loads.count == 1 }
        let other = PlayRequest(fileID: 9102, title: "Northfield Precinct")
        let second = Task { await service.play(other, quality: .hd) }
        await until { transport.loads.count == 2 }
        #expect(await !first.value)
        transport.emit(.message(Data(#"{"type":"state","v":1,"phase":"playing","fileId":9101}"#.utf8)))
        #expect(!service.ownsMedia)
        transport.acknowledgeLoad()
        #expect(await second.value)
        #expect(service.request?.fileID == 9102)
    }

    @Test func aFailedReplacementGrantCancelsThePreviousSubmittedTransfer() async throws {
        let service = await service()
        transport.automaticallyAcknowledge = false
        let local = CountingPlayback()
        let first = Task { await CastHandover.move(local, request: file, to: service) }
        await until { transport.loads.count == 1 }
        let oldLoad = try #require(transport.loads.first)
        gateway.issue = { throw APIError(status: nil, message: "The grant was refused.") }
        #expect(await !service.play(PlayRequest(fileID: 9102, title: "Northfield Precinct"), quality: .hd))
        #expect(await !first.value)
        #expect(transport.cancelledLoads == [oldLoad.id])
        #expect(transport.loads.count == 1)
        transport.acknowledgeLoad()
        #expect(!service.ownsMedia)
        #expect(local.stops == 0 && local.releases == 0)
    }

    @Test func acknowledgementTimeoutKeepsTheLocalPlayerAlive() async {
        var wake: CheckedContinuation<Void, Error>?
        let service = CastService(gateway: gateway, transport: transport, client: client, sleep: { _ in
            try await withCheckedThrowingContinuation { wake = $0 }
        })
        await service.refresh(); transport.connect(); transport.automaticallyAcknowledge = false
        let local = CountingPlayback()
        let moving = Task { await CastHandover.move(local, request: file, to: service) }
        await until { wake != nil && !transport.loads.isEmpty }
        wake?.resume()
        #expect(await !moving.value)
        #expect(local.stops == 0 && local.releases == 0)
        #expect(service.message?.contains("did not start") == true)
        #expect(transport.cancelledLoads == transport.loads.map(\.id), "timed-out transfer cancels its own submitted load")
        transport.acknowledgeLoad()
        #expect(!service.ownsMedia, "a late reply does not complete a timed-out transfer")
    }

    @Test(arguments: [true, false])
    func stoppingOrDisconnectingBeforeAGrantReturnsCannotRestartTheTV(stop: Bool) async throws {
        let service = await service()
        let grant = try await gateway.grant()
        var held: CheckedContinuation<CastGrant, Error>?
        gateway.issue = { try await withCheckedThrowingContinuation { held = $0 } }
        let moving = Task { await service.play(file, quality: .original) }
        await until { held != nil }
        if stop { service.stop() } else { service.disconnect(); transport.connect() }
        held?.resume(returning: grant)
        #expect(await !moving.value)
        #expect(transport.loads.isEmpty)
        #expect(!service.ownsMedia)
    }

    @Test func aCachedSameFileStateCannotAcknowledgeANewHandover() async throws {
        let service = await service()
        #expect(await service.play(file, quality: .original))
        let oldPlay = try #require(service.receiver?.playId)
        let oldSession = try #require(transport.mediaSessionID)
        transport.automaticallyAcknowledge = false
        let local = CountingPlayback()
        let moving = Task { await CastHandover.move(local, request: file, to: service) }
        await until { transport.loads.count == 2 }
        transport.emit(.message(Data("{\"type\":\"state\",\"v\":1,\"phase\":\"playing\",\"fileId\":9101,\"playId\":\"\(oldPlay)\"}".utf8)))
        transport.emit(.status(CastMediaStatus(contentID: "wmm:file:9101", status: .playing, positionMs: 10_000, sessionID: oldSession)))
        await Task.yield()
        #expect(local.stops == 0 && local.releases == 0)
        #expect(!service.ownsMedia)
        transport.acknowledgeLoad()
        #expect(await moving.value)
        #expect(local.stops == 1 && local.releases == 1)
    }

    @Test func aLateAcknowledgementAfterStopCannotRevivePrivateOwnership() async {
        let service = await service()
        transport.automaticallyAcknowledge = false
        let local = CountingPlayback()
        let moving = Task { await CastHandover.move(local, request: file, to: service) }
        await until { !transport.loads.isEmpty }
        service.stop()
        #expect(await !moving.value)
        transport.emit(.message(Data(#"{"type":"state","v":1,"phase":"playing","fileId":9101,"playId":"late-play"}"#.utf8)))
        transport.emit(.status(CastMediaStatus(contentID: "wmm:file:9101", status: .playing, positionMs: 10_000, sessionID: 1)))
        #expect(!service.ownsMedia)
        #expect(service.request == nil)
        #expect(local.stops == 0 && local.releases == 0)
    }

    @Test func anOfflineCopyCannotBeHandedToTheTVAtTheDomainBoundary() async {
        let service = await service()
        var grants = 0
        gateway.issue = { grants += 1; throw SignedOutError() }
        let local = CountingPlayback(); local.state.isOffline = true
        #expect(await !CastHandover.move(local, request: file, to: service))
        #expect(transport.loads.isEmpty)
        #expect(grants == 0 && transport.stopped == 0)
        #expect(local.stops == 0 && local.releases == 0)
    }

    private func until(_ condition: () -> Bool) async {
        for _ in 0..<1000 { if condition() { return }; await Task.yield() }
        #expect(condition(), "expected state was never reached")
    }
}

@MainActor @Observable
private final class CountingPlayback: PlaybackController {
    var state: PlayerState
    let events: AsyncStream<PlayerEvent> = AsyncStream { $0.finish() }
    var player: AVPlayer? { nil }
    var stops = 0
    var releases = 0
    init() {
        var state = PlayerState(); state.status = .playing; state.positionMs = 123_000
        state.audio = [AudioOption(index: 2, label: "English", language: "eng", isDefault: true)]
        state.selectedAudio = 2; state.selectedSubtitle = "s4"; state.quality = .hd
        self.state = state
    }
    func start(_ request: PlayRequest) {}
    func play() {}
    func pause() {}
    func togglePlayPause() {}
    func seek(toMs positionMs: Int) {}
    func seek(byMs deltaMs: Int) {}
    func noteSeek(playerMs: Int) {}
    func skip() {}
    func playNext() {}
    func watchCredits() {}
    func answerStillWatching(keepWatching: Bool) {}
    func selectAudio(_ index: Int) {}
    func selectSubtitle(_ id: String?) {}
    func downloadSubtitle(_ resultID: String) {}
    func selectQuality(_ quality: StreamQuality) {}
    func noteUserInput() {}
    func stop() { stops += 1 }
    func close() {}
    func release() { releases += 1 }
}

extension CastServiceTests {
    @Test func aGrowingRecordingStaysLocalWithoutMintingCastCredentials() async {
        let service = await service()
        var growing = file; growing.fileID = 0; growing.recordingID = "r1"
        let local = CountingPlayback()
        #expect(await !CastHandover.move(local, request: growing, to: service))
        #expect(local.stops == 0 && local.releases == 0)
        #expect(await !service.play(growing, quality: .original))
        #expect(transport.loads.isEmpty)
    }
}
