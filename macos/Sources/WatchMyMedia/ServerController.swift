import AppKit
import Foundation
import IOKit.pwr_mgt
import ServiceManagement

/// One running ffmpeg session, as the server reports it.
struct StreamSession: Decodable, Identifiable {
    let id: String
    let fileId: Int64
    let mode: String
    let encoder: String
    let started: Date
}

/// A play running now, direct play included: who is watching what.
struct LocalPlay: Decodable, Identifiable {
    let id: String
    let user: String
    let title: String
    let mode: String
    let state: String
    let startedAt: Date
}

/// `GET /api/playback/local`, which the server answers only on loopback.
struct LocalStatus: Decodable {
    let version: String
    let setupRequired: Bool
    /// A server with an account still needs a library or a saved tuner.
    /// Older servers expose only setupRequired.
    let setupIncomplete: Bool?
    let sessions: [StreamSession]
    /// Missing from servers older than the Activity section.
    let plays: [LocalPlay]?

    var needsSetup: Bool { setupIncomplete ?? setupRequired }
}

/// Runs the Go server from the app bundle and watches it. The server is a
/// child process: it logs to ~/Library/Logs/WatchMyMedia, keeps its data in
/// ~/Library/Application Support/WatchMyMedia, is restarted with backoff if
/// it exits on its own, and is stopped when the app quits. A pipe on its
/// stdin makes it exit if the app dies without stopping it.
@MainActor
final class ServerController: ObservableObject {
    static let shared = ServerController()

    enum State: Equatable {
        case stopped
        case starting
        case running(version: String)
        case portInUse(Int)
        case failed(String)
    }

    @Published private(set) var state: State = .stopped
    @Published private(set) var sessions: [StreamSession] = []
    @Published private(set) var plays: [LocalPlay] = []
    @Published private(set) var setupRequired = false
    @Published private(set) var launchAtLogin = SMAppService.mainApp.status == .enabled

    @Published private(set) var port: Int
    let dataDir: URL
    let logURL: URL

    private var process: Process?
    private var stdinPipe: Pipe?
    private var pollTask: Task<Void, Never>?
    private var restartTask: Task<Void, Never>?
    private var restartDelay: TimeInterval = 1
    private var stopping = false
    private var powerAssertion: IOPMAssertionID = 0
    private let decoder: JSONDecoder
    private let defaults: UserDefaults
    private let helpersOverride: URL?
    private let session: URLSession
    private let openURL: (URL) -> Bool
    private var launchLogOffset: UInt64 = 0

