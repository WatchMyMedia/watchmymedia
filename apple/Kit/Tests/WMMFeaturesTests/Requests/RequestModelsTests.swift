import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@MainActor
@Suite struct ShowMissingTests {
    let library = FakeLibraryRepository()
    let preferences = FakePreferencesRepository()
    let navigator = Navigator()

    init() { FakeLatency.duration = .zero }

    private func model(admin: Bool = true, requests: FakeRequestsRepository? = nil, season: Int = 2) -> (ShowPageModel, FakeRequestsRepository) {
        let requests = requests ?? FakeRequestsRepository(library: library, admin: admin)
        return (ShowPageModel(library: library, navigator: navigator, showID: 100, arrival: .season(season), requests: requests, preferences: preferences), requests)
    }

    @Test func showMissingAddsTheMissingEpisodesAndIsRemembered() async {
        let (model, _) = model()
        await model.retry()
        #expect(model.canShowMissing)
        #expect(!model.showMissing)
        #expect(model.seasonEpisodes.map(\.number) == [4, 5, 6, 7, 8], "on disk only")
        #expect(model.missingSummary == "5 of 8 episodes · 3 missing")

        await model.setShowMissing(true)
        #expect(model.seasonEpisodes.map(\.number) == Array(1...8))
        #expect(model.seasonEpisodes.prefix(3).map { $0.missingLine().text } == ["Not monitored", "Downloading 45% · 12 min left", "Missing · Sonarr is looking for it"])
        #expect(preferences.showsMissing(showID: 100))

        let (again, _) = self.model()
        #expect(again.showMissing, "remembered for the show")
    }

    @Test func anOlderServerHasNoSwitch() async {
        preferences.setShowsMissing(true, showID: 100)
        let older = ShowPageModel(library: library, navigator: navigator, showID: 100, arrival: .season(2), requests: NoRequests(), preferences: preferences)
        await older.retry()
        #expect(!older.canShowMissing)
        #expect(older.seasonEpisodes.map(\.number) == [4, 5, 6, 7, 8], "the switch remembered elsewhere does nothing here")
        #expect(!older.canRequestSeason && !older.canSearchNow && !older.canReport)
    }

    @Test func anAdminCanRequestSearchAndMonitor() async {
        let (model, _) = model()
        await model.retry()
        #expect(model.canRequestSeason)
        #expect(model.seasonRequestLabel == "Request season 2")
        #expect(model.canSearchNow)
        #expect(model.canMonitor, "episode 1 is not monitored")
        #expect(model.canRequestMissingSeasons)

        await model.monitor()
        #expect(model.message == "Monitored in Sonarr")
        #expect(!model.canMonitor)
        await model.setShowMissing(true)
        #expect(model.seasonEpisodes[0].missingLine().text == "Missing · Sonarr is looking for it")

        await model.searchNow(model.seasonEpisodes[2])
        #expect(model.message == "Sonarr is searching")
        #expect(model.seasonEpisodes[2].missingLine().text == "Waiting to download")
    }

    @Test func aMemberCanRequestButNotSearch() async {
        let (model, _) = model(admin: false)
        await model.retry()
        #expect(model.canRequestSeason)
        #expect(!model.canSearchNow && !model.canMonitor)
    }

    @Test func requestingASeasonMarksItsEpisodes() async throws {
        let (model, _) = model(admin: false)
        await model.retry()
        await model.setShowMissing(true)
        model.requestSeason()
        let sheet = try #require(model.requestSheet)
        await sheet.load()
        #expect(sheet.chosen == [2], "the season asked for, not every one that can be")
        #expect(sheet.sendLabel == "Request season 2")
        #expect(sheet.approvalNote == requestApprovalNote)
        await sheet.send()
        #expect(sheet.sentMessage == "Requested. You’ll find it in Your requests.")
        await model.retry()
        #expect(model.seasonRequested)
        #expect(!model.canRequestSeason)
        #expect(model.seasonEpisodes[2].missingLine().text == "Requested by you · Waiting for approval")
    }

