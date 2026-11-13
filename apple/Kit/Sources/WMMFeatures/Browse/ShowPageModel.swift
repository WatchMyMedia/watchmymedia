import Foundation
import Observation
import WMMModel

/// One page for a show, its seasons and its episodes (PLAN-SHOW-PAGE.md;
/// ShowPage, ShowPageMissing, Pad2ShowPage and TvShowPage.dc.html): the
/// episode on top with Play, Mark watched and its ⋯ (Report a problem,
/// Replace file for admins, Delete episode…), the season tabs (specials last)
/// over the season's episodes in a row, then About the show with the show's
/// actions (Mark show watched, Request missing seasons). The show's, a
/// season's and an episode's routes all open it; only the episode on top
/// differs (``ShowPageRule``).
///
/// Picking a tab or an episode changes the selection here and pushes
/// nothing, so Back leaves the show. The tab chosen loads its season and puts
/// its first unfinished episode on top. After a delete, the next episode in
/// the row (else the one before) is on top, with "Deleted …" over the row.
///
/// From API level 5 Show missing, remembered per show, lists every episode
/// and season with why it is missing, and the season gets Request season N
/// and, for admins, Search now and Monitor. Opened from Downloads with the
/// server away, the page is the downloaded copy and its downloaded siblings.
@MainActor
@Observable
public final class ShowPageModel {
    public let showID: Int
    public let arrival: ShowArrival
    /// The show as the server sent it, every season.
    public private(set) var loadedShow: Loadable<ShowDetail> = .loading
    /// The season whose tab is chosen.
    public private(set) var selectedSeason: Int?
    /// The chosen season as the server sent it, every episode; nil until a season is chosen.
    public private(set) var loadedSeason: Loadable<SeasonDetail>?
    /// The season the episode on top belongs to: the chosen one once it is in.
    public private(set) var topSeason: SeasonDetail?
    /// The episode on top; nil until its season is in.
    public private(set) var episodeID: Int?
    /// Why the episode on top was chosen for the person; nil once they pick one.
    public private(set) var reason: OnTopReason?
    /// "Deleted …" or "That episode isn’t here any more", over the row until another episode is picked.
    public private(set) var note: String?
    /// A mark, request, preview, delete or replace is on its way to the server.
    public private(set) var busy = false
    public var message: String?
    /// Report a problem, while it is up.
    public var report: ReportProblemModel?
    /// The delete confirmation, can't-delete or Replace file dialog, while it is up.
    public var dialog: EpisodeDialog?
    /// The delete dialog's "Don’t record it again if it’s shown again", for a recording; ticked when it opens.
    public var doNotRecordAgain = true
    /// The request sheet, while it is up.
    public var requestSheet: RequestSheetModel?
    /// Show missing, remembered per show.
    public private(set) var showMissing: Bool
    /// The page comes from a finished download because the server is away.
    public private(set) var offline = false

    @ObservationIgnored private let library: any LibraryRepository
    @ObservationIgnored private let requests: (any RequestsRepository)?
    @ObservationIgnored private let preferences: (any PreferencesRepository)?
    @ObservationIgnored private let downloads: (any DownloadRepository)?
    @ObservationIgnored private let downloadSession: (@MainActor () -> String?)?
    @ObservationIgnored private let downloadSessionAtOpen: String?
    @ObservationIgnored private let navigator: Navigator
    @ObservationIgnored private let changes: ScreenChanges
    /// The show's loads; one still running when another starts is cancelled.
    @ObservationIgnored private let showLoads = LoadController<ShowDetail>()
    /// The row's season: a newer one (another tab) wins, and an older reply is dropped.
    @ObservationIgnored private let seasonLoads = LoadController<SeasonDetail>(cancelsOlder: false)
    /// The episode on top was chosen by the rule or the person at least once.
    @ObservationIgnored private var placed = false

    public init(library: any LibraryRepository, navigator: Navigator, showID: Int, arrival: ShowArrival = .show, requests: (any RequestsRepository)? = nil, preferences: (any PreferencesRepository)? = nil, downloads: (any DownloadRepository)? = nil, downloadSession: (@MainActor () -> String?)? = nil,
                now: @escaping @MainActor () -> Date = { Date() }) {
        changes = ScreenChanges(now: now)
        self.library = library
        self.navigator = navigator
        self.requests = requests
        self.preferences = preferences
        self.downloads = downloads
        self.downloadSession = downloadSession
        self.downloadSessionAtOpen = downloadSession?()
        self.showMissing = preferences?.showsMissing(showID: showID) ?? false
        self.showID = showID
        self.arrival = arrival
    }

