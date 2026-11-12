import Foundation
import Testing
import WMMModel

/// The clash shown when Record finds the tuners booked (PLAN-LIVE-TV-DVR.md §3.1).
@Suite struct LiveRecordingClashTests {
    private static let zone = TimeZone(identifier: "America/New_York")!
    private static func calendar() -> Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = zone; return c }
    private let us = LiveClock(locale: Locale(identifier: "en_US"), calendar: calendar())
    private func at(_ hour: Int, _ minute: Int = 0) -> Date {
        Self.calendar().date(from: DateComponents(year: 2026, month: 11, day: 2, hour: hour, minute: minute))!
    }
    private func plain(_ s: String) -> String { s.replacingOccurrences(of: "\u{202F}", with: " ") }
    private var vane: LiveRecordingHolder { .init(id: "h1", title: "Inspector Vane", number: "4.2", start: at(21), end: at(22), userName: "Robin") }
    private var football: LiveRecordingHolder { .init(id: "h2", title: "Conference Football", number: "4.1", start: at(21), end: at(22, 30), userName: "Sam", running: true) }
    private func rescue(_ holders: [LiveRecordingHolder], reason: String = "tuners_busy", canCancel: Bool = true) -> LiveRecording {
        LiveRecording(id: "r1", channelID: "c9", number: "9.1", title: "Coastline Rescue", start: at(21), end: at(22),
                      state: .conflict, reason: reason, canCancel: canCancel, conflictsWith: holders)
    }

    @Test func onlyAClashIsOne() {
        var scheduled = rescue([vane]); scheduled.state = .scheduled
        #expect(LiveRecordingClash(scheduled) == nil)
        #expect(LiveRecordingClash(rescue([vane])) != nil)
    }
    @Test func eachHolderNotRecordingYetCanGiveWayAndKeepNamesThemAll() throws {
        let clash = try #require(LiveRecordingClash(rescue([vane, football])))
        #expect(plain(clash.title(clock: us)) == "The tuners are busy at 9:00 PM")
        #expect(clash.recording.statusTitle == "Won’t record: Inspector Vane and Conference Football have the tuners")
        #expect(clash.message == "To record Coastline Rescue on 9.1, choose one that won’t record.")
        #expect(clash.choices.map(\.id) == ["h1"], "A recording already running can't give way")
        #expect(clash.recordInstead == "Record Coastline Rescue instead")
        #expect(clash.recordInsteadLabel(vane) == "Record Coastline Rescue instead of Inspector Vane")
        #expect(clash.recordInsteadOf(vane) == "Record this instead of Inspector Vane", "Apple TV's, beside the holder")
        #expect(clash.kept == "Coastline Rescue stays under Won’t record in Recordings.")
        #expect(clash.keep == "Keep Inspector Vane and Conference Football")
        #expect(plain(clash.line(football, clock: us)) == "4.1 · 9:00–10:30 PM")
        #expect(clash.status(vane) == "Set by Robin")
        #expect(clash.status(football) == "Recording now")
    }
    @Test func whenNothingCanGiveWayItExplainsAndOnlyClosesWithOK() throws {
        let running = try #require(LiveRecordingClash(rescue([football])))
        #expect(running.choices.isEmpty && running.keep == "OK")
        #expect(running.message == "Conference Football is recording already and can’t give way. Coastline Rescue stays under Won’t record in Recordings.")
        let noTuners = try #require(LiveRecordingClash(rescue([], reason: "no_tuners")))
        #expect(noTuners.title == "Won’t record: no tuner is free for recordings")
        #expect(noTuners.message.contains("kept for watching live TV"))
        #expect(noTuners.choices.isEmpty && noTuners.keep == "OK")
        let unknown = try #require(LiveRecordingClash(rescue([])))
        #expect(plain(unknown.title(clock: us)) == "The tuners are busy at 9:00 PM")
        #expect(unknown.message.contains("stays under Won’t record"))
        let someoneElses = try #require(LiveRecordingClash(rescue([vane], canCancel: false)))
        #expect(someoneElses.choices.isEmpty, "Only the setter or an admin changes it")
        #expect(someoneElses.message.hasPrefix("Only whoever set Coastline Rescue"))
    }
}
