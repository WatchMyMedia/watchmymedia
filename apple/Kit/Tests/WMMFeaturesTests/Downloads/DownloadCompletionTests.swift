import Foundation
import Observation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@MainActor
@Suite struct EpisodeOfflineTests {
    init() { FakeLatency.duration = .zero }

    @Test func thePageUsesOnlyFinishedCopiesFromThisDownloadedSeason() async throws {
        let library = TestLibrary()
        library.failure = APIError(status: nil, message: "Couldn’t reach the server.")
        let downloads = BatchDownloads()
        downloads.downloads = [copy(1), copy(2), copy(3, status: .waiting(.turn)), copy(4, season: 2), copy(5, show: 200)]
        let navigator = Navigator()
        let requests = FakeRequestsRepository(library: library.fake)
        let model = ShowPageModel(library: library, navigator: navigator, showID: 100, arrival: .episode(id: 100002, season: nil), requests: requests, downloads: downloads)
        await model.retry()
        #expect(model.offline)
        #expect(model.showTitle == "Harbor Lights")
        #expect(model.selectedSeason == 1)
        #expect(model.episode?.title == "Episode 2")
        #expect(model.seasonEpisodes.map(\.number) == [1, 2])
        #expect(!model.hasMenu && !model.canDelete && !model.canReplace && !model.canReport)
        #expect(!model.canRequestMissingSeasons && !model.canMarkSeason && !model.canMarkShow && !model.canShowMissing)
        model.play(fromStart: true)
        #expect(navigator.playing?.fileID == 9002)
        #expect(navigator.playing?.startMs == 0)
        model.select(model.seasonEpisodes[0])
        #expect(model.episodeID == 100001)
        #expect(library.showCalls == 1)
    }

    @Test func offlineMarksAndPlaybackProgressRefreshWithoutCallingTheServer() async throws {
        let library = TestLibrary()
        library.failure = APIError(status: nil, message: "Offline")
        let downloads = BatchDownloads()
        downloads.downloads = [copy(1)]
        let model = ShowPageModel(library: library, navigator: Navigator(), showID: 100, arrival: .episode(id: 100001, season: 1), downloads: downloads)
        await model.retry()
        await model.toggleWatched()
        #expect(downloads.marks.map(\.fileID) == [9001])
        #expect(model.episode?.watched == true)
        #expect(library.watchCalls.isEmpty)
        downloads.downloads[0].watch = WatchProgress(positionMs: 120_000, durationMs: 600_000, completed: false)
        model.refreshOffline()
        #expect(model.episode?.watch?.positionMs == 120_000)
        await model.toggleWatched()
        #expect(model.episode?.watched == true)
    }

    @Test func losingTheCopyOrChangingAccountCannotKeepTheOldPagePlayable() async {
        let library = TestLibrary()
        library.failure = APIError(status: nil, message: "Offline")
        let downloads = BatchDownloads()
        downloads.downloads = [copy(1)]
        let navigator = Navigator()
        let model = ShowPageModel(library: library, navigator: navigator, showID: 100, arrival: .episode(id: 100001, season: 1), downloads: downloads)
        await model.retry()
        downloads.downloads = []
        #expect(model.episode == nil)
        model.refreshOffline()
        model.play()
        #expect(navigator.playing == nil)
        #expect(model.state == .failed("This episode isn’t downloaded any more."))
    }

    @Test func anUnfinishedCopyDoesNotMasqueradeAsAnOfflineEpisode() async {
        let library = TestLibrary()
        library.failure = APIError(status: nil, message: "Offline")
        let downloads = BatchDownloads()
        downloads.downloads = [copy(1, status: .downloading(percent: 90, bytes: 900))]
        let model = ShowPageModel(library: library, navigator: Navigator(), showID: 100, arrival: .episode(id: 100001, season: 1), downloads: downloads)
        await model.retry()
        #expect(!model.offline)
        #expect(model.state == .failed("Offline"))
    }

    @Test func anOldPageCannotMarkAnotherAccountsCopyWithTheSameEpisodeID() async {
        let library = TestLibrary()
        library.failure = APIError(status: nil, message: "Offline")
        let downloads = BatchDownloads()
        downloads.downloads = [copy(1)]
        var account = "one"
        let model = ShowPageModel(library: library, navigator: Navigator(), showID: 100, arrival: .episode(id: 100001, season: 1),
                                 downloads: downloads, downloadSession: { account })
        await model.retry()
        account = "two"
        await model.toggleWatched()
        #expect(model.episode == nil)
        #expect(downloads.marks.isEmpty)
    }

