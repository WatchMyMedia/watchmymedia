import Foundation

/// "Are you still watching?" bookkeeping (PLAN-CLIENTS.md §2.3.6), kept for
/// the app session: one for the app, shared by every controller, so it
/// survives leaving the player and coming back. Ask before moving on by
/// itself when the session has run 90 min and either 3 episodes have played
/// without input or there has been no input for 90 min.
@MainActor
public final class StillWatching {
    static let longMs = 90 * 60_000
    static let episodesInARow = 3

    private let clock: any PlaybackClock
    private var sessionStart: Int?
    private var lastInput = 0
    private var episodesSinceInput = 0

    public init(clock: any PlaybackClock) {
        self.clock = clock
    }

    /// A play started: the session's clock starts with the first one.
    func notePlay() {
        guard sessionStart == nil else { return }
        sessionStart = clock.elapsedMs
        lastInput = clock.elapsedMs
    }

    /// A tap, press or seek: the viewer is there.
    func noteInput() {
        lastInput = clock.elapsedMs
        episodesSinceInput = 0
    }

    /// The player moved on to the next episode by itself.
    func noteAutoAdvance() {
        episodesSinceInput += 1
    }

    func shouldAsk() -> Bool {
        guard let start = sessionStart else { return false }
        let now = clock.elapsedMs
        return now - start >= Self.longMs && (episodesSinceInput >= Self.episodesInARow || now - lastInput >= Self.longMs)
    }
}