    // MARK: What the page shows

    /// The show as the page shows it (the seasons on disk, or every season
    /// with Show missing on), once the episode on top is known.
    public var state: Loadable<ShowDetail> {
        if !sameDownloadSession { return .failed("The account or server changed. Open the episode again.") }
        if offline && held == nil { return .failed("This episode isn’t downloaded any more.") }
        switch loadedShow {
        case .loading: return .loading
        case .failed(let text): return .failed(text)
        case .loaded(let full):
            if topSeason == nil, !full.seasons.isEmpty {
                if case .failed(let text)? = loadedSeason { return .failed(text) }
                return .loading
            }
            return .loaded(missingShown ? full : full.seasonsOnDisk(keep: selectedSeason))
        }
    }

    public var show: ShowDetail? { state.value }

    public var showTitle: String { loadedShow.value?.title ?? topSeason?.showTitle ?? "" }

    /// The episode on top.
    public var episode: EpisodeDetail? {
        guard state.value != nil, let episodeID else { return nil }
        return topSeason?.episodes.first { $0.id == episodeID }
    }

    /// The season tabs, the specials last.
    public var tabs: [SeasonSummary] { show?.tabSeasons ?? [] }

    /// One season: a plain heading over the row instead of a lone tab.
    public var singleSeason: Bool { tabs.count == 1 }

    /// The chosen season as the show lists it.
    public var selectedSummary: SeasonSummary? {
        loadedShow.value?.seasons.first { $0.number == selectedSeason } ?? loadedSeason?.value?.season
    }

    /// The row: the chosen season's episodes on disk, those yet to air and
    /// the one on top; every episode with Show missing on.
    public var seasonEpisodes: [EpisodeDetail] {
        let all = loadedSeason?.value?.episodes ?? []
        return missingShown ? all : all.filter { $0.hasFile || !$0.aired || $0.id == episodeID }
    }

    /// The row's state: loading or failed while the chosen season comes in.
    public var rowState: Loadable<[EpisodeDetail]> {
        switch loadedSeason {
        case .loaded?: .loaded(seasonEpisodes)
        case .failed(let text)?: .failed(text)
        case .loading?, nil: .loading
        }
    }

    /// Beside the tabs: "8 episodes · 4 watched · E09 airs Nov 4", or with
    /// Show missing on "5 of 8 episodes · 3 missing".
    public var seasonLine: String? {
        guard let season = loadedSeason?.value else { return nil }
        return missingShown ? season.missingSummary : season.rowLine()
    }

    /// The chip by the eyebrow.
    public var chip: String? { reason?.label }

    /// "Mark watched" or "Mark unwatched"; nil before the episode airs.
    public var watchToggleLabel: String? { episode?.actions().watchToggle }

    // MARK: Loading

    /// Loads the show and its season, then reloads quietly whenever one of
    /// the season's episodes or the show is marked, its library changes or
    /// requests change, until cancelled. Coming back to the page loads nothing
    /// unless that happened meanwhile (``ScreenChanges``). Marks and changes
    /// made here reload it through their own notice, once.
    public func run() async {
        await changes.run(
            subscribe: { merge(library.libraryChanges(), unknown: requests?.changes()) },
            hasContent: loadedShow.value != nil,
            load: { quiet in await load(quiet: quiet) },
            apply: { changes in
                guard changes.contains(where: affects) else { return }
                await load(quiet: true)
            }
        )
    }

    /// Whether `change` can touch the page. Another season's episodes (whose
    /// IDs the page doesn't hold) are left for the next return or change.
    private func affects(_ change: LibraryChange) -> Bool {
        switch change {
        case .watched(.episode, let ids, _):
            let shown = Set((loadedSeason?.value?.episodes ?? []).map(\.id) + (topSeason?.episodes ?? []).map(\.id))
            return ids.contains(where: shown.contains)
        case .markedShow(let id, _, _): return id == showID
        case .watched(.movie, _, _), .scan: return false
        case .library, .unknown: return change.touches(library: loadedShow.value?.libraryID)
        }
    }

    public func retry() async {
        await load(quiet: false)
        changes.loaded()
    }

