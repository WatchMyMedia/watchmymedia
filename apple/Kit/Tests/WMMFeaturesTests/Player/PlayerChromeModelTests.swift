import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@MainActor
@Suite struct PlayerChromeModelTests {
    private func upNext() -> UpNextPrompt {
        UpNextPrompt(next: UpNext(fileID: 9102, episodeID: 100038, showID: 100, showTitle: "Harbor Lights", season: 3, episode: 8, title: "Ebb"), countdownMs: 7_000)
    }

    @Test func startingStartsTheControllerWithTheRequest() {
        let h = PlayerHarness()
        #expect(h.spy.calls == ["start 9101"])
    }

    @Test func overlaysComeOneAtATimeMostImportantFirst() async {
        let h = PlayerHarness()
        #expect(h.model.overlay == nil)
        h.model.openPicker(.subtitles)
        #expect(h.model.overlay == .picker(.subtitles))
        h.spy.state.askStillWatching = true
        await h.time.settle()
        #expect(h.model.overlay == .stillWatching)
        h.spy.state.stopMessage = "Dinner’s ready."
        await h.time.settle()
        #expect(h.model.overlay == .stopped("Dinner’s ready."))
        h.spy.state.error = "ffmpeg exited"
        await h.time.settle()
        #expect(h.model.overlay == .failed("ffmpeg exited"))
        #expect(!h.model.showsControls)
    }

    @Test func theNextEpisodeCardShowsOnceTheControlsAreHiddenAndHidesSkip() async {
        let h = PlayerHarness()
        h.spy.state.upNext = upNext()
        h.spy.state.skip = SkipPrompt(segment: Segment(kind: .credits, startMs: 2_970_000, endMs: nil), label: "Skip Credits")
        await h.time.settle()
        #expect(!h.model.showsUpNext)
        #expect(h.model.showsSkip)
        await h.time.advance(byMs: 3_000)
        #expect(h.model.showsUpNext)
        #expect(h.model.showsMiniProgress)
        #expect(!h.model.showsSkip)
    }

    @Test func thePickerPausesAndPlaybackCarriesOnWhenItCloses() {
        let h = PlayerHarness()
        h.model.openPicker(.audio)
        #expect(h.spy.state.status == .paused)
        #expect(h.model.hideAtMs == nil)
        h.model.selectTab(.quality)
        #expect(h.model.picker == .quality)
        h.model.closePicker()
        #expect(h.spy.state.status == .playing)
        #expect(h.model.controlsVisible)
    }

    @Test func theTabletPanelKeepsPlayingAndDoesNotResumeAPausedVideo() {
        let playing = PlayerHarness()
        playing.model.openPicker(.audio, pausesPlayback: false)
        #expect(playing.spy.state.status == .playing)
        #expect(playing.model.hideAtMs == nil)
        playing.model.pickAudio(2)
        #expect(!playing.spy.calls.contains("pause"))
        #expect(!playing.spy.calls.contains("play"))
        let paused = PlayerHarness(status: .paused)
        paused.model.openPicker(.quality, pausesPlayback: false)
        paused.model.closePicker()
        #expect(paused.spy.state.status == .paused)
        #expect(!paused.spy.calls.contains("play"))
    }

    @Test func choosingAnEpisodeReplacesTheRequestAndResetsScrubbing() {
        let h = PlayerHarness()
        h.model.scrub(x: 0.5, width: 1)
        h.model.openPicker(.audio)
        let next = PlayRequest(fileID: 9102, title: "Harbor Lights", showID: 100, seasonNumber: 3)
        h.model.play(next)
        #expect(h.model.request == next)
        #expect(h.model.scrubMs == nil)
        #expect(h.model.picker == nil)
        #expect(h.spy.calls.last == "start 9102")
        #expect(!h.spy.calls.contains("play"))
        h.model.retry()
        #expect(h.spy.calls.last == "start 9102")
    }

