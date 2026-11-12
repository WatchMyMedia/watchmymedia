import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// Automatic quality (PLAN-AUTO-QUALITY.md §6): what the player asks for,
/// what it measures and reports, and how it follows the server's step down.
@MainActor
@Suite struct AutoQualityTests {
    let h: Harness
    let request = PlayRequest(fileID: 1, startMs: 0, title: "Harbor Lights")

    init() {
        h = Harness(quality: .auto, servers: TestServers(baseURL: url("http://srv"), local: nil, apiLevel: 3))
    }

    /// Steps down to `step` at the first heartbeat from `atMs` on, once.
    private func lowerOnce(to step: StreamQuality, atMs: Int, message: String? = "Lowered to 720p for your connection.") {
        var sent = false
        h.gateway.reply = { r in
            guard !sent, r.positionMs >= atMs else { return ProgressReply(completed: false) }
            sent = true
            return ProgressReply(completed: false, lowerTo: step, lowerMessage: message)
        }
    }

    @Test func automaticAsksWithTheDevicesOwnProfile() async throws {
        await h.start(request, decision(autoStep: .original))
        let r = try #require(h.gateway.decisions.first)
        #expect(r.quality == .auto)
        #expect(r.profile == phoneProfile)
        #expect(h.state.quality == .auto)
        #expect(h.state.qualities == StreamQuality.allCases)
        #expect(h.state.autoStep == .original)
    }

    @Test func anOlderServerPlaysASavedAutomaticAsOriginal() async throws {
        let h = Harness(quality: .auto, servers: TestServers(baseURL: url("http://srv"), local: nil, apiLevel: 2))
        await h.start(request, decision())
        let r = try #require(h.gateway.decisions.first)
        #expect(r.quality == .original)
        #expect(h.state.quality == .original)
        #expect(!h.state.qualities.contains(.auto))
        #expect(h.state.autoStep == nil)
        // The pick stays for a server that can do it.
        #expect(h.prefs.quality == .auto)
        // Picking Original there changes nothing.
        h.controller.selectQuality(.original)
        await h.run()
        #expect(h.gateway.decisions.count == 1)
    }

    @Test func aStepDownReloadsHereInTheSamePlayAndKeepsTheDefault() async {
        var phases: [PlayerPhase] = []
        h.gateway.answer = { [h] r in
            phases.append(h.player.phase)
            return decision(autoStep: r.startMs == 0 ? .original : .hd)
        }
        lowerOnce(to: .hd, atMs: 20_000)
        h.controller.start(request)
        await h.run()
        await h.advance(20_000)

        #expect(h.gateway.decisions.count == 2)
        let again = h.gateway.decisions[1]
        #expect(again.quality == .auto)
        #expect(again.playID == "a1")
        #expect(again.startMs == 20_000)
        #expect(again.profile == phoneProfile)
        // The old stream played on while the server decided.
        #expect(phases == [.idle, .ready])
        #expect(h.player.loads.map(\.startPositionMs) == [0, 20_000])
        #expect(h.prefs.quality == .auto)
        #expect(h.state.quality == .auto)
        #expect(h.state.autoStep == .hd)
        #expect(h.state.status == .playing)
        #expect(h.state.qualityNote == "Lowered to 720p for your connection.")

        // The note goes after a few seconds; the server is not asked again.
        await h.advance(5_000)
        #expect(h.state.qualityNote == nil)
        await h.advance(30_000)
        #expect(h.gateway.decisions.count == 2)
    }

    @Test func aStepDownWithoutAMessageSaysWhereItWent() async {
        h.gateway.answer = { r in decision(autoStep: r.startMs == 0 ? .original : .sd) }
        lowerOnce(to: .sd, atMs: 5_000, message: nil)
        h.controller.start(request)
        await h.run()
        await h.advance(5_000)
        #expect(h.state.qualityNote == "Lowered to 480p for your connection.")
    }

