import Foundation
import Observation

// Offline downloads on iPhone and iPad (PLAN-DOWNLOADS.md, phase D3), worded
// and shaped as the Android app's (android/core/model/.../Downloads.kt).

/// The qualities a download is made at (Settings › Downloads › Quality, and the download sheet), best first.
public enum DownloadQuality: String, CaseIterable, Hashable, Sendable, Codable {
    case original, p1080 = "1080", p720 = "720", p480 = "480"

    /// An ID this app doesn't know reads as Original, the default.
    public init(id: String?) {
        self = id.flatMap(DownloadQuality.init(rawValue:)) ?? .original
    }

    public var id: String { rawValue }

    /// "Original", "1080p · 8 Mbps".
    public var label: String {
        switch self {
        case .original: "Original"
        case .p1080: "1080p · 8 Mbps"
        case .p720: "720p · 4 Mbps"
        case .p480: "480p · 2 Mbps"
        }
    }

    /// "Original", "1080p".
    public var short: String {
        switch self {
        case .original: "Original"
        case .p1080: "1080p"
        case .p720: "720p"
        case .p480: "480p"
        }
    }

    /// The step's cap in bits per second; nil for Original.
    public var maxBitrate: Int64? {
        switch self {
        case .original: nil
        case .p1080: 8_000_000
        case .p720: 4_000_000
        case .p480: 2_000_000
        }
    }
}

/// What a download is of: a snapshot the server took when it was made, so it reads after the file goes.
public struct OfflineItem: Hashable, Sendable, Codable {
    /// A file not matched yet is `.file`.
    public enum Kind: String, Hashable, Sendable, Codable { case movie, episode, file }

    public var kind: Kind
    public var title: String
    public var movieID: Int?
    public var showID: Int?
    public var episodeID: Int?
    public var showTitle: String?
    public var season: Int?
    public var episode: Int?
    public var year: Int?
    /// The movie's or the show's poster.
    public var posterPath: String?
    /// The movie's backdrop, or the episode's still.
    public var artPath: String?

    public init(kind: Kind, title: String, movieID: Int? = nil, showID: Int? = nil, episodeID: Int? = nil, showTitle: String? = nil, season: Int? = nil, episode: Int? = nil, year: Int? = nil, posterPath: String? = nil, artPath: String? = nil) {
        self.kind = kind
        self.title = title
        self.movieID = movieID
        self.showID = showID
        self.episodeID = episodeID
        self.showTitle = showTitle
        self.season = season
        self.episode = episode
        self.year = year
        self.posterPath = posterPath
        self.artPath = artPath
    }
}

/// Where a download stands on this device.
public enum DownloadStatus: Hashable, Sendable {
    public enum Reason: Hashable, Sendable { case turn, wifi, network, storage }

    /// Waiting to start: for another download, for a network, or for space.
    case waiting(Reason)
    /// The server is getting it ready (a 503 with Retry-After: keyframes being read, or the transcoder busy).
    case preparing
    case downloading(percent: Int, bytes: Int64)
    case paused(percent: Int, bytes: Int64)
    case downloaded
    case failed

    /// Still to finish: waiting, preparing, downloading or paused.
    public var coming: Bool {
        switch self {
        case .waiting, .preparing, .downloading, .paused: true
        case .downloaded, .failed: false
        }
    }
}

/// What the server said of a held download at its last sync (PLAN-DOWNLOADS.md §5): the copy still plays.
public enum DownloadMark: String, Hashable, Sendable, Codable { case none, removedFromServer, newVersion }

/// A download held on this device for the signed-in account.
public struct OfflineDownload: Hashable, Sendable, Identifiable {
    /// The server's record ID.
    public var id: String
    public var fileID: Int
    public var item: OfflineItem
    public var quality: DownloadQuality
    public var mode: PlaybackMode
    public var `protocol`: StreamProtocol
    /// The size once downloaded; the server's estimate before (`sizeEstimated`).
    public var sizeBytes: Int64
    public var sizeEstimated: Bool
    public var status: DownloadStatus
    public var mark: DownloadMark
    /// `.newVersion`: the item's file now, to download again.
    public var replacedBy: Int?
    public var createdAt: Date
    public var completedAt: Date?
    public var durationMs: Int
    /// Where the viewer is in it, as this device knows: what they watched here, else what the server said when it was made.
    public var watch: WatchProgress?

