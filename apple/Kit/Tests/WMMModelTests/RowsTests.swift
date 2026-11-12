import Foundation
import Testing
import WMMModel

@Suite struct RowsTests {
    @Test func screenKeysRoundTrip() {
        #expect(RowScreen.home.key == "home")
        #expect(RowScreen.library(12).key == "library-12")
        #expect(RowScreen(key: "home") == .home)
        #expect(RowScreen(key: "library-3") == .library(3))
        #expect(RowScreen(key: "library-") == nil)
        #expect(RowScreen(key: "settings") == nil)
    }

    @Test func aBuiltInRowsHeadingSplitsAtItsLastDot() {
        let movies = ScreenRow(id: "recentlyAddedMovies", style: .poster, title: "Recently added · Movies")
        #expect(movies.heading.title == "Recently added")
        #expect(movies.heading.detail == "Movies")
        let library = ScreenRow(id: "recentlyAdded", style: .poster, title: "Recently added")
        #expect(library.heading.title == "Recently added")
        #expect(library.heading.detail == nil)
    }

    @Test func aListsNameIsNeverSplit() {
        let row = ScreenRow(id: "list:4", style: .poster, title: "Sci-fi · 1980s", subtitle: "3 of 50 · From TMDB", listID: 4)
        #expect(row.isList)
        #expect(row.heading.title == "Sci-fi · 1980s")
        #expect(row.heading.detail == nil)
    }

    @Test func aLiveRowIsNeverEmptyAndOthersAreWithoutTheirCards() {
        #expect(!ScreenRow(id: "live", style: .live, title: "Live TV").isEmpty)
        #expect(ScreenRow(id: "continue", style: .continue, title: "Continue watching").isEmpty)
        #expect(ScreenRow(id: "recentlyAired", style: .episode, title: "Recently aired").isEmpty)
    }

    @Test func listSourcesAreNamed() {
        #expect(listSourceLine(kind: "tmdb") == "From TMDB")
        #expect(listSourceLine(kind: "mdblist") == "From MDBList")
        #expect(listSourceLine(kind: "library") == "From your library")
        #expect(listSourceLine(kind: "import") == "From an import")
        #expect(listSourceLine(kind: "trakt") == nil, "a source this app doesn't know says nothing")
        #expect(listMediaTypeLine("movie") == "Movies")
        #expect(listMediaTypeLine("all") == nil)
    }

    @Test func listCounts() {
        #expect(listCountLine(inLibrary: 18, total: 50) == "18 of 50 in your library")
        #expect(listCountLine(inLibrary: 0, total: 1_250) == "0 of 1,250 in your library")
        #expect(listCountLine(inLibrary: 0, total: 0) == "Nothing on this list yet")
    }

    @Test func aListsLines() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        var list = MediaList(id: 1, name: "Best of 2026", sourceKind: "mdblist", owner: .everyone, entries: 20, inLibrary: 5, fetchedAt: now - 3 * 86_400)
        #expect(list.sourceLine == "From MDBList")
        #expect(list.shortCount == "5 of 20")
        #expect(list.countsLine() == "5 of 20 in your library · For everyone")
        #expect(list.whenLine(now: now) == "Updated 3 days ago")
        #expect(list.failureLine(now: now) == nil)
        list.error = "MDBList refused the key"
        #expect(list.failureLine(now: now) == "Couldn’t update this list: MDBList refused the key. This is the list from 3 days ago.")
        list.fetchedAt = nil
        #expect(list.failureLine(now: now) == "Couldn’t update this list: MDBList refused the key.")
        list.fetching = true
        #expect(list.whenLine(now: now) == "Refreshing…")
        #expect(list.failureLine(now: now) == nil, "a fetch is running")
        list.inLibrary = 0
        #expect(list.shortCount == nil, "nothing in the library, no count in the row's line")

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let imported = MediaList(id: 2, name: "Rainy Sunday films", sourceKind: "import", owner: .me, entries: 14, inLibrary: 9, notFound: 2,
                                 createdAt: Date(timeIntervalSince1970: 1_793_620_800))
        #expect(imported.whenLine(now: Date(timeIntervalSince1970: 1_794_000_000), calendar: calendar) == "Imported Nov 2")
        #expect(imported.countsLine() == "9 of 14 in your library · 2 not found · Your list")
        #expect(!imported.refreshable, "imports are never fetched again")
        let named = MediaList(id: 4, name: "Watchlist", sourceKind: "import", sourceName: "an IMDb export")
        #expect(named.sourceLine == "From an IMDb export", "the server's name wins")
        let library = MediaList(id: 3, name: "Unwatched 4K films", sourceKind: "library", entries: 12, inLibrary: 12)
        #expect(library.shortCount == nil, "a library list is all in the library")
    }

    @Test func aMissingTitlesLine() {
        let requestable = MediaListEntry(rank: 2, mediaType: .movie, tmdbID: 7, title: "Glasswing", year: 2019, requestState: .requestable)
        #expect(requestable.missingLine == "2019 · Not in your library")
        #expect(requestable.requestable?.state == .requestable)
        var requested = requestable
        requested.requestState = .requested
        #expect(requested.missingLine == "2019 · Requested")
        let downloading = MediaListEntry(rank: 3, mediaType: .tv, tmdbID: 8, title: "Red Shift County", requestState: .downloading)
        #expect(downloading.missingLine == "Downloading")
        let notFound = MediaListEntry(rank: 4, mediaType: .movie, tmdbID: nil, title: "The Kiln Brothers", year: 1996, found: false)
        #expect(notFound.missingLine == "1996 · Not found")
        #expect(notFound.requestable == nil, "nothing to ask for without TMDB's ID")
        let noRequests = MediaListEntry(rank: 5, mediaType: .movie, tmdbID: 9, title: "Cinder Moons", year: 2022)
        #expect(noRequests.missingLine == "2022 · Not in your library")
        #expect(noRequests.requestable == nil, "requests aren't set up")
    }

    @Test func aTitleInTheLibraryIsNotRequestable() {
        let item = BrowseItem(kind: .movie, id: 504, libraryID: 1, title: "Signal to Noise", year: 2026)
        let entry = MediaListEntry(rank: 1, mediaType: .movie, tmdbID: 9, title: "Signal to Noise", year: 2026, item: item, requestState: .available)
        #expect(entry.inLibrary)
        #expect(entry.requestable == nil)
        #expect(entry.accessibilityLine == "1. Signal to Noise, 2026")
    }

    @Test func aRowNotShownSaysWhy() {
        let empty = RowLayoutEntry(id: "list:3", title: "Trending", style: .poster, kind: .list, hidden: false, shown: false, note: "Nothing from this list is in your library yet", listID: 3, owner: .everyone)
        #expect(empty.notShownLine == "Not shown: nothing from this list is in your library yet")
        var hidden = empty
        hidden.hidden = true
        #expect(hidden.notShownLine == nil, "a hidden row says it is hidden instead")
        let acronym = RowLayoutEntry(id: "list:4", title: "x", style: .poster, kind: .list, hidden: false, shown: false, note: "TMDB has nothing for this list")
        #expect(acronym.notShownLine == "Not shown: TMDB has nothing for this list")
    }
}