    /// Pull to refresh: keeps the page as it is, and says why if it fails.
    public func refresh() async {
        await load(quiet: true)
        changes.loaded()
    }

    public func retrySeason() async {
        guard let selectedSeason else { return }
        await loadSeason(selectedSeason, quiet: false, place: placed ? .season : .arrival)
    }

    /// Chooses a tab: loads its season and puts its first unfinished episode on top.
    public func selectSeason(_ number: Int) async {
        guard !offline, number != selectedSeason || loadedSeason?.value == nil else { return }
        selectedSeason = number
        await loadSeason(number, quiet: false, place: .season)
    }

    /// Puts an episode of the row on top.
    public func select(_ episode: EpisodeDetail) {
        guard seasonEpisodes.contains(where: { $0.id == episode.id }) else { return }
        if let season = loadedSeason?.value { topSeason = season }
        guard episode.id != episodeID else { return }
        episodeID = episode.id
        reason = nil
        note = nil
        dialog = nil
    }

    /// Refreshes queued marks, local progress and removed downloads without a network request.
    public func refreshOffline() {
        guard offline else { return }
        if !loadOffline() {
            loadedShow = .failed("This episode isn’t downloaded any more.")
            loadedSeason = .failed("This episode isn’t downloaded any more.")
        }
    }

    /// Loads the show, then the chosen season. `quiet` keeps the page as it is
    /// meanwhile. A load still running when another starts is cancelled.
    func load(quiet: Bool) async {
        await showLoads.run { [weak self] load in await self?.fetch(load, quiet: quiet) }
    }

    private func fetch(_ load: LoadController<ShowDetail>.Load, quiet: Bool) async {
        showLoads.begin(&loadedShow, quiet: quiet)
        guard let result = await showLoads.fetch(load, { try await self.library.show(id: self.showID) }) else { return }
        if case .failed = result, loadOffline() { return }
        if let text = showLoads.quietFailure(result, quiet: quiet, over: loadedShow) {
            message = text
            return
        }
        switch result {
        case .failed, .loading:
            loadedShow = result
            return
        case .loaded(let full):
            loadedShow = result
            offline = false
            let visible = missingShown ? full : full.seasonsOnDisk(keep: selectedSeason)
            let numbers = visible.seasons.map(\.number)
            if let selectedSeason, numbers.contains(selectedSeason) {
                await loadSeason(selectedSeason, quiet: quiet && loadedSeason?.value != nil, place: placed ? nil : .arrival)
            } else if !placed {
                if let number = ShowPageRule.season(for: arrival, show: full) {
                    selectedSeason = number
                    await loadSeason(number, quiet: false, place: .arrival)
                } else {
                    await findSeason(full, load: load)
                }
            } else if let first = ShowPageRule.firstSeason(visible) {
                // The chosen season went (deleted, or only Show missing listed it).
                selectedSeason = first
                await loadSeason(first, quiet: false, place: .season)
            }
        }
    }

    private enum Placing { case arrival, season }

    /// Loads a season into the row; once it is in, it holds the episode on
    /// top, chosen by `place` (the arrival's rule or the season's first
    /// unfinished), kept when still there, else the season's first unfinished.
    private func loadSeason(_ number: Int, quiet: Bool, place: Placing?) async {
        await seasonLoads.run { [weak self] load in await self?.fetchSeason(load, number: number, quiet: quiet, place: place) }
    }

    private func fetchSeason(_ load: LoadController<SeasonDetail>.Load, number: Int, quiet: Bool, place: Placing?) async {
        seasonLoads.begin(&loadedSeason, quiet: quiet)
        guard let result = await seasonLoads.fetch(load, { try await self.library.season(showID: self.showID, number: number) }),
              selectedSeason == number else { return }
        if case .failed = result, !placed, loadOffline() { return }
        guard case .loaded(let season) = result else {
            if let text = seasonLoads.quietFailure(result, quiet: quiet, over: loadedSeason ?? .loading) {
                message = text
            } else {
                loadedSeason = result
            }
            return
        }
        loadedSeason = result
        placeTop(in: season, by: place)
    }

