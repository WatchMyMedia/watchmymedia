import Foundation
import Observation
import WMMModel

/// Requests on the fakes: the boards' sample rows (SearchRequest, MyRequests
/// and Inbox.dc.html; every title and name is fictional). Asking for a season
/// of a library show marks its missing episodes requested on the show page,
/// and Search now and Monitor change them, so every screen runs without a server.
@MainActor
@Observable
public final class FakeRequestsRepository: RequestsRepository {
    public private(set) var options: RequestOptions
    public private(set) var myRequests: [MediaRequest]
    public private(set) var inboxRequests: [MediaRequest]
    public private(set) var problems: [Problem]
    /// What TMDB knows in the fakes, besides the library's shows.
    private var catalog: [Requestable]
    /// Reports sent, newest last; tests read them.
    public private(set) var reports: [(item: WatchKind, id: Int, fileID: Int?, kind: ProblemKind, note: String)] = []

    @ObservationIgnored private let library: FakeLibraryRepository?
    @ObservationIgnored private let admin: Bool
    @ObservationIgnored private let seerr: Bool
    @ObservationIgnored private let seerrLinked: Bool
    /// A refused Seerr issue falls back to the local Inbox.
    public var seerrAcceptsReports = true
    /// Sonarr moves what it deletes to its recycling bin.
    public var recycleBin = true
    /// Episodes deleted, by ID; tests read them.
    public private(set) var deletedEpisodes: [Int] = []
    /// Episodes whose file WatchMyMedia recorded, and what each delete said about recording them again.
    public var recordedEpisodes: Set<Int> = []
    public private(set) var recordAgainChoices: [Bool?] = []
    /// Files replaced, by ID; tests read them.
    public private(set) var replacedFiles: [Int] = []
    @ObservationIgnored private var nextID = 100
    @ObservationIgnored private var listeners: [UUID: AsyncStream<Void>.Continuation] = [:]

