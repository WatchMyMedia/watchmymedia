#if os(iOS) || os(macOS)
import AVFoundation
import Foundation
import Network
import WMMModel

/// Downloads with the system's background sessions (PLAN-DOWNLOADS.md D3), so
/// they go on while the app is away: an HLS download is an
/// `AVAssetDownloadURLSession` task, an original file and the subtitles beside
/// it `URLSession` download tasks. One download at a time, as on Android.
///
/// - An HLS download first fetches its master playlist itself with a fresh
///   `o1.` token: a 503 is waited out for its Retry-After (the server reading
///   keyframes, or the transcoder busy) while it shows Preparing, and 401,
///   403, 409 and 410 are told apart, which AVFoundation's errors cannot do.
///   The package keeps the token in its playlists, so a partial one older
///   than 5 hours starts again rather than resume.
/// - An original file goes with the owner's session as the bearer, which the
///   offline routes take, so its resume data still works hours later.
/// - Failures that are not refusals are tried again 10 times, waiting longer
///   each time and for the network to come back.
///
/// Everything runs on the main thread; the sessions deliver there too.
@MainActor
final class SystemDownloadEngine: NSObject, DownloadEngine {
    weak var delegate: (any EngineDelegate)?

    /// A download as the engine keeps it, in `engine.json` beside the files.
    private struct Entry: Codable {
        var request: EngineRequest
        var seq: Int
        var state: EngineDownload.State
        /// The HLS package (relative to the home folder) or the file (relative to `folder`), once it has one.
        var location: String?
        /// When the token in the HLS package's playlists was made.
        var tokenAt: Date?
        /// Finished subtitles by track ID, relative to `folder`.
        var parts: [String: String] = [:]
        var attempts = 0
        var notBefore: Date?
        var bytes: Int64 = 0
    }

    private static let maxAttempts = 10
    private static let maxPrepareWait: TimeInterval = 30 * 60
    private static let packageTokenLife: TimeInterval = 5 * 3600

    /// Background session events delivered while the app was away, by session identifier.
    static var completions: [String: () -> Void] = [:]
    /// Holds the app awake until the next download is handed to the system.
    private let handover = HandoverHold()

    private let folder: URL
    private let home = URL(fileURLWithPath: NSHomeDirectory())
    private var entries: [String: Entry] = [:]
    private var seq = 0
    private var wifiOnly = true
    private var path: NWPath?
    private let monitor = NWPathMonitor()
    private var fileSession: URLSession!
    private var hlsSession: AVAssetDownloadURLSession!
    private let probeSession: URLSession
    /// The running main task of each download, and its subtitle tasks.
    private var tasks: [String: URLSessionTask] = [:]
    private var partTasks: [String: URLSessionTask] = [:]
    /// Getting a download ready to fetch (its token, the master playlist), cancelled by pause or remove.
    private var preparing: [String: Task<Void, Never>] = [:]
    /// Why the HTTP response of a finished file task refused it, until the task completes.
    private var refusals: [String: EngineFailure] = [:]
    /// Downloads cancelled on purpose, so their completion is not a failure.
    private var stopping: [String: EngineDownload.State] = [:]
    private var wakeup: Task<Void, Never>?

