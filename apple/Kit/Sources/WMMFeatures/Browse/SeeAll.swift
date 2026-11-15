import Foundation
import WMMModel

// A row's See all (PLAN-SEE-ALL.md §3, §4.4): the card at the end of a row
// and the link in its heading say the same, and are worded here so the
// iPhone, iPad and Apple TV apps read alike, as Android's seeAllWords does.

/// What a row's See all says: on the card at the row's end, and to VoiceOver
/// on the heading's See all too.
public struct SeeAllWords: Hashable, Sendable {
    /// Where it goes, for the card's glyph: a grid for a library, a list's for a list.
    public enum Destination: Hashable, Sendable {
        case library(LibraryType)
        case list
    }

    public var destination: Destination
    /// The card's first line: "See all TV shows", "See all movies", a list's "See all".
    public var title: String
    /// The second line: "By last aired"; a list's "12 titles, with the ones you don’t have".
    /// Nil for a library link with no sort the grid offers.
    public var detail: String?
    /// "See all TV shows, by last aired"; a list's "See all of Top rated this year, 12 titles".
    public var accessibilityLabel: String

    public init(destination: Destination, title: String, detail: String?, accessibilityLabel: String) {
        self.destination = destination
        self.title = title
        self.detail = detail
        self.accessibilityLabel = accessibilityLabel
    }

    /// A library link's: "See all TV shows" over "By last aired". The second
    /// line is the grid's own sort label ("Last aired") after "By", so the
    /// card and the Sort menu read the same; a sort the library doesn't
    /// offer is one the grid won't take (``LibraryModel/open(_:)``), so it isn't named.
    public init(library type: LibraryType, sort: ItemSort?) {
        let title = type == .tv ? "See all TV shows" : "See all movies"
        let by = sort.flatMap { ItemSort.options(for: type).contains($0) ? "by \($0.label.lowercasedFirst)" : nil }
        self.init(destination: .library(type), title: title, detail: by.map(\.capitalizedFirst),
                  accessibilityLabel: [title, by].compactMap { $0 }.joined(separator: ", "))
    }

    /// A list's: "See all" over "12 titles, with the ones you don’t have" (its
    /// page lists every title on it, not only the library's); `total` nil
    /// leaves the count out.
    public init(listNamed name: String, total: Int?) {
        let count = total.map { plural($0, "title") }
        self.init(destination: .list, title: "See all",
                  detail: count.map { "\($0), with the ones you don’t have" } ?? "With the ones you don’t have",
                  accessibilityLabel: ["See all of \(name)", count].compactMap { $0 }.joined(separator: ", "))
    }

    /// The words for `row`'s See all, where `libraryType` is the type of the
    /// library its link names (nil when the screen doesn't know it). Nil when
    /// the row has no link, when the link's library isn't known, and for
    /// Continue watching and Live TV, which have no See all (decision 4).
    public init?(row: ScreenRow, libraryType: LibraryType?) {
        guard row.style != .continue, row.style != .live, let link = row.seeAll else { return nil }
        switch link {
        case .list:
            self.init(listNamed: row.title, total: row.listTotal)
        case .library(_, let sort, _, _, _):
            guard let libraryType else { return nil }
            self.init(library: libraryType, sort: sort)
        }
    }

    /// The See all card's identity among a row's cards (beside
    /// ``BrowseItem/cardID`` and ``EpisodeCard/cardKey``). It never becomes the
    /// iPad's leading card: ``RowPanel/leadingCard(settledOn:was:)``.
    public static let cardKey = "see-all"
}

extension RowPanel {
    /// The row's leading card once a swipe settles on `key`, where `current`
    /// was the leading one. The See all card never leads (PLAN-SEE-ALL.md
    /// §11.3): ``panel(for:card:)`` would take its key for the row's first
    /// card, so the panel would jump back to the start of the row. The panel
    /// stays on the card it was on.
    public static func leadingCard(settledOn key: String?, was current: String?) -> String? {
        key == SeeAllWords.cardKey ? current : key
    }
}

extension ScreenRow {
    /// A list row's whole count: 50 from its line "18 of 50 · From TMDB"; nil for other rows.
    public var listTotal: Int? {
        guard isList, let line = subtitle, let range = line.range(of: " of ") else { return nil }
        return Int(line[range.upperBound...].prefix { $0.isNumber || $0 == "," }.filter(\.isNumber))
    }
}

extension HomeData {
    /// The type of the library `id` names: Home's TV or movie library, or nil
    /// for one Home doesn't know (a Home that mixes two libraries of a type
    /// gets no link for those rows from the server, decision 6).
    public func libraryType(_ id: Int) -> LibraryType? {
        if id == tvLibraryID { return .tv }
        if id == movieLibraryID { return .movie }
        return nil
    }
}

extension LibraryType {
    /// The tab that shows this library's grid.
    public var tab: AppTab { self == .tv ? .tvShows : .movies }
}

private extension String {
    var lowercasedFirst: String { prefix(1).lowercased() + dropFirst() }
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}
