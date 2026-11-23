import Foundation
import Testing
import WMMModel
@testable import WMMFeatures

/// A channel that can't play on Apple TV: why in the viewer's words, then
/// what can be done, the most useful first.
@Suite struct TVLiveProblemTests {
    private let lksd = LiveChannel(id: "c71", number: "7.1", name: "LKSD")
    private let start = Date(timeIntervalSince1970: 1_800_000_000)

    private func state(_ status: LivePlayerStatus, reason: String? = nil, error: String? = nil) -> LivePlayerState {
        var state = LivePlayerState()
        state.status = status
        state.channel = lksd
        state.stopReason = reason
        state.error = error
        return state
    }

    @Test func noTunerFreeOffersTheRecordingsThatHoldThem() throws {
        let holding = [
            LiveRecording(id: "a", channelID: "c21", number: "2.1", channelName: "HRBR", title: "Harbor Lights", start: start, end: start.addingTimeInterval(3600), state: .recording, canCancel: true),
            LiveRecording(id: "b", channelID: "c91", number: "9.1", channelName: "PNVL", title: "Valley Bake-Off", start: start, end: start.addingTimeInterval(1800), state: .recording),
        ]
        let problem = try #require(TVLiveProblem(state: state(.error, reason: "tuners_busy"), recordingNow: holding, otherChannels: true))
        #expect(problem.title == "No tuner is free")
        #expect(problem.why.hasPrefix("Harbor Lights and Valley Bake-Off are recording until "))
        #expect(problem.why.hasSuffix("You can watch either from its start, or stop one to free a tuner."))
        #expect(problem.rows.map(\.title) == ["Watch Harbor Lights from the start", "Watch Valley Bake-Off from the start", "Stop a recording", "Back to the guide"])
        #expect(problem.rows[0].value == "2.1 HRBR")
        #expect(problem.rows[0].action == .watch(holding[0].playRequest(fromStart: true)))
    }

    @Test func noTunerFreeWithoutARecordingOffersTryAgain() throws {
        let problem = try #require(TVLiveProblem(state: state(.error, reason: "tuners_busy", error: LiveTVError.message("tuners_busy")), recordingNow: [], otherChannels: true))
        #expect(problem.why == "All tuners are in use. Try again when someone finishes watching.")
        #expect(problem.rows.map(\.action) == [.tryAgain, .otherChannels, .backToGuide])
    }

    @Test func noSignalNamesTheChannelAndOffersOthers() throws {
        let problem = try #require(TVLiveProblem(state: state(.error, reason: "no_signal"), recordingNow: [], otherChannels: true))
        #expect(problem.title == "No signal on 7.1 LKSD")
        #expect(problem.why == "The antenna isn’t picking this channel up right now. Other channels may be fine.")
        #expect(problem.rows.map(\.title) == ["Try again", "Other channels", "Back to the guide"])
        let alone = try #require(TVLiveProblem(state: state(.error, reason: "no_signal"), recordingNow: [], otherChannels: false))
        #expect(alone.rows.map(\.action) == [.tryAgain, .backToGuide], "no other channel to offer")
    }

    @Test func aRecordingThatTookTheTunerIsWatchedFromItsStart() throws {
        var stopped = state(.stopped, reason: "recording")
        stopped.takeover = LiveRecordingTakeover(recordingID: "r9", title: "Harbor Lights", at: start)
        let problem = try #require(TVLiveProblem(state: stopped, recordingNow: [], otherChannels: true))
        #expect(problem.title == "Stopped for a recording")
        #expect(problem.why == "The tuner was needed to record Harbor Lights, as scheduled.")
        #expect(problem.rows.map(\.title) == ["Watch Harbor Lights from the start", "Other channels", "Back to the guide"])
        #expect(problem.rows[0].action == .watch(PlayRequest(fileID: 0, startMs: 0, title: "Harbor Lights", recordingID: "r9")))
    }

    @Test func anyOtherFailureSaysWhyAndPlayingIsNoProblem() throws {
        let problem = try #require(TVLiveProblem(state: state(.error, reason: "encoder_failed", error: LiveTVError.message("encoder_failed")), recordingNow: [], otherChannels: true))
        #expect(problem.title == "Can’t play 7.1 LKSD")
        #expect(problem.why == LiveTVError.message("encoder_failed"))
        #expect(TVLiveProblem(state: state(.playing), recordingNow: [], otherChannels: true) == nil)
        let paused = try #require(TVLiveProblem(state: state(.stopped, reason: "pause_expired"), recordingNow: [], otherChannels: true))
        #expect(paused.rows.first?.title == "Watch live")
    }
}
