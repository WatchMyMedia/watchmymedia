import Foundation
import WMMAPI
import WMMModel

/// The person's watchlists on the current server (API level 33). Below that
/// level nothing here is called. Each change tells this device's screens at
/// once, as the rows' own changes do, so a row or a list page follows it
/// without waiting for the server's event.
@MainActor
final class RealWatchlistsRepository: WatchlistsRepository {
    private let store: LocalStore
    private let events: ServerEvents

    init(store: LocalStore, events: ServerEvents) {
        self.store = store
        self.events = events
    }

    var available: Bool { store.features.watchlists && store.currentCredential != nil }

    var contextID: String? {
        guard let current = store.current, let user = store.user else { return nil }
        return "\(current.id):\(user.id)"
    }

    func watchlists(containing key: WatchlistKey?) async throws -> WatchlistSet {
        guard available else { return WatchlistSet(items: []) }
        let query = Operations.ListWatchlists.Input.Query(
            mediaType: key.flatMap { .init(rawValue: $0.mediaType.rawValue) }, tmdbId: key.map { Int64($0.tmdbID) }
        )
        let reply = try await store.call { try await $0.listWatchlists(query: query).ok.body.json }
        return WatchlistSet(items: reply.items.map { $0.model(asked: key != nil) }, max: Int(reply.max), maxTitles: Int(reply.maxTitles))
    }

    func create(name: String) async throws -> Watchlist {
        let body = Components.Schemas.WatchlistBody(name: name)
        let list = try await store.call { try await $0.createWatchlist(body: .json(body)).created.body.json }
        events.notify(.rows)
        return list.model(asked: false)
    }

    func update(id: Int, name: String?, removeWatched: Bool?) async throws -> Watchlist {
        let body = Components.Schemas.UpdateWatchlistInputBody(name: name, removeWatched: removeWatched)
        let list = try await store.call { try await $0.updateWatchlist(path: .init(id: Int64(id)), body: .json(body)).ok.body.json }
        events.notify(.rows)
        return list.model(asked: false)
    }

    func delete(id: Int) async throws {
        _ = try await store.call { try await $0.deleteWatchlist(path: .init(id: Int64(id))).noContent }
        events.notify(.rows)
    }

    func save(_ title: WatchlistTitle, to id: Int) async throws -> WatchlistChange {
        let path = Operations.SaveToWatchlist.Input.Path(id: Int64(id), mediaType: .init(title.key), tmdbId: Int64(title.key.tmdbID))
        let change = try await store.call { try await $0.saveToWatchlist(path: path, body: .json(title.details)).ok.body.json }
        events.notify(.rows)
        return change.model
    }

    func remove(_ key: WatchlistKey, from id: Int) async throws -> WatchlistChange {
        let path = Operations.RemoveFromWatchlist.Input.Path(id: Int64(id), mediaType: .init(key), tmdbId: Int64(key.tmdbID))
        let change = try await store.call { try await $0.removeFromWatchlist(path: path).ok.body.json }
        events.notify(.rows)
        return change.model
    }

    func saveToOnly(_ title: WatchlistTitle) async throws -> WatchlistChange {
        let path = Operations.SaveToOnlyWatchlist.Input.Path(mediaType: .init(title.key), tmdbId: Int64(title.key.tmdbID))
        let change = try await store.call { try await $0.saveToOnlyWatchlist(path: path, body: .json(title.details)).ok.body.json }
        events.notify(.rows)
        return change.model
    }

    func removeFromAll(_ key: WatchlistKey) async throws -> WatchlistChange {
        let path = Operations.RemoveFromWatchlists.Input.Path(mediaType: .init(key), tmdbId: Int64(key.tmdbID))
        let change = try await store.call { try await $0.removeFromWatchlists(path: path).ok.body.json }
        events.notify(.rows)
        return change.model
    }

    func changes() -> AsyncStream<Void> {
        events.changes(.rows)
    }
}

/// The four title operations' path types, each its own enum of the same two cases.
private protocol WatchlistMediaTypePayload: RawRepresentable<String> {}

extension WatchlistMediaTypePayload {
    init(_ key: WatchlistKey) {
        // Both sides are "movie" and "tv".
        self.init(rawValue: key.mediaType.rawValue)!
    }
}

extension Operations.SaveToWatchlist.Input.Path.MediaTypePayload: WatchlistMediaTypePayload {}
extension Operations.RemoveFromWatchlist.Input.Path.MediaTypePayload: WatchlistMediaTypePayload {}
extension Operations.SaveToOnlyWatchlist.Input.Path.MediaTypePayload: WatchlistMediaTypePayload {}
extension Operations.RemoveFromWatchlists.Input.Path.MediaTypePayload: WatchlistMediaTypePayload {}

extension WatchlistTitle {
    /// What the server keeps for a title it has no record of; it prefers its own.
    fileprivate var details: Components.Schemas.WatchlistTitleDetails {
        .init(posterPath: posterPath, title: title, year: year.map(Int64.init))
    }
}

extension Components.Schemas.Watchlist {
    /// `asked`: the list was read for a title, so `contains` means something.
    func model(asked: Bool) -> Watchlist {
        Watchlist(id: Int(id), name: name, titles: Int(titles), inLibrary: Int(inLibrary), removeWatched: removeWatched,
                  posters: posters.compactMap { Mapping.nonBlank($0) }, contains: asked ? (contains ?? false) : nil, createdAt: createdAt)
    }
}

extension Components.Schemas.WatchlistTitleChange {
    var model: WatchlistChange {
        WatchlistChange(holding: watchlists.map(Int.init), watchlist: watchlist?.model(asked: false), created: created ?? false,
                        removed: (removed ?? []).map(Int.init))
    }
}
