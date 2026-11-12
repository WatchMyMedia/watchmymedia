import Foundation

/// The account a download belongs to: the server and the person signed in
/// there when it was made. Downloads stay with it; signing it out deletes
/// them (PLAN-DOWNLOADS.md §5).
public struct OfflineOwner: Hashable, Sendable, Codable {
    public var serverID: String
    public var userID: Int
    public var baseURL: URL
    public var apiLevel: Int

    public init(serverID: String, userID: Int, baseURL: URL, apiLevel: Int) {
        self.serverID = serverID
        self.userID = userID
        self.baseURL = baseURL
        self.apiLevel = apiLevel
    }

    public var key: String { "\(serverID)/\(userID)" }
}

/// A record's state on the server. A value this app doesn't know reads as `.revoked`, as the server asks.
public enum OfflineRecordState: String, Hashable, Sendable {
    case ready, replaced, gone, revoked, restart

    public init(server value: String) {
        self = OfflineRecordState(rawValue: value) ?? .revoked
    }
}

/// A download record as the server keeps it (`OfflineDownload`).
public struct OfflineRecord: Hashable, Sendable {
    public var id: String
    public var fileID: Int
    public var state: OfflineRecordState
    public var replacedBy: Int?
    public var mode: PlaybackMode
    public var `protocol`: StreamProtocol
    /// Absolute, without a token.
    public var url: URL
    public var sizeBytes: Int64
    public var sizeEstimated: Bool
    public var quality: DownloadQuality
    public var createdAt: Date
    public var item: OfflineItem
    /// Made downloads: what the offline player needs, with URLs on the offline routes.
    public var decision: PlaybackDecision?

    public init(id: String, fileID: Int, state: OfflineRecordState, replacedBy: Int? = nil, mode: PlaybackMode, protocol: StreamProtocol, url: URL, sizeBytes: Int64, sizeEstimated: Bool, quality: DownloadQuality, createdAt: Date, item: OfflineItem, decision: PlaybackDecision? = nil) {
        self.id = id
        self.fileID = fileID
        self.state = state
        self.replacedBy = replacedBy
        self.mode = mode
        self.protocol = `protocol`
        self.url = url
        self.sizeBytes = sizeBytes
        self.sizeEstimated = sizeEstimated
        self.quality = quality
        self.createdAt = createdAt
        self.item = item
        self.decision = decision
    }
}

/// A made download: the record, and the server's reply as it came, kept on the device to read again offline.
public struct CreatedDownload: Sendable {
    public var record: OfflineRecord
    public var json: Data

    public init(record: OfflineRecord, json: Data) {
        self.record = record
        self.json = json
    }
}

/// What a device says of a download it holds, in a sync.
public enum DeviceDownloadState: String, Hashable, Sendable { case queued, downloading, downloaded, removed }

public struct SyncedDownload: Hashable, Sendable {
    public var id: String
    public var state: DeviceDownloadState
    public var sizeBytes: Int64?

    public init(id: String, state: DeviceDownloadState, sizeBytes: Int64? = nil) {
        self.id = id
        self.state = state
        self.sizeBytes = sizeBytes
    }
}

/// A position (with `durationMs`) or a mark (`watched`) made offline, with when it happened by this device's clock.
public struct OfflineProgress: Hashable, Sendable {
    public var fileID: Int
    public var downloadID: String?
    public var at: Date
    public var positionMs: Int?
    public var durationMs: Int?
    public var watched: Bool?

    public init(fileID: Int, downloadID: String?, at: Date, positionMs: Int? = nil, durationMs: Int? = nil, watched: Bool? = nil) {
        self.fileID = fileID
        self.downloadID = downloadID
        self.at = at
        self.positionMs = positionMs
        self.durationMs = durationMs
        self.watched = watched
    }
}

public enum SyncOutcome: Hashable, Sendable { case applied, newer, unavailable }

/// What became of one progress entry, with the item's watch state now (nil when it has none).
public struct SyncResult: Hashable, Sendable {
    public var outcome: SyncOutcome
    public var watch: WatchProgress?

    public init(outcome: SyncOutcome, watch: WatchProgress? = nil) {
        self.outcome = outcome
        self.watch = watch
    }
}

/// POST /api/offline/sync's reply: whether this person may download now, every download of this device, and a result per progress entry, in order.
public struct SyncReply: Sendable {
    public var offline: Bool
    public var downloads: [OfflineRecord]
    public var results: [SyncResult]

    public init(offline: Bool, downloads: [OfflineRecord], results: [SyncResult]) {
        self.offline = offline
        self.downloads = downloads
        self.results = results
    }
}

/// The offline routes' calls (PLAN-DOWNLOADS.md §9) and the sign-in they are
/// made with. WMMData implements it; WMMDownloads uses it. Calls for an
/// ``OfflineOwner`` go to its server as it, whichever server is current, and
/// throw ``SignedOutError`` once it is signed out there (a 401 signs it
/// out); the rest throw ``APIError``.
@MainActor
public protocol OfflineGateway: AnyObject {
    /// The server and account in use now; nil when signed out.
    func owner() -> OfflineOwner?

    /// Whether `owner` is still signed in on its server.
    func signedIn(_ owner: OfflineOwner) -> Bool

    /// Called whenever the sign-in in use changes: another server or account, or signed out.
    func onSessionChange(_ listener: @escaping @MainActor () -> Void)

    /// Runs `hook` before a sign-out ends the session, while the server still takes it.
    func beforeSignOut(_ hook: @escaping @MainActor @Sendable () async -> Void)

    /// Whether `owner` may download now (``offlineAPILevel`` and RequestOptions.offline).
    func allowed(_ owner: OfflineOwner) async throws -> Bool

    func plan(_ owner: OfflineOwner, fileID: Int, profile: PlaybackProfile) async throws -> DownloadPlan

    func create(_ owner: OfflineOwner, choice: DownloadChoice, profile: PlaybackProfile, client: ClientInfo) async throws -> CreatedDownload

    /// Reads a record kept from ``create(_:choice:profile:client:)`` again.
    func parse(_ owner: OfflineOwner, json: Data) throws -> OfflineRecord

    /// An `o1.` token for the download's media routes (createOfflineToken).
    func token(_ owner: OfflineOwner, downloadID: String) async throws -> MediaToken

    /// A request for `url` on `owner`'s server with its session as the
    /// bearer, which the offline routes also take; nil once it is signed out.
    func authorizedRequest(_ owner: OfflineOwner, url: URL) -> URLRequest?

    func sync(_ owner: OfflineOwner, client: ClientInfo, sentAt: Date, downloads: [SyncedDownload], progress: [OfflineProgress]) async throws -> SyncReply
}
