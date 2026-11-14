import Foundation
@testable import WMMDownloads
import WMMModel

let server = URL(string: "http://127.0.0.1:27599")!
let sam = OfflineOwner(serverID: "srv", userID: 1, baseURL: server, apiLevel: offlineAPILevel)

/// A record as the server would make it for `fileID`.
func record(_ id: String, fileID: Int, protocol proto: StreamProtocol = .file, state: OfflineRecordState = .ready, replacedBy: Int? = nil, next: Int? = nil) -> OfflineRecord {
    let decision = PlaybackDecision(
        mode: proto == .file ? .direct : .transcode, protocol: proto, vod: true,
        url: server.appending(path: "api/offline/\(id)/\(proto == .file ? "stream" : "hls/master.m3u8")"),
        mimeType: proto == .file ? "video/mp4" : "application/vnd.apple.mpegurl", playID: "", durationMs: 3_000_000,
        audio: [AudioOption(index: 1, label: "English", language: "en", isDefault: true), AudioOption(index: 2, label: "Commentary", language: "en", isDefault: false)],
        selectedAudio: 2,
        subtitles: [
            DecisionSubtitle(option: SubtitleOption(id: "s3", label: "English", language: "en"), format: "webvtt", url: server.appending(path: "api/offline/\(id)/subtitles/s3.vtt")),
            DecisionSubtitle(option: SubtitleOption(id: "s4", label: "English · PGS", language: "en", burnInOnly: true), format: "image", url: nil),
        ],
        watchedAtMs: 2_800_000,
        upNextMs: 2_900_000,
        next: next.map { UpNext(fileID: $0, episodeID: $0, showID: 100, showTitle: "Harbor Lights", season: 3, episode: 8, title: "Next") }
    )
    return OfflineRecord(
        id: id, fileID: fileID, state: state, replacedBy: replacedBy, mode: decision.mode, protocol: proto, url: decision.url,
        sizeBytes: 4_000_000_000, sizeEstimated: proto == .hls, quality: .original, createdAt: .distantPast,
        item: OfflineItem(kind: .episode, title: "Keel", showID: 100, showTitle: "Harbor Lights", season: 3, episode: 5), decision: decision
    )
}

@MainActor
final class FakeGateway: OfflineGateway {
    var current: OfflineOwner? = sam
    var signedInKeys: Set<String> = [sam.key]
    var mayDownload = true
    var made: [OfflineRecord] = []
    var records: [String: OfflineRecord] = [:]
    var syncs: [(downloads: [SyncedDownload], progress: [OfflineProgress])] = []
    /// What the next syncs answer; by default every record held, ready.
    var reply: ((_ downloads: [SyncedDownload]) -> SyncReply)?
    var syncError: Error?
    var tokensMade = 0
    var planSize: Int64 = 4_000_000_000
    private(set) var planCalls: [Int] = []
    private(set) var createCalls: [Int] = []
    var planHook: (@MainActor () async -> Void)?
    var createHook: (@MainActor () async -> Void)?
    var createError: Error?
    private var sessionListeners: [@MainActor () -> Void] = []
    private var signOutHooks: [@MainActor @Sendable () async -> Void] = []

    func owner() -> OfflineOwner? { current }
    func signedIn(_ owner: OfflineOwner) -> Bool { signedInKeys.contains(owner.key) }
    func onSessionChange(_ listener: @escaping @MainActor () -> Void) { sessionListeners.append(listener) }
    func beforeSignOut(_ hook: @escaping @MainActor @Sendable () async -> Void) { signOutHooks.append(hook) }

    func select(_ owner: OfflineOwner) {
        current = owner
        signedInKeys.insert(owner.key)
        for listener in sessionListeners { listener() }
    }

    /// Signs the current account out, as the app does: the hooks first, then the session goes.
    func signOut() async {
        for hook in signOutHooks { await hook() }
        signedInKeys.remove(sam.key)
        current = nil
        for listener in sessionListeners { listener() }
    }

    func allowed(_ owner: OfflineOwner) async throws -> Bool { mayDownload }

    func plan(_ owner: OfflineOwner, fileID: Int, profile: PlaybackProfile) async throws -> DownloadPlan {
        planCalls.append(fileID)
        if let planHook { await planHook() }
        return DownloadPlan(fileID: fileID, durationMs: 3_000_000, options: [
            DownloadOption(quality: .original, mode: .direct, protocol: .file, sizeBytes: planSize, estimated: false),
            DownloadOption(quality: .p720, mode: .transcode, protocol: .hls, sizeBytes: 1_500_000_000, estimated: true),
        ])
    }

    func create(_ owner: OfflineOwner, choice: DownloadChoice, profile: PlaybackProfile, client: ClientInfo) async throws -> CreatedDownload {
        createCalls.append(choice.fileID)
        guard let next = made.first(where: { $0.fileID == choice.fileID }) else { throw APIError(status: 404, message: "Not found.") }
        records[next.id] = next
        if let createHook { await createHook() }
        if let createError { throw createError }
        return CreatedDownload(record: next, json: Data(next.id.utf8))
    }

