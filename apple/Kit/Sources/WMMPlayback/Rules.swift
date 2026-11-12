import Foundation
import WMMModel

// The numbers and rules of PLAN-CLIENTS.md §2.3, which are the web player's,
// as small pure functions (android/core/playback/.../Rules.kt).

/// Without an explicit start, resume only past this.
let resumeMinMs = 5_000

/// Heartbeats while playing, and while paused or buffering.
let beatPlayingMs = 10_000
let beatIdleMs = 30_000

/// At Automatic quality, heartbeats while playing or buffering, so the server hears of a slow link soon.
let beatAutoMs = 5_000

/// How long the note that Automatic lowered the quality stays over the video.
let qualityNoteMs = 5_000

/// The Skip button needs a segment this long; skipping by itself, 1 s.
let skipButtonMinMs = 3_000
let skipAutoMinMs = 1_000

/// A segment stops counting this close to its end, so a skip does not land in it again.
let segmentEndGuardMs = 500

/// How long auto-delay fills the Skip button before skipping.
let autoDelayMs = skipAutoDelayMs

/// A skip that lands this close to the end has reached the end.
let endSlackMs = 1_000

/// Refresh the media token this long before it runs out.
let tokenMarginMs = 60_000

/// However the clocks disagree, a token is trusted this long before a refresh.
let tokenMinLifetimeMs = 30 * 60_000

/// Event playlists: a seek this far past the buffer still plays in the session.
let eventSeekSlackMs = 500

/// Event playlists: a session started this close to where the viewer seeked meanwhile is close enough.
let eventStartToleranceMs = 15_000

/// The player's tick: state, prompts and heartbeats.
let tickMs = 250

/// Player failures retried at the current position before the error shows, and when the count resets.
let maxRecoveries = 2
let recoveryResetMs = 60_000

/// How the player's clock maps onto the file. VOD playlists and direct play
/// start at 0, so file position = player time; an event playlist starts at
/// the decision's `startMs`.
struct StreamClock: Hashable {
    var offsetMs: Int
    var event: Bool

    static let zero = StreamClock(offsetMs: 0, event: false)

    static func of(_ d: PlaybackDecision) -> StreamClock {
        let event = d.protocol == .hls && !d.vod
        return StreamClock(offsetMs: event ? d.startMs : 0, event: event)
    }

    func filePosition(_ playerMs: Int) -> Int { offsetMs + playerMs }

    func playerPosition(_ fileMs: Int) -> Int { max(fileMs - offsetMs, 0) }

    /// Whether a seek to `fileMs` stays in this session: always, except past an event playlist's buffer or before its start.
    func canSeekLocally(_ fileMs: Int, bufferedPlayerMs: Int) -> Bool {
        !event || (0...(bufferedPlayerMs + eventSeekSlackMs)).contains(fileMs - offsetMs)
    }
}

extension Segment {
    func end(or durationMs: Int) -> Int { endMs ?? durationMs }
}

/// The segment playing at `positionMs`: at least 1 s long, not in its last
/// half second, and not the final credits (the server's `final`, or the
/// credits at `creditsMs`) when the Next Episode card takes their place.
func activeSegment(_ d: PlaybackDecision, positionMs: Int, nextOffered: Bool) -> Segment? {
    d.segments.first { s in
        let end = s.end(or: d.durationMs)
        let finalCredits = s.kind == .credits && (s.final || (d.creditsMs > 0 && s.startMs == d.creditsMs))
        return end - s.startMs >= skipAutoMinMs && positionMs >= s.startMs && positionMs < end - segmentEndGuardMs && !(finalCredits && nextOffered)
    }
}

/// What the Skip control does for the playing segment.
enum SkipAction: Hashable {
    case none
    /// A Skip button the viewer presses.
    case button
    /// A Skip button that fills over 3 s, then skips.
    case autoDelay
    /// Skip at once, without a button.
    case autoNow
}

/// The viewer's setting for the segment's kind decides: a button needs 3 s of
/// segment, skipping by itself 1 s, and a segment the viewer seeked into is
/// never skipped by itself (it gets a plain button instead).
func skipAction(_ segment: Segment, durationMs: Int, mode: SkipMode, seekedInto: Bool) -> SkipAction {
    let long = segment.end(or: durationMs) - segment.startMs >= skipButtonMinMs
    if mode == .none { return .none }
    if mode == .auto && !seekedInto { return .autoNow }
    if !long { return .none }
    if mode == .autoDelay && !seekedInto { return .autoDelay }
    return .button
}

/// Whether the Next Episode card shows: from `upNextMs` unless the viewer
/// chose to watch the credits, and at the end whatever they chose.
func showsUpNext(offered: Bool, askingStillWatching: Bool, status: PlayerStatus, positionMs: Int, upNextMs: Int, creditsDismissed: Bool) -> Bool {
    offered && !askingStillWatching && status != .error && (status == .ended || (positionMs >= upNextMs && !creditsDismissed))
}

/// The Next Episode countdown after `dtMs`: 10 s when the card appears, then
/// counting down while playing, buffering and at the end, holding while
/// paused; nil without the card, or with autoplay off.
func upNextCountdown(_ current: Int?, showing: Bool, autoplay: Bool, status: PlayerStatus, dtMs: Int) -> Int? {
    guard showing, autoplay else { return nil }
    guard let current else { return upNextCountdownMs }
    return status == .paused ? current : max(current - dtMs, 0)
}

/// What a heartbeat reports for the player's status.
func playState(for status: PlayerStatus) -> PlayState {
    switch status {
    case .playing: .playing
    case .loading, .buffering: .buffering
    case .paused, .ended, .error: .paused
    }
}

