import Foundation
import Observation
import WMMModel

/// What a download's end says to the person: local notifications on a device.
@MainActor
protocol DownloadNotices: AnyObject {
    /// The first download is starting: the moment to ask whether notices may show.
    func firstDownload()

    func downloaded(_ title: String)

    func failed(_ title: String)

    /// An admin revoked it, or the person may no longer download: it was deleted.
    func removedByAdmin(_ title: String)
}

/// Downloads on this device (PLAN-DOWNLOADS.md §5, §6, D3): records and
/// progress in the store, bytes in the engine, the server through the
/// gateway (android/core/downloads/.../RealDownloadRepository.kt).
///
/// - Downloads belong to the account that made them; one no longer signed
///   in (signed out, removed, or refused with 401) has its downloads deleted.
/// - A sync sends what this device holds and the progress made on it, and
///   applies ``reconcile(_:_:)``'s rule to what comes back.
/// - Progress on a downloaded item goes to the queue, never to the server's
///   progress route; it is synced by when it happened.
@MainActor
@Observable
public final class RealDownloadRepository: DownloadRepository, EngineDelegate {
    private(set) var stored: StoredDownloads {
        didSet { if stored != oldValue { store.save(stored) } }
    }
    private(set) var owner: OfflineOwner?
    /// Bumped whenever the engine changes, so what reads `downloads` looks again.
    private var engineTick = 0

    @ObservationIgnored private let store: any OfflineStore
    @ObservationIgnored private let gateway: any OfflineGateway
    @ObservationIgnored private let engine: any DownloadEngine
    @ObservationIgnored private let client: @MainActor () -> ClientInfo
    @ObservationIgnored private let profile: @MainActor () -> PlaybackProfile
    @ObservationIgnored private let notices: any DownloadNotices
    @ObservationIgnored private let now: @MainActor () -> Date
    @ObservationIgnored private var parsed: [String: (json: Data, record: OfflineRecord)] = [:]
    @ObservationIgnored private var plans: [Int: DownloadPlan] = [:]
    /// A create can yield before its record reaches the queue. Reserve its
    /// planned bytes now so another create cannot spend the same free space.
    @ObservationIgnored private var reservations: [UUID: (owner: OfflineOwner, bytes: Int64)] = [:]
    @ObservationIgnored private var creates: [CreateKey: PendingCreate] = [:]
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var syncing: Task<Void, Never>?
    @ObservationIgnored private var tokens: [String: MediaToken] = [:]
    /// The device's name for the menu's Wi-Fi line ("iPhone", "iPad").
    @ObservationIgnored let deviceKind: String

    private struct CreateKey: Hashable {
        var ownerKey: String
        var baseURL: URL
        var fileID: Int
    }

    private struct PendingCreate {
        var id: UUID
        var choice: DownloadChoice
        var task: Task<Void, Error>
    }

    init(
        store: any OfflineStore,
        gateway: any OfflineGateway,
        engine: any DownloadEngine,
        client: @escaping @MainActor () -> ClientInfo,
        profile: @escaping @MainActor () -> PlaybackProfile,
        notices: any DownloadNotices,
        deviceKind: String = "iPhone",
        now: @escaping @MainActor () -> Date = { Date() }
    ) {
        self.store = store
        self.gateway = gateway
        self.engine = engine
        self.client = client
        self.profile = profile
        self.notices = notices
        self.deviceKind = deviceKind
        self.now = now
        stored = store.load()
        owner = gateway.owner()
        engine.delegate = self
        engine.setWifiOnly(stored.wifiOnly)
        gateway.onSessionChange { [weak self] in self?.sessionChanged() }
        gateway.beforeSignOut { [weak self] in await self?.reportAllRemoved() }
        sessionChanged()
        startTicker()
    }

    // MARK: What the screens see

    public var canDownload: Bool {
        guard let owner else { return false }
        return ServerFeatures(apiLevel: owner.apiLevel).offline && stored.allowed[owner.key] == true
    }

