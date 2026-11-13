import Foundation
import WMMAPI
import WMMModel

/// The offline routes (PLAN-DOWNLOADS.md §9) for WMMDownloads
/// (android/core/data/.../RealOfflineGateway.kt). Each call goes to its
/// owner's server as that account, so a download keeps its tokens and sync
/// while another server is in use; once the account is signed out there,
/// calls throw ``SignedOutError``, and a 401 signs it out.
@MainActor
final class RealOfflineGateway: OfflineGateway {
    private let store: LocalStore
    private let auth: RealAuthRepository

    init(store: LocalStore, auth: RealAuthRepository) {
        self.store = store
        self.auth = auth
    }

    func owner() -> OfflineOwner? {
        guard let server = store.current?.server, let credential = store.currentCredential else { return nil }
        return OfflineOwner(serverID: server.id, userID: credential.user.id, baseURL: server.baseURL, apiLevel: server.apiLevel)
    }

    func signedIn(_ owner: OfflineOwner) -> Bool {
        store.credentials[owner.serverID]?.user.id == owner.userID
    }

    func onSessionChange(_ listener: @escaping @MainActor () -> Void) {
        store.onSessionChange { _ in listener() }
    }

    func beforeSignOut(_ hook: @escaping @MainActor @Sendable () async -> Void) {
        auth.beforeSignOut(hook)
    }

    /// The owner's server as it is kept now (its address may have moved), with its sign-in.
    private func access(_ owner: OfflineOwner) throws -> (server: Server?, credential: StoredCredential) {
        guard let credential = store.credentials[owner.serverID], credential.user.id == owner.userID else { throw SignedOutError() }
        return (store.state.servers.first { $0.server.id == owner.serverID }?.server, credential)
    }

    private func baseURL(_ owner: OfflineOwner) -> URL {
        store.state.servers.first { $0.server.id == owner.serverID }?.server.baseURL ?? owner.baseURL
    }

    private func level(_ owner: OfflineOwner) -> Int {
        store.state.servers.first { $0.server.id == owner.serverID }?.server.apiLevel ?? owner.apiLevel
    }

    /// Runs a call as `owner`; a refused token signs that account out.
    private func call<T: Sendable>(_ owner: OfflineOwner, _ operation: @Sendable (Client) async throws -> T) async throws -> T {
        let (server, credential) = try access(owner)
        return try await store.api(serverID: owner.serverID, baseURL: server?.baseURL ?? owner.baseURL, token: credential.token).call(operation)
    }

    /// Older servers, and servers that turned requests off, offer no downloads.
    func allowed(_ owner: OfflineOwner) async throws -> Bool {
        guard ServerFeatures(apiLevel: level(owner)).offline else { return false }
        do {
            return try await call(owner) { try await $0.getRequestOptions().ok.body.json }.offline == true
        } catch let error as APIError where PlaybackMapping.unknownRoute(error) || error.status == 503 {
            return false
        }
    }

    func plan(_ owner: OfflineOwner, fileID: Int, profile: PlaybackProfile) async throws -> DownloadPlan {
        // An older server refuses the HDR profile's fields (PLAN-HDR-PROFILE.md).
        let body = Components.Schemas.OfflinePlanInputBody(profile: PlaybackMapping.profile(profile.forAPILevel(level(owner))))
        let plan = try await call(owner) {
            try await $0.planOfflineDownload(path: .init(id: Int64(fileID)), body: .json(body)).ok.body.json
        }
        return DownloadPlan(
            fileID: Int(plan.fileId),
            durationMs: Int(plan.durationMs),
            options: plan.qualities.map { q in
                DownloadOption(
                    quality: DownloadQuality(id: q.quality), mode: PlaybackMapping.mode(q.mode), protocol: Self.protocolOf(q._protocol),
                    sizeBytes: max(q.sizeBytes, 0), estimated: q.sizeEstimated, audioConverted: !q.audio.copy,
                    audioIndex: q.audio.index >= 0 ? Int(q.audio.index) : nil
                )
            },
            audio: plan.audioTracks.map(PlaybackMapping.audio),
            subtitles: plan.subtitles.map(PlaybackMapping.subtitle)
        )
    }

    func create(_ owner: OfflineOwner, choice: DownloadChoice, profile: PlaybackProfile, client: ClientInfo) async throws -> CreatedDownload {
        let body = Components.Schemas.CreateOfflineInputBody(
            audioIndex: choice.audioIndex.map(Int64.init),
            burnIn: choice.burnInSubtitleID.map { _ in true },
            client: PlaybackMapping.client(client),
            profile: PlaybackMapping.profile(profile.forAPILevel(level(owner))),
            quality: Components.Schemas.CreateOfflineInputBody.QualityPayload(rawValue: choice.quality.id) ?? .original,
            subtitleId: choice.burnInSubtitleID
        )
        let reply = try await call(owner) {
            try await $0.createOfflineDownload(path: .init(id: Int64(choice.fileID)), body: .json(body)).created.body.json
        }
        return CreatedDownload(record: Self.record(reply, base: baseURL(owner)), json: try Self.encoder.encode(reply))
    }

