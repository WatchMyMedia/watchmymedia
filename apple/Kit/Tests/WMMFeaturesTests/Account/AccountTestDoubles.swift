import Foundation
import Observation
import WMMModel

// Test doubles for the account screens' models: repositories that fail, count
// calls or follow a script, where the fakes in WMMFakes always succeed.

let sam = User(id: 1, name: "Sam", isAdmin: true)
let viewer = User(id: 2, name: "Robin", isAdmin: false)

func pairingCode(_ code: String, expiresIn seconds: TimeInterval = 600) -> PairingCode {
    PairingCode(code: code, expiresAt: .now.addingTimeInterval(seconds), linkURL: URL(string: "https://home.watchmy.media/link?code=\(code)")!, pollInterval: .seconds(2))
}

/// Waits (yielding to other tasks) until `condition` holds, for a model driven by a task.
@MainActor
func eventually(_ condition: () -> Bool) async -> Bool {
    for _ in 0..<2000 {
        if condition() { return true }
        await Task.yield()
    }
    return condition()
}

/// A server repository that counts discovery calls and answers probes as told.
@MainActor
@Observable
final class TestServers: ServerRepository {
    var discovered: [DiscoveredServer] = []
    var saved: [SavedServer] = []
    var current: SavedServer?
    var localNetworkDenied = false

    var probeResult: Result<Server, any Error> = .failure(APIError(status: nil, message: "Not set up."))
    private(set) var probed: [String] = []
    private(set) var selected: [Server] = []
    private(set) var starts = 0
    private(set) var stops = 0

    func startDiscovery() { starts += 1 }
    func stopDiscovery() { stops += 1 }

    func probe(_ address: String) async throws -> Server {
        probed.append(address)
        return try probeResult.get()
    }

    func select(_ server: Server) async throws {
        selected.append(server)
        current = SavedServer(server: server, userName: nil, signedIn: false, lastUsedAt: .now)
    }

    func forget(serverID: String) async {
        saved.removeAll { $0.server.id == serverID }
    }

    func localAddress() async -> URL? { nil }

    func refreshCurrent() async {}
}

/// An auth repository whose answers are set by the test; `pair()` plays
/// `pairingScripts` one per call, and waits after the last state of a script
/// marked `hangs` until the caller cancels.
@MainActor
@Observable
final class ScriptedAuth: AuthRepository {
    struct Script {
        var states: [PairingState]
        var hangs = false
    }

    var user: User?
    var deviceName = "Sam’s iPhone"

    var signInError: (any Error)?
    private(set) var signInCalls = 0
    var pairingScripts: [Script] = []
    private(set) var pairingsStarted = 0
    private(set) var pairingsEnded = 0
    var sessionList: [DeviceSession] = []
    var sessionsError: (any Error)?
    private(set) var sessionsAsked: [Bool] = []
    var revokeError: (any Error)?
    var lookUpResult: Result<PendingDevice, any Error>?
    var approveError: (any Error)?

    init(user: User? = nil) { self.user = user }

    func setDeviceName(_ name: String) async { deviceName = name }

    func signIn(name: String, password: String) async throws -> User {
        signInCalls += 1
        if let signInError { throw signInError }
        let u = User(id: 1, name: name, isAdmin: false)
        user = u
        return u
    }

    func pair() -> AsyncStream<PairingState> {
        let script = pairingScripts.isEmpty ? Script(states: []) : pairingScripts.removeFirst()
        pairingsStarted += 1
        return AsyncStream { continuation in
            for state in script.states { continuation.yield(state) }
            if !script.hangs { continuation.finish() }
            continuation.onTermination = { _ in Task { @MainActor in self.pairingsEnded += 1 } }
        }
    }

    func signOut() async { user = nil }

    func sessions(all: Bool) async throws -> [DeviceSession] {
        sessionsAsked.append(all)
        if let sessionsError { throw sessionsError }
        return sessionList
    }

    func revoke(sessionID: String) async throws {
        if let revokeError { throw revokeError }
        sessionList.removeAll { $0.id == sessionID }
    }

    func lookUpPairing(code: String) async throws -> PendingDevice {
        guard let lookUpResult else { return PendingDevice(code: code, name: "Den TV", approved: false) }
        return try lookUpResult.get()
    }

    func approvePairing(code: String) async throws -> PendingDevice {
        if let approveError { throw approveError }
        var device = try await lookUpPairing(code: code)
        device.approved = true
        return device
    }
}

/// Preferences that refuse to save (or load), optionally holding each save
/// until ``release()`` so a test can look at the screen mid-save.
@MainActor
@Observable
final class FailingPreferences: PreferencesRepository {
    private(set) var playback = PlaybackPreferences()
    private(set) var quality: StreamQuality = .original
    var failure: (any Error)? = APIError(status: 500, message: "The server couldn't save that.")
    var refreshFailure: (any Error)?
    var holds = false
    private(set) var waiting = 0
    private var gates: [CheckedContinuation<Void, Never>] = []

    func release() {
        let open = gates
        gates = []
        open.forEach { $0.resume() }
    }

    private func gate() async {
        guard holds else { return }
        waiting += 1
        await withCheckedContinuation { gates.append($0) }
    }

    func refresh() async throws {
        if let refreshFailure { throw refreshFailure }
    }

    func setSkip(_ kind: SegmentKind, mode: SkipMode) async throws {
        await gate()
        if let failure { throw failure }
        playback.skip[kind] = mode
    }

    func setAutoplayNext(_ enabled: Bool) async throws {
        await gate()
        if let failure { throw failure }
        playback.autoplayNext = enabled
    }

    var languageChanges: [LanguageChange] = []

    func setLanguages(_ change: LanguageChange) async throws {
        languageChanges.append(change)
        await gate()
        if let failure { throw failure }
        change.apply(to: &playback)
    }

    func setQuality(_ quality: StreamQuality) { self.quality = quality }
}