    @Test func thePickerLeavesAPausedVideoPaused() {
        let h = PlayerHarness(status: .paused)
        h.model.openPicker(.audio)
        h.model.closePicker()
        #expect(!h.spy.calls.contains("play"))
    }

    @Test func pickingAppliesAtOnceAndClosesThePicker() {
        let h = PlayerHarness()
        h.model.openPicker(.audio)
        h.model.pickAudio(2)
        #expect(h.model.picker == nil)
        h.model.openPicker(.subtitles)
        h.model.pickSubtitle(nil)
        h.model.openPicker(.quality)
        h.model.pickQuality(.hd)
        #expect(h.spy.calls.filter { !["play", "pause"].contains($0) } == ["start 9101", "audio 2", "subtitle off", "quality 720"])
        #expect(h.model.picker == nil)
    }

    @Test func anOnlineSubtitleKeepsThePickerOpenUntilItIsOn() async {
        let h = PlayerHarness()
        h.spy.state.online = OnlineSubtitles(results: [OnlineSubtitle(id: "9001", language: "en", label: "English", release: "R", hashMatch: true, hearingImpaired: false, forced: false, aiTranslated: false, downloads: 1)])
        h.model.openPicker(.subtitles)
        h.spy.state.online?.downloading = "9001"
        h.model.download("9001")
        await h.time.settle()
        #expect(h.model.picker == .subtitles)
        h.spy.state.online?.downloading = nil
        h.spy.state.online?.results[0].trackID = "d9001"
        await h.time.settle()
        #expect(h.model.picker == nil)
        #expect(h.spy.calls.contains("download 9001"))
    }

    @Test func aFailedDownloadLeavesThePickerOpen() async {
        let h = PlayerHarness()
        h.spy.state.online = OnlineSubtitles(results: [OnlineSubtitle(id: "9001", language: "en", label: "English", release: "R", hashMatch: true, hearingImpaired: false, forced: false, aiTranslated: false, downloads: 1)])
        h.model.openPicker(.subtitles)
        h.spy.state.online?.downloading = "9001"
        h.model.download("9001")
        await h.time.settle()
        h.spy.state.online?.downloading = nil
        h.spy.state.online?.error = "Download failed"
        await h.time.settle()
        #expect(h.model.picker == .subtitles)
    }

    @Test func theFakeDownloadClosesThePickerOnceItsTrackIsOn() async throws {
        // The fake waits on the test's clock, so a busy machine can't make it late.
        let time = ManualTime()
        let fake = FakePlaybackController(sleep: { try await time.sleep($0) })
        let model = PlayerChromeModel(controller: fake, request: PlayRequest(fileID: 9101, title: ""), onExit: {}, now: { time.nowMs }, sleep: { try await time.sleep($0) })
        model.start()
        await time.advance(byMs: 1_600)
        let online = try #require(fake.state.online)
        model.openPicker(.subtitles)
        model.download(online.results[0].id)
        #expect(model.picker == .subtitles)
        await time.advance(byMs: 1_500)
        #expect(model.picker == nil)
        #expect(fake.state.selectedSubtitle == "d\(online.results[0].id)")
        model.dispose()
    }

    @Test func exitLeavesThePlayer() async {
        let h = PlayerHarness()
        h.spy.send(.exit)
        // Wait for the exit itself: a fixed number of yields was too few on a loaded CI runner.
        #expect(await eventually { h.exits.count == 1 })
    }

    @Test func anAdminsStopWaitsForBack() async {
        let h = PlayerHarness()
        h.spy.state.stopMessage = "Dinner’s ready."
        h.spy.send(.exit)
        await h.time.settle()
        #expect(h.exits.count == 0)
        #expect(h.model.stoppedAt != nil)
        h.model.close()
        h.model.close()
        #expect(h.exits.count == 1)
    }

