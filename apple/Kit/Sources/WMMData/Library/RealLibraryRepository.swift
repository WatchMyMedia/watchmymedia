import Foundation
import WMMAPI
import WMMModel

/// Browsing the current server's libraries as the signed-in user
/// (android/core/data/.../RealLibraryRepository.kt).
@MainActor
final class RealLibraryRepository: LibraryRepository {
    private let store: LocalStore
    private let events: ServerEvents

    private static let maxPage = 1000
    private static let maxSearch = 100
    private static let maxShowGuesses = 3

    init(store: LocalStore, events: ServerEvents) {
        self.store = store
        self.events = events
    }

    /// From ``rowsAPILevel`` Home comes arranged: its rows in the person's
    /// order (`rows`), the fixed arrays empty. `rows` is left out when there
    /// are none (every row hidden, or nothing scanned): that is still an
    /// arranged Home. A reply with fixed rows and no `rows` keeps them, as
    /// older servers' do.
    func home() async throws -> HomeData {
        let arranged = (store.current?.server.apiLevel ?? 0) >= rowsAPILevel
        var home = try await store.call { try await $0.getHome(query: .init(arranged: arranged ? true : nil)).ok.body.json }.model
        if !arranged {
            home.rows = nil
        } else if home.rows == nil && home.continueWatching.isEmpty && home.recentlyAddedShows.isEmpty && home.recentlyAired.isEmpty
                    && home.recentlyAddedMovies.isEmpty && home.recentlyReleased.isEmpty {
            home.rows = []
        }
        return home
    }

    func libraries() async throws -> [Library] {
        try await store.call { try await $0.listLibraries().ok.body.json }.items
            .filter { $0._type == .movie || $0._type == .tv }.map(\.model)
    }

    func items(_ query: ItemQuery) async throws -> ItemPage {
        let parameters = Operations.ListLibraryItems.Input.Query(
            sort: query.sort.api,
            filter: query.filter.api,
            genre: Mapping.nonBlank(query.genre),
            decade: query.decade.map(Int64.init),
            q: Mapping.nonBlank(query.text?.trimmingCharacters(in: .whitespacesAndNewlines)),
            limit: Int64(min(max(query.limit, 1), Self.maxPage)),
            offset: Int64(max(query.offset, 0))
        )
        let id = Int64(query.libraryID)
        return try await store.call { try await $0.listLibraryItems(path: .init(id: id), query: parameters).ok.body.json }.model
    }

    func show(id: Int) async throws -> ShowDetail {
        try await store.call { try await $0.getShow(path: .init(id: Int64(id))).ok.body.json }.model
    }

    func season(showID: Int, number: Int) async throws -> SeasonDetail {
        try await store.call { try await $0.getSeason(path: .init(id: Int64(showID), number: Int64(number))).ok.body.json }.model
    }

    func movie(id: Int) async throws -> MovieDetail {
        try await store.call { try await $0.getMovie(path: .init(id: Int64(id))).ok.body.json }.model
    }