    public var enabled: Bool { canDownload || !downloads.isEmpty }

    public var settings: DownloadSettings { DownloadSettings(quality: DownloadQuality(id: stored.quality), wifiOnly: stored.wifiOnly) }

    public var downloads: [OfflineDownload] {
        _ = engineTick
        guard let owner else { return [] }
        let held = engine.downloads()
        let notMet = engine.notMet()
        return stored.records.filter { $0.ownerKey == owner.key }.compactMap { rec -> OfflineDownload? in
            guard let record = parse(rec) else { return nil }
            let e = held[rec.id]
            let status: DownloadStatus = if let e {
                Self.status(e, notMet: notMet, sizeBytes: record.sizeBytes)
            } else if rec.completedAt != nil {
                .downloaded
            } else {
                .failed
            }
            let done = status == .downloaded
            let duration = record.decision?.durationMs ?? 0
            return OfflineDownload(
                id: rec.id, fileID: rec.fileID, item: record.item, quality: record.quality, mode: record.mode, protocol: record.protocol,
                sizeBytes: done && (e?.bytes ?? 0) > 0 ? e!.bytes : record.sizeBytes,
                sizeEstimated: !done && record.sizeEstimated,
                status: status, mark: rec.mark, replacedBy: rec.replacedBy, createdAt: rec.createdAt, completedAt: rec.completedAt,
                durationMs: duration, watch: watch(rec, record)
            )
        }.sorted { $0.createdAt > $1.createdAt }
    }

    /// An engine download as the person sees it.
    static func status(_ e: EngineDownload, notMet: DownloadStatus.Reason?, sizeBytes: Int64) -> DownloadStatus {
        let percent = Int((e.fraction * 100).rounded(.down))
        let bytes = Int64(e.fraction * Double(sizeBytes))
        switch e.state {
        case .completed: return .downloaded
        case .failed: return .failed
        case .paused: return .paused(percent: percent, bytes: bytes)
        case .queued: return .waiting(notMet ?? .turn)
        // Its token and master playlist, and any wait the server asks for (keyframes, the transcoder busy).
        case .preparing: return notMet.map { .waiting($0) } ?? .preparing
        case .downloading: return notMet.map { .waiting($0) } ?? .downloading(percent: percent, bytes: bytes)
        }
    }

    /// Where the viewer is: the newest thing done here, else what the server last said.
    private func watch(_ rec: StoredRecord, _ record: OfflineRecord) -> WatchProgress? {
        let duration = record.decision?.durationMs ?? 0
        let latest = stored.progress.filter { $0.ownerKey == rec.ownerKey && $0.fileID == rec.fileID }.max { $0.at < $1.at }
        if let latest {
            if latest.watched == true { return WatchProgress(positionMs: 0, durationMs: duration, completed: true) }
            if latest.watched == false { return nil }
            let pos = latest.positionMs ?? 0
            let dur = latest.durationMs.flatMap { $0 > 0 ? $0 : nil } ?? duration
            return WatchProgress(positionMs: pos, durationMs: dur, completed: Self.watchedPast(pos, dur, record))
        }
        if let pos = rec.watchPositionMs { return WatchProgress(positionMs: pos, durationMs: rec.watchDurationMs ?? duration, completed: rec.watchCompleted) }
        if rec.watchCompleted { return WatchProgress(positionMs: 0, durationMs: duration, completed: true) }
        guard let resume = record.decision?.resumeMs, resume > 0 else { return nil }
        return WatchProgress(positionMs: resume, durationMs: duration, completed: false)
    }

    /// Past the credits (or 92% without them) counts as watched, as on the server.
    static func watchedPast(_ positionMs: Int, _ durationMs: Int, _ record: OfflineRecord) -> Bool {
        let at = record.decision.flatMap { $0.watchedAtMs > 0 ? $0.watchedAtMs : nil } ?? Int(Double(durationMs) * 0.92)
        return durationMs > 0 && positionMs >= at
    }

