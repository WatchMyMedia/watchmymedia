import Foundation
import Testing
@testable import WMMFeatures
import WMMModel

/// The lifecycle both players' models share (PLAN-APPLE-REVIEW.md §5.2), on a spy.
@MainActor
@Suite struct PlayerSessionTests {
    private let spy = SpyPlaybackController()
    private let exits = Counter()
    private let session: PlayerSession

    init() {
        let exits = exits
        session = PlayerSession(
            controller: spy,
            request: PlayRequest(fileID: 9101, title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark"),
            onExit: { exits.count += 1 }
        )
    }

    @Test func startStartsTheControllerOnceAndFollowsItsState() async {
        let changes = Counter()
        session.start(stateChanged: { changes.count += 1 })
        session.start(stateChanged: { changes.count += 100 })
        #expect(spy.calls == ["start 9101"])
        #expect(changes.count == 1, "told at once")
        spy.state.positionMs = 60_000
        #expect(await eventually { changes.count == 2 })
        spy.state.status = .paused
        #expect(await eventually { changes.count == 3 })
    }

    @Test func nothingIsFollowedOnceDisposed() async {
        let changes = Counter()
        session.start(stateChanged: { changes.count += 1 })
        session.dispose()
        session.dispose()
        #expect(spy.calls == ["start 9101", "stop", "release"])
        #expect(session.isDisposed)
        spy.state.positionMs = 60_000
        await settle()
        #expect(changes.count <= 2, "at most the change already on its way")
        spy.state.positionMs = 70_000
        await settle()
        #expect(changes.count <= 2)
    }

    @Test func theNextEpisodeBecomesTheRequestWithItsPictures() async {
        let nexts = Counter()
        session.start(startedNext: { nexts.count += 1 })
        spy.state.title = "Harbor Lights"
        spy.state.subtitle = "S03 E08 · Ebb"
        spy.state.backdropPath = "/b.jpg"
        spy.state.stillPath = "/s.jpg"
        spy.state.posterPath = "/p.jpg"
        spy.send(.startedNext(fileID: 9102))
        #expect(await eventually { nexts.count == 1 })
        #expect(session.request == PlayRequest(fileID: 9102, title: "Harbor Lights", subtitle: "S03 E08 · Ebb", backdropPath: "/b.jpg", stillPath: "/s.jpg", posterPath: "/p.jpg"))
        session.restart()
        #expect(spy.calls.last == "start 9102", "Try again plays the episode now playing")
    }

    @Test func anotherEpisodePlaysOnTheSameControllerAndBecomesTheRequest() {
        session.start()
        let other = PlayRequest(fileID: 9104, title: "Harbor Lights", subtitle: "S03 E10 · Slack Water")
        session.play(other)
        #expect(session.request == other)
        session.restart()
        #expect(spy.calls == ["start 9101", "start 9104", "start 9104"], "Try again plays the episode chosen")
    }

    @Test func theEndLeavesOnceButAnAdminsStopWaitsForBack() async {
        session.start()
        spy.state.stopMessage = "Dinner’s ready."
        spy.send(.exit)
        await settle()
        #expect(exits.count == 0, "the message shows first")
        session.finish()
        session.finish()
        #expect(exits.count == 1)
        spy.state.stopMessage = nil
        spy.send(.exit)
        await settle()
        #expect(exits.count == 1, "once")
    }

    @Test func theControllersExitLeaves() async {
        session.start()
        spy.send(.exit)
        #expect(await eventually { exits.count == 1 })
    }

    @Test func goingToTheBackgroundClosesThePlayOnceUntilItIsReopened() {
        session.start()
        #expect(session.closeInBackground())
        #expect(!session.closeInBackground())
        #expect(spy.calls == ["start 9101", "pause", "close"])
        #expect(session.isClosedInBackground)
        #expect(session.reopen())
        #expect(!session.reopen())
        #expect(session.closeInBackground())
        #expect(spy.calls.suffix(2) == ["pause", "close"])
    }

    @Test func aDisposedPlayIsNotClosedInTheBackground() {
        session.start()
        session.dispose()
        #expect(!session.closeInBackground())
        #expect(!spy.calls.contains("close"))
    }

    private func settle() async {
        for _ in 0..<20 { await Task.yield() }
    }
}
