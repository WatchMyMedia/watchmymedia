import Foundation
import WMMAPI
import WMMModel

/// One server-sent event.
struct ServerEvent: Equatable, Sendable {
    var event: String
    var data: String
    var id: String?
}

/// Reads the text/event-stream format
/// (https://html.spec.whatwg.org/#event-stream-interpretation) from bytes as
/// they arrive: lines end in LF, CR or CRLF, and may be split across chunks.
struct SSEParser {
    private var event = ""
    private var data = ""
    private var hasData = false
    /// The last event ID buffer: it carries on until another `id` field.
    private var lastID: String?
    private var pending: [UInt8] = []
    private var afterCR = false
    private var started = false

    /// Feeds bytes; returns the events they complete.
    mutating func feed(_ bytes: some Sequence<UInt8>) -> [ServerEvent] {
        var events: [ServerEvent] = []
        for byte in bytes {
            if let e = feed(byte) { events.append(e) }
        }
        return events
    }

    /// Feeds one byte; returns the event it completes, if any.
    mutating func feed(_ byte: UInt8) -> ServerEvent? {
        switch byte {
        case 0x0A where afterCR:
            afterCR = false
            return nil
        case 0x0A, 0x0D:
            afterCR = byte == 0x0D
            var bytes = pending
            pending.removeAll(keepingCapacity: true)
            if !started {
                started = true
                if bytes.starts(with: [0xEF, 0xBB, 0xBF]) { bytes.removeFirst(3) }
            }
            return line(String(decoding: bytes, as: UTF8.self))
        default:
            afterCR = false
            pending.append(byte)
            return nil
        }
    }

    /// Takes one line, without its ending; returns the event a blank line completes.
    mutating func line(_ line: String) -> ServerEvent? {
        if line.isEmpty { return dispatch() }
        if line.hasPrefix(":") { return nil }
        let field: Substring
        var value: Substring
        if let colon = line.firstIndex(of: ":") {
            field = line[..<colon]
            value = line[line.index(after: colon)...]
            if value.hasPrefix(" ") { value = value.dropFirst() }
        } else {
            field = line[...]
            value = ""
        }
        switch field {
        case "event":
            event = String(value)
        case "data":
            if hasData { data += "\n" }
            data += value
            hasData = true
        case "id":
            if !value.contains("\0") { lastID = String(value) }
        default:
            break
        }
        return nil
    }

    private mutating func dispatch() -> ServerEvent? {
        defer {
            event = ""
            data = ""
            hasData = false
        }
        guard hasData else { return nil }
        return ServerEvent(event: event.isEmpty ? "message" : event, data: data, id: lastID)
    }
}

/// What a listener hears about: watch state and the library (watch.changed,
/// library.changed), requests and problem reports (requests.changed,
/// problems.changed), the person's Live TV favourites and reminders, or
/// their row layouts and lists.
enum EventTopic: Hashable, Sendable {
    case library, requests
    /// The person's Live TV favourites, reminders and the household's recordings
    /// (live.favourites.changed, live.reminders.changed, live.recordings.changed).
    case liveTV
    /// The person's row layouts and lists (rows.changed, lists.changed).
    case rows
}

/// A change this device made, whose echo from the server is not news
/// (PLAN-APPLE-REVIEW.md §4.1.1): its own notice reaches every listener.
enum ExpectedEcho: Hashable, Sendable {
    /// watch.changed for these movies or episodes.
    case watched(WatchKind, ids: Set<Int>)
    /// watch.changed for a season's or a show's episodes, whichever they are.
    case episodes
}

