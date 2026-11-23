import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// The words above a row on Apple TV's Home: what the focused card is, how
/// long is left and when it would end; never the file's format.
@Suite struct TVFocusWordsTests {
    private static let zone = TimeZone(identifier: "America/New_York")!
    private static func calendar() -> Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = zone; return c }
    private let us = LiveClock(locale: Locale(identifier: "en_US"), calendar: calendar())
    private func plain(_ s: String?) -> String? { s?.replacingOccurrences(of: "\u{202F}", with: " ") }
    let home = FakeData.home(now: testToday)

    /// 2026-10-30 at 9:18 PM in New York.
    private var evening: Date { Self.calendar().date(from: DateComponents(year: 2026, month: 10, day: 30, hour: 21, minute: 18))! }

    @Test func continueWatchingSaysWhereYouWereAndWhenItEnds() throws {
        let row = ScreenRow(id: "continueWatching", style: .continue, title: "Continue watching", continueItems: home.continueWatching)
        let words = try #require(TVFocusWords.words(for: row, card: nil, now: evening, clock: us))
        #expect(words.kicker == nil)
        #expect(words.title == "The Quiet Meridian")
        #expect(words.subtitle == "S02 E04 · Drift")
        #expect(words.lead == "23 min left")
        #expect(words.facts.map(plain) == ["Ends at 9:41 PM"])
        #expect(plain(words.accessibilityLabel) == "The Quiet Meridian. S02 E04 · Drift. 23 min left, Ends at 9:41 PM")
    }

    @Test func aMovieCardHasNoQualityTagsAndAFilmPartwaySaysTimeLeft() throws {
        let row = ScreenRow(id: "recentlyAddedMovies", style: .poster, title: "Recently added · Movies", items: home.recentlyAddedMovies)
        let first = try #require(TVFocusWords.words(for: row, card: nil, now: evening, clock: us))
        #expect(first.kicker == "Recently added · today")
        #expect(first.title == "Dead Reckoning Bay")
        #expect(first.lead == nil)
        #expect(first.facts == ["2026", "1 h 52 min"])
        #expect(first.after == ["Thriller"])
        #expect(!first.accessibilityLabel.contains("4K") && !first.accessibilityLabel.contains("HDR"))

        let partway = home.recentlyAddedMovies[1]
        let second = try #require(TVFocusWords.words(for: row, card: partway.cardID, now: evening, clock: us))
        let runtime = try #require(partway.runtimeMin)
        let left = Int(Double(runtime * 60_000) * (1 - 0.66))
        #expect(second.lead == formatRemaining(ms: left))
        #expect(second.facts.map(plain) == [plain(endsAtLine(remainingMs: left, now: evening, clock: us))])
    }

    @Test func anEpisodeCardLeadsWithItsShow() throws {
        let row = ScreenRow(id: "recentlyAired", style: .episode, title: "Recently aired · Episodes", episodes: home.recentlyAired)
        let words = try #require(TVFocusWords.words(for: row, card: nil, now: testToday, clock: us))
        let episode = try #require(home.recentlyAired.first)
        #expect(words.kicker == "New episode · aired yesterday")
        #expect(words.title == episode.showTitle)
        #expect(words.subtitle == "\(episodeCode(season: episode.season, episode: episode.episode)) · \(episode.title)")
    }

    @Test func aChannelSaysTimeLeftItsTimesAndWhatIsNext() {
        let start = Self.calendar().date(from: DateComponents(year: 2026, month: 10, day: 30, hour: 21))!
        let now = LiveProgramme(id: "p1", start: start, end: start.addingTimeInterval(3600), title: "The Deep End", episodeTitle: "Pressure", season: 1, episode: 3, rating: "TV-PG")
        let next = LiveProgramme(id: "p2", start: start.addingTimeInterval(3600), end: start.addingTimeInterval(5400), title: "Tide Tables")
        let channel = LiveChannel(id: "c41", number: "4.1", name: "NFLD", now: now, next: next)
        let words = TVFocusWords(channel: channel, now: start.addingTimeInterval(28 * 60), clock: us)
        #expect(words.kicker == "On now · 4.1 NFLD")
        #expect(words.live)
        #expect(words.title == "The Deep End")
        #expect(words.subtitle == "S1 E3 · Pressure")
        #expect(words.lead == "32 min left")
        #expect(words.facts.map(plain) == ["9:00–10:00 PM"])
        #expect(words.rating == "TV-PG")
        #expect(plain(words.next) == "Next: Tide Tables at 10:00 PM")

        let bare = TVFocusWords(channel: LiveChannel(id: "c7", number: "7.1", name: "LKSD"), now: start, clock: us)
        #expect(bare.title == "LKSD")
        #expect(bare.facts == ["No listings"])
    }

    @Test func theCounterIsShort() {
        #expect(tvRowCounter(index: 0, of: 7) == "1 of 7")
    }
}

/// Home's states on Apple TV: which server didn't answer, and what to try.
@Suite struct TVHomeWordsTests {
    @Test func anUnansweredServerIsNamed() {
        let message = "Couldn't reach the server. Check the connection and try again."
        #expect(TVHomeWords.failedTitle(serverName: "Harbour House", message: message) == "Can’t reach Harbour House")
        #expect(TVHomeWords.failedMessage(message).hasPrefix("This Apple TV is online, but the server isn’t answering."))
        #expect(TVHomeWords.failedTitle(serverName: nil, message: message) == "Can’t reach the server")
        #expect(TVHomeWords.unreachable("Couldn’t reach the server. Check the connection and try again."), "with either apostrophe")
    }

    @Test func anotherFailureKeepsItsReason() {
        #expect(TVHomeWords.failedTitle(serverName: "Harbour House", message: "The server said no.") == "Couldn’t load Home")
        #expect(TVHomeWords.failedMessage("The server said no.") == "The server said no.")
    }

    @Test func theSetupLineNamesTheAddress() {
        #expect(TVHomeWords.setupLine(address: "harbourhouse.example") == "Run this server? Add its folders at harbourhouse.example in a browser.")
    }
}
