import Foundation
import Observation
import WMMModel

/// A movie (Movie.dc.html) with its versions, how each would play on this
/// device (``PlaybackControllerFactory/plans(fileIDs:)``), and the selected
/// version's tracks. Play and Resume play the selected version (the first
/// unless another was picked); each version's Play this plays that one.
@MainActor
@Observable
public final class MovieModel {
    public let movieID: Int
    public private(set) var state: Loadable<MovieDetail> = .loading
    /// By file ID; empty until known, and when the server cannot say.
    public private(set) var plans: [Int: PlaybackPlan] = [:]
    public private(set) var selectedVersionID: Int?
    /// A mark is on its way to the server.
    public private(set) var busy = false
    public var message: String?
    /// YouTube's player for the movie's YouTube trailers, while it is open (iPhone and iPad).
    public var trailerPlayer: TrailerPlayerModel?
    /// Apple TV's code to scan for a YouTube trailer, while it shows (TvTrailerQR.dc.html).
    public var trailerCode: YouTubeTrailer?

    @ObservationIgnored private let library: any LibraryRepository
    @ObservationIgnored private let playback: any PlaybackControllerFactory
    @ObservationIgnored private let navigator: Navigator
    @ObservationIgnored private let device: String
    @ObservationIgnored private let downloads: (any DownloadRepository)?
    @ObservationIgnored private let changes: ScreenChanges
    @ObservationIgnored private let loads = LoadController<MovieDetail>()

    /// `device` names this device in how a version would play: "this phone", "this TV".
    /// With `downloads`, a downloaded movie's page opens with no server, and its marks wait for one (PLAN-DOWNLOADS.md §6).
    public init(library: any LibraryRepository, playback: any PlaybackControllerFactory, navigator: Navigator, movieID: Int, device: String = "this phone",
                downloads: (any DownloadRepository)? = nil, now: @escaping @MainActor () -> Date = { Date() }) {
        changes = ScreenChanges(now: now)
        self.library = library
        self.playback = playback
        self.navigator = navigator
        self.movieID = movieID
        self.device = device
        self.downloads = downloads
    }

    /// This movie's finished download, of any version.
    private var held: OfflineDownload? {
        downloads?.downloads.first { $0.item.movieID == movieID && $0.downloaded }
    }

    /// The page comes from the download: the server can't be reached.
    public private(set) var offline = false

    public var movie: MovieDetail? { state.value }

    public var selectedVersion: MediaVersion? {
        guard let movie else { return nil }
        return movie.versions.first { $0.id == selectedVersionID } ?? movie.versions.first
    }

    public var watched: Bool { movie?.watched ?? false }

    public var resumable: Bool { movie?.resumable ?? false }

    /// "Resume · 1 h 12 min left" or "Play".
    public var playLabel: String { WMMFeatures.playLabel(movie?.watch) }

    public var watchToggleLabel: String { WMMFeatures.watchToggleLabel(watched: watched) }

    /// How a version would play here, or its file name while that is not known.
    public func planLine(for version: MediaVersion) -> String {
        plans[version.fileID]?.versionLine(on: device) ?? version.label
    }

    public func isDefault(_ version: MediaVersion) -> Bool { movie?.versions.first?.id == version.id }

    /// The trailer Trailer plays: the first the server lists. Nil hides the
    /// button, as on servers before API level 20, which list none.
    public var trailer: MovieTrailer? { movie?.trailer }

    /// What Apple TV says under its button row while Trailer has the focus
    /// (TvMovieTrailer.dc.html); nil for a trailer file, which plays here.
    public func trailerHint(youTubeApp: Bool) -> String? {
        guard trailer?.youTube != nil else { return nil }
        return youTubeApp ? "Opens the YouTube app" : "Shows a code to scan with your phone"
    }

    // MARK: Loading

    /// Loads the movie and its plans, then reloads quietly whenever it is
    /// marked or its library changes, until cancelled. Coming back to the page
    /// loads nothing unless that happened meanwhile (``ScreenChanges``).
    public func run() async {
        await changes.run(
            subscribe: { library.libraryChanges() },
            hasContent: state.value != nil,
            load: { quiet in await load(quiet: quiet) },
            apply: { changes in
                guard changes.contains(where: affects) else { return }
                await load(quiet: true)
            }
        )
    }