    private func placeTop(in season: SeasonDetail, by place: Placing?) {
        // A reload of the season on top keeps its episode; a season newly in takes its first unfinished.
        let placing = place ?? (topSeason?.season.number == season.season.number ? nil : .season)
        topSeason = season
        switch placing {
        case nil:
            if !season.episodes.contains(where: { $0.id == episodeID }) {
                episodeID = season.arrivalEpisode?.id
                reason = nil
            }
        case .arrival?:
            if let show = loadedShow.value, let top = ShowPageRule.onTop(for: arrival, show: show, season: season) {
                episodeID = top.episodeID
                reason = top.reason
                note = top.gone ? ShowPageNote.gone : nil
            }
        case .season?:
            episodeID = season.arrivalEpisode?.id
            reason = nil
            note = nil
        }
        placed = true
    }

    /// The season holding the episode, opened without one (Home's hero): the
    /// next episode's, else the first from the newest that has it, which is
    /// where a new episode is.
    private func findSeason(_ show: ShowDetail, load: LoadController<ShowDetail>.Load) async {
        guard case .episode(let id, _) = arrival else { return }
        loadedSeason = .loading
        for number in show.seasons.map(\.number).sorted(by: >) {
            guard let season = try? await library.season(showID: showID, number: number), showLoads.isCurrent(load) else { continue }
            if season.episodes.contains(where: { $0.id == id }) {
                selectedSeason = number
                loadedSeason = .loaded(season)
                placeTop(in: season, by: .arrival)
                return
            }
        }
        guard showLoads.isCurrent(load) else { return }
        // Not in any season: the show's own first season, saying so.
        guard let first = ShowPageRule.firstSeason(show) else { return }
        selectedSeason = first
        await loadSeason(first, quiet: false, place: .arrival)
    }

    // MARK: Offline

    private var held: OfflineDownload? {
        guard sameDownloadSession, case .episode(let id, _) = arrival else { return nil }
        let shown = episodeID ?? id
        return downloads?.downloads.first { $0.downloaded && $0.item.showID == showID && $0.item.episodeID == shown }
            ?? downloads?.downloads.first { $0.downloaded && $0.item.showID == showID && $0.item.episodeID == id }
    }

    private var sameDownloadSession: Bool {
        guard let downloadSession else { return true }
        return downloadSessionAtOpen != nil && downloadSession() == downloadSessionAtOpen
    }

    /// The copy and only its downloaded siblings, scoped by the repository to the current account.
    @discardableResult
    private func loadOffline() -> Bool {
        guard let held, let number = held.item.season, let detail = held.episodeDetail() else { return false }
        var siblings = (downloads?.downloads ?? []).filter {
            $0.downloaded && $0.item.showID == showID && $0.item.season == number
        }.compactMap { $0.episodeDetail() }
        // A replaced file may leave two copies of one episode until the new one completes.
        var seen: Set<Int> = []
        siblings = siblings.filter { seen.insert($0.id).inserted }.sorted { $0.number < $1.number }
        if !siblings.contains(where: { $0.id == detail.id }) { siblings.append(detail) }
        let summary = SeasonSummary(id: number, number: number, name: seasonName(number),
                                    episodeCount: siblings.count, availableCount: siblings.count,
                                    watchedCount: siblings.filter(\.watched).count)
        loadedShow = .loaded(ShowDetail(
            id: showID, libraryID: 0, title: held.item.showTitle ?? held.item.title,
            year: held.item.year, overview: "", status: nil, genres: [], rating: nil,
            posterPath: held.item.posterPath, backdropPath: nil, quality: nil, hdr: false,
            seasons: [summary], totalEpisodes: siblings.count, episodesOnDisk: siblings.count,
            totalSizeBytes: siblings.reduce(0) { $0 + ($1.versions.first?.sizeBytes ?? 0) },
            nextAirDate: nil, nextUp: nil, cast: []
        ))
        let season = SeasonDetail(showID: showID, showTitle: held.item.showTitle ?? held.item.title, season: summary, episodes: siblings)
        loadedSeason = .loaded(season)
        topSeason = season
        selectedSeason = number
        if episodeID == nil || !siblings.contains(where: { $0.id == episodeID }) { episodeID = detail.id }
        placed = true
        reason = nil
        offline = true
        report = nil
        dialog = nil
        requestSheet = nil
        return true
    }

    // MARK: Playing and marking

    /// The episode on top: resumes where the viewer left off, or plays from 0:00 with `fromStart`.
    public func play(fromStart: Bool = false) {
        guard let episode, let request = playRequest(episode, fromStart: fromStart) else { return }
        navigator.play(request)
    }

