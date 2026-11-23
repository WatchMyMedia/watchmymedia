import Foundation
import WMMModel

/// A channel that can't play, on Apple TV: why, in the viewer's words, then
/// what can be done, the most useful first. Nothing names the tuner's make,
/// a session count or an address.
public struct TVLiveProblem: Hashable, Sendable {
    public enum Action: Hashable, Sendable {
        /// A recording that holds a tuner, or took this one, from its start.
        case watch(PlayRequest)
        /// Recordings, where one can be stopped.
        case stopARecording
        /// Tune this channel again.
        case tryAgain
        /// The other channels, over the picture.
        case otherChannels
        /// Leave for the guide.
        case backToGuide
    }

    public struct Row: Hashable, Sendable, Identifiable {
        public var title: String
        /// At the row's right: the channel a recording is on.
        public var value: String?
        public var action: Action
        public var id: String { title }
    }

    public var title: String
    public var why: String
    public var rows: [Row]

    /// The problem `state` shows, or nil while the channel plays (or only
    /// asks Still watching). `recordingNow` is what Recordings knows to be
    /// recording now; `otherChannels` is false when there is no other
    /// channel to offer.
    public init?(state: LivePlayerState, recordingNow: [LiveRecording], otherChannels: Bool, clock: LiveClock = .device) {
        let name = state.channel.map { "\($0.number) \($0.name)" } ?? "this channel"
        let others: [Row] = otherChannels ? [Row(title: "Other channels", action: .otherChannels)] : []
        let back = Row(title: "Back to the guide", action: .backToGuide)
        switch state.status {
        case .stopped:
            if let taken = state.stoppedForRecording {
                title = "Stopped for a recording"
                why = "The tuner was needed to record \(taken.title), as scheduled."
                rows = [Row(title: "Watch \(taken.title) from the start", action: .watch(PlayRequest(fileID: 0, startMs: 0, title: taken.title, recordingID: taken.recordingID)))] + others + [back]
            } else {
                title = state.stoppedTitle
                why = state.stoppedMessage
                rows = [Row(title: "Watch live", action: .tryAgain)] + others + [back]
            }
        case .error:
            switch state.stopReason {
            case "tuners_busy":
                title = "No tuner is free"
                let recording = recordingNow.filter { $0.state == .recording && $0.playable }
                if let end = recording.map(\.end).max() {
                    let names = liveListed(recording.map(\.title))
                    let until = clock.time(end)
                    why = recording.count == 1
                        ? "\(names) is recording until \(until) and has a tuner. You can watch it from its start, or stop it to free a tuner."
                        : "\(names) are recording until \(until). You can watch \(recording.count == 2 ? "either" : "any") from its start, or stop one to free a tuner."
                    rows = recording.map { r in
                        Row(title: "Watch \(r.title) from the start", value: [r.number, r.channelName].filter { !$0.isEmpty }.joined(separator: " "), action: .watch(r.playRequest(fromStart: true)))
                    }
                    if recording.contains(where: { $0.actions.cancel }) { rows.append(Row(title: "Stop a recording", action: .stopARecording)) }
                    rows.append(back)
                } else {
                    why = state.error ?? LiveTVError.message("tuners_busy")
                    rows = [Row(title: "Try again", action: .tryAgain)] + others + [back]
                }
            case "no_signal", "signal_lost", "stream_stalled":
                title = "No signal on \(name)"
                why = "The antenna isn’t picking this channel up right now. Other channels may be fine."
                rows = [Row(title: "Try again", action: .tryAgain)] + others + [back]
            default:
                title = "Can’t play \(name)"
                why = state.error ?? LiveTVError.message("")
                rows = [Row(title: "Try again", action: .tryAgain)] + others + [back]
            }
        default:
            return nil
        }
    }
}
