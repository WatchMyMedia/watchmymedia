import Foundation
import Observation
import WMMModel

/// Downloads on the fakes (android FakeDownloads.kt): Dead Reckoning Bay
/// downloaded, an episode of Harbor Lights coming, and one of Saltmarsh whose
/// file the server deleted. A new download moves through its states in a few
/// seconds (at once with `FakeLatency.duration` zero), and Download again for
/// a new version replaces the old copy when it is done.
@MainActor
@Observable
public final class FakeDownloadRepository: DownloadRepository {
    public private(set) var downloads: [OfflineDownload]
    public var canDownload: Bool
    public private(set) var settings = DownloadSettings()
    /// The marks made offline, in order (tests).
    public private(set) var marks: [(fileID: Int, watched: Bool)] = []
    public private(set) var syncs = 0
    @ObservationIgnored private var runs: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var next = 0

    /// The audio track the server would choose for a converted copy (the
    /// person's audio languages); nil is the file's default.
    public var preferredAudio: Int?

    public init(seeded: Bool = true, canDownload: Bool = true) {
        self.canDownload = canDownload
        downloads = seeded ? Self.seed : []
    }

    public var enabled: Bool { canDownload || !downloads.isEmpty }

    public func plan(fileID: Int) async throws -> DownloadPlan {
        await FakeLatency.wait()
        // A 4K remux is converted; anything else downloads as it is at Original.
        let big = fileID >= 9400 && fileID < 9500 + 9400
        let original: Int64 = big ? 49_400_000_000 : 4_500_000_000
        // The server puts the person's audio language in a converted copy, else the default.
        let audio = big ? preferredAudio ?? 1 : nil
        return DownloadPlan(
            fileID: fileID, durationMs: 134 * 60_000,
            options: [
                DownloadOption(quality: .original, mode: big ? .remux : .direct, protocol: big ? .hls : .file, sizeBytes: original, estimated: big, audioConverted: big, audioIndex: audio),
                DownloadOption(quality: .p1080, mode: .transcode, protocol: .hls, sizeBytes: 9_000_000_000, estimated: true, audioIndex: audio),
                DownloadOption(quality: .p720, mode: .transcode, protocol: .hls, sizeBytes: 4_700_000_000, estimated: true, audioIndex: audio),
                DownloadOption(quality: .p480, mode: .transcode, protocol: .hls, sizeBytes: 2_600_000_000, estimated: true, audioIndex: audio),
            ],
            audio: big ? [
                AudioOption(index: 1, label: "English · TrueHD 7.1 (Default)", language: "eng", isDefault: true),
                AudioOption(index: 2, label: "Spanish · AC3 5.1", language: "spa", isDefault: false),
                AudioOption(index: 3, label: "English · AAC Stereo · Commentary", language: "eng", isDefault: false),
            ] : [],
            subtitles: [
                SubtitleOption(id: "s5", label: "English", language: "en", format: "SRT"),
                SubtitleOption(id: "s7", label: "English", language: "en", burnInOnly: true, format: "PGS"),
            ]
        )
    }

    public func download(_ choice: DownloadChoice) async throws {
        await FakeLatency.wait()
        next += 1
        let id = String(format: "fake%012d", next)
        let replaced = downloads.first { $0.replacedBy == choice.fileID }
        downloads.removeAll { $0.fileID == choice.fileID }
        let d = OfflineDownload(
            id: id, fileID: choice.fileID, item: Self.item(for: choice.fileID), quality: choice.quality,
            mode: choice.quality == .original ? .direct : .transcode, protocol: choice.quality == .original ? .file : .hls,
            sizeBytes: 4_200_000_000, sizeEstimated: choice.quality != .original, status: .waiting(.turn), createdAt: Date(), durationMs: 52 * 60_000
        )
        downloads.insert(d, at: 0)
        run(id, replacing: replaced?.id)
    }

    /// Waiting, then downloading in steps, then downloaded.
    private func run(_ id: String, replacing old: String?) {
        runs[id] = Task { [weak self] in
            var percent = 0
            while percent < 100 {
                if FakeLatency.duration > .zero { try? await Task.sleep(for: .milliseconds(400)) }
                guard !Task.isCancelled, let self else { return }
                percent = FakeLatency.duration > .zero ? percent + 10 : 100
                self.update(id) { $0.status = percent >= 100 ? .downloaded : .downloading(percent: percent, bytes: Int64(Double($0.sizeBytes) * Double(percent) / 100)) }
            }
            self?.update(id) {
                $0.completedAt = Date()
                $0.sizeEstimated = false
            }
            if let old { self?.downloads.removeAll { $0.id == old } }
        }
    }

    private func update(_ id: String, _ change: (inout OfflineDownload) -> Void) {
        guard let i = downloads.firstIndex(where: { $0.id == id }) else { return }
        change(&downloads[i])
    }