    /// `admin` false is Alex, a member: requests wait for an admin, and there
    /// is no Inbox, Search now or Monitor. Alex may delete episodes only when
    /// `everyoneDeletes` (the server's Who can delete is Everyone).
    public init(library: FakeLibraryRepository? = nil, admin: Bool = true, seerr: Bool = false, seerrLinked: Bool = true, everyoneDeletes: Bool = false, now: Date = .now) {
        self.library = library
        self.admin = admin
        self.seerr = seerr
        self.seerrLinked = seerrLinked
        let hour: TimeInterval = 3600, day: TimeInterval = 86_400
        myRequests = [
            MediaRequest(id: 1, status: .pending, requestedBy: "Sam", mine: true, seasons: [2], createdAt: now - 2 * hour, mediaType: .tv, tmdbID: FakeData.tmdbID(showID: 105), title: "Copperline", year: 2019, showID: 105),
            MediaRequest(id: 2, status: .pending, requestedBy: "Sam", mine: true, createdAt: now - 5 * hour, mediaType: .movie, tmdbID: 72_002, title: "The Harbour Keeper", year: 1987),
            MediaRequest(id: 3, status: .approved, requestedBy: "Sam", mine: true, seasons: [1, 2], createdAt: now - 26 * hour, mediaType: .tv, tmdbID: 71_002, title: "Harbor Bells", year: 2016, download: QueueDownload(state: .downloading, progress: 0.45)),
            MediaRequest(id: 4, status: .approved, requestedBy: "Sam", mine: true, seasons: [1], createdAt: now - 2 * day, mediaType: .tv, tmdbID: 71_001, title: "Harbourside", year: 2019),
            MediaRequest(id: 5, status: .failed, requestedBy: "Sam", mine: true, note: "Radarr has no root folder for this library", createdAt: now - 5 * day, mediaType: .movie, tmdbID: 72_003, title: "Low Water", year: 2023),
            MediaRequest(id: 6, status: .declined, requestedBy: "Sam", mine: true, seasons: [2], note: "Already on the other server", createdAt: now - 11 * day, mediaType: .tv, tmdbID: FakeData.tmdbID(showID: 104), title: "Orbital Decay", year: 2024, showID: 104),
            MediaRequest(id: 7, status: .available, requestedBy: "Sam", mine: true, seasons: [2], createdAt: now - 19 * day, mediaType: .tv, tmdbID: FakeData.tmdbID(showID: 106), title: "The Lantern House", year: 2020, showID: 106),
        ]
        inboxRequests = admin ? [
            MediaRequest(id: 11, status: .pending, requestedBy: "Alex", mine: false, seasons: [2], createdAt: now - 2 * hour, mediaType: .tv, tmdbID: FakeData.tmdbID(showID: 105), title: "Copperline", year: 2019, showID: 105),
            MediaRequest(id: 12, status: .pending, requestedBy: "Alex", mine: false, createdAt: now - 5 * hour, mediaType: .movie, tmdbID: 72_002, title: "The Harbour Keeper", year: 1987),
        ] : []
        problems = admin ? [
            Problem(id: 21, reportedBy: "Alex", itemType: .episode, itemID: 100037, fileID: 9101, kind: .audio, note: "No sound after 20 minutes", details: "Harbor.Lights.S03E07.2160p.mkv · HEVC · E-AC-3 · 2160p", title: "Harbor Lights · S03E07 · The Long Dark", showID: 100, season: 3, createdAt: now - 26 * hour),
            Problem(id: 22, reportedBy: "Alex", itemType: .movie, itemID: 500, fileID: 9900, kind: .subtitles, note: "English subtitles are a second late", details: "Dead.Reckoning.Bay.2026.2160p.mkv · HEVC · TrueHD 7.1 · 2160p", title: "Dead Reckoning Bay", createdAt: now - 3 * day),
        ] : []
        let alex = RequestState(id: 4, status: .pending, requestedBy: "Alex", mine: !admin, seasons: [1], createdAt: now - 2 * day)
        catalog = [
            Requestable(mediaType: .tv, tmdbID: 71_001, title: "Harbourside", year: 2019, overview: "Two sisters reopen their late mother’s seafront guesthouse and find she left them the whole town’s secrets with it.", state: .requestable, request: alex, seasons: [
                RequestableSeason(number: 1, name: "Season 1", episodeCount: 8, airDate: "2019-09-14", state: .requested),
                RequestableSeason(number: 2, name: "Season 2", episodeCount: 8, airDate: "2020-09-14", state: .missing),
                RequestableSeason(number: 3, name: "Season 3", episodeCount: 8, airDate: "2021-09-14", state: .missing),
                RequestableSeason(number: 4, name: "Season 4", episodeCount: 6, airDate: "2022-09-14", state: .missing),
            ]),
            Requestable(mediaType: .movie, tmdbID: 72_001, title: "Harbor Ghosts", year: 2024, overview: "A lighthouse keeper’s daughter hears voices on a harbour radio channel that went silent in 1962.", state: .requestable),
            Requestable(mediaType: .movie, tmdbID: 72_002, title: "The Harbour Keeper", year: 1987, overview: "A keeper who never left his rock is asked to leave it for one night.", state: .requested),
            Requestable(mediaType: .tv, tmdbID: 71_002, title: "Harbor Bells", year: 2016, overview: "A bell foundry on the estuary casts its last commission.", state: .downloading, seasons: [
                RequestableSeason(number: 1, name: "Season 1", episodeCount: 8, airDate: "2016-03-01", state: .requested),
                RequestableSeason(number: 2, name: "Season 2", episodeCount: 8, airDate: "2017-03-01", state: .requested),
            ]),
            Requestable(mediaType: .movie, tmdbID: 72_004, title: "Low Water Mark", year: 2021, overview: "A river pilot reads the drought in the stones.", state: .requestable),
        ] + FakeRowsRepository.catalog
        options = RequestOptions(
            movies: !seerr || seerrLinked, shows: !seerr || seerrLinked, needsApproval: !admin, problems: true, manage: admin && !seerr,
            delete: (admin || everyoneDeletes) && !seerr, backend: seerr ? "seerr" : "arr",
            inbox: admin ? 4 : 0, inboxAllowed: admin
        )
    }

    public func refreshOptions() async {
        options.inbox = admin ? inboxRequests.filter { $0.status == .pending }.count + problems.count : 0
    }