    init(folder: URL, identifier: String = "media.watchmy.client.downloads") {
        self.folder = folder
        probeSession = URLSession(configuration: {
            let c = URLSessionConfiguration.ephemeral
            c.timeoutIntervalForRequest = 60
            return c
        }())
        super.init()
        load()
        let files = URLSessionConfiguration.background(withIdentifier: identifier + ".files")
        files.sessionSendsLaunchEvents = true
        files.isDiscretionary = false
        fileSession = URLSession(configuration: files, delegate: self, delegateQueue: .main)
        let hls = URLSessionConfiguration.background(withIdentifier: identifier + ".hls")
        hls.sessionSendsLaunchEvents = true
        hls.isDiscretionary = false
        hlsSession = AVAssetDownloadURLSession(configuration: hls, assetDownloadDelegate: self, delegateQueue: .main)
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in self?.pathChanged(path) }
        }
        monitor.start(queue: .main)
        Task { await reattach() }
    }

    // MARK: DownloadEngine

    func add(_ request: EngineRequest) {
        if var e = entries[request.id] {
            e.request = request
            if e.state == .failed || e.state == .paused { e.state = .queued }
            e.attempts = 0
            e.notBefore = nil
            entries[request.id] = e
        } else {
            seq += 1
            entries[request.id] = Entry(request: request, seq: seq, state: .queued)
        }
        save()
        changed(request.id)
        pump()
    }

    func remove(_ id: String) {
        guard let e = entries.removeValue(forKey: id) else { return }
        stopping[id] = .queued
        preparing.removeValue(forKey: id)?.cancel()
        tasks.removeValue(forKey: id)?.cancel()
        for (key, task) in partTasks where key.hasPrefix(id + "/") {
            task.cancel()
            partTasks[key] = nil
        }
        try? FileManager.default.removeItem(at: folder.appending(path: id))
        if e.request.hls, let media = mediaURL(e) { try? FileManager.default.removeItem(at: media) }
        save()
        pump()
    }

    func setPaused(_ id: String, _ paused: Bool) {
        guard var e = entries[id] else { return }
        if paused {
            guard e.state != .completed, e.state != .paused else { return }
            e.state = .paused
            entries[id] = e
            stopping[id] = .paused
            preparing.removeValue(forKey: id)?.cancel()
            if let task = tasks.removeValue(forKey: id) {
                if let file = task as? URLSessionDownloadTask {
                    file.cancel { [weak self] data in
                        Task { @MainActor in self?.keepResumeData(data, for: id) }
                    }
                } else {
                    task.cancel()
                }
            }
        } else {
            guard e.state == .paused || e.state == .failed else { return }
            e.state = .queued
            e.attempts = 0
            e.notBefore = nil
            entries[id] = e
        }
        save()
        changed(id)
        pump()
    }

    func downloads() -> [String: EngineDownload] {
        entries.mapValues { e in
            var fraction = e.state == .completed ? 1 : 0.0
            if let task = tasks[e.request.id], e.state == .downloading {
                fraction = task.progress.fractionCompleted
                if fraction <= 0, task.countOfBytesExpectedToReceive > 0 {
                    fraction = Double(task.countOfBytesReceived) / Double(task.countOfBytesExpectedToReceive)
                }
            }
            return EngineDownload(
                id: e.request.id, state: e.state, fraction: min(max(fraction, 0), 1), bytes: e.bytes,
                location: e.state == .completed ? mediaURL(e) : nil,
                parts: e.parts.mapValues { folder.appending(path: $0) }
            )
        }
    }

    func setWifiOnly(_ wifiOnly: Bool) {
        guard wifiOnly != self.wifiOnly else { return }
        self.wifiOnly = wifiOnly
        // The running download takes the new setting from its next request.
        if let id = entries.values.first(where: { $0.state == .downloading || $0.state == .preparing })?.request.id {
            setPaused(id, true)
            setPaused(id, false)
        }
        changed(nil)
    }

    func usedBytes() -> Int64 {
        Self.size(of: folder) + entries.values.reduce(0) { sum, e in
            guard e.request.hls, let location = e.location else { return sum }
            return sum + Self.size(of: home.appending(path: location))
        }
    }

    func space() -> (free: Int64, total: Int64) {
        let values = try? home.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey, .volumeTotalCapacityKey])
        return (values?.volumeAvailableCapacityForImportantUsage ?? 0, Int64(values?.volumeTotalCapacity ?? 0))
    }

    func notMet() -> DownloadStatus.Reason? {
        guard let path else { return nil }
        return Self.notMet(satisfied: path.status == .satisfied, wifi: path.usesInterfaceType(.wifi),
                           wired: path.usesInterfaceType(.wiredEthernet), expensive: path.isExpensive,
                           constrained: path.isConstrained, wifiOnly: wifiOnly)
    }

    /// What holds downloads back on a network. Wi-Fi only means no mobile
    /// data, so a phone's personal hotspot (Wi-Fi the system marks
    /// expensive) and Low Data Mode (constrained) wait as mobile data does.
    static func notMet(satisfied: Bool, wifi: Bool, wired: Bool, expensive: Bool, constrained: Bool, wifiOnly: Bool) -> DownloadStatus.Reason? {
        if !satisfied { return .network }
        if wifiOnly && (!(wifi || wired) || expensive || constrained) { return .wifi }
        return nil
    }

    /// A request kept to Wi-Fi and wired networks that cost nothing while Wi-Fi only is on.
    private func limited(_ request: URLRequest) -> URLRequest {
        var request = request
        request.allowsCellularAccess = !wifiOnly
        request.allowsExpensiveNetworkAccess = !wifiOnly
        request.allowsConstrainedNetworkAccess = !wifiOnly
        return request
    }

    // MARK: Running

    /// Starts the next queued download when none runs.
    private func pump() {
        guard !entries.values.contains(where: { $0.state == .preparing || $0.state == .downloading }) else { return }
        let now = Date()
        let queued = entries.values.filter { $0.state == .queued }.sorted { $0.seq < $1.seq }
        guard let next = queued.first(where: { ($0.notBefore ?? .distantPast) <= now }) else {
            if let soonest = queued.compactMap(\.notBefore).min() { wake(at: soonest) }
            return
        }
        // Waiting for a network (or Wi-Fi) would only spend the retries.
        if notMet() != nil, next.attempts > 0 { return }
        start(next.request.id)
    }

    private func wake(at date: Date) {
        wakeup?.cancel()
        wakeup = Task { [weak self] in
            try? await Task.sleep(for: .seconds(max(date.timeIntervalSinceNow, 0.5)))
            guard !Task.isCancelled else { return }
            self?.pump()
        }
    }

    private func start(_ id: String) {
        guard var e = entries[id] else { return }
        e.state = .preparing
        entries[id] = e
        save()
        changed(id)
        startParts(e)
        preparing[id] = Task { [weak self] in
            guard let self else { return }
            if e.request.hls { await self.startHLS(id) } else { self.startFile(id) }
            self.preparing[id] = nil
        }
    }

    private func startFile(_ id: String) {
        guard let e = entries[id], e.state == .preparing else { return }
        guard let request = delegate?.request(for: id, url: e.request.url).map(limited) else { return giveUp(id, .refused(401)) }
        let task: URLSessionDownloadTask
        if let data = try? Data(contentsOf: resumeFile(id)) {
            task = fileSession.downloadTask(withResumeData: data)
            try? FileManager.default.removeItem(at: resumeFile(id))
        } else {
            task = fileSession.downloadTask(with: request)
        }
        task.taskDescription = id
        running(id, task)
    }

    private func startHLS(_ id: String) async {
        guard let delegate, let e = entries[id] else { return }
        let started = Date()
        var renewed = false
        while !Task.isCancelled {
            let token: MediaToken
            do {
                token = try await delegate.token(for: id)
            } catch {
                return tokenFailed(id, error)
            }
            let url = token.apply(to: e.request.url)
            let probe = limited(URLRequest(url: url))
            let status: Int
            let retryAfter: TimeInterval
            do {
                let (_, response) = try await probeSession.data(for: probe)
                let http = response as? HTTPURLResponse
                status = http?.statusCode ?? 0
                retryAfter = Self.retryAfter(http?.value(forHTTPHeaderField: "Retry-After"))
            } catch {
                if Task.isCancelled { return }
                return retry(id)
            }
            switch status {
            case 200..<300:
                return fetchHLS(id, url: url)
            case 401 where !renewed:
                renewed = true
            case 401, 403, 410:
                return giveUp(id, .refused(status))
            case 409:
                return giveUp(id, .restart)
            case 503 where Date().timeIntervalSince(started) + retryAfter < Self.maxPrepareWait:
                try? await Task.sleep(for: .seconds(retryAfter))
            default:
                return retry(id)
            }
        }
    }

    private func fetchHLS(_ id: String, url: URL) {
        guard var e = entries[id], e.state == .preparing else { return }
        let asset: AVURLAsset
        if let location = e.location, let tokenAt = e.tokenAt, Date().timeIntervalSince(tokenAt) < Self.packageTokenLife,
           FileManager.default.fileExists(atPath: home.appending(path: location).path) {
            // What came so far, with the playlists it was fetched by.
            asset = AVURLAsset(url: home.appending(path: location))
        } else {
            if let location = e.location { try? FileManager.default.removeItem(at: home.appending(path: location)) }
            e.location = nil
            e.tokenAt = Date()
            entries[id] = e
            asset = AVURLAsset(url: url, options: [
                AVURLAssetAllowsCellularAccessKey: !wifiOnly,
                AVURLAssetAllowsExpensiveNetworkAccessKey: !wifiOnly,
                AVURLAssetAllowsConstrainedNetworkAccessKey: !wifiOnly,
            ])
        }
        let task = hlsSession.makeAssetDownloadTask(downloadConfiguration: AVAssetDownloadConfiguration(asset: asset, title: e.request.title))
        task.taskDescription = id
        running(id, task)
    }

    private func running(_ id: String, _ task: URLSessionTask) {
        guard var e = entries[id] else { return task.cancel() }
        e.state = .downloading
        entries[id] = e
        tasks[id] = task
        task.resume()
        save()
        changed(id)
    }

    /// Subtitles are small: they go at once, beside the media.
    private func startParts(_ e: Entry) {
        for (track, url) in e.request.parts where e.parts[track] == nil && partTasks[partKey(e.request.id, track)] == nil {
            guard let request = delegate?.request(for: e.request.id, url: url).map(limited) else { continue }
            let task = fileSession.downloadTask(with: request)
            task.taskDescription = partKey(e.request.id, track)
            partTasks[partKey(e.request.id, track)] = task
            task.resume()
        }
    }

    private func tokenFailed(_ id: String, _ error: Error) {
        switch error {
        case is SignedOutError: giveUp(id, .refused(401))
        case let api as APIError where api.status == 403 || api.status == 410: giveUp(id, .refused(api.status ?? 403))
        case is CancellationError: break
        default: retry(id)
        }
    }

    /// Tries again later, waiting longer each time; fails after ``maxAttempts``.
    private func retry(_ id: String) {
        guard var e = entries[id], e.state == .preparing || e.state == .downloading else { return }
        tasks[id] = nil
        e.attempts += 1
        guard e.attempts < Self.maxAttempts else {
            entries[id] = e
            return giveUp(id, .failed)
        }
        e.state = .queued
        e.notBefore = Date().addingTimeInterval(min(10 * Double(e.attempts), 120))
        entries[id] = e
        save()
        changed(id)
        pump()
    }

    private func giveUp(_ id: String, _ failure: EngineFailure) {
        guard var e = entries[id] else { return }
        tasks[id] = nil
        e.state = .failed
        entries[id] = e
        save()
        delegate?.engineChanged(id, failure: failure)
        pump()
    }

    private func completed(_ id: String) {
        guard var e = entries[id] else { return }
        tasks[id] = nil
        e.state = .completed
        e.attempts = 0
        if let media = mediaURL(e) {
            Self.excludeFromBackup(media)
            e.bytes = Self.size(of: media)
        }
        entries[id] = e
        // A subtitle that failed tries once more.
        startParts(e)
        save()
        changed(id)
        pump()
    }

    private func keepResumeData(_ data: Data?, for id: String) {
        guard let data, entries[id] != nil else { return }
        try? FileManager.default.createDirectory(at: folder.appending(path: id), withIntermediateDirectories: true)
        try? data.write(to: resumeFile(id), options: .atomic)
    }

    private func pathChanged(_ new: NWPath) {
        let wasMet = notMet() == nil
        path = new
        if !wasMet && notMet() == nil {
            // Back online: what waited goes at once.
            for (id, var e) in entries where e.state == .queued {
                e.notBefore = nil
                entries[id] = e
            }
            pump()
        }
        changed(nil)
    }

    /// After a relaunch: the sessions' tasks still running, and downloads whose preparation the app's end cut short.
    private func reattach() async {
        let files = await fileSession.allTasks
        let hls = await hlsSession.allTasks
        for task in files + hls {
            guard let description = task.taskDescription else { continue }
            if description.contains("/") {
                partTasks[description] = task
            } else if entries[description]?.state == .downloading || entries[description]?.state == .preparing {
                tasks[description] = task
                entries[description]?.state = .downloading
            } else {
                task.cancel()
            }
        }
        for (id, e) in entries where (e.state == .downloading || e.state == .preparing) && tasks[id] == nil && preparing[id] == nil {
            entries[id]?.state = .queued
        }
        save()
        changed(nil)
        pump()
    }

    // MARK: Files

    private func partKey(_ id: String, _ track: String) -> String { "\(id)/\(track)" }

    private func resumeFile(_ id: String) -> URL { folder.appending(path: id).appending(path: "resume.data") }

    private func mediaURL(_ e: Entry) -> URL? {
        guard let location = e.location else { return nil }
        return e.request.hls ? home.appending(path: location) : folder.appending(path: location)
    }

    private func changed(_ id: String?) {
        delegate?.engineChanged(id, failure: nil)
    }

    private var indexFile: URL { folder.appending(path: "engine.json") }

    private func load() {
        guard let data = try? Data(contentsOf: indexFile), let saved = try? JSONDecoder().decode([Entry].self, from: data) else { return }
        entries = Dictionary(saved.map { ($0.request.id, $0) }, uniquingKeysWith: { first, _ in first })
        seq = saved.map(\.seq).max() ?? 0
    }

    private func save() {
        // Every change of state is saved: while one is being made ready, the app stays awake for it.
        handover.setPreparing(entries.values.contains { $0.state == .preparing })
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        guard let data = try? JSONEncoder().encode(Array(entries.values)) else { return }
        try? data.write(to: indexFile, options: .atomic)
        Self.excludeFromBackup(folder)
    }

    /// The wait a 503 asks for, in seconds: 10 when it names none, at most 5 minutes.
    static func retryAfter(_ value: String?) -> TimeInterval {
        guard let seconds = value.flatMap({ Double($0.trimmingCharacters(in: .whitespaces)) }) else { return 10 }
        return min(max(seconds, 1), 300)
    }

    static func size(of url: URL) -> Int64 {
        let keys: Set<URLResourceKey> = [.totalFileAllocatedSizeKey, .fileAllocatedSizeKey, .isDirectoryKey]
        guard let values = try? url.resourceValues(forKeys: keys) else { return 0 }
        if values.isDirectory != true { return Int64(values.totalFileAllocatedSize ?? values.fileAllocatedSize ?? 0) }
        guard let walk = FileManager.default.enumerator(at: url, includingPropertiesForKeys: Array(keys)) else { return 0 }
        var total: Int64 = 0
        for case let file as URL in walk {
            let v = try? file.resourceValues(forKeys: keys)
            if v?.isDirectory != true { total += Int64(v?.totalFileAllocatedSize ?? v?.fileAllocatedSize ?? 0) }
        }
        return total
    }

    static func excludeFromBackup(_ url: URL) {
        var url = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }
}