    /// Whether `change` can touch this movie.
    private func affects(_ change: LibraryChange) -> Bool {
        switch change {
        case .watched(.movie, let ids, _): ids.contains(movieID)
        case .watched(.episode, _, _), .markedShow, .scan: false
        case .library, .unknown: change.touches(library: movie?.libraryID)
        }
    }

    public func retry() async {
        await load(quiet: false)
        changes.loaded()
    }

    // MARK: Taps

    /// Shows a version's tracks, and makes it the one Play plays.
    public func select(_ version: MediaVersion) {
        selectedVersionID = version.id
    }

    /// Plays the selected version: resumes, or plays from 0:00 with `fromStart`.
    public func play(fromStart: Bool = false) {
        guard let request = movie?.playRequest(version: selectedVersion, fromStart: fromStart) else { return }
        navigator.play(request)
    }

    /// A version's Play this: that file, resuming.
    public func play(_ version: MediaVersion) {
        guard let request = movie?.playRequest(version: version) else { return }
        navigator.play(request)
    }

    /// Trailer (PLAN-TRAILERS.md §6.3). A trailer file plays in our player
    /// from its start. A YouTube trailer opens YouTube's player in the app
    /// (`trailerPlayer`) on iPhone and iPad. On Apple TV, which passes
    /// `openApp`, it goes to the YouTube app when `openApp` opens its link,
    /// else to a code to scan (`trailerCode`).
    public func playTrailer(openApp: ((URL) -> Bool)? = nil) {
        guard let movie, let trailer = movie.trailer else { return }
        switch trailer.source {
        case .file(let fileID):
            navigator.play(movie.trailerRequest(fileID: fileID, name: trailer.name))
        case .youTube(let youTube):
            if let openApp {
                if !openApp(youTube.appURL) { trailerCode = youTube }
            } else {
                trailerPlayer = TrailerPlayerModel(movieTitle: movie.title, trailers: movie.youTubeTrailers)
            }
        }
    }

    public func setWatched(_ watched: Bool) async {
        guard !busy else { return }
        busy = true
        // The mark's own notice reloads the page (``run()``).
        let note = await markWatched(.movie(id: movieID), watched, in: library, offline: {
            // No server: the mark waits with the download, by when it was made.
            guard let held else { return false }
            downloads?.mark(fileID: held.fileID, watched: watched)
            if offline, let detail = self.held?.movieDetail() { state = .loaded(detail) }
            return true
        })
        busy = false
        message = note
    }

    /// The page from the download again, after a play or a mark made here with no server.
    public func refreshOffline() {
        guard offline, let detail = held?.movieDetail(), detail != state.value else { return }
        state = .loaded(detail)
    }

    // MARK: Internals

    /// A load still running when another starts is cancelled.
    func load(quiet: Bool) async {
        await loads.run { [weak self] load in await self?.fetch(load, quiet: quiet) }
    }

    private func fetch(_ load: LoadController<MovieDetail>.Load, quiet: Bool) async {
        loads.begin(&state, quiet: quiet)
        guard var result = await loads.fetch(load, { try await self.library.movie(id: self.movieID) }) else { return }
        if case .failed = result, let detail = held?.movieDetail() {
            // No server, but the movie is here: its page from the download.
            offline = true
            state = .loaded(detail)
            return
        }
        offline = false
        if let text = loads.quietFailure(result, quiet: quiet, over: state) {
            message = text
            return
        }
        if var movie = result.value, let held, held.watch != nil, movie.versions.contains(where: { $0.fileID == held.fileID }) {
            // Online, a downloaded movie resumes from what this device last heard, as it plays from the copy.
            movie.watch = held.watch
            result = .loaded(movie)
        }
        state = result
        guard let movie = result.value else { return }
        if let selectedVersionID, !movie.versions.contains(where: { $0.id == selectedVersionID }) { self.selectedVersionID = nil }
        // A version list without the play mode is still right, so this cannot fail the page.
        let found = await playback.plans(fileIDs: movie.versions.map(\.fileID))
        if loads.isCurrent(load) { plans = found }
    }
}
