import Foundation
import Testing
@testable import WMMModel

/// The recording words both apps' menus, Recordings and options forms share
/// (PLAN-APPLE-REVIEW.md §5.8), as the Android app words them.
@Suite struct LiveRecordingWordingTests {
    @Test func stoppingAndRecordingAreWordedByState() {
        #expect(liveCancelRecordingTitle(.recording) == "Stop recording")
        #expect(liveCancelRecordingTitle(.recording, short: true) == "Stop")
        #expect(liveCancelRecordingTitle(.scheduled) == "Don’t record")
        #expect(liveCancelRecordingTitle(.conflict, short: true) == "Don’t record")
        #expect(liveRecordTitle(rest: false) == "Record")
        #expect(liveRecordTitle(rest: true) == "Record the rest")
        #expect(liveRecordTitle(rest: true, choosing: true) == "Record the rest…")
        #expect(liveRecordHeading("Station 9") == "Record Station 9")
    }

    @Test func recordingsAndTheFormSayWhatTheyHold() {
        #expect(liveSetBy("Alex") == "Set by Alex")
        #expect(liveRecordingsLoading(.upcoming) == "Loading upcoming")
        #expect(liveRecordingsFailed(.now) == "Couldn’t load recording now")
        #expect(liveKeepChoice(0) == "All recordings")
        #expect(liveKeepChoice(3) == "The latest 3")
        #expect(livePaddingChoice(15) == "15 min")
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York")!
        let clock = LiveClock(locale: Locale(identifier: "en_US"), calendar: calendar)
        let start = DateComponents(calendar: calendar, year: 2026, month: 11, day: 2, hour: 21).date!
        let recording = LiveRecording(id: "r", channelID: "c", number: "4.1", title: "Station 9", start: start, end: start + 3600)
        #expect(liveRecordingWhen(recording, clock: clock).replacingOccurrences(of: "\u{202F}", with: " ")
                == "\(clock.weekdayName(start)) \(clock.range(start, start + 3600).replacingOccurrences(of: "\u{202F}", with: " ")) · 4.1")
    }
}