    func parse(_ owner: OfflineOwner, json: Data) throws -> OfflineRecord {
        guard let r = records[String(decoding: json, as: UTF8.self)] else { throw APIError(status: nil, message: "unknown") }
        return r
    }

    func token(_ owner: OfflineOwner, downloadID: String) async throws -> MediaToken {
        tokensMade += 1
        return MediaToken(token: "o1.test\(tokensMade)", expiresAt: Date().addingTimeInterval(6 * 3600))
    }

    func authorizedRequest(_ owner: OfflineOwner, url: URL) -> URLRequest? {
        signedIn(owner) ? URLRequest(url: url) : nil
    }

    func sync(_ owner: OfflineOwner, client: ClientInfo, sentAt: Date, downloads: [SyncedDownload], progress: [OfflineProgress]) async throws -> SyncReply {
        syncs.append((downloads, progress))
        if let syncError {
            // A 401 signs the account out, as the real gateway does.
            if syncError is SignedOutError { signedInKeys.remove(owner.key) }
            throw syncError
        }
        if let reply { return reply(downloads) }
        let held = downloads.filter { $0.state != .removed }.compactMap { records[$0.id] }
        return SyncReply(offline: mayDownload, downloads: held, results: progress.map { _ in SyncResult(outcome: .applied) })
    }
}

@MainActor
final class FakeEngine: DownloadEngine {
    weak var delegate: (any EngineDelegate)?
    var added: [EngineRequest] = []
    var states: [String: EngineDownload] = [:]
    var removed: [String] = []
    var wifiOnly = true
    var reason: DownloadStatus.Reason?
    var freeBytes: Int64 = 50_000_000_000
    /// How often the repository asked for the downloads' states.
    private(set) var reads = 0

    func add(_ request: EngineRequest) {
        added.append(request)
        states[request.id] = EngineDownload(id: request.id, state: .queued, fraction: 0, bytes: 0, location: nil, parts: [:])
    }

    func remove(_ id: String) {
        removed.append(id)
        states[id] = nil
    }

    func setPaused(_ id: String, _ paused: Bool) {
        states[id]?.state = paused ? .paused : .queued
        delegate?.engineChanged(id, failure: nil)
    }

    func downloads() -> [String: EngineDownload] { reads += 1; return states }
    func setWifiOnly(_ wifiOnly: Bool) { self.wifiOnly = wifiOnly }
    func usedBytes() -> Int64 { states.values.reduce(0) { $0 + $1.bytes } }
    func space() -> (free: Int64, total: Int64) { (freeBytes, 256_000_000_000) }
    func notMet() -> DownloadStatus.Reason? { reason }

    /// Finishes a download as the system would, with its subtitles.
    func finish(_ id: String, bytes: Int64 = 3_900_000_000) {
        let request = added.last { $0.id == id }
        let media = URL(fileURLWithPath: "/tmp/\(id)/media.mp4")
        let parts = (request?.parts ?? [:]).mapValues { URL(fileURLWithPath: "/tmp/\(id)/sub-\($0.lastPathComponent)") }
        states[id] = EngineDownload(id: id, state: .completed, fraction: 1, bytes: bytes, location: media, parts: parts)
        delegate?.engineChanged(id, failure: nil)
    }

    func move(_ id: String, _ state: EngineDownload.State, fraction: Double = 0) {
        states[id]?.state = state
        states[id]?.fraction = fraction
        delegate?.engineChanged(id, failure: nil)
    }
}

@MainActor
final class FakeNotices: DownloadNotices {
    var said: [String] = []
    func firstDownload() { said.append("ask") }
    func downloaded(_ title: String) { said.append("downloaded \(title)") }
    func failed(_ title: String) { said.append("failed \(title)") }
    func removedByAdmin(_ title: String) { said.append("removed \(title)") }
}

let client = ClientInfo(deviceID: "dev1", name: "Sam’s iPhone", product: "WatchMyMedia for iOS", platform: "ios", version: "1.0")
let profile = PlaybackProfile(name: "test", containers: ["mp4"], videoCodecs: ["h264"], audioCodecs: ["aac"])

@MainActor
struct Harness {
    let store = MemoryOfflineStore()
    let gateway = FakeGateway()
    let engine = FakeEngine()
    let notices = FakeNotices()
    var clock = Date(timeIntervalSince1970: 1_800_000_000)

    func make() -> RealDownloadRepository {
        let clock = clock
        return RealDownloadRepository(
            store: store, gateway: gateway, engine: engine, client: { client }, profile: { profile }, notices: notices, now: { clock }
        )
    }
}

/// Lets the repository's background work (its allowed check, a scheduled sync) run.
@MainActor
func settle() async {
    for _ in 0..<20 { await Task.yield() }
}
