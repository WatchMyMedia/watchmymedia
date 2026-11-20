import Foundation
import WMMModel

/// Following shows on the fakes (every title is
/// fictional): Sam follows Harbor Lights, Northfield Precinct and Orbital
/// Decay, and The Tidewater Array, which has left the library. The feed gives
/// a first check its cursor, the next one what arrived (Harbor Lights' three
/// episodes across two seasons, one of Northfield Precinct's; with
/// `manyShows`, seven shows, so the notifications say "2 more shows"), and
/// nothing after that. Only at ``followsAPILevel`` (`-fakesFollows`).
@MainActor
public final class FakeFollowsRepository: FollowsRepository {
    /// What the feed was asked, in order: nil for a first check.
    public private(set) var asked: [String?] = []
    /// The next follow, stop or remove fails, as an unanswered server would.
    public var failNext = false
    /// The feed has seven shows' arrivals, past the five a check notifies one by one.
    public var manyShows = false

    private let servers: FakeServerRepository?
    private let now: Date
    private var items: [FollowedShow]
    private var listeners: [UUID: AsyncStream<Void>.Continuation] = [:]

    public init(servers: FakeServerRepository? = nil, seeded: Bool = true, now: Date = .now) {
        self.servers = servers
        self.now = now
        items = seeded ? Self.seed(now: now) : []
    }

    static func seed(now: Date) -> [FollowedShow] {
        let day: TimeInterval = 86_400
        func show(_ id: Int, followed: Double, latest: FollowedShow.Latest?) -> FollowedShow {
            let s = FakeData.shows.first { $0.id == id }!
            return FollowedShow(tmdbID: FakeData.tmdbID(showID: id), title: s.title, followedAt: now - followed * day, showID: id, latest: latest)
        }
        return [
            show(100, followed: 3, latest: .init(season: 3, number: 2, title: "The Pilot Boat", arrivedAt: now - day)),
            show(103, followed: 10, latest: .init(season: 1, number: 5, title: "Squall Line", arrivedAt: now - 2 * day)),
            // Its latest arrived before Sam followed it: no new episode yet.
            show(104, followed: 12, latest: .init(season: 1, number: 2, arrivedAt: now - 20 * day)),
            FollowedShow(tmdbID: 71_010, title: "The Tidewater Array", followedAt: now - 40 * day),
        ]
    }

    /// The shows followed, by the show's own id, for the fakes' Home row.
    public var followedShowIDs: Set<Int> { Set(items.compactMap(\.showID)) }

    public var available: Bool {
        guard let servers else { return true }
        return ServerFeatures(apiLevel: servers.current?.server.apiLevel).follows && servers.currentUser != nil
    }

    public var contextID: String? {
        guard let servers else { return "fake:1" }
        guard let current = servers.current, let user = servers.currentUser else { return nil }
        return "\(current.id):\(user.id)"
    }

    public var serverID: String? {
        guard let servers else { return FakeServers.livingRoom.id }
        return servers.currentUser == nil ? nil : servers.current?.id
    }

    public func following(showID: Int) -> Bool { items.contains { $0.showID == showID } }

    public func follow(showID: Int) async throws {
        await FakeLatency.wait()
        try refuseIfAsked()
        guard !items.contains(where: { $0.showID == showID }) else { return }
        guard let s = FakeData.shows.first(where: { $0.id == showID }) else { throw APIError(status: 404, message: "No such show.") }
        items.insert(FollowedShow(tmdbID: FakeData.tmdbID(showID: showID), title: s.title, followedAt: .now, showID: showID), at: 0)
        changed()
    }

    public func unfollow(showID: Int) async throws {
        await FakeLatency.wait()
        try refuseIfAsked()
        items.removeAll { $0.showID == showID }
        changed()
    }

    public func follows() async throws -> [FollowedShow] {
        await FakeLatency.wait()
        guard available else { return [] }
        return items
    }

    public func remove(tmdbID: Int) async throws {
        await FakeLatency.wait()
        try refuseIfAsked()
        items.removeAll { $0.tmdbID == tmdbID }
        changed()
    }

    public func newEpisodes(after: String?) async throws -> NewEpisodesPage {
        await FakeLatency.wait()
        asked.append(after)
        switch after {
        case nil: return NewEpisodesPage(cursor: "1840")
        case "1840": return NewEpisodesPage(cursor: "1846", shows: Array(groups().prefix(manyShows ? 7 : 2)))
        default: return NewEpisodesPage(cursor: after ?? "1846")
        }
    }

    public func changes() -> AsyncStream<Void> {
        AsyncStream { continuation in
            let id = UUID()
            listeners[id] = continuation
            continuation.onTermination = { [weak self] _ in Task { @MainActor in self?.listeners[id] = nil } }
        }
    }

    /// Tells listeners the follows changed, as follows.changed from another device does.
    public func changed() {
        for listener in listeners.values { listener.yield() }
    }

    private func refuseIfAsked() throws {
        guard failNext else { return }
        failNext = false
        throw APIError(status: nil, message: "The server can’t be reached.")
    }

    /// What arrived: Harbor Lights' S2 E8 to S3 E2, then one episode each of other shows.
    private func groups() -> [NewEpisodeGroup] {
        func show(_ id: Int) -> NewEpisodeGroup.Show {
            let s = FakeData.shows.first { $0.id == id }!
            return .init(id: id, tmdbID: FakeData.tmdbID(showID: id), title: s.title)
        }
        func one(_ id: Int, season: Int, number: Int, title: String) -> NewEpisodeGroup {
            let episodeID = id * 1000 + season * 10 + number
            return NewEpisodeGroup(show: show(id), count: 1,
                                   episodes: [ArrivedEpisode(id: episodeID, season: season, number: number, title: title, arrivedAt: now)],
                                   open: .init(episodeID: episodeID, season: season, number: number))
        }
        let harbor = [(2, 8, "Lantern Rock"), (3, 1, "Low Water"), (3, 2, "The Ferryman")].map { season, number, title in
            ArrivedEpisode(id: 100_000 + season * 10 + number, season: season, number: number, title: title, arrivedAt: now)
        }
        return [
            NewEpisodeGroup(show: show(100), count: 3, episodes: harbor, open: .init(episodeID: harbor[0].id, season: 2, number: 8)),
            one(103, season: 1, number: 6, title: "Markers"),
            one(104, season: 1, number: 3, title: "Undertow"),
            one(106, season: 2, number: 7, title: "The Long Dark"),
            one(102, season: 1, number: 9, title: "High Water"),
            one(101, season: 2, number: 5, title: "Squall Line"),
            one(105, season: 4, number: 2, title: "The Ferryman"),
        ]
    }
}