    init(port configuredPort: Int? = nil, dataDir configuredDataDir: URL? = nil,
         logURL configuredLogURL: URL? = nil, helpers: URL? = nil,
         defaults: UserDefaults = .standard, session: URLSession = .shared,
         openURL: @escaping (URL) -> Bool = { NSWorkspace.shared.open($0) }) {
        // WATCHMYMEDIA_PORT in the environment (development), else the
        // `port` default (`defaults write media.watchmy.WatchMyMedia port`).
        let fromEnv = ProcessInfo.processInfo.environment["WATCHMYMEDIA_PORT"].flatMap(Int.init).flatMap(ServerStartup.validPort)
        port = configuredPort.flatMap(ServerStartup.validPort) ?? fromEnv
            ?? ServerStartup.validPort(defaults.integer(forKey: "port")) ?? 27587
        self.defaults = defaults
        helpersOverride = helpers
        self.session = session
        self.openURL = openURL
        let fm = FileManager.default
        dataDir = configuredDataDir ?? fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("WatchMyMedia", isDirectory: true)
        logURL = configuredLogURL ?? fm.urls(for: .libraryDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Logs/WatchMyMedia", isDirectory: true)
            .appendingPathComponent("server.log")
        decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            // Go writes RFC 3339 with or without fractional seconds.
            let s = try decoder.singleValueContainer().decode(String.self)
            let withFraction = ISO8601DateFormatter()
            withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let d = withFraction.date(from: s) ?? ISO8601DateFormatter().date(from: s) {
                return d
            }
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "not a date: \(s)"))
        }
    }

    var url: URL { URL(string: "http://localhost:\(port)/")! }

    var canOpenInBrowser: Bool {
        if case .portInUse = state { return false }
        return true
    }

    private var helpers: URL {
        helpersOverride ?? Bundle.main.bundleURL.appendingPathComponent("Contents/Helpers", isDirectory: true)
    }

    // MARK: Lifecycle

    func start() {
        guard process == nil else { return }
        stopping = false
        restartTask?.cancel()
        setupRequired = false
        // Do not poll or open the browser on somebody else's service. A
        // bind probe reads nothing from the app occupying this port.
        guard ServerStartup.portIsAvailable(port) else {
            state = .portInUse(port)
            return
        }
        do {
            let fm = FileManager.default
            try fm.createDirectory(at: dataDir, withIntermediateDirectories: true)
            try fm.createDirectory(at: logURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            rotateLog()
            if !fm.fileExists(atPath: logURL.path) {
                fm.createFile(atPath: logURL.path, contents: nil)
            }
            let log = try FileHandle(forWritingTo: logURL)
            launchLogOffset = log.seekToEndOfFile()

            let p = Process()
            p.executableURL = helpers.appendingPathComponent("watchmymedia")
            p.arguments = ["serve"]
            var env = ProcessInfo.processInfo.environment
            env["WATCHMYMEDIA_DATA_DIR"] = dataDir.path
            env["WATCHMYMEDIA_PORT"] = String(port)
            env["WATCHMYMEDIA_EXIT_ON_STDIN_EOF"] = "1"
            // Check for updates in Settings links to the DMG, not a tarball.
            env["WATCHMYMEDIA_INSTALL"] = "macos-app"
            p.environment = env
            let stdin = Pipe()
            p.standardInput = stdin
            p.standardOutput = log
            p.standardError = log
            p.terminationHandler = { [weak self] proc in
                let status = proc.terminationStatus
                Task { @MainActor in self?.exited(process: proc, status: status) }
            }
            try p.run()
            process = p
            stdinPipe = stdin
            state = .starting
            startPolling()
        } catch {
            state = .failed(error.localizedDescription)
            scheduleRestart()
        }
    }

    /// Stops the server and calls back once it has exited; the delegate
    /// uses this before letting the app quit.
    func shutdown(then done: @escaping @MainActor () -> Void) {
        stopping = true
        restartTask?.cancel()
        pollTask?.cancel()
        releasePowerAssertion()
        guard let p = process else {
            state = .stopped
            done()
            return
        }
        p.terminate()
        let deadline = Date().addingTimeInterval(5)
        Task.detached {
            while p.isRunning, Date() < deadline {
                try? await Task.sleep(for: .milliseconds(100))
            }
            if p.isRunning {
                kill(p.processIdentifier, SIGKILL)
            }
            await MainActor.run {
                self.process = nil
                self.stdinPipe = nil
                self.sessions = []
                self.plays = []
                self.state = .stopped
                done()
            }
        }
    }

    func restart() {
        guard !stopping else { return }
        shutdown { [weak self] in
            self?.restartDelay = 1
            self?.start()
        }
    }

    func useAnotherPort() {
        guard process == nil else { return }
        do {
            port = try ServerStartup.unusedPort()
            defaults.set(port, forKey: "port")
            restartDelay = 1
            start()
        } catch {
            state = .failed("Couldn't find an available port. Choose Restart Server to try again.")
        }
    }

    func quit() {
        NSApp.terminate(nil)
    }

    private func exited(process exitedProcess: Process, status: Int32) {
        // A delayed termination callback from a stopped child must not
        // clear its replacement or schedule another restart.
        guard process === exitedProcess else { return }
        process = nil
        stdinPipe = nil
        pollTask?.cancel()
        sessions = []
        plays = []
        releasePowerAssertion()
        if stopping {
            state = .stopped
            return
        }
        if ServerStartup.addressInUse(currentLaunchLogTail()) {
            state = .portInUse(port)
            return
        }
        state = .failed("server exited with status \(status)")
        scheduleRestart()
    }

    private func scheduleRestart() {
        let delay = restartDelay
        restartDelay = min(restartDelay * 2, 60)
        restartTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard let self, !Task.isCancelled, !self.stopping, self.process == nil else { return }
            self.start()
        }
    }

    /// Keeps the log from growing forever: one previous file is kept.
    private func rotateLog() {
        let fm = FileManager.default
        let size = (try? fm.attributesOfItem(atPath: logURL.path)[.size] as? Int) ?? 0
        guard size > 20 * 1024 * 1024 else { return }
        let old = logURL.deletingPathExtension().appendingPathExtension("1.log")
        try? fm.removeItem(at: old)
        try? fm.moveItem(at: logURL, to: old)
    }

    private func currentLaunchLogTail() -> String {
        guard let handle = try? FileHandle(forReadingFrom: logURL) else { return "" }
        defer { try? handle.close() }
        do {
            let end = try handle.seekToEnd()
            try handle.seek(toOffset: max(launchLogOffset, end > 16_384 ? end - 16_384 : 0))
            return String(data: try handle.readToEnd() ?? Data(), encoding: .utf8) ?? ""
        } catch {
            return ""
        }
    }

    // MARK: Status

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.poll()
                try? await Task.sleep(for: .seconds(3))
            }
        }
    }

    private func poll() async {
        guard let child = process, child.isRunning else { return }
        var req = URLRequest(url: url.appendingPathComponent("api/playback/local"))
        req.timeoutInterval = 2
        do {
            let (data, resp) = try await session.data(for: req)
            guard process === child, child.isRunning, !Task.isCancelled else { return }
            guard (resp as? HTTPURLResponse)?.statusCode == 200 else { return }
            let status = try decoder.decode(LocalStatus.self, from: data)
            state = .running(version: status.version)
            sessions = status.sessions
            plays = status.plays ?? []
            setupRequired = status.needsSetup
            if setupRequired && !defaults.bool(forKey: "openedInitialSetup") {
                if openURL(url.appendingPathComponent("setup")) {
                    defaults.set(true, forKey: "openedInitialSetup")
                }
            }
            restartDelay = 1
            updatePowerAssertion()
        } catch {
            guard process === child, child.isRunning, !Task.isCancelled else { return }
            // Still starting, or between a crash and its restart; the
            // termination handler reports the latter.
            if case .running = state {
                state = .starting
            }
        }
    }

    /// Stops the Mac from idle-sleeping while something is streaming,
    /// direct play included.
    private func updatePowerAssertion() {
        if sessions.isEmpty && plays.isEmpty {
            releasePowerAssertion()
        } else if powerAssertion == 0 {
            var id: IOPMAssertionID = 0
            let ok = IOPMAssertionCreateWithName(
                kIOPMAssertionTypePreventUserIdleSystemSleep as CFString,
                IOPMAssertionLevel(kIOPMAssertionLevelOn),
                "WatchMyMedia is playing or recording" as CFString,
                &id)
            if ok == kIOReturnSuccess {
                powerAssertion = id
            }
        }
    }

    private func releasePowerAssertion() {
        if powerAssertion != 0 {
            IOPMAssertionRelease(powerAssertion)
            powerAssertion = 0
        }
    }

    // MARK: Actions

    func openInBrowser(path: String = "") {
        guard canOpenInBrowser else { return }
        _ = openURL(path.isEmpty ? url : url.appendingPathComponent(path))
    }

    func showLogs() {
        NSWorkspace.shared.activateFileViewerSelecting([logURL])
    }

    func setLaunchAtLogin(_ on: Bool) {
        do {
            if on {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            NSLog("launch at login: %@", error.localizedDescription)
        }
        launchAtLogin = SMAppService.mainApp.status == .enabled
    }
}
