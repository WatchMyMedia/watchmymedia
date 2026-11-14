import Foundation
import Observation

// Requests, downloads and problem reports (PLAN-ARR.md §6–§9): the server
// asks Radarr and Sonarr (or Seerr) for what is missing; the apps ask the
// server. Mirrors android/core/model's requests.

public enum MediaType: String, Hashable, Sendable { case movie, tv }

/// What the signed-in user may do (GET /api/requests/options), so every
/// screen decides from one place which buttons to show.
public struct RequestOptions: Hashable, Sendable {
    /// Movies can be requested.
    public var movies: Bool
    /// Shows and seasons can be requested.
    public var shows: Bool
    /// This user's requests wait for an admin.
    public var needsApproval: Bool
    /// This user can report a problem with a file.
    public var problems: Bool
    /// An admin with Radarr or Sonarr connected: Search now, Monitor and Replace file.
    public var manage: Bool
    /// This user may delete episodes (and movies): an admin, or anyone when the
    /// server lets everyone delete, with Radarr or Sonarr connected. False
    /// below ``deleteAPILevel``.
    public var delete: Bool
    /// An authenticated admin on an allowed network, independent of the request backend.
    public var inboxAllowed: Bool
    /// Who carries requests out: "none", "arr", or "seerr" from servers that have it.
    public var backend: String
    /// Admins: requests waiting for approval plus open problems.
    public var inbox: Int
    /// The server answered: it is at ``requestsAPILevel`` or later, so pages
    /// can show missing episodes and why they are missing.
    public var supported: Bool

    public init(movies: Bool = false, shows: Bool = false, needsApproval: Bool = false, problems: Bool = false, manage: Bool = false, delete: Bool = false, backend: String = "none", inbox: Int = 0, supported: Bool = true, inboxAllowed: Bool? = nil) {
        self.supported = supported
        self.delete = delete
        self.movies = movies
        self.shows = shows
        self.needsApproval = needsApproval
        self.problems = problems
        self.manage = manage
        self.inboxAllowed = inboxAllowed ?? manage
        self.backend = backend
        self.inbox = inbox
    }

    /// Nothing: a server below ``requestsAPILevel``, one without integrations, or nobody signed in.
    public static let none = RequestOptions(supported: false)

    /// Something can be asked for.
    public var canRequest: Bool { movies || shows }

    /// Existing requests remain available after requesting is disabled or disconnected.
    public var hasYourRequests: Bool { supported }

    /// Seerr carries requests out, and approves them in its own pages.
    public var viaSeerr: Bool { backend == "seerr" }

    public func canRequest(_ type: MediaType) -> Bool { type == .movie ? movies : shows }
}

/// A download in Radarr's or Sonarr's queue.
public struct QueueDownload: Hashable, Sendable {
    public enum State: String, Hashable, Sendable { case queued, downloading, importing, failed }

    public var state: State
    /// 0–1.
    public var progress: Double
    /// When the download client expects it to finish.
    public var finishesAt: Date?
    /// Why it failed or is stuck, in the app's words.
    public var message: String?

    public init(state: State, progress: Double, finishesAt: Date? = nil, message: String? = nil) {
        self.state = state
        self.progress = progress
        self.finishesAt = finishesAt
        self.message = message
    }
}

public enum RequestStatus: String, Hashable, Sendable {
    case pending, approved, declined, failed, available

    /// Still waiting on an admin or on the download.
    public var isOpen: Bool { self == .pending || self == .approved }
}

/// A request as an item's page shows it: the newest for a season or movie.
public struct RequestState: Hashable, Sendable, Identifiable {
    public var id: Int
    public var status: RequestStatus
    public var requestedBy: String
    /// The signed-in user asked for it.
    public var mine: Bool
    /// Season numbers asked for; empty for a movie.
    public var seasons: [Int]
    /// Why it was declined, or why carrying it out failed.
    public var note: String?
    public var createdAt: Date

    public init(id: Int, status: RequestStatus, requestedBy: String, mine: Bool, seasons: [Int] = [], note: String? = nil, createdAt: Date) {
        self.id = id
        self.status = status
        self.requestedBy = requestedBy
        self.mine = mine
        self.seasons = seasons
        self.note = note
        self.createdAt = createdAt
    }
}

/// A request in a list: Your requests, the Inbox.
public struct MediaRequest: Hashable, Sendable, Identifiable {
    public var id: Int
    public var status: RequestStatus
    public var requestedBy: String
    public var mine: Bool
    public var seasons: [Int]
    public var note: String?
    public var createdAt: Date
    public var mediaType: MediaType
    public var tmdbID: Int
    public var title: String
    public var year: Int?
    public var posterPath: String?
    public var download: QueueDownload?
    /// The library's movie or show once something of it is there.
    public var movieID: Int?
    public var showID: Int?

