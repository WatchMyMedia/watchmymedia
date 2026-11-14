import Foundation
import WMMAPI
import WMMModel

/// Arranged rows and lists on the current server as the signed-in person
/// (PLAN-ROWS.md §6, android/core/data/.../RealRowsRepository.kt). Below
/// ``rowsAPILevel`` the library screens get no rows and nothing else is called.
@MainActor
final class RealRowsRepository: RowsRepository {
    private let store: LocalStore
    private let events: ServerEvents

    /// The server keeps at most 250 entries a list.
    private static let maxEntries = 250

    init(store: LocalStore, events: ServerEvents) {
        self.store = store
        self.events = events
    }

    var available: Bool { store.features.rows && store.currentCredential != nil }

    func libraryRows(libraryID: Int) async throws -> [ScreenRow] {
        guard available else { return [] }
        let reply = try await store.call {
            try await $0.getLibraryRows(path: .init(id: Int64(libraryID)), query: .init(arranged: true)).ok.body.json
        }
        return (reply.rows ?? []).compactMap(\.model)
    }

    func layout(_ screen: RowScreen) async throws -> RowLayout {
        let key = screen.key
        return try await store.call { try await $0.getRowLayout(path: .init(screen: key)).ok.body.json }.model(screen)
    }

    func saveLayout(_ rows: [RowPlacement], for screen: RowScreen) async throws -> RowLayout {
        let key = screen.key
        let body = Components.Schemas.UpdateRowLayoutInputBody(rows: rows.map { .init(hidden: $0.hidden ? true : nil, id: $0.id) })
        let saved = try await store.call { try await $0.updateRowLayout(path: .init(screen: key), body: .json(body)).ok.body.json }.model(screen)
        events.notify(.rows)
        return saved
    }

    func resetLayout(_ screen: RowScreen) async throws -> RowLayout {
        let key = screen.key
        let reset = try await store.call { try await $0.resetRowLayout(path: .init(screen: key)).ok.body.json }.model(screen)
        events.notify(.rows)
        return reset
    }

    func lists() async throws -> [MediaList] {
        guard available else { return [] }
        return try await store.call { try await $0.listMediaLists().ok.body.json }.items.map(\.model)
    }

    func list(id: Int) async throws -> MediaList {
        try await store.call { try await $0.getMediaList(path: .init(id: Int64(id))).ok.body.json }.model
    }

    func entries(listID: Int, offset: Int, limit: Int) async throws -> MediaListEntries {
        let query = Operations.ListMediaListEntries.Input.Query(limit: Int64(min(max(limit, 1), Self.maxEntries)), offset: Int64(max(offset, 0)))
        return try await store.call { try await $0.listMediaListEntries(path: .init(id: Int64(listID)), query: query).ok.body.json }.model
    }

    func refresh(listID: Int) async throws -> MediaList {
        let list = try await store.call { try await $0.refreshMediaList(path: .init(id: Int64(listID))).ok.body.json }.model
        events.notify(.rows)
        return list
    }

    func delete(listID: Int) async throws {
        _ = try await store.call { try await $0.deleteMediaList(path: .init(id: Int64(listID))).noContent }
        events.notify(.rows)
    }

    func changes() -> AsyncStream<Void> {
        events.changes(.rows)
    }
}
