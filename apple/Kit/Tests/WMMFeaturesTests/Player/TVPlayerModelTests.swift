import Foundation
import Testing
@testable import WMMFeatures
import WMMModel

/// The Apple TV player's model on a spy and a manual clock, started.
@MainActor
private struct TVHarness {
    let spy = SpyPlaybackController()
    let model: TVPlayerModel
    let exits = Counter()

    init(status: PlayerStatus = .playing) {
        let exits = exits
        model = TVPlayerModel(
            controller: spy,
            request: PlayRequest(fileID: 9101, title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark"),
            onExit: { exits.count += 1 }
        )
        spy.state.status = status
        model.start()
    }
}

@MainActor
@Suite struct TVPlayerModelTests {
    private let next = UpNext(fileID: 9102, episodeID: 100038, showID: 100, showTitle: "Harbor Lights", season: 3, episode: 8, title: "Ebb")
    private let intro = Segment(kind: .intro, startMs: 60_000, endMs: 105_000)

    @Test func startingStartsTheControllerWithTheRequest() {
        let h = TVHarness()
        #expect(h.spy.calls == ["start 9101"])
        #expect(h.model.overlay == nil)
        #expect(h.model.showsVideo)
        #expect(h.model.videoTakesInput)
    }

    @Test func theNextEpisodeCardWaitsWhileTheSystemsControlsAreUp() {
        let h = TVHarness()
        let prompt = UpNextPrompt(next: next, countdownMs: 7_000)
        h.spy.state.nextEpisode = next
        h.spy.state.upNext = prompt
        #expect(h.model.overlay == .upNext(prompt))
        #expect(h.model.contextualActions.isEmpty)
        #expect(h.model.videoTakesInput)
        h.model.transportBarChanged(visible: true)
        #expect(h.model.overlay == nil)
        #expect(h.model.transportBarActions == [.nextEpisode])
        h.model.transportBarChanged(visible: false)
        #expect(h.model.overlay == .upNext(prompt))
    }

    @Test func promptsComeOneAtATimeMostImportantFirst() {
        let h = TVHarness()
        h.spy.state.upNext = UpNextPrompt(next: next, countdownMs: 7_000)
        h.spy.state.askStillWatching = true
        #expect(h.model.overlay == .stillWatching)
        #expect(h.model.showsVideo)
        #expect(!h.model.videoTakesInput)
        h.spy.state.stopMessage = "Dinner’s ready."
        #expect(h.model.overlay == .stopped("Dinner’s ready."))
        #expect(!h.model.showsVideo)
        h.spy.state.error = "ffmpeg exited"
        #expect(h.model.overlay == .failed("ffmpeg exited"))
        #expect(!h.model.showsVideo)
        #expect(h.model.contextualActions.isEmpty)
        #expect(h.model.transportBarActions.isEmpty)
    }

    @Test func skipIsAContextualActionAndNextEpisodeATransportBarButton() {
        let h = TVHarness()
        #expect(h.model.contextualActions.isEmpty)
        #expect(h.model.transportBarActions.isEmpty)
        h.spy.state.nextEpisode = next
        #expect(h.model.transportBarActions.map(\.title) == ["Next Episode"])
        h.spy.state.skip = SkipPrompt(segment: intro, label: "Skip Intro")
        #expect(h.model.contextualActions == [TVPlayerAction(kind: .skip, title: "Skip Intro")])
        h.spy.state.askStillWatching = true
        #expect(h.model.contextualActions.isEmpty)
        #expect(h.model.transportBarActions.isEmpty)
    }

    @Test func autoDelayCountsTheSecondsDownInTheSkipsTitle() {
        func title(_ progress: Double) -> String { TVPlayerAction.skip(SkipPrompt(segment: intro, label: "Skip Intro", autoProgress: progress)).title }
        #expect(title(0) == "Skip Intro in 3")
        #expect(title(0.25) == "Skip Intro in 3")
        #expect(title(0.34) == "Skip Intro in 2")
        #expect(title(0.9) == "Skip Intro in 1")
        #expect(title(1) == "Skip Intro in 1")
    }

