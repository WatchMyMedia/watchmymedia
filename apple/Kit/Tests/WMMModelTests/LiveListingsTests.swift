import Foundation
import Testing
import WMMModel

@Suite struct LiveListingsTests {
    private static let zone = TimeZone(identifier: "America/New_York")!
    private static func calendar() -> Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = zone; return c }
    private let us = LiveClock(locale: Locale(identifier: "en_US"), calendar: calendar())
    private let uk = LiveClock(locale: Locale(identifier: "en_GB"), calendar: calendar())

    /// Mon 2026-11-02 at hh:mm in New York.
    private func at(_ hour: Int, _ minute: Int = 0, day: Int = 2) -> Date {
        Self.calendar().date(from: DateComponents(year: 2026, month: 11, day: day, hour: hour, minute: minute))!
    }
    /// The system writes a narrow no-break space before AM and PM.
    private func plain(_ s: String) -> String { s.replacingOccurrences(of: "\u{202F}", with: " ") }

    private var sleuth: LiveProgramme {
        LiveProgramme(id: "p1", start: at(20), end: at(21), title: "The Sleuth Files", episodeTitle: "The Low Tide", season: 2, episode: 4,
                      kind: .episode, isNew: true, hd: true, categories: ["Series", "Mystery", "Drama"])
    }

    @Test func episodeLinesFollowTheBoards() {
        #expect(sleuth.episodeLine == "S2 E4 · The Low Tide")
        var p = sleuth; p.season = 2026; p.episode = 14; p.episodeTitle = "Owls of the Valley"
        #expect(p.episodeLine == "2026 E14 · Owls of the Valley")
        p = LiveProgramme(id: "m", start: at(20), end: at(21), title: "The Iron Coast", airDate: "1958", kind: .movie)
        #expect(p.episodeLine == "Movie · 1958")
        p.airDate = nil
        #expect(p.episodeLine == "Movie")
        p = LiveProgramme(id: "s", start: at(20), end: at(21), title: "Conference Football", episodeTitle: "Ridgeway at Lakeport")
        #expect(p.episodeLine == "Ridgeway at Lakeport")
        p = LiveProgramme(id: "q", start: at(20), end: at(21), title: "Quiz Parlor", season: 12, episode: 88)
        #expect(p.episodeLine == "S12 E88")
        p = LiveProgramme(id: "l", start: at(20), end: at(21), title: "Talk", episodeTitle: " ", episodeLabel: "Ep. 1204")
        #expect(p.episodeLine == "Ep. 1204")
        p = LiveProgramme(id: "n", start: at(20), end: at(21), title: "Harbor News", kind: .show)
        #expect(p.episodeLine == nil)
    }

    @Test func tagsCategoriesAndFilters() {
        var p = sleuth
        #expect(p.tags == ["New", "HD"])
        p.live = true
        #expect(p.tags == ["New", "Live", "HD"])
        #expect(p.categoryLine == "Series · Mystery · Drama")
        #expect(!p.matches(.movies) && !p.matches(.news))
        p.categories = ["Sports", "Football"]
        #expect(p.matches(.sports))
        p = LiveProgramme(id: "m", start: at(20), end: at(21), title: "Film", kind: .movie)
        #expect(p.matches(.movies))
        p.categories = ["Children"]
        #expect(p.matches(.kids))
    }

    @Test func picturesTakeTheTMDBStillOnlyWhereItIsWide() throws {
        var p = sleuth
        #expect(p.picture(wide: true) == nil)
        p.pictureURL = URL(string: "http://s/api/live-tv/art/a")
        p.stillURL = URL(string: "http://s/api/images/w780/b.jpg")
        #expect(p.picture(wide: false) == LivePicture(url: try #require(p.pictureURL), fromTMDB: false))
        #expect(p.picture(wide: true) == LivePicture(url: try #require(p.stillURL), fromTMDB: true))
        p.stillURL = nil; p.pictureFromTMDB = true
        #expect(p.picture(wide: true)?.fromTMDB == true)
    }

    @Test func timesShareTheirMeridiemAndFollowTheDevicesClock() {
        #expect(plain(us.range(at(20), at(21))) == "8:00–9:00 PM")
        #expect(plain(us.range(at(23, 30), at(0, 30, day: 3))) == "11:30 PM–12:30 AM")
        #expect(plain(us.range(at(11, 30), at(12, 30))) == "11:30 AM–12:30 PM")
        #expect(us.shortRange(at(19), at(21)) == "7:00–9:00")
        #expect(plain(us.time(at(21))) == "9:00 PM")
        #expect(us.shortTime(at(21)) == "9:00")
        #expect(uk.range(at(20), at(21)) == "20:00–21:00")
        #expect(uk.shortTime(at(21)) == "21:00")
    }

    @Test func daysAndRelativeTimes() {
        let now = at(20, 12)
        #expect(us.dayName(at(9), now: now) == "Today")
        #expect(us.dayName(at(9, day: 3), now: now) == "Tomorrow")
        #expect(us.dayName(at(9, day: 4), now: now) == "Wed")
        #expect(liveTimeLeft(sleuth, now: now) == "48 min left")
        var later = sleuth; later.start = at(21); later.end = at(22, 30)
        #expect(liveStartsIn(later, now: now) == "Starts in 48 min")
        #expect(plain(liveDetailsTime(sleuth, now: now, clock: us)) == "Mon 8:00–9:00 PM · 48 min left")
        #expect(plain(liveDetailsTime(later, now: now, clock: us)) == "Mon 9:00–10:30 PM · Starts in 48 min")
        #expect(plain(liveTimeLine(sleuth, now: now, clock: us)) == "8:00–9:00 PM · 48 min left")
        #expect(liveWhen(sleuth, now: at(21, 5)) == "Ended")
        #expect(liveStatus(sleuth, now: now, clock: us) == "On now")
        #expect(liveStatus(later, now: now, clock: us) == "Later today")
        later.start = at(9, day: 3); later.end = at(10, day: 3)
        #expect(liveStatus(later, now: now, clock: us) == "Tomorrow")
    }

    @Test func watchAndOnAtNameTheChannel() {
        let channel = LiveChannel(id: "c", number: "4.1", name: "NFLD-HD")
        let now = at(20, 12)
        var later = sleuth; later.start = at(21); later.end = at(22, 30)
        #expect(liveWatchLabel(channel, programme: sleuth, now: now) == "Watch 4.1")
        #expect(liveWatchLabel(channel, programme: later, now: now) == "Watch 4.1 now")
        #expect(liveWatchLabel(channel, programme: nil, now: now) == "Watch 4.1")
        #expect(plain(liveOnAt(channel, programme: later, now: now, clock: us)) == "On 4.1 at 9:00 PM")
        later.start = at(21, day: 3)
        #expect(plain(liveOnAt(channel, programme: later, now: now, clock: us)) == "On 4.1 Tue at 9:00 PM")
        var next = sleuth; next.start = at(21); next.title = "Station 9"
        #expect(liveNextLine(next, clock: us) == "Next: 9:00 Station 9")
        #expect(liveNextLine(next, colon: false, clock: us) == "Next 9:00 Station 9")
        #expect(plain(liveOnNowHeading(now: now, clock: us)) == "On now · 8:12 PM")
    }

    @Test func noticesAndAccessibleNames() {
        #expect(plain(liveNoListingsAfter(at(19, day: 4), clock: us)) == "No listings after Wed 7 PM")
        #expect(plain(liveNoListingsAfter(at(19, 30, day: 4), clock: us)) == "No listings after Wed 7:30 PM")
        #expect(plain(liveStaleNotice(.init(stale: true, updatedAt: at(19, 40, day: 1)), clock: us)).hasPrefix("Listings last updated Sun 7:40 PM"))
        #expect(liveStaleNotice(.init(stale: true), clock: us) == "Listings may be out of date")
        var p = sleuth; p.end = at(20, 30); p.hd = false
        #expect(plain(liveAccessibilityLabel(p, clock: us)) == "8:00 to 8:30 PM, The Sleuth Files, season 2 episode 4, The Low Tide, New")
        let movie = LiveProgramme(id: "m", start: at(20), end: at(21, 30), title: "The Iron Coast", airDate: "1958", kind: .movie)
        #expect(plain(liveAccessibilityLabel(movie, clock: us)) == "8:00 to 9:30 PM, The Iron Coast, Movie · 1958")
    }

    @Test func aChannelMovesOnToItsNextProgrammeAtTheEnd() {
        var next = sleuth; next.id = "p2"; next.start = at(21); next.end = at(22, 30); next.title = "Conference Football"
        let channel = LiveChannel(id: "c", number: "4.1", name: "NFLD-HD", now: sleuth, next: next)
        #expect(channel.programme(at: at(20, 59))?.id == "p1")
        #expect(channel.programme(after: at(20, 59))?.id == "p2")
        #expect(channel.programme(at: at(21))?.id == "p2")
        #expect(channel.programme(after: at(21)) == nil)
        #expect(channel.programme(at: at(22, 30)) == nil)
        #expect(sleuth.progress(at: at(20, 12)) == 0.2)
        #expect(LiveTVCapabilities(enabled: true, allowed: true, playback: true, guide: true).listings)
        #expect(!LiveTVCapabilities(enabled: true, allowed: false, playback: true, guide: true).listings)
    }
}