    private func parse(_ rec: StoredRecord) -> OfflineRecord? {
        if let cached = parsed[rec.id], cached.json == rec.json { return cached.record }
        do {
            let record = try gateway.parse(rec.owner, json: rec.json)
            parsed[rec.id] = (rec.json, record)
            return record
        } catch {
            downloadsLog.error("could not read download \(rec.id, privacy: .public): \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    func record(_ id: String) -> OfflineRecord? { stored.record(id).flatMap(parse) }

    // MARK: The account

    /// Another account or server, or signed out: deletes what belongs to accounts no longer signed in, then checks what this one may do.
    private func sessionChanged() {
        owner = gateway.owner()
        plans = [:]
        prune()
        Task { await refreshAllowed() }
    }

    /// Deletes the downloads, progress and pending reports of every account no longer signed in.
    private func prune() {
        let gone = stored.records.filter { !gateway.signedIn($0.owner) }
        let signedIn = { (key: String) in self.signedIn(key) }
        guard !gone.isEmpty || !stored.progress.allSatisfy({ signedIn($0.ownerKey) }) || !stored.removed.allSatisfy({ signedIn($0.ownerKey) }) else { return }
        for rec in gone { removeBytes(rec) }
        let ids = Set(gone.map(\.id))
        var s = stored
        s.records.removeAll { ids.contains($0.id) }
        s.progress.removeAll { !signedIn($0.ownerKey) }
        s.removed.removeAll { !signedIn($0.ownerKey) }
        s.allowed = s.allowed.filter { signedIn($0.key) }
        stored = s
    }

    private func signedIn(_ key: String) -> Bool {
        guard let slash = key.lastIndex(of: "/"), let user = Int(key[key.index(after: slash)...]) else { return false }
        return gateway.signedIn(OfflineOwner(serverID: String(key[..<slash]), userID: user, baseURL: URL(string: "about:blank")!, apiLevel: 0))
    }

    private func refreshAllowed() async {
        guard let owner else { return }
        do {
            let allowed = try await gateway.allowed(owner)
            stored.allowed[owner.key] = allowed
        } catch {
            // Offline, or the server is away: what was last heard stands.
        }
    }

    // MARK: The engine

    func engineChanged(_ id: String?, failure: EngineFailure?) {
        engineTick += 1
        guard let id else {
            // The network changed: back online, the queue goes to the server.
            if engine.notMet() == nil { scheduleSync() }
            return
        }
        guard let rec = stored.record(id) else { return }
        let e = engine.downloads()[id]
        if e?.state == .completed, rec.completedAt == nil { completed(rec) }
        switch failure {
        case .refused?:
            // The sync explains it: deleted or marked.
            scheduleSync()
        case .restart?:
            restart(id)
        case .failed?:
            if let record = parse(rec) { notices.failed(downloadTitle(record.item)) }
        case nil:
            break
        }
        if engine.downloads().values.contains(where: { $0.state == .downloading || $0.state == .preparing }) { startTicker() }
    }

    func token(for id: String) async throws -> MediaToken {
        guard let rec = stored.record(id) else { throw APIError(status: 410, message: "Gone") }
        if let t = tokens[id], t.expiresAt.timeIntervalSince(now()) > 600 { return t }
        let t = try await gateway.token(rec.owner, downloadID: id)
        tokens[id] = t
        return t
    }

    func request(for id: String, url: URL) -> URLRequest? {
        guard let rec = stored.record(id) else { return nil }
        return gateway.authorizedRequest(rec.owner, url: url)
    }

    private func completed(_ rec: StoredRecord) {
        stored.updateRecord(rec.id) { $0.completedAt = now() }
        if let record = parse(rec) { notices.downloaded(downloadTitle(record.item)) }
        // A new version done: the old copy goes.
        if let old = rec.replaces.flatMap(stored.record) { removeRecord(old, report: true) }
        scheduleSync()
    }

    private func restart(_ id: String) {
        guard let rec = stored.record(id), let record = parse(rec) else { return }
        downloadsLog.info("download \(id, privacy: .public) starts over: the server's session changed")
        engine.remove(id)
        stored.updateRecord(id) { $0.completedAt = nil }
        enqueue(record)
    }

    private func enqueue(_ record: OfflineRecord) {
        let hls = record.protocol == .hls
        var parts: [String: URL] = [:]
        for sub in record.decision?.subtitles ?? [] where sub.format != "image" && sub.option.id != record.decision?.burnInSubtitleID {
            if let url = sub.url { parts[sub.option.id] = url }
        }
        let ext = hls ? nil : Self.fileExtension(record.decision?.mimeType)
        engine.add(EngineRequest(id: record.id, url: record.url, hls: hls, title: downloadTitle(record.item), parts: parts, fileExtension: ext))
    }

    /// "mp4", "mov", "m4v" from the decision's MIME type.
    static func fileExtension(_ mime: String?) -> String {
        switch mime {
        case "video/quicktime": "mov"
        case "video/x-m4v": "m4v"
        default: "mp4"
        }
    }

    private func removeBytes(_ rec: StoredRecord) {
        engine.remove(rec.id)
        parsed[rec.id] = nil
        tokens[rec.id] = nil
    }

    /// Deletes the copy; `report` tells the server at the next sync.
    private func removeRecord(_ rec: StoredRecord, report: Bool) {
        removeBytes(rec)
        var s = stored
        s.records.removeAll { $0.id == rec.id }
        if report { s.removed.append(StoredRemoval(id: rec.id, ownerKey: rec.ownerKey)) }
        stored = s
    }

    /// Looks again each second while something downloads: the sessions' progress has no callback worth taking.
    private func startTicker() {
        guard ticker == nil else { return }
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard let self else { return }
                let busy = self.engine.downloads().values.contains { $0.state == .downloading || $0.state == .preparing }
                self.engineTick += 1
                if !busy { break }
            }
            self?.ticker = nil
        }
    }

    // MARK: What the screens do

    public func plan(fileID: Int) async throws -> DownloadPlan {
        guard let owner else { throw SignedOutError() }
        let plan = try await gateway.plan(owner, fileID: fileID, profile: profile())
        guard self.owner?.key == owner.key, self.owner?.baseURL == owner.baseURL,
              gateway.signedIn(owner) else { throw CancellationError() }
        plans[fileID] = plan
        return plan
    }

    public func download(_ choice: DownloadChoice) async throws {
        guard let owner else { throw SignedOutError() }
        let key = CreateKey(ownerKey: owner.key, baseURL: owner.baseURL, fileID: choice.fileID)
        if let pending = creates[key] {
            guard pending.choice == choice else {
                throw APIError(status: nil, message: "This file is already being prepared for download. Wait for it to finish before choosing different options.")
            }
            try await pending.task.value
            return
        }
        let requestedProfile = profile()
        let requestedClient = client()
        let id = UUID()
        let task = Task {
            try await self.create(choice, owner: owner, profile: requestedProfile, client: requestedClient)
        }
        creates[key] = PendingCreate(id: id, choice: choice, task: task)
        defer { if creates[key]?.id == id { creates[key] = nil } }
        try await task.value
    }

    private func create(_ choice: DownloadChoice, owner: OfflineOwner, profile: PlaybackProfile, client: ClientInfo) async throws {
        // The shared task may start after an account switch. Never prepare it
        // using the new account, client or profile after yielding to the actor.
        guard self.owner?.key == owner.key, self.owner?.baseURL == owner.baseURL,
              gateway.signedIn(owner) else { throw CancellationError() }
        let plan: DownloadPlan
        if let cached = plans[choice.fileID] { plan = cached }
        else {
            plan = try await gateway.plan(owner, fileID: choice.fileID, profile: profile)
            guard self.owner?.key == owner.key, self.owner?.baseURL == owner.baseURL,
                  gateway.signedIn(owner) else { throw CancellationError() }
            plans[choice.fileID] = plan
        }
        guard let option = plan.option(choice.quality) else {
            throw APIError(status: nil, message: "Download options changed. Open Download again.")
        }
        guard availableStorage().fits(option.sizeBytes) else { throw insufficientSpace(option.sizeBytes) }
        let reservation = UUID()
        reservations[reservation] = (owner, option.sizeBytes)
        defer { reservations[reservation] = nil }
        let created = try await gateway.create(owner, choice: choice, profile: profile, client: client)
        // Sign-out may have pruned this account while the create was in
        // flight. Do not put its late reply back into storage or the engine.
        guard gateway.signedIn(owner) else { throw SignedOutError() }
        let record = created.record
        // A repeated reply for this account's accepted record is already
        // queued or held. Keep its progress, pause and completion state intact.
        if stored.records.contains(where: { $0.ownerKey == owner.key && $0.fileID == record.fileID && $0.id == record.id }) { return }
        // A fresh decision can be larger than its plan. Keep existing copies
        // and report the rejected new record removed without starting its bytes.
        guard availableStorage(excluding: reservation).fits(record.sizeBytes) else {
            if stored.record(record.id) == nil {
                stored.removed.append(StoredRemoval(id: record.id, ownerKey: owner.key))
                await syncOwner(owner, again: false)
            }
            throw insufficientSpace(record.sizeBytes)
        }
        let first = stored.records.isEmpty
        // The server replaced this device's earlier download of the file; so does this.
        for old in stored.records where old.ownerKey == owner.key && old.fileID == record.fileID && old.id != record.id {
            removeRecord(old, report: false)
        }
        let replaces = stored.records.first { $0.ownerKey == owner.key && $0.replacedBy == record.fileID }?.id
        stored.records.append(StoredRecord(id: record.id, owner: owner, json: created.json, createdAt: now(), fileID: record.fileID, replaces: replaces))
        parsed[record.id] = (created.json, record)
        enqueue(record)
        if first { notices.firstDownload() }
    }

    public func pause(_ id: String) { engine.setPaused(id, true) }

    public func resume(_ id: String) { engine.setPaused(id, false) }

    public func retry(_ id: String) {
        guard let record = record(id) else { return }
        enqueue(record)
    }

    public func remove(_ id: String) {
        guard let rec = stored.record(id) else { return }
        removeRecord(rec, report: true)
        scheduleSync()
    }

    public func removeAll() {
        guard let owner else { return }
        for rec in stored.records where rec.ownerKey == owner.key { removeRecord(rec, report: true) }
        scheduleSync()
    }

    public func storage() -> DownloadStorage {
        let (free, total) = engine.space()
        return DownloadStorage(usedBytes: engine.usedBytes(), freeBytes: free, totalBytes: total)
    }

    public func availableStorage() -> DownloadStorage { availableStorage(excluding: nil) }

    /// Only aggregate bytes leave the repository; another account's titles
    /// and records remain hidden from the current account's `downloads`.
    private func availableStorage(excluding reservation: UUID?) -> DownloadStorage {
        _ = engineTick
        var result = storage()
        func reserve(_ bytes: Int64) {
            result.freeBytes = max(0, result.freeBytes - min(max(0, result.freeBytes), max(0, bytes)))
        }
        let held = engine.downloads()
        for rec in stored.records where gateway.signedIn(rec.owner) {
            guard let transfer = held[rec.id], let record = parse(rec) else { continue }
            switch transfer.state {
            case .queued, .preparing, .downloading, .paused:
                let size = max(0, record.sizeBytes)
                let fraction = transfer.fraction.isFinite ? min(max(transfer.fraction, 0), 1) : 0
                let written = Int64(exactly: (Double(size) * fraction).rounded(.down)) ?? 0
                reserve(size - min(size, max(0, written)))
            case .completed, .failed: break
            }
        }
        for (id, pending) in reservations where id != reservation && gateway.signedIn(pending.owner) {
            reserve(pending.bytes)
        }
        return result
    }

    private func insufficientSpace(_ bytes: Int64) -> APIError {
        APIError(status: nil, message: "Needs \(neededSize(bytes)) free. Pick a lower quality or free up space.")
    }

    public func setQuality(_ quality: DownloadQuality) { stored.quality = quality.id }

    public func setWifiOnly(_ wifiOnly: Bool) {
        stored.wifiOnly = wifiOnly
        engine.setWifiOnly(wifiOnly)
    }

    public func mark(fileID: Int, watched: Bool) {
        guard let owner, let rec = stored.records.first(where: { $0.ownerKey == owner.key && $0.fileID == fileID }) else { return }
        let at = now()
        stored.addProgress { StoredProgress(seq: $0, ownerKey: rec.ownerKey, fileID: fileID, downloadID: rec.id, at: at, watched: watched) }
        scheduleSync()
    }

    // MARK: Offline plays (OfflinePlaybackGateway)

    /// The current account's finished download of `fileID`, to play from the device.
    func playable(_ fileID: Int) -> OfflineDownload? {
        downloads.first { $0.fileID == fileID && $0.downloaded }
    }

    /// The play of `fileID` from the device, or nil when it isn't downloaded here.
    func offlinePlay(_ fileID: Int) -> OfflinePlay? {
        guard let d = playable(fileID), let stored = record(d.id)?.decision, let e = engine.downloads()[d.id], let location = e.location else { return nil }
        let next = stored.next.map { playable($0.fileID) != nil } ?? false
        return OfflinePlay(download: d, decision: OfflinePlaybackGateway.offlineDecision(d, stored, media: location, parts: e.parts, nextDownloaded: next))
    }

    /// A heartbeat of an offline play: the newest position goes to the queue, and a play that ends syncs.
    func progress(_ play: OfflinePlay, _ report: ProgressReport) {
        guard let rec = stored.record(play.download.id) else { return }
        let at = now()
        stored.addProgress {
            StoredProgress(seq: $0, ownerKey: rec.ownerKey, fileID: report.fileID, downloadID: rec.id, at: at, positionMs: max(report.positionMs, 0), durationMs: report.durationMs)
        }
        if report.state == .stopped || report.state == .closed { scheduleSync() }
    }

    // MARK: Sync

    private func scheduleSync() {
        guard syncing == nil else { return }
        syncing = Task { [weak self] in
            await self?.sync()
            self?.syncing = nil
        }
    }

    /// Syncs every account that has downloads or reports here and is still
    /// signed in. Offline, or with the server away, it tries again when the
    /// network is back.
    public func sync() async {
        let s = stored
        let keys = Set(s.records.map(\.ownerKey) + s.removed.map(\.ownerKey) + s.progress.map(\.ownerKey))
        for key in keys.sorted() {
            guard let o = s.records.first(where: { $0.ownerKey == key })?.owner ?? (owner?.key == key ? owner : nil), gateway.signedIn(o) else { continue }
            await syncOwner(o, again: true)
        }
        await refreshAllowed()
    }

    private func syncOwner(_ o: OfflineOwner, again: Bool) async {
        let s = stored
        let held = s.records.filter { $0.ownerKey == o.key }
        let removals = s.removed.filter { $0.ownerKey == o.key }
        // A download whose file the server deleted syncs no progress (PLAN-DOWNLOADS.md §5).
        let gone = Set(held.filter { $0.mark == .removedFromServer }.map(\.id))
        let entries = s.progress.filter { $0.ownerKey == o.key }
        let sent = entries.filter { $0.downloadID.map { !gone.contains($0) } ?? true }
        let engineDownloads = engine.downloads()
        let reports = held.map { rec -> SyncedDownload in
            let e = engineDownloads[rec.id]
            let state: DeviceDownloadState = if rec.completedAt != nil || e?.state == .completed {
                .downloaded
            } else if e?.state == .downloading {
                .downloading
            } else {
                .queued
            }
            return SyncedDownload(id: rec.id, state: state, sizeBytes: state == .downloaded ? e?.bytes : nil)
        } + removals.map { SyncedDownload(id: $0.id, state: .removed) }
        let finished = held.map { (id: $0.id, done: $0.completedAt != nil) }
        let reply: SyncReply
        do {
            reply = try await gateway.sync(
                o, client: client(), sentAt: now(), downloads: reports,
                progress: sent.map { OfflineProgress(fileID: $0.fileID, downloadID: $0.downloadID, at: $0.at, positionMs: $0.positionMs, durationMs: $0.durationMs, watched: $0.watched) }
            )
        } catch is SignedOutError {
            // Signed out on that server (the session was revoked): its downloads go.
            prune()
            return
        } catch {
            downloadsLog.info("could not sync downloads: \(error.localizedDescription, privacy: .public)")
            return
        }
        let sentSeqs = Set(entries.map(\.seq))
        // The item's state now, as the server answered each entry: what the copy resumes from once the queue is empty.
        var watch: [Int: WatchProgress?] = [:]
        for (i, entry) in sent.enumerated() where i < reply.results.count && reply.results[i].outcome != .unavailable {
            watch[entry.fileID] = reply.results[i].watch
        }
        let removedIDs = Set(removals.map(\.id))
        var next = stored
        // Every outcome is final: the entries sent go, and so do a gone download's.
        next.progress.removeAll { sentSeqs.contains($0.seq) }
        next.removed.removeAll { $0.ownerKey == o.key && removedIDs.contains($0.id) }
        next.allowed[o.key] = reply.offline
        for i in next.records.indices where next.records[i].ownerKey == o.key {
            guard let w = watch[next.records[i].fileID] else { continue }
            next.records[i].watchPositionMs = w?.positionMs ?? 0
            next.records[i].watchDurationMs = w?.durationMs
            next.records[i].watchCompleted = w?.completed == true
        }
        stored = next
        var removedHere = false
        for action in reconcile(finished, reply) {
            guard let rec = stored.record(action.id) else { continue }
            switch action {
            case .delete(_, let notice):
                if notice, let record = parse(rec) { notices.removedByAdmin(downloadTitle(record.item)) }
                // A revoked record goes once this device says its copy is gone.
                removeRecord(rec, report: notice)
                removedHere = removedHere || notice
            case .mark(_, let mark, let replacedBy):
                if rec.mark != mark || rec.replacedBy != replacedBy {
                    stored.updateRecord(rec.id) {
                        $0.mark = mark
                        $0.replacedBy = replacedBy
                    }
                }
            case .restart:
                restart(rec.id)
            }
        }
        // Say at once that the revoked copies are gone, so the server can forget them.
        if removedHere && again { await syncOwner(o, again: false) }
    }

    /// Before signing out: the progress made here and every copy, reported removed, while the session stands.
    private func reportAllRemoved() async {
        guard let o = owner else { return }
        let held = stored.records.filter { $0.ownerKey == o.key }
        let gone = Set(held.filter { $0.mark == .removedFromServer }.map(\.id))
        let entries = stored.progress.filter { $0.ownerKey == o.key && !gone.contains($0.downloadID ?? "") }
        let removals = stored.removed.filter { $0.ownerKey == o.key }
        guard !held.isEmpty || !entries.isEmpty || !removals.isEmpty else { return }
        _ = try? await gateway.sync(
            o, client: client(), sentAt: now(),
            downloads: held.map { SyncedDownload(id: $0.id, state: .removed) } + removals.map { SyncedDownload(id: $0.id, state: .removed) },
            progress: entries.map { OfflineProgress(fileID: $0.fileID, downloadID: $0.downloadID, at: $0.at, positionMs: $0.positionMs, durationMs: $0.durationMs, watched: $0.watched) }
        )
    }
}
