import AVFoundation
import Foundation
import Observation
import WMMModel

public enum FakeAutomaticScene: String, Sendable {
    case slowed, recovered, failed
}

/// A pretend player for building the player screen without a server: a 52
/// minute episode with an intro from 1:00 to 1:45 and final credits from
/// 49:30, ticking in real time (or faster, with `speed`). It offers Skip
/// Intro, then the Next Episode card with its 10 s countdown, and shows a
/// few subtitle cues. No video: `player` stays nil, so the screen draws the
/// backdrop instead. A request without a title plays as Harbor Lights S03 E07.
/// Its waits go through `sleep`, so a test can pass a clock it moves by hand.
@MainActor
@Observable
public final class FakePlaybackController: PlaybackController {
    public private(set) var state = PlayerState()
    public let events: AsyncStream<PlayerEvent>
    public var player: AVPlayer? { nil }

    private let continuation: AsyncStream<PlayerEvent>.Continuation
    private let speed: Double
    private let tick: Duration
    private let sleep: @MainActor (Duration) async throws -> Void
    private let offlineFiles: @MainActor (Int) -> Bool
    private var durationMs = 52 * 60_000
    private let intro = Segment(kind: .intro, startMs: 60_000, endMs: 105_000)
    private var credits: Segment { Segment(kind: .credits, startMs: max(0, durationMs - 150_000), endMs: nil, final: true) }
    private let sampleCues = [
        SubtitleCue(startMs: 50_000, endMs: 53_000, text: "The channel markers were moved."),
        SubtitleCue(startMs: 54_000, endMs: 58_000, text: "Then someone wanted\nthe ferry on the bar."),
    ]
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var creditsDismissed = false

    public init(speed: Double = 1, tick: Duration = .milliseconds(250), sleep: (@MainActor (Duration) async throws -> Void)? = nil, offlineFiles: @escaping @MainActor (Int) -> Bool = { _ in false }) {
        self.speed = speed
        self.tick = tick
        self.offlineFiles = offlineFiles
        // Define this closure in the library, rather than copying an async
        // default argument into every caller (swiftlang/swift#92017).
        self.sleep = sleep ?? { try await Task.sleep(for: $0) }
        (events, continuation) = AsyncStream.makeStream(of: PlayerEvent.self)
    }

    public func start(_ request: PlayRequest) {
        let named = !request.title.isEmpty
        let resolved = FakeData.playRequest(fileID: request.fileID)
        let fileID = !named && resolved.title.isEmpty ? 9101 : request.fileID
        let episode = FakeData.playbackEpisode(fileID: fileID)
        durationMs = episode?.episode.versions.first?.durationMs ?? 52 * 60_000
        var s = PlayerState()
        s.isOffline = offlineFiles(fileID)
        s.status = .loading
        s.title = named ? request.title : resolved.title.isEmpty ? "Harbor Lights" : resolved.title
        s.subtitle = named ? request.subtitle : resolved.title.isEmpty ? "S03 E07 · The Long Dark" : resolved.subtitle
        s.backdropPath = request.backdropPath
        s.nextEpisode = FakeData.nextEpisode(afterFileID: fileID)
        s.positionMs = request.startMs ?? 50_000
        s.durationMs = durationMs
        if request.recordingID != nil {
            s.growing = .init(startedAt: .now.addingTimeInterval(-23 * 60), endsAt: .now.addingTimeInterval(37 * 60))
            s.durationMs = 23 * 60_000
            s.nextEpisode = nil
        }
        s.segments = [intro, credits]
        s.trickplay = FakeSeekSheets.trickplay(durationMs: durationMs)
        if s.isOffline {
            s.trickplay = nil
            s.nextEpisode = s.nextEpisode.flatMap { offlineFiles($0.fileID) ? $0 : nil }
        }
        s.audio = [
            AudioOption(index: 1, label: "English · EAC3 5.1 (Default)", language: "en", isDefault: true),
            AudioOption(index: 2, label: "English · AAC Stereo · Commentary", language: "en", isDefault: false),
            AudioOption(index: 3, label: "Spanish · AAC Stereo", language: "es", isDefault: false),
        ]
        s.selectedAudio = 1
        s.subtitles = [
            SubtitleOption(id: "s4", label: "English", language: "en", format: "SRT"),
            SubtitleOption(id: "s5", label: "English · SDH", language: "en", format: "SRT"),
            SubtitleOption(id: "s6", label: "Spanish", language: "es", format: "ASS"),
            SubtitleOption(id: "s7", label: "English", language: "en", burnInOnly: true, format: "PGS"),
        ]
        s.selectedSubtitle = request.subtitleID
        if s.isOffline { s.subtitles.removeAll(where: \.burnInOnly) }
        s.quality = .auto
        s.qualities = StreamQuality.allCases
        s.autoStep = .original
        s.mode = .remux
        s.reasons = ["client cannot play the mkv container"]
        s.video = StreamVideo(width: 3840, height: 2160, hdr: "HDR10")
        state = s
        creditsDismissed = false
        ticker?.cancel()
        let sleep = sleep
        ticker = Task { [weak self] in
            try? await sleep(.milliseconds(600))
            guard let self, !Task.isCancelled else { return }
            state.status = .playing
            // The online search answers once the video plays, as with a server.
            Task { [weak self] in
                try? await sleep(.milliseconds(800))
                if self?.state.isOffline == false { self?.state.online = FakeData.onlineSubtitles }
            }
            while !Task.isCancelled {
                try? await sleep(tick)
                if state.status == .playing { advance(by: Int(Double(tickMs) * speed)) }
                updatePrompts()
            }
        }
    }

