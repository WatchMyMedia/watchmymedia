import AVFoundation
import Foundation
import Observation
import WMMModel

/// A controller whose first start fails, as when the server cannot start the
/// stream; Try again plays. The apps' `-playerError` launch argument wraps the
/// fake player in one, for screenshots of the can't-play state.
@MainActor
@Observable
public final class FailingFirstController: PlaybackController {
    public let base: any PlaybackController
    private var starts = 0

    public init(base: any PlaybackController) { self.base = base }

    public var state: PlayerState {
        var s = base.state
        if starts == 1 {
            s.status = .error
            s.error = "The server couldn’t start the stream: ffmpeg exited while remuxing this file. Try again, pick a lower quality, or check the server’s log."
        }
        return s
    }

    public var events: AsyncStream<PlayerEvent> { base.events }
    public var player: AVPlayer? { base.player }

    public func start(_ request: PlayRequest) {
        starts += 1
        base.start(request)
    }

    public func play() { base.play() }
    public func pause() { base.pause() }
    public func togglePlayPause() { base.togglePlayPause() }
    public func seek(toMs positionMs: Int) { base.seek(toMs: positionMs) }
    public func seek(byMs deltaMs: Int) { base.seek(byMs: deltaMs) }
    public func noteSeek(playerMs: Int) { base.noteSeek(playerMs: playerMs) }
    public func skip() { base.skip() }
    public func playNext() { base.playNext() }
    public func watchCredits() { base.watchCredits() }
    public func answerStillWatching(keepWatching: Bool) { base.answerStillWatching(keepWatching: keepWatching) }
    public func selectAudio(_ index: Int) { base.selectAudio(index) }
    public func selectSubtitle(_ id: String?) { base.selectSubtitle(id) }
    public func downloadSubtitle(_ resultID: String) { base.downloadSubtitle(resultID) }
    public func selectQuality(_ quality: StreamQuality) { base.selectQuality(quality) }
    public func noteUserInput() { base.noteUserInput() }
    public func stop() { base.stop() }
    public func close() { base.close() }
    public func release() { base.release() }
}