    @Test func downloadedEpisodeNavigationDoesNotInsertAnUnreachableShowPage() {
        let downloads = BatchDownloads()
        downloads.downloads = [copy(1)]
        let navigator = Navigator()
        navigator.open(.downloads)
        let list = DownloadsModel(downloads: downloads, navigator: navigator)
        list.open(downloads.downloads[0])
        #expect(navigator.path(for: navigator.tab) == [.downloads, .episode(showID: 100, season: 1, id: 100001)])
    }

    @Test func offlineIgnoresAnUnrelatedEpisodeSelection() async {
        let library = TestLibrary()
        library.failure = APIError(status: nil, message: "Offline")
        let downloads = BatchDownloads()
        downloads.downloads = [copy(1)]
        let navigator = Navigator()
        navigator.open(.episode(showID: 100, season: 1, id: 100001))
        let model = ShowPageModel(library: library, navigator: navigator, showID: 100, arrival: .episode(id: 100001, season: 1), downloads: downloads)
        await model.retry()
        model.select(EpisodeDetail(id: 100002, season: 1, number: 2, title: "Not downloaded", hasFile: true, aired: true))
        #expect(model.episodeID == 100001)
        #expect(navigator.path(for: navigator.tab) == [.episode(showID: 100, season: 1, id: 100001)])
    }
}

@MainActor
@Suite struct SeasonDownloadModelTests {
    private func model(_ downloads: BatchDownloads, session: @escaping @MainActor () -> String? = { "account-one" }) -> SeasonDownloadModel {
        SeasonDownloadModel(downloads: downloads, season: season(), session: session)
    }

    @Test func plansOnlyEligibleEpisodesAndSumsTheQualityBeforeQueueing() async throws {
        let downloads = BatchDownloads()
        downloads.downloads = [copy(1)]
        let model = model(downloads)
        await model.load()
        #expect(downloads.planCalls == [9002, 9003])
        #expect(model.count == 2 && model.heldCount == 1 && model.missingCount == 1)
        #expect(model.excludedLine == "1 episode already downloaded or queued. 1 episode not in your library.")
        #expect(model.chosen?.sizeBytes == 1_000)
        model.quality = .p720
        #expect(model.chosen?.sizeBytes == 200)
        #expect(model.chosen?.estimated == true)
        await model.start()
        #expect(downloads.choices.map(\.fileID) == [9002, 9003])
        #expect(downloads.choices.allSatisfy { $0.quality == .p720 && $0.audioIndex == nil && $0.burnInSubtitleID == nil })
        #expect(model.finished && model.queuedCount == 2)
        await model.start()
        #expect(downloads.choices.count == 2)
    }

    @Test func fakeSeasonDownloadsKeepTheEpisodesIdentityInsteadOfBecomingMovies() async throws {
        FakeLatency.duration = .zero
        let season = try await FakeLibraryRepository().season(showID: 100, number: 3)
        let episode = try #require(season.episodes.first)
        let file = try #require(episode.fileID)
        let downloads = FakeDownloadRepository(seeded: false)
        try await downloads.download(DownloadChoice(fileID: file, quality: .p720))
        #expect(downloads.downloads.first?.item.kind == .episode)
        #expect(downloads.downloads.first?.item.episodeID == episode.id)
        #expect(downloads.downloads.first?.item.showTitle == "Harbor Lights")
    }

    @Test func aHeldVersionOrAnExistingTransferIsNotReplacedByTheBatch() async {
        let downloads = BatchDownloads()
        downloads.downloads = [copy(1, file: 40001), copy(2, status: .paused(percent: 40, bytes: 200))]
        let model = model(downloads)
        await model.load()
        await model.start()
        #expect(downloads.planCalls == [9003])
        #expect(downloads.choices.map(\.fileID) == [9003])
        #expect(downloads.downloads.contains { $0.fileID == 40001 && $0.quality == .original })
    }