    @Test func reportAProblemIsOnTheEpisodeOnTop() async throws {
        let (model, requests) = model(season: 3)
        await model.retry()
        #expect(model.canReport)
        model.reportProblem()
        let report = try #require(model.report)
        #expect(report.target.kicker == "Harbor Lights · S03 E05 The Long Dark")
        #expect(report.fileLine == "Harbor Lights - S03E05.mkv · HEVC · EAC3 5.1 · 4K")
        #expect(!report.canSend, "a kind first")
        report.pick(.audio)
        report.note = "No sound after 20 minutes"
        await report.send()
        #expect(report.sent)
        #expect(requests.reports.last?.kind == .audio)
        #expect(requests.reports.last?.fileID == report.target.fileID)
    }
}

@MainActor
@Suite struct RequestSheetTests {
    let requests = FakeRequestsRepository(admin: false)

    init() { FakeLatency.duration = .zero }

    @Test func aShowsSeasonsStartTickedWhereTheyCanBeAskedFor() async {
        let sheet = RequestSheetModel(requests: requests, mediaType: .tv, tmdbID: 71_001)
        #expect(sheet.state.isLoading)
        await sheet.load()
        #expect(sheet.title == "Harbourside")
        #expect(sheet.meta == "2019 · 4 seasons")
        #expect(sheet.chosen == [2, 3, 4])
        #expect(sheet.isTicked(sheet.seasons[0]), "season 1, asked for already, is ticked and greyed")
        #expect(sheet.sendLabel == "Request 3 seasons")
        sheet.toggle(sheet.seasons[0])
        #expect(sheet.chosen == [2, 3, 4], "can't be unticked")
        sheet.toggle(sheet.seasons[3])
        sheet.toggle(sheet.seasons[2])
        #expect(sheet.sendLabel == "Request season 2")
        sheet.toggle(sheet.seasons[1])
        #expect(!sheet.canSend)
        #expect(sheet.sendLabel == "Request")
    }

    @Test func aMovieIsRequestedAtOnceForAnAdmin() async {
        let admin = FakeRequestsRepository(admin: true)
        let sheet = RequestSheetModel(requests: admin, mediaType: .movie, tmdbID: 72_001)
        await sheet.load()
        #expect(sheet.sendLabel == "Request movie")
        #expect(sheet.approvalNote == nil, "an admin's go straight through")
        await sheet.send()
        #expect(sheet.sent?.request.status == .approved)
        #expect(admin.myRequests.first?.title == "Harbor Ghosts")
    }

    @Test func aMovieAskedForAlreadySaysRequested() async {
        let sheet = RequestSheetModel(requests: requests, mediaType: .movie, tmdbID: 72_002)
        await sheet.load()
        #expect(sheet.alreadyAsked)
        #expect(sheet.sendLabel == "Requested")
        #expect(!sheet.canSend)
    }

    @Test func aLookupThatFailsIsTheSheetsError() async {
        let sheet = RequestSheetModel(requests: requests, mediaType: .movie, tmdbID: 1)
        await sheet.load()
        #expect(sheet.state == .failed("TMDB has nothing with that ID."))
    }
}

@MainActor
@Suite struct SearchRequestTests {
    let library = TestLibrary()
    let navigator = Navigator()

    init() { FakeLatency.duration = .zero }

    @Test func notInYourLibraryComesBesideTheLibrarysResults() async throws {
        let requests = FakeRequestsRepository(admin: false)
        let model = SearchModel(library: library, navigator: navigator, debounce: .zero, requests: requests)
        model.setQuery("harbo")
        await model.pending?.value
        await model.pendingRequestables?.value
        guard case .results(let results) = model.phase else { Issue.record("no results"); return }
        #expect(results.shows.map(\.title) == ["Harbor Lights"])
        #expect(model.notInLibrary.map(\.title) == ["Harbourside", "Harbor Ghosts", "The Harbour Keeper", "Harbor Bells"], "not Harbor Lights, which is here")
        #expect(model.notInLibrary.map(\.state) == [.requestable, .requestable, .requested, .downloading])

        model.openRequest(model.notInLibrary[2])
        #expect(model.requestSheet == nil, "asked for already: no sheet")
        model.openRequest(model.notInLibrary[1])
        let sheet = try #require(model.requestSheet)
        await sheet.load()
        await sheet.send()
        model.requestSheetClosed(sheet)
        #expect(model.notInLibrary[1].state == .requested)
    }

