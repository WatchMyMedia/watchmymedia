import Foundation
@testable import WMMFeatures
import WMMModel

/// A FollowsRepository the test steers: who is signed in, what each call
/// answers, and a gate that holds a call until the test lets it go.
@MainActor
final class SpyFollows: FollowsRepository {
    var available = true
    var contextID: String? = "srv-a:1"
    var serverID: String? = "srv-a"
    var list: [FollowedShow] = []
    /// What the feed answers, by `after` ("" for none); unknown cursors answer nothing new.
    var pages: [String: NewEpisodesPage] = [:]
    /// Each call fails with this, until cleared.
    var failure: (any Error)?
    /// Calls wait here until ``release()``.
    var holding = false
    private(set) var calls: [String] = []
    private var waiting: [CheckedContinuation<Void, Never>] = []
    private var listeners: [UUID: AsyncStream<Void>.Continuation] = [:]

    func release() {
        holding = false
        let all = waiting
        waiting = []
        all.forEach { $0.resume() }
    }

    private func call(_ name: String) async throws {
        calls.append(name)
        if holding { await withCheckedContinuation { waiting.append($0) } }
        if let failure { throw failure }
    }

    func follow(showID: Int) async throws {
        try await call("follow \(showID)")
        if !list.contains(where: { $0.showID == showID }) {
            list.insert(FollowedShow(tmdbID: 70_000 + showID, title: "Show \(showID)", followedAt: .now, showID: showID), at: 0)
        }
    }

    func unfollow(showID: Int) async throws {
        try await call("unfollow \(showID)")
        list.removeAll { $0.showID == showID }
    }

    func follows() async throws -> [FollowedShow] {
        try await call("follows")
        return list
    }

    func remove(tmdbID: Int) async throws {
        try await call("remove \(tmdbID)")
        list.removeAll { $0.tmdbID == tmdbID }
    }

    func newEpisodes(after: String?) async throws -> NewEpisodesPage {
        try await call("feed \(after ?? "-")")
        return pages[after ?? ""] ?? NewEpisodesPage(cursor: after ?? "0")
    }

    func changes() -> AsyncStream<Void> {
        AsyncStream { continuation in
            let id = UUID()
            listeners[id] = continuation
            continuation.onTermination = { [weak self] _ in Task { @MainActor in self?.listeners[id] = nil } }
        }
    }

    var listenerCount: Int { listeners.count }

    /// follows.changed from another device.
    func send() { listeners.values.forEach { $0.yield() } }
}

/// A fresh UserDefaults for one test.
func scratchDefaults() -> UserDefaults {
    let name = "wmm-tests-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: name)!
    defaults.removePersistentDomain(forName: name)
    return defaults
}

func followedShow(_ showID: Int, title: String = "Harbor Lights", following: Bool? = false) -> ShowDetail {
    ShowDetail(id: showID, libraryID: 2, title: title, year: 2022, overview: "", status: nil, genres: [], rating: nil, posterPath: nil,
               backdropPath: nil, quality: nil, hdr: false, seasons: [], totalEpisodes: 0, episodesOnDisk: 0, totalSizeBytes: 0,
               nextAirDate: nil, nextUp: nil, cast: [], tmdbID: 70_000 + showID, following: following)
}

func arrivals(_ showID: Int, title: String, _ episodes: [(Int, Int)], count: Int? = nil) -> NewEpisodeGroup {
    let list = episodes.map { ArrivedEpisode(id: showID * 1000 + $0.0 * 100 + $0.1, season: $0.0, number: $0.1, arrivedAt: .now) }
    return NewEpisodeGroup(show: .init(id: showID, tmdbID: 70_000 + showID, title: title), count: count ?? list.count, episodes: list,
                           open: .init(episodeID: list[0].id, season: list[0].season, number: list[0].number))
}
