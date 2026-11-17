import Foundation
import Testing
import WMMFakes
import WMMModel

/// The fakes' related titles keep the server's rules (PLAN-RELATED.md §3),
/// so the pages drawn on them look as a real server's would.
@MainActor
@Suite struct FakeRelatedTests {
    init() { FakeLatency.duration = .zero }

    @Test func everyPageListsItsOwnKindOnlyAndNeverItself() {
        for movie in FakeData.movies {
            let rows = FakeRelated.rows(for: .movie(id: movie.id))
            #expect(rows.flatMap(\.items).allSatisfy { $0.kind == .movie && $0.id != movie.id }, "\(movie.title)")
            #expect(rows.allSatisfy { $0.style == .poster && $0.seeAll == nil })
            let similar = rows.first { $0.id == "similar" }
            #expect(similar.map { $0.items.count >= 3 } ?? true, "More like this shows from three")
        }
        for show in FakeData.shows {
            let rows = FakeRelated.rows(for: .show(id: show.id))
            #expect(rows.map(\.id).allSatisfy { $0 == "similar" }, "a show has no collection")
            #expect(rows.flatMap(\.items).allSatisfy { $0.kind == .show && $0.id != show.id }, "\(show.title)")
        }
    }

    @Test func theCollectionIsInReleaseOrderAndNotListedAgain() {
        let rows = FakeRelated.rows(for: .movie(id: 505))
        #expect(rows.map(\.id) == ["collection", "similar"])
        #expect(rows[0].title == FakeRelated.collectionName)
        #expect(rows[0].items.map(\.id) == [508, 500])
        #expect(rows[0].items.map(\.releaseDate) == rows[0].items.map(\.releaseDate).sorted { ($0 ?? "") < ($1 ?? "") })
        #expect(Set(rows[1].items.map(\.id)).isDisjoint(with: FakeRelated.collection))
    }

    @Test func somePagesHaveNothing() {
        #expect(FakeRelated.rows(for: .movie(id: 507)).isEmpty)
        #expect(FakeRelated.rows(for: .show(id: 108)).isEmpty)
        #expect(FakeRelated.rows(for: .movie(id: 501)).map(\.id) == ["similar"])
    }

    @Test func theyFollowTheServersLevelAndTheMarksMadeHere() async throws {
        let library = FakeLibraryRepository()
        library.servers = FakeServerRepository(apiLevel: relatedAPILevel - 1)
        #expect(!library.offersRelated)
        #expect(try await library.relatedRows(.movie(id: 500)).isEmpty)

        library.servers = FakeServerRepository(apiLevel: relatedAPILevel)
        #expect(library.offersRelated)
        let before = try await library.relatedRows(.movie(id: 500))
        let card = try #require(before.last?.items.first { !$0.watched })
        try await library.setWatched(.movie, id: card.id, watched: true)
        let after = try await library.relatedRows(.movie(id: 500))
        #expect(after.last?.items.first { $0.id == card.id }?.watched == true)
    }

    @Test func theFakesTakeTheLevelFromTheirArgument() {
        #expect(AppServices.fake(related: true).library.offersRelated)
        #expect(!AppServices.fake().library.offersRelated, "the fakes' server is at level 6 by default")
    }
}