    @Test func someoneWhoMayNotRequestSeesNoSection() async {
        let model = SearchModel(library: library, navigator: navigator, debounce: .zero, requests: NoRequests())
        model.setQuery("harbo")
        await model.retry()
        #expect(model.notInLibrary.isEmpty)
        #expect(model.pendingRequestables == nil, "nothing asked")
    }
}

@MainActor
@Suite struct RequestListTests {
    init() { FakeLatency.duration = .zero }

    @Test func yourRequestsCancelWhileTheyWait() async {
        let requests = FakeRequestsRepository(admin: false)
        let model = MyRequestsModel(requests: requests)
        await model.retry()
        let list = model.state.value ?? []
        #expect(list.map(\.title).prefix(2) == ["Copperline", "The Harbour Keeper"])
        #expect(list.map(\.stateLine.text) == [
            "Waiting for approval", "Waiting for approval", "Downloading 45%", "Approved",
            "Failed · Radarr has no root folder for this library", "Declined · “Already on the other server”", "In your library",
        ])
        await model.cancel(list[2])
        #expect(model.state.value?.count == 7, "only a waiting request can be cancelled")
        await model.cancel(list[0])
        #expect(model.state.value?.map(\.title).first == "The Harbour Keeper")
    }

    @Test func seerrKeepsLegacyLocalDecisionsAndAddsItsPendingCount() async {
        let requests = FakeRequestsRepository(admin: true, seerr: true)
        let model = InboxModel(requests: requests)
        await model.retry()
        #expect(model.viaSeerr)
        #expect(!requests.options.manage && requests.options.inboxAllowed)
        #expect(model.requestList.count == 2)
        #expect(model.count(.requests) == 5)
        #expect(model.seerrRequestsURL == URL(string: "https://seerr.example/requests"))
        await model.approve(model.requestList[0])
        #expect(model.count(.requests) == 4)
        #expect(requests.options.inbox == 3, "the badge counts local decisions and problems, not external Seerr approvals")
    }

    @Test func seerrYourRequestsCanCancelAndUnlinkedAccountsHaveAnEmptyList() async {
        let requests = FakeRequestsRepository(admin: false, seerr: true)
        let model = MyRequestsModel(requests: requests)
        await model.retry()
        await model.cancel(model.state.value![0])
        #expect(model.state.value?.count == 6)
        let unlinked = FakeRequestsRepository(admin: false, seerr: true, seerrLinked: false)
        #expect(!unlinked.options.canRequest && unlinked.options.hasYourRequests)
        let unlinkedModel = MyRequestsModel(requests: unlinked)
        await unlinkedModel.retry()
        #expect(unlinkedModel.state == .loaded([]))
        #expect(unlinkedModel.emptyMessage == "Your requests will appear here.")
    }

    @Test func reportsUseTheActualDestinationIncludingSeerrFallbacks() async {
        let requests = FakeRequestsRepository(admin: false, seerr: true)
        let target = ReportProblemModel.Target(item: .movie, id: 500, fileID: 9900, kicker: "Amber Coast")
        let accepted = ReportProblemModel(requests: requests, target: target)
        accepted.pick(.audio)
        await accepted.send()
        #expect(accepted.sentMessage == "Sent to Seerr as an issue. Admins hear about it as Seerr is set up to tell them.")
        requests.seerrAcceptsReports = false
        let refused = ReportProblemModel(requests: requests, target: target)
        refused.pick(.audio)
        await refused.send()
        #expect(refused.sent && refused.sentMessage == reportSentMessage)
        let unlinked = ReportProblemModel(requests: FakeRequestsRepository(admin: false, seerr: true, seerrLinked: false), target: target)
        unlinked.pick(.audio)
        await unlinked.send()
        #expect(unlinked.sent && unlinked.sentMessage == reportSentMessage)
    }

    @Test func seerrRequestConfirmationLinksToYourRequests() async {
        let sheet = RequestSheetModel(requests: FakeRequestsRepository(admin: false, seerr: true), mediaType: .movie, tmdbID: 72_001)
        await sheet.load()
        #expect(sheet.approvalNote == seerrApprovalNote)
        await sheet.send()
        #expect(sheet.sentToYourRequests)
        #expect(sheet.sentMessage == "Sent to Seerr. You’ll find it in Your requests.")
    }

    @Test func theInboxApprovesDeclinesAndResolves() async {
        let requests = FakeRequestsRepository(admin: true)
        let model = InboxModel(requests: requests)
        await model.retry()
        #expect(model.count(.requests) == 2)
        #expect(model.count(.problems) == 2)
        #expect(requests.options.inbox == 4)

        await model.approve(model.requestList[0])
        #expect(model.count(.requests) == 1)
        #expect(requests.options.inbox == 3, "the Settings badge follows")

        let keeper = model.requestList.first { $0.title == "The Harbour Keeper" }!
        model.startDeclining(keeper)
        #expect(model.declining == keeper.id)
        model.note = "Already on the other server"
        await model.decline(keeper)
        #expect(model.declining == nil)
        #expect(model.count(.requests) == nil)

        await model.resolve(model.problems[0])
        #expect(model.count(.problems) == 1)
        #expect(requests.options.inbox == 1)
    }
}

