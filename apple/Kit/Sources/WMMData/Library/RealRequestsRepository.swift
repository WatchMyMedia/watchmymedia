import Foundation
import Observation
import WMMAPI
import WMMModel

/// Requests and problem reports on the current server as the signed-in user
/// (android/core/data/.../RealRequestsRepository.kt). Below API level 5, and
/// on servers without integrations, ``options`` stays ``RequestOptions/none``
/// and nothing here is called.
@MainActor
@Observable
final class RealRequestsRepository: RequestsRepository {
    private(set) var options = RequestOptions.none

    @ObservationIgnored private let store: LocalStore
    @ObservationIgnored private let events: ServerEvents
    /// Bumped by each refresh and sign-in change, so only the newest reply applies.
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private let flight = SingleFlight<Void>()

    private static let maxSearch = 100

    init(store: LocalStore, events: ServerEvents) {
        self.store = store
        self.events = events
        store.onSessionChange { [weak self] signedIn in
            guard let self else { return }
            generation += 1
            options = .none
            if signedIn { Task { await self.refreshOptions() } }
        }
    }

    /// One request (and the admin's auth status) for every caller at once:
    /// launch, the sign-in and the foreground refresh ask together. A caller
    /// after a requests event asks again (PLAN-APPLE-REVIEW.md §4.1.3).
    func refreshOptions() async {
        await flight.run(stamp: "\(store.revision)#\(events.received[.requests] ?? 0)") { [weak self] in await self?.loadOptions() }
    }

    private func loadOptions() async {
        generation += 1
        let mine = generation
        guard let server = store.current?.server, server.features.requests, store.currentCredential != nil else {
            options = .none
            return
        }
        do {
            var loaded = try await store.call { try await $0.getRequestOptions().ok.body.json }.model
            // Deleting from the apps came with API level 6.
            if !server.features.delete { loaded.delete = false }
            // The Inbox also holds local reports when Seerr handles requests.
            // Auth status supplies both the current identity and network policy.
            if store.user?.isAdmin == true {
                if let status = try? await store.call({ try await $0.getAuthStatus().ok.body.json }) {
                    loaded.inboxAllowed = status.user?.isAdmin == true && status.adminAllowed
                }
            } else {
                loaded.inboxAllowed = false
            }
            if !loaded.inboxAllowed { loaded.inbox = 0 }
            if mine == generation { options = loaded }
        } catch let error as APIError where error.status == 404 || error.status == 503 {
            // Integrations are off on this server.
            if mine == generation { options = .none }
        } catch {
            // Unreachable for now: keep what was known.
        }
    }