/// GET /api/events on the current server, signed in: tells every listener
/// when an event of its topic arrives (android/core/data/.../ServerEvents.kt),
/// saying what changed where the event does (``LibraryChange``,
/// ``LiveTVChange``). One connection is shared by all listeners and kept
/// while at least one listens (and a few seconds after the last leaves, so
/// moving between screens does not reconnect). A dropped stream reconnects
/// with backoff, and tells listeners once it is back, since events may have
/// been missed. Marks and requests made on this device tell listeners too
/// (``notify(_:)-swift.method``), and the server's echo of a mark made here
/// is dropped (``expect(_:)``), so a mark loads each screen once.
///
/// It follows the network (PLAN-APPLE-REVIEW.md §4.1.7): when a path
/// returns, or the app comes back to the foreground (``reconnectNow()``), it
/// connects again at once rather than at its next backoff. A server without
/// events (404) is asked again at the longest backoff, so one that comes back
/// with them is followed again. Before telling listeners a reconnect may have
/// missed events, it asks the server's level again (``onReconnect``): a
/// restart is usually an upgrade.
@MainActor
final class ServerEvents {
    private enum Delivery {
        case signal(AsyncStream<Void>.Continuation)
        case library(AsyncStream<LibraryChange>.Continuation)
        case live(AsyncStream<LiveTVChange>.Continuation)
    }

    /// The events some listener handles; the rest (progress, activity, Plex)
    /// are dropped before they reach the main actor.
    nonisolated static let handled: Set<String> = [
        "ping", "watch.changed", "library.changed", "scan.started", "scan.finished", "scan.failed",
        "requests.changed", "problems.changed",
        "live.favourites.changed", "live.reminders.changed", "live.recordings.changed",
        "rows.changed", "lists.changed",
    ]

    /// How long a change made here waits for its echo.
    static let echoWindow: TimeInterval = 30

    private let store: LocalStore
    private let session: URLSession
    private let backoff: @Sendable (_ attempt: Int) -> Duration
    private let linger: Duration
    private let now: @Sendable () -> Date
    private var listeners: [UUID: (topic: EventTopic, delivery: Delivery)] = [:]
    private var connection: Task<Void, Never>?
    private var stopping: Task<Void, Never>?
    /// The connection in use, so a cancelled one's last events are dropped.
    private var generation = 0
    private var attempt = 0
    private var connectedBefore = false
    /// Whether the current connection has sent its first ping.
    private var pinged = false
    /// Changes made here whose echo is still to come, with when they were made.
    private var echoes: [(id: UUID, echo: ExpectedEcho, at: Date)] = []
    /// Events of each topic received from the server (and reconnects), so a
    /// request made before one is not taken as an answer after it.
    private(set) var received: [EventTopic: Int] = [:]
    /// Runs when a connection comes back after another, before listeners are
    /// told: DataServices asks the server's level again.
    var onReconnect: (@MainActor () async -> Void)?
    private var pathWatch: Task<Void, Never>?

    /// 1 s, 2 s, 4 s … up to a minute.
    nonisolated static let defaultBackoff: @Sendable (Int) -> Duration = { attempt in .seconds(min(1 << min(attempt, 6), 60)) }

    init(store: LocalStore, session: URLSession, backoff: @escaping @Sendable (Int) -> Duration = ServerEvents.defaultBackoff,
         linger: Duration = .seconds(5), now: @escaping @Sendable () -> Date = { .now }, network: (any NetworkPath)? = nil) {
        self.store = store
        self.session = session
        self.backoff = backoff
        self.linger = linger
        self.now = now
        store.onSessionChange { [weak self] _ in self?.reconnect() }
        if let network {
            let updates = network.updates()
            pathWatch = Task { [weak self] in
                var satisfied = true
                for await now in updates {
                    if now && !satisfied { self?.reconnectNow() }
                    satisfied = now
                }
            }
        }
    }

    isolated deinit {
        pathWatch?.cancel()
    }

