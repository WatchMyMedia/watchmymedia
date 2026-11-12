import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// The one show page (PLAN-SHOW-PAGE.md) on the fakes: Harbor Lights'
/// seasons 1 and 2 are watched (season 2 misses its first three episodes);
/// season 3 has four watched, the fifth partway (the server's next episode),
/// two new and the ninth yet to air. Sonarr holds Harbor Lights; The Quiet
/// Meridian is no app's, so it can't be deleted from here.
@MainActor
@Suite struct ShowPageModelTests {
    let library = TestLibrary()
    let navigator = Navigator()
    let preferences = FakePreferencesRepository()

    init() { FakeLatency.duration = .zero }

    private func model(showID: Int = 100, _ arrival: ShowArrival = .episode(id: 100037, season: 3), requests: (any RequestsRepository)? = nil) -> ShowPageModel {
        ShowPageModel(library: library, navigator: navigator, showID: showID, arrival: arrival, requests: requests ?? FakeRequestsRepository(library: library.fake), preferences: preferences)
    }

    // MARK: Arriving

    @Test func theShowPutsTheNextEpisodeOnTop() async throws {
        let model = model(.show)
        #expect(model.state == .loading)
        await model.retry()
        #expect(model.show?.title == "Harbor Lights")
        #expect(model.selectedSeason == 3)
        #expect(model.episode?.id == 100035)
        #expect(model.chip == "Up next · resume", "partway through")
        #expect(model.tabs.map(\.number) == [1, 2, 3])
        #expect(!model.singleSeason)
        #expect(model.seasonEpisodes.map(\.number) == Array(1...9), "the eight on disk and the one yet to air")
        #expect(library.showCalls == 1 && library.seasonCalls == [3])
    }

    @Test func aSeasonPutsItsFirstUnfinishedOnTop() async {
        let model = model(.season(2))
        await model.retry()
        #expect(model.selectedSeason == 2)
        #expect(model.episode?.id == 100024, "every episode on disk is watched: its first on disk")
        #expect(model.chip == nil)
        #expect(model.seasonWatched)
        #expect(model.seasonToggleLabel == "Mark season unwatched")
        #expect(model.seasonLine == "5 episodes · all watched")
    }

    @Test func anEpisodePutsItselfOnTop() async throws {
        let model = model()
        await model.retry()
        let episode = try #require(model.episode)
        #expect(episode.title == "The Long Dark")
        #expect(model.showTitle == "Harbor Lights")
        #expect(model.chip == nil && model.note == nil)
        #expect(model.watchToggleLabel == "Mark watched")
    }

    @Test func findsTheSeasonWhenOpenedWithoutOne() async {
        let model = model(.episode(id: 100022, season: nil))
        await model.retry()
        #expect(model.selectedSeason == 2)
        #expect(model.episode?.title == "The Ferryman")
        #expect(library.seasonCalls == [3, 2], "the newest season first, where a new episode is")
    }

    @Test func anEpisodeNoLongerInItsSeasonPutsWhereTheSeasonStartsOnTop() async {
        let model = model(.episode(id: 100999, season: 3))
        await model.retry()
        #expect(model.episode?.id == 100035, "the season's first unfinished, as when arriving at it")
        #expect(model.note == ShowPageNote.gone)
    }

    @Test func aShowWithOneSeasonHasAHeadingNotATab() async {
        let model = model(showID: 102, .show)
        await model.retry()
        #expect(model.singleSeason)
        #expect(model.episode?.id == 102015)
    }

    @Test func aFailedLoadOffersTryAgain() async {
        let model = model()
        library.failure = APIError(status: 500, message: "Database is locked.")
        await model.retry()
        #expect(model.state == .failed("Database is locked."))
        library.failure = nil
        await model.retry()
        #expect(model.episode?.id == 100037)
    }