    public init(id: Int, status: RequestStatus, requestedBy: String, mine: Bool, seasons: [Int] = [], note: String? = nil, createdAt: Date, mediaType: MediaType, tmdbID: Int, title: String, year: Int? = nil, posterPath: String? = nil, download: QueueDownload? = nil, movieID: Int? = nil, showID: Int? = nil) {
        self.id = id
        self.status = status
        self.requestedBy = requestedBy
        self.mine = mine
        self.seasons = seasons
        self.note = note
        self.createdAt = createdAt
        self.mediaType = mediaType
        self.tmdbID = tmdbID
        self.title = title
        self.year = year
        self.posterPath = posterPath
        self.download = download
        self.movieID = movieID
        self.showID = showID
    }

    /// Waiting for an admin: Approve and Decline (a failed one can be tried again).
    public var needsDecision: Bool { status == .pending || status == .failed }

    /// The request as an item's page would show it.
    public var state: RequestState {
        RequestState(id: id, status: status, requestedBy: requestedBy, mine: mine, seasons: seasons, note: note, createdAt: createdAt)
    }
}

/// A season in the request sheet.
public struct RequestableSeason: Hashable, Sendable, Identifiable {
    public enum State: String, Hashable, Sendable {
        /// Every aired episode is in the library.
        case available
        case partial
        case requested
        /// Nothing has aired.
        case unaired
        case missing
    }

    public var number: Int
    public var name: String
    public var episodeCount: Int
    public var airDate: String?
    public var state: State

    public init(number: Int, name: String, episodeCount: Int, airDate: String? = nil, state: State) {
        self.number = number
        self.name = name
        self.episodeCount = episodeCount
        self.airDate = airDate
        self.state = state
    }

    public var id: Int { number }

    /// It can be ticked: some of it aired and is missing, and nobody asked for it.
    public var askable: Bool { state == .missing || state == .partial }
}

/// A movie or show someone may ask for (TMDB's, through the server), with where it stands.
public struct Requestable: Hashable, Sendable, Identifiable {
    public enum State: String, Hashable, Sendable {
        case requestable
        /// Asked for, not yet downloading.
        case requested
        case downloading
        /// Something of it is in the library.
        case available
    }

    public var mediaType: MediaType
    public var tmdbID: Int
    public var title: String
    public var year: Int?
    public var overview: String
    public var posterPath: String?
    public var state: State
    public var movieID: Int?
    public var showID: Int?
    /// The newest open request.
    public var request: RequestState?
    /// A show's seasons, from the lookup only.
    public var seasons: [RequestableSeason]

    public init(mediaType: MediaType, tmdbID: Int, title: String, year: Int? = nil, overview: String = "", posterPath: String? = nil, state: State, movieID: Int? = nil, showID: Int? = nil, request: RequestState? = nil, seasons: [RequestableSeason] = []) {
        self.mediaType = mediaType
        self.tmdbID = tmdbID
        self.title = title
        self.year = year
        self.overview = overview
        self.posterPath = posterPath
        self.state = state
        self.movieID = movieID
        self.showID = showID
        self.request = request
        self.seasons = seasons
    }

    public var id: String { "\(mediaType.rawValue)-\(tmdbID)" }
}

/// A request made: the new one, or the one that was there already.
public struct CreatedRequest: Hashable, Sendable {
    public var request: MediaRequest
    /// False when it was asked for already: by this user (the seasons were added) or by someone else.
    public var created: Bool

    public init(request: MediaRequest, created: Bool) {
        self.request = request
        self.created = created
    }
}

public enum ProblemKind: String, CaseIterable, Hashable, Sendable {
    case video, audio, subtitles, other

    /// "Video", "Audio", "Subtitles", "Other".
    public var label: String { rawValue.prefix(1).uppercased() + rawValue.dropFirst() }
}

/// A problem someone reported with a movie or episode, in the admins' Inbox.
public struct Problem: Hashable, Sendable, Identifiable {
    public var id: Int
    public var reportedBy: String
    public var itemType: WatchKind
    public var itemID: Int
    public var fileID: Int?
    public var kind: ProblemKind
    public var note: String
    /// What the server knew about the file: its name, codecs and resolution.
    public var details: String
    /// The movie, or the show and episode ("Harbor Lights S03 E07").
    public var title: String
    public var showID: Int?
    public var season: Int?
    public var createdAt: Date
    /// Present only when Seerr accepted this report as an issue.
    public var seerrIssueID: Int?