    @Test func actionsSkipAndPlayTheNextEpisode() {
        let h = TVHarness()
        h.model.perform(.skip)
        h.model.perform(.nextEpisode)
        #expect(h.spy.calls == ["start 9101", "skip", "playNext"])
        #expect(h.spy.inputs == 2)
    }

    @Test func theTransportBarComingUpAndItsSeeksCountAsInput() {
        let h = TVHarness()
        h.model.transportBarChanged(visible: true)
        #expect(h.spy.inputs == 1)
        h.model.transportBarChanged(visible: false)
        #expect(h.spy.inputs == 1)
        h.model.userSeeked(toPlayerMs: 70_000)
        #expect(h.spy.calls.last == "noteSeek 70000")
    }

    @Test func soundAndTimingApplyAtOnceAndCountAsInput() {
        let h = TVHarness()
        h.model.setSound(.reduceLoudSounds, to: .medium)
        h.model.moveTiming(later: false)
        h.model.moveTiming(later: true)
        h.model.resetTiming()
        h.model.saveTimingForEveryone()
        #expect(h.spy.calls == ["start 9101", "sound reduceLoudSounds medium", "timing -100", "timing 100", "timing reset", "timing everyone"])
        #expect(h.spy.inputs == 5)
    }

    @Test func picksApplyAtOnce() {
        let h = TVHarness()
        h.model.pickAudio(2)
        h.model.pickSubtitle("s4")
        h.model.pickSubtitle(nil)
        h.model.download("os1")
        h.model.pickQuality(.hd)
        #expect(h.spy.calls == ["start 9101", "audio 2", "subtitle s4", "subtitle off", "download os1", "quality 720"])
        #expect(h.spy.inputs == 5)
        #expect(h.spy.state.status == .playing)
    }

    @Test func menuStopsAtStillWatchingPutsTheCardAwayOrLeaves() {
        let h = TVHarness()
        h.spy.state.askStillWatching = true
        h.model.back()
        #expect(h.spy.calls.last == "stillWatching false")
        h.spy.state.askStillWatching = false
        h.spy.state.upNext = UpNextPrompt(next: next, countdownMs: 7_000)
        h.model.back()
        #expect(h.spy.calls.last == "watchCredits")
        #expect(h.exits.count == 0)
        h.spy.state.upNext = nil
        h.model.back()
        #expect(h.exits.count == 1)
        h.model.back()
        #expect(h.exits.count == 1)
    }

    @Test func onlyAButtonAnswersStillWatching() {
        let h = TVHarness()
        h.model.playPause()
        #expect(h.spy.state.status == .paused)
        h.spy.state.askStillWatching = true
        let before = h.spy.calls
        h.model.playPause()
        #expect(h.spy.calls == before, "Play/Pause is not an answer")
        h.model.answerStillWatching(keepWatching: true)
        #expect(h.spy.calls.last == "stillWatching true")
    }