    public init(id: String, fileID: Int, item: OfflineItem, quality: DownloadQuality, mode: PlaybackMode, protocol: StreamProtocol, sizeBytes: Int64, sizeEstimated: Bool, status: DownloadStatus, mark: DownloadMark = .none, replacedBy: Int? = nil, createdAt: Date = .distantPast, completedAt: Date? = nil, durationMs: Int = 0, watch: WatchProgress? = nil) {
        self.id = id
        self.fileID = fileID
        self.item = item
        self.quality = quality
        self.mode = mode
        self.protocol = `protocol`
        self.sizeBytes = sizeBytes
        self.sizeEstimated = sizeEstimated
        self.status = status
        self.mark = mark
        self.replacedBy = replacedBy
        self.createdAt = createdAt
        self.completedAt = completedAt
        self.durationMs = durationMs
        self.watch = watch
    }

    /// A copy that plays: downloaded, whatever the server has done since.
    public var downloaded: Bool { status == .downloaded }

    /// Partway through, for Continue watching offline.
    public var partway: WatchProgress? {
        guard let watch, !watch.completed, watch.positionMs > 0, watch.durationMs > watch.positionMs else { return nil }
        return watch
    }
}

/// How a file would download at one quality (``DownloadPlan``).
public struct DownloadOption: Hashable, Sendable {
    public var quality: DownloadQuality
    public var mode: PlaybackMode
    public var `protocol`: StreamProtocol
    public var sizeBytes: Int64
    /// The size is about this much: anything but the original file.
    public var estimated: Bool
    /// The audio is converted.
    public var audioConverted: Bool
    /// The audio track it carries when the sheet names none: the person's
    /// audio languages, else the file's default (API level 21); nil when the
    /// server didn't say.
    public var audioIndex: Int?

    public init(quality: DownloadQuality, mode: PlaybackMode, protocol: StreamProtocol, sizeBytes: Int64, estimated: Bool, audioConverted: Bool = false, audioIndex: Int? = nil) {
        self.quality = quality
        self.mode = mode
        self.protocol = `protocol`
        self.sizeBytes = sizeBytes
        self.estimated = estimated
        self.audioConverted = audioConverted
        self.audioIndex = audioIndex
    }

    /// The sheet's line under the quality: "The file as it is", "Video as it is, audio converted", "Converted on the server".
    public var detail: String {
        switch mode {
        case .direct: "The file as it is"
        case .remux: audioConverted ? "Video as it is, audio converted" : "Video and audio as they are"
        case .audioTranscode: "Video as it is, audio converted"
        case .transcode: "Converted on the server"
        }
    }
}

/// What the download sheet lists (POST /api/files/{id}/offline/plan).
public struct DownloadPlan: Hashable, Sendable {
    public var fileID: Int
    public var durationMs: Int
    /// Best first.
    public var options: [DownloadOption]
    /// An HLS download carries one: the sheet picks it.
    public var audio: [AudioOption]
    /// Text subtitles come with every download; image ones only burned in, picked in the sheet.
    public var subtitles: [SubtitleOption]

    public init(fileID: Int, durationMs: Int, options: [DownloadOption], audio: [AudioOption] = [], subtitles: [SubtitleOption] = []) {
        self.fileID = fileID
        self.durationMs = durationMs
        self.options = options
        self.audio = audio
        self.subtitles = subtitles
    }

    public func option(_ quality: DownloadQuality) -> DownloadOption? {
        options.first { $0.quality == quality } ?? options.first
    }

    public var imageSubtitles: [SubtitleOption] { subtitles.filter(\.burnInOnly) }