    /// One version (file) of the episode on top, resuming: its ⋯ on Apple TV.
    public func play(version: MediaVersion) {
        guard let episode, episode.versions.contains(version), var request = playRequest(episode) else { return }
        request.fileID = version.fileID
        navigator.play(request)
    }

    /// What Play/Pause does on an episode of the row on Apple TV
    /// (``CardPlayPause``): plays it, resuming, when it is on disk; else the
    /// card's Select, which puts it on top.
    public func cardPlayPause(_ episode: EpisodeDetail) -> CardPlayPause {
        guard episode.playable, let request = playRequest(episode) else { return .select }
        return .play(request)
    }

    private func playRequest(_ episode: EpisodeDetail, fromStart: Bool = false) -> PlayRequest? {
        let show = loadedShow.value
        return episode.playRequest(showTitle: showTitle, backdropPath: show?.backdropPath, posterPath: show?.posterPath, fromStart: fromStart, showID: offline ? nil : showID)
    }

    /// Mark watched, or Mark unwatched (which takes it back to not started).
    public func toggleWatched() async {
        guard let episode, episode.aired else { return }
        if offline, let held, held.item.episodeID == episode.id {
            downloads?.mark(fileID: held.fileID, watched: !episode.watched)
            message = markedMessage(watched: !episode.watched)
            refreshOffline()
            return
        }
        await mark(.episode(id: episode.id), !episode.watched)
    }

    /// The chosen season has episodes on disk to mark.
    public var canMarkSeason: Bool { !offline && (selectedSummary?.availableCount ?? 0) > 0 }

    public var seasonWatched: Bool { selectedSummary?.allWatched ?? false }

    /// Beside the tabs: "Mark season watched" or "Mark season unwatched".
    public var seasonToggleLabel: String { seasonWatched ? "Mark season unwatched" : "Mark season watched" }

    /// Every episode of the chosen season on disk, watched or not.
    public func toggleSeasonWatched() async {
        guard let selectedSeason, canMarkSeason else { return }
        await mark(.season(showID: showID, number: selectedSeason), !seasonWatched)
    }

    /// About the show: something on disk to mark.
    public var canMarkShow: Bool { !offline && (loadedShow.value?.episodesOnDisk ?? 0) > 0 }

    public var showWatched: Bool { loadedShow.value?.allWatched ?? false }

    /// "Mark show watched" or "Mark show unwatched".
    public var showToggleLabel: String { markShowLabel(watched: showWatched) }

    /// Every episode of the show on disk, watched or not.
    public func toggleShowWatched() async {
        guard canMarkShow else { return }
        await mark(.show(id: showID), !showWatched)
    }

    private func mark(_ target: WatchTarget, _ watched: Bool) async {
        guard !busy else { return }
        busy = true
        // The mark's own notice reloads the page (``run()``).
        let note = await markWatched(target, watched, in: library, offline: {
            // No server: an episode's mark waits with its download.
            guard case .episode = target, let held else { return false }
            downloads?.mark(fileID: held.fileID, watched: watched)
            loadOffline()
            return true
        })
        busy = false
        message = note
    }

    // MARK: The episode's ⋯

    private var options: RequestOptions { requests?.options ?? .none }

    /// Report a problem: anyone who may report, on an episode with a file.
    public var canReport: Bool { !offline && requests != nil && options.problems && (episode?.playable ?? false) }

    /// Replace file: admins, when Sonarr manages the show and the episode has a file.
    public var canReplace: Bool { !offline && options.manage && loadedShow.value?.managedBy != nil && (episode?.playable ?? false) }

    /// Delete episode: from API level 6, whoever the server lets delete, on an episode with a file.
    public var canDelete: Bool { !offline && options.delete && (episode?.hasFile ?? false) }

    /// The ⋯ button shows: something is in its menu.
    public var hasMenu: Bool { canReport || canReplace || canDelete }

    public func reportProblem() {
        guard let requests, let episode, canReport else { return }
        report = ReportProblemModel(requests: requests, target: .episode(episode, showTitle: showTitle))
    }

