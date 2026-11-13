import Foundation
import Testing
import WMMFakes
import WMMModel
@testable import WMMFeatures

/// What a Live TV programme, a recording row and the options form offer
/// (PLAN-APPLE-REVIEW.md §5.4): the whole table the six menus draw from.
@Suite @MainActor
struct LiveProgrammeActionsTests {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private let channel = LiveChannel(id: "ch-4.1", number: "4.1", name: "NFLD-HD")

    private func programme(_ kind: Timing, series: Bool = true) -> LiveProgramme {
        let start: Date, end: Date
        switch kind {
        case .past: start = now - 7200; end = now - 3600
        case .onNow: start = now - 600; end = now + 1200
        case .later: start = now + 3600; end = now + 5400
        }
        return LiveProgramme(id: "p-\(kind)", start: start, end: end, title: "Station 9", seriesKey: series ? "station-9" : nil)
    }

    private enum Timing: CaseIterable { case past, onNow, later }

    private func actions(_ timing: Timing, series: Bool = true, recording: Bool = true, mark: LiveRecordingMark? = nil,
                         reminded: Bool = false, canRemind: Bool = true, channel: LiveChannel? = nil) -> LiveProgrammeActions {
        LiveProgrammeActions(programme: programme(timing, series: series), channel: channel ?? self.channel, now: now,
                             recordingAvailable: recording, mark: mark, reminded: reminded, canRemind: canRemind)
    }

    // MARK: Record

    @Test func recordIsOfferedUntilTheProgrammeEndsAndOnlyWhereTheServerRecordsForThisPerson() {
        #expect(actions(.later).recording == .record(rest: false, asks: true, note: nil))
        #expect(actions(.onNow).recording == .record(rest: true, asks: true, note: nil), "Record the rest")
        #expect(actions(.past).recording == nil)
        for timing in Timing.allCases {
            #expect(actions(timing, recording: false).recording == nil, "no capability or no permission: no Record")
        }
    }

    @Test func aFilmOrASpecialRecordsAtOnceAndASeriesAsksWhich() {
        #expect(actions(.later, series: false).recording == .record(rest: false, asks: false, note: nil))
        #expect(actions(.onNow, series: false).recording == .record(rest: true, asks: false, note: nil))
    }

    @Test func aProgrammeSetToRecordOffersNoSecondRecordAndOnlyItsOwnerMayStopIt() {
        for state in [LiveRecordingState.scheduled, .recording, .conflict] {
            let mine = LiveRecordingMark(id: "r", state: state, canCancel: true)
            let theirs = LiveRecordingMark(id: "r", state: state, canCancel: false)
            #expect(actions(.later, mark: mine).recording == .marked(mine))
            #expect(actions(.later, mark: mine).cancel == mine)
            #expect(actions(.later, mark: theirs).recording == .marked(theirs))
            #expect(actions(.later, mark: theirs).cancel == nil, "set by someone else: no Don't record")
        }
        let recording = LiveRecordingMark(id: "r", state: .recording, canCancel: true)
        #expect(actions(.onNow, mark: recording).recordingNow == recording, "Watch from the start")
        #expect(actions(.onNow, mark: LiveRecordingMark(id: "r", state: .scheduled)).recordingNow == nil)
        #expect(actions(.later, mark: LiveRecordingMark(id: "r", state: .conflict)).clashes, "Resolve")
        #expect(!actions(.later, mark: LiveRecordingMark(id: "r", state: .scheduled)).clashes)
    }

    @Test func anEarlierAttemptsNoteIsSaidAndRecordStillOffered() {
        for state in [LiveRecordingState.skipped, .gone, .failed, .partial, .done, .cancelled] {
            let mark = LiveRecordingMark(id: "r", state: state, reason: state == .skipped ? "not_new" : nil)
            #expect(actions(.later, mark: mark).recording == .record(rest: false, asks: true, note: mark.note))
            #expect(actions(.later, mark: mark).cancel == nil)
        }
    }

    // MARK: Remind me

    @Test func onlyAProgrammeThatHasntStartedHasAReminderChoice() {
        #expect(actions(.later, reminded: true).reminder == .set, "the bell, and Remove reminder")
        #expect(actions(.later).reminder == .offered, "Remind me")
        #expect(actions(.later, canRemind: false).reminder == .unavailable, "within the lead time, or no reminders on the server")
        for timing in [Timing.onNow, .past] {
            #expect(actions(timing, reminded: true).reminder == nil)
            #expect(actions(timing).reminder == nil)
        }
    }

    // MARK: Watch

    @Test func watchFollowsTheChannel() {
        #expect(actions(.later).watch)
        #expect(!actions(.later, channel: LiveChannel(id: "ch-9", number: "9.1", name: "Off air", available: false)).watch)
        #expect(actions(.onNow).onNow && !actions(.later).onNow && !actions(.past).onNow)
    }

