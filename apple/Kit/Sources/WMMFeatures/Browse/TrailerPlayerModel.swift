import Foundation
import Observation
import WMMModel

/// A movie's YouTube trailers in YouTube's own player, full screen on iPhone
/// and iPad (TrailerPlayer.dc.html, PLAN-TRAILERS.md §6.3): our bar above
/// the player and nothing of ours over it. It follows the web app's rules:
/// - 101 and 150 (the owner keeps the video on YouTube): the next trailer,
///   and after the last, "This trailer can't play here" with Open in YouTube
///   for the first, the one Trailer meant;
/// - 100 (the video is gone or private): the next trailer, and after the
///   last, YouTube's own message stays in the player;
/// - anything else: YouTube's own message stays in the player.
@MainActor
@Observable
public final class TrailerPlayerModel: Identifiable {
    public enum State: Hashable, Sendable {
        case playing
        /// Every trailer's owner keeps it on YouTube.
        case refused
        /// The trailer played to its end: the screen closes, as the player
        /// does after a trailer file.
        case ended
    }

    public nonisolated let id = UUID()
    public let movieTitle: String
    /// Best first, as the server lists them; never empty.
    public let trailers: [YouTubeTrailer]
    public private(set) var index = 0
    public private(set) var state: State = .playing
    /// The player said it is playing this trailer, so a later "ended" is its end.
    @ObservationIgnored private var started = false

    /// Nil without a YouTube trailer.
    public init?(movieTitle: String, trailers: [YouTubeTrailer]) {
        guard !trailers.isEmpty else { return nil }
        self.movieTitle = movieTitle
        self.trailers = trailers
    }

    /// The trailer in the player, or once all were refused the first, which
    /// Open in YouTube opens.
    public var trailer: YouTubeTrailer { trailers[state == .refused ? 0 : index] }

    /// YouTube's player reported error `code` (its `onError`) for `key`.
    /// Errors for a trailer it has moved on from are ignored.
    public func playerFailed(key: String, code: Int) {
        guard state == .playing, key == trailer.key else { return }
        let hasNext = index + 1 < trailers.count
        switch code {
        case 101, 150:
            if hasNext { next() } else { state = .refused }
        case 100:
            if hasNext { next() }
        default:
            break
        }
    }

    /// The player's state changed (YT.PlayerState: 1 playing, 0 ended) for `key`.
    public func playerChanged(key: String, state code: Int) {
        guard state == .playing, key == trailer.key else { return }
        if code == 1 {
            started = true
        } else if code == 0 && started {
            state = .ended
        }
    }

    private func next() {
        index += 1
        started = false
    }
}
