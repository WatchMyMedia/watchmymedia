import Foundation
import Observation
import Testing
import WMMFakes
import WMMModel
@testable import WMMFeatures

@Suite @MainActor struct LiveRecordingsStoreTests {
    private let start = Date(timeIntervalSince1970: 1_800_000_000)
    private func item(_ id: String, state: LiveRecordingState = .scheduled, canCancel: Bool = true) -> LiveRecording {
        .init(id: id, channelID: "ch-7.1", number: "7.1", programmeID: "p-\(id)", title: "Station 9", start: start, end: start.addingTimeInterval(3600), state: state, canCancel: canCancel)
    }
    @Test func onlyOpenedSectionsLoadAndPaginationDoesNotDuplicateRows() async {
        let repository = RecordingsRepository()
        repository.pages[.upcoming] = .init(items: [item("1")], nextCursor: "cursor")
        let store = LiveRecordingsStore(repository: repository)
        await store.load(.upcoming)
        await store.load(.upcoming)
        #expect(repository.requests.count == 1)
        #expect(store.page(.now) == nil)
        repository.pages[.upcoming] = .init(items: [item("1"), item("2")])
        await store.load(.upcoming, more: true)
        #expect(repository.requests.map(\.1) == [nil, "cursor"])
        #expect(store.page(.upcoming)?.items.map(\.id) == ["1", "2"])
    }
    @Test func aFailedRefreshRetainsUsableDataAndRetryClearsTheError() async {
        let repository = RecordingsRepository()
        repository.pages[.now] = .init(items: [item("1", state: .recording)])
        let store = LiveRecordingsStore(repository: repository)
        await store.load(.now)
        repository.failure = "The server is busy."
        await store.load(.now, force: true)
        #expect(store.page(.now)?.items.count == 1)
        #expect(store.errors[.now] == "The server is busy.")
        repository.failure = nil
        await store.load(.now, force: true)
        #expect(store.errors[.now] == nil)
    }
    @Test func anEventDuringAPageLoadInvalidatesTheReplyAndRefetchesOnce() async {
        let repository = RecordingsRepository()
        repository.deferPage = true
        let store = LiveRecordingsStore(repository: repository)
        let first = Task { await store.load(.now) }
        #expect(await eventually(timeout: .seconds(2)) { repository.pendingPage != nil })
        await store.load(.now, force: true)
        repository.deferPage = false
        repository.pages[.now] = .init(items: [item("new", state: .recording)])
        repository.pendingPage?.resume(returning: .init(items: [item("old")]))
        repository.pendingPage = nil
        await first.value
        #expect(repository.requests.count == 2)
        #expect(store.page(.now)?.items.first?.id == "new")
    }
    @Test func oldServerAndPermissionDenialMakeNoCalls() async {
        let repository = RecordingsRepository()
        repository.capabilities = .init(enabled: true, allowed: true, playback: true)
        let store = LiveRecordingsStore(repository: repository)
        await store.load(.upcoming)
        #expect(!store.available && repository.requests.isEmpty)
        let result = await store.manual(.init(channelID: "ch-7.1", name: "Station 9"))
        #expect(result == nil && repository.manualCalls == 0)
    }
    @Test func aReplyFromThePreviousAccountNeverReachesTheNewAccount() async throws {
        let repository = RecordingsRepository()
        repository.deferPage = true
        let store = LiveRecordingsStore(repository: repository)
        let loading = Task { await store.load(.upcoming) }
        #expect(await eventually(timeout: .seconds(2)) { repository.pendingPage != nil })
        repository.contextID = "other-account"
        repository.pendingPage?.resume(returning: .init(items: [item("private")]))
        repository.pendingPage = nil
        await loading.value
        #expect(store.page(.upcoming) == nil)
        repository.deferPage = false
        await store.load(.upcoming)
        #expect(store.page(.upcoming)?.items.isEmpty == true)
    }
    @Test func unknownOrUnownedRecordingsCannotBeCancelledFromTheStore() async {
        let repository = RecordingsRepository()
        let store = LiveRecordingsStore(repository: repository)
        await store.cancel(.init(id: "r1", state: .recording))
        await store.cancel(.init(id: "r2", state: .unknown, canCancel: true))
        #expect(repository.cancelled.isEmpty)
        await store.cancel(.init(id: "r3", state: .scheduled, canCancel: true))
        #expect(repository.cancelled == ["r3"])
    }
    @Test func invalidManualRecordingNeverSendsARequest() async {
        let repository = RecordingsRepository()
        let store = LiveRecordingsStore(repository: repository)
        _ = await store.manual(.init())
        #expect(repository.manualCalls == 0)
        #expect(store.message == "Choose a channel.")
    }
    @Test func serverEventsRefreshOnlySectionsAlreadyLoaded() async {
        let repository = RecordingsRepository()
        let store = LiveRecordingsStore(repository: repository)
        await store.load(.now)
        let following = Task { await store.follow() }
        defer { following.cancel() }
        #expect(await eventually(timeout: .seconds(2)) { repository.listener != nil })
        repository.pages[.now] = .init(items: [item("new", state: .recording)])
        repository.listener?.yield()
        #expect(await eventually(timeout: .seconds(2)) { store.page(.now)?.items.first?.id == "new" })
        #expect(repository.requests.allSatisfy { $0.0 == .now })
    }
    // MARK: Marks after Record, Don't record and server events

