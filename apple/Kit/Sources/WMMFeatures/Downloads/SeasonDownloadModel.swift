import Foundation
import Observation
import WMMModel

/// Plans and queues a season's files independently. A partial failure keeps
/// the successful downloads and offers only the remaining files on retry.
/// The captured account/server identity prevents a sheet from continuing a
/// batch after someone switches accounts or servers.
@MainActor
@Observable
public final class SeasonDownloadModel: Identifiable {
    public let id = UUID()
    public let title: String
    public let seasonNumber: Int
    public let device: String
    public let missingCount: Int
    public private(set) var state: Loadable<[DownloadPlan]> = .loading
    public private(set) var starting = false
    public private(set) var finished = false
    public private(set) var queuedCount = 0
    public private(set) var storage = DownloadStorage(usedBytes: 0, freeBytes: 0, totalBytes: 0)
    public var quality: DownloadQuality
    public var message: String?

    @ObservationIgnored private let downloads: any DownloadRepository
    @ObservationIgnored private let episodes: [EpisodeDetail]
    @ObservationIgnored private let session: @MainActor () -> String?
    @ObservationIgnored private let sessionAtOpen: String?
    @ObservationIgnored private var queued: Set<Int> = []
    @ObservationIgnored private var generation = 0

    public init(downloads: any DownloadRepository, season: SeasonDetail, device: String = "iPhone",
                session: @escaping @MainActor () -> String?) {
        self.downloads = downloads
        self.episodes = season.episodes
        self.title = season.showTitle
        self.seasonNumber = season.season.number
        self.device = device
        self.session = session
        self.sessionAtOpen = session()
        self.quality = downloads.settings.quality
        self.missingCount = season.episodes.filter { !$0.hasFile || $0.fileID == nil }.count
    }

    private var sameSession: Bool { sessionAtOpen != nil && session() == sessionAtOpen }

    /// A copy or transfer of another version of this episode also counts as held.
    private func held(_ episode: EpisodeDetail) -> Bool {
        downloads.downloads.contains {
            $0.fileID == episode.fileID || ($0.item.kind == .episode && $0.item.episodeID == episode.id)
        }
    }

    public var heldCount: Int { episodes.filter { $0.hasFile && $0.fileID != nil && held($0) }.count }

    public var pendingEpisodes: [EpisodeDetail] {
        var files: Set<Int> = []
        return episodes.filter {
            guard $0.hasFile, let file = $0.fileID, !queued.contains(file), !held($0) else { return false }
            return files.insert(file).inserted
        }
    }

    public var count: Int { pendingEpisodes.count }
    public var seasonTitle: String { seasonName(seasonNumber) }
    public var wifiLine: String { downloads.settings.wifiOnly ? "Downloads wait for Wi-Fi." : "Downloads use Wi-Fi and mobile data." }
    public var excludedLine: String? {
        var parts: [String] = []
        if heldCount > 0 { parts.append("\(plural(heldCount, "episode")) already downloaded or queued") }
        if missingCount > 0 { parts.append("\(plural(missingCount, "episode")) not in your library") }
        return parts.isEmpty ? nil : parts.joined(separator: ". ") + "."
    }

    private var pendingPlans: [DownloadPlan] {
        let ids = Set(pendingEpisodes.compactMap(\.fileID))
        return (state.value ?? []).filter { ids.contains($0.fileID) }
    }

    /// Offer only qualities every remaining file actually supports. Missing
    /// qualities are never silently substituted with a different size.
    public var options: [DownloadOption] {
        let plans = pendingPlans
        guard !plans.isEmpty, plans.count == count else { return [] }
        return DownloadQuality.allCases.compactMap { quality in
            let choices = plans.compactMap { $0.options.first { $0.quality == quality } }
            guard choices.count == plans.count, choices.allSatisfy({ $0.sizeBytes >= 0 }) else { return nil }
            let bytes = choices.reduce(Int64(0)) { partial, option in
                let next = partial.addingReportingOverflow(option.sizeBytes)
                return next.overflow ? Int64.max : next.partialValue
            }
            return DownloadOption(quality: quality, mode: choices.first?.mode ?? .direct,
                                  protocol: choices.contains { $0.protocol == .hls } ? .hls : .file,
                                  sizeBytes: bytes, estimated: choices.contains(where: \.estimated),
                                  audioConverted: choices.contains(where: \.audioConverted))
        }
    }

    public var chosen: DownloadOption? { options.first { $0.quality == quality } ?? options.first }

    public func fits(_ option: DownloadOption) -> Bool { storage.fits(option.sizeBytes) }
    public var canStart: Bool { !starting && sameSession && downloads.canDownload && chosen.map(fits) == true }
    public var downloadLabel: String { "Download \(plural(count, "episode"))" }
    public var summary: String {
        let size = chosen.map { " · \(downloadSize($0.sizeBytes, estimated: $0.estimated))" } ?? ""
        return "\(plural(count, "episode"))\(size)"
    }
    public var spaceLine: String {
        if let chosen, !fits(chosen) {
            return "Not enough space. Keep at least 1 GB free after downloading. Choose a lower quality or remove a download."
        }
        return "\(freeSize(storage.freeBytes)) available on this \(device). At least 1 GB stays free."
    }

    public func load() async {
        generation += 1
        let mine = generation
        guard sameSession, downloads.canDownload else {
            state = .failed("This account or server changed, or downloading is no longer allowed. Open Download season again.")
            return
        }
        state = .loading
        storage = downloads.availableStorage()
        var plans: [DownloadPlan] = []
        do {
            for episode in pendingEpisodes {
                try Task.checkCancellation()
                guard sameSession else { throw CancellationError() }
                if let file = episode.fileID { plans.append(try await downloads.plan(fileID: file)) }
                guard mine == generation, sameSession else { throw CancellationError() }
            }
            state = .loaded(plans)
            storage = downloads.availableStorage()
        } catch is CancellationError {
            if mine == generation {
                state = .failed("This account or server changed, or the download options were cancelled. Open Download season again.")
            }
        } catch {
            if mine == generation { state = .failed("Couldn’t prepare every episode. \(error.displayMessage)") }
        }
    }

    /// A file is a durable transfer once the repository accepts it. A later
    /// create failure stops the batch, leaves accepted transfers alone and
    /// allows the remaining episodes to be submitted again.
    public func start() async {
        storage = downloads.availableStorage()
        guard let chosen, canStart else { return }
        let pending = pendingEpisodes
        starting = true
        message = nil
        defer { starting = false; storage = downloads.availableStorage() }
        do {
            for episode in pending {
                try Task.checkCancellation()
                guard sameSession, downloads.canDownload else { throw CancellationError() }
                guard let file = episode.fileID, !held(episode) else { continue }
                try await downloads.download(DownloadChoice(fileID: file, quality: chosen.quality))
                queued.insert(file)
                queuedCount += 1
                guard sameSession else { throw CancellationError() }
            }
            finished = pendingEpisodes.isEmpty
            message = "Added \(plural(queuedCount, "episode")) to Downloads."
        } catch is CancellationError {
            message = "Added \(plural(queuedCount, "episode")). The remaining episodes weren’t added because the account, server or task changed."
        } catch {
            message = "Added \(plural(queuedCount, "episode")). \(plural(pendingEpisodes.count, "episode")) weren’t added: \(error.displayMessage) Try again for the remaining episodes."
        }
    }
}