    /// A new listener to `topic`; it ends when the iterating task is cancelled.
    func changes(_ topic: EventTopic = .library) -> AsyncStream<Void> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let id = add(topic, .signal(continuation))
            continuation.onTermination = { [weak self] _ in Task { @MainActor in self?.remove(id) } }
        }
    }

    /// A new listener to the library, told what changed. Nothing is dropped:
    /// a listener reads them as they come and merges a burst itself.
    func libraryChanges() -> AsyncStream<LibraryChange> {
        AsyncStream { continuation in
            let id = add(.library, .library(continuation))
            continuation.onTermination = { [weak self] _ in Task { @MainActor in self?.remove(id) } }
        }
    }

    /// A new listener to Live TV, told which part changed.
    func liveChanges() -> AsyncStream<LiveTVChange> {
        AsyncStream { continuation in
            let id = add(.liveTV, .live(continuation))
            continuation.onTermination = { [weak self] _ in Task { @MainActor in self?.remove(id) } }
        }
    }

    private func add(_ topic: EventTopic, _ delivery: Delivery) -> UUID {
        let id = UUID()
        listeners[id] = (topic, delivery)
        stopping?.cancel()
        stopping = nil
        if connection == nil { connect() }
        return id
    }

    /// Tells every listener to `topic` something changed; every listener when nil.
    func notify(_ topic: EventTopic? = .library) {
        for listener in listeners.values where topic == nil || listener.topic == topic {
            switch listener.delivery {
            case .signal(let c): c.yield()
            case .library(let c): c.yield(.unknown)
            case .live(let c): c.yield(.all)
            }
        }
    }

    /// Tells the library's listeners what changed.
    func notify(_ change: LibraryChange) {
        for listener in listeners.values where listener.topic == .library {
            switch listener.delivery {
            case .signal(let c): c.yield()
            case .library(let c): c.yield(change)
            case .live: break
            }
        }
    }

    /// Tells Live TV's listeners which part changed.
    func notify(_ change: LiveTVChange) {
        for listener in listeners.values where listener.topic == .liveTV {
            switch listener.delivery {
            case .signal(let c): c.yield()
            case .live(let c): c.yield(change)
            case .library: break
            }
        }
    }

    /// A change this device is making: its echo from the server is dropped
    /// when it comes within ``echoWindow``. Call before the request, since the
    /// echo can come before the reply; ``forget(_:)`` it if the request fails.
    @discardableResult
    func expect(_ echo: ExpectedEcho) -> UUID {
        let id = UUID()
        let date = now()
        echoes.removeAll { date.timeIntervalSince($0.at) > Self.echoWindow }
        echoes.append((id, echo, date))
        return id
    }

    func forget(_ id: UUID) {
        echoes.removeAll { $0.id == id }
    }

    /// Whether watch.changed for `ids` is the echo of a change made here; if so it is used up.
    private func isEcho(_ kind: WatchKind, ids: [Int]) -> Bool {
        let date = now()
        echoes.removeAll { date.timeIntervalSince($0.at) > Self.echoWindow }
        let index = echoes.firstIndex { expected in
            switch expected.echo {
            case .watched(let k, let expectedIDs): k == kind && !ids.isEmpty && Set(ids).isSubset(of: expectedIDs)
            case .episodes: kind == .episode
            }
        }
        guard let index else { return false }
        echoes.remove(at: index)
        return true
    }

    private func remove(_ id: UUID) {
        listeners[id] = nil
        guard listeners.isEmpty, stopping == nil else { return }
        let linger = linger
        stopping = Task { [weak self] in
            try? await Task.sleep(for: linger)
            guard !Task.isCancelled, let self, self.listeners.isEmpty else { return }
            self.connection?.cancel()
            self.connection = nil
            self.stopping = nil
        }
    }

    /// The network or the app came back: connect again at once, rather than
    /// waiting out a backoff or reading a connection that died meanwhile.
    /// Listeners hear of it as a reconnect, since events may have been missed.
    func reconnectNow() {
        guard connection != nil || !listeners.isEmpty else { return }
        let before = connectedBefore
        connection?.cancel()
        connection = nil
        guard !listeners.isEmpty else { return }
        connect()
        connectedBefore = before
    }

    /// The sign-in changed: follow it.
    private func reconnect() {
        connection?.cancel()
        connection = nil
        echoes = []
        if !listeners.isEmpty { connect() }
    }

    private func connect() {
        guard let signedIn = try? store.signedIn() else { return }
        generation += 1
        let generation = generation
        attempt = 0
        connectedBefore = false
        var request = signedIn.api.authorizedRequest(signedIn.api.url("/api/events"))
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.cachePolicy = .reloadIgnoringLocalCacheData
        connection = Task { [weak self, session, backoff, request] in
            while !Task.isCancelled {
                self?.pinged = false
                let status = await Self.read(request, session: session) { event in
                    self?.receive(event, generation: generation)
                }
                guard !Task.isCancelled, let self else { return }
                switch status {
                case 401 where ServerAPI.revokes(status: 401, signed: RequestSigner.signed(request)):
                    // Signed out; a new sign-in reconnects.
                    await signedIn.api.tokenRefused()
                    return
                case 404:
                    // A server without events (or a proxy in front of a restarting
                    // one): asked again at the longest wait, or when the network
                    // or the app comes back.
                    attempt = max(attempt, 6)
                    let delay = backoff(attempt)
                    try? await Task.sleep(for: delay)
                default:
                    let delay = backoff(attempt)
                    attempt += 1
                    try? await Task.sleep(for: delay)
                }
            }
        }
    }

    private func receive(_ event: ServerEvent, generation: Int) {
        guard generation == self.generation else { return }
        switch event.event {
        case "ping":
            attempt = 0
            // Only a connection's first ping can follow missed events;
            // servers up to 0.4.1 also ping every 15 s to keep it open.
            guard !pinged else { break }
            pinged = true
            if connectedBefore {
                for topic in [EventTopic.library, .requests, .liveTV, .rows] { received[topic, default: 0] += 1 }
                // A restart is usually an upgrade: its level first, so what
                // listeners load next uses what the server now offers.
                Task { [weak self] in
                    await self?.onReconnect?()
                    guard let self, generation == self.generation else { return }
                    notify(nil)
                }
            }
            connectedBefore = true
        case "watch.changed":
            received[.library, default: 0] += 1
            guard let changed = Self.decode(Components.Schemas.WatchChanged.self, event.data) else { return notify(.unknown) }
            let kind: WatchKind
            switch changed.itemType {
            case .movie: kind = .movie
            case .episode: kind = .episode
            case .undocumented: return notify(.unknown)
            }
            let ids = changed.itemIds.map { Int($0) }
            if isEcho(kind, ids: ids) { return }
            notify(.watched(kind, ids: ids, watched: nil))
        case "library.changed":
            received[.library, default: 0] += 1
            notify(.library(id: Self.decode(Components.Schemas.LibraryChanged.self, event.data).map { Int($0.libraryId) }))
        case "scan.started", "scan.finished", "scan.failed":
            received[.library, default: 0] += 1
            notify(.scan(libraryID: Self.decode(ScanEvent.self, event.data)?.libraryId))
        case "requests.changed", "problems.changed":
            received[.requests, default: 0] += 1
            notify(.requests)
        case "live.favourites.changed":
            received[.liveTV, default: 0] += 1
            notify(LiveTVChange.favourites)
        case "live.reminders.changed":
            received[.liveTV, default: 0] += 1
            notify(LiveTVChange.reminders)
        case "live.recordings.changed":
            received[.liveTV, default: 0] += 1
            notify(LiveTVChange.recordings)
        case "rows.changed", "lists.changed":
            received[.rows, default: 0] += 1
            notify(.rows)
        default:
            break
        }
    }

    /// A scan event's library; the rest of each payload differs by event.
    private struct ScanEvent: Decodable {
        var libraryId: Int?
    }

    private static func decode<T: Decodable>(_ type: T.Type, _ data: String) -> T? {
        try? JSONDecoder().decode(type, from: Data(data.utf8))
    }

    /// One connection: hands each event a listener handles to `deliver` on the
    /// main actor until the server closes the stream or it drops. Returns the
    /// HTTP status, or 0 when there was no answer.
    @concurrent
    private nonisolated static func read(_ request: URLRequest, session: URLSession, deliver: @escaping @MainActor (ServerEvent) -> Void) async -> Int {
        do {
            let (bytes, response) = try await session.bytes(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard status == 200 else { return status }
            var parser = SSEParser()
            for try await byte in bytes {
                if let event = parser.feed(byte), handled.contains(event.event) { await deliver(event) }
            }
            return status
        } catch {
            return 0
        }
    }
}
