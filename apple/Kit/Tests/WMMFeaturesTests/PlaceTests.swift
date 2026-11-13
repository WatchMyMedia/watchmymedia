import Foundation
import Testing
import WMMModel
@testable import WMMFeatures

/// Coming back to where the person was after the system evicts the app (PLAN-APPLE-REVIEW.md §6.3).
@Suite @MainActor
struct PlaceTests {
    private func defaults() -> UserDefaults {
        let name = "PlaceTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    @Test func everyRouteEncodesAndReadsBack() throws {
        let routes: [Route] = [
            .show(id: 100), .show(id: 100, season: 3), .episode(showID: 100, season: nil, id: 100037), .episode(showID: 100, season: 3, id: 100037),
            .movie(id: 500), .linkTV(code: nil), .linkTV(code: "K7M4PQ"), .myRequests, .inbox, .downloads,
            .liveRecordings, .liveReminders, .liveSearch, .mediaList(id: 9001),
        ]
        let place = SavedPlace(tab: .movies, paths: [.home: routes, .movies: [.movie(id: 501)]])
        let read = try JSONDecoder().decode(SavedPlace.self, from: JSONEncoder().encode(place))
        #expect(read == place)
    }

    @Test func theNavigatorGoesBackToItsTabAndPagesButNotThePlayerOrSettings() {
        let before = Navigator()
        before.select(.tvShows)
        before.open(.show(id: 100, season: 2))
        before.open(.episode(showID: 100, season: 2, id: 100021))
        before.select(.movies)
        before.open(.movie(id: 500))
        before.setPath([.downloads], for: .settings)
        before.play(PlayRequest(fileID: 9101, title: "Harbor Lights"))
        let place = before.place
        #expect(place.tab == .movies)
        #expect(place.paths[.settings] == nil)

        let after = Navigator()
        after.restore(place)
        #expect(after.tab == .movies)
        #expect(after.path(for: .movies) == [.movie(id: 500)])
        #expect(after.path(for: .tvShows) == [.show(id: 100, season: 2), .episode(showID: 100, season: 2, id: 100021)])
        #expect(after.path(for: .settings).isEmpty)
        #expect(after.playbackPresentation == nil)
    }

    @Test func settingsIsNotAPlaceToComeBackTo() {
        let navigator = Navigator()
        navigator.open(.movie(id: 500))
        navigator.select(.settings)
        #expect(navigator.place.tab == .home)
        #expect(navigator.place.paths[.home] == [.movie(id: 500)])
    }

    @Test func eachAccountOnEachServerHasItsOwnPlace() {
        let store = PlaceStore(defaults: defaults())
        let place = SavedPlace(tab: .movies, paths: [.movies: [.movie(id: 500)]])
        store.save(place, server: "server-a", user: 1)
        #expect(store.place(server: "server-a", user: 1) == place)
        #expect(store.place(server: "server-a", user: 2) == nil, "Another account on the server")
        #expect(store.place(server: "server-b", user: 1) == nil, "The same account number on another server")
        store.forget(server: "server-a", user: 1)
        #expect(store.place(server: "server-a", user: 1) == nil)
    }

    @Test func aPageThatNoLongerReadsEndsItsTabsPathThere() throws {
        // A later version's page in the middle of TV Shows, and a tab this version doesn't know.
        let json = """
        {"tab":"tvShows","paths":{
          "tvShows":[{"show":{"id":100}},{"seasonPicker":{"id":100}},{"movie":{"id":500}}],
          "movies":[{"movie":{"id":501}}],
          "sports":[{"movie":{"id":1}}]
        }}
        """
        let place = try JSONDecoder().decode(SavedPlace.self, from: Data(json.utf8))
        #expect(place.tab == .tvShows)
        #expect(place.paths[.tvShows] == [.show(id: 100)])
        #expect(place.paths[.movies] == [.movie(id: 501)])
        #expect(place.paths.count == 2)
    }

    @Test func aPlaceThatDoesNotReadAtAllIsNone() {
        let defaults = defaults()
        defaults.set(Data("not json".utf8), forKey: "place.server-a.1")
        #expect(PlaceStore(defaults: defaults).place(server: "server-a", user: 1) == nil)
    }
}
