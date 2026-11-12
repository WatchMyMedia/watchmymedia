import Foundation
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
    /// The person's Live TV favourites and reminders (live.favourites.changed, live.reminders.changed).
    case liveTV
    /// The person's row layouts and lists (rows.changed, lists.changed).
    case rows
}

/// GET /api/events on the current server, signed in: tells every listener
/// when an event of its topic arrives (android/core/data/.../ServerEvents.kt).
/// One connection is shared by all listeners and kept while at least one
/// listens (and a few seconds after the last leaves, so moving between screens
/// does not reconnect). A dropped stream reconnects with backoff, and tells
/// listeners once it is back, since events may have been missed. Marks and
/// requests made on this device tell listeners too (``notify(_:)``).
@MainActor
final class ServerEvents {
    private let store: LocalStore
    private let session: URLSession
    private let backoff: @Sendable (_ attempt: Int) -> Duration
    private let linger: Duration
    private var listeners: [UUID: (topic: EventTopic, continuation: AsyncStream<Void>.Continuation)] = [:]
    private var connection: Task<Void, Never>?
    private var stopping: Task<Void, Never>?
    /// The connection in use, so a cancelled one's last events are dropped.
    private var generation = 0
    private var attempt = 0
    private var connectedBefore = false
    /// Whether the current connection has sent its first ping.
    private var pinged = false

    /// 1 s, 2 s, 4 s … up to a minute.
    nonisolated static let defaultBackoff: @Sendable (Int) -> Duration = { attempt in .seconds(min(1 << min(attempt, 6), 60)) }

    init(store: LocalStore, session: URLSession, backoff: @escaping @Sendable (Int) -> Duration = ServerEvents.defaultBackoff, linger: Duration = .seconds(5)) {
        self.store = store
        self.session = session
        self.backoff = backoff
        self.linger = linger
        store.onSessionChange { [weak self] _ in self?.reconnect() }
    }

    /// A new listener to `topic`; it ends when the iterating task is cancelled.
    func changes(_ topic: EventTopic = .library) -> AsyncStream<Void> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let id = UUID()
            listeners[id] = (topic, continuation)
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.remove(id) }
            }
            stopping?.cancel()
            stopping = nil
            if connection == nil { connect() }
        }
    }

    /// Tells every listener to `topic` something changed; every listener when nil.
    func notify(_ topic: EventTopic? = .library) {
        for listener in listeners.values where topic == nil || listener.topic == topic { listener.continuation.yield() }
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

    /// The sign-in changed: follow it.
    private func reconnect() {
        connection?.cancel()
        connection = nil
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
                case 401:
                    // Signed out; a new sign-in reconnects.
                    store.tokenRejected(serverID: signedIn.serverID, token: signedIn.token)
                    return
                case 404:
                    // A server without events.
                    return
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
            if connectedBefore { notify(nil) }
            connectedBefore = true
        case "watch.changed", "library.changed", "scan.started", "scan.finished", "scan.failed":
            notify(.library)
        case "requests.changed", "problems.changed":
            notify(.requests)
        case "live.favourites.changed", "live.reminders.changed", "live.recordings.changed":
            notify(.liveTV)
        case "rows.changed", "lists.changed":
            notify(.rows)
        default:
            break
        }
    }

    /// One connection: hands each event to `deliver` on the main actor until the
    /// server closes the stream or it drops. Returns the HTTP status, or 0 when
    /// there was no answer.
    @concurrent
    private nonisolated static func read(_ request: URLRequest, session: URLSession, deliver: @escaping @MainActor (ServerEvent) -> Void) async -> Int {
        do {
            let (bytes, response) = try await session.bytes(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard status == 200 else { return status }
            var parser = SSEParser()
            for try await byte in bytes {
                if let event = parser.feed(byte) { await deliver(event) }
            }
            return status
        } catch {
            return 0
        }
    }
}