    @Test func refetchesWhenTheLibraryChangesAndKeepsTheEpisodeOnTop() async {
        let model = model()
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { model.episode != nil })
        model.select(model.seasonEpisodes[1])
        library.announceChange()
        #expect(await eventually { library.showCalls == 2 && library.seasonCalls.count == 2 })
        #expect(model.episode?.id == 100032, "the person's pick stays on top")
    }

    // MARK: Picking

    @Test func pickingACardPutsItOnTopAndPushesNothing() async throws {
        navigator.open(.show(id: 100))
        let model = model(.show)
        await model.retry()
        let second = model.seasonEpisodes[1]
        model.select(second)
        #expect(model.episode?.id == second.id)
        #expect(model.chip == nil, "picking drops the chip")
        #expect(navigator.path(for: .home) == [.show(id: 100)], "Back leaves the show")
        #expect(library.seasonCalls == [3], "the season is already in")
    }

    @Test func pickingATabPutsItsFirstUnfinishedOnTop() async throws {
        let model = model(.show)
        await model.retry()
        await model.selectSeason(1)
        #expect(model.selectedSeason == 1)
        #expect(model.episode?.id == 100011)
        #expect(model.seasonEpisodes.allSatisfy { $0.season == 1 })
        #expect(library.seasonCalls == [3, 1])
        await model.selectSeason(1)
        #expect(library.seasonCalls == [3, 1], "the season on screen is not asked for again")
    }

    @Test func aSeasonThatFailsKeepsTheEpisodeOnTopAndOffersTryAgain() async {
        let model = model()
        await model.retry()
        library.failure = APIError(status: 500, message: "Season not found.")
        await model.selectSeason(1)
        #expect(model.rowState == .failed("Season not found."))
        #expect(model.episode?.id == 100037)
        library.failure = nil
        await model.retrySeason()
        #expect(model.episode?.id == 100011)
    }

    // MARK: Playing and marking

    @Test func playResumesOrStartsOver() async throws {
        let model = model(.episode(id: 100035, season: 3))
        await model.retry()
        model.play()
        let resume = try #require(navigator.playing)
        #expect(resume.fileID == 19335)
        #expect(resume.startMs == nil)
        #expect(resume.title == "Harbor Lights")
        #expect(resume.subtitle == "S03 E05 · The Long Dark")
        navigator.closePlayer()
        model.play(fromStart: true)
        #expect(navigator.playing?.startMs == 0)
    }

    @Test func aVersionFromTheMenuPlaysThatFile() async throws {
        let model = model(.episode(id: 100031, season: 3))
        await model.retry()
        let version = try #require(model.episode?.versions.first)
        model.play(version: version)
        #expect(navigator.playing?.fileID == version.fileID)
        navigator.closePlayer()
        model.play(version: MediaVersion(fileID: 78, label: "elsewhere.mkv", sizeBytes: 1))
        #expect(navigator.playing == nil, "only a version of that episode")
    }

    @Test func anEpisodeYetToAirPlaysNothing() async {
        let model = model(.episode(id: 100039, season: 3))
        await model.retry()
        #expect(model.episode?.pagePlayLabel == nil)
        model.play()
        #expect(navigator.playing == nil)
        #expect(model.watchToggleLabel == nil, "nothing to mark before it airs")
        #expect(!model.canDelete)
    }

    @Test func markWatchedTogglesAndSays() async {
        let model = model()
        await model.retry()
        await model.toggleWatched()
        #expect(library.watchCalls == ["episode 100037 true"])
        #expect(model.message == "Marked as watched")
        #expect(model.episode?.watched == true)
        #expect(model.watchToggleLabel == "Mark unwatched")
        await model.toggleWatched()
        #expect(library.watchCalls.last == "episode 100037 false")
        #expect(model.episode?.watched == false)
    }

    @Test func theSeasonAndTheShowMark() async {
        let model = model()
        await model.retry()
        #expect(model.canMarkSeason)
        #expect(model.seasonToggleLabel == "Mark season watched")
        await model.toggleSeasonWatched()
        #expect(library.watchCalls == ["season 100 3 true"])
        #expect(model.seasonWatched)
        #expect(model.episode?.watched == true)

        #expect(model.canMarkShow)
        #expect(model.showWatched)
        #expect(model.showToggleLabel == "Mark show unwatched")
        await model.toggleShowWatched()
        #expect(library.watchCalls.last == "show 100 false")
        #expect(!model.showWatched && !model.seasonWatched)
        #expect(model.showToggleLabel == "Mark show watched")
    }

    @Test func aFailedMarkSaysWhy() async {
        let model = model()
        await model.retry()
        library.failure = APIError(status: 500, message: "Database is locked.")
        await model.toggleSeasonWatched()
        #expect(model.message == "Database is locked.")
        #expect(!model.busy)
    }

    // MARK: The episode's ⋯

    @Test func adminsGetEveryItem() async {
        let model = model()
        await model.retry()
        #expect(model.canReport)
        #expect(model.canReplace, "Sonarr holds Harbor Lights")
        #expect(model.canDelete)
        #expect(model.hasMenu)
    }

    @Test func membersDeleteOnlyWhenTheServerLetsEveryone() async {
        let member = model(requests: FakeRequestsRepository(library: library.fake, admin: false))
        await member.retry()
        #expect(member.canReport)
        #expect(!member.canReplace)
        #expect(!member.canDelete, "Who can delete is Admins only")

        let allowed = model(requests: FakeRequestsRepository(library: library.fake, admin: false, everyoneDeletes: true))
        await allowed.retry()
        #expect(allowed.canDelete)
        #expect(!allowed.canReplace, "Replace file stays with admins")
    }

    @Test func anOlderServerKeepsOnlyTheMarks() async {
        let older = model(requests: NoRequests())
        await older.retry()
        #expect(!older.canShowMissing)
        #expect(!older.canRequestMissingSeasons)
        #expect(older.canMarkSeason && older.canMarkShow)
        #expect(!older.hasMenu)
    }

    @Test func anOlderServerOffersNoDelete() async {
        let older = model(requests: StubRequests(RequestOptions(problems: true, manage: true)))
        await older.retry()
        #expect(!older.canDelete, "RequestOptions.delete is false below API level 6")
        #expect(older.canReplace)
    }

    @Test func deleteConfirmsThenPutsTheNextEpisodeOnTop() async throws {
        let requests = FakeRequestsRepository(library: library.fake)
        navigator.open(.episode(showID: 100, season: 3, id: 100037))
        let model = model(requests: requests)
        await model.retry()
        await model.askDelete()
        let dialog = try #require(model.dialog)
        #expect(dialog.kind == .delete)
        #expect(dialog.title == "Delete “The Long Dark”?")
        #expect(dialog.subtitle == "Harbor Lights · S03 E07")
        #expect(dialog.file == "Harbor Lights - S03E07.mkv · \(formatBytes(2_400_000_000))")
        #expect(dialog.message == [
            "Sonarr deletes the file and stops monitoring the episode, so it isn’t downloaded again. It goes to Sonarr’s recycling bin.",
            "Your watch history stays.",
        ])
        #expect(dialog.confirm == "Delete episode")
        #expect(dialog.dismiss == "Cancel")

        model.dialog = nil // the action sheet clears it before its button's task runs
        await model.confirm(dialog)
        #expect(requests.deletedEpisodes == [100037])
        #expect(model.dialog == nil)
        #expect(model.episode?.id == 100038, "the next one is on top")
        #expect(model.note == "Deleted “The Long Dark”. Showing the next episode.")
        #expect(!model.seasonEpisodes.contains { $0.id == 100037 }, "gone from the row")
        #expect(navigator.path(for: .home) == [.episode(showID: 100, season: 3, id: 100037)], "the page stays")

        model.select(model.seasonEpisodes[0])
        #expect(model.note == nil)
    }

    @Test func deletingTheLastOnDiskPutsTheOneBeforeOnTop() async throws {
        let requests = FakeRequestsRepository(library: library.fake)
        let model = model(.episode(id: 100038, season: 3), requests: requests)
        await model.retry()
        await model.askDelete()
        await model.confirm(try #require(model.dialog))
        #expect(model.episode?.id == 100039, "the one yet to air is next in the row")
        #expect(model.note == "Deleted “Squall Line”. Showing the next episode.")
    }

    @Test func deletingARecordingSendsTheTickedChoiceAndAnythingElseSendsNone() async throws {
        let requests = FakeRequestsRepository(library: library.fake)
        requests.recordedEpisodes = [100037, 100036]
        let first = model(requests: requests)
        await first.retry()
        await first.askDelete()
        let dialog = try #require(first.dialog)
        #expect(dialog.recordAgainChoice && first.doNotRecordAgain, "Ticked when it opens")
        await first.confirm(dialog)
        #expect(requests.recordAgainChoices == [false], "Ticked: don't record it again")

        let second = model(.episode(id: 100036, season: 3), requests: requests)
        await second.retry()
        await second.askDelete()
        second.doNotRecordAgain = false
        await second.confirm(try #require(second.dialog))
        #expect(requests.recordAgainChoices == [false, true], "Unticked: a series may record it again")

        let sonarr = model(.episode(id: 100035, season: 3), requests: requests)
        await sonarr.retry()
        await sonarr.askDelete()
        await sonarr.confirm(try #require(sonarr.dialog))
        #expect(requests.recordAgainChoices == [false, true, nil], "A file Sonarr deletes says nothing about recording")
    }

    @Test func withoutARecyclingBinItIsDeletedForGood() async throws {
        let requests = FakeRequestsRepository(library: library.fake)
        requests.recycleBin = false
        let model = model(requests: requests)
        await model.retry()
        await model.askDelete()
        #expect(model.dialog?.message.first?.hasSuffix("It is deleted for good.") == true)
    }

    @Test func aFileNoAppManagesCantBeDeletedHere() async throws {
        let requests = FakeRequestsRepository(library: library.fake)
        let model = model(showID: 101, .episode(id: 101011, season: 1), requests: requests)
        await model.retry()
        #expect(model.canDelete, "the server can't say until it is asked")
        await model.askDelete()
        let dialog = try #require(model.dialog)
        #expect(dialog.kind == .cantDelete)
        #expect(dialog.title == "Delete “Low Water”?")
        #expect(dialog.text == "WatchMyMedia can’t delete this: no Radarr or Sonarr manages it. Delete it where it was downloaded, or on disk.")
        #expect(dialog.confirm == nil)
        #expect(dialog.dismiss == "OK")
        await model.confirm()
        #expect(requests.deletedEpisodes.isEmpty)
        #expect(model.note == nil)
    }

    @Test func aFailedDeleteStaysAndSaysWhy() async throws {
        // The server changed Who can delete after the page opened.
        let model = model(requests: StubRequests(RequestOptions(problems: true, delete: true), deleteError: APIError(status: 403, message: "Only admins can delete on this server")))
        await model.retry()
        await model.askDelete()
        #expect(model.dialog?.kind == .delete)
        await model.confirm()
        #expect(model.message == "Only admins can delete on this server")
        #expect(model.episode?.id == 100037)
        #expect(model.note == nil)
    }

    @Test func replaceFileConfirmsThenSaysWhatSonarrDoes() async throws {
        let requests = FakeRequestsRepository(library: library.fake)
        let model = model(requests: requests)
        await model.retry()
        model.askReplace()
        let dialog = try #require(model.dialog)
        #expect(dialog.title == "Replace this file?")
        #expect(dialog.text == "Sonarr blocks this release and searches for another. The episode is gone until the new copy arrives.")
        #expect(dialog.confirm == "Replace file")
        await model.confirm()
        #expect(requests.replacedFiles == [model.episode?.fileID].compactMap { $0 })
        #expect(model.message == "Sonarr blocked this release and is searching for another.")
    }

    @Test func reportAProblemOpensForTheEpisodeOnTop() async throws {
        let model = model()
        await model.retry()
        model.reportProblem()
        let report = try #require(model.report)
        #expect(report.target.id == 100037)
    }

    // MARK: About the show and Show missing

    @Test func requestMissingSeasonsOpensTheSheetOnEverySeasonThatCanBeAskedFor() async throws {
        let model = model(requests: FakeRequestsRepository(library: library.fake, admin: false))
        await model.retry()
        #expect(model.canRequestMissingSeasons, "Harbor Lights' season 2 is missing episodes")
        model.requestMissingSeasons()
        let sheet = try #require(model.requestSheet)
        await sheet.load()
        #expect(!sheet.chosen.isEmpty)
    }

    @Test func showMissingListsEveryEpisodeAndIsRemembered() async throws {
        let model = model(.episode(id: 100024, season: 2))
        await model.retry()
        #expect(model.canShowMissing)
        #expect(model.seasonEpisodes.map(\.number) == [4, 5, 6, 7, 8], "on disk only")
        await model.setShowMissing(true)
        #expect(model.missingShown)
        #expect(model.seasonEpisodes.map(\.number) == Array(1...8))
        #expect(model.seasonLine == "5 of 8 episodes · 3 missing")
        #expect(model.tabs.first { $0.number == 2 }?.tabCount(missingShown: true) == "5 of 8")
        #expect(preferences.showsMissing(showID: 100), "remembered for the show")
        #expect(self.model(.show).missingShown, "a page opened now has it on")

        model.select(model.seasonEpisodes[0])
        #expect(model.episode?.hasFile == false, "a missing episode can be on top")
        await model.setShowMissing(false)
        #expect(model.episode?.id == 100024, "turning it off puts the season's first on disk back")
        #expect(!preferences.showsMissing(showID: 100))
    }
}

/// A server that says what the user may do, and refuses a delete when `deleteError` is set.
@MainActor
@Observable
private final class StubRequests: RequestsRepository {
    let options: RequestOptions
    let deleteError: APIError?
    init(_ options: RequestOptions, deleteError: APIError? = nil) {
        self.options = options
        self.deleteError = deleteError
    }
    func refreshOptions() async {}
    func search(_ text: String) async throws -> [Requestable] { [] }
    func lookup(_ type: MediaType, tmdbID: Int) async throws -> Requestable { throw CancellationError() }
    func ask(_ type: MediaType, tmdbID: Int, seasons: [Int]) async throws -> CreatedRequest { throw CancellationError() }
    func mine() async throws -> [MediaRequest] { [] }
    func cancel(requestID: Int) async throws {}
    func inbox() async throws -> Inbox { Inbox() }
    func decide(requestID: Int, approve: Bool, note: String) async throws {}
    func report(_ item: WatchKind, id: Int, fileID: Int?, kind: ProblemKind, note: String) async throws -> Problem { throw CancellationError() }
    func resolve(problemID: Int) async throws {}
    func searchMissing(showID: Int, season: Int, episodes: [Int]) async throws {}
    func monitor(showID: Int, season: Int, episodes: [Int], monitored: Bool) async throws {}
    func previewDelete(episodeID: Int) async throws -> DeletePreview { DeletePreview(managed: true, apps: [DeleteApp(connection: "Sonarr", kind: .sonarr)]) }
    func deleteEpisode(id: Int) async throws -> DeleteOutcome {
        if let deleteError { throw deleteError }
        return DeleteOutcome()
    }
    func replaceFile(fileID: Int) async throws -> ReplaceOutcome { ReplaceOutcome(connection: "Sonarr", blocklisted: false) }
    func changes() -> AsyncStream<Void> { AsyncStream { _ in } }
}
