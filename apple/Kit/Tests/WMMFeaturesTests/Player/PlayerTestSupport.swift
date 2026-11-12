import AVFoundation
import Foundation
import Observation
@testable import WMMFeatures
import WMMModel

/// A clock the test moves by hand: `sleep` waits until `advance` passes its deadline.
@MainActor
final class ManualTime {
    private(set) var nowMs = 0
    private var waiters: [(id: Int, at: Int, continuation: CheckedContinuation<Void, any Error>)] = []
    private var nextID = 0

    func sleep(_ duration: Duration) async throws {
        let (seconds, attoseconds) = duration.components
        let at = nowMs + Int(seconds) * 1000 + Int(attoseconds / 1_000_000_000_000_000)
        let id = nextID
        nextID += 1
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, any Error>) in
                if Task.isCancelled {
                    c.resume(throwing: CancellationError())
                } else {
                    waiters.append((id, at, c))
                }
            }
        } onCancel: {
            Task { @MainActor in self.cancel(id) }
        }
    }

    /// Moves time on, waking each sleeper whose deadline passes, earliest first.
    func advance(byMs ms: Int) async {
        let target = nowMs + ms
        await settle()
        while let next = waiters.filter({ $0.at <= target }).min(by: { $0.at < $1.at }) {
            nowMs = max(nowMs, next.at)
            waiters.removeAll { $0.id == next.id }
            next.continuation.resume()
            await settle()
        }
        nowMs = target
        await settle()
    }

    /// Lets tasks the model started run.
    func settle() async {
        for _ in 0..<30 { await Task.yield() }
    }

    private func cancel(_ id: Int) {
        guard let i = waiters.firstIndex(where: { $0.id == id }) else { return }
        waiters.remove(at: i).continuation.resume(throwing: CancellationError())
    }
}

/// A controller that only records what it is asked, with a state the test sets.
@MainActor
@Observable
final class SpyPlaybackController: PlaybackController {
    var state = PlayerState()
    let events: AsyncStream<PlayerEvent>
    var player: AVPlayer? { nil }
    private(set) var calls: [String] = []
    private(set) var inputs = 0
    @ObservationIgnored private let continuation: AsyncStream<PlayerEvent>.Continuation

    init() {
        (events, continuation) = AsyncStream.makeStream(of: PlayerEvent.self)
        state.durationMs = 52 * 60_000
        state.positionMs = 50_000
    }

    func send(_ event: PlayerEvent) { continuation.yield(event) }

    func start(_ request: PlayRequest) { calls.append("start \(request.fileID)") }
    func play() {
        calls.append("play")
        state.status = .playing
    }
    func pause() {
        calls.append("pause")
        state.status = .paused
    }
    func togglePlayPause() { state.status == .playing ? pause() : play() }
    func seek(toMs positionMs: Int) {
        calls.append("seek \(positionMs)")
        state.positionMs = min(max(positionMs, 0), state.durationMs)
    }
    func seek(byMs deltaMs: Int) {
        calls.append("seekBy \(deltaMs)")
        state.positionMs = min(max(state.positionMs + deltaMs, 0), state.durationMs)
    }
    func noteSeek(playerMs: Int) { calls.append("noteSeek \(playerMs)") }
    func skip() { calls.append("skip") }
    func playNext() { calls.append("playNext") }
    func watchCredits() { calls.append("watchCredits") }
    func answerStillWatching(keepWatching: Bool) { calls.append("stillWatching \(keepWatching)") }
    func selectAudio(_ index: Int) { calls.append("audio \(index)") }
    func selectSubtitle(_ id: String?) { calls.append("subtitle \(id ?? "off")") }
    func downloadSubtitle(_ resultID: String) { calls.append("download \(resultID)") }
    func selectQuality(_ quality: StreamQuality) { calls.append("quality \(quality.rawValue)") }
    func noteUserInput() { inputs += 1 }
    func stop() { calls.append("stop") }
    func close() { calls.append("close") }
    func release() {
        calls.append("release")
        continuation.finish()
    }
}

/// A model on a spy and a manual clock, started.
@MainActor
struct PlayerHarness {
    let time = ManualTime()
    let spy = SpyPlaybackController()
    let model: PlayerChromeModel
    let exits: Counter

    init(status: PlayerStatus = .playing, thumbnails: SeekThumbnails? = nil) {
        let time = time
        let exits = Counter()
        self.exits = exits
        model = PlayerChromeModel(
            controller: spy,
            request: PlayRequest(fileID: 9101, title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark"),
            thumbnails: thumbnails,
            onExit: { exits.count += 1 },
            now: { time.nowMs },
            sleep: { try await time.sleep($0) }
        )
        spy.state.status = status
        model.start()
    }
}

@MainActor
final class Counter {
    var count = 0
}
