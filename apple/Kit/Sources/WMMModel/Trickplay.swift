import Foundation

/// A file's seek thumbnails (PLAN-TRICKPLAY.md §2): tiles of `width` ×
/// `height` pixels, one every `intervalMs`, `columns` × `rows` to a JPEG
/// sheet. Tile i covers [i × intervalMs, (i+1) × intervalMs); the last
/// sheet is partly filled (android/core/model's `Trickplay`).
public struct Trickplay: Hashable, Sendable {
    public var intervalMs: Int
    public var width: Int
    public var height: Int
    public var columns: Int
    public var rows: Int
    public var count: Int
    public var sheets: Int
    /// A sheet's absolute URL with `{sheet}` for its number from 0, without a token.
    public var urlTemplate: String

    /// Nil for a manifest the app cannot draw from: a size of 0, no
    /// `{sheet}` in the URL, or more tiles than the sheets hold.
    public init?(intervalMs: Int, width: Int, height: Int, columns: Int, rows: Int, count: Int, sheets: Int, urlTemplate: String) {
        guard intervalMs > 0, width > 0, height > 0, columns > 0, rows > 0, count > 0, sheets > 0,
              count <= sheets * columns * rows, urlTemplate.contains("{sheet}") else { return nil }
        self.intervalMs = intervalMs
        self.width = width
        self.height = height
        self.columns = columns
        self.rows = rows
        self.count = count
        self.sheets = sheets
        self.urlTemplate = urlTemplate
    }

    /// The tiles a full sheet holds.
    public var perSheet: Int { columns * rows }

    /// The tile showing `ms` (§2's locator): the first before the start, the last past the end.
    public func tile(atMs ms: Int) -> TrickplayTile {
        let i = min(max(ms, 0) / intervalMs, count - 1)
        let cell = i % perSheet
        return TrickplayTile(index: i, sheet: i / perSheet, x: (cell % columns) * width, y: (cell / columns) * height)
    }

    /// A sheet's URL, or nil for a sheet the file does not have.
    public func sheetURL(_ sheet: Int) -> URL? {
        guard (0..<sheets).contains(sheet) else { return nil }
        return URL(string: urlTemplate.replacingOccurrences(of: "{sheet}", with: String(sheet)))
    }
}

/// Where one tile is: its number, its sheet, and its top-left corner on the sheet in pixels.
public struct TrickplayTile: Hashable, Sendable {
    public var index: Int
    public var sheet: Int
    public var x: Int
    public var y: Int

    public init(index: Int, sheet: Int, x: Int, y: Int) {
        self.index = index
        self.sheet = sheet
        self.x = x
        self.y = y
    }
}