extension SystemDownloadEngine: AVAssetDownloadDelegate, URLSessionDownloadDelegate {
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // The file goes before this returns, or the system deletes it.
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
        let description = downloadTask.taskDescription ?? ""
        MainActor.assumeIsolated { finishedFile(description, status: status, at: location) }
    }

    nonisolated func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, willDownloadTo location: URL) {
        let description = assetDownloadTask.taskDescription ?? ""
        // Relative to the home folder, which moves with each install.
        let relative = location.relativePath
        MainActor.assumeIsolated {
            guard var e = entries[description] else { return }
            e.location = relative
            entries[description] = e
            save()
        }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let description = task.taskDescription ?? ""
        let resumeData = (error as? URLError)?.downloadTaskResumeData
        let cancelled = (error as? URLError)?.code == .cancelled
        MainActor.assumeIsolated { taskEnded(description, task: task, failed: error != nil, cancelled: cancelled, resumeData: resumeData) }
    }

    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        let identifier = session.configuration.identifier ?? ""
        // Delivered after the session's other events, so a download that
        // finished has started the next one by now: the app is suspended
        // only once that one is handed to the system.
        MainActor.assumeIsolated {
            guard let completion = Self.completions.removeValue(forKey: identifier) else { return }
            handover.delivered(completion)
        }
    }

    private func finishedFile(_ description: String, status: Int, at location: URL) {
        let parts = description.split(separator: "/", maxSplits: 1).map(String.init)
        let id = parts[0]
        guard var e = entries[id] else { return }
        let fm = FileManager.default
        try? fm.createDirectory(at: folder.appending(path: id), withIntermediateDirectories: true)
        if parts.count == 2 {
            let track = parts[1]
            partTasks[description] = nil
            guard (200..<300).contains(status), let url = e.request.parts[track] else { return }
            let name = "sub-\(track).\(url.pathExtension.isEmpty ? "vtt" : url.pathExtension)"
            let target = folder.appending(path: id).appending(path: name)
            try? fm.removeItem(at: target)
            guard (try? fm.moveItem(at: location, to: target)) != nil else { return }
            e.parts[track] = "\(id)/\(name)"
            entries[id] = e
            save()
            return
        }
        switch status {
        case 200..<300:
            let name = "media.\(e.request.fileExtension ?? "mp4")"
            let target = folder.appending(path: id).appending(path: name)
            try? fm.removeItem(at: target)
            if (try? fm.moveItem(at: location, to: target)) != nil {
                e.location = "\(id)/\(name)"
                entries[id] = e
            }
        case 401, 403, 410: refusals[id] = .refused(status)
        case 409: refusals[id] = .restart
        default: refusals[id] = .failed
        }
    }

    private func taskEnded(_ description: String, task: URLSessionTask, failed: Bool, cancelled: Bool, resumeData: Data?) {
        if description.contains("/") {
            if partTasks[description] === task { partTasks[description] = nil }
            return
        }
        let id = description
        if let state = stopping.removeValue(forKey: id), tasks[id] == nil || tasks[id] === task {
            // Paused or removed: what came is kept for later, or already gone.
            if state == .paused, let resumeData { keepResumeData(resumeData, for: id) }
            if tasks[id] === task { tasks[id] = nil }
            return
        }
        guard tasks[id] === task || tasks[id] == nil, let e = entries[id], e.state == .downloading else { return }
        if let refusal = refusals.removeValue(forKey: id) {
            tasks[id] = nil
            return refusal == .failed ? retry(id) : giveUp(id, refusal)
        }
        if failed || cancelled {
            if let resumeData { keepResumeData(resumeData, for: id) }
            return retry(id)
        }
        guard e.location != nil else { return retry(id) }
        completed(id)
    }
}
#endif
