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
    /// The person's sound settings and subtitle size, and the server's level: Sound and Subtitle timing show from API level 35.
    @ObservationIgnored private let preferences: (any PreferencesRepository)?
    @ObservationIgnored private let features: @MainActor () -> ServerFeatures
    @ObservationIgnored private let admin: Bool
    /// Each track's timing this play, and the one saved for everyone where there is one.
    @ObservationIgnored private var timings: [String: TrackTimingPreview] = [:]
    @ObservationIgnored private var soundBusy: SoundSetting?
    @ObservationIgnored private var soundBusyLevel: SoundLevel?
    @ObservationIgnored private var soundFailed: (SoundSetting, String)?
    @ObservationIgnored private var timingSave: Task<Void, Never>?

    private struct TrackTimingPreview {
        var ms = 0
        var shared = false
        var sharedMs: Int?
        var save: SubtitleTimingState.Save = .rest
        var savedForEveryone = false
    }

    public init(speed: Double = 1, tick: Duration = .milliseconds(250), sleep: (@MainActor (Duration) async throws -> Void)? = nil, offlineFiles: @escaping @MainActor (Int) -> Bool = { _ in false },
                preferences: (any PreferencesRepository)? = nil, features: @escaping @MainActor () -> ServerFeatures = { ServerFeatures(apiLevel: nil) }, admin: Bool = false) {
        self.speed = speed
        self.tick = tick
        self.offlineFiles = offlineFiles
        self.preferences = preferences
        self.features = features
        self.admin = admin
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
        refreshDialogue()
        // A track in the picture has no cues to draw; a text track's move by its timing.
        let timing = state.timing.flatMap { $0.burnedIn ? nil : $0.timingMs } ?? 0
        state.cues = state.selectedSubtitle == nil || state.timing?.burnedIn == true ? [] : sampleCues
            .filter { pos - timing >= $0.startMs && pos - timing < $0.endMs }
            .map { SubtitleCue(startMs: $0.startMs + timing, endMs: $0.endMs + timing, text: $0.text) }
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

    // MARK: Sound and subtitle timing

    /// The sound, the timing and the size as a server at API level 35 has them.
    private func refreshDialogue() {
        let on = features().dialogueAndSubtitles
        state.subtitleSize = on ? preferences?.playback.subtitles.drawnSize ?? .normal : .normal
        if on, var audio = preferences?.playback.audio {
            // The level being changed to shows from the press, as the real player's does.
            if let soundBusy, let soundBusyLevel { audio[soundBusy] = soundBusyLevel }
            state.sound = PlayerSound(dialogueBoost: audio.dialogueBoost, reduceLoudSounds: audio.reduceLoudSounds, busy: soundBusy,
                                      failed: soundFailed?.0, failure: soundFailed?.1, download: state.isOffline)
        } else {
            state.sound = nil
        }
        guard on, let id = state.selectedSubtitle, let track = state.subtitles.first(where: { $0.id == id }) else {
            state.timing = nil
            return
        }
        let t = timings[id] ?? TrackTimingPreview()
        state.timing = SubtitleTimingState(
            trackID: id, timingMs: t.ms, shared: t.shared, burnedIn: track.burnInOnly,
            canReset: state.isOffline ? t.ms != 0 : !t.shared && (t.ms != 0 || (t.sharedMs ?? 0) != 0),
            canSaveForEveryone: admin && !state.isOffline && !t.shared && t.ms != 0,
            savedForEveryone: t.savedForEveryone, save: state.isOffline ? .playOnly : t.save
        )
    }

    /// Saves, then plays on from here with the new sound, as a server's reload would.
    public func setSound(_ setting: SoundSetting, to level: SoundLevel) {
        guard let preferences, let sound = state.sound, sound.enabled, sound[setting] != level else { return }
        let before = sound[setting]
        soundBusy = setting
        soundBusyLevel = level
        soundFailed = nil
        refreshDialogue()
        let sleep = sleep
        Task { [weak self] in
            do {
                try await preferences.setLanguages(LanguageChange(setting, level))
                // A slow server's reload, long enough to see (and for a UI test to read).
                try? await sleep(.seconds(4))
            } catch {
                self?.soundFailed = (setting, SoundWords.changeFailed(error, still: before))
            }
            self?.soundBusy = nil
            self?.soundBusyLevel = nil
            self?.refreshDialogue()
        }
    }

    public func moveSubtitleTiming(byMs deltaMs: Int) {
        guard let timing = state.timing else { return }
        var t = timings[timing.trackID] ?? TrackTimingPreview()
        let next = min(max(t.ms + deltaMs, -SubtitleTimingState.limitMs), SubtitleTimingState.limitMs)
        if t.shared { t.sharedMs = t.ms }
        t.ms = next
        t.shared = false
        t.savedForEveryone = false
        if case .failed = t.save {} else { t.save = .rest }
        timings[timing.trackID] = t
        updatePrompts()
        guard !state.isOffline else { return }
        settleTiming(timing.trackID) { _ in }
    }

    public func resetSubtitleTiming() {
        guard let timing = state.timing, timing.canReset else { return }
        var t = timings[timing.trackID] ?? TrackTimingPreview()
        t.ms = state.isOffline ? 0 : t.sharedMs ?? 0
        t.shared = !state.isOffline && (t.sharedMs ?? 0) != 0
        t.savedForEveryone = false
        timings[timing.trackID] = t
        updatePrompts()
    }

    public func saveSubtitleTimingForEveryone() {
        guard let timing = state.timing, timing.canSaveForEveryone else { return }
        settleTiming(timing.trackID, after: .zero) { t in
            t.shared = true
            t.sharedMs = t.ms
            t.savedForEveryone = true
        }
    }

    /// A second after the last press (at once for Save for everyone), saved or refused (`-fakesSaveFails timing`).
    private func settleTiming(_ id: String, after wait: Duration = .seconds(1), _ saved: @escaping @MainActor (inout TrackTimingPreview) -> Void) {
        timingSave?.cancel()
        let sleep = sleep
        let refusal = (preferences as? FakePreferencesRepository)?.refusal
        timingSave = Task { [weak self] in
            guard (try? await sleep(wait)) != nil, !Task.isCancelled, let self, var t = timings[id] else { return }
            t.save = .saving
            timings[id] = t
            updatePrompts()
            try? await sleep(.milliseconds(400))
            guard !Task.isCancelled, var t = timings[id] else { return }
            if let refusal, refusal.refuses("timing") {
                t.save = .failed(TimingWords.saveFailed(refusal.error))
            } else {
                t.save = .rest
                saved(&t)
            }
            timings[id] = t
            updatePrompts()
        }
    }

    /// A track's timing as a play would start with it, for previews: the
    /// person's own, or one an admin saved for everyone (`shared`).
    public func previewTiming(_ ms: Int, shared: Bool = false) {
        guard let id = state.selectedSubtitle else { return }
        timings[id] = TrackTimingPreview(ms: ms, shared: shared, sharedMs: shared ? ms : nil)
        updatePrompts()
    }

    /// Shows the Still watching prompt, for previews and tests.
    public func askStillWatching() { state.askStillWatching = true }

    /// Shows an admin's stop message, for previews and tests.
    public func stopByAdmin(_ message: String) {
        state.stopMessage = message
        state.status = .paused
    }

    public func stop() {
        ticker?.cancel()
        timingSave?.cancel()
    }

    public func close() {}

    public func release() {
        stop()
        continuation.finish()
    }
}

