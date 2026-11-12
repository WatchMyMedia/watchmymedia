import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// android/core/playback/.../OnlineSubtitlesTest.kt.
@MainActor
@Suite struct OnlineSubtitlesTests {
    let h = Harness()
    let request = PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse")

    private func result(_ id: String, trackID: String? = nil) -> OnlineSubtitle {
        OnlineSubtitle(id: id, language: "en", label: "English", release: "Lighthouse.2020.1080p", hashMatch: true, hearingImpaired: false, forced: false, aiTranslated: false, downloads: 10, trackID: trackID)
    }

    @Test func searchesOnceTheVideoPlaysAndNotAgainOnAReload() async {
        h.gateway.online = OnlineSubtitles(results: [result("77")], remaining: 5)
        h.player.readyOnLoad = false
        await h.start(request, decision())
        // Starting the video never waits on the provider.
        #expect(h.gateway.searches.isEmpty)
        #expect(h.state.online == nil)
        h.player.ready()
        await h.run()
        #expect(h.gateway.searches == [1])
        #expect(h.state.online?.results.map(\.id) == ["77"])
        // A quality change reloads the same play: no second search.
        h.controller.selectQuality(.sd)
        await h.run()
        h.player.ready()
        await h.run()
        #expect(h.gateway.searches.count == 1)
    }

    @Test func aServerWithoutAProviderShowsNothing() async {
        await h.start(request, decision())
        #expect(h.gateway.searches.count == 1)
        #expect(h.state.online == nil)
    }

    @Test func downloadingReloadsThePlayWithTheNewTrackOn() async throws {
        h.gateway.online = OnlineSubtitles(results: [result("77")], remaining: 5)
        h.gateway.download = { _ in "d3" }
        h.gateway.subtitleFiles = ["/api/files/1/subtitles/d3.vtt": "WEBVTT\n\n01:00.500 --> 01:03.000\nDownloaded\n"]
        await h.start(request) { [h] r in
            decision(sessionID: "s\(h.gateway.decisions.count)", subtitles: r.subtitleID == "d3" ? [textSub("s2"), textSub("d3")] : [textSub("s2")])
        }
        await h.advance(60_000)
        h.controller.downloadSubtitle("77")
        await h.run()
        #expect(h.gateway.downloads == ["77"])
        let reload = try #require(h.gateway.decisions.last)
        #expect(reload.subtitleID == "d3")
        #expect(reload.playID == "a1")
        #expect(!reload.burnIn)
        #expect(reload.startMs! >= 60_000)
        #expect(h.state.selectedSubtitle == "d3")
        await h.advance(500)
        #expect(h.state.cues.map(\.text) == ["Downloaded"])
        let online = try #require(h.state.online)
        #expect(online.downloading == nil)
        #expect(online.results.map(\.trackID) == ["d3"])
        #expect(online.remaining == 4)
    }

    @Test func aResultDownloadedBeforeIsJustTurnedOn() async {
        h.gateway.online = OnlineSubtitles(results: [result("77", trackID: "d3")])
        await h.start(request, decision(subtitles: [textSub("s2"), textSub("d3")]))
        h.controller.downloadSubtitle("77")
        await h.run()
        #expect(h.gateway.downloads.isEmpty)
        #expect(h.gateway.decisions.count == 1)
        #expect(h.state.selectedSubtitle == "d3")
        #expect(h.gateway.subtitleFetches.map(\.path) == ["/api/files/1/subtitles/d3.vtt"])
    }

    @Test func quotaAndPermissionErrorsShowAndPlayingGoesOn() async throws {
        h.gateway.online = OnlineSubtitles(results: [result("77"), result("78")], remaining: 1)
        h.gateway.download = { _ in throw APIError(status: 429, message: "No subtitle downloads left today; they resume at 00:00 UTC.") }
        await h.start(request, decision())
        h.controller.downloadSubtitle("77")
        await h.run()
        var online = try #require(h.state.online)
        #expect(online.error == "No subtitle downloads left today; they resume at 00:00 UTC.")
        #expect(online.remaining == 0)
        #expect(online.downloading == nil)
        #expect(h.gateway.decisions.count == 1)
        #expect(h.state.error == nil)

        h.gateway.download = { _ in throw APIError(status: 403, message: "Only admins can download subtitles on this server.") }
        h.controller.downloadSubtitle("78")
        await h.run()
        online = try #require(h.state.online)
        #expect(online.error == "Only admins can download subtitles on this server.")
        #expect(h.state.selectedSubtitle == nil)
    }

    @Test func oneDownloadAtATime() async {
        h.gateway.online = OnlineSubtitles(results: [result("77"), result("78")])
        h.gateway.download = { "d\($0)" }
        await h.start(request) { r in decision(subtitles: r.subtitleID.map { [textSub($0)] } ?? []) }
        h.controller.downloadSubtitle("77")
        #expect(h.state.online?.downloading == "77")
        h.controller.downloadSubtitle("78")
        await h.run()
        #expect(h.gateway.downloads == ["77"])
    }
}