    /// Asks what deleting would do, then shows the confirmation, or that
    /// WatchMyMedia can't delete it when no Sonarr manages the file.
    public func askDelete() async {
        guard let requests, let episode, canDelete, !busy else { return }
        busy = true
        defer { busy = false }
        do {
            let preview = try await requests.previewDelete(episodeID: episode.id)
            guard episode.id == episodeID else { return }
            doNotRecordAgain = true
            dialog = preview.managed && (!preview.apps.isEmpty || preview.recording)
                ? .delete(episode, showTitle: showTitle, preview: preview)
                : .cantDelete(episode, showTitle: showTitle)
        } catch is CancellationError {
        } catch {
            message = error.displayMessage
        }
    }

    /// Shows Replace file's confirmation.
    public func askReplace() {
        guard let episode, canReplace else { return }
        dialog = .replace(episode, showTitle: showTitle, app: loadedShow.value?.managedBy ?? "Sonarr")
    }

    /// The dialog's destructive button: deletes the episode and puts the next
    /// one on top, or replaces the file. `shown` is the dialog the button was
    /// on, which the alert may have cleared before this runs.
    public func confirm(_ shown: EpisodeDialog? = nil) async {
        guard let dialog = shown ?? dialog, let episode, !busy else { return }
        self.dialog = nil
        guard let requests else { return }
        busy = true
        defer { busy = false }
        do {
            switch dialog.kind {
            case .delete:
                // Ticked means don't record it again: recordAgain false.
                let outcome = try await requests.deleteEpisode(id: episode.id, recordAgain: dialog.recordAgainChoice ? !doNotRecordAgain : nil)
                if outcome.deleted {
                    let after = ShowPageRule.afterDelete(episode.id, in: seasonEpisodes)
                    if let after { episodeID = after.id }
                    reason = nil
                    note = ([ShowPageNote.deleted(episode.displayTitle, next: after?.next)] + outcome.failures).joined(separator: ". ")
                    busy = false
                    await load(quiet: true)
                } else {
                    message = outcome.failures.first ?? "Couldn’t delete the episode"
                }
            case .replace:
                guard let fileID = episode.fileID else { return }
                let outcome = try await requests.replaceFile(fileID: fileID)
                message = outcome.message
                busy = false
                await load(quiet: true)
            case .cantDelete:
                break
            }
        } catch is CancellationError {
        } catch {
            message = error.displayMessage
        }
    }

    // MARK: Missing episodes and requests

    /// The Show missing switch is offered: the server says why episodes are missing (API level 5).
    public var canShowMissing: Bool { !offline && options.supported }

    /// Show missing is on, on a server that can say why: every episode and season is listed.
    public var missingShown: Bool { showMissing && canShowMissing }

    /// Turns Show missing on or off, remembered for this show.
    public func setShowMissing(_ on: Bool) async {
        guard canShowMissing, on != showMissing else { return }
        showMissing = on
        preferences?.setShowsMissing(on, showID: showID)
        if !on {
            // The chosen season may be one only Show missing lists, or the
            // episode on top one only it lists.
            if let full = loadedShow.value, let selected = selectedSeason,
               !full.seasonsOnDisk().seasons.contains(where: { $0.number == selected }),
               let first = ShowPageRule.firstSeason(full.seasonsOnDisk()) {
                await selectSeason(first)
            } else if let episode, episode.aired, !episode.hasFile, let season = loadedSeason?.value {
                episodeID = season.arrivalEpisode?.id
            }
        }
    }

    /// The chosen season with every episode, as the server sent it.
    private var fullSeason: SeasonDetail? { loadedSeason?.value }

    /// "4 of 7 episodes · 3 missing".
    public var missingSummary: String? { canShowMissing ? fullSeason?.missingSummary : nil }

    /// The chosen season's missing (aired) episodes.
    public var missingEpisodes: [EpisodeDetail] { fullSeason?.missingEpisodes ?? [] }

    /// Request season N: anyone who may ask, for an aired season with
    /// something missing that nobody asked for yet.
    public var canRequestSeason: Bool {
        guard !offline, options.shows, loadedShow.value?.tmdbID != nil, let summary = selectedSummary, summary.number > 0 else { return false }
        return summary.requestable() && !missingEpisodes.isEmpty
    }

    /// The chosen season was asked for and is still on its way: "Requested", greyed.
    public var seasonRequested: Bool { (fullSeason?.season.requestOpen ?? false) || (selectedSummary?.requestOpen ?? false) }

    /// "Request season 3".
    public var seasonRequestLabel: String { requestButtonLabel(.tv, seasons: selectedSeason.map { [$0] } ?? []) }