    func search(_ text: String) async throws -> SearchResults {
        let q = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxSearch))
        guard !q.isEmpty else { return SearchResults() }
        let r = try await store.call { try await $0.search(query: .init(q: q)).ok.body.json }
        return SearchResults(movies: r.movies.map(\.browseItem), shows: r.shows.map(\.browseItem), episodes: r.episodes.map(\.card))
    }

    /// Each mark tells every listener what changed, and the server's echo of
    /// it is dropped (``ServerEvents/expect(_:)``), so every screen showing it
    /// loads once.
    func setWatched(_ kind: WatchKind, id: Int, watched: Bool) async throws {
        let echo = events.expect(.watched(kind, ids: [id]))
        let id64 = Int64(id)
        do {
            if watched {
                let type: Operations.MarkWatched.Input.Path._TypePayload = kind == .movie ? .movie : .episode
                _ = try await store.call { try await $0.markWatched(path: .init(_type: type, id: id64)).noContent }
            } else {
                let type: Operations.MarkUnwatched.Input.Path._TypePayload = kind == .movie ? .movie : .episode
                _ = try await store.call { try await $0.markUnwatched(path: .init(_type: type, id: id64)).noContent }
            }
        } catch {
            events.forget(echo)
            throw error
        }
        events.notify(.watched(kind, ids: [id], watched: watched))
    }

    func setSeasonWatched(showID: Int, season: Int, watched: Bool) async throws {
        let echo = events.expect(.episodes)
        do {
            try await markSeason(showID: showID, season: season, watched: watched)
        } catch {
            events.forget(echo)
            throw error
        }
        events.notify(.markedShow(id: showID, season: season, watched: watched))
    }

    /// One call for the whole show; a server without it gets one per season.
    func setShowWatched(showID: Int, watched: Bool) async throws {
        let id = Int64(showID)
        let echo = events.expect(.episodes)
        do {
            if watched {
                _ = try await store.call { try await $0.markShowWatched(path: .init(id: id)).noContent }
            } else {
                _ = try await store.call { try await $0.markShowUnwatched(path: .init(id: id)).noContent }
            }
        } catch let error as APIError where error.status == 404 || error.status == 405 {
            do {
                for season in try await show(id: showID).seasons {
                    events.expect(.episodes)
                    try await markSeason(showID: showID, season: season.number, watched: watched)
                }
            } catch {
                events.forget(echo)
                throw error
            }
        } catch {
            events.forget(echo)
            throw error
        }
        events.notify(.markedShow(id: showID, season: nil, watched: watched))
    }

    private func markSeason(showID: Int, season: Int, watched: Bool) async throws {
        let path = Operations.MarkSeasonWatched.Input.Path(id: Int64(showID), number: Int64(season))
        if watched {
            _ = try await store.call { try await $0.markSeasonWatched(path: path).noContent }
        } else {
            _ = try await store.call { try await $0.markSeasonUnwatched(path: .init(id: path.id, number: path.number)).noContent }
        }
    }

    /// The file names its movie, or its episode and show; then the episode is
    /// found in its season. A file the server has not linked gets its show
    /// found by the title guessed from the file name, and the episode by its
    /// file in that season. Failing that, the guess itself.
    func playRequest(fileID: Int) async throws -> PlayRequest {
        let file = try await fileInfo(fileID)
        if let movieID = file.movieId {
            let movie = try await movie(id: Int(movieID))
            return PlayRequest(fileID: fileID, title: movie.title, subtitle: movie.year.map(String.init), backdropPath: movie.backdropPath, posterPath: movie.posterPath)
        }
        if let showID = file.showId, let number = file.seasonNumber {
            let detail = try await season(showID: Int(showID), number: Int(number))
            if let episode = detail.episodes.first(where: { file.episodeId == Int64($0.id) }) {
                return PlayRequest(
                    fileID: fileID, title: detail.showTitle,
                    subtitle: "\(episodeCode(season: episode.season, episode: episode.number)) · \(episode.title)",
                    backdropPath: detail.showBackdropPath ?? episode.stillPath, stillPath: episode.stillPath, posterPath: detail.season.posterPath, showID: Int(showID), seasonNumber: Int(number)
                )
            }
        }
        let guess = file.parsed
        let title = Mapping.nonBlank(guess.title) ?? Mapping.fileName(file.relativePath)
        guard guess.kind == .episode, let season = guess.season.map(Int.init) else { return PlayRequest(fileID: fileID, title: title) }
        if let found = try await findEpisode(fileID: fileID, showTitle: guess.title, season: season) { return found }
        let code = guess.episodes?.first.map { episodeCode(season: season, episode: Int($0)) }
        let subtitle = [code, Mapping.nonBlank(guess.episodeTitle)].compactMap { $0 }.joined(separator: " · ")
        return PlayRequest(fileID: fileID, title: title, subtitle: subtitle.isEmpty ? nil : subtitle)
    }

    private func findEpisode(fileID: Int, showTitle: String, season: Int) async throws -> PlayRequest? {
        guard Mapping.nonBlank(showTitle) != nil else { return nil }
        let shows: [BrowseItem]
        do {
            shows = try await search(showTitle).shows
        } catch is APIError {
            return nil
        }
        for show in shows.prefix(Self.maxShowGuesses) {
            let detail: SeasonDetail
            do {
                detail = try await self.season(showID: show.id, number: season)
            } catch is APIError {
                continue
            }
            guard let episode = detail.episodes.first(where: { $0.versions.contains { $0.fileID == fileID } }) else { continue }
            return PlayRequest(
                fileID: fileID, title: detail.showTitle,
                subtitle: "\(episodeCode(season: episode.season, episode: episode.number)) · \(episode.title)",
                backdropPath: show.backdropPath ?? episode.stillPath, stillPath: episode.stillPath, posterPath: show.posterPath, showID: show.id, seasonNumber: season
            )
        }
        return nil
    }

    /// GET /api/files/{id}: what the server knows about a file.
    private func fileInfo(_ id: Int) async throws -> Components.Schemas.MediaFileDetail {
        try await store.call { try await $0.getFile(path: .init(id: Int64(id))).ok.body.json }
    }

    func imageURL(_ path: String?, size: ImageSize) -> URL? {
        guard let p = Mapping.nonBlank(path?.trimmingCharacters(in: .whitespacesAndNewlines)),
              let base = store.current?.server.baseURL
        else { return nil }
        return URL(string: base.absoluteString + "/api/images/\(size.rawValue)" + (p.hasPrefix("/") ? p : "/" + p))
    }

    func changes() -> AsyncStream<Void> {
        events.changes()
    }

    func libraryChanges() -> AsyncStream<LibraryChange> {
        events.libraryChanges()
    }
}

/// A media file as GET /api/files/{id} describes it.
private extension ItemSort {
    var api: Operations.ListLibraryItems.Input.Query.SortPayload {
        switch self {
        case .title: .title
        case .added: .added
        case .year: .year
        case .rating: .rating
        case .released: .released
        case .runtime: .runtime
        case .aired: .aired
        }
    }
}

private extension ItemFilter {
    /// Nil for All, which sends no filter.
    var api: Operations.ListLibraryItems.Input.Query.FilterPayload? {
        switch self {
        case .all: nil
        case .continuing: .continuing
        case .ended: .ended
        case .fourK: ._4k
        case .hdr: .hdr
        case .unwatched: .unwatched
        }
    }
}