    @Test func theTitleViewSaysWhenItEndsWhileTheBarShows() {
        let zone = TimeZone(identifier: "America/New_York")!
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let at = calendar.date(from: DateComponents(year: 2026, month: 11, day: 23, hour: 21, minute: 18))!
        let now = WallClock(at)
        let spy = SpyPlaybackController()
        let model = TVPlayerModel(
            controller: spy,
            request: PlayRequest(fileID: 9101, title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark"),
            onExit: {},
            now: { now.date },
            clock: LiveClock(locale: Locale(identifier: "en_US"), calendar: calendar)
        )
        spy.state.status = .playing
        model.start()
        spy.state.durationMs = 52 * 60_000
        spy.state.positionMs = 29 * 60_000
        func plain(_ s: String??) -> String? { s.flatMap { $0 }?.replacingOccurrences(of: "\u{202F}", with: " ") }
        model.transportBarChanged(visible: true)
        #expect(plain(spy.ends.last) == "Ends at 9:41 PM")
        // Paused, the end moves on with the clock.
        spy.state.status = .paused
        now.date = at.addingTimeInterval(5 * 60)
        model.refreshEndsAt()
        #expect(plain(spy.ends.last) == "Ends at 9:46 PM")
        // A recording still being made has no end to give.
        spy.state.growing = .init(startedAt: at, endsAt: at.addingTimeInterval(3600))
        model.refreshEndsAt()
        #expect(spy.ends.last == .some(nil))
    }

    @Test func cantPlayOffersTheQualityBelowAndKeepsIt() {
        let h = TVHarness()
        h.spy.state.qualities = StreamQuality.allCases
        h.spy.state.quality = .original
        h.spy.state.error = "The server couldn’t start the stream."
        #expect(h.model.lowerQuality == .fullHD)
        h.model.playAtLowerQuality()
        #expect(h.spy.calls.suffix(2) == ["quality 1080", "start 9101"])
        h.spy.state.quality = .low
        #expect(h.model.lowerQuality == nil)
    }

    @Test func theAdminsStopIsNotedWithWhenAndWhereAndBackLeaves() async {
        let h = TVHarness()
        h.spy.state.positionMs = 23 * 60_000
        h.spy.state.stopMessage = "Dinner’s ready."
        await settle()
        #expect(h.model.stoppedAt != nil)
        #expect(h.model.stoppedAtMs == 23 * 60_000)
        // The controller's exit waits for the viewer to read it.
        h.spy.send(.exit)
        await settle()
        #expect(h.exits.count == 0)
        h.model.back()
        #expect(h.exits.count == 1)
    }

    @Test func theEndLeavesAndTheNextEpisodeTakesOverTheRequest() async {
        let h = TVHarness()
        h.spy.state.title = "Harbor Lights"
        h.spy.state.subtitle = "S03 E08 · Ebb"
        h.spy.state.stillPath = "/e8.jpg"
        h.spy.state.posterPath = "/harbor.jpg"
        h.spy.send(.startedNext(fileID: 9102))
        await settle()
        #expect(h.model.request.fileID == 9102)
        #expect(h.model.request.subtitle == "S03 E08 · Ebb")
        // A retry keeps the episode's still and poster for Now Playing.
        #expect(h.model.request.stillPath == "/e8.jpg")
        #expect(h.model.request.posterPath == "/harbor.jpg")
        h.model.retry()
        #expect(h.spy.calls.last == "start 9102")
        h.spy.send(.exit)
        await settle()
        #expect(h.exits.count == 1)
    }

    @Test func tryAgainStartsTheSamePlayAgain() {
        let h = TVHarness()
        h.spy.state.error = "ffmpeg exited"
        h.model.retry()
        #expect(h.spy.calls == ["start 9101", "start 9101"])
    }

    @Test func theTVButtonPausesAndReportsClosedOnce() {
        let h = TVHarness()
        h.model.enteredBackground()
        h.model.enteredBackground()
        #expect(h.spy.calls == ["start 9101", "pause", "close"])
        h.model.becameActive()
        h.model.enteredBackground()
        #expect(h.spy.calls.suffix(2) == ["pause", "close"])
    }

    @Test func disposingStopsAndReleasesOnce() {
        let h = TVHarness()
        h.model.dispose()
        h.model.dispose()
        #expect(h.spy.calls == ["start 9101", "stop", "release"])
    }

    /// Lets the model's event task run.
    private func settle() async {
        for _ in 0..<30 { await Task.yield() }
    }
}

/// A wall clock a test moves.
@MainActor
private final class WallClock {
    var date: Date
    init(_ date: Date) { self.date = date }
}
