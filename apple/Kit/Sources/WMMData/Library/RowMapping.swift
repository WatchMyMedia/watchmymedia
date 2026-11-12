import Foundation
import WMMAPI
import WMMModel

// Arranged rows and lists (PLAN-ROWS.md §6) from the generated types. Styles,
// kinds and states are plain strings: what this app doesn't know is skipped,
// as a trailer source it doesn't know is (PLAN-ROWS.md Decision 2).

extension Components.Schemas.ScreenRow {
    /// Nil for a style this app doesn't draw, or a row with nothing to draw.
    var model: ScreenRow? {
        guard let style = RowStyle(rawValue: style) else { return nil }
        let row = ScreenRow(
            id: id,
            style: style,
            title: title,
            subtitle: Mapping.nonBlank(subtitle),
            items: (items ?? []).compactMap(\.model),
            episodes: (episodes ?? []).map(\.model),
            continueItems: (_continue ?? []).compactMap(\.model),
            seeAll: seeAll?.model,
            source: source?.model,
            listID: Mapping.positive(listId)
        )
        return row.isEmpty ? nil : row
    }
}

extension Components.Schemas.ScreenRowLink {
    /// Nil for a kind this app doesn't know: the row then has no See all.
    var model: RowLink? {
        switch kind {
        case "library":
            guard let id = Mapping.positive(libraryId) else { return nil }
            return .library(
                id: id, sort: sort.flatMap(ItemSort.init(rawValue:)), filter: filter.flatMap(ItemFilter.init(rawValue:)),
                genre: Mapping.nonBlank(genre), decade: Mapping.positive(decade)
            )
        case "list":
            return Mapping.positive(listId).map { .list(id: $0) }
        default:
            return nil
        }
    }
}

extension Components.Schemas.ScreenRowSource {
    var model: RowSource {
        RowSource(kind: kind, name: name, url: Mapping.nonBlank(url).flatMap(URL.init(string:)))
    }
}

extension Components.Schemas.RowLayoutEntry {
    var model: RowLayoutEntry {
        let entryKind: RowLayoutEntry.Kind = switch self.kind {
        case "builtin": .builtin
        case "list": .list
        default: .other(self.kind)
        }
        return RowLayoutEntry(
            id: id, title: title, style: RowStyle(rawValue: style), kind: entryKind,
            hidden: hidden, shown: shown, note: Mapping.nonBlank(note), listID: Mapping.positive(listId),
            owner: owner.flatMap(RowLayoutEntry.Owner.init(api:))
        )
    }
}

extension Components.Schemas.RowLayout {
    func model(_ asked: RowScreen) -> RowLayout {
        RowLayout(screen: RowScreen(key: screen) ?? asked, rows: rows.map(\.model), customized: customized)
    }
}

extension RowLayoutEntry.Owner {
    init?(api: String) {
        switch api {
        case "me": self = .me
        case "everyone": self = .everyone
        default: return nil
        }
    }
}

extension Components.Schemas.MediaList {
    var model: MediaList {
        MediaList(
            id: Int(id), name: name, mediaType: mediaType, sourceKind: source.kind, sourceName: Mapping.nonBlank(sourceName),
            owner: RowLayoutEntry.Owner(api: owner),
            canEdit: canEdit, screen: RowScreen(key: screen), limit: Int(limit), entries: Int(entries), inLibrary: Int(inLibrary),
            notFound: notFound.map(Int.init) ?? 0, fetching: fetching, fetchedAt: fetchedAt, nextFetchAt: nextFetchAt,
            error: Mapping.nonBlank(error), errorAt: errorAt, createdAt: createdAt,
            url: Mapping.nonBlank(sourceUrl).flatMap(URL.init(string:))
        )
    }
}

extension Components.Schemas.MediaListEntry {
    var model: MediaListEntry {
        MediaListEntry(
            rank: Int(rank), mediaType: MediaType(rawValue: mediaType), tmdbID: Mapping.positive(tmdbId), title: title,
            year: Mapping.positive(year), posterPath: Mapping.nonBlank(posterPath), found: found, item: item?.model,
            requestState: requestState.flatMap(Requestable.State.init(rawValue:))
        )
    }
}

extension Components.Schemas.MediaListEntries {
    var model: MediaListEntries {
        MediaListEntries(items: items.map(\.model), total: Int(total), inLibrary: Int(inLibrary), offset: Int(offset))
    }
}
