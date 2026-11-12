import Foundation
import WMMModel

/// A download as this device keeps it: whose it is, the server's record as it
/// came (`json`, with the decision the offline player needs), and what the
/// last sync said of it. The engine keeps the bytes and their progress; this
/// keeps the rest (android/core/downloads/.../OfflineStore.kt).
struct StoredRecord: Codable, Hashable, Sendable {
    var id: String
    var serverID: String
    var userID: Int
    var baseURL: URL
    var apiLevel: Int
    var json: Data
    var createdAt: Date
    var fileID: Int
    var mark: DownloadMark = .none
    var replacedBy: Int?
    var completedAt: Date?
    /// A new version's download replaces this one once it is done ("Download again").
    var replaces: String?
    /// The item's watch state as the server last said.
    var watchPositionMs: Int?
    var watchDurationMs: Int?
    var watchCompleted = false

    var owner: OfflineOwner { OfflineOwner(serverID: serverID, userID: userID, baseURL: baseURL, apiLevel: apiLevel) }
    var ownerKey: String { "\(serverID)/\(userID)" }

    init(id: String, owner: OfflineOwner, json: Data, createdAt: Date, fileID: Int, replaces: String? = nil) {
        self.id = id
        serverID = owner.serverID
        userID = owner.userID
        baseURL = owner.baseURL
        apiLevel = owner.apiLevel
        self.json = json
        self.createdAt = createdAt
        self.fileID = fileID
        self.replaces = replaces
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        serverID = try c.decode(String.self, forKey: .serverID)
        userID = try c.decode(Int.self, forKey: .userID)
        baseURL = try c.decode(URL.self, forKey: .baseURL)
        apiLevel = try c.decodeIfPresent(Int.self, forKey: .apiLevel) ?? 0
        json = try c.decode(Data.self, forKey: .json)
        createdAt = try c.decode(Date.self, forKey: .createdAt)
        fileID = try c.decode(Int.self, forKey: .fileID)
        mark = (try? c.decodeIfPresent(DownloadMark.self, forKey: .mark)) ?? .none
        replacedBy = try c.decodeIfPresent(Int.self, forKey: .replacedBy)
        completedAt = try c.decodeIfPresent(Date.self, forKey: .completedAt)
        replaces = try c.decodeIfPresent(String.self, forKey: .replaces)
        watchPositionMs = try c.decodeIfPresent(Int.self, forKey: .watchPositionMs)
        watchDurationMs = try c.decodeIfPresent(Int.self, forKey: .watchDurationMs)
        watchCompleted = try c.decodeIfPresent(Bool.self, forKey: .watchCompleted) ?? false
    }
}

/// Progress made on a downloaded item (PLAN-DOWNLOADS.md §6): a position, or a
/// mark, with when it happened by this device's clock. `seq` tells entries
/// apart, so a sync drops exactly the ones it sent.
struct StoredProgress: Codable, Hashable, Sendable {
    var seq: Int
    var ownerKey: String
    var fileID: Int
    var downloadID: String?
    var at: Date
    var positionMs: Int?
    var durationMs: Int?
    var watched: Bool?

    var isMark: Bool { watched != nil }
}

/// A download removed here, still to be told to the server.
struct StoredRemoval: Codable, Hashable, Sendable {
    var id: String
    var ownerKey: String
}

struct StoredDownloads: Codable, Hashable, Sendable {
    var records: [StoredRecord] = []
    var progress: [StoredProgress] = []
    var removed: [StoredRemoval] = []
    var quality = DownloadQuality.original.id
    var wifiOnly = true
    /// Whether each account may download, as last heard, so the app still offers its downloads offline.
    var allowed: [String: Bool] = [:]
    var seq = 0

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        records = try c.decodeIfPresent([StoredRecord].self, forKey: .records) ?? []
        progress = try c.decodeIfPresent([StoredProgress].self, forKey: .progress) ?? []
        removed = try c.decodeIfPresent([StoredRemoval].self, forKey: .removed) ?? []
        quality = try c.decodeIfPresent(String.self, forKey: .quality) ?? DownloadQuality.original.id
        wifiOnly = try c.decodeIfPresent(Bool.self, forKey: .wifiOnly) ?? true
        allowed = try c.decodeIfPresent([String: Bool].self, forKey: .allowed) ?? [:]
        seq = try c.decodeIfPresent(Int.self, forKey: .seq) ?? 0
    }

    func record(_ id: String) -> StoredRecord? { records.first { $0.id == id } }

    mutating func updateRecord(_ id: String, _ change: (inout StoredRecord) -> Void) {
        guard let i = records.firstIndex(where: { $0.id == id }) else { return }
        change(&records[i])
    }

    /// Adds a progress entry under the queue's rule, with the next sequence number.
    mutating func addProgress(_ make: (Int) -> StoredProgress) {
        seq += 1
        progress = progress.adding(make(seq))
    }
}

extension Array where Element == StoredProgress {
    /// The offline queue's rule (PLAN-DOWNLOADS.md §6): per file, the newest
    /// position with its time, and the mark made after it, if any. A position
    /// drops the marks before it; a mark replaces the one before it.
    func adding(_ entry: StoredProgress) -> [StoredProgress] {
        let same = { (p: StoredProgress) in p.ownerKey == entry.ownerKey && p.fileID == entry.fileID }
        let kept = entry.isMark
            ? filter { !(same($0) && $0.isMark) }
            : filter { !(same($0) && (!$0.isMark || $0.at <= entry.at)) }
        return kept + [entry]
    }
}

/// Where ``StoredDownloads`` is kept.
@MainActor
protocol OfflineStore: AnyObject {
    func load() -> StoredDownloads
    func save(_ state: StoredDownloads)
}

/// `Library/Application Support/Downloads/state.json`, left out of backups,
/// written whole after each change.
@MainActor
final class FileOfflineStore: OfflineStore {
    private let file: URL

    init(folder: URL) {
        file = folder.appending(path: "state.json")
    }

    func load() -> StoredDownloads {
        guard let data = try? Data(contentsOf: file) else { return StoredDownloads() }
        do {
            return try Self.decoder.decode(StoredDownloads.self, from: data)
        } catch {
            downloadsLog.error("could not read the offline store; starting afresh: \(error.localizedDescription, privacy: .public)")
            return StoredDownloads()
        }
    }

    func save(_ state: StoredDownloads) {
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Self.encoder.encode(state).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            var url = file
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? url.setResourceValues(values)
        } catch {
            downloadsLog.error("could not save the offline store: \(error.localizedDescription, privacy: .public)")
        }
    }

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
}

/// Keeps it in memory: tests, and a second repository on the same state.
@MainActor
final class MemoryOfflineStore: OfflineStore {
    var state: StoredDownloads

    init(_ state: StoredDownloads = StoredDownloads()) {
        self.state = state
    }

    func load() -> StoredDownloads { state }
    func save(_ state: StoredDownloads) { self.state = state }
}
