import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Search's rows on Apple TV: movies and shows in one row, the server in
/// use's first; a line that says which each one is; and Select on a
/// programme on now watches it.
@Suite struct TVSearchRowsTests {
    private let cabin = SearchSource(id: "srv-cabin", name: "Cabin server")
    private let den = SearchSource(id: "srv-den", name: "Den server")

    @Test func moviesAndShowsShareOneRowTheServerInUsesFirst() {
        let show = FakeData.shows[0], otherShow = FakeData.shows[1]
        let movie = FakeData.movies[0], otherMovie = FakeData.movies[1]
        let listing = SearchListing(
            shows: [FoundTitle(show), FoundTitle(otherShow, server: cabin), FoundTitle(show, server: den)],
            movies: [FoundTitle(movie), FoundTitle(otherMovie, server: den), FoundTitle(movie, server: cabin)]
        )
        #expect(listing.titles.map(\.id) == [
            FoundTitle(show).id, FoundTitle(movie).id,
            FoundTitle(otherShow, server: cabin).id, FoundTitle(movie, server: cabin).id,
            FoundTitle(show, server: den).id, FoundTitle(otherMovie, server: den).id,
        ])
        #expect(SearchListing().titles.isEmpty)
    }

    @Test func aTitlesLineSaysWhichItIsAndNoFormat() {
        var show = FakeData.shows[0]
        show.watched = false
        #expect(show.tvSearchLine == "TV show · \(show.subtitle ?? "")")
        var movie = FakeData.movies[0]
        movie.watched = true
        #expect(movie.tvSearchLine == "Movie · \(movie.gridSubtitle ?? "") · watched")
        #expect(!movie.tvSearchLine.contains("4K"))
    }

    @Test func selectWatchesWhatIsOnNowAndShowsALaterOneInTheGuide() {
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        let programme = LiveProgramme(id: "p", start: start, end: start.addingTimeInterval(3600), title: "The Glass Harbor")
        let channel = LiveChannel(id: "c20", number: "20.1", name: "Cinema Vault")
        let hit = LiveSearchHit(channel: channel, programme: programme)
        #expect(hit.searchSelect(now: start.addingTimeInterval(600)) == .watch)
        #expect(hit.searchSelect(now: start.addingTimeInterval(-600)) == .showInGuide, "later: the guide, where it can be recorded")
        let off = LiveSearchHit(channel: LiveChannel(id: "c21", number: "21.1", name: "Off air", available: false), programme: programme)
        #expect(off.searchSelect(now: start.addingTimeInterval(600)) == .showInGuide, "a channel that can't play isn't tuned")
    }
}
