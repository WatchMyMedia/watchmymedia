import Foundation
import Testing
import WMMModel

/// When something would end, said in the device's clock.
@Suite struct EndTimesTests {
    private static let zone = TimeZone(identifier: "America/New_York")!
    private static func calendar() -> Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = zone; return c }
    private let us = LiveClock(locale: Locale(identifier: "en_US"), calendar: calendar())
    private let uk = LiveClock(locale: Locale(identifier: "en_GB"), calendar: calendar())
    private func plain(_ s: String) -> String { s.replacingOccurrences(of: "\u{202F}", with: " ") }

    /// Mon 2026-11-23 at 9:18:20 PM in New York.
    private var now: Date { Self.calendar().date(from: DateComponents(year: 2026, month: 11, day: 23, hour: 21, minute: 18, second: 20))! }

    @Test func endsAtSaysTheTimeTheRestWouldEnd() {
        #expect(plain(endsAtLine(remainingMs: 23 * 60_000, now: now, clock: us)) == "Ends at 9:41 PM")
        #expect(endsAtLine(remainingMs: 23 * 60_000, now: now, clock: uk) == "Ends at 21:41")
        // Past midnight, to the nearest minute.
        #expect(plain(endsAtLine(remainingMs: 3 * 3_600_000 - 10_000, now: now, clock: us)) == "Ends at 12:18 AM")
        // Nothing left ends now.
        #expect(plain(endsAtLine(remainingMs: -5, now: now, clock: us)) == "Ends at 9:18 PM")
    }

    @Test func startNowSaysWhenAWholeFilmWouldEnd() {
        #expect(plain(startNowEndsLine(durationMs: 62 * 60_000, now: now, clock: us)) == "Start now and it ends at 10:20 PM")
    }
}