    private func programme(_ id: String, mark: LiveRecordingMark? = nil) -> LiveProgramme {
        LiveProgramme(id: id, start: start, end: start.addingTimeInterval(3600), title: "Station 9", recording: mark)
    }
    /// Follows events with the guide's span on screen and its first reply in hand.
    private func following(_ repository: RecordingsRepository, _ store: LiveRecordingsStore) async -> Task<Void, Never> {
        let task = Task { await store.follow() }
        #expect(await eventually(timeout: .seconds(2)) { repository.listener != nil })
        let guide = Task { await store.showGuide(from: start, to: start.addingTimeInterval(3600)) }
        return Task {
            await withTaskCancellationHandler { await task.value } onCancel: { task.cancel(); guide.cancel() }
        }
    }
    @Test func recordKeepsItsMarkThroughItsOwnEventAndTheGuideBringsRecordingNow() async {
        let repository = RecordingsRepository()
        let store = LiveRecordingsStore(repository: repository)
        let following = await following(repository, store)
        defer { following.cancel() }
        let p = programme("p-1")
        repository.recordReply = item("1")
        repository.notifiesOnChange = true
        let recorded = await store.record(p, options: .init())
        #expect(recorded?.id == "1")
        #expect(store.mark(for: p)?.state == .scheduled, "Record's own event must not wipe the mark")
        // The guide window asked again after that event still says scheduled.
        #expect(await eventually(timeout: .seconds(2)) { repository.guideRequests >= 1 })
        #expect(store.mark(for: p)?.state == .scheduled)
        // At the start the server says it is recording: the visible window is asked again.
        repository.guideMarks["p-1"] = .init(id: "1", state: .recording, canCancel: true)
        let asked = repository.guideRequests
        repository.listener?.yield()
        #expect(await eventually(timeout: .seconds(2)) { store.mark(for: p)?.state == .recording })
        #expect(repository.guideRequests == asked + 1)
    }
    @Test func aNewSeriesMarksItsOtherEpisodesFromTheGuideWithoutWaitingForListingsToAge() async {
        let repository = RecordingsRepository()
        let store = LiveRecordingsStore(repository: repository)
        let following = await following(repository, store)
        defer { following.cancel() }
        let next = programme("p-2")
        repository.guideMarks["p-2"] = .init(id: "2", state: .scheduled, series: true)
        repository.listener?.yield()
        #expect(await eventually(timeout: .seconds(2)) { store.mark(for: next)?.series == true })
    }
    @Test func cancelLeavesATombstoneThatTheStaleGuideCopyCannotUndo() async {
        let repository = RecordingsRepository()
        let store = LiveRecordingsStore(repository: repository)
        let following = await following(repository, store)
        defer { following.cancel() }
        let mark = LiveRecordingMark(id: "1", state: .scheduled, canCancel: true)
        let p = programme("p-1", mark: mark)
        #expect(store.mark(for: p)?.state == .scheduled)
        repository.guideDelay = true
        await store.cancel(mark, programmeID: p.id)
        #expect(repository.cancelled == ["1"])
        #expect(store.mark(for: p)?.state.marked != true, "The guide's copy from before still says scheduled")
        repository.listener?.yield()
        #expect(await eventually(timeout: .seconds(2)) { repository.pendingGuide != nil })
        #expect(store.mark(for: p)?.state.marked != true, "Before the guide answers, the tombstone holds")
        let before = store.revision
        repository.pendingGuide?.resume(returning: repository.guideReply())
        repository.pendingGuide = nil
        #expect(await eventually(timeout: .seconds(2)) { store.revision > before })
        #expect(store.mark(for: p)?.state.marked != true)
    }
    @Test func aGuideReplyAskedBeforeAnActionNeverOverwritesIt() async {
        let repository = RecordingsRepository()
        let store = LiveRecordingsStore(repository: repository)
        let following = await following(repository, store)
        defer { following.cancel() }
        let p = programme("p-1")
        repository.guideDelay = true
        repository.listener?.yield()
        #expect(await eventually(timeout: .seconds(2)) { repository.pendingGuide != nil })
        let stale = repository.guideReply()   // asked before Record: no mark
        repository.recordReply = item("1")
        _ = await store.record(p, options: .init())
        let before = store.revision
        repository.pendingGuide?.resume(returning: stale)
        repository.pendingGuide = nil
        #expect(await eventually(timeout: .seconds(2)) { store.revision > before })
        #expect(store.mark(for: p)?.state == .scheduled)
    }
    @Test func aSpanFirstShownAfterAnEventIsAskedAgainButNotBeforeOne() async {
        let repository = RecordingsRepository()
        let store = LiveRecordingsStore(repository: repository)
        var guide = Task { await store.showGuide(from: start, to: start.addingTimeInterval(3600)) }
        #expect(await eventually(timeout: .seconds(2)) { store.showsGuideSpan })
        #expect(repository.guideRequests == 0, "Nothing changed since the guide loaded it")
        let following = Task { await store.follow() }
        defer { following.cancel() }
        #expect(await eventually(timeout: .seconds(2)) { repository.listener != nil })
        repository.listener?.yield()
        #expect(await eventually(timeout: .seconds(2)) { repository.guideRequests == 1 })
        guide.cancel()
        let later = start.addingTimeInterval(3600)
        guide = Task { await store.showGuide(from: later, to: later.addingTimeInterval(3600)) }
        #expect(await eventually(timeout: .seconds(2)) { repository.guideRequests == 2 }, "Its listings may be from before the event")
        guide.cancel()
        await guide.value
        guide = Task { await store.showGuide(from: later, to: later.addingTimeInterval(3600)) }
        #expect(await eventually(timeout: .seconds(2)) { store.showsGuideSpan })
        #expect(repository.guideRequests == 2)
        guide.cancel()
        await guide.value
        // The guide has gone: an event asks nothing for it.
        let followed = store.eventsFollowed
        repository.listener?.yield()
        #expect(await eventually(timeout: .seconds(2)) { store.eventsFollowed == followed + 1 })
        #expect(repository.guideRequests == 2)
    }

