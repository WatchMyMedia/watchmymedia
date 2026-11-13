import Foundation

/// The Top Shelf's folder in the App Group: `TopShelf/snapshot.json` and the
/// pictures beside it in `TopShelf/Pictures/`. The app writes and clears it;
/// the extension reads it. No modification dates are read, so neither side
/// uses an API the privacy manifest would have to declare.
public struct TopShelfStore: Sendable {
    public let folder: URL

    public init(folder: URL) {
        self.folder = folder
    }

    /// The store in the App Group's container; nil when the build has no
    /// App Group (an unsigned build on a device, where nothing runs anyway).
    public static func appGroup(_ group: String = TopShelfRules.appGroup) -> TopShelfStore? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)
            .map { TopShelfStore(folder: $0.appendingPathComponent("TopShelf", isDirectory: true)) }
    }

    var snapshotURL: URL { folder.appendingPathComponent("snapshot.json") }
    var picturesFolder: URL { folder.appendingPathComponent("Pictures", isDirectory: true) }

    // MARK: Reading (the extension, and the app before it writes)

    /// The snapshot as the extension shows it: nil when there is none, when it
    /// can't be read, is of another format, is older than
    /// ``TopShelfRules/maxAge`` or holds nothing to show. Pictures whose file
    /// has gone are dropped from their cards.
    public func current(now: Date = Date()) -> TopShelfSnapshot? {
        guard let snapshot = stored(), TopShelfRules.isFresh(writtenAt: snapshot.writtenAt, now: now) else { return nil }
        var shown = snapshot.validated()
        for s in shown.sections.indices {
            for i in shown.sections[s].items.indices where shown.sections[s].items[i].picture.map({ pictureURL($0) == nil }) == true {
                shown.sections[s].items[i].picture = nil
            }
        }
        return shown.isEmpty ? nil : shown
    }

    /// The snapshot on disk in this format, however old.
    public func stored() -> TopShelfSnapshot? {
        guard let data = try? Data(contentsOf: snapshotURL),
              let snapshot = try? Self.decoder.decode(TopShelfSnapshot.self, from: data),
              snapshot.format == TopShelfSnapshot.format else { return nil }
        return snapshot
    }

    /// The owner key of whatever snapshot is on disk, in any format.
    public func storedOwner() -> String? {
        guard let data = try? Data(contentsOf: snapshotURL) else { return nil }
        struct Owner: Decodable { var owner: String? }
        return (try? JSONDecoder().decode(Owner.self, from: data))?.owner ?? ""
    }

    /// A picture's file, when it is there.
    public func pictureURL(_ name: String) -> URL? {
        guard TopShelfRules.isPictureName(name) else { return nil }
        let url = picturesFolder.appendingPathComponent(name)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    // MARK: Writing (the app)

    /// Saves a picture's bytes under `name`.
    public func savePicture(_ data: Data, as name: String) throws {
        guard TopShelfRules.isPictureName(name) else { throw CocoaError(.fileWriteInvalidFileName) }
        try FileManager.default.createDirectory(at: picturesFolder, withIntermediateDirectories: true)
        try data.write(to: picturesFolder.appendingPathComponent(name), options: .atomic)
    }

    /// Writes the snapshot (validated first), then removes the pictures it no longer names.
    public func write(_ snapshot: TopShelfSnapshot) throws {
        let snapshot = snapshot.validated()
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try Self.encoder.encode(snapshot).write(to: snapshotURL, options: .atomic)
        let keep = snapshot.pictures
        for name in pictureNames() where !keep.contains(name) {
            try? FileManager.default.removeItem(at: picturesFolder.appendingPathComponent(name))
        }
    }

    /// Removes the snapshot and every picture. True when there was something to remove.
    @discardableResult
    public func clear() -> Bool {
        guard FileManager.default.fileExists(atPath: folder.path) else { return false }
        try? FileManager.default.removeItem(at: folder)
        return true
    }

    /// The picture files on disk.
    public func pictureNames() -> [String] {
        (try? FileManager.default.contentsOfDirectory(atPath: picturesFolder.path)) ?? []
    }

    static var encoder: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return encoder
    }

    static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }
}