    @Test func totalSizeAndAlreadyQueuedBytesMustLeaveTheReserveFree() async {
        let downloads = BatchDownloads()
        downloads.freeBytes = DownloadStorage.reserveBytes + 1_499
        let model = model(downloads)
        await model.load()
        #expect(!model.canStart, "three original copies need 1500, not only the first file’s 500")
        await model.start()
        #expect(downloads.choices.isEmpty)
        model.quality = .p720
        #expect(model.canStart)
        downloads.downloads = [copy(9, show: 200, status: .waiting(.turn))]
        downloads.freeBytes = DownloadStorage.reserveBytes + 799
        await model.load()
        #expect(!model.canStart, "the unrelated pending 500 bytes are reserved before this 300-byte batch")
    }

    @Test func simpleRepositoriesReserveOnlyTheUnwrittenPendingBytes() {
        let downloads = BatchDownloads()
        downloads.freeBytes = DownloadStorage.reserveBytes + 2_000
        downloads.downloads = [copy(1, status: .paused(percent: 40, bytes: 200)),
                               copy(2, status: .waiting(.wifi)), copy(3, status: .failed), copy(4)]
        #expect(downloads.availableStorage().freeBytes == DownloadStorage.reserveBytes + 1_200)
        #expect(downloads.storage().freeBytes == DownloadStorage.reserveBytes + 2_000)
    }

    @Test func partialFailureKeepsAcceptedFilesAndRetriesOnlyTheRemainingOnes() async {
        let downloads = BatchDownloads()
        downloads.failFile = 9002
        let model = model(downloads)
        await model.load()
        await model.start()
        #expect(model.queuedCount == 1 && !model.finished)
        #expect(downloads.choices.map(\.fileID) == [9001])
        #expect(model.count == 2)
        #expect(model.message?.contains("2 episodes weren’t added") == true)
        downloads.failFile = nil
        await model.start()
        #expect(downloads.choices.map(\.fileID) == [9001, 9002, 9003])
        #expect(model.finished)
    }

    @Test func switchingAccountsDuringPlanningOrQueueingStopsFurtherRequests() async {
        let downloads = BatchDownloads()
        var account = "one"
        downloads.planHook = { _ in account = "two" }
        let plan = model(downloads, session: { account })
        await plan.load()
        #expect(plan.state.value == nil && !plan.canStart)
        #expect(downloads.planCalls == [9001])
        downloads.planHook = nil
        let queue = model(downloads, session: { account })
        await queue.load()
        downloads.downloadHook = { _ in account = "three" }
        await queue.start()
        #expect(downloads.choices.map(\.fileID) == [9001])
        #expect(!queue.finished && queue.queuedCount == 1)
        await queue.start()
        #expect(downloads.choices.count == 1)
    }

    @Test func planningFailureAndAnUnsupportedCommonQualityNeverQueueAPartialGuess() async {
        let downloads = BatchDownloads()
        downloads.failPlan = 9002
        let model = model(downloads)
        await model.load()
        #expect(!model.canStart && model.state.value == nil)
        await model.start()
        #expect(downloads.choices.isEmpty)
        downloads.failPlan = nil
        downloads.onlyQuality = [9001: .original, 9002: .p720]
        await model.load()
        #expect(model.options.isEmpty && !model.canStart)
    }

    @Test func oversizedEstimatesCannotOverflowTheStorageCheck() async {
        let downloads = BatchDownloads()
        downloads.optionSize = Int64.max
        let model = model(downloads)
        await model.load()
        #expect(model.chosen?.sizeBytes == Int64.max)
        #expect(!model.canStart)
        #expect(!DownloadStorage(usedBytes: 0, freeBytes: Int64.max, totalBytes: Int64.max).fits(Int64.max))
    }
}

@MainActor
@Suite struct OfflinePlayerChoicesTests {
    @Test func theCopyHasNoStreamingQualityControlsOrEffectiveQualityAction() async {
        let h = PlayerHarness()
        h.spy.state.isOffline = true
        h.spy.state.quality = .auto
        await h.time.settle()
        #expect(h.model.pickerTabs == [.audio, .subtitles])
        #expect(h.model.pickerTitle == "Audio and subtitles")
        h.model.openPicker(.quality)
        #expect(h.model.picker == nil)
        h.model.openPicker(.audio)
        h.model.selectTab(.quality)
        #expect(h.model.picker == .audio)
        h.model.pickQuality(.hd)
        #expect(!h.spy.calls.contains("quality 720"))
        #expect(autoChip(h.spy.state) == nil)
        #expect(streamLine(h.spy.state) == "Downloaded")
        #expect(!pickerFootnote(device: "iPhone", offline: true).contains("quality"))
    }
}

