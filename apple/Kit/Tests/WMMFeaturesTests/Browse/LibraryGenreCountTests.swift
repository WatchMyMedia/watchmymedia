import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@MainActor
@Suite struct LibraryGenreCountTests {
    let library = TestLibrary()
    let navigator = Navigator()

    init() { FakeLatency.duration = .zero }

    private func model() -> LibraryModel {
        LibraryModel(library: library, navigator: navigator, type: .tv, debounce: .zero)
    }

    @Test func serverCountsNeedNoSupplementalRequests() async throws {
        let model = model()
        await model.retry()
        #expect(library.queries.count == 1)
        #expect(!model.genreCounts.isEmpty)
        await model.setFilter(.fourK)
        await model.toggleDecade(2020)
        model.setSearchText("a")
        #expect(model.genreCounts.isEmpty, "old counts disappear while the query is changing")
        await model.pendingSearch?.value
        #expect(library.queries.count == 4)
        for genre in model.genres {
            let query = ItemQuery(libraryID: FakeData.tvLibrary, filter: .fourK, genre: genre, decade: 2020, text: "a", limit: 1)
            #expect(model.genreCounts[genre] == (try await library.fake.items(query)).total)
        }
    }

    @Test func countsExcludeTheSelectedGenreAndDoNotChangeWithSorting() async {
        let model = model()
        await model.retry()
        let counts = model.genreCounts
        await model.toggleGenre("Drama")
        #expect(model.genreCounts == counts)
        await model.setSort(.title)
        #expect(model.genreCounts == counts)
        #expect(library.queries.count == 3)
    }

    @Test func oldServersLeaveUnknownCountsBlank() async {
        library.itemsOverride = FakeData.shows
        let model = model()
        await model.retry()
        #expect(model.genreCounts.isEmpty)
        #expect(library.queries.count == 1)
        #expect(model.state.value?.total == FakeData.shows.count)
    }

    @Test func aRefreshUsesTheLatestCountsFromTheSameResponse() async {
        let model = model()
        await model.retry()
        library.itemsHandler = { query in
            ItemPage(items: [], total: 7, offset: query.offset, genres: ["Drama"], decades: [], genreCounts: ["Drama": 7])
        }
        await model.refresh()
        #expect(model.genreCounts == ["Drama": 7])
        #expect(library.queries.count == 2)
    }
}