    // MARK: From the stores

    @Test func theStoresSayWhatTheFakesHold() async throws {
        FakeLatency.duration = .zero
        let guide = FakeLiveGuide(now: now, favourites: true, extras: true)
        let repository = FakeLiveTVRepository(guide: guide)
        let now = now
        repository.now = { now }
        try await repository.refresh()
        let extras = LiveExtrasStore(repository: repository, now: { now })
        await extras.refresh()
        let recordings = LiveRecordingsStore(repository: repository)
        let reminder = try #require(extras.reminders.first { $0.start > now && $0.state != .gone })
        let later = LiveProgramme(id: reminder.programmeID, start: reminder.start, end: reminder.end, title: reminder.title)
        let fromStores = LiveProgrammeActions(later, on: channel, now: now, extras: extras, recordings: recordings)
        #expect(fromStores.reminder == .set)
        #expect(fromStores.recording == (recordings.available ? .record(rest: false, asks: false, note: nil) : nil))
        #expect(LiveProgrammeActions(later, on: channel, now: now, extras: extras).recording == nil, "search's: no Record")
    }

    // MARK: A recording's row

    private func recording(_ state: LiveRecordingState, mine: Bool = true, series: Bool = false, file: Bool = true) -> LiveRecording {
        LiveRecording(id: "r", ruleID: series ? "rule" : nil, channelID: "ch-4.1", number: "4.1", title: "Station 9",
                      start: now, end: now + 3600, state: state, mediaFileID: file ? 9 : nil, canCancel: mine, canDelete: mine)
    }

    @Test func aRecordingRowOffersWhatItsStateAndOwnerAllow() {
        let scheduled = recording(.scheduled).actions
        #expect(scheduled.watch == nil && !scheduled.delete && !scheduled.recordInstead)
        #expect(scheduled.edit && scheduled.cancel)
        #expect(scheduled.cancelTitle == "Don’t record" && scheduled.swipeTitle == "Don’t record")
        #expect(recording(.scheduled, series: true).actions.swipeTitle == "Skip this one")

        let now = recording(.recording).actions
        #expect(now.watch == .fromStart && now.cancel && !now.edit)
        #expect(now.cancelTitle == "Stop recording" && now.swipeTitle == "Stop recording")

        let conflict = recording(.conflict).actions
        #expect(conflict.recordInstead && conflict.cancel && !conflict.edit)

        for state in [LiveRecordingState.done, .partial] {
            let done = recording(state).actions
            #expect(done.watch == .recorded && done.delete && !done.cancel)
            #expect(recording(state, file: false).actions.watch == nil, "no file yet")
        }
        let failed = recording(.failed).actions
        #expect(failed.watch == nil && !failed.delete && !failed.cancel)

        for state in LiveRecordingState.allCases {
            let theirs = recording(state, mine: false).actions
            #expect(!theirs.delete && !theirs.cancel && !theirs.edit && !theirs.recordInstead, "someone else's: watch only")
        }
    }

    // MARK: The options form

    @Test func theOptionsFormShowsWhatItsSubjectAllows() {
        let series = LiveRecordingForm.programme(programme(.later))
        #expect(series.offersSeries && series.offersServerPadding)
        #expect(!series.showsSeriesSettings && !series.showsPadding && !series.showsKeepNote, "a new recording starts plain")
        var options = series.options
        options.series = true; options.useServerPadding = false; options.keep = 3
        let changed = LiveRecordingForm(options: options, like: series)
        #expect(changed.showsSeriesSettings && changed.showsPadding && changed.showsKeepNote)
        #expect(!LiveRecordingForm.programme(programme(.later, series: false)).offersSeries)

        let rule = LiveRecordingForm.rule(LiveRecordingRule(id: "rule", title: "Station 9", newOnly: false, anyChannel: true, keep: 5, padBefore: 2, padAfter: 15))
        #expect(rule.options == LiveRecordingOptions(series: true, newOnly: false, anyChannel: true, keep: 5, padBefore: 2, padAfter: 15, useServerPadding: false))
        #expect(rule.offersSeries && !rule.offersServerPadding && rule.showsSeriesSettings && rule.showsPadding)
        let once = LiveRecordingForm.rule(LiveRecordingRule(id: "rule", kind: "manual", title: "News"))
        #expect(!once.offersSeries && !once.showsSeriesSettings)

        let single = LiveRecordingForm.recording(LiveRecording(id: "r", channelID: "c", number: "4.1", title: "Station 9", start: now, end: now + 60, padBefore: 5, padAfter: 30))
        #expect(single.options == LiveRecordingOptions(padBefore: 5, padAfter: 30, useServerPadding: false))
        #expect(!single.offersSeries && !single.offersServerPadding && single.showsPadding && !single.showsSeriesSettings)
        #expect(LiveRecordingForm.keepChoices == [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    }
}