    @Test func aStepDownIsLeftAloneAtAFixedQuality() async {
        let h = Harness(quality: .hd, servers: TestServers(baseURL: url("http://srv"), local: nil, apiLevel: 3))
        h.gateway.reply = { _ in ProgressReply(completed: false, lowerTo: .sd) }
        await h.start(request, decision())
        await h.advance(30_000)
        #expect(h.gateway.decisions.count == 1)
        #expect(h.state.qualityNote == nil)
    }

    @Test func pickingAFixedQualityEndsAutomaticAndPickingItAgainStartsIt() async {
        h.gateway.answer = { r in decision(autoStep: r.quality == .auto ? .original : nil) }
        h.controller.start(request)
        await h.run()
        await h.advance(10_000)
        h.controller.selectQuality(.sd)
        await h.run()
        #expect(h.gateway.decisions.last?.quality == .sd)
        #expect(h.gateway.decisions.last?.profile.maxBitrate == 2_000_000)
        #expect(h.prefs.quality == .sd)
        #expect(h.state.autoStep == nil)

        h.controller.selectQuality(.auto)
        await h.run()
        #expect(h.gateway.decisions.last?.quality == .auto)
        #expect(h.gateway.decisions.last?.profile == phoneProfile)
        #expect(h.prefs.quality == .auto)
        #expect(h.state.autoStep == .original)
    }

    @Test func automaticBeatsEveryFiveSecondsWhilePlayingOrBuffering() async {
        await h.start(request, decision(autoStep: .original))
        #expect(h.gateway.reports.count == 1)
        await h.advance(5_000)
        #expect(h.gateway.reports.count == 2)
        await h.advance(5_000)
        #expect(h.gateway.reports.count == 3)
        h.player.buffer()
        await h.run()
        #expect(h.states.last == .buffering)
        await h.advance(5_000)
        #expect(h.states.suffix(2) == [.buffering, .buffering])
        h.player.ready()
        h.controller.pause()
        await h.run()
        let paused = h.gateway.reports.count
        await h.advance(29_750)
        #expect(h.gateway.reports.count == paused)
        await h.advance(250)
        #expect(h.gateway.reports.count == paused + 1)
    }

    @Test func atTheLowestStepAStallSaysOnceThatTheConnectionIsTooSlow() async {
        await h.start(request, decision(autoStep: .low))
        await h.advance(15_000)
        h.player.buffer()
        await h.run()
        #expect(h.state.qualityNote == "Your connection is too slow to play this smoothly.")
        h.player.ready()
        await h.advance(6_000)
        #expect(h.state.qualityNote == nil)
        h.player.buffer()
        await h.run()
        #expect(h.state.qualityNote == nil)
    }
}

/// API 23 switches keep the prior stream until a decoded frame and server acknowledgement.
@MainActor
@Suite struct AutoRecoveryTests {
    let h = Harness(quality: .auto, servers: TestServers(baseURL: url("http://srv"), local: nil, apiLevel: 24))
    let request = PlayRequest(fileID: 1, startMs: 0, title: "Harbor Lights")

    @Test func originalRecoveryWaitsForFirstFrameAndKeepsTheSamePlay() async throws {
        h.player.autoFrame = false
        h.player.transfer = MediaTransfer(bps: 20_000_000, ageMs: 900)
        h.gateway.answer = { request in
            request.switchGeneration == nil
                ? decision(sessionID: "s1", autoStep: .hd)
                : decision(protocol: .file, autoStep: .original)
        }
        var sent = false
        h.gateway.reply = { report in
            guard !sent, report.positionMs >= 5_000 else { return ProgressReply(completed: false) }
            sent = true
            return ProgressReply(completed: false, lowerTo: .original, switchGeneration: 7)
        }
        h.controller.start(request)
        await h.run()
        #expect(h.gateway.decisions.first?.autoRecovery == true)
        await h.advance(5_000)
        #expect(h.gateway.decisions.count == 2)
        #expect(h.gateway.decisions.last?.switchGeneration == 7)
        #expect(h.gateway.decisions.last?.playID == "a1")
        #expect(h.player.loads.last?.hls == false)
        #expect(h.gateway.stoppedSessions.isEmpty)
        #expect(h.gateway.reports.last?.throughputAgeMs == 900)
        #expect(h.gateway.reports.allSatisfy { $0.switchGeneration == nil })
        #expect(h.state.qualityNote == nil)

        h.player.firstFrame()
        await h.advance(5_000)
        #expect(h.gateway.reports.contains { $0.switchGeneration == 7 && $0.state == .playing })
        #expect(h.gateway.stoppedSessions == ["s1"])
        #expect(h.state.quality == .auto)
        #expect(h.state.autoStep == .original)
        #expect(h.state.qualityNote == "Connection recovered; playing the original file.")
    }