@MainActor
public final class FakePlaybackFactory: PlaybackControllerFactory {
    private let offlineFiles: @MainActor (Int) -> Bool
    private let preferences: (any PreferencesRepository)?
    private let features: @MainActor () -> ServerFeatures
    private let admin: Bool

    public init(offlineFiles: @escaping @MainActor (Int) -> Bool = { _ in false }, preferences: (any PreferencesRepository)? = nil,
                features: @escaping @MainActor () -> ServerFeatures = { ServerFeatures(apiLevel: nil) }, admin: Bool = false) {
        self.offlineFiles = offlineFiles
        self.preferences = preferences
        self.features = features
        self.admin = admin
    }

    public func make() -> any PlaybackController {
        FakePlaybackController(offlineFiles: offlineFiles, preferences: preferences, features: features, admin: admin)
    }

    /// Timings the Cast remote saved: each person's own and the one for everyone, by file and track.
    private var ownTimings: [String: Int] = [:]
    private var sharedTimings: [String: Int] = [:]

    /// As a server does: refused with `-fakesSaveFails timing`, for everyone only for an admin.
    public func setSubtitleTiming(fileID: Int, trackID: String, timingMs: Int, everyone: Bool) async throws -> SavedTiming {
        await FakeLatency.wait()
        if let refusal = (preferences as? FakePreferencesRepository)?.refusal, refusal.refuses("timing") { throw refusal.error }
        let key = "\(fileID)/\(trackID)"
        if everyone {
            guard admin else { throw APIError(status: 403, message: "Only an admin can save a timing for everyone.") }
            sharedTimings[key] = timingMs == 0 ? nil : timingMs
            ownTimings[key] = nil
        } else {
            ownTimings[key] = timingMs
        }
        return timing(key)
    }

    public func deleteSubtitleTiming(fileID: Int, trackID: String) async throws -> SavedTiming {
        await FakeLatency.wait()
        if let refusal = (preferences as? FakePreferencesRepository)?.refusal, refusal.refuses("timing") { throw refusal.error }
        let key = "\(fileID)/\(trackID)"
        ownTimings[key] = nil
        return timing(key)
    }