    @Test func onlyRecordingsEventsAskAgainAndTheEchoOfAChangeMadeHereAsksNothing() async {
        let repository = RecordingsRepository()
        repository.pages[.upcoming] = .init(items: [item("1")])
        repository.recordReply = item("1")
        let store = LiveRecordingsStore(repository: repository)
        let following = Task { await store.follow() }
        defer { following.cancel() }
        #expect(await eventually(timeout: .seconds(2)) { repository.typed != nil })
        let list = Task { await store.show([.upcoming]) }
        defer { list.cancel() }
        await store.load(.upcoming)
        _ = await store.record(programme("p-1"), options: .init())
        #expect(repository.requests.count == 2, "the change asks for what is on screen itself")
        let followed = store.eventsFollowed
        repository.typed?.yield(.recordings)
        repository.typed?.yield(.favourites)
        repository.typed?.yield(.reminders)
        #expect(await eventually(timeout: .seconds(2)) { store.eventsFollowed == followed + 3 })
        #expect(repository.requests.count == 2, "its echo, and other parts' events, ask nothing")
        repository.typed?.yield(.recordings)
        #expect(await eventually(timeout: .seconds(2)) { repository.requests.count == 3 }, "another device's change does")
    }

    @Test func sectionsThatLeftTheScreenAreForgottenNotAskedAgain() async {
        let repository = RecordingsRepository()
        repository.pages[.upcoming] = .init(items: [item("1")])
        repository.pages[.now] = .init(items: [item("2", state: .recording)])
        let store = LiveRecordingsStore(repository: repository)
        let following = Task { await store.follow() }
        defer { following.cancel() }
        #expect(await eventually(timeout: .seconds(2)) { repository.listener != nil })
        let list = Task { await store.show([.upcoming, .now]) }
        await store.load(.upcoming)
        await store.load(.now)
        #expect(repository.requests.count == 2)
        repository.listener?.yield()
        #expect(await eventually(timeout: .seconds(2)) { repository.requests.count == 4 }, "both on screen: both asked again")
        list.cancel()
        await list.value
        repository.listener?.yield()
        #expect(await eventually(timeout: .seconds(2)) { store.page(.upcoming) == nil && store.page(.now) == nil })
        #expect(repository.requests.count == 4, "gone from the screen: forgotten, not asked again")
        await store.load(.upcoming)
        #expect(repository.requests.count == 5, "and loaded again when it comes back")
    }
    @Test func aProgrammeWithoutASeriesRecordsOnceEvenWhenAskedForTheSeries() async {
        let repository = RecordingsRepository()
        let store = LiveRecordingsStore(repository: repository)
        repository.recordReply = item("1")
        var special = programme("p-1")
        _ = await store.record(special, options: .init(series: true))
        #expect(repository.recordedOptions.last?.series == false, "The server refuses a series without a series key")
        special.seriesKey = "SH0042"
        _ = await store.record(special, options: .init(series: true))
        #expect(repository.recordedOptions.last?.series == true)
    }
    @Test func recordThisInsteadNamesTheHolderThatGivesWayAndNeverOneAlreadyRecording() async throws {
        let repository = RecordingsRepository()
        let store = LiveRecordingsStore(repository: repository)
        let waiting = LiveRecordingHolder(id: "h1", title: "Inspector Vane", number: "4.2", start: start, end: start.addingTimeInterval(3600))
        var running = waiting; running.id = "h2"; running.running = true
        var clash = item("1", state: .conflict); clash.conflictsWith = [waiting, running]
        repository.recordReply = clash
        let result = await store.prefer(clash, over: running)
        #expect(result == nil && repository.preferred.isEmpty, "A recording already running can't give way")
        let updated = await store.prefer(clash, over: waiting)
        #expect(updated?.state == .scheduled)
        #expect(repository.preferred.map(\.0) == ["1"] && repository.preferred.map(\.1) == ["h1"])
        #expect(store.mark(for: programme("p-1"))?.state == .scheduled)
        _ = await store.prefer(clash)
        #expect(repository.preferred.last?.1 == nil, "Without holders it goes ahead of every one not yet recording")
        var someoneElses = clash; someoneElses.canCancel = false
        _ = await store.prefer(someoneElses, over: waiting)
        #expect(repository.preferred.count == 2)
    }
    @Test func dontRecordItFromTheTakeoverWarningIsForWhoeverMayAndSaysWhyItFailed() async {
        let repository = RecordingsRepository()
        let store = LiveRecordingsStore(repository: repository)
        let warning = LiveRecordingTakeover(recordingID: "r1", title: "Inspector Vane", at: start)
        #expect(await store.cancelTakeover(warning) == false && repository.cancelled.isEmpty)
        var mine = warning; mine.canCancel = true
        #expect(await store.cancelTakeover(mine))
        #expect(repository.cancelled == ["r1"])
        repository.cancelFailure = "The server is busy."
        #expect(await store.cancelTakeover(mine) == false)
        #expect(store.message == "The server is busy.")
    }
    @Test func recordingLinksGateNavigationOnTheServersCapability() async {
        let repository = RecordingsRepository()
        let navigator = Navigator()
        await navigator.open(.liveRecordings, library: FakeLibraryRepository(), liveTV: repository)
        #expect(navigator.tab == .liveTV && navigator.path(for: .liveTV) == [.liveRecordings])
        #expect(DeepLink(url: URL(string: "watchmymedia://live-tv/recordings")!) == .liveRecordings)
    }
}