    private var tickMs: Int { Int(tick.components.seconds * 1000) + Int(tick.components.attoseconds / 1_000_000_000_000_000) }

    private func advance(by ms: Int) {
        let pos = min(state.positionMs + ms, durationMs)
        state.positionMs = pos
        state.bufferedMs = 30_000
        if pos >= durationMs { state.status = .ended }
    }

    private func updatePrompts() {
        let pos = state.positionMs
        if let end = intro.endMs, pos >= intro.startMs, pos < end, end - pos >= 3_000 {
            state.skip = SkipPrompt(segment: intro, label: intro.kind.skipLabel)
        } else {
            state.skip = nil
        }
        state.cues = state.selectedSubtitle == nil ? [] : sampleCues.filter { pos >= $0.startMs && pos < $0.endMs }
        if let nextEpisode = state.nextEpisode, !creditsDismissed, pos >= credits.startMs {
            let previous = state.upNext?.countdownMs ?? upNextCountdownMs
            let countdown = state.status == .playing ? max(previous - Int(Double(tickMs) * speed), 0) : previous
            state.upNext = UpNextPrompt(next: nextEpisode, countdownMs: countdown)
        } else {
            state.upNext = nil
        }
        if state.upNext?.countdownMs == 0 { playNext() }
        if state.status == .ended && state.upNext == nil { continuation.yield(.exit) }
    }

    public func play() { state.status = .playing }

    public func pause() { state.status = .paused }

    public func togglePlayPause() { state.status == .playing ? pause() : play() }

    public func seek(toMs positionMs: Int) {
        if positionMs < credits.startMs { creditsDismissed = false }
        state.positionMs = min(max(positionMs, 0), durationMs)
        updatePrompts()
    }

    public func seek(byMs deltaMs: Int) { seek(toMs: state.positionMs + deltaMs) }

    public func noteSeek(playerMs: Int) {
        if playerMs < credits.startMs { creditsDismissed = false }
    }

    public func skip() {
        guard let end = state.skip?.segment.endMs else { return }
        seek(toMs: end)
    }

    public func playNext() {
        guard let n = state.nextEpisode else { return }
        continuation.yield(.startedNext(fileID: n.fileID))
        start(n.playRequest)
    }

    public func watchCredits() {
        creditsDismissed = true
        state.upNext = nil
    }

    public func answerStillWatching(keepWatching: Bool) {
        state.askStillWatching = false
        if !keepWatching { continuation.yield(.exit) }
    }

    public func selectAudio(_ index: Int) { state.selectedAudio = index }

    public func selectSubtitle(_ id: String?) {
        state.selectedSubtitle = id
        updatePrompts()
    }

    public func downloadSubtitle(_ resultID: String) {
        guard let online = state.online, let result = online.results.first(where: { $0.id == resultID }) else { return }
        if let track = result.trackID { return selectSubtitle(track) }
        guard online.downloading == nil else { return }
        state.online?.downloading = resultID
        state.online?.error = nil
        let sleep = sleep
        Task { [weak self] in
            try? await sleep(.milliseconds(1200))
            guard let self else { return }
            let id = "d\(resultID)"
            state.subtitles.append(SubtitleOption(id: id, label: result.label, language: result.language, forced: result.forced, format: "SRT", source: .online, release: result.release, hearingImpaired: result.hearingImpaired))
            state.online?.downloading = nil
            // Read before writing: an optional-chained assignment holds `state` while its right side runs.
            let results = (state.online?.results ?? []).map { r in
                var copy = r
                if r.id == resultID { copy.trackID = id }
                return copy
            }
            state.online?.results = results
            let remaining = state.online?.remaining.map { max($0 - 1, 0) }
            state.online?.remaining = remaining
            selectSubtitle(id)
        }
    }

    /// Automatic plays at 720p here, as if the connection were slow.
    public func selectQuality(_ quality: StreamQuality) {
        state.quality = quality
        state.autoStep = quality == .auto ? .hd : nil
        state.qualityNote = quality == .auto ? "Lowered to 720p for your connection." : nil
        state.mode = quality == .original ? .remux : .transcode
    }

    /// Stable previews of the negotiated quality states on each Apple player.
    public func previewAutomatic(_ scene: FakeAutomaticScene) {
        state.quality = .auto
        switch scene {
        case .slowed:
            state.autoStep = .hd
            state.mode = .transcode
            state.video = StreamVideo(width: 1280, height: 720, hdr: nil)
            state.reasons = ["connection slowed"]
            state.qualityNote = "Lowered to 720p for your connection."
        case .recovered:
            state.autoStep = .original
            state.mode = .direct
            state.video = StreamVideo(width: 3840, height: 2160, hdr: "HDR10")
            state.reasons = []
            state.qualityNote = "Connection recovered; playing the original file."
        case .failed:
            state.autoStep = .hd
            state.mode = .transcode
            state.video = StreamVideo(width: 1280, height: 720, hdr: nil)
            state.reasons = ["connection slowed"]
            state.qualityNote = "Still playing at 720p. Quality will retry later."
        }
    }

