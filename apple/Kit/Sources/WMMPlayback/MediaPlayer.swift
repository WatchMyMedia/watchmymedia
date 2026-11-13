import AVFoundation
import Foundation

/// Where the player is, as ExoPlayer's playback states (the Android player's words).
public enum PlayerPhase: Hashable, Sendable {
    /// Nothing loaded, or stopped.
    case idle
    /// Loading or waiting for data.
    case buffering
    /// Able to play (playing or paused).
    case ready
    /// Reached the end of the item.
    case ended
}

/// What to load.
public struct PlayerSource: Hashable, Sendable {
    /// With the media token.
    public var url: URL
    public var mimeType: String?
    public var hls: Bool
    /// An event playlist, which AVPlayer treats as live: it must start at `startPositionMs`, not the live edge.
    public var live: Bool
    /// Player time to start at.
    public var startPositionMs: Int
    public var playWhenReady: Bool
    /// For Now Playing.
    public var title: String
    public var subtitle: String?
    /// A direct-play file's audio track to start on, selected before the first frame.
    public var audio: AudioStart?
    /// What else the system's player shows about it (tvOS's Info panel).
    public var details: ItemDetails

    public init(url: URL, mimeType: String? = nil, hls: Bool, live: Bool, startPositionMs: Int, playWhenReady: Bool, title: String, subtitle: String? = nil, audio: AudioStart? = nil, details: ItemDetails = ItemDetails()) {
        self.url = url
        self.mimeType = mimeType
        self.hls = hls
        self.live = live
        self.startPositionMs = startPositionMs
        self.playWhenReady = playWhenReady
        self.title = title
        self.subtitle = subtitle
        self.audio = audio
        self.details = details
    }
}

/// What the Apple TV player's Info panel shows about the item beside its
/// title and subtitle (PLAN-APPLE-REVIEW.md §6.2): from the play request and
/// the picture Now Playing loads, so no request is added for it.
public struct ItemDetails: Hashable, Sendable {
    public var overview: String?
    public var year: Int?
    /// The certification ("TV-14", "R").
    public var contentRating: String?
    public var genres: [String]
    /// The play's picture, once loaded.
    public var artwork: NowPlayingArtwork?

    public init(overview: String? = nil, year: Int? = nil, contentRating: String? = nil, genres: [String] = [], artwork: NowPlayingArtwork? = nil) {
        self.overview = overview
        self.year = year
        self.contentRating = contentRating
        self.genres = genres
        self.artwork = artwork
    }
}

/// The decision's audio track as the player finds it: the `ordinal`th of
/// the file's `count` tracks, in the order the decision lists them. A
/// player's options that don't number `count` don't line up
/// (PLAN-LANGUAGES.md §5.2, §8).
public struct AudioStart: Hashable, Sendable {
    public var ordinal: Int
    public var count: Int

    public init(ordinal: Int, count: Int) {
        self.ordinal = ordinal
        self.count = count
    }
}

/// Why the player stopped. `detail` is for logs and the fallback message.
public struct PlayerFailure: Error, Hashable, Sendable {
    public enum Kind: Hashable, Sendable { case network, http, decoder, source, other }

    public var kind: Kind
    public var detail: String
    public var httpStatus: Int?

    public init(kind: Kind, detail: String, httpStatus: Int? = nil) {
        self.kind = kind
        self.detail = detail
        self.httpStatus = httpStatus
    }
}

/// What the player tells the controller.
public struct MediaPlayerListener {
    /// ``MediaPlayer/phase`` or ``MediaPlayer/playWhenReady`` changed.
    public var changed: @MainActor () -> Void
    /// The player stopped on a failure.
    public var failed: @MainActor (PlayerFailure) -> Void
    /// The time moved (about 10 times a second while playing, and on seeks), for subtitle cues.
    public var time: @MainActor () -> Void
    /// The newly loaded video decoded a frame (or advanced on the remote AirPlay screen).
    public var firstFrame: @MainActor () -> Void

    /// The requested audio could not be matched to a local track.
    public var audioUnmatched: @MainActor () -> Void

    public init(changed: @escaping @MainActor () -> Void, failed: @escaping @MainActor (PlayerFailure) -> Void, time: @escaping @MainActor () -> Void, firstFrame: @escaping @MainActor () -> Void = {}, audioUnmatched: @escaping @MainActor () -> Void = {}) {
        self.changed = changed
        self.failed = failed
        self.time = time
        self.firstFrame = firstFrame
        self.audioUnmatched = audioUnmatched
    }
}

/// What the controller needs from a player: ``AVMediaPlayer`` in the app, a
/// fake in tests, so the §2.3 machine runs on the Mac with virtual time.
/// Positions are player time; the controller maps them to file positions
/// (android/core/playback/.../player/MediaPlayer.kt).
@MainActor
public protocol MediaPlayer: AnyObject {
    /// The AVPlayer to render video from; nil in fakes.
    var avPlayer: AVPlayer? { get }

    var phase: PlayerPhase { get }

    /// False when paused, by the viewer or by the system (a call, headphones unplugged).
    var playWhenReady: Bool { get }

    var positionMs: Int { get }

    /// The end of the buffered range around the position, player time.
    var bufferedPositionMs: Int { get }

    /// The video is on another screen over AirPlay (`isExternalPlaybackActive`),
    /// which fetches the stream itself. A change calls ``MediaPlayerListener/changed``.
    var externalPlaybackActive: Bool { get }

    /// The player's estimate of its link from its own downloads since the
    /// last read, in bits per second, for Automatic quality; nil while it has none.
    var throughputBps: Int? { get }
    /// A measured completed media transfer and its current age, when available.
    var transfer: MediaTransfer? { get }

    func setListener(_ listener: MediaPlayerListener?)

    func load(_ source: PlayerSource)

    func play()

    func pause()

    func seek(toMs positionMs: Int)

    /// Direct play: switches to the file's `ordinal`th of `count` audio tracks.
    /// False when this player cannot, or does not know the tracks yet.
    func selectAudio(ordinal: Int, of count: Int) -> Bool

    /// The item playing is named afresh (a bare play link named after it
    /// started): the title and subtitle the system shows for it.
    func retitle(_ title: String, subtitle: String?)

    /// The item playing is described afresh (its picture loaded, or a bare
    /// play link named): what the system shows beside its title.
    func describe(_ details: ItemDetails)

    /// Stops loading and playing; ``load(_:)`` starts again.
    func stop()

    func release()
}

extension MediaPlayer {
    public func retitle(_ title: String, subtitle: String?) {}

    public func describe(_ details: ItemDetails) {}
}

public struct MediaTransfer: Hashable, Sendable {
    public var bps: Int
    public var ageMs: Int

    public init(bps: Int, ageMs: Int) {
        self.bps = bps
        self.ageMs = ageMs
    }
}