    public var textSubtitles: [SubtitleOption] { subtitles.filter { !$0.burnInOnly } }
}

/// A download to make: the file at `quality`, with an HLS download's audio track and an image subtitle to burn in.
public struct DownloadChoice: Hashable, Sendable {
    public var fileID: Int
    public var quality: DownloadQuality
    public var audioIndex: Int?
    public var burnInSubtitleID: String?

    public init(fileID: Int, quality: DownloadQuality, audioIndex: Int? = nil, burnInSubtitleID: String? = nil) {
        self.fileID = fileID
        self.quality = quality
        self.audioIndex = audioIndex
        self.burnInSubtitleID = burnInSubtitleID
    }
}

/// The space downloads take and what is left, on the volume that holds them.
public struct DownloadStorage: Hashable, Sendable {
    /// 1 GB stays free after any download (PLAN-DOWNLOADS.md §5).
    public static let reserveBytes: Int64 = 1 << 30

    public var usedBytes: Int64
    public var freeBytes: Int64
    public var totalBytes: Int64

    public init(usedBytes: Int64, freeBytes: Int64, totalBytes: Int64) {
        self.usedBytes = usedBytes
        self.freeBytes = freeBytes
        self.totalBytes = totalBytes
    }

    /// A download of `sizeBytes` fits when 1 GB would still be free after it.
    public func fits(_ sizeBytes: Int64) -> Bool {
        sizeBytes >= 0 && freeBytes >= Self.reserveBytes && sizeBytes <= freeBytes - Self.reserveBytes
    }
}

public struct DownloadSettings: Hashable, Sendable {
    public var quality: DownloadQuality
    public var wifiOnly: Bool

    public init(quality: DownloadQuality = .original, wifiOnly: Bool = true) {
        self.quality = quality
        self.wifiOnly = wifiOnly
    }
}

/// Downloads to watch offline on an iPhone or iPad (PLAN-DOWNLOADS.md §10).
/// Everything here is for the account signed in now. Methods that talk to
/// the server throw ``APIError`` or ``SignedOutError``.
/// ``UnavailableDownloads`` where there are none: the Apple TV app, and
/// previews.
@MainActor
public protocol DownloadRepository: AnyObject, Observable {
    /// Downloads are offered: a server at ``offlineAPILevel`` that lets this person download, or downloads already here.
    var enabled: Bool { get }

    /// Download may be offered for new items: the server lets this person download.
    var canDownload: Bool { get }

    /// This account's downloads, newest first.
    var downloads: [OfflineDownload] { get }

    var settings: DownloadSettings { get }

    /// How `fileID` would download at each quality.
    func plan(fileID: Int) async throws -> DownloadPlan

    /// Makes the download and queues it. A device downloading a file again replaces its earlier download of it.
    func download(_ choice: DownloadChoice) async throws

    func pause(_ id: String)

    func resume(_ id: String)

    /// Starts a failed download again.
    func retry(_ id: String)

    /// Deletes the copy (or stops the download) and tells the server at the next sync.
    func remove(_ id: String)

    func removeAll()

    func storage() -> DownloadStorage

    /// Space available for a new download after reserving unfinished transfers,
    /// including other signed-in accounts when the repository keeps them.
    /// `storage()` continues to describe the physical volume for storage charts.
    func availableStorage() -> DownloadStorage

    func setQuality(_ quality: DownloadQuality)

    func setWifiOnly(_ wifiOnly: Bool)

    /// Marks a downloaded item watched or unwatched while offline; synced by when it happened.
    func mark(fileID: Int, watched: Bool)

    /// Sends offline progress and learns what the server decided of the downloads (POST /api/offline/sync).
    func sync() async
}

public extension DownloadRepository {
    /// Simple repositories expose only the current account's pending transfers.
    func availableStorage() -> DownloadStorage {
        var result = storage()
        for held in downloads where held.status.coming {
            let size = max(0, held.sizeBytes)
            let written: Int64
            switch held.status {
            case .downloading(_, let bytes), .paused(_, let bytes): written = min(size, max(0, bytes))
            default: written = 0
            }
            result.freeBytes = max(0, result.freeBytes - min(max(0, result.freeBytes), size - written))
        }
        return result
    }
}

