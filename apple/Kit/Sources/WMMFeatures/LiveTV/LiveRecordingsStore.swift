import Foundation
import Observation
import WMMModel

/// Household schedule shared by the guide and the list. Loads only sections the
/// person opens, by keyset page; server events invalidate them without polling.
///
/// A programme's mark is the newest this device has learnt: from Record and
/// Don't record, the lists, or the guide's span on screen, asked again after
/// each `live.recordings.changed` (scheduled becoming REC, a new series' other
/// episodes) rather than when the guide's listings are half an hour old. A
/// cancelled mark is kept as a tombstone, so the guide's older copy can't bring
/// the dot back, and a reply asked for before a change never overwrites it.
@MainActor @Observable
public final class LiveRecordingsStore {
    public private(set) var pages: [LiveRecordingSection: LiveRecordingPage] = [:]
    public private(set) var loading: Set<LiveRecordingSection> = []
    public private(set) var changing: Set<String> = []
    public var message: String?
    public private(set) var errors: [LiveRecordingSection: String] = [:]
    public private(set) var revision = 0
    @ObservationIgnored private let repository: any LiveTVRepository
    @ObservationIgnored private var context: String?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var invalidated: Set<LiveRecordingSection> = []
    /// By programme id; a nil mark means the guide's copy is out of date and nothing records it.
    private var marks: [String: (mark: LiveRecordingMark?, stamp: Int)] = [:]
    /// Counts every mark written, so a reply knows what is newer than its request.
    @ObservationIgnored private var stamp = 0
    /// Server events since the context began.
    @ObservationIgnored private var events = 0
    /// The guide's span on screen, and the event count each span's marks were last asked at.
    @ObservationIgnored private var visible: (start: Date, end: Date)?
    @ObservationIgnored private var askedAt: [Date: Int] = [:]

