import Foundation
import Testing
import WMMModel
@testable import WMMFakes

/// The large library for measuring the Movies grid (`-fakesLargeLibrary`).
@Suite @MainActor
struct FakeLargeLibraryTests {
    @Test func itHoldsAboutTwelveHundredDistinctMovies() {
        let movies = FakeData.largeMovies
        #expect(movies.count == 1_200)
        #expect(Set(movies.map(\.id)).count == movies.count)
        #expect(Set(movies.map(\.title)).count == movies.count)
        #expect(Array(movies.prefix(FakeData.movies.count)) == FakeData.movies, "the sample's come first")
        #expect(movies.allSatisfy { $0.kind == .movie && $0.libraryID == FakeData.movieLibrary })
    }

    @Test func theGridPagesThroughIt() async throws {
        FakeLatency.duration = .zero
        let library = FakeLibraryRepository()
        let small = try await library.items(ItemQuery(libraryID: FakeData.movieLibrary, sort: .title, filter: .all, offset: 0, limit: 60))
        #expect(small.total == FakeData.movies.count)
        library.largeLibrary = true
        let page = try await library.items(ItemQuery(libraryID: FakeData.movieLibrary, sort: .title, filter: .all, offset: 600, limit: 60))
        #expect(page.total == 1_200)
        #expect(page.summary?.items == 1_200, "the count above the grid says so too")
        #expect(page.items.count == 60)
        #expect((page.letters?.count ?? 0) > 10, "the A–Z bar has letters to jump to")
    }
}
