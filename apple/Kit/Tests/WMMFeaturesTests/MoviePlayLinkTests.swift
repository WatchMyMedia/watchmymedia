import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// The movie play link the Top Shelf publishes (PLAN-APPLE-REVIEW.md §2.5).
@MainActor
@Suite struct MoviePlayLinkTests {
    init() { FakeLatency.duration = .zero }

    @Test func roundTripsAndPlaysOnlyWhenPublished() throws {
        let link = DeepLink.playMovie(id: 500)
        #expect(link.url.absoluteString == "watchmymedia://movies/500?play=1")
        #expect(DeepLink(url: link.url) == link)
        #expect(DeepLink(url: try #require(URL(string: "watchmymedia://movies/500?play=0"))) == .movie(id: 500))
        #expect(DeepLink.opening(link.url(publishedFor: "srv-a", source: "top-shelf"), signedInServer: "srv-a") == .playMovie(id: 500))
        #expect(DeepLink.opening(link.url(publishedFor: "srv-a", source: "top-shelf"), signedInServer: "srv-b") == nil)
        // From anywhere else: the movie's page, with Play ready.
        #expect(DeepLink.opening(link.url, signedInServer: "srv-a") == .movie(id: 500))
    }

    @Test func openingItPlaysTheMovieFromWhereItWasLeft() async throws {
        let nav = Navigator()
        await nav.open(.playMovie(id: 500), library: FakeLibraryRepository())
        let playing = try #require(nav.playing)
        let expected = try #require(try await FakeLibraryRepository().movie(id: 500).playRequest())
        #expect(playing.fileID == expected.fileID)
        #expect(nav.path(for: .home).isEmpty)
    }

    @Test func aMovieThatCantBeLoadedOpensItsPage() async {
        let nav = Navigator()
        let library = FakeLibraryRepository()
        library.offline = true
        await nav.open(.playMovie(id: 500), library: library)
        #expect(nav.playing == nil)
        #expect(nav.path(for: .home) == [.movie(id: 500)])
    }
}
