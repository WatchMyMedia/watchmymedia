import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@MainActor
@Suite struct LibraryLetterTests {
    let library = TestLibrary()
    let navigator = Navigator()
    let letters = [LetterOffset(letter: "A", offset: 0), LetterOffset(letter: "B", offset: 10), LetterOffset(letter: "C", offset: 20)]

    init() {
        FakeLatency.duration = .zero
        library.itemsOverride = (0..<30).map {
            BrowseItem(kind: .movie, id: 10_000 + $0, libraryID: FakeData.movieLibrary, title: "\(["A", "B", "C"][$0 / 10]) \($0 % 10)")
        }
        library.lettersOverride = letters
    }

    private func model() -> LibraryModel {
        LibraryModel(library: library, navigator: navigator, type: .movie, pageSize: 4)
    }

    @Test func jumpsPastTheLoadedPageAndPagesFromThatOffset() async throws {
        let model = model()
        await model.retry()
        #expect(model.letters.isEmpty)
        await model.setSort(.title)
        #expect(model.letters == letters)
        await model.jump(to: letters[1])
        #expect(library.queries.last?.offset == 10)
        #expect(model.state.value?.offset == 10)
        #expect(model.state.value?.items.first?.title == "B 0")
        #expect(model.selectedLetter == "B")
        #expect(model.scrollTargetID == 10_010)
        await model.loadMore()
        #expect(library.queries.last?.offset == 14)
        #expect(model.state.value?.items.map(\.id) == Array(10_010..<10_018))
        await model.refresh()
        #expect(library.queries.last?.offset == 10)
        #expect(library.queries.last?.limit == 8)
        #expect(model.state.value?.items.count == 8)
        #expect(model.selectedLetter == "B")
        await model.jump(to: letters[2])
        await model.loadMore()
        await model.loadMore()
        #expect(model.state.value?.items.count == 10)
        #expect(model.state.value?.hasMore == false)
    }

    @Test func aLoadedLetterScrollsWithoutFetchingAndCanBeTappedAgain() async {
        let model = model()
        await model.setSort(.title)
        let count = library.queries.count
        await model.jump(to: letters[0])
        let request = model.scrollRequestID
        await model.jump(to: letters[0])
        #expect(library.queries.count == count)
        #expect(model.scrollTargetID == 10_000)
        #expect(model.scrollRequestID == request + 1)
    }

    @Test func changingFiltersResetsTheJumpAndUsesTheFirstPage() async {
        let model = model()
        await model.setSort(.title)
        await model.jump(to: letters[2])
        await model.setFilter(.fourK)
        #expect(model.selectedLetter == nil)
        #expect(model.scrollTargetID == nil)
        #expect(model.state.value?.offset == 0)
        #expect(library.queries.last?.offset == 0)
        #expect(model.activeFilterCount == 1)
        await model.toggleGenre("Drama")
        await model.toggleDecade(2020)
        #expect(model.activeFilterCount == 3)
        await model.clearFilters()
        #expect(model.activeFilterCount == 0)
        await model.setSort(.added)
        #expect(model.letters.isEmpty)
    }

    @Test func olderServersWithoutLettersKeepNormalPaging() async {
        library.lettersOverride = nil
        let model = model()
        await model.setSort(.title)
        #expect(model.letters.isEmpty)
        let count = library.queries.count
        await model.jump(to: letters[2])
        #expect(library.queries.count == count)
        #expect(model.scrollTargetID == nil)
        await model.loadMore()
        #expect(library.queries.last?.offset == 4)
        #expect(model.state.value?.items.count == 8)
    }

    @Test func aFailedJumpRetainsTheGridAndCanBeRetried() async {
        let model = model()
        await model.setSort(.title)
        let grid = model.state
        library.failure = APIError(status: 500, message: "Try again shortly.")
        await model.jump(to: letters[2])
        #expect(model.state == grid)
        #expect(model.message == "Try again shortly.")
        #expect(!model.loadingMore)
        library.failure = nil
        await model.jump(to: letters[2])
        #expect(model.state.value?.offset == 20)
    }

    @Test func anInMemoryJumpSupersedesASlowNetworkJump() async throws {
        let model = model()
        await model.setSort(.title)
        let items = try #require(library.itemsOverride)
        var response: CheckedContinuation<ItemPage, any Error>?
        library.itemsHandler = { _ in
            try await withCheckedThrowingContinuation { response = $0 }
        }
        let pending = Task { await model.jump(to: letters[2]) }
        #expect(await eventually { response != nil })
        await model.jump(to: letters[0])
        #expect(!model.loadingMore)
        response?.resume(returning: ItemPage(items: Array(items[20..<24]), total: 30, offset: 20, genres: [], decades: [], letters: letters))
        await pending.value
        #expect(model.selectedLetter == "A")
        #expect(model.state.value?.offset == 0)
        #expect(model.scrollTargetID == 10_000)
    }

    @Test func aSlowJumpCannotReplaceANewerSort() async throws {
        let model = model()
        await model.setSort(.title)
        let items = try #require(library.itemsOverride)
        var response: CheckedContinuation<ItemPage, any Error>?
        library.itemsHandler = { _ in
            try await withCheckedThrowingContinuation { response = $0 }
        }
        let pending = Task { await model.jump(to: letters[2]) }
        #expect(await eventually { response != nil })
        library.itemsHandler = nil
        await model.setSort(.added)
        response?.resume(returning: ItemPage(items: Array(items[20..<24]), total: 30, offset: 20, genres: [], decades: [], letters: letters))
        await pending.value
        #expect(model.sort == .added)
        #expect(model.state.value?.offset == 0)
        #expect(model.letters.isEmpty)
        #expect(model.selectedLetter == nil)
    }

    @Test func anOldIndexCannotJumpDuringAFilterChange() async throws {
        let model = model()
        await model.setSort(.title)
        var response: CheckedContinuation<ItemPage, any Error>?
        library.itemsHandler = { _ in
            try await withCheckedThrowingContinuation { response = $0 }
        }
        let changing = Task { await model.setFilter(.fourK) }
        #expect(await eventually { response != nil })
        #expect(model.letters.isEmpty)
        let before = library.queries.count
        await model.jump(to: letters[2])
        await model.loadMore()
        #expect(library.queries.count == before)
        response?.resume(returning: ItemPage(items: [], total: 0, offset: 0, genres: [], decades: [], letters: []))
        await changing.value
        #expect(model.filter == .fourK)
        #expect(model.state.value?.offset == 0)
    }

    @Test func fakeLettersDescribeTheEntireFilteredList() async throws {
        let fake = FakeLibraryRepository()
        let query = ItemQuery(libraryID: FakeData.tvLibrary, sort: .title, filter: .fourK, genre: "Sci-Fi", offset: 0, limit: 1)
        let page = try await fake.items(query)
        let letters = try #require(page.letters)
        #expect(page.items.count == 1)
        #expect(letters.count > 1, "the index includes letters beyond this page")
        for letter in letters {
            var jump = query
            jump.offset = letter.offset
            let result = try await fake.items(jump)
            #expect(result.letters == letters)
            #expect(result.items.first?.quality == "4K")
            #expect(result.items.first?.genres.contains("Sci-Fi") == true)
            #expect(result.items.first != nil)
            #expect(result.offset == letter.offset)
        }
    }
}
