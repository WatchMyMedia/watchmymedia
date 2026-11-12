import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

@MainActor
@Suite struct AutomaticSubtitleTests {
    private let automatic = PlaybackPreferences(subtitles: SubtitlePreferences(languages: ["en"], mode: .on, automaticDownload: true))

    @Test func theServersStartingTrackIsSelectedWithoutAnExtraDecision() async {
        let h = Harness()
        var d = decision(subtitles: [textSub("s2")])
        d.selectedSubtitleID = "s2"
        await h.start(d)
        #expect(h.state.selectedSubtitle == "s2")
        #expect(h.gateway.decisions.count == 1)
        #expect(h.gateway.automaticRequests.isEmpty)
        await h.release()
    }

    @Test func automaticDownloadWaitsForVideoAndRunsOncePerPlay() async {
        let h = Harness(preferences: automatic)
        h.player.readyOnLoad = false
        h.gateway.automatic = { _, _ in textSub("d3") }
        await h.start { r in decision(subtitles: r.subtitleID == "d3" ? [textSub("d3")] : []) }
        #expect(h.gateway.automaticRequests.isEmpty)
        h.player.ready()
        await h.run()
        #expect(h.gateway.automaticRequests.count == 1)
        #expect(h.gateway.automaticRequests.first?.0 == 1)
        #expect(h.gateway.automaticRequests.first?.1 == "a1")
        #expect(h.state.selectedSubtitle == "d3")
        h.controller.selectQuality(.sd)
        await h.run()
        #expect(h.gateway.automaticRequests.count == 1)
        await h.release()
    }

    @Test func offAndExistingLocalTracksNeverDownload() async {
        let off = Harness(preferences: PlaybackPreferences(subtitles: SubtitlePreferences(mode: .off, automaticDownload: true)))
        await off.start(decision())
        #expect(off.gateway.automaticRequests.isEmpty)
        await off.release()

        let local = Harness(preferences: automatic)
        var d = decision(subtitles: [textSub("s2")])
        d.selectedSubtitleID = "s2"
        await local.start(d)
        #expect(local.gateway.automaticRequests.isEmpty)
        await local.release()
    }

    @Test func aManualOffWinsEvenWhenNoTrackWasSelectedYet() async {
        let h = Harness(preferences: automatic)
        h.player.readyOnLoad = false
        await h.start { r in decision(subtitles: r.subtitleID == "d3" ? [textSub("d3")] : []) }
        var answer: CheckedContinuation<DecisionSubtitle?, Never>?
        h.gateway.automatic = { _, _ in await withCheckedContinuation { answer = $0 } }
        h.player.ready()
        let running = Task { await h.run() }
        for _ in 0..<2000 {
            if answer != nil { break }
            await Task.yield()
        }
        #expect(answer != nil)
        h.controller.selectSubtitle(nil)
        answer?.resume(returning: textSub("d3"))
        await running.value
        #expect(h.state.selectedSubtitle == nil)
        #expect(h.gateway.automaticCancellations.count == 1)
        #expect(h.gateway.decisions.count == 1)
        h.controller.selectQuality(.sd)
        await h.run()
        #expect(h.gateway.decisions.last?.subtitleMode == .off)
        await h.release()
    }

    @Test func aProviderFailureLeavesVideoPlaying() async {
        let h = Harness(preferences: automatic)
        h.gateway.automatic = { _, _ in throw APIError(status: 429, message: "No subtitle downloads left.") }
        await h.start(decision())
        #expect(h.state.status == .playing)
        #expect(h.state.error == nil)
        #expect(h.state.selectedSubtitle == nil)
        #expect(h.gateway.automaticRequests.count == 1)
        await h.release()
    }

    @Test func localFilesNeverRequestAnAutomaticDownload() async {
        let h = Harness(preferences: automatic)
        await h.start(decision(protocol: .file, url: URL(filePath: "/tmp/fictional-offline-movie.mp4")))
        #expect(h.gateway.automaticRequests.isEmpty)
        await h.release()
    }

    @Test func offlineCopiesApplyPreferencesOnlyToFinishedLocalTracks() async {
        let h = Harness(preferences: automatic)
        await h.start(decision(protocol: .file, url: URL(filePath: "/tmp/fictional-offline-movie.mp4"), subtitles: [textSub("s2")]))
        #expect(h.state.selectedSubtitle == "s2")
        #expect(h.state.isOffline)
        #expect(h.gateway.automaticRequests.isEmpty)
        h.controller.selectSubtitle(nil)
        await h.advance(1000)
        #expect(h.state.selectedSubtitle == nil, "manual Off remains Off on later refreshes")
        await h.release()
    }

    @Test func delayedPreferencesDoNotDelayVideoOrLoseTheAutomaticAttempt() async {
        let h = Harness()
        var loaded: CheckedContinuation<Void, Never>?
        h.prefs.refreshAction = {
            await withCheckedContinuation { loaded = $0 }
            h.prefs.playback = automatic
        }
        h.gateway.answer = { _ in decision() }
        h.controller.start(PlayRequest(fileID: 1, title: "Harbor Lights"))
        let running = Task { await h.run() }
        for _ in 0..<2000 {
            if loaded != nil && !h.player.loads.isEmpty { break }
            await Task.yield()
        }
        #expect(!h.player.loads.isEmpty, "the preference fetch never blocks media loading")
        #expect(h.gateway.automaticRequests.isEmpty)
        loaded?.resume()
        await running.value
        #expect(h.gateway.automaticRequests.count == 1)
        await h.release()
    }

    @Test func handbackPreservesQualityAndOffWithoutChangingThePhoneDefault() async {
        let h = Harness(quality: .fullHD)
        await h.start(PlayRequest(fileID: 1, title: "Harbor Lights", subtitleMode: .off, qualityOverride: .sd), decision())
        #expect(h.gateway.decisions.first?.quality == .sd)
        #expect(h.gateway.decisions.first?.subtitleMode == .off)
        #expect(h.prefs.quality == .fullHD)
        await h.release()
    }
}
