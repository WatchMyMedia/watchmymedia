import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// What leads Apple TV's Home: the title most likely to be resumed, else the
/// server's pick; the rows follow without the title on the card.
@Suite struct HomeLeadTests {
    private static let zone = TimeZone(identifier: "America/New_York")!
    private static func calendar() -> Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = zone; return c }
    private let us = LiveClock(locale: Locale(identifier: "en_US"), calendar: calendar())
    private func plain(_ s: String?) -> String? { s?.replacingOccurrences(of: "\u{202F}", with: " ") }
    private let home = FakeData.home(now: testToday)
    /// 2026-10-30 at 9:18 PM in New York.
    private var evening: Date { Self.calendar().date(from: DateComponents(year: 2026, month: 10, day: 30, hour: 21, minute: 18))! }

    private var rows: [ScreenRow] { home.fixedRows }

    @Test func somethingInProgressLeadsAndContinueWatchingKeepsTheRest() throws {
        let (lead, rest) = HomeLead.split(rows, hero: home.hero)
        let first = try #require(home.continueWatching.first)
        #expect(lead == .resume(first))
        let continuing = try #require(rest.first { $0.style == .continue })
        #expect(continuing.continueItems == Array(home.continueWatching.dropFirst()))
        #expect(rest.map(\.id) == rows.map(\.id))
    }

    @Test func theOnlyTitleInProgressTakesTheRowWithIt() {
        var one = rows
        one[0].continueItems = Array(one[0].continueItems.prefix(1))
        let (lead, rest) = HomeLead.split(one, hero: home.hero)
        #expect(lead == .resume(home.continueWatching[0]))
        #expect(!rest.contains { $0.style == .continue })
        #expect(rest.count == one.count - 1)
    }

    @Test func nothingInProgressLeadsWithTheServersPick() {
        let none = rows.filter { $0.style != .continue }
        let (lead, rest) = HomeLead.split(none, hero: home.hero)
        #expect(lead == home.hero.map(HomeLead.pick))
        #expect(rest == none)
        #expect(HomeLead.split(none, hero: nil).lead == nil)
    }

    @Test func continueWatchingLeadsWhereverThePersonPutIt() throws {
        // Arranged rows put Continue watching third: its first title still leads, and its row keeps its place.
        var arranged = rows.filter { $0.style != .continue }
        arranged.insert(rows[0], at: 2)
        let (lead, rest) = HomeLead.split(arranged, hero: home.hero)
        #expect(lead == .resume(home.continueWatching[0]))
        #expect(rest.map(\.id) == arranged.map(\.id))
        #expect(rest[2].continueItems.count == home.continueWatching.count - 1)
    }

    @Test func theCardSaysWhereYouWereHowLongIsLeftAndWhenItEnds() throws {
        var item = home.continueWatching[0]
        item.contentRating = "TV-14"
        item.overview = "The survey ship reaches the line."
        let words = TVFocusWords(lead: .resume(item), now: evening, clock: us)
        #expect(words.kicker == "Continue watching")
        #expect(words.title == "The Quiet Meridian")
        #expect(words.subtitle == "S02 E04 · Drift")
        #expect(words.lead == "23 min left")
        #expect(words.facts.map(plain) == ["Ends at 9:41 PM"])
        #expect(words.rating == "TV-14")
        #expect(words.overview == "The survey ship reaches the line.")
    }

    @Test func aMovieInProgressHasNoEpisodeLine() throws {
        let movie = try #require(home.continueWatching.first { $0.kind == .movie })
        let words = TVFocusWords(lead: .resume(movie), now: evening, clock: us)
        #expect(words.subtitle == nil)
        #expect(words.title == movie.title)
    }

    @Test func thePickSaysWhyAndPutsTheEpisodeUnderTheTitle() throws {
        let hero = try #require(home.hero)
        let words = TVFocusWords(lead: .pick(hero), now: evening, clock: us)
        #expect(words.kicker == "New episode · aired yesterday")
        #expect(words.title == "Harbor Lights")
        #expect(words.subtitle == "S03 E07 · The Long Dark")
        #expect(words.facts == ["52 min"])
        #expect(words.lead == nil)
        #expect(words.rating == "TV-14")
        // Never the file's format, though the pick is 4K HDR.
        #expect(!words.accessibilityLabel.contains("4K") && !words.accessibilityLabel.contains("HDR"))
    }

    @Test func aPickedMovieKeepsItsFactsTogether() {
        let hero = Hero(kind: .movie, kicker: "Recently added", title: "Ninety Fathoms", facts: "2024 · 1 h 52 min", overview: "")
        let words = TVFocusWords(lead: .pick(hero))
        #expect(words.subtitle == nil)
        #expect(words.facts == ["2024", "1 h 52 min"])
    }

    @Test func theCardShowsTheStillAndHowFarAlong() throws {
        let item = home.continueWatching[0]
        let lead = HomeLead.resume(item)
        #expect(lead.picturePath == item.stillPath ?? item.backdropPath)
        #expect(lead.progress == item.progress)
        let hero = try #require(home.hero)
        #expect(HomeLead.pick(hero).progress == nil)
        #expect(HomeLead.pick(hero).picturePath == hero.backdropPath)
        #expect(lead.key != HomeLead.resume(home.continueWatching[1]).key)
    }
}
