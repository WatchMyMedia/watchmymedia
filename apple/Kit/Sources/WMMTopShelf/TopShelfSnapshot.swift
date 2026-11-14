import Foundation

// The Apple TV's Top Shelf (PLAN-APPLE-REVIEW.md §2.2, §6.2; TvTopShelfSections.dc.html):
// the app writes this snapshot into the App Group it shares with its Top Shelf
// extension, and the extension only reads it. This target is Foundation alone,
// so the extension links nothing else of the Kit: no sign-in, no network, no
// server address. What the snapshot may hold is checked here, on both sides:
// titles, the app's own `watchmymedia:` links and the names of picture files
// in the group. Never a server address, an image's web address or a token.

/// What the Top Shelf shows: Continue watching and Recently added, as two
/// sections the system draws.
public struct TopShelfSnapshot: Codable, Hashable, Sendable {
    /// The snapshot's format. The extension ignores any other, so an app
    /// update that changes the format never shows a half-read shelf.
    public static let format = 1

    public var format: Int
    /// When the app wrote it, by the app's clock (``TopShelfRules/maxAge``).
    public var writtenAt: Date
    /// Whose it is: ``TopShelfOwner/key``, a hash of the server's ID and the
    /// person's, so a sign-out or a server switch can tell a snapshot isn't
    /// theirs without the snapshot naming either.
    public var owner: String
    public var sections: [TopShelfSection]

    public init(writtenAt: Date, owner: String, sections: [TopShelfSection]) {
        format = Self.format
        self.writtenAt = writtenAt
        self.owner = owner
        self.sections = sections
    }

    /// Nothing to show: the system's static image instead.
    public var isEmpty: Bool { sections.allSatisfy(\.items.isEmpty) }

    /// The picture files the snapshot names.
    public var pictures: Set<String> { Set(sections.flatMap { $0.items.compactMap(\.picture) }) }

    /// The snapshot with only what may be in it (``TopShelfRules``): items
    /// whose links aren't the app's own, or whose picture isn't a bare file
    /// name, are dropped; progress is kept within 0…1; each section keeps at
    /// most its share; empty sections go.
    public func validated() -> TopShelfSnapshot {
        var copy = self
        copy.sections = sections.compactMap { section in
            var section = section
            section.items = Array(section.items.compactMap { $0.validated() }.prefix(TopShelfRules.limit(section.kind)))
            return section.items.isEmpty ? nil : section
        }
        return copy
    }
}

public struct TopShelfSection: Codable, Hashable, Sendable {
    public enum Kind: String, Codable, Hashable, Sendable {
        /// 16:9 cards with the system's progress bar.
        case continueWatching
        /// Posters.
        case recentlyAdded
    }

    public var kind: Kind
    /// "Continue watching", "Recently added".
    public var title: String
    public var items: [TopShelfItem]

    public init(kind: Kind, items: [TopShelfItem]) {
        self.kind = kind
        title = kind.title
        self.items = items
    }
}

extension TopShelfSection.Kind {
    /// The section's heading (TvTopShelfSections.dc.html).
    public var title: String {
        switch self {
        case .continueWatching: "Continue watching"
        case .recentlyAdded: "Recently added"
        }
    }
}

/// One card. The system draws it, its focus, its title under it when focused
/// and, for Continue watching, the progress bar.
public struct TopShelfItem: Codable, Hashable, Sendable {
    public enum Shape: String, Codable, Hashable, Sendable {
        /// 16:9.
        case wide
        /// 2:3.
        case poster
    }

    /// Stable across snapshots: `continue-<fileID>`, `movie-<id>`, `show-<id>`.
    public var id: String
    /// "The Quiet Meridian · S2, E4", "Signal to Noise".
    public var title: String
    public var shape: Shape
    /// 0…1 for Continue watching; nil for none.
    public var progress: Double?
    /// The picture's file name in the snapshot's folder; nil for none.
    public var picture: String?
    /// Select: the page, as a link the app published for its server.
    public var open: URL
    /// Play/Pause: plays (or, with nothing single to play, the page), published likewise.
    public var play: URL