    public init(id: Int, reportedBy: String, itemType: WatchKind, itemID: Int, fileID: Int? = nil, kind: ProblemKind, note: String, details: String, title: String, showID: Int? = nil, season: Int? = nil, createdAt: Date, seerrIssueID: Int? = nil) {
        self.id = id
        self.reportedBy = reportedBy
        self.seerrIssueID = seerrIssueID
        self.itemType = itemType
        self.itemID = itemID
        self.fileID = fileID
        self.kind = kind
        self.note = note
        self.details = details
        self.title = title
        self.showID = showID
        self.season = season
        self.createdAt = createdAt
    }
}

/// The external approval page and optional count when Seerr handles requests.
public struct SeerrInbox: Hashable, Sendable {
    public var requestsURL: URL?
    public var pending: Int?

    public init(requestsURL: URL? = nil, pending: Int? = nil) {
        self.requestsURL = requestsURL
        self.pending = pending
    }
}

/// Local requests and problems, plus Seerr’s approval page when connected.
public struct Inbox: Hashable, Sendable {
    public var requests: [MediaRequest]
    public var problems: [Problem]
    public var seerr: SeerrInbox?

    public init(requests: [MediaRequest] = [], problems: [Problem] = [], seerr: SeerrInbox? = nil) {
        self.seerr = seerr
        self.requests = requests
        self.problems = problems
    }
}

/// Requests and problem reports on the current server. Methods throw
/// ``APIError``; a revoked sign-in throws ``SignedOutError``.
@MainActor
public protocol RequestsRepository: AnyObject, Observable {
    /// What the signed-in user may do; ``RequestOptions/none`` until loaded,
    /// on servers below ``requestsAPILevel`` and without integrations.
    var options: RequestOptions { get }

    /// Asks the server what the user may do again (on launch, on foreground,
    /// after a change). Quiet: a failure keeps what was known.
    func refreshOptions() async

    /// TMDB's movies and shows for `text`, with where each stands. Called
    /// beside the library's search, never in its way.
    func search(_ text: String) async throws -> [Requestable]

    /// A movie or show with its seasons, for the request sheet.
    func lookup(_ type: MediaType, tmdbID: Int) async throws -> Requestable

    /// Asks for a movie, or some seasons of a show.
    func ask(_ type: MediaType, tmdbID: Int, seasons: [Int]) async throws -> CreatedRequest

    /// The signed-in user's requests, newest first.
    func mine() async throws -> [MediaRequest]

    /// Cancels a request that is waiting for approval.
    func cancel(requestID: Int) async throws

    /// Admins: requests and problems.
    func inbox() async throws -> Inbox

    /// Admins: approves a request, or declines it with a note for whoever asked.
    func decide(requestID: Int, approve: Bool, note: String) async throws

    /// Reports a problem with a movie or episode, and the file that played.
    @discardableResult
    func report(_ item: WatchKind, id: Int, fileID: Int?, kind: ProblemKind, note: String) async throws -> Problem

    /// Admins: marks a problem resolved.
    func resolve(problemID: Int) async throws

    /// Admins: has Sonarr look for a season's missing episodes, or some of
    /// them (`episodes`, by number), now.
    func searchMissing(showID: Int, season: Int, episodes: [Int]) async throws

    /// Admins: has Sonarr monitor (or stop monitoring) a season or some of its episodes.
    func monitor(showID: Int, season: Int, episodes: [Int], monitored: Bool) async throws

    /// What deleting an episode would do (``RequestOptions/delete``).
    func previewDelete(episodeID: Int) async throws -> DeletePreview

    /// Has the Sonarrs that hold an episode delete its file and stop monitoring it.
    func deleteEpisode(id: Int) async throws -> DeleteOutcome

    /// The same; for a file WatchMyMedia recorded (API level 19), `recordAgain`
    /// says whether a series may record it again if it's shown again. Nil sends
    /// nothing, which the server reads as don't record it again.
    func deleteEpisode(id: Int, recordAgain: Bool?) async throws -> DeleteOutcome

    /// Admins: the app that manages a file blocks its release, deletes it and searches again.
    func replaceFile(fileID: Int) async throws -> ReplaceOutcome

    /// Yields when requests or problems changed (here, or the server's
    /// requests.changed and problems.changed events), so screens refetch.
    /// Each call is a new listener; it ends when the task is cancelled.
    func changes() -> AsyncStream<Void>
}