    public init(repository: any LiveTVRepository) { self.repository = repository; context = repository.contextID }
    public var available: Bool { repository.capabilities?.recordingsAvailable == true }
    public func page(_ section: LiveRecordingSection) -> LiveRecordingPage? { context == repository.contextID ? pages[section] : nil }
    public func mark(for programme: LiveProgramme) -> LiveRecordingMark? {
        guard available else { return nil }
        guard context == repository.contextID, let known = marks[programme.id] else { return programme.recording }
        return known.mark
    }
    /// The guide's span on screen. Its marks are asked again after each event, and
    /// when a span is first shown after one, since its listings may be older.
    public func showGuide(from start: Date, to end: Date) async {
        syncContext()
        visible = (start, end)
        guard available, (askedAt[start] ?? 0) < events else { return }
        await refreshVisibleMarks()
    }
    public func load(_ section: LiveRecordingSection, more: Bool = false, force: Bool = false) async {
        syncContext()
        guard available, more || force || pages[section] == nil else { return }
        if loading.contains(section) {
            if force { invalidated.insert(section) }
            return
        }
        let identity = context, version = generation, asked = stamp
        let cursor = more ? pages[section]?.nextCursor : nil
        if more && cursor == nil { return }
        loading.insert(section); errors[section] = nil
        defer { if identity == repository.contextID && version == generation { loading.remove(section) } }
        do {
            var page = try await repository.recordings(section: section, cursor: cursor)
            guard identity == repository.contextID, version == generation else { return }
            if more, let before = pages[section] {
                let seen = Set(before.items.map(\.id)), seenRules = Set(before.rules.map(\.id))
                page.items = before.items + page.items.filter { !seen.contains($0.id) }
                page.rules = before.rules + page.rules.filter { !seenRules.contains($0.id) }
            }
            pages[section] = page
            for recording in page.items { if let id = recording.programmeID { put(recording.mark, for: id, askedAt: asked) } }
        } catch {
            guard identity == repository.contextID, version == generation, !(error is CancellationError) else { return }
            errors[section] = error.displayMessage
        }
        if identity == repository.contextID, version == generation, invalidated.remove(section) != nil {
            loading.remove(section)
            await load(section, force: true)
        }
    }
    /// A programme without a series (a film, a special) records once: the server refuses a series of it.
    @discardableResult public func record(_ programme: LiveProgramme, options: LiveRecordingOptions) async -> LiveRecording? {
        guard available else { return nil }
        var options = options
        if programme.seriesKey == nil { options.series = false }
        return await change(programme.id) {
            let result = try await repository.record(programmeID: programme.id, options: options)
            put(result.mark, for: programme.id)
            return result
        }
    }
    @discardableResult public func manual(_ request: LiveManualRecording) async -> LiveRecording? {
        if let validation = request.validationMessage { message = validation; return nil }
        guard available else { return nil }
        return await change("manual") { try await repository.recordManual(request) }
    }
    /// Don't record, or Stop recording. `programmeID` is the programme it was on, when known.
    /// Don't record it, from the live player's warning: for whoever set it and admins.
    /// False, with ``message`` saying why, when the server refused or couldn't be reached.
    @discardableResult public func cancelTakeover(_ takeover: LiveRecordingTakeover) async -> Bool {
        guard takeover.canCancel else { return false }
        return await cancel(.init(id: takeover.recordingID, state: .scheduled, canCancel: true))
    }
    @discardableResult public func cancel(_ mark: LiveRecordingMark, programmeID: String? = nil) async -> Bool {
        guard mark.canCancel, mark.state.mayCancel else { return false }
        return await change(mark.id) {
            try await repository.cancelRecording(id: mark.id)
            let tombstone = LiveRecordingMark(id: mark.id, state: .cancelled, series: mark.series)
            for (id, known) in marks where known.mark?.id == mark.id { put(tombstone, for: id) }
            if let programmeID { put(tombstone, for: programmeID) }
            return true
        } ?? false
    }
    /// Record this instead, from a clash: `holder` (not recording yet) gives way and
    /// the others keep their tuners; without one, this goes ahead of every recording
    /// not yet recording. The reply may still be a clash, when the plan changed meanwhile.
    @discardableResult public func prefer(_ recording: LiveRecording, over holder: LiveRecordingHolder? = nil) async -> LiveRecording? {
        guard recording.canCancel, recording.state == .conflict, holder?.running != true else { return nil }
        return await change(recording.id) {
            let updated = try await repository.preferRecording(id: recording.id, over: holder?.id)
            if let id = updated.programmeID ?? recording.programmeID { put(updated.mark, for: id) }
            return updated
        }
    }
    public func edit(_ recording: LiveRecording, options: LiveRecordingOptions) async {
        guard recording.canCancel else { return }
        _ = await change(recording.id) {
            _ = try await repository.editRecording(id: recording.id, padBefore: options.padBefore, padAfter: options.padAfter, prefer: false)
            return true
        }
    }
    public func edit(_ rule: LiveRecordingRule, options: LiveRecordingOptions) async {
        guard rule.canCancel else { return }
        _ = await change(rule.id) { _ = try await repository.editRecordingRule(id: rule.id, options: options); return true }
    }
    @discardableResult public func deleteFile(_ recording: LiveRecording, recordAgain: Bool) async -> Bool {
        guard recording.canDelete, [.done, .partial].contains(recording.state) else { return false }
        return await change(recording.id) { try await repository.deleteRecordingFile(id: recording.id, recordAgain: recordAgain); return true } ?? false
    }
    public func remove(_ rule: LiveRecordingRule) async {
        guard rule.canCancel else { return }
        _ = await change(rule.id) { try await repository.removeRecordingRule(id: rule.id); return true }
    }
    public func refresh() async {
        syncContext()
        guard available else { return }
        for section in LiveRecordingSection.allCases where pages[section] != nil { await load(section, force: true) }
    }
    /// Server events (and this device's own changes) ask again for what is on screen.
    public func follow() async {
        for await _ in repository.changes() {
            guard !Task.isCancelled else { return }
            syncContext()
            events += 1; revision += 1
            await refreshVisibleMarks()
            await refresh()
        }
    }
    /// The visible span's marks from the guide, one request; a programme it lists with none records nothing.
    private func refreshVisibleMarks() async {
        guard available, repository.capabilities?.listings == true, let visible else { return }
        let identity = context, version = generation, asked = stamp, seen = events
        let hours = min(max(Int((visible.end.timeIntervalSince(visible.start) / 3600).rounded(.up)), 1), 6)
        guard let guide = try? await repository.guide(start: visible.start, hours: hours),
              identity == repository.contextID, version == generation else { return }
        askedAt[visible.start] = max(askedAt[visible.start] ?? 0, seen)
        for channel in guide.channels { for p in channel.programmes { put(p.recording, for: p.id, askedAt: asked) } }
        revision += 1
    }
    /// Keeps `mark` unless something newer than `askedAt` (a reply's request) was written since.
    private func put(_ mark: LiveRecordingMark?, for programmeID: String, askedAt: Int? = nil) {
        guard context == repository.contextID else { return }
        if let askedAt, let known = marks[programmeID], known.stamp > askedAt { return }
        stamp += 1
        marks[programmeID] = (mark, stamp)
    }
    private func change<T>(_ key: String, operation: () async throws -> T) async -> T? {
        syncContext()
        guard available, !changing.contains(key) else { return nil }
        let identity = context, version = generation
        changing.insert(key); message = nil
        defer { if identity == repository.contextID && version == generation { changing.remove(key) } }
        do {
            let result = try await operation()
            guard identity == repository.contextID, version == generation else { return nil }
            revision += 1
            await refresh()
            return result
        } catch {
            guard identity == repository.contextID, version == generation, !(error is CancellationError) else { return nil }
            message = error.displayMessage
            return nil
        }
    }
    private func syncContext() {
        guard context != repository.contextID else { return }
        context = repository.contextID; generation += 1; revision += 1
        pages = [:]; loading = []; changing = []; errors = [:]; marks = [:]; invalidated = []; message = nil
        events = 0; askedAt = [:]; visible = nil
    }
}
