import Foundation
import WMMModel

/// The Apple TV grid's letter index (TvLibraryAZ.dc.html, PLAN-APPLE-REVIEW.md
/// §6.2): # and A to Z down the right edge, the letters with no title dimmed
/// and skipped.
///
/// It is built from what the server already sends: sorted by title, a page of
/// `GET /libraries/{id}/items` carries `letters`, each letter present in the
/// whole filtered list with its first item's offset, in list order. A
/// letter's count is the distance to the next letter's offset (the last
/// letter's, to the total). The server files a title under the letter of the
/// key it sorts by ("The Matrix" under M) and anything else under #, which
/// it keeps once, at its first place. Older servers send no letters, and
/// there is no index; nor is there when the list is sorted any other way
/// (``LibraryModel/letters`` is empty then).
public struct LetterIndex: Hashable, Sendable {
    public struct Entry: Hashable, Sendable, Identifiable {
        /// "#" or "A" to "Z".
        public var letter: String
        /// The letter's first title, in the whole list; nil when it has none.
        public var offset: Int?
        /// Titles under the letter.
        public var count: Int

        public var id: String { letter }
        public var isEmpty: Bool { count == 0 }

        /// What the server sent for it, to jump with.
        public var letterOffset: LetterOffset? { offset.map { LetterOffset(letter: letter, offset: $0) } }
    }

    /// The index's 27 places, top to bottom.
    public static let alphabet: [String] = ["#"] + (UnicodeScalar("A").value...UnicodeScalar("Z").value).map { String(UnicodeScalar($0)!) }

    /// Every place of ``alphabet``, empty or not.
    public let entries: [Entry]
    /// The titles the list holds.
    public let total: Int

    /// Nil when there is nothing to index: no letters (an older server, or not
    /// sorted by title) or no titles.
    public init?(letters: [LetterOffset], total: Int) {
        // Every letter sent bounds the one before it; only the index's own get a place.
        let known = letters.filter { $0.offset >= 0 && $0.offset < total }.sorted { $0.offset < $1.offset }
        guard total > 0, known.contains(where: { Self.alphabet.contains($0.letter) }) else { return nil }
        var counts: [String: (offset: Int, count: Int)] = [:]
        for (i, letter) in known.enumerated() where counts[letter.letter] == nil && Self.alphabet.contains(letter.letter) {
            let end = i + 1 < known.count ? known[i + 1].offset : total
            counts[letter.letter] = (letter.offset, end - letter.offset)
        }
        entries = Self.alphabet.map { letter in
            Entry(letter: letter, offset: counts[letter]?.offset, count: counts[letter]?.count ?? 0)
        }
        self.total = total
    }

    public func entry(_ letter: String) -> Entry? {
        entries.first { $0.letter == letter }
    }

    /// The letter the title at `offset` in the whole list is under: the last
    /// letter starting at or before it.
    public func letter(at offset: Int) -> String? {
        guard offset >= 0, offset < total else { return nil }
        return entries.filter { ($0.offset ?? .max) <= offset }.max { $0.offset! < $1.offset! }?.letter
    }

    /// The next letter with titles `by` places on (negative: up), skipping
    /// empty ones; nil past either end.
    public func step(from letter: String, by delta: Int) -> String? {
        guard delta != 0, let start = entries.firstIndex(where: { $0.letter == letter }) else { return nil }
        var i = start + delta.signum()
        var moved = 0
        var found: String?
        while entries.indices.contains(i) {
            if !entries[i].isEmpty {
                found = entries[i].letter
                moved += 1
                if moved == abs(delta) { break }
            }
            i += delta.signum()
        }
        return found
    }

    // MARK: Wording

    /// Beside the focused letter: "6 shows", "1 movie".
    public func countLine(_ entry: Entry, noun: String) -> String {
        plural(entry.count, noun)
    }

    /// VoiceOver's: "Jump to M, 6 shows", "Jump to numbers, 2 shows", "X, no shows".
    public func accessibilityLabel(_ entry: Entry, noun: String) -> String {
        guard !entry.isEmpty else { return "\(entry.letter), no \(noun)s" }
        return "Jump to \(entry.letter == "#" ? "numbers" : entry.letter), \(plural(entry.count, noun))"
    }
}

/// Page Up and Page Down on a grid (TvLibraryAZ.dc.html): two rows on or
/// back, in the same column, in any sort.
public enum GridPaging {
    /// The rows one press moves.
    public static let rows = 2

    /// Where focus goes from the item at `index` in a grid of `count` items,
    /// `columns` a row, `pages` presses on (negative: back). Past the end it
    /// stops on the last row, in the same column where that row has one,
    /// else on the last item; past the start, on the first row. Nil when it
    /// can't move (already on the first or the last row that way).
    public static func target(from index: Int, pages: Int, columns: Int, count: Int) -> Int? {
        guard count > 0, columns > 0, index >= 0, index < count, pages != 0 else { return nil }
        let column = index % columns
        let lastRow = (count - 1) / columns
        let row = min(max(index / columns + pages * rows, 0), lastRow)
        let target = min(row * columns + column, count - 1)
        return target == index ? nil : target
    }
}