    @Test func theNextEpisodeStartingInPlaceClosesThePickerAndShowsTheControls() async {
        let h = PlayerHarness()
        await h.time.advance(byMs: 3_000)
        #expect(!h.model.controlsVisible)
        h.spy.state.title = "Harbor Lights"
        h.spy.state.subtitle = "S03 E08 · Ebb"
        h.spy.state.stillPath = "/e8.jpg"
        h.spy.state.posterPath = "/harbor.jpg"
        h.spy.send(.startedNext(fileID: 9102))
        await h.time.settle()
        #expect(h.model.controlsVisible)
        #expect(h.model.request.fileID == 9102)
        #expect(h.model.request.subtitle == "S03 E08 · Ebb")
        // A retry keeps the episode's Now Playing picture.
        #expect(h.model.request.stillPath == "/e8.jpg")
        #expect(h.model.request.posterPath == "/harbor.jpg")
        h.model.retry()
        #expect(h.spy.calls.last == "start 9102")
    }

    @Test func goingAwayStopsAndReleasesOnce() {
        let h = PlayerHarness()
        h.model.dispose()
        h.model.dispose()
        #expect(h.spy.calls == ["start 9101", "stop", "release"])
    }

    @Test func theBackgroundPausesAndClosesAndTheVideoStaysPausedOnReturn() async {
        let h = PlayerHarness()
        await h.time.advance(byMs: 3_000)
        h.model.enteredBackground()
        #expect(h.spy.calls.suffix(2) == ["pause", "close"])
        h.model.enteredBackground()
        #expect(h.spy.calls.filter { $0 == "close" }.count == 1)
        h.model.becameActive()
        #expect(h.spy.state.status == .paused)
        #expect(h.model.controlsVisible)
    }

    @Test func airPlayKeepsPlayingInTheBackground() {
        let h = PlayerHarness()
        h.model.enteredBackground(externalPlayback: true)
        #expect(!h.spy.calls.contains("close"))
        #expect(h.spy.state.status == .playing)
    }

    @Test func pictureInPictureGetsAMomentToStartByItself() async {
        let h = PlayerHarness()
        h.model.enteredBackground(pictureInPictureMayStart: true)
        await h.time.advance(byMs: 500)
        h.model.pictureInPictureChanged(active: true, appActive: false)
        await h.time.advance(byMs: PlayerChromeModel.pictureInPictureGraceMs)
        #expect(!h.spy.calls.contains("close"))
        #expect(!h.model.showsControls)
        #expect(h.model.hideAtMs == nil)
    }

    @Test func whenPictureInPictureDoesNotStartTheBackgroundCloses() async {
        let h = PlayerHarness()
        h.model.enteredBackground(pictureInPictureMayStart: true)
        await h.time.advance(byMs: PlayerChromeModel.pictureInPictureGraceMs)
        #expect(h.spy.calls.suffix(2) == ["pause", "close"])
    }

    @Test func closingTheSmallWindowInTheBackgroundPauses() async {
        let h = PlayerHarness()
        h.model.pictureInPictureChanged(active: true, appActive: false)
        h.model.pictureInPictureChanged(active: false, appActive: false)
        #expect(h.spy.calls.suffix(2) == ["pause", "close"])
    }

    @Test func comingBackFromPictureInPictureShowsTheControls() async {
        let h = PlayerHarness()
        h.model.openPicker(.audio)
        h.model.pictureInPictureChanged(active: true, appActive: true)
        #expect(h.model.picker == nil)
        #expect(!h.model.controlsVisible)
        h.model.pictureInPictureChanged(active: false, appActive: true)
        #expect(h.model.controlsVisible)
        #expect(!h.spy.calls.contains("close"))
    }

    @Test func promptsReachTheController() {
        let h = PlayerHarness()
        h.model.skip()
        h.model.playNext()
        h.model.watchCredits()
        h.model.answerStillWatching(keepWatching: true)
        #expect(h.spy.calls.dropFirst() == ["skip", "playNext", "watchCredits", "stillWatching true"])
        #expect(h.spy.inputs == 4)
    }
}
