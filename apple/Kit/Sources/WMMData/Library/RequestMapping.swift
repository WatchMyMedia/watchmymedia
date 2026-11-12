import Foundation
import WMMAPI
import WMMModel

// Requests, downloads and problems as the app's model (Mapping.swift's rules:
// blank strings and zero IDs and years are nil).

extension Components.Schemas.QueueDownload {
    var model: QueueDownload? {
        guard let state = QueueDownload.State(rawValue: state.rawValue) else { return nil }
        return QueueDownload(state: state, progress: min(max(progress, 0), 1), finishesAt: finishesAt, message: Mapping.nonBlank(message))
    }
}

extension Components.Schemas.RequestState {
    var model: RequestState? {
        guard let status = RequestStatus(rawValue: status.rawValue) else { return nil }
        return RequestState(
            id: Int(id), status: status, requestedBy: requestedBy, mine: mine,
            seasons: seasons.map(Int.init), note: Mapping.nonBlank(note), createdAt: createdAt
        )
    }
}

extension Components.Schemas.MediaRequest {
    var model: MediaRequest? {
        guard let status = RequestStatus(rawValue: status.rawValue), let type = MediaType(rawValue: mediaType.rawValue) else { return nil }
        return MediaRequest(
            id: Int(id), status: status, requestedBy: requestedBy, mine: mine,
            seasons: seasons.map(Int.init), note: Mapping.nonBlank(note), createdAt: createdAt,
            mediaType: type, tmdbID: Int(tmdbId), title: title, year: Mapping.positive(year),
            posterPath: Mapping.nonBlank(posterPath), download: download.flatMap(\.model),
            movieID: Mapping.positive(movieId), showID: Mapping.positive(showId)
        )
    }
}

extension Components.Schemas.RequestableSeason {
    var model: RequestableSeason? {
        guard let state = RequestableSeason.State(rawValue: state.rawValue) else { return nil }
        return RequestableSeason(
            number: Int(number), name: name, episodeCount: Int(episodeCount), airDate: Mapping.nonBlank(airDate),
            state: state
        )
    }
}

extension Components.Schemas.Requestable {
    var model: Requestable? {
        guard let state = Requestable.State(rawValue: state.rawValue), let type = MediaType(rawValue: mediaType.rawValue),
              request == nil || request?.model != nil else { return nil }
        return Requestable(
            mediaType: type, tmdbID: Int(tmdbId), title: title, year: Mapping.positive(year),
            overview: overview, posterPath: Mapping.nonBlank(posterPath), state: state,
            movieID: Mapping.positive(movieId), showID: Mapping.positive(showId), request: request.flatMap(\.model),
            seasons: (seasons ?? []).compactMap(\.model)
        )
    }
}

extension Components.Schemas.Problem {
    var model: Problem? {
        guard itemType == .movie || itemType == .episode else { return nil }
        return Problem(
            id: Int(id), reportedBy: reportedBy, itemType: itemType == .movie ? .movie : .episode, itemID: Int(itemId),
            fileID: Mapping.positive(fileId), kind: ProblemKind(rawValue: kind.rawValue) ?? .other, note: note, details: details,
            title: title, showID: Mapping.positive(showId), season: season.map(Int.init), createdAt: createdAt, seerrIssueID: Mapping.positive(seerrIssueId)
        )
    }
}

extension Components.Schemas.RequestOptions {
    var model: RequestOptions {
        RequestOptions(
            movies: movies, shows: shows, needsApproval: needsApproval, problems: problems, manage: manage,
            delete: delete ?? false, backend: backend, inbox: Int(inbox)
        )
    }
}

extension Components.Schemas.DeletePreview {
    var model: DeletePreview {
        get throws {
            // A delete confirmation must describe every destination, including its kind.
            guard apps.allSatisfy({ $0.kind == .radarr || $0.kind == .sonarr }) else {
                throw APIError(status: nil, message: "This delete preview needs a newer version of WatchMyMedia.")
            }
            return DeletePreview(
                managed: managed, files: Int(files), sizeBytes: size,
                apps: apps.map {
                    DeleteApp(
                        connection: $0.connection, kind: $0.kind == .radarr ? .radarr : .sonarr, recycleBin: Mapping.nonBlank($0.recycleBin),
                        error: Mapping.nonBlank($0.error), otherEpisodes: ($0.otherEpisodes ?? []).map(Int.init)
                    )
                }, recording: recording ?? false
            )
        }
    }
}

extension Components.Schemas.DeleteResult {
    var model: DeleteOutcome {
        let failed = apps.filter { Mapping.nonBlank($0.error) != nil }
        return DeleteOutcome(
            failures: failed.map { "\($0.connection) couldn’t delete: \($0.error ?? "")" },
            deleted: failed.count < apps.count
        )
    }
}
