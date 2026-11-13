import Foundation

/// The server's quality switches as a controller follows them, by
/// generation, for Automatic on files (`Int64`) and on Live TV (`Int`)
/// alike (PLAN-APPLE-REVIEW.md §5.1): a directive is acted on once, and a
/// switch that showed a frame, or failed, is reported in every heartbeat
/// until the server has heard it. What waits for the new stream's frame,
/// and what is kept to go back to, differs between the two and stays with
/// each controller.
struct SwitchGenerations<Generation: BinaryInteger> {
    /// The newest directive acted on.
    var handled: Generation = 0
    /// A switch whose new stream showed a frame, until a reply acknowledges it.
    private(set) var accepted: Generation?
    /// A switch that couldn't be made, until a reply acknowledges it.
    private(set) var failed: Generation?

    /// Whether to act on a directive for `generation`: once, and only past
    /// what was handled and what the server says plays (`playing`).
    mutating func take(_ generation: Generation, playing: Generation = 0) -> Bool {
        guard generation > max(handled, playing) else { return false }
        handled = generation
        return true
    }

    mutating func accept(_ generation: Generation) { accepted = generation }

    mutating func fail(_ generation: Generation) { failed = generation }

    /// The accepted switch is no longer one to report, acknowledged or not.
    mutating func forgetAccepted() { accepted = nil }

    /// A reply to a report that carried `generation` as accepted: true when
    /// that is the switch still waiting to be heard, which it no longer is.
    @discardableResult
    mutating func acknowledge(accepted generation: Generation?) -> Bool {
        guard let generation, generation == accepted else { return false }
        accepted = nil
        return true
    }

    /// A reply to a report that carried `generation` as failed.
    mutating func acknowledge(failed generation: Generation?) {
        guard let generation, generation == failed else { return }
        failed = nil
    }
}
