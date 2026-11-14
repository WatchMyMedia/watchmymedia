import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// Asking the server how to play (PLAN-APPLE-REVIEW.md §3.1.2, §3.1.3 and
/// §4.2.1): a codec this device can't decode, a decision a newer request
/// overtook, and the media token beside the decision.
@MainActor
@Suite struct DecisionTests {
    let h = Harness()
    let request = PlayRequest(fileID: 1, startMs: 0, title: "Harbor Lights")

    /// Yields until `condition` holds, at most `times` times; for work held where ``Harness/run()`` would wait for ever.
    private func until(_ times: Int = 100, _ condition: () -> Bool) async {
        for _ in 0..<times where !condition() { await Task.yield() }
    }

    // MARK: A codec the device can't decode (§3.1.2)

    @Test func aDecoderFailureAsksAgainWithoutTheCopiedCodec() async throws {
        h.gateway.answer = { r in
            r.profile.videoCodecs.contains("hevc10")
                ? decision(sessionID: "s1", copiedVideoCodec: "hevc10")
                : decision(sessionID: "s2", mode: .transcode)
        }
        h.controller.start(request)
        await h.run()
        await h.advance(10_000)
        h.player.fail(PlayerFailure(kind: .decoder, detail: "AVFoundationErrorDomain -11821"))
        await h.run()
        let again = try #require(h.gateway.decisions.last)
        #expect(h.gateway.decisions.count == 2)
        #expect(again.profile.videoCodecs == ["h264", "hevc"])
        #expect(again.playID == "a1")
        #expect(again.startMs == 10_000)
        #expect(h.state.status == .playing)
        #expect(h.state.error == nil)
        #expect(h.gateway.stoppedSessions == ["s1"])

        // Once a play: the converted stream failing too is the error, and Retry still leaves the codec out.
        h.player.fail(PlayerFailure(kind: .decoder, detail: "again"))
        await h.run()
        #expect(h.state.error == "This device could not decode the stream.")
        #expect(h.gateway.decisions.count == 2)
        h.controller.play()
        await h.run()
        #expect(h.gateway.decisions.last?.profile.videoCodecs == ["h264", "hevc"])
        #expect(h.state.status == .playing)

        // The next play asks with the whole profile again.
        h.controller.start(request)
        await h.run()
        #expect(h.gateway.decisions.last?.profile.videoCodecs == phoneProfile.videoCodecs)
    }

    @Test func anUnreadableCopiedStreamAsksAgainToo() async {
        h.gateway.answer = { r in
            r.profile.videoCodecs.contains("h264") ? decision(protocol: .file, copiedVideoCodec: "h264") : decision(sessionID: "s2", mode: .transcode)
        }
        h.controller.start(request)
        await h.run()
        h.player.fail(PlayerFailure(kind: .source, detail: "failedToParse"))
        await h.run()
        #expect(h.gateway.decisions.last?.profile.videoCodecs == ["hevc", "hevc10"])
        #expect(h.state.status == .playing)
    }

    @Test func aFailedSecondDecisionKeepsTheDecodersMessage() async {
        h.gateway.answer = { r in
            guard r.profile.videoCodecs.contains("hevc") else { throw APIError(status: 503, message: "Transcoder not available.") }
            return decision(copiedVideoCodec: "hevc")
        }
        h.controller.start(request)
        await h.run()
        h.player.fail(PlayerFailure(kind: .decoder, detail: "-11821"))
        await h.run()
        #expect(h.gateway.decisions.count == 2)
        #expect(h.state.status == .error)
        #expect(h.state.error == "This device could not decode the stream.")
    }

    @Test func otherFailuresConvertedStreamsAndDownloadsDontAskWithoutACodec() async {
        // A network failure is retried as before, with the whole profile.
        await h.start(request, decision(copiedVideoCodec: "hevc"))
        h.player.fail(PlayerFailure(kind: .network, detail: "offline"))
        await h.run()
        #expect(h.gateway.decisions.last?.profile.videoCodecs == phoneProfile.videoCodecs)

        // A converted stream has no copied codec to leave out.
        await h.start(request, decision(mode: .transcode))
        h.player.fail(PlayerFailure(kind: .decoder, detail: "-11821"))
        await h.run()
        #expect(h.state.error == "This device could not decode the stream.")

        // A download plays the file it has.
        let offline = Harness()
        await offline.start(request, decision(protocol: .file, url: URL(fileURLWithPath: "/tmp/media.mp4"), copiedVideoCodec: "hevc"))
        offline.player.fail(PlayerFailure(kind: .decoder, detail: "-11821"))
        await offline.run()
        #expect(offline.gateway.decisions.count == 1)
        #expect(offline.state.error == "This device could not decode the stream.")
    }

    // MARK: A decision a newer request overtook (§3.1.3)

