import Foundation
import Testing
import WMMModel

/// How watchlists are worded, and what a watchlist's list page leaves out.
@Suite struct WatchlistWordingTests {
    static let names = [9101: "Watchlist", 9102: "Halloween"]

    @Test(arguments: [
        ([Int](), "Watchlist"),
        ([9101], "On Watchlist"),
        ([9102], "On Halloween"),
        ([9101, 9102], "On 2 watchlists"),
        ([-1], "On Watchlist"),
    ])
    func theButtonSaysWhichWatchlistsHoldTheTitle(_ holding: [Int], _ words: String) {
        #expect(WatchlistWords.button(holding: holding, names: Self.names) == words)
    }

    @Test(arguments: [
        (12, 9, "12 titles · 9 in your library"),
        (1, 1, "1 title · 1 in your library"),
        (6, 6, "6 titles · 6 in your library"),
        (0, 0, "0 titles"),
    ])
    func aWatchlistsCounts(_ titles: Int, _ inLibrary: Int, _ words: String) {
        #expect(WatchlistWords.counts(titles: titles, inLibrary: inLibrary) == words)
    }

    @Test(arguments: [
        (6, "Its 6 titles stay in your library. This can’t be undone."),
        (1, "Its 1 title stays in your library. This can’t be undone."),
        (0, "This can’t be undone."),
    ])
    func deleteSaysWhatStays(_ titles: Int, _ words: String) {
        #expect(WatchlistWords.deleteMessage(titles: titles) == words)
    }

    @Test(arguments: [
        (["Watchlist"], "Removed from Watchlist"),
        (["Watchlist", "Halloween"], "Removed from 2 watchlists"),
    ])
    func theRemovedMessageNamesTheWatchlist(_ names: [String], _ words: String) {
        #expect(WatchlistWords.removed(from: names) == words)
    }

    /// Whatever a server says of its next fetch, a watchlist is never
    /// refreshed, never "Updated", and has no source line.
    @Test func aWatchlistHasNoFetchWords() {
        let list = MediaList(id: 9101, name: "Watchlist", mediaType: "all", sourceKind: watchlistSourceKind, sourceName: "", limit: 500,
                             entries: 12, inLibrary: 9, fetchedAt: .now - 7200, nextFetchAt: .now + 3600, removeWatched: true)
        #expect(!list.refreshable)
        #expect(list.whenLine() == nil)
        #expect(list.sourceLine == nil)
        #expect(list.countsLine() == "12 titles · 9 in your library")
        var fetched = list
        fetched.sourceKind = "tmdb"
        #expect(fetched.refreshable && fetched.whenLine() != nil, "other lists keep theirs")
    }
}