    public func search(_ text: String) async throws -> [Requestable] {
        await FakeLatency.wait()
        let t = text.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return [] }
        let library = FakeData.shows.filter { $0.title.localizedCaseInsensitiveContains(t) }.map { libraryShow($0.id) }
        return catalog.filter { $0.title.localizedCaseInsensitiveContains(t) } + library
    }

    public func lookup(_ type: MediaType, tmdbID: Int) async throws -> Requestable {
        await FakeLatency.wait()
        if let found = catalog.first(where: { $0.mediaType == type && $0.tmdbID == tmdbID }) { return found }
        if type == .tv, let show = FakeData.shows.first(where: { FakeData.tmdbID(showID: $0.id) == tmdbID }) { return libraryShow(show.id) }
        throw APIError(status: 404, message: "TMDB has nothing with that ID.")
    }

    public func ask(_ type: MediaType, tmdbID: Int, seasons: [Int]) async throws -> CreatedRequest {
        await FakeLatency.wait()
        if seerr && !seerrLinked { throw APIError(status: 403, message: "Ask an admin to link your account to a Seerr user.") }
        if type == .tv && seasons.isEmpty { throw APIError(status: 422, message: "Choose at least one season.") }
        let item = try await lookup(type, tmdbID: tmdbID)
        nextID += 1
        let me = admin ? "Sam" : "Alex"
        let request = MediaRequest(
            id: nextID, status: admin ? .approved : .pending, requestedBy: me, mine: true, seasons: type == .tv ? seasons.sorted() : [],
            createdAt: .now, mediaType: type, tmdbID: tmdbID, title: item.title, year: item.year, posterPath: item.posterPath, showID: item.showID
        )
        myRequests.insert(request, at: 0)
        if let i = catalog.firstIndex(where: { $0.id == item.id }) {
            catalog[i].state = .requested
            catalog[i].request = request.state
            catalog[i].seasons = catalog[i].seasons.map { s in
                var copy = s
                if seasons.contains(s.number) { copy.state = .requested }
                return copy
            }
        }
        if let showID = item.showID {
            for n in seasons { library?.seasonRequests[.init(showID: showID, season: n)] = request.state }
            library?.changed()
        }
        changed()
        return CreatedRequest(request: request, created: true)
    }

    public func mine() async throws -> [MediaRequest] {
        await FakeLatency.wait()
        if seerr && !seerrLinked { return [] }
        return myRequests
    }

    public func cancel(requestID: Int) async throws {
        await FakeLatency.wait()
        guard let gone = myRequests.first(where: { $0.id == requestID }), gone.status == .pending else {
            throw APIError(status: 409, message: "Only a request that is waiting can be cancelled.")
        }
        myRequests.removeAll { $0.id == requestID }
        if let showID = gone.showID {
            for n in gone.seasons { library?.seasonRequests[.init(showID: showID, season: n)] = nil }
            library?.changed()
        }
        changed()
    }

    public func inbox() async throws -> Inbox {
        await FakeLatency.wait()
        guard admin else { throw APIError(status: 403, message: "Only an admin can do that.") }
        return Inbox(requests: inboxRequests, problems: problems, seerr: seerr ? SeerrInbox(requestsURL: URL(string: "https://seerr.example/requests"), pending: 3) : nil)
    }

    public func decide(requestID: Int, approve: Bool, note: String) async throws {
        await FakeLatency.wait()
        guard let i = inboxRequests.firstIndex(where: { $0.id == requestID }) else { throw APIError(status: 404, message: "request not found") }
        if approve {
            inboxRequests[i].status = .approved
        } else {
            inboxRequests.remove(at: i)
        }
        await refreshOptions()
        changed()
    }

    @discardableResult
    public func report(_ item: WatchKind, id: Int, fileID: Int?, kind: ProblemKind, note: String) async throws -> Problem {
        await FakeLatency.wait()
        reports.append((item, id, fileID, kind, note))
        nextID += 1
        let report = Problem(id: nextID, reportedBy: admin ? "Sam" : "Alex", itemType: item, itemID: id, fileID: fileID, kind: kind, note: note, details: "", title: "Harbor Lights", createdAt: .now, seerrIssueID: seerr && seerrLinked && seerrAcceptsReports ? nextID : nil)
        if report.seerrIssueID == nil { problems.append(report) }
        await refreshOptions()
        changed()
        return report
    }

    public func resolve(problemID: Int) async throws {
        await FakeLatency.wait()
        problems.removeAll { $0.id == problemID }
        await refreshOptions()
        changed()
    }

    public func searchMissing(showID: Int, season: Int, episodes: [Int]) async throws {
        await FakeLatency.wait()
        for e in try await missing(showID: showID, season: season, episodes: episodes) {
            library?.monitored[e.id] = true
            library?.downloads[e.id] = e.download ?? QueueDownload(state: .queued, progress: 0)
        }
        library?.changed()
    }

    public func monitor(showID: Int, season: Int, episodes: [Int], monitored: Bool) async throws {
        await FakeLatency.wait()
        for e in try await missing(showID: showID, season: season, episodes: episodes) {
            library?.monitored[e.id] = monitored
        }
        library?.changed()
    }

    /// Sonarr holds Harbor Lights and Copperline; any other episode has no delete.
    public func previewDelete(episodeID: Int) async throws -> DeletePreview {
        await FakeLatency.wait()
        let showID = episodeID / 1000
        if recordedEpisodes.contains(episodeID) { return DeletePreview(managed: true, files: 1, sizeBytes: 2_400_000_000, recording: true) }
        guard FakeData.sonarrShows.contains(showID) else { return DeletePreview(managed: false) }
        let size = FakeData.season(showID: showID, number: episodeID % 1000 / 10).episodes.first { $0.id == episodeID }?.versions.first?.sizeBytes ?? 0
        return DeletePreview(managed: true, files: 1, sizeBytes: size, apps: [DeleteApp(connection: "Sonarr", kind: .sonarr, recycleBin: recycleBin ? "/data/.recycle" : nil)])
    }

    public func deleteEpisode(id: Int) async throws -> DeleteOutcome { try await deleteEpisode(id: id, recordAgain: nil) }

    public func deleteEpisode(id: Int, recordAgain: Bool?) async throws -> DeleteOutcome {
        await FakeLatency.wait()
        recordAgainChoices.append(recordAgain)
        guard options.delete else { throw APIError(status: 403, message: "Only admins can delete on this server") }
        deletedEpisodes.append(id)
        library?.deleted.insert(id)
        library?.changed()
        return DeleteOutcome()
    }

    public func replaceFile(fileID: Int) async throws -> ReplaceOutcome {
        await FakeLatency.wait()
        replacedFiles.append(fileID)
        return ReplaceOutcome(connection: "Sonarr", blocklisted: true)
    }

    public func changes() -> AsyncStream<Void> {
        AsyncStream { continuation in
            let id = UUID()
            listeners[id] = continuation
            continuation.onTermination = { [weak self] _ in Task { @MainActor in self?.listeners[id] = nil } }
        }
    }

    private func changed() {
        for listener in listeners.values { listener.yield() }
    }

    /// The season's aired episodes without a file, or those of them numbered in `episodes`.
    private func missing(showID: Int, season: Int, episodes: [Int]) async throws -> [EpisodeDetail] {
        let all = FakeData.season(showID: showID, number: season).missingEpisodes
        return episodes.isEmpty ? all : all.filter { episodes.contains($0.number) }
    }

    /// A library show as TMDB's search would find it: in the library, its
    /// seasons as full, partly there or asked for.
    private func libraryShow(_ id: Int) -> Requestable {
        let show = FakeData.show(id: id)
        return Requestable(
            mediaType: .tv, tmdbID: FakeData.tmdbID(showID: id), title: show.title, year: show.year, overview: show.overview,
            state: .available, showID: id,
            seasons: show.seasons.map { s in
                let asked = library?.seasonRequests[.init(showID: id, season: s.number)]?.status.isOpen == true
                let gone = FakeData.season(showID: id, number: s.number).missingEpisodes.count
                let state: RequestableSeason.State =
                    asked ? .requested : gone == 0 ? .available : s.availableCount > 0 ? .partial : .missing
                return RequestableSeason(number: s.number, name: s.name, episodeCount: s.episodeCount, airDate: s.airDate, state: state)
            }
        )
    }
}