    @Test func aDecisionOvertakenByANewerOneEndsItsSession() async {
        await h.start(request, decision())
        h.gateway.answer = { r in decision(sessionID: r.audioIndex == 2 ? "s2" : "s3", selectedAudio: r.audioIndex) }
        var held: CheckedContinuation<Void, Never>?
        h.gateway.deciding = { r in if r.audioIndex == 2 { await withCheckedContinuation { held = $0 } } }
        h.controller.selectAudio(2)
        await until { held != nil }
        h.controller.selectAudio(1)
        await until { h.player.loads.count == 2 }
        #expect(h.player.loads.last?.url == url("http://srv/api/playback/s3/master.m3u8?access_token=tok1"))
        held?.resume()
        await h.run()
        // The overtaken s2 never plays, and its server session ends.
        #expect(h.player.loads.count == 2)
        #expect(Set(h.gateway.stoppedSessions) == ["s1", "s2"])
        #expect(h.state.selectedAudio == 1)
    }

    @Test func closingWhileTheServerDecidesEndsTheSessionItMakes() async {
        var held: CheckedContinuation<Void, Never>?
        h.gateway.deciding = { _ in await withCheckedContinuation { held = $0 } }
        h.gateway.answer = { _ in decision(sessionID: "s1") }
        h.controller.start(request)
        await until { held != nil }
        h.controller.stop()
        held?.resume()
        await h.run()
        #expect(h.player.loads.isEmpty)
        #expect(h.gateway.stoppedSessions == ["s1"])
        #expect(h.gateway.reports.isEmpty)
    }

    @Test func anOvertakenQualitySwitchIsReportedFailed() async {
        let h = Harness(quality: .auto, servers: TestServers(baseURL: url("http://srv"), local: nil, apiLevel: 24))
        h.player.autoFrame = false
        h.gateway.answer = { r in
            r.switchGeneration != nil ? decision(sessionID: "s2", autoStep: .hd)
                : decision(sessionID: r.audioIndex == 2 ? "s3" : "s1", selectedAudio: r.audioIndex, autoStep: .original)
        }
        var sent = false
        h.gateway.reply = { report in
            guard !sent, report.positionMs >= 5_000 else { return ProgressReply(completed: false) }
            sent = true
            return ProgressReply(completed: false, lowerTo: .hd, switchGeneration: 9)
        }
        var held: CheckedContinuation<Void, Never>?
        h.gateway.deciding = { r in if r.switchGeneration != nil { await withCheckedContinuation { held = $0 } } }
        h.controller.start(request)
        await h.run()
        h.player.firstFrame()
        await h.clock.advance(by: 5_000) { for _ in 0..<5 { await Task.yield() } }
        await until { held != nil }
        #expect(h.gateway.decisions.last?.switchGeneration == 9)
        // The viewer picks another track while the switch is out.
        h.controller.selectAudio(2)
        await until { h.player.loads.count == 2 }
        held?.resume()
        await h.run()
        await h.advance(5_000)
        #expect(h.gateway.stoppedSessions.contains("s2"))
        #expect(h.player.loads.count == 2)
        #expect(h.gateway.reports.contains { $0.switchFailedGeneration == 9 })
    }

    // MARK: The media token beside the decision (§4.2.1)

    @Test func theTokenIsAskedForBesideTheDecision() async {
        var tokenAsked = false
        h.gateway.deciding = { [h] _ in
            for _ in 0..<100 where h.gateway.tokens == 0 { await Task.yield() }
            tokenAsked = h.gateway.tokens == 1
        }
        await h.start(request, decision())
        #expect(tokenAsked)
        #expect(h.gateway.tokens == 1)
        #expect(h.player.loads.last?.url == url("http://srv/api/playback/s1/master.m3u8?access_token=tok1"))

        // A reload keeps the valid token: no second request.
        h.gateway.answer = { r in decision(sessionID: "s2", selectedAudio: r.audioIndex) }
        h.controller.selectAudio(2)
        await h.run()
        #expect(h.gateway.tokens == 1)
        #expect(h.player.loads.last?.url == url("http://srv/api/playback/s2/master.m3u8?access_token=tok1"))
    }

    @Test func aTokenTheDecisionBringsIsUsed() async {
        let carried = MediaToken(token: "carried", expiresAt: TestClock.wallStart.addingTimeInterval(6 * 3600))
        h.gateway.tokenBeside = false
        await h.start(request, decision(mediaToken: carried))
        #expect(h.gateway.tokens == 0)
        #expect(h.player.loads.last?.url == url("http://srv/api/playback/s1/master.m3u8?access_token=carried"))

        // Even when one was asked for beside it.
        let other = Harness()
        await other.start(request, decision(mediaToken: carried))
        #expect(other.player.loads.last?.url == url("http://srv/api/playback/s1/master.m3u8?access_token=carried"))
    }

    @Test func aServerWhoseDecisionsCarryNoTokenStillGetsOneAsked() async {
        // A gateway that expected a token in the decision, from a server that sent none.
        h.gateway.tokenBeside = false
        await h.start(request, decision())
        #expect(h.gateway.tokens == 1)
        #expect(h.player.loads.last?.url == url("http://srv/api/playback/s1/master.m3u8?access_token=tok1"))
    }

    @Test func aRefusedTokenEndsTheDecisionsSession() async {
        h.gateway.tokening = { throw APIError(status: 500, message: "No token today.") }
        await h.start(request, decision(sessionID: "s1"))
        #expect(h.state.status == .error)
        #expect(h.state.error == "No token today.")
        #expect(h.gateway.stoppedSessions == ["s1"])
        #expect(h.player.loads.isEmpty)
    }
}
