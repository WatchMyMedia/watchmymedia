import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

@Suite @MainActor struct GrowingRecordingTests {
    @Test func durationAndSeekLimitAdvanceWithoutFetchingAnotherDecision() async {
        let h = Harness()
        let start = TestClock.wallStart.addingTimeInterval(-60)
        await h.start(.init(fileID: 0, startMs: 0, title: "Station 9", recordingID: "r1")) { request in
            var d = decision(vod: false, startMs: request.startMs ?? 0, durationMs: 60_000)
            d.growing = .init(startedAt: start, endsAt: start.addingTimeInterval(3600))
            return d
        }
        #expect(h.state.durationMs == 60_000)
        #expect(h.gateway.decisions.first?.recordingID == "r1")
        #expect(h.gateway.decisions.first?.vod == false)
        await h.advance(10_000)
        #expect(h.state.durationMs == 70_000)
        #expect(h.gateway.decisions.count == 1, "Progress comes from the clock, with no decision polling")
        h.controller.seek(toMs: 90_000)
        await h.run()
        #expect(h.gateway.decisions.last?.startMs == 70_000, "A seek never goes past what has been recorded")
        #expect(h.gateway.searches.isEmpty && h.gateway.automaticRequests.isEmpty, "An unlinked recording does not ask file 0 for subtitles")
        #expect(h.gateway.reports.allSatisfy { $0.recordingID == "r1" })
        h.controller.release(); await h.run()
    }
    @Test func theRecordedLengthComesFromTheDecisionNotThisDevicesClockAgainstTheServers() async {
        let h = Harness()
        // This device's date is 10 minutes ahead of the server's: the length must not jump by them.
        let start = TestClock.wallStart.addingTimeInterval(-60 - 600)
        await h.start(.init(fileID: 0, startMs: 0, title: "Station 9", recordingID: "r1")) { request in
            var d = decision(vod: false, startMs: request.startMs ?? 0, durationMs: 60_000)
            d.growing = .init(startedAt: start, endsAt: start.addingTimeInterval(3600))
            return d
        }
        #expect(h.state.durationMs == 60_000)
        await h.advance(5_000)
        #expect(h.state.durationMs == 65_000)
        h.controller.release(); await h.run()
    }
    @Test func atItsPlannedEndTheLabelAndGoToNowGoAndThePlayCarriesOn() async {
        let h = Harness()
        let start = TestClock.wallStart.addingTimeInterval(-60)
        await h.start(.init(fileID: 0, startMs: 0, title: "Station 9", recordingID: "r1")) { request in
            var d = decision(vod: false, startMs: request.startMs ?? 0, durationMs: 60_000)
            d.growing = .init(startedAt: start, endsAt: start.addingTimeInterval(90))
            return d
        }
        #expect(h.state.growing != nil && h.state.showsGoToNow)
        await h.advance(40_000)
        #expect(h.state.growing == nil, "Past its end it is no longer recording")
        #expect(h.state.durationMs == 90_000)
        #expect(!h.state.showsGoToNow)
        #expect(h.state.status == .playing && h.events.isEmpty, "The play carries on")
        await h.advance(10_000)
        #expect(h.state.durationMs == 90_000)
        #expect(h.gateway.decisions.count == 1)
        h.controller.release(); await h.run()
    }
    @Test func aStreamThatEndsEarlyStopsGrowingWhereItEnded() async {
        let h = Harness()
        let start = TestClock.wallStart.addingTimeInterval(-60)
        await h.start(.init(fileID: 0, startMs: 0, title: "Station 9", recordingID: "r1")) { request in
            var d = decision(vod: false, startMs: request.startMs ?? 0, durationMs: 60_000)
            d.growing = .init(startedAt: start, endsAt: start.addingTimeInterval(3600))
            return d
        }
        await h.advance(12_000)
        let reached = h.state.positionMs
        h.player.end()
        await h.run()
        #expect(h.state.growing == nil)
        #expect(h.state.durationMs == reached)
        await h.advance(60_000)
        #expect(h.state.durationMs == reached, "A stopped recording doesn't grow forever")
        h.controller.release(); await h.run()
    }
    @Test func goToNowShowsOnlyMoreThanTenSecondsBehind() {
        var state = PlayerState()
        state.growing = .init(startedAt: TestClock.wallStart, endsAt: TestClock.wallStart.addingTimeInterval(3600))
        state.durationMs = 600_000
        state.positionMs = 595_000
        #expect(!state.showsGoToNow)
        state.positionMs = 589_000
        #expect(state.showsGoToNow)
        state.growing = nil
        #expect(!state.showsGoToNow)
    }
}
