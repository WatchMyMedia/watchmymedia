import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// The panel above a row screen (PLAN-HOME.md §7.1), worded per row style as the boards say.
@Suite struct RowPanelTests {
    let home = FakeData.home(now: testToday)

    @Test func aRecentlyAddedShowCardNamesTheEpisodeAndOpensIt() throws {
        let row = ScreenRow(id: "recentlyAddedShows", style: .poster, title: "Recently added · TV Shows", items: home.recentlyAddedShows)
        let panel = try #require(RowPanel.panel(for: row, card: nil, today: testToday))
        #expect(panel.kicker == "Recently added · S03 E07 · added today")
        #expect(panel.title == "Harbor Lights")
        #expect(panel.facts == ["2022", "24 episodes", "Drama"])
        #expect(panel.tags == ["4K", "HDR"])
        #expect(panel.rating == "★ 7.0")
        #expect(panel.picturePath == FakeBackdrops.path(for: "Harbor Lights"))
        #expect(panel.opens == "opens the episode")
        #expect(panel.hint() == "opens the episode · ◀ ▶ more in this row · ▼ next row")
        #expect(panel.buttons.map(\.label) == ["Go to episode", "Go to show"])
        #expect(panel.buttons.last?.action == .openShow(id: 100))
        #expect(panel.accessibilityLabel.hasPrefix("Recently added · S03 E07 · added today. Harbor Lights. 2022, 24 episodes, Drama, 4K, HDR, ★ 7.0. A storm"))

        // The card with that key, not the first.
        let second = try #require(RowPanel.panel(for: row, card: home.recentlyAddedShows[1].cardID, today: testToday))
        #expect(second.title == "The Quiet Meridian")
        #expect(second.kicker == "Recently added · Season 1 · yesterday")
        #expect(second.opens == "opens the show")
        #expect(second.buttons.map(\.label) == ["Go to show"])
    }

    @Test func aMovieCardReadsAddedAsWhen() throws {
        let row = ScreenRow(id: "recentlyAddedMovies", style: .poster, title: "Recently added · Movies", items: home.recentlyAddedMovies)
        let panel = try #require(RowPanel.panel(for: row, card: nil, today: testToday))
        #expect(panel.kicker == "Recently added · today")
        #expect(panel.title == "Dead Reckoning Bay")
        #expect(panel.facts == ["2026", "1 h 52 min", "Thriller"])
        #expect(panel.opens == "opens the movie")
        #expect(panel.buttons.map(\.label) == ["Go to movie"])
    }

    @Test func aReleasedCardLeadsWithItsDateAndAListWithItsRank() throws {
        let released = ScreenRow(id: "recentlyReleased", style: .wide, title: "Recently released · Movies", items: home.recentlyReleased)
        let panel = try #require(RowPanel.panel(for: released, card: nil, today: testToday))
        #expect(panel.kicker == "Released Oct 17, 2026")
        #expect(panel.title == "Signal to Noise")

        let list = ScreenRow(id: "list:9001", style: .poster, title: "Top rated science fiction", subtitle: "4 of 12 · From TMDB", items: Array(home.recentlyAddedMovies.prefix(3)), listID: 9001)
        let third = try #require(RowPanel.panel(for: list, card: home.recentlyAddedMovies[2].cardID, today: testToday))
        #expect(third.kicker == "#3 on the list")
        #expect(list.screenName == "Top rated science fiction · 4 of 12")
        #expect(released.screenName == "Recently released · Movies")
    }

    @Test func anEpisodeCardSaysNewAndWhenItAired() throws {
        let row = ScreenRow(id: "recentlyAired", style: .episode, title: "Recently aired · Episodes", episodes: home.recentlyAired)
        let panel = try #require(RowPanel.panel(for: row, card: nil, today: testToday))
        #expect(panel.kicker == "New episode · aired yesterday")
        #expect(panel.title == "The Long Dark")
        #expect(panel.facts == ["Harbor Lights", "S03 E07"])
        #expect(panel.picturePath == FakeBackdrops.path(for: "Harbor Lights"))
        #expect(panel.buttons.map(\.label) == ["Play", "Go to episode"])
        #expect(panel.mainAction == .playEpisode(home.recentlyAired[0]))

        let missing = try #require(RowPanel.panel(for: row, card: home.recentlyAired[3].cardKey, today: testToday))
        #expect(missing.kicker == "Episode · aired 4 days ago")
        #expect(missing.facts == ["Copperline", "S04 E02", "Not in library"])
        #expect(missing.buttons.map(\.label) == ["Go to episode"])
        let watched = try #require(RowPanel.panel(for: row, card: home.recentlyAired[4].cardKey, today: testToday))
        #expect(watched.kicker == "Watched · aired 5 days ago")
    }