/// No downloads: the Apple TV app, older servers in previews, and tests that don't need them.
@MainActor
@Observable
public final class UnavailableDownloads: DownloadRepository {
    public init() {}

    public var enabled: Bool { false }
    public var canDownload: Bool { false }
    public var downloads: [OfflineDownload] { [] }
    public var settings: DownloadSettings { DownloadSettings() }
    public func plan(fileID: Int) async throws -> DownloadPlan { throw APIError(status: nil, message: Self.off) }
    public func download(_ choice: DownloadChoice) async throws { throw APIError(status: nil, message: Self.off) }
    public func pause(_ id: String) {}
    public func resume(_ id: String) {}
    public func retry(_ id: String) {}
    public func remove(_ id: String) {}
    public func removeAll() {}
    public func storage() -> DownloadStorage { DownloadStorage(usedBytes: 0, freeBytes: 0, totalBytes: 0) }
    public func setQuality(_ quality: DownloadQuality) {}
    public func setWifiOnly(_ wifiOnly: Bool) {}
    public func mark(fileID: Int, watched: Bool) {}
    public func sync() async {}

    private static let off = "Downloads aren’t available here."
}

extension Array where Element == OfflineDownload {
    /// The download of `fileID` held here, if any.
    public func forFile(_ fileID: Int?) -> OfflineDownload? {
        guard let fileID else { return nil }
        return first { $0.fileID == fileID }
    }
}

// MARK: - Wording

private let gigabyte = 1_073_741_824.0

/// "about 46 GB" for an estimate (whole gigabytes from 10 GB), else the size as it is ("4.2 GB").
public func downloadSize(_ bytes: Int64, estimated: Bool) -> String {
    let gb = Double(bytes) / gigabyte
    let size: String
    if gb < 0.1 {
        size = formatBytes(bytes)
    } else if estimated && gb >= 10 {
        size = "\(Int(gb.rounded())) GB"
    } else {
        size = String(format: "%.1f GB", gb)
    }
    return estimated ? "about \(size)" : size
}

/// "47 GB", "5.2 GB": what must be free for a download of `bytes`.
public func neededSize(_ bytes: Int64) -> String {
    let gb = (Double(bytes) + Double(DownloadStorage.reserveBytes)) / gigabyte
    return gb >= 10 ? "\(Int(gb.rounded())) GB" : String(format: "%.1f GB", gb)
}

/// "96.4 GB": free space, to a tenth.
public func freeSize(_ bytes: Int64) -> String { String(format: "%.1f GB", Double(bytes) / gigabyte) }

/// The download button's words (MovieDownload.dc.html).
public func downloadLabel(_ status: DownloadStatus) -> String {
    switch status {
    case .waiting(let reason): reason == .wifi ? "Waiting for Wi-Fi" : "Waiting"
    case .preparing: "Preparing"
    case .downloading(let percent, _): "Downloading \(percent)%"
    case .paused(let percent, _): "Paused · \(percent)%"
    case .downloaded: "Downloaded"
    case .failed: "Couldn’t download"
    }
}

/// The line under the button, for a wait the person can't see the reason of.
public func downloadHelp(_ status: DownloadStatus) -> String? {
    switch status {
    case .waiting(.turn): "Another download is first."
    case .preparing: "The server is getting it ready."
    default: nil
    }
}

/// The menu's title for a download (MovieDownload.dc.html, menu open).
public func downloadMenuTitle(_ status: DownloadStatus) -> String {
    if case .paused(let percent, _) = status { return "Paused at \(percent)%" }
    return downloadLabel(status)
}

