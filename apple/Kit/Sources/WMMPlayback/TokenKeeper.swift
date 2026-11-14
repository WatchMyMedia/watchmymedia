import Foundation
import WMMModel

/// The media token a controller plays with, for the file player and Live TV
/// alike (PLAN-APPLE-REVIEW.md §5.1). It is timed on this device's
/// monotonic clock from what was left of it when it came, with a floor
/// (``tokenMinLifetimeMs``), so a device clock that disagrees with the
/// server's cannot make a player renew it, and reload, over and over. It is
/// renewed ``tokenMarginMs`` before it runs out.
@MainActor
final class TokenKeeper {
    private let clock: any PlaybackClock
    private(set) var token: MediaToken?
    /// When to renew it, on the monotonic clock.
    private var renewAt = 0

    init(clock: any PlaybackClock) {
        self.clock = clock
    }

    /// There is a token and it isn't time to renew it.
    var valid: Bool { token != nil && clock.elapsedMs < renewAt }

    /// There is a token and it is time to renew it.
    var due: Bool { token != nil && clock.elapsedMs >= renewAt }

    /// Plays with `fresh` from now on.
    func keep(_ fresh: MediaToken) {
        token = fresh
        let lifetime = max(Int(fresh.expiresAt.timeIntervalSince(clock.now) * 1000), tokenMinLifetimeMs)
        renewAt = clock.elapsedMs + lifetime - tokenMarginMs
    }

    /// Forgets the token: the next play asks for one.
    func clear() { token = nil }

    /// `url` with the token, or as it is without one.
    func apply(to url: URL) -> URL { token?.apply(to: url) ?? url }
}
