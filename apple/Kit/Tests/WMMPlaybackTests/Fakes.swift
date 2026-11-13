import AVFoundation
import Foundation
import Observation
@testable import WMMPlayback
import WMMModel

// The controller on fakes and virtual time, as android/core/playback's Fakes.kt.

/// Virtual time: timers fire only when a test moves the clock on.
@MainActor
final class TestClock: PlaybackClock {
    static let wallStart = Date(timeIntervalSince1970: 1_800_000_000)

    private(set) var elapsedMs = 0
    private var timers: [(id: Int, due: Int, action: @MainActor () -> Void)] = []
    private var count = 0

    var now: Date { Self.wallStart.addingTimeInterval(Double(elapsedMs) / 1000) }

    func after(ms: Int, _ action: @escaping @MainActor () -> Void) -> TimerHandle {
        count += 1
        let id = count
        timers.append((id, elapsedMs + ms, action))
        return TimerHandle { [weak self] in self?.timers.removeAll { $0.id == id } }
    }

    /// Moves time on by `ms`, firing each timer due on the way at its own time, and letting the work it starts finish after each.
    func advance(by ms: Int, settle: () async -> Void) async {
        let target = elapsedMs + ms
        while let next = timers.filter({ $0.due <= target }).min(by: { ($0.due, $0.id) < ($1.due, $1.id) }) {
            timers.removeAll { $0.id == next.id }
            elapsedMs = next.due
            next.action()
            await settle()
        }
        elapsedMs = target
        await settle()
    }
}

@MainActor
final class FakeGateway: PlaybackGateway {
    private let clock: TestClock
    var decisions: [DecisionRequest] = []
    var answer: (DecisionRequest) throws -> PlaybackDecision = { _ in throw APIError(status: nil, message: "no decision set") }
    var reports: [ProgressReport] = []
    var reply: (ProgressReport) -> ProgressReply = { _ in ProgressReply(completed: false) }
    var tokenLifetimeMs = 6 * 3_600_000
    var tokens = 0
    var stoppedSessions: [String] = []
    /// What a subtitle search finds; nil is a server without a provider.
    var online: OnlineSubtitles?
    var searches: [Int] = []
    var downloads: [String] = []
    var download: (String) throws -> String = { _ in throw APIError(status: nil, message: "no download set") }
    var automaticRequests: [(Int, String)] = []
    var automaticCancellations: [(Int, String)] = []
    var automatic: (Int, String) async throws -> DecisionSubtitle? = { _, _ in nil }
    /// Subtitle files by path; the URLs asked for, with their tokens.
    var subtitleFiles: [String: String] = [:]
    var subtitleFetches: [URL] = []
    var planRequests: [(PlaybackProfile, StreamQuality, [Int])] = []
    /// While true, reports wait in ``reportProgress(_:)`` (a server that can't be reached) until ``releaseReports()``.
    var holdReports = false
    private var heldReports: [CheckedContinuation<Void, Never>] = []

    init(clock: TestClock) {
        self.clock = clock
    }

    func decide(_ request: DecisionRequest) async throws -> PlaybackDecision {
        decisions.append(request)
        return try answer(request)
    }

    func reportProgress(_ report: ProgressReport) async throws -> ProgressReply {
        reports.append(report)
        if holdReports { await withCheckedContinuation { heldReports.append($0) } }
        return reply(report)
    }

    func releaseReports() {
        holdReports = false
        let held = heldReports
        heldReports = []
        for c in held { c.resume() }
    }

    func mediaToken() async throws -> MediaToken {
        tokens += 1
        return MediaToken(token: "tok\(tokens)", expiresAt: clock.now.addingTimeInterval(Double(tokenLifetimeMs) / 1000))
    }

    func stopSession(_ sessionID: String) async throws {
        stoppedSessions.append(sessionID)
    }

    func plans(profile: PlaybackProfile, quality: StreamQuality, fileIDs: [Int]) async throws -> [PlaybackPlan] {
        planRequests.append((profile, quality, fileIDs))
        return fileIDs.map { PlaybackPlan(fileID: $0, mode: .direct) }
    }

    func searchSubtitles(fileID: Int) async throws -> OnlineSubtitles? {
        searches.append(fileID)
        return online
    }

    func downloadSubtitle(fileID: Int, resultID: String) async throws -> String {
        downloads.append(resultID)
        return try download(resultID)
    }

    func automaticSubtitle(fileID: Int, playID: String) async throws -> DecisionSubtitle? {
        automaticRequests.append((fileID, playID))
        return try await automatic(fileID, playID)
    }

