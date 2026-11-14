import Foundation
import Observation
import WMMModel

/// Everything downloaded here (Downloads.dc.html, SettingsDownloads.dc.html,
/// Pad2Downloads.dc.html, the offline Home; android DownloadsViewModel):
/// movies, and shows with their episodes, the space they take, the download
/// settings, and what a row's menu does.
@MainActor
@Observable
public final class DownloadsModel {
    /// A show's downloaded episodes, in order.
    public struct ShowGroup: Hashable, Identifiable {
        public var showID: Int?
        public var title: String
        public var posterPath: String?
        public var episodes: [OfflineDownload]

        public var id: String { showID.map(String.init) ?? title }

        /// "3 episodes · 12.1 GB", "1 episode · about 1.1 GB".
        public var line: String {
            downloadGroupLine(count: episodes.count, bytes: episodes.reduce(0) { $0 + $1.sizeBytes }, estimated: episodes.contains(where: \.sizeEstimated))
        }
    }

    public private(set) var storage = DownloadStorage(usedBytes: 0, freeBytes: 0, totalBytes: 0)
    /// Asking whether to remove them all.
    public var confirmingRemoveAll = false
    /// Said once, then cleared by the view.
    public var message: String?

    @ObservationIgnored private let downloads: any DownloadRepository
    @ObservationIgnored private let navigator: Navigator
    /// "iPhone", "iPad".
    public let device: String

    public init(downloads: any DownloadRepository, navigator: Navigator, device: String = "iPhone") {
        self.downloads = downloads
        self.navigator = navigator
        self.device = device
    }

    public var enabled: Bool { downloads.enabled }
    public var canDownload: Bool { downloads.canDownload }
    public var all: [OfflineDownload] { downloads.downloads }
    public var settings: DownloadSettings { downloads.settings }

    public var movies: [OfflineDownload] { all.filter { $0.item.kind != .episode } }

    public var shows: [ShowGroup] {
        let episodes = all.filter { $0.item.kind == .episode }
        let grouped = Dictionary(grouping: episodes) { $0.item.showID ?? -1 }
        return grouped.map { id, eps in
            let first = eps[0].item
            return ShowGroup(
                showID: id >= 0 ? id : nil, title: first.showTitle ?? first.title, posterPath: first.posterPath,
                episodes: eps.sorted { ($0.item.season ?? 0, $0.item.episode ?? 0) < ($1.item.season ?? 0, $1.item.episode ?? 0) }
            )
        }.sorted { $0.title < $1.title }
    }

    /// Downloads partway through, for Continue watching offline.
    public var partway: [OfflineDownload] { all.filter { $0.downloaded && $0.partway != nil } }

    /// "66.1 GB": what the downloads take, as far as they've come.
    public var used: String { freeSize(usedBytes) }

    public var usedBytes: Int64 {
        all.reduce(0) { sum, d in
            if d.downloaded { return sum + d.sizeBytes }
            if case .downloading(_, let bytes) = d.status { return sum + bytes }
            return sum
        }
    }

    /// "96.4 GB": free space.
    public var free: String { freeSize(storage.freeBytes) }

    /// The bar's shares of the volume, 0–1: downloads, then everything else.
    public var usedShare: Double { storage.totalBytes > 0 ? min(Double(usedBytes) / Double(storage.totalBytes), 1) : 0 }
    public var otherShare: Double {
        guard storage.totalBytes > 0 else { return 0 }
        return max(min(Double(storage.totalBytes - storage.freeBytes - usedBytes) / Double(storage.totalBytes), 1 - usedShare), 0)
    }

    /// Settings' row: "7 · 66.1 GB", or "None".
    public var summary: String { all.isEmpty ? "None" : "\(all.count) · \(used)" }

    /// "7 downloads, 66.1 GB, are deleted from this iPhone. You can download them again."
    public var removeAllLine: String {
        "\(plural(all.count, "download")), \(used), \(all.count == 1 ? "is" : "are") deleted from this \(device). You can download them again."
    }

    /// The space now, when a screen opens (it walks the files, so not on every read).
    public func refreshStorage() { storage = downloads.storage() }

    // MARK: Rows

    /// A row's state line: "Downloading 42% · 1.8 of 4.2 GB", or the server's mark.
    public func stateLine(_ d: OfflineDownload) -> String { downloadRowState(d) }

    /// A row's facts: "Original · 46.1 GB", "720p · about 1.1 GB".
    public func factsLine(_ d: OfflineDownload) -> String {
        "\(d.quality.short) · \(downloadSize(d.sizeBytes, estimated: d.sizeEstimated))"
    }

    /// Opens the item's page: the movie, or the episode.
    public func open(_ d: OfflineDownload) {
        if let movie = d.item.movieID {
            navigator.open(.movie(id: movie))
        } else if let show = d.item.showID, let episode = d.item.episodeID {
            // The page can stand on its downloaded snapshot. Do not insert a
            // show page that cannot open offline between it and Downloads.
            navigator.setPath(navigator.path(for: navigator.tab) + [.episode(showID: show, season: d.item.season, id: episode)], for: navigator.tab)
        } else if d.downloaded {
            play(d)
        }
    }

    /// Settings › Downloads, from the offline Home.
    public func openAll() { navigator.open(.downloads) }

    /// Plays the copy on the device, resuming where this device last was.
    public func play(_ d: OfflineDownload) {
        navigator.play(playRequest(d))
    }

    public func playRequest(_ d: OfflineDownload) -> PlayRequest {
        let item = d.item
        let subtitle = item.kind == .episode ? downloadTitle(item) : nil
        return PlayRequest(
            fileID: d.fileID, title: item.kind == .episode ? (item.showTitle ?? item.title) : item.title, subtitle: subtitle,
            backdropPath: item.kind == .movie ? item.artPath : nil, stillPath: item.kind == .episode ? item.artPath : nil,
            posterPath: item.posterPath, showID: item.showID, seasonNumber: item.season,
            // A movie's year is all of the Info panel's details the download keeps.
            year: item.kind == .movie ? item.year : nil
        )
    }

    public func pause(_ d: OfflineDownload) { downloads.pause(d.id) }
    public func resume(_ d: OfflineDownload) { downloads.resume(d.id) }
    public func retry(_ d: OfflineDownload) { downloads.retry(d.id) }

    public func remove(_ d: OfflineDownload) {
        downloads.remove(d.id)
        refreshStorage()
    }

    public func removeAll() {
        downloads.removeAll()
        confirmingRemoveAll = false
        refreshStorage()
    }

    // MARK: Settings

    public func setQuality(_ quality: DownloadQuality) { downloads.setQuality(quality) }

    public func setWifiOnly(_ wifiOnly: Bool) { downloads.setWifiOnly(wifiOnly) }

    /// "Downloads wait for Wi-Fi", "Downloads use mobile data too".
    public var wifiLine: String { settings.wifiOnly ? "Downloads wait for Wi-Fi" : "Downloads use mobile data too" }

    /// Try again on the offline Home: a sync, which also says whether the server is back.
    public func sync() async { await downloads.sync() }
}