@MainActor @Observable private final class RecordingsRepository: LiveTVRepository {
    var contextID: String? = "account"
    var capabilities: LiveTVCapabilities? = .init(enabled: true, allowed: true, playback: true, guide: true, recording: true, canRecord: true)
    var recordReply: LiveRecording?
    var recordedOptions: [LiveRecordingOptions] = []
    var preferred: [(String, String?)] = []
    /// Record and Don't record tell the event stream, as the real repository does.
    var notifiesOnChange = false
    var guideMarks: [String: LiveRecordingMark] = [:]
    var guideRequests = 0
    var guideDelay = false
    var pendingGuide: CheckedContinuation<LiveGuide, Never>?
    func guideReply() -> LiveGuide {
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        let programmes = ["p-1", "p-2"].enumerated().map { i, id in
            LiveProgramme(id: id, start: start.addingTimeInterval(Double(i) * 1800), end: start.addingTimeInterval(Double(i + 1) * 1800),
                          title: "Station 9", recording: guideMarks[id])
        }
        return LiveGuide(start: start, end: start.addingTimeInterval(3600), state: .init(),
                         channels: [.init(channel: .init(id: "ch-7.1", number: "7.1", name: "LKSD-HD"), listings: true, programmes: programmes)])
    }
    func record(programmeID: String, options: LiveRecordingOptions) async throws -> LiveRecording {
        recordedOptions.append(options)
        guard let recordReply else { throw LiveTVError(reason: "unavailable") }
        guideMarks[programmeID] = recordReply.mark
        if notifiesOnChange { listener?.yield() }
        return recordReply
    }
    func preferRecording(id: String, over: String?) async throws -> LiveRecording {
        preferred.append((id, over))
        guard var recordReply else { throw LiveTVError(reason: "unavailable") }
        recordReply.state = .scheduled
        return recordReply
    }
    var channels: [LiveChannel] = []
    var pages: [LiveRecordingSection: LiveRecordingPage] = [:]
    var requests: [(LiveRecordingSection, String?)] = []
    var cancelled: [String] = []
    var manualCalls = 0
    var failure: String?
    var deferPage = false
    var pendingPage: CheckedContinuation<LiveRecordingPage, Never>?
    var listener: AsyncStream<Void>.Continuation?
    func refresh() async throws {}
    func makeSessionGateway() throws -> any LiveSessionGateway { throw LiveTVError(reason: "unavailable") }
    func guide(start: Date, hours: Int) async throws -> LiveGuide {
        guideRequests += 1
        if guideDelay { return await withCheckedContinuation { pendingGuide = $0 } }
        return guideReply()
    }
    func recordings(section: LiveRecordingSection, cursor: String?) async throws -> LiveRecordingPage {
        requests.append((section, cursor))
        if deferPage { return await withCheckedContinuation { pendingPage = $0 } }
        if let failure { throw APIError(status: 503, message: failure) }
        return pages[section] ?? .init()
    }
    var cancelFailure: String?
    func cancelRecording(id: String) async throws {
        if let cancelFailure { throw APIError(status: 503, message: cancelFailure) }
        cancelled.append(id)
        for (key, mark) in guideMarks where mark.id == id { guideMarks[key] = nil }
        if notifiesOnChange { listener?.yield() }
    }
    func recordManual(_ request: LiveManualRecording) async throws -> LiveRecording { manualCalls += 1; throw LiveTVError(reason: "unavailable") }
    func changes() -> AsyncStream<Void> { AsyncStream { listener = $0 } }
    /// What the server's events say; `listener` yields stand for a reconnect (`.all`).
    var typed: AsyncStream<LiveTVChange>.Continuation?
    func liveChanges() -> AsyncStream<LiveTVChange> {
        let signals = changes()
        return AsyncStream { continuation in
            typed = continuation
            let task = Task { for await _ in signals { continuation.yield(.all) } }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
