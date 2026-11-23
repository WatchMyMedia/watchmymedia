import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// A card's counts and rating, and a library's totals and rating filter, on Apple TV (API level 34).
@MainActor
@Suite struct CardFactsTests {
    init() { FakeLatency.duration = .zero }

    private static let today = parseDay("2026-11-23")! // a Monday

    @Test func whenTheNextEpisodeAirs() {
        #expect(nextAirLine("2026-11-23", today: Self.today) == "next today")
        #expect(nextAirLine("2026-11-24", today: Self.today) == "next tomorrow")
        #expect(nextAirLine("2026-11-26", today: Self.today) == "next on Thursday")
        #expect(nextAirLine("2026-12-08", today: Self.today) == "next on Dec 8")
        #expect(nextAirLine("2027-01-08", today: Self.today) == "next on Jan 8, 2027")
        #expect(nextAirLine("2026-11-20", today: Self.today) == nil, "past days aren't next")
        #expect(nextAirLine(nil, today: Self.today) == nil)
    }

    @Test func aShowsCardSaysWhatIsLeftAndWhenTheNextAirs() {
        var show = BrowseItem(kind: .show, id: 1, libraryID: 2, title: "Harbor Lights", year: 2022, episodeCount: 8, unwatchedCount: 2, nextAirDate: "2026-11-26")
        #expect(show.episodesLeftLine(today: Self.today) == "2 episodes left · next on Thursday")
        show.unwatchedCount = 0
        show.nextAirDate = nil
        #expect(show.episodesLeftLine(today: Self.today) == "All watched")
        show.unwatchedCount = nil
        #expect(show.episodesLeftLine(today: Self.today) == nil, "an older server doesn't count it")
        #expect(show.tvGridLine(today: Self.today) == show.gridSubtitle)
        let movie = BrowseItem(kind: .movie, id: 2, libraryID: 1, title: "Tin Sky", year: 2026, runtimeMin: 96)
        #expect(movie.episodesLeftLine(today: Self.today) == nil)
    }

    @Test func homesWordsLeadWithWhatIsLeftAndCarryTheRating() throws {
        let show = BrowseItem(kind: .show, id: 1, libraryID: 2, title: "Harbor Lights", year: 2022, genres: ["Drama"], episodeCount: 8, contentRating: "TV-14", unwatchedCount: 2, nextAirDate: "2026-11-26")
        let row = ScreenRow(id: "recentlyAddedShows", style: .poster, title: "Recently added · TV Shows", items: [show])
        let words = try #require(TVFocusWords.words(for: row, card: show.cardID, now: Self.today))
        #expect(words.lead == "2 episodes left")
        #expect(words.facts == ["2022", "Next on Thursday"])
        #expect(words.rating == "TV-14")
        var movie = BrowseItem(kind: .movie, id: 2, libraryID: 1, title: "Tin Sky", year: 2026, genres: ["Animation"], runtimeMin: 96)
        movie.contentRating = "PG"
        let movies = ScreenRow(id: "recentlyAddedMovies", style: .poster, title: "Recently added · Movies", items: [movie])
        #expect(TVFocusWords.words(for: movies, card: movie.cardID, now: Self.today)?.rating == "PG")
    }


    private func library(_ type: LibraryType, level: Int = ServerFeatures.Level.redesign) -> LibraryModel {
        let fake = FakeLibraryRepository()
        fake.servers = FakeServerRepository(apiLevel: level)
        return LibraryModel(library: fake, navigator: Navigator(), type: type, libraryID: type == .tv ? FakeData.tvLibrary : FakeData.movieLibrary)
    }

    @Test func theUnwatchedChipCountsAndTheHeadingAddsThisMonth() async {
        let model = library(.movie)
        await model.retry()
        #expect(model.unwatchedChipLabel == "Unwatched · 9")
        #expect(model.gridCountLine == "10 movies · 3 added this month")
        let older = library(.movie, level: ServerFeatures.Level.watchlists)
        await older.retry()
        #expect(older.unwatchedChipLabel == "Unwatched")
        #expect(older.gridCountLine == "10 movies")
    }

    @Test func theRatingFilterNarrowsTheGridAndSaysSo() async throws {
        let model = library(.movie)
        await model.retry()
        #expect(model.ratingOptions == ["PG", "PG-13", "R"])
        #expect(model.ratingChoiceLabel("R") == "R · 2")
        await model.setRating("R", on: true)
        #expect(model.state.value?.items.map(\.contentRating) == ["R", "R"])
        #expect(model.moreFiltersLabel == "More filters · R")
        #expect(model.moreFiltersOn)
        #expect(model.gridCountLine == "2 movies rated R")
        await model.setRating("PG", on: true)
        #expect(model.contentRatings == ["PG", "R"], "in the library's order")
        #expect(model.moreFiltersLabel == "More filters · 2 ratings")
        await model.clearFilters()
        #expect(model.contentRatings.isEmpty)
        #expect(model.state.value?.items.count == 10)
        #expect(contentRatingName(unratedContentRating) == "Not rated")
    }

    @Test func olderServersOfferNoRatings() async {
        let model = library(.movie, level: ServerFeatures.Level.watchlists)
        await model.retry()
        #expect(model.ratingOptions.isEmpty)
    }
}