    func cancelAutomaticSubtitle(fileID: Int, playID: String) async throws {
        automaticCancellations.append((fileID, playID))
    }

    func subtitleText(_ url: URL) async throws -> String {
        subtitleFetches.append(url)
        guard let text = subtitleFiles[url.path] else { throw APIError(status: 404, message: "Not found.") }
        return text
    }
}

/// A player whose position runs with virtual time while it is ready and
/// playing. Tests move it between phases with ``buffer()``, ``ready()`` and ``end()``.
@MainActor
final class FakeMediaPlayer: MediaPlayer {
    private let clock: TestClock
    private var listener: MediaPlayerListener?
    var loads: [PlayerSource] = []
    var seeks: [Int] = []
    var audioOrdinal: Int?
    var playsAudio: (Int) -> Bool = { _ in true }
    var bufferAheadMs = 30_000
    var readyOnLoad = true
    var throughputBps: Int?
    var transfer: MediaTransfer?
    var autoFrame = true
    var released = false
    private(set) var externalPlaybackActive = false
    private(set) var phase = PlayerPhase.idle
    private(set) var playWhenReady = false
    private var anchorPos = 0
    private var anchorAt = 0

    init(clock: TestClock) {
        self.clock = clock
    }

    var avPlayer: AVPlayer? { nil }

    var positionMs: Int { phase == .ready && playWhenReady ? anchorPos + (clock.elapsedMs - anchorAt) : anchorPos }

    var bufferedPositionMs: Int { positionMs + bufferAheadMs }

    private func change(_ block: () -> Void) {
        anchorPos = positionMs
        anchorAt = clock.elapsedMs
        block()
        listener?.changed()
    }

    func setListener(_ listener: MediaPlayerListener?) { self.listener = listener }

    func load(_ source: PlayerSource) {
        loads.append(source)
        change {
            anchorPos = source.startPositionMs
            playWhenReady = source.playWhenReady
            phase = readyOnLoad ? .ready : .buffering
        }
        if autoFrame && phase == .ready && playWhenReady { listener?.firstFrame() }
    }

    func play() { change { playWhenReady = true } }

    func pause() { change { playWhenReady = false } }

    func seek(toMs positionMs: Int) {
        seeks.append(positionMs)
        change {
            anchorPos = positionMs
            if phase == .ended { phase = .ready }
        }
    }

    func selectAudio(ordinal: Int, of count: Int) -> Bool {
        guard playsAudio(ordinal) else { return false }
        audioOrdinal = ordinal
        return true
    }

    func stop() { change { phase = .idle } }

    func release() { released = true }

    func buffer() { change { phase = .buffering } }

    func ready() {
        change { phase = .ready }
        if autoFrame && playWhenReady { listener?.firstFrame() }
    }

    func firstFrame() { listener?.firstFrame() }

    func end() { change { phase = .ended } }

    /// Something else moved the player (the tvOS transport bar seeks the AVPlayer itself): not one of ``seeks``.
    func jump(toMs positionMs: Int) {
        change {
            anchorPos = positionMs
            if phase == .ended { phase = .ready }
        }
    }

    /// The video goes to an Apple TV over AirPlay, or comes back.
    func airPlay(_ on: Bool) { change { externalPlaybackActive = on } }

    func fail(_ failure: PlayerFailure) { listener?.failed(failure) }

    /// The file's audio options didn't line up with the source's ``PlayerSource/audio``.
    func unmatchAudio() { listener?.audioUnmatched() }

    /// The 10 Hz time callback.
    func tick() { listener?.time() }
}

@MainActor
@Observable
final class TestPreferences: PreferencesRepository {
    var playback: PlaybackPreferences
    var quality: StreamQuality = .original
    var refreshes = 0
    var refreshAction: (() async -> Void)?

    init(_ playback: PlaybackPreferences = PlaybackPreferences()) {
        self.playback = playback
    }

    func refresh() async throws { refreshes += 1; await refreshAction?() }

    func setSkip(_ kind: SegmentKind, mode: SkipMode) async throws { playback.skip[kind] = mode }

    func setAutoplayNext(_ enabled: Bool) async throws { playback.autoplayNext = enabled }

    func setQuality(_ quality: StreamQuality) { self.quality = quality }
}

/// Records what the player tells the system, and stands in for it with ``commands``.
@MainActor
final class FakeSystem: PlaybackSystem {
    private(set) var commands: SystemCommands?
    private(set) var opened = 0
    private(set) var closed = 0
    private(set) var nowPlaying: [NowPlayingInfo] = []
    private(set) var holds = 0
    private(set) var released = 0