    @Test func failedNewStreamRestoresThePriorSessionAndReportsFailure() async {
        h.player.autoFrame = false
        h.gateway.answer = { request in
            request.switchGeneration == nil
                ? decision(sessionID: "s1", autoStep: .original)
                : decision(sessionID: "s2", autoStep: .hd)
        }
        var sent = false
        h.gateway.reply = { report in
            guard !sent, report.positionMs >= 5_000 else { return ProgressReply(completed: false) }
            sent = true
            return ProgressReply(completed: false, lowerTo: .hd, switchGeneration: 9)
        }
        h.controller.start(request)
        await h.run()
        await h.advance(5_000)
        #expect(h.player.loads.count == 2)
        h.player.fail(PlayerFailure(kind: .decoder, detail: "fake decoder rejected stream"))
        await h.run()
        #expect(h.player.loads.count == 3)
        #expect(h.state.autoStep == .original)
        #expect(h.gateway.stoppedSessions == ["s2"])
        await h.advance(5_000)
        #expect(h.gateway.reports.contains { $0.switchFailedGeneration == 9 })
        #expect(h.gateway.reports.allSatisfy { $0.switchGeneration == nil })
    }

    @Test func decisionFailureKeepsPlayingAndStaleGenerationDoesNotReload() async {
        h.gateway.answer = { request in
            if request.switchGeneration != nil { throw APIError(status: 503, message: "busy") }
            return decision(autoStep: .hd)
        }
        var sent = false
        h.gateway.reply = { report in
            guard report.positionMs >= 5_000 else { return ProgressReply(completed: false) }
            if !sent {
                sent = true
                return ProgressReply(completed: false, lowerTo: .fullHD, switchGeneration: 11)
            }
            return ProgressReply(completed: false, lowerTo: .fullHD, switchGeneration: 11)
        }
        h.controller.start(request)
        await h.run()
        await h.advance(5_000)
        #expect(h.gateway.decisions.count == 2)
        #expect(h.state.autoStep == .hd)
        #expect(h.state.status == .playing)
        await h.advance(10_000)
        #expect(h.gateway.reports.contains { $0.switchFailedGeneration == 11 })
        #expect(h.gateway.decisions.count == 2)
    }

    @Test func airPlayRouteChangeRestoresThePriorStreamBeforeAFrame() async {
        let servers = TestServers(baseURL: url("https://home.example.net"), local: url("http://192.168.1.20:27588"), apiLevel: 24)
        let h = Harness(quality: .auto, servers: servers)
        h.player.autoFrame = false
        h.gateway.answer = { request in
            request.switchGeneration == nil
                ? decision(sessionID: "s1", url: url("https://home.example.net/api/playback/s1/master.m3u8"), autoStep: .hd)
                : decision(sessionID: "s2", url: url("https://home.example.net/api/playback/s2/master.m3u8"), autoStep: .fullHD)
        }
        var sent = false
        h.gateway.reply = { report in
            guard !sent, report.positionMs >= 5_000 else { return ProgressReply(completed: false) }
            sent = true
            return ProgressReply(completed: false, lowerTo: .fullHD, switchGeneration: 13)
        }
        h.controller.start(request)
        await h.run()
        await h.advance(5_000)
        #expect(h.player.loads.count == 2)
        h.player.airPlay(true)
        await h.run()
        #expect(h.player.loads.count == 3)
        #expect(h.player.loads.last?.url.host() == "192.168.1.20")
        #expect(h.state.autoStep == .hd)
        #expect(h.gateway.stoppedSessions == ["s2"])
        await h.advance(5_000)
        #expect(h.gateway.reports.contains { $0.switchFailedGeneration == 13 })
    }
}

