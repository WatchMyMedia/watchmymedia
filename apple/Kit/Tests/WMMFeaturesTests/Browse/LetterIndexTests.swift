import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// The Apple TV grid's A–Z index (TvLibraryAZ.dc.html): which letter goes
/// where, its counts and wording, Page Up/Down, and a jump that keeps the
/// grid's start.
@MainActor
@Suite struct LetterIndexTests {
    let library = TestLibrary()
    let navigator = Navigator()

    init() {
        FakeLatency.duration = .zero
    }

    // MARK: The index

    @Test func countsEachLetterToTheNextAndDimsTheRest() throws {
        // Two titles under #, three under A, none under B, one under C, four under M.
        let index = try #require(LetterIndex(letters: [
            LetterOffset(letter: "#", offset: 0), LetterOffset(letter: "A", offset: 2),
            LetterOffset(letter: "C", offset: 5), LetterOffset(letter: "M", offset: 6),
        ], total: 10))
        #expect(index.entries.map(\.letter) == LetterIndex.alphabet)
        #expect(index.entries.count == 27)
        #expect(index.entry("#")?.count == 2)
        #expect(index.entry("A")?.count == 3)
        #expect(index.entry("A")?.offset == 2)
        #expect(index.entry("B")?.isEmpty == true)
        #expect(index.entry("B")?.letterOffset == nil)
        #expect(index.entry("C")?.count == 1)
        #expect(index.entry("M")?.count == 4)
        #expect(index.entry("M")?.letterOffset == LetterOffset(letter: "M", offset: 6))
        #expect(index.entries.filter { !$0.isEmpty }.map(\.letter) == ["#", "A", "C", "M"])
    }

    @Test func noIndexWithoutLettersOrTitles() {
        #expect(LetterIndex(letters: [], total: 40) == nil)
        #expect(LetterIndex(letters: [LetterOffset(letter: "A", offset: 0)], total: 0) == nil)
        // An offset the list doesn't reach, or a letter outside the index, is left out.
        let index = LetterIndex(letters: [LetterOffset(letter: "A", offset: 0), LetterOffset(letter: "Ä", offset: 3), LetterOffset(letter: "Z", offset: 12)], total: 5)
        #expect(index?.entry("A")?.count == 3)
        #expect(index?.entry("Z")?.isEmpty == true)
    }

    @Test func listOrderNotAlphabetOrderGivesTheCounts() throws {
        // The server keeps # once, at its first place; titles after Z that it
        // files under # (accented letters) count with Z.
        let index = try #require(LetterIndex(letters: [LetterOffset(letter: "Z", offset: 4), LetterOffset(letter: "#", offset: 0), LetterOffset(letter: "A", offset: 1)], total: 7))
        #expect(index.entry("#")?.count == 1)
        #expect(index.entry("A")?.count == 3)
        #expect(index.entry("Z")?.count == 3)
    }

    @Test func theLetterATitleIsUnder() throws {
        let index = try #require(LetterIndex(letters: [LetterOffset(letter: "#", offset: 0), LetterOffset(letter: "A", offset: 2), LetterOffset(letter: "M", offset: 6)], total: 10))
        #expect(index.letter(at: 0) == "#")
        #expect(index.letter(at: 1) == "#")
        #expect(index.letter(at: 2) == "A")
        #expect(index.letter(at: 5) == "A")
        #expect(index.letter(at: 9) == "M")
        #expect(index.letter(at: 10) == nil)
        #expect(index.letter(at: -1) == nil)
    }

    @Test func upAndDownSkipEmptyLetters() throws {
        let index = try #require(LetterIndex(letters: [LetterOffset(letter: "#", offset: 0), LetterOffset(letter: "A", offset: 2), LetterOffset(letter: "M", offset: 6)], total: 10))
        #expect(index.step(from: "A", by: 1) == "M")
        #expect(index.step(from: "M", by: -1) == "A")
        #expect(index.step(from: "A", by: -1) == "#")
        #expect(index.step(from: "#", by: -1) == nil)
        #expect(index.step(from: "M", by: 1) == nil)
        #expect(index.step(from: "#", by: 2) == "M")
        // From an empty letter, the next with titles.
        #expect(index.step(from: "B", by: 1) == "M")
    }

    @Test func wordsTheCountAndTheLabels() throws {
        let index = try #require(LetterIndex(letters: [LetterOffset(letter: "#", offset: 0), LetterOffset(letter: "A", offset: 2), LetterOffset(letter: "M", offset: 3)], total: 9))
        let numbers = try #require(index.entry("#"))
        let a = try #require(index.entry("A"))
        let m = try #require(index.entry("M"))
        let x = try #require(index.entry("X"))
        #expect(index.countLine(m, noun: "show") == "6 shows")
        #expect(index.countLine(a, noun: "movie") == "1 movie")
        #expect(index.accessibilityLabel(m, noun: "show") == "Jump to M, 6 shows")
        #expect(index.accessibilityLabel(a, noun: "show") == "Jump to A, 1 show")
        #expect(index.accessibilityLabel(numbers, noun: "show") == "Jump to numbers, 2 shows")
        #expect(index.accessibilityLabel(x, noun: "show") == "X, no shows")
    }

    @Test func theModelHasAnIndexOnlyWhenSortedByTitle() async {
        library.itemsOverride = items(30)
        library.lettersOverride = [LetterOffset(letter: "A", offset: 0), LetterOffset(letter: "B", offset: 10), LetterOffset(letter: "C", offset: 20)]
        let model = LibraryModel(library: library, navigator: navigator, type: .tv, pageSize: 7)
        await model.retry()
        #expect(model.letterIndex == nil)
        await model.setSort(.title)
        #expect(model.letterIndex?.entry("B")?.count == 10)
        #expect(model.letterIndex?.total == 30)
        library.lettersOverride = nil
        await model.setSort(.added)
        await model.setSort(.title)
        #expect(model.letterIndex == nil, "older servers send no letters")
    }

    // MARK: Page Up and Page Down

    @Test func pagingMovesTwoRowsInTheSameColumn() {
        // Seven a row, 60 titles: rows 0–8, the last holding four.
        #expect(GridPaging.target(from: 3, pages: 1, columns: 7, count: 60) == 17)
        #expect(GridPaging.target(from: 17, pages: -1, columns: 7, count: 60) == 3)
        #expect(GridPaging.target(from: 10, pages: -1, columns: 7, count: 60) == 3, "up from the second row stops on the first")
        #expect(GridPaging.target(from: 3, pages: -1, columns: 7, count: 60) == nil, "nothing above the first row")
        #expect(GridPaging.target(from: 45, pages: 1, columns: 7, count: 60) == 59, "the short last row has no seventh column")
        #expect(GridPaging.target(from: 44, pages: 1, columns: 7, count: 60) == 58)
        #expect(GridPaging.target(from: 58, pages: 1, columns: 7, count: 60) == nil, "nothing below the last row")
        #expect(GridPaging.target(from: 0, pages: 0, columns: 7, count: 60) == nil)
        #expect(GridPaging.target(from: 0, pages: 1, columns: 7, count: 0) == nil)
    }

    // MARK: Jumping without losing the grid's start

    private func items(_ count: Int) -> [BrowseItem] {
        (0..<count).map { BrowseItem(kind: .show, id: 10_000 + $0, libraryID: FakeData.tvLibrary, title: "\(["A", "B", "C", "D"][$0 * 4 / count]) \($0)") }
    }

    @Test func anExtendingJumpLoadsUpToTheLetterAfterWhatIsHeld() async {
        library.itemsOverride = items(40)
        let letters = [LetterOffset(letter: "A", offset: 0), LetterOffset(letter: "B", offset: 10), LetterOffset(letter: "C", offset: 20), LetterOffset(letter: "D", offset: 30)]
        library.lettersOverride = letters
        let model = LibraryModel(library: library, navigator: navigator, type: .tv, pageSize: 7, reloadPageSize: 8, letterJump: .extend)
        await model.setSort(.title)
        #expect(model.state.value?.items.count == 7)
        await model.jump(to: letters[2])
        // Titles 7 up to C's first (20) and a page more (27), in requests of at most eight.
        #expect(library.queries.suffix(3).map(\.offset) == [7, 15, 23])
        #expect(library.queries.suffix(3).map(\.limit) == [8, 8, 4])
        #expect(model.state.value?.offset == 0)
        #expect(model.state.value?.items.map(\.id) == Array(10_000..<10_027))
        #expect(model.scrollTargetID == 10_020)
        #expect(model.selectedLetter == "C")
        // Back up to B is in memory now.
        let count = library.queries.count
        await model.jump(to: letters[1])
        #expect(library.queries.count == count)
        #expect(model.scrollTargetID == 10_010)
        // Paging on carries on from the end.
        await model.loadMore()
        #expect(library.queries.last?.offset == 27)
        #expect(model.state.value?.items.count == 34)
    }

    @Test func anExtendingJumpFollowsTheLetterWhereTheFreshIndexPutsIt() async {
        library.itemsOverride = items(40)
        library.lettersOverride = [LetterOffset(letter: "A", offset: 0), LetterOffset(letter: "D", offset: 30)]
        let model = LibraryModel(library: library, navigator: navigator, type: .tv, pageSize: 7, letterJump: .extend)
        await model.setSort(.title)
        let stale = LetterOffset(letter: "D", offset: 30)
        // A title was added before D meanwhile; the page's letters say so.
        library.lettersOverride = [LetterOffset(letter: "A", offset: 0), LetterOffset(letter: "D", offset: 31)]
        await model.jump(to: stale)
        #expect(model.scrollTargetID == 10_031)
        #expect(model.state.value?.letters?.last?.offset == 31)
    }

    @Test func aFailedExtendingJumpKeepsTheGrid() async {
        library.itemsOverride = items(40)
        let letters = [LetterOffset(letter: "A", offset: 0), LetterOffset(letter: "D", offset: 30)]
        library.lettersOverride = letters
        let model = LibraryModel(library: library, navigator: navigator, type: .tv, pageSize: 7, letterJump: .extend)
        await model.setSort(.title)
        let grid = model.state
        library.failure = APIError(status: 500, message: "Try again shortly.")
        await model.jump(to: letters[1])
        #expect(model.state == grid)
        #expect(model.message == "Try again shortly.")
        #expect(!model.loadingMore)
        #expect(model.selectedLetter == nil)
    }

    @Test func thePageJumpStaysTheDefault() async {
        library.itemsOverride = items(40)
        let letters = [LetterOffset(letter: "A", offset: 0), LetterOffset(letter: "D", offset: 30)]
        library.lettersOverride = letters
        let model = LibraryModel(library: library, navigator: navigator, type: .tv, pageSize: 7)
        await model.setSort(.title)
        await model.jump(to: letters[1])
        #expect(model.state.value?.offset == 30)
        #expect(library.queries.last?.offset == 30)
    }
}