    @Test func aContinueWatchingItemResumes() throws {
        let row = ScreenRow(id: "continue", style: .continue, title: "Continue watching", continueItems: home.continueWatching)
        let panel = try #require(RowPanel.panel(for: row, card: home.continueWatching[1].cardKey))
        #expect(panel.kicker == "Continue watching · 1 h 12 min left")
        #expect(panel.title == "Dead Reckoning Bay")
        #expect(panel.facts.isEmpty)
        #expect(panel.opens == "resumes")
        #expect(panel.buttons.map(\.label) == ["Resume", "Go to movie"])
        let episode = try #require(RowPanel.panel(for: row, card: nil))
        #expect(episode.facts == ["S02 E04", "Drift"])
        #expect(episode.hint(moreInRow: false) == "resumes · ▼ next row")
    }

    @Test func aChannelSaysWhatIsOnNow() {
        let clock = LiveClock(locale: Locale(identifier: "en_US"), calendar: Calendar(identifier: .gregorian))
        let start = testToday.addingTimeInterval(-37 * 60), end = testToday.addingTimeInterval(23 * 60)
        let programme = LiveProgramme(id: "p1", start: start, end: end, title: "Evening News", categories: ["News"], description: "Local and national headlines.")
        let channel = LiveChannel(id: "live-1", number: "7.1", name: "KBAY", now: programme)
        let panel = RowPanel(channel: channel, now: testToday, clock: clock)
        #expect(panel.kicker == "On now · 7.1 KBAY · \(clock.range(start, end))")
        #expect(panel.title == "Evening News")
        #expect(panel.facts == ["23 min left", "News"])
        #expect(panel.overview == "Local and national headlines.")
        #expect(panel.opens == "watches 7.1")
        #expect(panel.buttons.map(\.label) == ["Watch 7.1", "Full guide"])
        #expect(panel.buttons.first?.action == .watchChannel(id: "live-1"))

        let bare = RowPanel(channel: LiveChannel(id: "live-2", number: "5.1", name: "Northline"), now: testToday, clock: clock)
        #expect(bare.kicker == "On now · 5.1 Northline")
        #expect(bare.title == "Northline")
        #expect(bare.facts == ["No listings"])
        #expect(RowPanel.fullGuide.buttons.first?.action == .openGuide)
    }

    @Test func emptyAndLiveRowsHaveNoPanelOfTheirOwn() {
        #expect(RowPanel.panel(for: ScreenRow(id: "live", style: .live, title: "Live TV"), card: nil) == nil)
        #expect(RowPanel.panel(for: ScreenRow(id: "recentlyAired", style: .episode, title: "Recently aired · Episodes"), card: nil) == nil)
        #expect(rowCounter(index: 1, of: 7) == "Row 2 of 7")
        #expect(ScreenRow(id: "live", style: .live, title: "Live TV").screenName == "Live TV")
        #expect(!ScreenRow(id: "recentlyReleased", style: .wide, title: "Recently released · Movies", items: [home.recentlyReleased[0]]).hasSeveralCards)
    }

    @MainActor @Test func theFakesCarryBackdropsButNoPosters() throws {
        let library = FakeLibraryRepository()
        let show = try #require(home.recentlyAddedShows.first)
        #expect(show.posterPath == nil)
        #expect(show.backdropPath?.hasPrefix("/fake-backdrop/") == true)
        let url = try #require(library.imageURL(show.backdropPath, size: .w1280))
        #expect(url.scheme == "fake-backdrop")
        #expect(library.imageURL("/abc.jpg", size: .w342) == nil)
        #expect(library.imageURL(nil, size: .w342) == nil)
        #expect(home.hero?.backdropPath == show.backdropPath)
        #expect(home.continueWatching.allSatisfy { $0.backdropPath != nil })
        #expect(home.recentlyAired.allSatisfy { $0.showBackdropPath != nil })
        #expect(Set(FakeData.movies.compactMap(\.backdropPath)).count > 1, "the titles spread over the scenes")
    }
}
