import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// Now Playing, the remote commands and the audio session, through ``PlaybackSystem``.
@MainActor
@Suite struct SystemTests {
    let h = Harness()

    private func startPlaying() async {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark"), decision())
    }

    @Test func nowPlayingShowsTheTitleDurationPositionAndRate() async {
        await startPlaying()
        #expect(h.system.opened == 1)
        let playing = h.system.nowPlaying.last
        #expect(playing == NowPlayingInfo(title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark", durationMs: 50 * minute, positionMs: 0, playing: true))
        await h.advance(5_000)
        h.controller.pause()
        await h.run()
        #expect(h.system.nowPlaying.last == NowPlayingInfo(title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark", durationMs: 50 * minute, positionMs: 5_000, playing: false))
    }

    @Test func remoteCommandsDriveThePlayer() async throws {
        await startPlaying()
        let commands = try #require(h.system.commands)
        commands.pause()
        await h.run()
        #expect(h.state.status == .paused)
        commands.togglePlayPause()
        await h.run()
        #expect(h.state.status == .playing)
        commands.seekBy(10_000)
        commands.seekTo(120_000)
        await h.run()
        #expect(h.player.seeks == [10_000, 120_000])
        commands.togglePlayPause()
        await h.run()
        #expect(h.state.status == .paused)
        commands.play()
        await h.run()
        #expect(h.state.status == .playing)
    }

    @Test func aCallPausesAndResumesWhenTheSystemSaysSo() async throws {
        await startPlaying()
        let commands = try #require(h.system.commands)
        commands.interruption(true, false)
        await h.run()
        #expect(h.state.status == .paused)
        #expect(h.states.last == .paused)
        commands.interruption(false, true)
        await h.run()
        #expect(h.state.status == .playing)

        // Paused before the call: it stays paused after.
        h.controller.pause()
        commands.interruption(true, false)
        commands.interruption(false, true)
        await h.run()
        #expect(h.state.status == .paused)
    }

    @Test func headphonesUnpluggedPause() async throws {
        await startPlaying()
        try #require(h.system.commands).routeLost()
        await h.run()
        #expect(h.state.status == .paused)
        #expect(h.states.last == .paused)
    }

    @Test func systemPausesAreNotTheViewer() async throws {
        // Still watching counts the viewer's input only.
        await startPlaying()
        await h.advance(90 * minute)
        try #require(h.system.commands).routeLost()
        await h.run()
        #expect(h.stillWatching.shouldAsk())
    }

    @Test func releaseGivesUpTheSystem() async {
        await startPlaying()
        await h.release()
        #expect(h.system.closed == 1)
    }
}

/// android/core/playback/.../PlaybackEngine.kt's factory duties.
@MainActor
@Suite struct EngineTests {
    @Test func plansGoThroughTheGatewayAtTheChosenQuality() async throws {
        let clock = TestClock()
        let gateway = FakeGateway(clock: clock)
        let prefs = TestPreferences()
        prefs.setQuality(.fullHD)
        let engine = PlaybackEngine(gateway: gateway, preferences: prefs, client: { client }, profile: phoneProfile, system: FakeSystem())
        let plans = await engine.plans(fileIDs: [7, 8])
        #expect(plans.keys.sorted() == [7, 8])
        #expect(plans[7]?.label == "Direct play")
        let (profile, quality, ids) = try #require(gateway.planRequests.first)
        #expect(profile == phoneProfile)
        #expect(quality == .fullHD)
        #expect(ids == [7, 8])
    }

    @Test func eachScreenGetsItsOwnController() {
        let engine = PlaybackEngine(gateway: FakeGateway(clock: TestClock()), preferences: TestPreferences(), client: { client }, profile: phoneProfile, system: FakeSystem())
        let first = engine.make()
        let second = engine.make()
        #expect(first !== second)
        #expect(first.player == nil)
        #expect(first.state == PlayerState())
    }
}
