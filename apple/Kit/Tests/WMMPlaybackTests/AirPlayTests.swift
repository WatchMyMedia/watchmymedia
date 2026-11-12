import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// AirPlay from an address an Apple TV is unlikely to reach: the play
/// restarts on the server's LAN address at its position (PLAN-IOS.md §5).
@MainActor
@Suite struct AirPlayTests {
    static let tailnet = url("https://studio.tail1234.ts.net")
    static let lan = url("http://192.168.1.20:27588")
    static let stream = url("https://studio.tail1234.ts.net/api/playback/s1/master.m3u8")

    let servers = TestServers(baseURL: tailnet, local: lan)
    let h: Harness

    init() {
        h = Harness(servers: servers)
    }

    private var loaded: URL? { h.player.loads.last?.url }

    private func startPlaying(_ next: UpNext? = nil) async {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Harbor Lights"), decision(url: Self.stream, upNextMs: next == nil ? 0 : 48 * minute, next: next))
    }

    @Test func theLANAddressStandsInForTheOneInUseUnderIt() {
        let route = LocalRoute(from: url("https://home.example.net/wmm/"), to: url("http://192.168.1.20:27588/wmm"))
        #expect(route.apply(to: url("https://home.example.net/wmm/api/files/1/stream?play=a1")) == url("http://192.168.1.20:27588/wmm/api/files/1/stream?play=a1"))
        #expect(route.apply(to: url("https://home.example.net/other/api")) == nil, "not under the address in use")
        #expect(route.apply(to: url("https://cdn.example.net/wmm/api/files/1/stream")) == nil)
        #expect(route.apply(to: url("https://home.example.net/wmmx/api")) == nil)
    }

    @Test func airPlayFromATailnetAddressRestartsOnTheLANAddressWhereItWas() async throws {
        await startPlaying()
        await h.advance(60_000)
        #expect(loaded?.host() == "studio.tail1234.ts.net")

        h.gateway.answer = { _ in decision(sessionID: "s2", url: Self.stream) }
        h.player.airPlay(true)
        await h.run()
        #expect(servers.lookups == 1)
        #expect(h.gateway.decisions.count == 2)
        let restart = try #require(h.gateway.decisions.last)
        #expect(restart.startMs == 60_000)
        #expect(restart.playID == "a1", "the same play carries on")
        #expect(loaded == url("http://192.168.1.20:27588/api/playback/s1/master.m3u8?access_token=tok1"))
        #expect(h.player.loads.last?.startPositionMs == 60_000)
        #expect(h.gateway.stoppedSessions == ["s1"], "the old session ends")

        // Heartbeats still come from the phone, through the address in use.
        let before = h.gateway.reports.count
        await h.advance(20_000)
        #expect(h.gateway.reports.count > before)
        #expect(h.gateway.reports.last?.positionMs == 80_000)
        #expect(h.gateway.reports.last?.state == .playing)
    }

    @Test func withNoLANAddressThePlayGoesOnAsItIs() async {
        servers.local = nil
        await startPlaying()
        await h.advance(30_000)
        h.player.airPlay(true)
        await h.run()
        await h.advance(10_000)
        #expect(servers.lookups == 1)
        #expect(h.gateway.decisions.count == 1)
        #expect(h.player.loads.count == 1)
        #expect(h.state.status == .playing)
        #expect(h.state.positionMs == 40_000)

        // A later AirPlay looks again.
        servers.local = Self.lan
        h.player.airPlay(false)
        await h.run()
        h.player.airPlay(true)
        await h.run()
        #expect(servers.lookups == 2)
        #expect(loaded?.host() == "192.168.1.20")
    }

    @Test func anAddressOnTheLANAlreadyNeedsNoRestart() async {
        let home = TestServers(baseURL: url("http://192.168.1.20:27588"), local: Self.lan)
        let h = Harness(servers: home)
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Harbor Lights"), decision(url: url("http://192.168.1.20:27588/api/playback/s1/master.m3u8")))
        h.player.airPlay(true)
        await h.run()
        #expect(home.lookups == 0)
        #expect(h.gateway.decisions.count == 1)
    }

    @Test func theNextEpisodeAndAnotherAirPlayStayOnTheLANAddress() async {
        await startPlaying(nextEpisode)
        h.gateway.answer = { _ in decision(sessionID: "s2", url: Self.stream, upNextMs: 48 * minute, next: nextEpisode) }
        h.player.airPlay(true)
        await h.run()
        #expect(loaded?.host() == "192.168.1.20")

        h.gateway.answer = { _ in decision(playID: "b1", sessionID: "s3", url: url("https://studio.tail1234.ts.net/api/playback/s3/master.m3u8")) }
        h.controller.playNext()
        await h.run()
        #expect(h.gateway.decisions.last?.fileID == 2)
        #expect(loaded?.absoluteString.hasPrefix("http://192.168.1.20:27588/api/playback/s3/") == true)

        // The route coming and going (as when the item changes) restarts nothing more.
        let decisions = h.gateway.decisions.count
        h.player.airPlay(false)
        await h.run()
        h.player.airPlay(true)
        await h.run()
        #expect(h.gateway.decisions.count == decisions)
        #expect(servers.lookups == 1)
    }

    @Test func afterAirPlayAFailureOnTheLANGoesBackToTheAddressInUse() async {
        await startPlaying()
        h.gateway.answer = { _ in decision(sessionID: "s2", url: Self.stream) }
        h.player.airPlay(true)
        await h.run()
        #expect(loaded?.host() == "192.168.1.20")

        // Back on the phone, it plays on from the LAN; when that fails, the address in use takes over.
        h.player.airPlay(false)
        await h.run()
        #expect(loaded?.host() == "192.168.1.20")
        h.player.fail(PlayerFailure(kind: .network, detail: "offline"))
        await h.run()
        #expect(loaded?.host() == "studio.tail1234.ts.net")
        #expect(h.state.status == .playing)
    }

    @Test func airPlayWhileTheServerDecidesPlaysFromTheLANAtOnce() async {
        // AirPlay is on before the stream starts: the first load is the LAN one.
        h.player.airPlay(true)
        await startPlaying()
        #expect(h.gateway.decisions.count <= 2)
        #expect(loaded?.host() == "192.168.1.20")
    }
}