/// A server below API level 5: nothing offered, and nothing should be called.
@MainActor
@Observable
final class NoRequests: RequestsRepository {
    let options = RequestOptions.none
    func refreshOptions() async {}
    func search(_ text: String) async throws -> [Requestable] { Issue.record("searched"); return [] }
    func lookup(_ type: MediaType, tmdbID: Int) async throws -> Requestable { throw APIError(status: 404, message: "Not found.") }
    func ask(_ type: MediaType, tmdbID: Int, seasons: [Int]) async throws -> CreatedRequest { throw APIError(status: 404, message: "Not found.") }
    func mine() async throws -> [MediaRequest] { [] }
    func cancel(requestID: Int) async throws {}
    func inbox() async throws -> Inbox { Inbox() }
    func decide(requestID: Int, approve: Bool, note: String) async throws {}
    func report(_ item: WatchKind, id: Int, fileID: Int?, kind: ProblemKind, note: String) async throws -> Problem { throw APIError(status: 404, message: "Not found.") }
    func resolve(problemID: Int) async throws {}
    func searchMissing(showID: Int, season: Int, episodes: [Int]) async throws {}
    func monitor(showID: Int, season: Int, episodes: [Int], monitored: Bool) async throws {}
    func previewDelete(episodeID: Int) async throws -> DeletePreview { Issue.record("previewed a delete"); return DeletePreview(managed: false) }
    func deleteEpisode(id: Int) async throws -> DeleteOutcome { Issue.record("deleted"); return DeleteOutcome() }
    func replaceFile(fileID: Int) async throws -> ReplaceOutcome { Issue.record("replaced"); return ReplaceOutcome(connection: "", blocklisted: false) }
    func changes() -> AsyncStream<Void> { AsyncStream { _ in } }
}

/// Your requests and the Inbox keep the newest reply (PLAN-APPLE-REVIEW.md §5.5).
@MainActor
@Suite struct RequestListOrderTests {
    private func request(_ id: Int) -> MediaRequest {
        MediaRequest(id: id, status: .pending, requestedBy: "Sam", mine: true, createdAt: Date(timeIntervalSince1970: 1_800_000_000),
                     mediaType: .movie, tmdbID: id, title: "Film \(id)")
    }

    @Test func anOlderListNeverOverwritesANewerOne() async {
        let repository = HeldRequests()
        let model = MyRequestsModel(requests: repository)
        repository.hold = true
        let older = Task { await model.retry() }
        #expect(await eventually { repository.held != nil })
        repository.hold = false
        repository.reply = [request(2)]
        await model.retry()
        #expect(model.state.value?.map(\.id) == [2])
        repository.release([request(1)])
        await older.value
        #expect(model.state.value?.map(\.id) == [2], "the older reply came last and was dropped")
    }

