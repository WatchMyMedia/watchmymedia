import AVFoundation
import Foundation
import Observation

/// A live channel target has no file ID, resume position, history or episode context.
public struct LivePlayRequest: Hashable, Sendable, Identifiable {
    public var channel: LiveChannel
    public var quality: LiveQuality
    public init(channel: LiveChannel, quality: LiveQuality = .p1080) { self.channel = channel; self.quality = quality }
    public var id: String { channel.id }
}

public enum LivePlayerStatus: String, Hashable, Sendable { case idle, tuning, playing, paused, buffering, stopped, error }

public struct LivePlayerState: Hashable, Sendable {
    public var status: LivePlayerStatus = .idle
    public var channel: LiveChannel?
    public var quality: LiveQuality = .p1080
    public var qualities: [LiveQuality] = LiveQuality.fixed
    public var actualQuality: LiveQuality?
    public var actualOutput: String?
    public var switchStatus: String?
    public var qualityLabel: String {
        guard quality == .auto else { return quality.title }
        return "Auto · \(actualOutput == "original" ? "Original" : actualQuality?.title ?? "Starting")"
    }
    public var switchMessage: String? {
        switch switchStatus {
        case "preparing": "Preparing another quality while this channel keeps playing."
        case "waiting_capacity": "Keeping this quality until the server has room to improve it."
        case "source_wait": "Waiting for new broadcast segments."
        case "failed": "Keeping the current quality after a switch could not finish."
        default: nil
        }
    }
    public var isAtLiveEdge = true
    public var askStillWatching = false
    public var stillWatchingSeconds = 60
    public var pauseRemainingSeconds = 300
    public var bufferSeconds = 60
    public var cellular = false
    public var message: String?
    public var error: String?
    public var takeover: LiveRecordingTakeover?
    /// Why the server ended the session, such as `recording` when a recording took its tuner.
    public var stopReason: String?
    public init() {}

    /// The recording that took this tuner, once it has (reason `recording`), for Watch it from the start.
    public var stoppedForRecording: LiveRecordingTakeover? {
        status == .stopped && stopReason == "recording" ? takeover : nil
    }
    /// The stopped screen's title: "Stopped for a recording", "Can't play this channel" or "Live TV stopped".
    public var stoppedTitle: String {
        if status == .stopped && stopReason == "recording" { return "Stopped for a recording" }
        return status == .error ? "Can't play this channel" : "Live TV stopped"
    }
    /// Under it: "Inspector Vane is recording and needed this tuner." once a recording
    /// took it, else the error or the reason's words.
    public var stoppedMessage: String {
        if let taken = stoppedForRecording { return "\(taken.title) is recording and needed this tuner." }
        return error ?? message ?? "The tuner has been released."
    }
}

@MainActor
public protocol LivePlaybackController: AnyObject, Observable {
    var state: LivePlayerState { get }
    var player: AVPlayer? { get }
    func start(_ request: LivePlayRequest)
    func play()
    func pause()
    func togglePlayPause()
    func goLive()
    func selectQuality(_ quality: LiveQuality)
    func noteUserInput()
    func answerStillWatching(keepWatching: Bool)
    /// Ends playback immediately and releases the original owner's tuner asynchronously.
    func stop()
    func release()
}

@MainActor
public protocol LivePlaybackControllerFactory: AnyObject {
    func make() -> any LivePlaybackController
}
