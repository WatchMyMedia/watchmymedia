import Foundation
import WMMAPI
import WMMModel

/// Following shows and the feed of their new episodes, on the current server
/// as the signed-in person (PLAN-NEW-EPISODES.md §5.3, §5.4; API level 29).
/// Below that level nothing here is called.
@MainActor
final class RealFollowsRepository: FollowsRepository {
    private let store: LocalStore
    private let events: ServerEvents

    init(store: LocalStore, events: ServerEvents) {
        self.store = store
        self.events = events
    }

    var available: Bool { store.features.follows && store.currentCredential != nil }

    var contextID: String? {
        guard let current = store.current, let user = store.user else { return nil }
        return "\(current.id):\(user.id)"
    }

    var serverID: String? { store.user == nil ? nil : store.current?.id }

    func follow(showID: Int) async throws {
        _ = try await store.call { try await $0.followShow(path: .init(id: Int64(showID))).noContent }
    }

    func unfollow(showID: Int) async throws {
        _ = try await store.call { try await $0.unfollowShow(path: .init(id: Int64(showID))).noContent }
    }

    func follows() async throws -> [FollowedShow] {
        guard available else { return [] }
        return try await store.call { try await $0.listFollows().ok.body.json }.follows.map(\.model)
    }

    func remove(tmdbID: Int) async throws {
        _ = try await store.call { try await $0.removeFollow(path: .init(tmdbId: Int64(tmdbID))).noContent }
    }

    func newEpisodes(after: String?) async throws -> NewEpisodesPage {
        let reply = try await store.call { try await $0.listNewEpisodes(query: .init(after: after)).ok.body.json }
        return NewEpisodesPage(cursor: reply.cursor, shows: reply.shows.map(\.model))
    }

    func changes() -> AsyncStream<Void> {
        events.changes(.follows)
    }
}

extension Components.Schemas.Follow {
    var model: FollowedShow {
        FollowedShow(
            tmdbID: Int(tmdbId), title: title, followedAt: followedAt,
            showID: show.map { Int($0.id) }, posterPath: show.flatMap { Mapping.nonBlank($0.posterPath) },
            latest: latest.map { .init(season: Int($0.season), number: Int($0.number), title: Mapping.nonBlank($0.title), arrivedAt: $0.arrivedAt) }
        )
    }
}

extension Components.Schemas.NewEpisodeGroup {
    var model: NewEpisodeGroup {
        NewEpisodeGroup(
            show: .init(id: Int(show.id), tmdbID: Int(show.tmdbId), title: show.title, posterPath: Mapping.nonBlank(show.posterPath)),
            count: Int(count),
            episodes: episodes.map {
                ArrivedEpisode(id: Int($0.id), season: Int($0.season), number: Int($0.number), title: Mapping.nonBlank($0.title),
                               stillPath: Mapping.nonBlank($0.stillPath), arrivedAt: $0.arrivedAt)
            },
            open: .init(episodeID: Int(open.episodeId), season: Int(open.season), number: Int(open.number))
        )
    }
}