    private func timing(_ key: String) -> SavedTiming {
        if let own = ownTimings[key] { return SavedTiming(timingMs: own, shared: false) }
        return SavedTiming(timingMs: sharedTimings[key] ?? 0, shared: sharedTimings[key] != nil)
    }

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
    /// `related` puts the server at API level 30: movie and show pages end in ``FakeRelated``'s rows.
    /// `watchlists` puts it at API level 33, with ``FakeWatchlistsRepository``'s three watchlists.
    /// `redesign` puts it at API level 34: the cast as people with their own
    /// pages, a card's counts, the library's totals and rating filter, and
    /// Remove from Continue watching. `dialogue` puts it at API level 35:
    /// Boost dialogue, Reduce loud sounds, Subtitle size and subtitle timing.
    public static func fake(signedIn: Bool = true, chosen: Bool = true, client: ClientInfo = .fakePhone, admin: Bool = true, seerr: Bool = false, seerrLinked: Bool = true, liveTV: Bool = false, liveGuide: FakeLiveGuide? = nil, downloads: Bool = false, apiLevel: Int? = nil, recordings: Bool = false, liveAutomatic: Bool = false, rows: Bool = false, follows: Bool = false, related: Bool = false, watchlists: Bool = false, several: Bool = false, redesign: Bool = false, dialogue: Bool = false) -> AppServices {
        let liveTV = liveTV || liveGuide != nil || liveAutomatic
        let level = dialogue ? ServerFeatures.Level.dialogueAndSubtitles : redesign ? ServerFeatures.Level.redesign : watchlists ? ServerFeatures.Level.watchlists : related ? ServerFeatures.Level.related : follows ? ServerFeatures.Level.follows : rows ? ServerFeatures.Level.rows : nil
        let servers = FakeServerRepository(signedIn: signedIn, chosen: chosen, admin: admin, liveTV: liveTV, liveGuide: liveGuide != nil,
                                           liveExtras: liveGuide?.extras ?? false,
                                           apiLevel: dialogue ? ServerFeatures.Level.dialogueAndSubtitles : redesign ? ServerFeatures.Level.redesign : apiLevel ?? level, several: several)
        let library = FakeLibraryRepository()
        library.servers = servers
        // Read here rather than passed, so both apps' fakes take it with no change to their factories.
        library.largeLibrary = ProcessInfo.processInfo.arguments.contains("-fakesLargeLibrary")
        let rowsRepository = FakeRowsRepository(servers: servers, admin: admin)
        library.rows = rowsRepository
        let followsRepository = FakeFollowsRepository(servers: servers)
        library.follows = followsRepository
        rowsRepository.follows = followsRepository
        let watchlistsRepository = FakeWatchlistsRepository(servers: servers)
        library.watchlists = watchlistsRepository
        rowsRepository.watchlists = watchlistsRepository
        let preferences = FakePreferencesRepository()
        let downloadRepository: any DownloadRepository = downloads ? FakeDownloadRepository(servers: servers) : UnavailableDownloads()
        let liveRepository = liveTV ? FakeLiveTVRepository(servers: servers, guide: liveGuide) : nil
        if liveAutomatic { liveRepository?.offered.automatic = true }
        if recordings { liveRepository?.offerRecordings(admin: admin) }
        let services = AppServices(
            servers: servers,
            auth: FakeAuthRepository(signedIn: signedIn, servers: servers, client: client, admin: admin),
            library: library,
            requests: FakeRequestsRepository(library: library, admin: admin, seerr: seerr, seerrLinked: seerrLinked),
            preferences: preferences,
            playback: FakePlaybackFactory(offlineFiles: { fileID in downloadRepository.downloads.contains { $0.fileID == fileID && $0.downloaded } },
                                          preferences: preferences, features: { [weak servers] in ServerFeatures(apiLevel: servers?.current?.server.apiLevel) }, admin: admin),
            images: FakeImageLoader(),
            client: client,
            liveTV: liveRepository,
            livePlayback: liveTV ? FakeLivePlaybackFactory(automatic: liveAutomatic && ServerFeatures(apiLevel: servers.current?.server.apiLevel).liveAutomatic) : nil,
            downloads: downloadRepository,
            rows: rowsRepository,
            follows: followsRepository,
            watchlists: watchlistsRepository
        )
        // `-fakesSaveFails` and `-fakesPasswordFails`, read here as `-fakesLargeLibrary` is.
        services.applyFakeRefusals(ProcessInfo.processInfo.arguments)
        // Another server signed in to: its own library, played from there (one card).
        services.makeScoped = { [weak services] serverID in
            guard let services, servers.signedIn(on: serverID), serverID != servers.current?.server.id else { return nil }
            return AppServices(servers: servers, auth: services.auth, library: library.scoped(to: serverID), requests: UnavailableRequests(),
                               preferences: services.preferences, playback: FakePlaybackFactory(), images: services.images, client: client, scope: serverID)
        }
        return services
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