/// A heartbeat goes out at every change of state, then every 10 s while
/// playing and every 30 s while paused or buffering; at Automatic quality,
/// every 5 s while playing or buffering.
func heartbeatDue(_ state: PlayState, lastReported: PlayState?, sinceLastBeatMs: Int, auto: Bool = false) -> Bool {
    let every = auto && (state == .playing || state == .buffering) ? beatAutoMs : state == .playing ? beatPlayingMs : beatIdleMs
    return state != lastReported || sinceLastBeatMs >= every
}

/// After a seek or a pause, buffering is the seek's or the resume's until the video has played this long.
let excuseMs = 1_000

/// A decision's start and its rebuffers, as heartbeats report them
/// (PLAN-AUTO-QUALITY.md §3.1). A rebuffer is buffering after the first
/// frame that no seek, reload or resume caused: a seek or a pause excuses
/// the buffering that follows, until the video has played on for ``excuseMs``.
struct StreamMeasure {
    /// When the decision was asked for, on the controller's clock.
    let requestedAt: Int
    /// From asking to the first frame; nil until then.
    private(set) var startupMs: Int?
    private(set) var stalls = 0
    private var stalledMs = 0
    private var stallStart: Int?
    /// The last seek, or the last moment paused, while it still excuses buffering.
    private var excusedAt: Int?
    private var startupSent = false

    init(requestedAt: Int) {
        self.requestedAt = requestedAt
    }

    /// Takes the status at `now`, with whether the new stream is ready to
    /// play; true when a stall began.
    mutating func observe(_ status: PlayerStatus, ready: Bool, at now: Int) -> Bool {
        guard startupMs != nil else {
            if ready { startupMs = max(now - requestedAt, 1) }
            return false
        }
        switch status {
        case .playing:
            if let at = excusedAt, now - at >= excuseMs { excusedAt = nil }
            endStall(at: now)
        case .buffering:
            guard stallStart == nil, excusedAt == nil else { return false }
            stalls += 1
            stallStart = now
            return true
        case .paused:
            excusedAt = now
            endStall(at: now)
        case .loading, .ended, .error:
            endStall(at: now)
        }
        return false
    }

    /// A seek at `now`: its buffering is not a stall.
    mutating func excuse(at now: Int) {
        excusedAt = now
        endStall(at: now)
    }

    /// Time spent in stalls, the one under way included.
    func stallMs(at now: Int) -> Int { stalledMs + (stallStart.map { now - $0 } ?? 0) }

    /// The startup time the first time it is asked for once known; nil after.
    mutating func takeStartup() -> Int? {
        guard let startupMs, !startupSent else { return nil }
        startupSent = true
        return startupMs
    }

    private mutating func endStall(at now: Int) {
        guard let start = stallStart else { return }
        stalledMs += now - start
        stallStart = nil
    }
}

/// The message for ``PlayerState/error``.
func errorMessage(_ failure: PlayerFailure) -> String {
    switch failure.kind {
    case .network: return "Lost the connection to the server."
    case .http:
        switch failure.httpStatus {
        case 401, 403: return "The server refused the stream. Try signing in again."
        case 404, 410: return "The server no longer has this stream."
        case nil: return "The server refused the stream."
        case let status?: return "The server answered with HTTP \(status)."
        }
    case .decoder: return "This device could not decode the stream."
    case .source: return "The stream could not be read."
    case .other: return "Playback failed (\(failure.detail))."
    }
}

extension PlayerFailure {
    /// Worth one more try at the current position: an expired token, a session the server dropped, a network hiccup.
    var recoverable: Bool {
        switch kind {
        case .network: true
        case .http: [401, 403, 404, 410].contains(httpStatus ?? 0)
        default: false
        }
    }
}

/// A picture for Now Playing: an image path at the size the screens load it
/// at, so the image loader's cache usually has it already.
public struct ArtworkImage: Hashable, Sendable {
    public var path: String
    public var size: ImageSize

    public init(path: String, size: ImageSize) {
        self.path = path
        self.size = size
    }

    /// "w300/abc.jpg": tells one picture from another in ``NowPlayingArtwork``.
    var id: String { size.rawValue + path }
}

/// Now Playing's picture for a play: the episode's still, else the backdrop, else the poster.
func nowPlayingImage(_ request: PlayRequest) -> ArtworkImage? {
    func given(_ path: String?) -> String? {
        guard let path, !path.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return path
    }
    if let still = given(request.stillPath) { return ArtworkImage(path: still, size: .w300) }
    if let backdrop = given(request.backdropPath) { return ArtworkImage(path: backdrop, size: .w1280) }
    if let poster = given(request.posterPath) { return ArtworkImage(path: poster, size: .w342) }
    return nil
}

/// Over AirPlay, the stream from the server's address on this network
/// rather than the one in use (PLAN-IOS.md §5): `from` is the base URL in
/// use, `to` the same server's LAN address, path and all.
struct LocalRoute: Hashable {
    var from: URL
    var to: URL

    /// `url` at the LAN address; nil when it is not under the address in use.
    func apply(to url: URL) -> URL? {
        let base = Self.trimmed(from.absoluteString)
        let s = url.absoluteString
        guard s.hasPrefix(base + "/") || s.hasPrefix(base + "?") else { return nil }
        return URL(string: Self.trimmed(to.absoluteString) + s.dropFirst(base.count))
    }

    private static func trimmed(_ s: String) -> String { s.hasSuffix("/") ? String(s.dropLast()) : s }
}
