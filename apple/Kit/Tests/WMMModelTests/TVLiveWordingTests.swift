import Foundation
import Testing
import WMMModel

/// Apple TV's Live TV words: the guide's lines and sheet, On now cards, Recordings.
@Suite struct TVLiveWordingTests {
    private static let zone = TimeZone(identifier: "America/New_York")!
    private static func calendar() -> Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = zone; return c }
    private let us = LiveClock(locale: Locale(identifier: "en_US"), calendar: calendar())
    private func plain(_ s: String?) -> String? { s?.replacingOccurrences(of: "\u{202F}", with: " ") }

    /// Mon 2026-11-02 at hh:mm in New York.
    private func at(_ hour: Int, _ minute: Int = 0, day: Int = 2) -> Date {
        Self.calendar().date(from: DateComponents(year: 2026, month: 11, day: day, hour: hour, minute: minute))!
    }
    private var now: Date { at(20, 28) }
    private let lksd = LiveChannel(id: "c71", number: "7.1", name: "LKSD")

    @Test func theGuideLinesSayWhenWhatAndWhatFollows() {
        let on = LiveProgramme(id: "p1", start: at(20), end: at(21), title: "The Deep End")
        let next = LiveProgramme(id: "p2", start: at(21), end: at(21, 30), title: "Tide Tables")
        let later = LiveProgramme(id: "p3", start: at(21), end: at(22), title: "The Quiet Meridian", episodeTitle: "Slack Water", season: 2, episode: 5, isNew: true)
        #expect(liveGuideWhen(on, channel: lksd, now: now, clock: us) == "On now · 7.1 LKSD")
        #expect(liveGuideWhen(later, channel: lksd, now: now, clock: us) == "Tonight · 7.1 LKSD")
        #expect(liveGuideWhen(nil, channel: lksd, now: now, clock: us) == "7.1 LKSD")
        #expect(liveGuideAfter(on, next: next, now: now) == "Next: Tide Tables")
        #expect(liveGuideAfter(later, next: nil, now: now) == "New episode")
        #expect(plain(liveUntil(on, clock: us)) == "Until 9:00 PM")
        #expect(plain(liveSheetWhen(later, channel: lksd, now: now, clock: us)) == "Tonight · 9:00–10:00 PM · 7.1 LKSD")
        #expect(liveSheetLine(later) == "S2 E5 · Slack Water · New episode")
        #expect(liveSheetLine(on) == nil)
    }

    @Test func theDayWordIsTonightFromFivePM() {
        #expect(liveDayWord(at(14), now: at(9), clock: us) == "Today")
        #expect(liveDayWord(at(17), now: at(9), clock: us) == "Tonight")
        #expect(liveDayWord(at(8, day: 3), now: at(9), clock: us) == "Tomorrow")
    }

    @Test func theSheetsRowsNameTheChannelAndTheReminder() {
        #expect(liveRemindMeRow(leadMinutes: 0) == "Remind me when it starts")
        #expect(liveRemindMeRow(leadMinutes: 5) == "Remind me 5 min before")
        #expect(liveWatchNowRow(lksd) == "Watch 7.1 LKSD now")
        #expect(liveIsOn(LiveProgramme(id: "p", start: at(20, 30), end: at(21), title: "Lake Report")) == "Lake Report is on")
    }
}
