import Foundation

/// Something that can be marked watched or unwatched.
public enum WatchTarget: Hashable, Sendable {
    case movie(id: Int)
    case episode(id: Int)
    case season(showID: Int, number: Int)
    case show(id: Int)
}

extension LibraryRepository {
    /// Marks `target` watched or unwatched.
    public func mark(_ target: WatchTarget, watched: Bool) async throws {
        switch target {
        case .movie(let id): try await setWatched(.movie, id: id, watched: watched)
        case .episode(let id): try await setWatched(.episode, id: id, watched: watched)
        case .season(let showID, let number): try await setSeasonWatched(showID: showID, season: number, watched: watched)
        case .show(let id): try await setShowWatched(showID: id, watched: watched)
        }
    }
}

extension BrowseItem {
    public var watchTarget: WatchTarget? {
        switch kind {
        case .movie: .movie(id: id)
        case .show: .show(id: id)
        case .unmatched: nil
        }
    }
}

extension BrowseItem {
    /// What tapping an unmatched item plays: its file, when it is a single
    /// file (an unmatched film). Nil for matched items, which open their page,
    /// and for unmatched folders, which need matching in the web app first.
    public var unmatchedPlayRequest: PlayRequest? {
        guard kind == .unmatched, (fileCount ?? 1) == 1 else { return nil }
        return PlayRequest(fileID: id, title: title)
    }
}

extension ContinueItem {
    public var watchTarget: WatchTarget? {
        switch kind {
        case .movie: movieID.map { .movie(id: $0) }
        case .episode: episodeID.map { .episode(id: $0) }
        }
    }

    /// Resumes where the viewer left off.
    public var playRequest: PlayRequest {
        let code: String? = season.flatMap { s in episode.map { episodeCode(season: s, episode: $0) } }
        return PlayRequest(fileID: fileID, title: title, subtitle: subtitle ?? code, backdropPath: backdropPath ?? stillPath, stillPath: stillPath, posterPath: posterPath, showID: showID, seasonNumber: season)
    }
}

extension EpisodeCard {
    /// Nil when the episode is not on disk.
    public var playRequest: PlayRequest? {
        fileID.map {
            PlayRequest(
                fileID: $0, title: showTitle, subtitle: "\(episodeCode(season: season, episode: episode)) · \(title)",
                backdropPath: showBackdropPath ?? stillPath, stillPath: stillPath, posterPath: showPosterPath, showID: showID, seasonNumber: season
            )
        }
    }
}

extension Hero {
    /// Nil when the hero has nothing to play. `episodes` names the episode when the hero is one.
    public func playRequest(episodes: [EpisodeCard] = []) -> PlayRequest? {
        guard let fileID else { return nil }
        let episode = episodeID.flatMap { e in episodes.first { $0.episodeID == e } }
        let subtitle = episode.map { "\(episodeCode(season: $0.season, episode: $0.episode)) · \($0.title)" } ?? (kind == .episode ? facts : nil)
        return PlayRequest(fileID: fileID, title: title, subtitle: subtitle, backdropPath: backdropPath, posterPath: posterPath, showID: episode?.showID, seasonNumber: episode?.season)
    }
}

extension EpisodeDetail {
    /// Plays this episode of `show`: resumes when partway through, unless `fromStart`.
    public func playRequest(showTitle: String, backdropPath: String?, posterPath: String? = nil, fromStart: Bool = false, showID: Int? = nil) -> PlayRequest? {
        guard let fileID else { return nil }
        return PlayRequest(
            fileID: fileID,
            startMs: fromStart ? 0 : nil,
            title: showTitle,
            subtitle: "\(episodeCode(season: season, episode: number)) · \(title)",
            backdropPath: backdropPath ?? stillPath,
            stillPath: stillPath,
            posterPath: posterPath, showID: showID, seasonNumber: season
        )
    }
}

extension MovieDetail {
    /// Plays `version` (the first by default): resumes when partway through, unless `fromStart`.
    public func playRequest(version: MediaVersion? = nil, fromStart: Bool = false) -> PlayRequest? {
        guard let fileID = (version ?? versions.first)?.fileID else { return nil }
        return PlayRequest(fileID: fileID, startMs: fromStart ? 0 : nil, title: title, subtitle: year.map(String.init), backdropPath: backdropPath, posterPath: posterPath)
    }
}