    func parse(_ owner: OfflineOwner, json: Data) throws -> OfflineRecord {
        Self.record(try Self.decoder.decode(Components.Schemas.OfflineDownload.self, from: json), base: baseURL(owner))
    }

    func token(_ owner: OfflineOwner, downloadID: String) async throws -> MediaToken {
        let t = try await call(owner) { try await $0.createOfflineToken(path: .init(id: downloadID)).ok.body.json }
        return t.model(server: baseURL(owner))
    }

    /// Nil for an address that isn't on the owner's server as it is kept now:
    /// the sign-in goes only to the server that issued it (PLAN-APPLE-REVIEW.md §3.4.8).
    func authorizedRequest(_ owner: OfflineOwner, url: URL) -> URLRequest? {
        guard let (_, credential) = try? access(owner) else { return nil }
        return store.signer(baseURL: baseURL(owner), token: credential.token).signedRequest(url)
    }

    func sync(_ owner: OfflineOwner, client: ClientInfo, sentAt: Date, downloads: [SyncedDownload], progress: [OfflineProgress]) async throws -> SyncReply {
        let body = Components.Schemas.OfflineSyncInputBody(
            client: PlaybackMapping.client(client),
            downloads: downloads.isEmpty ? nil : downloads.map { d in
                Components.Schemas.OfflineSyncDownload(
                    deviceState: .init(rawValue: d.state.rawValue) ?? .queued, id: d.id,
                    sizeBytes: d.sizeBytes.flatMap { $0 > 0 ? $0 : nil }
                )
            },
            progress: progress.isEmpty ? nil : progress.map { p in
                Components.Schemas.OfflineSyncProgress(
                    at: p.at, downloadId: p.downloadID, durationMs: p.durationMs.flatMap { $0 > 0 ? Int64($0) : nil }, fileId: Int64(p.fileID),
                    positionMs: p.positionMs.map { Int64(max($0, 0)) }, watched: p.watched
                )
            },
            sentAt: sentAt
        )
        let reply = try await call(owner) { try await $0.syncOffline(body: .json(body)).ok.body.json }
        let base = baseURL(owner)
        return SyncReply(
            offline: reply.offline,
            downloads: reply.downloads.map { Self.record($0, base: base) },
            results: reply.progress.map { r in
                let outcome: SyncOutcome = switch r.outcome {
                case "applied": .applied
                case "newer": .newer
                default: .unavailable
                }
                return SyncResult(outcome: outcome, watch: r.watchState?.model)
            }
        )
    }

    // MARK: Mapping

    /// The server's reply as the device keeps it: plain JSON with ISO 8601 dates.
    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }()

    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    static func protocolOf(_ value: String) -> StreamProtocol { value == "file" ? .file : .hls }

    static func record(_ d: Components.Schemas.OfflineDownload, base: URL) -> OfflineRecord {
        OfflineRecord(
            id: d.id,
            fileID: Int(d.fileId),
            state: OfflineRecordState(server: d.state),
            replacedBy: d.replacedBy.flatMap { $0 > 0 ? Int($0) : nil },
            mode: PlaybackMapping.mode(d.mode),
            protocol: protocolOf(d._protocol),
            url: PlaybackMapping.absolute(d.url, base: base),
            sizeBytes: max(d.sizeBytes, 0),
            sizeEstimated: d.sizeEstimated,
            quality: DownloadQuality(id: d.quality),
            createdAt: d.createdAt,
            item: item(d.item),
            // A copy plays from the device, so its decision carries no seek thumbnails (PLAN-TRICKPLAY.md).
            decision: d.decision.flatMap { try? PlaybackMapping.decision($0, base: base, apiLevel: 0) }
        )
    }

    static func item(_ i: Components.Schemas.OfflineItem) -> OfflineItem {
        OfflineItem(
            kind: OfflineItem.Kind(rawValue: i._type) ?? .file,
            title: i.title,
            movieID: i.movieId.map(Int.init),
            showID: i.showId.map(Int.init),
            episodeID: i.episodeId.map(Int.init),
            showTitle: PlaybackMapping.nonBlank(i.showTitle),
            season: i.seasonNumber.map(Int.init),
            episode: i.episodeNumber.map(Int.init),
            year: i.year.flatMap { $0 > 0 ? Int($0) : nil },
            posterPath: PlaybackMapping.nonBlank(i.posterPath),
            artPath: PlaybackMapping.nonBlank(i.artPath)
        )
    }
}