    public func pause(_ id: String) {
        runs.removeValue(forKey: id)?.cancel()
        update(id) {
            if case .downloading(let p, let b) = $0.status { $0.status = .paused(percent: p, bytes: b) }
        }
    }

    public func resume(_ id: String) {
        update(id) { $0.status = .waiting(.turn) }
        run(id, replacing: nil)
    }

    public func retry(_ id: String) { resume(id) }

    public func remove(_ id: String) {
        runs.removeValue(forKey: id)?.cancel()
        downloads.removeAll { $0.id == id }
    }

    public func removeAll() {
        for task in runs.values { task.cancel() }
        runs = [:]
        downloads = []
    }

    public func storage() -> DownloadStorage {
        DownloadStorage(usedBytes: downloads.reduce(0) { $0 + $1.sizeBytes }, freeBytes: 103_500_000_000, totalBytes: 256_000_000_000)
    }

    public func setQuality(_ quality: DownloadQuality) { settings.quality = quality }

    public func setWifiOnly(_ wifiOnly: Bool) { settings.wifiOnly = wifiOnly }

    public func mark(fileID: Int, watched: Bool) {
        marks.append((fileID, watched))
        update(downloads.first { $0.fileID == fileID }?.id ?? "") {
            $0.watch = watched ? WatchProgress(positionMs: 0, durationMs: $0.durationMs, completed: true) : nil
        }
    }

    public func sync() async {
        await FakeLatency.wait()
        syncs += 1
    }

    // MARK: The library's items

    private static func item(for fileID: Int) -> OfflineItem {
        if let match = FakeData.playbackEpisode(fileID: fileID) {
            let episode = match.episode
            return OfflineItem(kind: .episode, title: episode.title, showID: match.show.id, episodeID: episode.id,
                               showTitle: match.show.title, season: episode.season, episode: episode.number,
                               year: match.show.year, posterPath: match.show.posterPath, artPath: episode.stillPath)
        }
        if fileID >= 9400 {
            let movieID = fileID >= 9900 ? fileID - 9500 : fileID - 9400
            let m = FakeData.movie(id: movieID)
            return OfflineItem(kind: .movie, title: m.title, movieID: m.id, year: m.year, posterPath: m.posterPath, artPath: m.backdropPath)
        }
        let card = FakeData.recentlyAired().first { $0.fileID == fileID }
        return OfflineItem(
            kind: .episode, title: card?.title ?? "Episode", showID: card?.showID ?? 100, episodeID: card?.episodeID,
            showTitle: card?.showTitle ?? "Harbor Lights", season: card?.season ?? 3, episode: card?.episode ?? 7
        )
    }

    private static var seed: [OfflineDownload] {
        let movie = FakeData.movie(id: 500)
        let day = Date(timeIntervalSince1970: 1_793_318_400)
        return [
            OfflineDownload(
                id: "fakeseed00000001", fileID: 9101,
                item: OfflineItem(kind: .episode, title: "The Long Dark", showID: 100, episodeID: 100037, showTitle: "Harbor Lights", season: 3, episode: 7),
                quality: .original, mode: .direct, protocol: .file, sizeBytes: 4_200_000_000, sizeEstimated: false,
                status: .downloading(percent: 42, bytes: 1_800_000_000), createdAt: day.addingTimeInterval(7200), durationMs: 52 * 60_000
            ),
            OfflineDownload(
                id: "fakeseed00000002", fileID: 9003,
                item: OfflineItem(kind: .episode, title: "Undertow", showID: 102, episodeID: 102019, showTitle: "Saltmarsh", season: 1, episode: 9),
                quality: .original, mode: .direct, protocol: .file, sizeBytes: 1_900_000_000, sizeEstimated: false,
                status: .downloaded, mark: .removedFromServer, createdAt: day.addingTimeInterval(3600), completedAt: day.addingTimeInterval(4000), durationMs: 53 * 60_000,
                watch: WatchProgress(positionMs: 45 * 60_000, durationMs: 53 * 60_000, completed: false)
            ),
            OfflineDownload(
                id: "fakeseed00000003", fileID: movie.versions[0].fileID,
                item: OfflineItem(kind: .movie, title: movie.title, movieID: movie.id, year: movie.year, posterPath: movie.posterPath, artPath: movie.backdropPath),
                quality: .original, mode: .remux, protocol: .hls, sizeBytes: 46_100_000_000, sizeEstimated: false,
                status: .downloaded, createdAt: day, completedAt: day.addingTimeInterval(1800), durationMs: 134 * 60_000,
                watch: WatchProgress(positionMs: 62 * 60_000, durationMs: 134 * 60_000, completed: false)
            ),
        ]
    }
}