/// What every play measures and reports, whatever its quality.
@MainActor
@Suite struct MeasurementTests {
    let h = Harness()

    private func startPlaying() async {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Harbor Lights"), decision())
    }

    @Test func heartbeatsCarryTheLinkTheStartAndTheStalls() async throws {
        h.player.throughputBps = 12_000_000
        h.player.readyOnLoad = false
        h.gateway.answer = { _ in decision() }
        h.controller.start(PlayRequest(fileID: 1, startMs: 0, title: "Harbor Lights"))
        await h.run()
        await h.advance(1_500)
        h.player.ready()
        await h.run()
        // Loading, then playing.
        #expect(h.states == [.buffering, .playing])
        let first = try #require(h.gateway.reports.last)
        #expect(first.throughputBps == 12_000_000)
        #expect(first.startupMs == 1_500)
        #expect(first.stalls == 0)
        #expect(first.stallMs == 0)
        await h.advance(10_000)
        // The start is sent once.
        #expect(h.gateway.reports.last?.startupMs == nil)

        // A stall reports at once, and its time as it goes.
        h.player.buffer()
        await h.run()
        #expect(h.gateway.reports.last?.state == .buffering)
        #expect(h.gateway.reports.last?.stalls == 1)
        await h.advance(3_000)
        h.player.ready()
        await h.run()
        #expect(h.gateway.reports.last?.state == .playing)
        #expect(h.gateway.reports.last?.stalls == 1)
        #expect(h.gateway.reports.last?.stallMs == 3_000)
    }

    @Test func aSeekOrAResumeIsNotAStall() async {
        await startPlaying()
        await h.advance(10_000)
        h.controller.seek(toMs: 20_000)
        h.player.buffer()
        await h.run()
        h.player.ready()
        await h.run()
        h.controller.pause()
        await h.run()
        h.controller.play()
        h.player.buffer()
        await h.run()
        h.player.ready()
        await h.advance(10_000)
        #expect(h.gateway.reports.allSatisfy { $0.stalls == 0 && $0.stallMs == 0 })
        // The tvOS transport bar's own seek counts as one too.
        h.controller.noteSeek(playerMs: 40_000)
        h.player.buffer()
        await h.run()
        #expect(h.gateway.reports.last?.stalls == 0)
    }

    @Test func aReloadStartsTheCountAgainAndLeavesTheOldStreamPlaying() async {
        var statuses: [PlayerStatus] = []
        h.gateway.answer = { [h] _ in
            statuses.append(h.state.status)
            return decision(audio: [english, commentary])
        }
        h.controller.start(PlayRequest(fileID: 1, startMs: 0, title: "Harbor Lights"))
        await h.run()
        await h.advance(10_000)
        h.player.buffer()
        await h.run()
        h.player.ready()
        await h.advance(5_000)
        #expect(h.gateway.reports.last?.stalls == 1)

        // A track the player cannot switch itself: a new stream from the server, where the old one got to.
        h.player.playsAudio = { _ in false }
        h.controller.selectAudio(2)
        await h.run()
        #expect(statuses == [.loading, .playing])
        #expect(h.player.loads.last?.startPositionMs == 15_000)
        // The server heard "buffering" while it decided.
        #expect(h.states.suffix(2) == [.buffering, .playing])
        #expect(h.gateway.reports.last?.stalls == 0)
        #expect(h.gateway.reports.last?.stallMs == 0)
    }
}
