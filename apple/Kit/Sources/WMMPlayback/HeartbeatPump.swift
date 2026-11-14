import Foundation

/// The reports a controller sends its server, one at a time (PLAN-APPLE-REVIEW.md
/// §5.1): one is out at once; what comes meanwhile waits, and `coalesce`
/// says what a newer message replaces among those waiting, so a server that
/// can't be reached gets the newest position rather than a backlog, while
/// a stop is never dropped. The file player queues its heartbeats, stops and
/// session ends here, in order (``RealPlaybackController/enqueue(_:into:behindDelivery:)``);
/// Live TV keeps one heartbeat out and the newest asked for waiting.
///
/// The work runs on tasks from `spawn`, so a controller can keep count of
/// them. While messages are out or waiting the pump holds `owner` (the
/// controller, which `deliver` should hold weakly), so the last of them go
/// out after the screen has let the controller go.
@MainActor
final class HeartbeatPump<Message> {
    /// Adds `message` to `waiting`; `behindDelivery` is true while one is out.
    typealias Coalesce = (_ message: Message, _ waiting: inout [Message], _ behindDelivery: Bool) -> Void

    private weak var owner: AnyObject?
    private let coalesce: Coalesce
    private let deliver: @MainActor (Message) async -> Void
    private let spawn: (@escaping @MainActor () async -> Void) -> Task<Void, Never>
    private var waiting: [Message] = []
    private var draining: Task<Void, Never>?
    /// One message is out, waiting for the server.
    private(set) var sending = false
    /// Called each time the last waiting message has gone out.
    var onIdle: (@MainActor () -> Void)?

    init(
        owner: AnyObject,
        coalesce: @escaping Coalesce,
        spawn: @escaping (@escaping @MainActor () async -> Void) -> Task<Void, Never> = { work in Task { @MainActor in await work() } },
        deliver: @escaping @MainActor (Message) async -> Void
    ) {
        self.owner = owner
        self.coalesce = coalesce
        self.spawn = spawn
        self.deliver = deliver
    }

    /// Something is out or waiting.
    var busy: Bool { draining != nil }

    func submit(_ message: Message) {
        coalesce(message, &waiting, sending)
        guard draining == nil else { return }
        draining = spawn { [self, owner] in
            _ = owner
            while !waiting.isEmpty {
                let next = waiting.removeFirst()
                sending = true
                await deliver(next)
                sending = false
            }
            draining = nil
            onIdle?()
        }
    }

    /// Returns once everything submitted so far has gone out.
    func drained() async {
        while let draining { await draining.value }
    }
}