/// The menu's line under the title: "Original · 46.1 GB · downloaded Oct 30",
/// "19.4 of about 46 GB · on Wi-Fi", "It starts when this iPhone is on Wi-Fi."
public func downloadMenuLine(_ d: OfflineDownload, device: String = "iPhone", wifiOnly: Bool = true, calendar: Calendar = .current) -> String? {
    switch d.status {
    case .downloaded:
        let day = d.completedAt.map { "downloaded \(monthDay($0, calendar: calendar))" }
        return [d.quality.short, formatBytes(d.sizeBytes), day].compactMap { $0 }.joined(separator: " · ")
    case .downloading(_, let bytes):
        return progressBytes(bytes, of: d) + (wifiOnly ? " · on Wi-Fi" : "")
    case .paused(_, let bytes):
        return progressBytes(bytes, of: d)
    case .waiting(.wifi):
        return "It starts when this \(device) is on Wi-Fi."
    default:
        return downloadHelp(d.status)
    }
}

/// "Oct 30".
func monthDay(_ date: Date, calendar: Calendar = .current) -> String {
    let f = DateFormatter()
    f.calendar = calendar
    f.timeZone = calendar.timeZone
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "MMM d"
    return f.string(from: date)
}

/// "19.4 of about 46 GB", "1.8 of 4.2 GB".
public func progressBytes(_ bytes: Int64, of d: OfflineDownload) -> String {
    "\(String(format: "%.1f", Double(bytes) / gigabyte)) of \(downloadSize(d.sizeBytes, estimated: d.sizeEstimated))"
}

/// The Downloads list's state line: "Downloading 42% · 1.8 of 4.2 GB", or the server's mark.
public func downloadRowState(_ d: OfflineDownload) -> String {
    switch d.mark {
    case .removedFromServer: return "Removed from the server"
    case .newVersion: return "A new version is available"
    case .none: break
    }
    if case .downloading(_, let bytes) = d.status { return "\(downloadLabel(d.status)) · \(progressBytes(bytes, of: d))" }
    return downloadLabel(d.status)
}

/// "S03 E05 · Keel", or the movie's title.
public func downloadTitle(_ item: OfflineItem) -> String {
    if item.kind == .episode, let s = item.season, let e = item.episode { return "\(episodeCode(season: s, episode: e)) · \(item.title)" }
    return item.title
}

/// "3 episodes · 12.1 GB", "1 episode · about 1.1 GB": a show's line in Downloads.
public func downloadGroupLine(count: Int, bytes: Int64, estimated: Bool) -> String {
    "\(plural(count, "episode")) · \(downloadSize(bytes, estimated: estimated))"
}

extension OfflineDownload {
    /// A downloaded episode's page, without asking a server that may be away.
    /// Only facts kept with the download are included; no metadata is invented.
    public func episodeDetail() -> EpisodeDetail? {
        guard downloaded, item.kind == .episode, let id = item.episodeID,
              let season = item.season, let number = item.episode else { return nil }
        return EpisodeDetail(
            id: id, season: season, number: number, title: item.title,
            stillPath: item.artPath, runtimeMin: durationMs > 0 ? durationMs / 60_000 : nil,
            hasFile: true, aired: true,
            versions: [MediaVersion(fileID: fileID, label: item.title, sizeBytes: sizeBytes,
                                    durationMs: durationMs > 0 ? durationMs : nil)],
            watch: watch
        )
    }

    /// The movie page offline: what the download knows of the movie, with the progress made here.
    public func movieDetail() -> MovieDetail? {
        guard let id = item.movieID else { return nil }
        let runtime = durationMs / 60_000
        return MovieDetail(
            id: id, libraryID: 0, title: item.title, year: item.year, tagline: nil, overview: "", releaseDate: nil,
            runtimeMin: runtime > 0 ? runtime : nil, genres: [], rating: nil, posterPath: item.posterPath, backdropPath: item.artPath,
            quality: nil, hdr: false,
            versions: [MediaVersion(fileID: fileID, label: item.title, sizeBytes: sizeBytes, durationMs: durationMs > 0 ? durationMs : nil)],
            cast: [], watch: watch
        )
    }
}
