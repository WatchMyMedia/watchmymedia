import Foundation
import Testing
import WMMModel

/// The tile locator (PLAN-TRICKPLAY.md §2), as android/core/model's TrickplayTest has it.
@Suite struct TrickplayTests {
    let t = Trickplay(intervalMs: 10_000, width: 320, height: 180, columns: 10, rows: 10, count: 724, sheets: 8,
                      urlTemplate: "http://192.168.1.20:27587/api/files/42/trickplay/9c1e07a2/{sheet}.jpg")!

    @Test func aPositionFindsItsSheetAndCell() {
        #expect(t.tile(atMs: 0) == TrickplayTile(index: 0, sheet: 0, x: 0, y: 0))
        #expect(t.tile(atMs: 9_999) == TrickplayTile(index: 0, sheet: 0, x: 0, y: 0))
        // Tile 137: sheet 1, cell 37, column 7 and row 3.
        #expect(t.tile(atMs: 1_372_000) == TrickplayTile(index: 137, sheet: 1, x: 7 * 320, y: 3 * 180))
        #expect(t.tile(atMs: 999_999) == TrickplayTile(index: 99, sheet: 0, x: 9 * 320, y: 9 * 180))
        // Before the start, the first; past the end, the last, on the part-filled sheet.
        #expect(t.tile(atMs: -5_000).index == 0)
        #expect(t.tile(atMs: 9_000_000) == TrickplayTile(index: 723, sheet: 7, x: 3 * 320, y: 2 * 180))
    }

    @Test func sheetURLsFillTheNumber() {
        #expect(t.sheetURL(0)?.absoluteString == "http://192.168.1.20:27587/api/files/42/trickplay/9c1e07a2/0.jpg")
        #expect(t.sheetURL(7)?.absoluteString == "http://192.168.1.20:27587/api/files/42/trickplay/9c1e07a2/7.jpg")
        #expect(t.sheetURL(8) == nil)
        #expect(t.sheetURL(-1) == nil)
    }

    @Test func manifestsTheAppCannotDrawFromAreDropped() {
        let url = "http://h/{sheet}.jpg"
        #expect(Trickplay(intervalMs: 10_000, width: 0, height: 180, columns: 10, rows: 10, count: 5, sheets: 1, urlTemplate: url) == nil)
        #expect(Trickplay(intervalMs: 10_000, width: 320, height: 180, columns: 10, rows: 10, count: 5, sheets: 1, urlTemplate: "http://h/0.jpg") == nil)
        #expect(Trickplay(intervalMs: 10_000, width: 320, height: 180, columns: 10, rows: 10, count: 101, sheets: 1, urlTemplate: url) == nil)
        #expect(Trickplay(intervalMs: 0, width: 320, height: 180, columns: 10, rows: 10, count: 5, sheets: 1, urlTemplate: url) == nil)
        #expect(Trickplay(intervalMs: 10_000, width: 320, height: 180, columns: 10, rows: 10, count: 100, sheets: 1, urlTemplate: url) != nil)
    }
}
