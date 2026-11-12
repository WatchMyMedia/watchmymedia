import Foundation
import Testing
import WMMModel

@Suite struct LiveRecordingsTests {
    private let start = Date(timeIntervalSince1970: 1_800_000_000)
    private static let zone = TimeZone(identifier: "America/New_York")!
    private static func calendar() -> Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = zone; return c }
    private let us = LiveClock(locale: Locale(identifier: "en_US"), calendar: calendar())
    /// Mon 2026-11-02 at hh:mm in New York.
    private func at(_ hour: Int, _ minute: Int = 0) -> Date {
        Self.calendar().date(from: DateComponents(year: 2026, month: 11, day: 2, hour: hour, minute: minute))!
    }
    private func plain(_ s: String) -> String { s.replacingOccurrences(of: "\u{202F}", with: " ") }
    @Test func recordingNeedsBothServerAndPersonalPermission() {
        var capabilities = LiveTVCapabilities(enabled: true, allowed: true, playback: true)
        #expect(!capabilities.recordingsAvailable)
        capabilities.recording = true
        #expect(!capabilities.recordingsAvailable)
        capabilities.canRecord = true
        #expect(capabilities.recordingsAvailable)
        capabilities.allowed = false
        #expect(!capabilities.recordingsAvailable)
    }
    @Test func growingLengthAndSeekLimitAdvanceAndStopAtTheScheduledEnd() {
        let growing = GrowingRecording(startedAt: start, endsAt: start.addingTimeInterval(3600))
        #expect(growing.durationMs(at: start.addingTimeInterval(-1)) == 0)
        #expect(growing.seekLimitMs(at: start.addingTimeInterval(2)) == 0)
        #expect(growing.durationMs(at: start.addingTimeInterval(60)) == 60_000)
        #expect(growing.seekLimitMs(at: start.addingTimeInterval(60)) == 57_000)
        #expect(growing.durationMs(at: start.addingTimeInterval(4000)) == 3_600_000)
    }
    @Test func manualValidatesDatesDurationChannelNameAndZone() {
        var request = LiveManualRecording(channelID: "ch-7.1", start: start, end: start.addingTimeInterval(3600), name: "Northfield News")
        #expect(request.validationMessage == nil)
        request.end = start
        #expect(request.validationMessage == "The end must be after the start.")
        request.end = start.addingTimeInterval(12 * 3600 + 1)
        #expect(request.validationMessage == "A recording can last up to 12 hours.")
        request.end = start.addingTimeInterval(3600); request.name = "  "
        #expect(request.validationMessage == "Name this recording.")
        request.name = "Northfield News"; request.channelID = ""
        #expect(request.validationMessage == "Choose a channel.")
        request.channelID = "ch-7.1"; request.zone = "unknown/zone"
        #expect(request.validationMessage == "Choose a valid time zone.")
    }
    @Test func recordingUsesExistingFilePlayerAndSwitchesToItsLinkedFileWhenFinished() {
        var recording = LiveRecording(id: "r1", channelID: "ch-7.1", number: "7.1", title: "Station 9", start: start, end: start.addingTimeInterval(3600), state: .recording)
        let growing = recording.playRequest(fromStart: true)
        #expect(recording.playable)
        #expect(growing.fileID == 0 && growing.recordingID == "r1" && growing.startMs == 0)
        recording.state = .done; recording.mediaFileID = 42
        let finished = recording.playRequest()
        #expect(finished.fileID == 42 && finished.recordingID == nil)
        #expect(finished.id != growing.id)
        recording.state = .failed
        #expect(!recording.playable)
        recording.reason = "disk_full"
        #expect(recording.statusTitle == "Not enough free space")
        #expect(recording.reasonMessage == nil, "The reason is the failed row's words, said once")
    }
    @Test func takeoverWarnsWithTheSharedWordsAndHasNoPermissionByDefault() {
        let takeover = LiveRecordingTakeover(recordingID: "r1", title: "Station 9", at: at(21))
        #expect(plain(takeover.message(clock: us)) == "At 9:00 PM a recording needs this tuner. Watching stops then.")
        #expect(!takeover.canCancel)
        #expect(LiveTVError.message("recording").contains("Stopped for a recording"))
    }
    @Test func everyReasonCodeHasTheSharedWordsAndNoCodeIsEverShown() {
        let words: [String: String] = [
            "already_recorded": "Already recorded", "already_scheduled": "Already set to record", "in_library": "Already in your library",
            "not_new": "Not a new episode", "rule_filtered": "Left out by the series options", "listing_gone": "No longer in the listings",
            "tuners_busy": "Won’t record: the tuners are busy", "no_tuners": "Won’t record: no tuner is free for recordings",
            "missed": "Missed: the server wasn’t running", "disk_full": "Stopped: the disk is nearly full",
            "folder_unavailable": "The recordings folder isn’t available", "output_changed": "Something else changed the recording’s file",
            "output_missing": "The recording’s file is missing", "server_restarted": "The server restarted during it",
            "signal_lost": "The signal dropped", "stopped": "Stopped early", "stop_requested": "Stopping…",
            "finish_pending": "Finishing…", "started_late": "Started late",
        ]
        for (code, expected) in words { #expect(liveRecordingReason(code) == expected, "\(code)") }
        #expect(liveRecordingReason("disk_full", finished: true) == "Not enough free space")
        #expect(liveRecordingReason("tuners_busy", finished: true) == "The tuners were busy")
        #expect(liveRecordingReason("something_new") == nil)
        func row(_ state: LiveRecordingState, _ reason: String?) -> LiveRecording {
            LiveRecording(id: "r", channelID: "c", number: "7.1", title: "Station 9", start: start, end: start.addingTimeInterval(3600), state: state, reason: reason)
        }
        #expect(row(.failed, "something_new").statusTitle == "Didn’t record")
        #expect(row(.failed, "listing_gone").statusTitle == "No longer in the listings")
        #expect(row(.failed, "tuners_busy").statusTitle == "The tuners were busy")
        #expect(row(.failed, "disk_full").statusTitle == "Not enough free space")
        #expect(row(.partial, "tuners_busy").reasonMessage == "The tuners were busy")
        #expect(row(.skipped, "not_new").statusTitle == "Not a new episode")
        #expect(row(.skipped, "rule_filtered").statusTitle == "Left out by the series options")
        #expect(row(.skipped, "something_new").statusTitle == "Won’t record")
        #expect(row(.recording, "finish_pending").reasonMessage == "Finishing…")
        #expect(row(.recording, "stop_requested").reasonMessage == "Stopping…")
        #expect(row(.partial, "something_new").reasonMessage == nil)
        for state in [LiveRecordingState.failed, .skipped, .recording, .partial, .done, .conflict] {
            for code in ["finish_pending", "stop_requested", "server_restarted", "not_new", "listing_gone", "missed", "rule_filtered", "output_missing", "already_scheduled", "some_code"] {
                let shown = [row(state, code).statusTitle, row(state, code).reasonMessage ?? ""].joined(separator: " ")
                #expect(!shown.contains("_") && !shown.lowercased().contains("some code") && !shown.contains("server restarted\n"),
                        Comment(rawValue: "\(state) \(code): \(shown)"))
            }
        }
    }
    @Test func aClashNamesWhatHasTheTunersAndNoTunersExplainsItself() {
        var clash = LiveRecording(id: "r", channelID: "c", number: "9.1", title: "Coastline Rescue", start: at(21), end: at(22), state: .conflict, reason: "tuners_busy")
        #expect(clash.statusTitle == "Won’t record: the tuners are busy")
        clash.conflictsWith = [.init(id: "a", title: "Inspector Vane", number: "4.2", start: at(21), end: at(22)),
                               .init(id: "b", title: "Conference Football", number: "4.1", start: at(21), end: at(22, 30), running: true)]
        #expect(clash.statusTitle == "Won’t record: Inspector Vane and Conference Football have the tuners")
        clash.conflictsWith.removeLast()
        #expect(clash.statusTitle == "Won’t record: Inspector Vane has the tuner")
        clash.reason = "no_tuners"
        #expect(clash.statusTitle == "Won’t record: no tuner is free for recordings")
        #expect(clash.mark.accessibilityLabel == "won’t record: no tuner is free for recordings")
    }
    @Test func aMissingPartSaysWhenAndWhy() {
        #expect(plain(liveRecordingGapLine(.init(start: at(21, 12), end: at(21, 14), reason: "signal_lost"), clock: us)) == "Missing 9:12–9:14 PM: the signal dropped")
        #expect(plain(liveRecordingGapLine(.init(start: at(21, 12), end: at(21, 14), reason: "tuners_busy"), clock: us)) == "Missing 9:12–9:14 PM: the tuners were busy")
        #expect(plain(liveRecordingGapLine(.init(start: at(21, 12), end: at(21, 14), reason: "server_restarted"), clock: us)) == "Missing 9:12–9:14 PM: the server restarted during it")
        #expect(plain(liveRecordingGapLine(.init(start: at(21, 12), end: at(21, 14), reason: "new_code"), clock: us)) == "Missing part · 9:12–9:14 PM")
    }
    @Test func theGuideMarksOnlyWhatWillRecordIsRecordingOrClashes() {
        let marked = LiveRecordingState.allCases.filter(\.marked)
        #expect(marked == [.scheduled, .conflict, .recording])
        // Everything else offers Record again, with its words where it has some.
        #expect(LiveRecordingMark(id: "r", state: .skipped, reason: "not_new").note == "Not a new episode")
        #expect(LiveRecordingMark(id: "r", state: .gone).note == "No longer in the listings")
        #expect(LiveRecordingMark(id: "r", state: .failed, reason: "missed").note == "Missed: the server wasn’t running")
        #expect(LiveRecordingMark(id: "r", state: .cancelled).note == nil)
        #expect(LiveRecordingMark(id: "r", state: .deleted).note == nil)
        #expect(LiveRecordingMark(id: "r", state: .scheduled).note == nil)
        for state in LiveRecordingState.allCases where !state.marked {
            #expect(LiveRecordingMark(id: "r", state: state).accessibilityLabel == nil, "\(state)")
        }
    }
    @Test func aCellSaysItsRecordingStateOnce() {
        var p = LiveProgramme(id: "p", start: at(21), end: at(22), title: "Station 9")
        p.recording = .init(id: "r", state: .recording)
        let label = liveAccessibilityLabel(p, clock: us)
        #expect(label.components(separatedBy: "recording now").count == 2)
        p.recording = .init(id: "r", state: .skipped, reason: "already_recorded")
        #expect(!liveAccessibilityLabel(p, clock: us).contains("already"))
    }
    @Test func scheduledWordsSayWhenASeriesRecordsIt() {
        #expect(LiveRecordingMark(id: "r", state: .scheduled).title == "Will record")
        #expect(LiveRecordingMark(id: "r", state: .scheduled, series: true).title == "Will record · Every new episode")
        #expect(LiveRecordingMark(id: "r", state: .recording).accessibilityLabel == "recording now")
    }
    @Test func adBreaksDefaultToAButtonAndPreserveAnExplicitOffChoice() {
        #expect(PlaybackPreferences().skipMode(.commercial) == .ask)
        #expect(PlaybackPreferences(skip: [.commercial: .none]).skipMode(.commercial) == .none)
    }
    @Test func recordingMarksHaveWordsAndUnknownStatesHaveNoMutation() {
        #expect(LiveRecordingMark(id: "r1", state: .conflict).accessibilityLabel == "won’t record: the tuners are busy")
        #expect(LiveRecordingMark(id: "r1", state: .skipped, reason: "already_recorded").accessibilityLabel == nil, "A cell without a mark says nothing about it")
        #expect(LiveRecordingMark(id: "r1", state: .skipped, reason: "already_recorded").title == "Already recorded")
        #expect(!LiveRecordingState.unknown.mayCancel)
        #expect(!LiveRecordingState.done.mayCancel)
        #expect(LiveRecordingOptions().padBefore == 1 && LiveRecordingOptions().padAfter == 2)
    }
}