    func search(_ text: String) async throws -> [Requestable] {
        let q = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxSearch))
        guard !q.isEmpty else { return [] }
        return try await store.call { try await $0.searchRequestable(query: .init(q: q)).ok.body.json }.items.compactMap(\.model)
    }

    func lookup(_ type: MediaType, tmdbID: Int) async throws -> Requestable {
        let query = Operations.GetRequestable.Input.Query(mediaType: type == .movie ? .movie : .tv, tmdbId: Int64(tmdbID))
        return try Mapping.require(await store.call { try await $0.getRequestable(query: query).ok.body.json }.model)
    }

    func ask(_ type: MediaType, tmdbID: Int, seasons: [Int]) async throws -> CreatedRequest {
        let body = Components.Schemas.CreateRequestInputBody(
            mediaType: type == .movie ? .movie : .tv, seasons: type == .tv ? seasons.map(Int64.init) : nil, tmdbId: Int64(tmdbID)
        )
        let made = try await store.call { try await $0.createRequest(body: .json(body)).created.body.json }
        changed()
        return CreatedRequest(request: try Mapping.require(made.request.model), created: made.created)
    }

    func mine() async throws -> [MediaRequest] {
        try await store.call { try await $0.listMyRequests().ok.body.json }.items.compactMap(\.model)
    }

    func cancel(requestID: Int) async throws {
        _ = try await store.call { try await $0.cancelRequest(path: .init(id: Int64(requestID))).noContent }
        changed()
    }

    func inbox() async throws -> Inbox {
        let inbox = try await store.call { try await $0.listInbox().ok.body.json }
        let seerr = inbox.seerr.map { value in
            let link = URLComponents(string: value.requestsUrl)
            let safe = link.map {
                ["http", "https"].contains($0.scheme?.lowercased() ?? "") &&
                    !($0.host ?? "").isEmpty && $0.user == nil && $0.password == nil
            } ?? false
            return SeerrInbox(requestsURL: safe ? link?.url : nil, pending: value.pending.map { max(0, Int($0)) })
        }
        return Inbox(requests: inbox.requests.compactMap(\.model), problems: inbox.problems.compactMap(\.model), seerr: seerr)
    }

    func decide(requestID: Int, approve: Bool, note: String) async throws {
        let body = Components.Schemas.DecideRequestInputBody(approve: approve, note: Mapping.nonBlank(note.trimmingCharacters(in: .whitespacesAndNewlines)))
        _ = try await store.call { try await $0.decideRequest(path: .init(id: Int64(requestID)), body: .json(body)).ok }
        changed()
    }

    @discardableResult
    func report(_ item: WatchKind, id: Int, fileID: Int?, kind: ProblemKind, note: String) async throws -> Problem {
        let body = Components.Schemas.ReportProblemInputBody(
            fileId: fileID.map(Int64.init), itemId: Int64(id), itemType: item == .movie ? .movie : .episode,
            kind: .init(rawValue: kind.rawValue) ?? .other, note: Mapping.nonBlank(note.trimmingCharacters(in: .whitespacesAndNewlines))
        )
        let report = try await store.call { try await $0.reportProblem(body: .json(body)).created.body.json }.model
        changed()
        return try Mapping.require(report)
    }

    func resolve(problemID: Int) async throws {
        _ = try await store.call { try await $0.resolveProblem(path: .init(id: Int64(problemID))).noContent }
        changed()
    }

    func searchMissing(showID: Int, season: Int, episodes: [Int]) async throws {
        let body = Components.Schemas.ShowEpisodesInputBody(episodes: episodes.isEmpty ? nil : episodes.map(Int64.init), season: Int64(season))
        _ = try await store.call { try await $0.searchMissingEpisodes(path: .init(id: Int64(showID)), body: .json(body)).accepted }
        events.notify(.library)
    }

    func monitor(showID: Int, season: Int, episodes: [Int], monitored: Bool) async throws {
        let body = Components.Schemas.MonitorEpisodesInputBody(episodes: episodes.isEmpty ? nil : episodes.map(Int64.init), monitored: monitored, season: Int64(season))
        _ = try await store.call { try await $0.monitorEpisodes(path: .init(id: Int64(showID)), body: .json(body)).noContent }
        events.notify(.library)
    }

    func previewDelete(episodeID: Int) async throws -> DeletePreview {
        let query = Operations.PreviewDelete.Input.Query(_type: .episode, id: Int64(episodeID))
        return try await store.call { try await $0.previewDelete(query: query).ok.body.json }.model
    }

    func deleteEpisode(id: Int) async throws -> DeleteOutcome { try await deleteEpisode(id: id, recordAgain: nil) }

    func deleteEpisode(id: Int, recordAgain: Bool?) async throws -> DeleteOutcome {
        // Only a server that records knows the choice; older ones never get it.
        let recordAgain = store.features.liveRecordings ? recordAgain : nil
        let done = try await store.call {
            try await $0.deleteEpisode(path: .init(id: Int64(id)), body: .json(.init(recordAgain: recordAgain))).ok.body.json
        }.model
        changed()
        return done
    }

    func replaceFile(fileID: Int) async throws -> ReplaceOutcome {
        let done = try await store.call { try await $0.replaceFile(path: .init(id: Int64(fileID))).ok.body.json }
        events.notify(.library)
        return ReplaceOutcome(connection: done.connection, blocklisted: done.blocklisted, note: Mapping.nonBlank(done.note))
    }

    func changes() -> AsyncStream<Void> {
        events.changes(.requests)
    }

    /// A change made here: the screens that list requests refetch, and so do
    /// the pages that show request state.
    private func changed() {
        events.notify(.requests)
        events.notify(.library)
    }
}
