import Foundation
import WMMModel

/// Related titles on the fakes (PLAN-RELATED.md §3; every title is
/// fictional), as the server sends them from API level 30
/// (`-fakesRelated`): Cold Harbour Road, Low Tide at Carrow and Dead
/// Reckoning Bay are the Carrow Coast Collection, so each of their pages has
/// the other two in release order, then More like this. Every other movie and
/// show has More like this, except Tin Sky and Glass Country, which have
/// nothing, so their pages end as they did.
public enum FakeRelated {
    public static let collectionName = "Carrow Coast Collection"
    /// The collection's films, in release order.
    public static let collection = [508, 505, 500]
    /// Pages with no rows at all.
    public static let none: Set<Int> = [507, 108]
    /// More like this is at most this many on the fakes.
    static let similarLimit = 8

    /// The rows for `title`'s page, without the marks made here.
    public static func rows(for title: RelatedTitle) -> [ScreenRow] {
        switch title {
        case .movie(let id): movieRows(id)
        case .show(let id): showRows(id)
        }
    }

    private static func movieRows(_ id: Int) -> [ScreenRow] {
        guard FakeData.movies.contains(where: { $0.id == id }), !none.contains(id) else { return [] }
        var rows: [ScreenRow] = []
        let inCollection = collection.contains(id)
        if inCollection {
            let others = collection.filter { $0 != id }.compactMap { other in FakeData.movies.first { $0.id == other } }
            rows.append(ScreenRow(id: "collection", style: .poster, title: collectionName, items: others))
        }
        let left = FakeData.movies.filter { $0.id != id && !none.contains($0.id) && !(inCollection && collection.contains($0.id)) }
        let similar = id == 500
            // Dead Reckoning Bay's as its boards draw it: a New one, a watched one and one partway through.
            ? [502, 504, 506, 501, 509, 503].compactMap { other in left.first { $0.id == other } }
            : alike(left, to: FakeData.movies.first { $0.id == id })
        if similar.count >= 3 { rows.append(ScreenRow(id: "similar", style: .poster, title: "More like this", items: similar)) }
        return rows
    }

    private static func showRows(_ id: Int) -> [ScreenRow] {
        guard let show = FakeData.shows.first(where: { $0.id == id }), !none.contains(id) else { return [] }
        let similar = alike(FakeData.shows.filter { $0.id != id && !none.contains($0.id) }, to: show)
        return similar.count >= 3 ? [ScreenRow(id: "similar", style: .poster, title: "More like this", items: similar)] : []
    }

    /// Those sharing a genre first, then the nearest in years, best first.
    private static func alike(_ candidates: [BrowseItem], to title: BrowseItem?) -> [BrowseItem] {
        guard let title else { return [] }
        let genres = Set(title.genres)
        let year = title.year ?? 2020
        return Array(candidates.sorted { a, b in
            let sharedA = !genres.isDisjoint(with: a.genres), sharedB = !genres.isDisjoint(with: b.genres)
            if sharedA != sharedB { return sharedA }
            let apart = (abs((a.year ?? year) - year), abs((b.year ?? year) - year))
            return apart.0 != apart.1 ? apart.0 < apart.1 : a.id < b.id
        }.prefix(similarLimit))
    }
}