    @Test func pullToRefreshKeepsYourRequestsWhenItFails() async {
        let repository = HeldRequests()
        let model = MyRequestsModel(requests: repository)
        repository.reply = [request(1)]
        await model.retry()
        repository.reply = [request(2), request(1)]
        await model.refresh()
        #expect(model.state.value?.map(\.id) == [2, 1])
        repository.failure = APIError(status: nil, message: "Couldn't reach the server.")
        await model.refresh()
        #expect(model.state.value?.map(\.id) == [2, 1], "the list stays on screen")
        #expect(model.message == "Couldn't reach the server.")
    }

    @Test func pullToRefreshKeepsTheInboxWhenItFails() async {
        let repository = HeldRequests()
        let model = InboxModel(requests: repository)
        repository.reply = [request(1)]
        await model.retry()
        repository.reply = [request(2), request(1)]
        await model.refresh()
        #expect(model.requestList.map(\.id) == [2, 1])
        repository.failure = APIError(status: nil, message: "Couldn't reach the server.")
        await model.refresh()
        #expect(model.requestList.map(\.id) == [2, 1], "the inbox stays on screen")
        #expect(model.message == "Couldn't reach the server.")
    }

    @Test func anOlderInboxNeverOverwritesANewerOne() async {
        let repository = HeldRequests()
        let model = InboxModel(requests: repository)
        repository.hold = true
        let older = Task { await model.retry() }
        #expect(await eventually { repository.heldInbox != nil })
        repository.hold = false
        repository.reply = [request(2)]
        await model.retry()
        #expect(model.requestList.map(\.id) == [2])
        repository.releaseInbox(Inbox(requests: [request(1)]))
        await older.value
        #expect(model.requestList.map(\.id) == [2])
    }
}

/// Requests whose replies the test holds back and lets go.
@MainActor
@Observable
final class HeldRequests: RequestsRepository {
    let options = RequestOptions(movies: true, shows: true, problems: true)
    var hold = false
    var reply: [MediaRequest] = []
    var failure: APIError?
    private(set) var held: CheckedContinuation<[MediaRequest], Never>?
    private(set) var heldInbox: CheckedContinuation<Inbox, Never>?

    func release(_ list: [MediaRequest]) { held?.resume(returning: list); held = nil }
    func releaseInbox(_ inbox: Inbox) { heldInbox?.resume(returning: inbox); heldInbox = nil }

    func refreshOptions() async {}
    func search(_ text: String) async throws -> [Requestable] { [] }
    func lookup(_ type: MediaType, tmdbID: Int) async throws -> Requestable { throw APIError(status: 404, message: "Not found.") }
    func ask(_ type: MediaType, tmdbID: Int, seasons: [Int]) async throws -> CreatedRequest { throw APIError(status: 404, message: "Not found.") }
    func mine() async throws -> [MediaRequest] {
        if let failure { throw failure }
        if hold { return await withCheckedContinuation { held = $0 } }
        return reply
    }
    func cancel(requestID: Int) async throws {}
    func inbox() async throws -> Inbox {
        if let failure { throw failure }
        if hold { return await withCheckedContinuation { heldInbox = $0 } }
        return Inbox(requests: reply)
    }
    func decide(requestID: Int, approve: Bool, note: String) async throws {}
    func report(_ item: WatchKind, id: Int, fileID: Int?, kind: ProblemKind, note: String) async throws -> Problem { throw APIError(status: 404, message: "Not found.") }
    func resolve(problemID: Int) async throws {}
    func searchMissing(showID: Int, season: Int, episodes: [Int]) async throws {}
    func monitor(showID: Int, season: Int, episodes: [Int], monitored: Bool) async throws {}
    func previewDelete(episodeID: Int) async throws -> DeletePreview { DeletePreview(managed: false) }
    func deleteEpisode(id: Int) async throws -> DeleteOutcome { DeleteOutcome() }
    func replaceFile(fileID: Int) async throws -> ReplaceOutcome { ReplaceOutcome(connection: "", blocklisted: false) }
    func changes() -> AsyncStream<Void> { AsyncStream { _ in } }
}
