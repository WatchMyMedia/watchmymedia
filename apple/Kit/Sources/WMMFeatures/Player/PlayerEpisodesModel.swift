import Foundation
import Observation
import WMMModel

/// The current file's season, independent of orientation and the next-episode prompt.
/// Resolving by file also handles deep links and a season's final episode.
@MainActor
@Observable
public final class PlayerEpisodesModel {
    public private(set) var season: SeasonDetail?
    public private(set) var loading = false
    public private(set) var error: String?
    private let library: any LibraryRepository
    private var revision = 0

    public init(library: any LibraryRepository) { self.library = library }

    public func load(for request: PlayRequest) async {
        revision += 1
        let run = revision
        loading = true
        error = nil
        season = nil
        defer { if revision == run { loading = false } }
        do {
            let resolved = request.showID != nil && request.seasonNumber != nil
                ? request : try await library.playRequest(fileID: request.fileID)
            guard !Task.isCancelled, run == revision,
                  let showID = resolved.showID, let number = resolved.seasonNumber else { return }
            let detail = try await library.season(showID: showID, number: number)
            guard !Task.isCancelled, run == revision else { return }
            season = detail.episodesOnDisk()
        } catch is CancellationError {
        } catch {
            guard !Task.isCancelled, run == revision else { return }
            self.error = error.displayMessage
        }
    }

    public func request(for episode: EpisodeDetail) -> PlayRequest? {
        guard let season else { return nil }
        return episode.playRequest(
            showTitle: season.showTitle, backdropPath: season.showBackdropPath,
            posterPath: season.season.posterPath, showID: season.showID
        )
    }
}