private func season() -> SeasonDetail {
    SeasonDetail(showID: 100, showTitle: "Harbor Lights", season: SeasonSummary(id: 1, number: 1, name: "Season 1", episodeCount: 4, availableCount: 3, watchedCount: 0), episodes: (1...4).map {
        EpisodeDetail(id: 100000 + $0, season: 1, number: $0, title: "Episode \($0)", hasFile: $0 < 4, aired: true,
                      versions: $0 < 4 ? [MediaVersion(fileID: 9000 + $0, label: "Episode \($0)", sizeBytes: 500)] : [])
    })
}

private func copy(_ number: Int, show: Int = 100, season: Int = 1, file: Int? = nil, status: DownloadStatus = .downloaded) -> OfflineDownload {
    OfflineDownload(id: "held-\(show)-\(season)-\(number)", fileID: file ?? 9000 + number,
                    item: OfflineItem(kind: .episode, title: "Episode \(number)", showID: show, episodeID: show * 1000 + number,
                                      showTitle: "Harbor Lights", season: season, episode: number),
                    quality: .original, mode: .direct, protocol: .file, sizeBytes: 500, sizeEstimated: false,
                    status: status, durationMs: 600_000)
}

@MainActor
@Observable
private final class BatchDownloads: DownloadRepository {
    var downloads: [OfflineDownload] = []
    var canDownload = true
    var enabled: Bool { canDownload || !downloads.isEmpty }
    var settings = DownloadSettings()
    var freeBytes = 10 * DownloadStorage.reserveBytes
    var optionSize: Int64 = 500
    var onlyQuality: [Int: DownloadQuality] = [:]
    var failFile: Int?
    var failPlan: Int?
    private(set) var planCalls: [Int] = []
    private(set) var choices: [DownloadChoice] = []
    private(set) var marks: [(fileID: Int, watched: Bool)] = []
    @ObservationIgnored var planHook: ((Int) async -> Void)?
    @ObservationIgnored var downloadHook: ((Int) async -> Void)?

    func plan(fileID: Int) async throws -> DownloadPlan {
        planCalls.append(fileID)
        if let planHook { await planHook(fileID) }
        if failPlan == fileID { throw APIError(status: nil, message: "Plan unavailable") }
        let options = [DownloadOption(quality: .original, mode: .direct, protocol: .file, sizeBytes: optionSize, estimated: false),
                       DownloadOption(quality: .p720, mode: .transcode, protocol: .hls, sizeBytes: optionSize / 5, estimated: true)]
        return DownloadPlan(fileID: fileID, durationMs: 600_000, options: options.filter { onlyQuality[fileID] == nil || $0.quality == onlyQuality[fileID] })
    }

    func download(_ choice: DownloadChoice) async throws {
        if choice.fileID == failFile { throw APIError(status: nil, message: "Server unavailable") }
        choices.append(choice)
        var d = copy(choice.fileID - 9000, status: .waiting(.turn))
        d.quality = choice.quality
        downloads.append(d)
        if let downloadHook { await downloadHook(choice.fileID) }
    }
    func pause(_ id: String) {}
    func resume(_ id: String) {}
    func retry(_ id: String) {}
    func remove(_ id: String) { downloads.removeAll { $0.id == id } }
    func removeAll() { downloads = [] }
    func storage() -> DownloadStorage { DownloadStorage(usedBytes: 0, freeBytes: freeBytes, totalBytes: freeBytes) }
    func setQuality(_ quality: DownloadQuality) { settings.quality = quality }
    func setWifiOnly(_ wifiOnly: Bool) { settings.wifiOnly = wifiOnly }
    func mark(fileID: Int, watched: Bool) {
        marks.append((fileID, watched))
        if let index = downloads.firstIndex(where: { $0.fileID == fileID }) {
            downloads[index].watch = watched ? WatchProgress(positionMs: 0, durationMs: 600_000, completed: true) : nil
        }
    }
    func sync() async {}
}