    public init(id: String, title: String, shape: Shape, progress: Double? = nil, picture: String? = nil, open: URL, play: URL) {
        self.id = id
        self.title = title
        self.shape = shape
        self.progress = progress
        self.picture = picture
        self.open = open
        self.play = play
    }

    func validated() -> TopShelfItem? {
        guard TopShelfRules.isAppLink(open), TopShelfRules.isAppLink(play), !title.isEmpty else { return nil }
        var item = self
        if let picture, !TopShelfRules.isPictureName(picture) { item.picture = nil }
        item.progress = progress.map { min(max($0, 0), 1) }
        return item
    }
}

/// What the snapshot may hold, and for how long the extension trusts it.
public enum TopShelfRules {
    /// The App Group the Apple TV app and its Top Shelf extension share
    /// (`apple/project.yml`, the entitlements in `apple/TopShelf/`).
    public static let appGroup = "group.media.watchmy.client"

    /// A snapshot older than this is not shown: the app hasn't run for two
    /// weeks, so what was partway through may long be finished elsewhere.
    /// The system's static image shows until the app runs again.
    public static let maxAge: TimeInterval = 14 * 24 * 60 * 60

    /// Continue watching's ten (TvTopShelfSections shows four at a time),
    /// Recently added's twelve (eight at a time).
    public static func limit(_ kind: TopShelfSection.Kind) -> Int {
        switch kind {
        case .continueWatching: 10
        case .recentlyAdded: 12
        }
    }

    /// The query items an app link may carry. Anything else (an address, a
    /// token) can't reach the shelf, whatever builds the snapshot.
    public static let linkQueryNames: Set<String> = ["source", "server", "season", "episode", "play"]

    /// The source the app's Top Shelf links say they came from (`source=`).
    public static let linkSource = "top-shelf"

    /// The app's own `watchmymedia:` link, naming only what ``linkQueryNames`` allows.
    public static func isAppLink(_ url: URL) -> Bool {
        guard url.scheme == "watchmymedia", url.user == nil, url.password == nil, url.port == nil, url.fragment == nil,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return false }
        return (components.queryItems ?? []).allSatisfy { linkQueryNames.contains($0.name) }
    }

    /// A bare file name the app wrote: letters, digits, `-` and one `.`.
    public static func isPictureName(_ name: String) -> Bool {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789-.")
        return !name.isEmpty && name.count <= 64 && !name.hasPrefix(".") && name.filter { $0 == "." }.count <= 1
            && name.unicodeScalars.allSatisfy(allowed.contains)
    }

    /// Whether a snapshot written at `writtenAt` is shown at `now`. One from
    /// the future (the clock was set back) is shown.
    public static func isFresh(writtenAt: Date, now: Date) -> Bool {
        now.timeIntervalSince(writtenAt) <= maxAge
    }

    /// The file name for a picture: a hash of its image path and size (never
    /// a web address), with the extension its bytes say.
    public static func pictureName(path: String, size: String, data: Data) -> String {
        pictureStem(path: path, size: size) + pictureExtension(data)
    }

    /// The name a picture was saved under, when it is in `store` already.
    public static func savedPictureName(path: String, size: String, in store: TopShelfStore) -> String? {
        let stem = pictureStem(path: path, size: size)
        return [".jpg", ".png"].map { stem + $0 }.first { store.pictureURL($0) != nil }
    }

    static func pictureStem(path: String, size: String) -> String { fnv1a("\(size)|\(path)") }

    /// ".png" or ".jpg" by the bytes' signature; JPEG for anything else.
    static func pictureExtension(_ data: Data) -> String {
        data.starts(with: [0x89, 0x50, 0x4E, 0x47]) ? ".png" : ".jpg"
    }

    /// FNV-1a, 64 bits, in hex: stable across launches, unlike `Hasher`.
    static func fnv1a(_ text: String) -> String {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in text.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01b3
        }
        return String(format: "%016llx", hash)
    }
}

/// Who a snapshot is for: a server and a person on it.
public struct TopShelfOwner: Hashable, Sendable {
    public var serverID: String
    public var userID: Int

    public init(serverID: String, userID: Int) {
        self.serverID = serverID
        self.userID = userID
    }

    /// What the snapshot keeps of it: a hash, naming neither.
    public var key: String { TopShelfRules.fnv1a("\(serverID)|\(userID)") }
}