    public func noteUserInput() {}

    /// Shows the Still watching prompt, for previews and tests.
    public func askStillWatching() { state.askStillWatching = true }

    /// Shows an admin's stop message, for previews and tests.
    public func stopByAdmin(_ message: String) {
        state.stopMessage = message
        state.status = .paused
    }

    public func stop() { ticker?.cancel() }

    public func close() {}

    public func release() {
        stop()
        continuation.finish()
    }
}

@MainActor
public final class FakePlaybackFactory: PlaybackControllerFactory {
    private let offlineFiles: @MainActor (Int) -> Bool
    public init(offlineFiles: @escaping @MainActor (Int) -> Bool = { _ in false }) { self.offlineFiles = offlineFiles }

    public func make() -> any PlaybackController { FakePlaybackController(offlineFiles: offlineFiles) }

    public func plans(fileIDs: [Int]) async -> [Int: PlaybackPlan] {
        Dictionary(uniqueKeysWithValues: fileIDs.compactMap { FakeData.plan(fileID: $0) }.map { ($0.fileID, $0) })
    }
}

extension AppServices {
    /// All fakes together. `signedIn` false starts at sign-in; `chosen` false starts at the server picker.
    /// `client` is the device the fakes run as: ``ClientInfo/fakePhone``, ``ClientInfo/fakePad``
    /// (so an iPad reads "This iPad"), or ``ClientInfo/fakeAppleTV`` in the Apple TV app.
    /// `admin` false signs in as Alex, a member (`-fakesMember`): requests wait for an admin.
    /// `liveGuide` adds the guide boards' listings to Live TV (it implies `liveTV`).
    /// `downloads` gives the iPhone and iPad app its fake downloads; the Apple TV app has none.
    public static func fake(signedIn: Bool = true, chosen: Bool = true, client: ClientInfo = .fakePhone, admin: Bool = true, seerr: Bool = false, seerrLinked: Bool = true, liveTV: Bool = false, liveGuide: FakeLiveGuide? = nil, downloads: Bool = false, apiLevel: Int? = nil, recordings: Bool = false, liveAutomatic: Bool = false, rows: Bool = false) -> AppServices {
        let liveTV = liveTV || liveGuide != nil || liveAutomatic
        let servers = FakeServerRepository(signedIn: signedIn, chosen: chosen, admin: admin, liveTV: liveTV, liveGuide: liveGuide != nil,
                                           liveExtras: liveGuide?.extras ?? false, apiLevel: apiLevel ?? (rows ? ServerFeatures.Level.rows : nil))
        let library = FakeLibraryRepository()
        let rowsRepository = FakeRowsRepository(servers: servers, admin: admin)
        library.rows = rowsRepository
        let downloadRepository: any DownloadRepository = downloads ? FakeDownloadRepository() : UnavailableDownloads()
        let liveRepository = liveTV ? FakeLiveTVRepository(servers: servers, guide: liveGuide) : nil
        if liveAutomatic { liveRepository?.offered.automatic = true }
        if recordings { liveRepository?.offerRecordings(admin: admin) }
        return AppServices(
            servers: servers,
            auth: FakeAuthRepository(signedIn: signedIn, servers: servers, client: client, admin: admin),
            library: library,
            requests: FakeRequestsRepository(library: library, admin: admin, seerr: seerr, seerrLinked: seerrLinked),
            preferences: FakePreferencesRepository(),
            playback: FakePlaybackFactory(offlineFiles: { fileID in downloadRepository.downloads.contains { $0.fileID == fileID && $0.downloaded } }),
            images: FakeImageLoader(),
            client: client,
            liveTV: liveRepository,
            livePlayback: liveTV ? FakeLivePlaybackFactory(automatic: liveAutomatic && ServerFeatures(apiLevel: servers.current?.server.apiLevel).liveAutomatic) : nil,
            downloads: downloadRepository,
            rows: rowsRepository
        )
    }
}

extension ClientInfo {
    /// The iPhone the fakes run as.
    public static let fakePhone = ClientInfo(deviceID: "fake-device", name: "Sam’s iPhone", product: "WatchMyMedia for iOS", platform: "ios", version: "1.0", model: "iPhone 16")
    /// The iPad the fakes run as on an iPad.
    public static let fakePad = ClientInfo(deviceID: "fake-device", name: "Sam’s iPad", product: "WatchMyMedia for iOS", platform: "ios", version: "1.0", model: "iPad Pro 11″")
    /// The Apple TV the fakes run as in the Apple TV app.
    public static let fakeAppleTV = ClientInfo(deviceID: "fake-device", name: "Living room Apple TV", product: "WatchMyMedia for Apple TV", platform: "tvos", version: "1.0", model: "Apple TV 4K")
}
