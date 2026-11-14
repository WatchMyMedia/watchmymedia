import Foundation
import Testing
import WMMModel
@testable import WMMFeatures

/// Words both apps' views used to write themselves, now shared (PLAN-APPLE-REVIEW.md §5.8).
@Suite struct SharedWordingTests {
    @Test func signInAndServerScreens() {
        #expect(pairingCodeAccessibilityLabel("") == "No code yet")
        #expect(pairingCodeAccessibilityLabel("K7M4PQ") == "Code K 7 M 4 P Q")
        #expect(signOutQuestion(serverName: "Home") == "Sign out of Home?")
        #expect(signOutQuestion(serverName: nil) == "Sign out?")
        #expect(pairingGettingCode == "Getting a code…")
        #expect(pairingCodeAccepted == "Code accepted. Signing in…")
        #expect(lookingForServers == "Looking for servers…")
        #expect(serverAddressExample == "192.168.1.20 or https://home.example.net", "an example address, never a real server's")
    }

    @Test func homesRows() {
        #expect([continueWatchingTitle, recentlyAddedTitle, recentlyAiredTitle, recentlyReleasedTitle]
                == ["Continue watching", "Recently added", "Recently aired", "Recently released"])
        #expect(nextRowLabel("Recently added") == "Next row: Recently added")
        #expect(reportProblemTitle == "Report a problem")
    }

    @Test func fixedHomeRowsAsApplesTVDrawsThem() {
        let show = BrowseItem(kind: .show, id: 1, libraryID: 1, title: "Harbor Lights")
        let movie = BrowseItem(kind: .movie, id: 2, libraryID: 2, title: "Night Ferry")
        let home = HomeData(hero: nil, continueWatching: [], recentlyAddedShows: [show], recentlyAired: [], recentlyAddedMovies: [movie], recentlyReleased: [movie], totals: LibraryTotals(movies: 1, shows: 1, episodes: 0, files: 2, totalSizeBytes: 0), lastScannedAt: nil, movieLibraryID: 2, tvLibraryID: 1)
        #expect(home.fixedRows.map(\.title) == ["Live TV", "Recently added · TV Shows", "Recently added · Movies", "Recently released · Movies"],
                "empty rows are left out; the Live TV row stays, drawing nothing without Live TV")
    }

    @Test func liveTVGuideAndChannels() {
        let channel = LiveChannel(id: "ch-4.3", number: "4.3", name: "Coast Shop")
        #expect(liveNoListingsAccessibilityLabel(channel) == "4.3 Coast Shop, No listings. It still plays.")
        #expect(liveListingsFailed("The server is busy.") == "The server is busy. Channels still play.")
        #expect(liveTunerReleasesIn(seconds: 245) == "Tuner releases in 4:05")
        #expect(liveTunerReleasesIn(seconds: 59) == "Tuner releases in 0:59")
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        let programme = LiveProgramme(id: "p", start: start, end: start + 1800, title: "Station 9")
        let label = liveCellAccessibilityLabel(programme, reminded: true, channel: channel)
        #expect(label.hasPrefix(liveAccessibilityLabel(programme)))
        #expect(label.hasSuffix(", reminder set, 4.3 Coast Shop"))
        #expect(!liveCellAccessibilityLabel(programme, reminded: false, channel: channel).contains("reminder set"))
    }
}