    func open(_ commands: SystemCommands) -> any SystemSession {
        self.commands = commands
        opened += 1
        return Session(system: self)
    }

    func holdForDelivery() -> @MainActor () -> Void {
        holds += 1
        return { [weak self] in self?.released += 1 }
    }

    final class Session: SystemSession {
        weak var system: FakeSystem?

        init(system: FakeSystem) { self.system = system }

        func update(_ info: NowPlayingInfo) {
            if system?.nowPlaying.last != info { system?.nowPlaying.append(info) }
        }

        func close() { system?.closed += 1 }
    }
}

@MainActor
final class RecordingLookups: PlaybackLookups {
    var named: PlayRequest?
    var described: (UpNext) throws -> UpNext = { $0 }
    private(set) var names: [Int] = []
    /// Image bytes by path; a path not here fails to load.
    var pictures: [String: Data] = [:]
    /// Called as an image loads, before it answers.
    var loading: (ArtworkImage) -> Void = { _ in }
    private(set) var images: [ArtworkImage] = []

    init(named: PlayRequest? = nil, described: @escaping (UpNext) throws -> UpNext = { $0 }) {
        self.named = named
        self.described = described
    }

    func name(_ request: PlayRequest) async throws -> PlayRequest? {
        names.append(request.fileID)
        return named
    }

    func describe(_ next: UpNext) async throws -> UpNext { try described(next) }

    func artwork(_ image: ArtworkImage) async throws -> Data? {
        images.append(image)
        loading(image)
        guard let data = pictures[image.path] else { throw URLError(.fileDoesNotExist) }
        return data
    }
}

/// The server in use and its LAN address, as ``ServerRepository/localAddress()`` finds it.
@MainActor
@Observable
final class TestServers: ServerRepository {
    var discovered: [DiscoveredServer] = []
    var saved: [SavedServer] = []
    var current: SavedServer?
    var localNetworkDenied = false
    /// What a look for the LAN address finds.
    var local: URL?
    private(set) var lookups = 0

    init(baseURL: URL, local: URL?, apiLevel: Int = 2) {
        current = SavedServer(server: Server(id: "srv-1", name: "Home", baseURL: baseURL, version: "1.0", apiLevel: apiLevel), userName: "sam", signedIn: true, lastUsedAt: .now)
        self.local = local
    }

    func startDiscovery() {}
    func stopDiscovery() {}
    func probe(_ address: String) async throws -> Server { throw APIError(status: nil, message: "not in these tests") }
    func select(_ server: Server) async throws {}
    func forget(serverID: String) async {}
    func refreshCurrent() async {}

    func localAddress() async -> URL? {
        lookups += 1
        return local
    }
}

let client = ClientInfo(deviceID: "device-1", name: "Sam’s iPhone", product: "WatchMyMedia for iOS", platform: "ios", version: "1.0", model: "iPhone 17 Pro")

let phoneProfile = PlaybackProfile(
    name: "ios",
    containers: ["mp4", "mov"],
    videoCodecs: ["h264", "hevc", "hevc10"],
    audioCodecs: ["aac", "mp3", "flac", "alac", "ac3", "eac3"],
    maxWidth: 3840,
    maxHeight: 2160,
    maxAudioChannels: 2,
    hdr: true,
    selectsAudioTrack: true
)

let minute = 60_000

let nextEpisode = UpNext(fileID: 2, episodeID: 12, showID: 100, showTitle: "Harbor Lights", season: 3, episode: 8, title: "Squall Line")

let english = AudioOption(index: 1, label: "English · EAC3 5.1 (Default)", language: "en", isDefault: true)
let commentary = AudioOption(index: 2, label: "English · AAC Stereo · Commentary", language: "en", isDefault: false)

/// Copper Valley's tracks (deploy/fixture/fixture.sh): English, Spanish and an English commentary.
let valleyAudio = [
    AudioOption(index: 1, label: "English · AAC Stereo (Default)", language: "eng", isDefault: true, codec: "aac", channels: 2),
    AudioOption(index: 2, label: "Spanish · AAC Stereo", language: "spa", isDefault: false, codec: "aac", channels: 2),
    AudioOption(index: 3, label: "English · AAC Stereo · Commentary", language: "eng", isDefault: false, codec: "aac", channels: 2, title: "Commentary"),
]

func url(_ string: String) -> URL { URL(string: string)! }

