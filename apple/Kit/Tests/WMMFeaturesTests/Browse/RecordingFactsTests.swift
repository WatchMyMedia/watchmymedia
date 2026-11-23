import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// A recording's marks and a repeat's next airing (API level 34).
@MainActor
@Suite struct RecordingFactsTests {
    init() { FakeLatency.duration = .zero }

    @Test func aClashSaysWhichIsARepeatAndWhenItAirsAgain() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let clock = LiveClock(locale: Locale(identifier: "en_US"), calendar: calendar)
        let now = ISO8601DateFormatter().date(from: "2026-11-23T20:00:00Z")!
        let friday = ISO8601DateFormatter().date(from: "2026-11-27T14:00:00Z")!
        let repeatHolder = LiveRecordingHolder(id: "a", title: "Tide Tables", number: "4.1", start: now, end: now.addingTimeInterval(3600),
                                               isRepeat: true, nextAiring: LiveAiring(channelID: "c", number: "4.1", programmeID: "p", start: friday))
        let newHolder = LiveRecordingHolder(id: "b", title: "Harbor Lights", number: "2.1", start: now, end: now.addingTimeInterval(3600), isRepeat: false)
        let recording = LiveRecording(id: "r", channelID: "c", number: "7.1", title: "The Quiet Meridian", start: now, end: now.addingTimeInterval(3600),
                                      state: .conflict, reason: "tuners_busy", canCancel: true, conflictsWith: [newHolder, repeatHolder])
        let clash = try #require(LiveRecordingClash(recording))
        #expect(clash.airingNote(repeatHolder, clock: clock, now: now)?.replacingOccurrences(of: "\u{202F}", with: " ") == "a repeat, on again Friday at 2:00 PM")
        #expect(clash.airingNote(newHolder, clock: clock, now: now) == "new episode")
        #expect(clash.line(newHolder, clock: clock, now: now).replacingOccurrences(of: "\u{202F}", with: " ") == "2.1 · 8:00–9:00 PM · new episode")
        #expect(clash.suggested?.id == "a", "the repeat airs again, so it is the one to leave out")
        let older = LiveRecordingHolder(id: "c", title: "Station 9", number: "7.1", start: now, end: now)
        #expect(clash.airingNote(older, clock: clock, now: now) == nil, "an older server doesn't say")
    }

    @Test func aRecordingIsUnwatchedOnlyWhereTheServerSays() {
        var recording = LiveRecording(id: "r", channelID: "c", number: "7.1", title: "Station 9", start: .now, end: .now, state: .done, mediaFileID: 3)
        #expect(!recording.unwatched)
        recording.watched = false
        #expect(recording.unwatched)
        recording.state = .scheduled
        #expect(!recording.unwatched, "only what is recorded can be new")
    }
}