    /// About the show: Request missing seasons.
    public var canRequestMissingSeasons: Bool { !offline && requests != nil && (loadedShow.value?.offersMissingSeasons(options) ?? false) }

    /// Admins with Sonarr holding the show: Search now and Monitor.
    private var manages: Bool { !offline && options.manage && loadedShow.value?.managedBy != nil }

    /// Search now: Sonarr looks for the season's missing episodes.
    public var canSearchNow: Bool { manages && !missingEpisodes.isEmpty }

    /// Monitor: the season, or some of its missing episodes, are not monitored.
    public var canMonitor: Bool {
        manages && (fullSeason?.season.monitored == false || missingEpisodes.contains { $0.monitored == false })
    }

    /// Admins, on one missing episode.
    public func canSearchNow(_ episode: EpisodeDetail) -> Bool { manages && episode.aired && !episode.hasFile }

    public func canMonitor(_ episode: EpisodeDetail) -> Bool { manages && !episode.hasFile && episode.monitored == false }

    /// A missing episode's Request asks for its season (Sonarr fetches only what is missing).
    public func canRequest(_ episode: EpisodeDetail) -> Bool {
        canRequestSeason && episode.season == selectedSeason && episode.aired && !episode.hasFile
    }

    /// Opens the request sheet for some seasons (Request season N), or for
    /// every season that can be asked for (Request missing seasons).
    public func openRequest(seasons: [Int]? = nil) {
        guard let requests, !offline, let sheet = loadedShow.value?.requestSheet(requests: requests, seasons: seasons) else { return }
        requestSheet = sheet
    }

    /// Request missing seasons, from About the show.
    public func requestMissingSeasons() {
        guard canRequestMissingSeasons else { return }
        openRequest()
    }

    /// Request season N for the chosen season.
    public func requestSeason() {
        guard let selectedSeason else { return }
        openRequest(seasons: [selectedSeason])
    }

    /// Search now on the chosen season, or on one episode.
    public func searchNow(_ episode: EpisodeDetail? = nil) async {
        guard let requests, let number = episode?.season ?? selectedSeason else { return }
        await act(message: "Sonarr is searching") {
            try await requests.searchMissing(showID: self.showID, season: number, episodes: episode.map { [$0.number] } ?? [])
        }
    }

    /// Monitor the chosen season (or, when the season is monitored, its
    /// unmonitored missing episodes), or one episode.
    public func monitor(_ episode: EpisodeDetail? = nil) async {
        guard let requests, let number = episode?.season ?? selectedSeason else { return }
        let numbers: [Int]
        if let episode {
            numbers = [episode.number]
        } else if fullSeason?.season.monitored == false {
            numbers = []
        } else {
            numbers = missingEpisodes.filter { $0.monitored == false }.map(\.number)
        }
        await act(message: "Monitored in Sonarr") {
            try await requests.monitor(showID: self.showID, season: number, episodes: numbers, monitored: true)
        }
    }

    private func act(message done: String, _ operation: @escaping () async throws -> Void) async {
        guard !busy else { return }
        busy = true
        do {
            try await operation()
            busy = false
            message = done
            await load(quiet: true)
        } catch is CancellationError {
            busy = false
        } catch {
            busy = false
            message = error.displayMessage
        }
    }
}

extension ShowDetail {
    /// Request missing seasons is offered: someone who may ask for shows, on
    /// a show TMDB knows, with a season that can be asked for. `self` is
    /// every season, as the server sent them.
    func offersMissingSeasons(_ options: RequestOptions) -> Bool {
        options.shows && tmdbID != nil && !requestableSeasons().isEmpty
    }

    /// The request sheet, loading, for some seasons (Request season N) or for
    /// every season that can be asked for; nil for a show TMDB doesn't know.
    @MainActor
    func requestSheet(requests: any RequestsRepository, seasons: [Int]?) -> RequestSheetModel? {
        guard let tmdbID else { return nil }
        let placeholder = Requestable(mediaType: .tv, tmdbID: tmdbID, title: title, year: year, overview: overview, posterPath: posterPath, state: .available, showID: id)
        let sheet = RequestSheetModel(requests: requests, mediaType: .tv, tmdbID: tmdbID, preselect: seasons, placeholder: placeholder)
        Task { await sheet.loadOnce() }
        return sheet
    }
}