func textSub(_ id: String, _ format: String = "webvtt", url: String? = nil) -> DecisionSubtitle {
    DecisionSubtitle(
        option: SubtitleOption(id: id, label: "Sub \(id)", language: "en", burnInOnly: format == "image"),
        format: format,
        url: format == "image" ? nil : URL(string: url ?? "http://srv/api/files/1/subtitles/\(id).vtt")
    )
}

/// A 50 minute file in a VOD playlist unless told otherwise.
func decision(
    playID: String = "a1",
    sessionID: String? = "s1",
    protocol proto: WMMModel.StreamProtocol = .hls,
    vod: Bool = true,
    url: URL? = nil,
    startMs: Int = 0,
    durationMs: Int = 50 * minute,
    resumeMs: Int = 0,
    segments: [Segment] = [],
    creditsMs: Int = 0,
    watchedAtMs: Int = 46 * minute,
    upNextMs: Int = 0,
    next: UpNext? = nil,
    subtitles: [DecisionSubtitle] = [],
    burnInSubtitleID: String? = nil,
    audio: [AudioOption] = [english, commentary],
    selectedAudio: Int? = 1,
    mode: PlaybackMode? = nil,
    video: StreamVideo? = nil,
    autoStep: StreamQuality? = nil
) -> PlaybackDecision {
    let session = proto == .file ? nil : sessionID
    return PlaybackDecision(
        mode: mode ?? (proto == .file ? .direct : .remux),
        protocol: proto,
        vod: vod,
        url: url ?? URL(string: proto == .file ? "http://srv/api/files/1/stream?play=\(playID)" : "http://srv/api/playback/\(session ?? "")/master.m3u8")!,
        mimeType: proto == .hls ? "application/vnd.apple.mpegurl" : "video/mp4",
        playID: playID,
        sessionID: session,
        durationMs: durationMs,
        startMs: startMs,
        resumeMs: resumeMs,
        audio: audio,
        selectedAudio: selectedAudio,
        subtitles: subtitles,
        burnInSubtitleID: burnInSubtitleID,
        segments: segments,
        creditsMs: creditsMs,
        watchedAtMs: watchedAtMs,
        upNextMs: upNextMs,
        next: next,
        video: video,
        autoStep: autoStep
    )
}

/// A controller on fakes and virtual time.
@MainActor
final class Harness {
    let clock = TestClock()
    let gateway: FakeGateway
    let prefs: TestPreferences
    let player: FakeMediaPlayer
    let system = FakeSystem()
    let stillWatching: StillWatching
    let controller: RealPlaybackController
    private(set) var events: [PlayerEvent] = []
    private var listening: Task<Void, Never>?

    /// - Parameter quality: the device's saved quality, which the controller reads as it is made.
    init(preferences: PlaybackPreferences = PlaybackPreferences(), quality: StreamQuality = .original, lookups: any PlaybackLookups = NoLookups(), servers: (any ServerRepository)? = nil) {
        gateway = FakeGateway(clock: clock)
        prefs = TestPreferences(preferences)
        prefs.quality = quality
        let player = FakeMediaPlayer(clock: clock)
        self.player = player
        stillWatching = StillWatching(clock: clock)
        controller = RealPlaybackController(
            gateway: gateway,
            preferences: prefs,
            client: { client },
            profile: phoneProfile,
            makePlayer: { player },
            stillWatching: stillWatching,
            system: system,
            lookups: lookups,
            servers: servers,
            clock: clock
        )
        let events = controller.events
        listening = Task { [weak self] in
            for await event in events { self?.events.append(event) }
        }
    }

    var state: PlayerState { controller.state }

    var states: [PlayState] { gateway.reports.map(\.state) }

    /// Lets everything started so far finish, and the events reach ``events``.
    func run() async {
        await controller.settle()
        for _ in 0..<5 { await Task.yield() }
    }

    func advance(_ ms: Int) async {
        await clock.advance(by: ms) { await controller.settle() }
        for _ in 0..<5 { await Task.yield() }
    }

    /// Starts `request` with every decision request answered by `answer`.
    func start(_ request: PlayRequest = PlayRequest(fileID: 1, title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark"), answer: @escaping (DecisionRequest) throws -> PlaybackDecision) async {
        gateway.answer = answer
        controller.start(request)
        await run()
    }

    func start(_ request: PlayRequest, _ d: PlaybackDecision) async {
        await start(request) { _ in d }
    }

    /// Starts Harbor Lights S03 E07 with `d`.
    func start(_ d: PlaybackDecision) async {
        await start { _ in d }
    }

    func release() async {
        controller.release()
        await run()
        listening?.cancel()
    }
}
