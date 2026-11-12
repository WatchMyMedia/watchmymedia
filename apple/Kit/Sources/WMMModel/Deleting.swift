import Foundation

/// The API level from which the apps offer Delete episode
/// (PLAN-EPISODE-PAGE.md §4): `RequestOptions.delete`, and members may
/// delete episodes and movies when the server allows it.
public let deleteAPILevel = 6

/// What deleting an episode would do, as the server's `previewDelete` says
/// (PLAN-ARR.md §8): the Radarrs or Sonarrs that hold it, asked just now.
public struct DeletePreview: Hashable, Sendable {
    /// Some Radarr or Sonarr manages the item. When false there is no
    /// delete: it is deleted where it was downloaded, or on disk.
    public var recording: Bool
    public var managed: Bool
    /// The files the apps list, and their size in bytes.
    public var files: Int
    public var sizeBytes: Int64
    public var apps: [DeleteApp]

    public init(managed: Bool, files: Int = 0, sizeBytes: Int64 = 0, apps: [DeleteApp] = [], recording: Bool = false) {
        self.recording = recording
        self.managed = managed
        self.files = files
        self.sizeBytes = sizeBytes
        self.apps = apps
    }

    /// The apps that answered: only they can delete.
    public var reachable: [DeleteApp] { apps.filter { $0.error == nil } }
}

/// A Radarr or Sonarr that holds the item.
public struct DeleteApp: Hashable, Sendable {
    public enum Kind: String, Hashable, Sendable { case radarr, sonarr }

    /// The connection's name: "Sonarr 4K".
    public var connection: String
    public var kind: Kind
    /// Where the app moves what it deletes; nil when it deletes for good.
    public var recycleBin: String?
    /// Why the app could not be asked; it can't delete until it answers.
    public var error: String?
    /// An episode's file also holds these episodes of the season, which go too.
    public var otherEpisodes: [Int]

    public init(connection: String, kind: Kind, recycleBin: String? = nil, error: String? = nil, otherEpisodes: [Int] = []) {
        self.connection = connection
        self.kind = kind
        self.recycleBin = recycleBin
        self.error = error
        self.otherEpisodes = otherEpisodes
    }
}

/// What a delete did: each app's failure, if any. The others went ahead.
public struct DeleteOutcome: Hashable, Sendable {
    /// "Sonarr couldn’t delete: …", one per app that failed.
    public var failures: [String]
    /// Some app deleted the file.
    public var deleted: Bool

    public init(failures: [String] = [], deleted: Bool = true) {
        self.failures = failures
        self.deleted = deleted
    }
}

/// What Replace file did.
public struct ReplaceOutcome: Hashable, Sendable {
    /// The connection that manages the file: "Sonarr".
    public var connection: String
    /// The release that brought the file in was blocklisted.
    public var blocklisted: Bool
    /// Why it could not be blocklisted; the file was replaced all the same.
    public var note: String?

    public init(connection: String, blocklisted: Bool, note: String? = nil) {
        self.connection = connection
        self.blocklisted = blocklisted
        self.note = note
    }
}

extension RequestsRepository {
    /// Repositories that never delete recordings have nothing to send.
    public func deleteEpisode(id: Int, recordAgain: Bool?) async throws -> DeleteOutcome { try await deleteEpisode(id: id) }
}
