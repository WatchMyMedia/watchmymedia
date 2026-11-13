import Foundation
import WMMAPI
import WMMModel

// The API's types as the app's model (android/core/data/.../Mapping.kt).
// Blank strings are nil, zero years, ratings and runtimes are nil, and the
// track and version wording comes from WMMModel's helpers.

/// Helpers the mapping shares.
enum Mapping {
    /// Nil for nil, "" and whitespace.
    static func nonBlank(_ s: String?) -> String? {
        guard let s, !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return s
    }

    /// A media token as the player and downloads keep it: for the server at
    /// `server` only (``MediaToken/covers(_:)``), and access_token when the
    /// server names no parameter. Playback's, Live TV's and a download's
    /// tokens all come through here.
    static func mediaToken(_ token: String, expiresAt: Date, param: String?, server: URL) -> MediaToken {
        MediaToken(token: token, expiresAt: expiresAt, param: nonBlank(param) ?? "access_token", server: server)
    }

    /// Nil for nil and anything not above zero.
    static func positive(_ n: Int64?) -> Int? {
        guard let n, n > 0 else { return nil }
        return Int(n)
    }

    static func positive(_ d: Double?) -> Double? {
        guard let d, d > 0 else { return nil }
        return d
    }

    /// "4K", "1080p", "720p", "SD", or nil for "".
    static func quality<Q: RawRepresentable>(_ value: Q?) -> QualityLabel? where Q.RawValue == String {
        nonBlank(value?.rawValue)
    }

    /// "matroska,webm" → "MKV", "mov,mp4,m4a" → "MP4".
    static func containerName(_ value: String) -> String {
        let names = value.lowercased().split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
        if names.contains("matroska") || names.contains("mkv") { return "MKV" }
        if names.contains("mp4") || names.contains("mov") { return "MP4" }
        if names.contains("mpegts") || names.contains("ts") { return "TS" }
        return (names.first ?? value).uppercased()
    }

    /// The server's HDR value as people know it ("hdr10" → "HDR10", "dolbyvision" → ``dolbyVision``); nil for none.
    static func hdrName<H: RawRepresentable>(_ value: H?) -> String? where H.RawValue == String {
        hdrLabel(value?.rawValue)
    }

    /// The file's name, from its path in the library.
    static func fileName(_ relativePath: String) -> String {
        relativePath.split(separator: "/").last.map(String.init) ?? relativePath
    }

    static func skipMode(_ value: String) -> SkipMode? { SkipMode(rawValue: value) }

    static func segmentKind(_ value: String) -> SegmentKind? { SegmentKind(rawValue: value) }

    /// Unsupported single-item replies fail locally; lists can keep their known members.
    static func require<T>(_ value: T?) throws -> T {
        guard let value else { throw APIError(status: nil, message: "This item needs a newer version of WatchMyMedia.") }
        return value
    }
}

extension Components.Schemas.User {
    var model: User { User(id: Int(id), name: name, isAdmin: isAdmin) }
}

extension Components.Schemas.BrowseItem {
    private var itemKind: ItemKind? {
        switch kind {
        case .movie: .movie
        case .show: .show
        case .unmatched: .unmatched
        case .undocumented: nil
        }
    }

    var model: BrowseItem? {
        guard let itemKind else { return nil }
        return BrowseItem(
            kind: itemKind,
            id: Int(id),
            libraryID: Int(libraryId),
            title: title,
            year: Mapping.positive(year),
            overview: overview,
            genres: genres,
            rating: Mapping.positive(rating),
            posterPath: Mapping.nonBlank(posterPath),
            backdropPath: Mapping.nonBlank(backdropPath),
            quality: Mapping.quality(quality),
            hdr: hdr,
            isNew: isNew,
            subtitle: Mapping.nonBlank(subtitle),
            watched: watched,
            progress: Mapping.positive(progress),
            episodeCount: episodeCount.map(Int.init),
            runtimeMin: Mapping.positive(runtimeMin),
            releaseDate: Mapping.nonBlank(releaseDate),
            fileName: Mapping.nonBlank(fileName),
            fileCount: fileCount.map(Int.init),
            episodeID: Mapping.positive(episodeId),
            seasonNumber: episodeId == nil ? nil : seasonNumber.map(Int.init)
        )
    }
}

extension Components.Schemas.Movie {
    var browseItem: BrowseItem {
        BrowseItem(
            kind: .movie, id: Int(id), libraryID: Int(libraryId), title: title, year: Mapping.positive(year),
            overview: overview, genres: genres, rating: Mapping.positive(rating),
            posterPath: Mapping.nonBlank(posterPath), backdropPath: Mapping.nonBlank(backdropPath),
            runtimeMin: Mapping.positive(runtimeMin), releaseDate: Mapping.nonBlank(releaseDate), fileCount: Int(fileCount)
        )
    }
}

extension Components.Schemas.Show {
    var browseItem: BrowseItem {
        BrowseItem(
            kind: .show, id: Int(id), libraryID: Int(libraryId), title: title, year: Mapping.positive(year),
            overview: overview, genres: genres, rating: Mapping.positive(rating),
            posterPath: Mapping.nonBlank(posterPath), backdropPath: Mapping.nonBlank(backdropPath),
            episodeCount: Int(episodeCount)
        )
    }
}

extension Components.Schemas.EpisodeCard {
    var model: EpisodeCard {
        EpisodeCard(
            episodeID: Int(episodeId),
            showID: Int(showId),
            showTitle: showTitle,
            season: Int(seasonNumber),
            episode: Int(episodeNumber),
            title: title,
            overview: overview,
            airDate: Mapping.nonBlank(airDate),
            stillPath: Mapping.nonBlank(stillPath),
            showPosterPath: Mapping.nonBlank(showPosterPath),
            showBackdropPath: Mapping.nonBlank(showBackdropPath),
            hasFile: hasFile,
            fileID: fileId.map(Int.init),
            isNew: isNew,
            watched: watched == true
        )
    }
}

extension Components.Schemas.SearchEpisode {
    /// A search hit is always on disk.
    var card: EpisodeCard {
        EpisodeCard(
            episodeID: Int(id), showID: Int(showId), showTitle: showTitle, season: Int(seasonNumber), episode: Int(number),
            title: title, airDate: Mapping.nonBlank(airDate), stillPath: Mapping.nonBlank(stillPath),
            showPosterPath: Mapping.nonBlank(showPosterPath), hasFile: true, fileID: Int(fileId), isNew: false,
            watched: watched == true
        )
    }
}

extension Components.Schemas.ContinueItem {
    var model: ContinueItem? {
        guard kind == .movie || kind == .episode else { return nil }
        return ContinueItem(
            kind: kind == .movie ? .movie : .episode,
            title: title,
            subtitle: Mapping.nonBlank(subtitle),
            posterPath: Mapping.nonBlank(posterPath),
            backdropPath: Mapping.nonBlank(backdropPath),
            stillPath: Mapping.nonBlank(stillPath),
            fileID: Int(fileId),
            movieID: movieId.map(Int.init),
            showID: showId.map(Int.init),
            episodeID: episodeId.map(Int.init),
            season: seasonNumber.map(Int.init),
            episode: episodeNumber.map(Int.init),
            positionMs: Int(positionMs),
            durationMs: Int(durationMs)
        )
    }
}

extension Components.Schemas.HomeHero {
    private var heroKind: Hero.Kind? {
        switch kind {
        case .episode: .episode
        case .movie: .movie
        case .show: .show
        case .undocumented: nil
        }
    }

    var model: Hero? {
        guard let heroKind else { return nil }
        return Hero(
            kind: heroKind,
            kicker: kicker,
            title: title,
            facts: facts,
            overview: overview,
            backdropPath: Mapping.nonBlank(backdropPath),
            posterPath: Mapping.nonBlank(posterPath),
            showID: showId.map(Int.init),
            movieID: movieId.map(Int.init),
            episodeID: episodeId.map(Int.init),
            fileID: fileId.map(Int.init),
            contentRating: Mapping.nonBlank(contentRating),
            quality: Mapping.quality(quality),
            hdr: hdr == true
        )
    }
}

extension Components.Schemas.HomeData {
    var model: HomeData {
        HomeData(
            hero: hero.flatMap(\.model),
            continueWatching: continueWatching.compactMap(\.model),
            recentlyAddedShows: recentlyAddedShows.compactMap(\.model),
            recentlyAired: recentlyAired.map(\.model),
            recentlyAddedMovies: recentlyAddedMovies.compactMap(\.model),
            recentlyReleased: recentlyReleased.compactMap(\.model),
            totals: LibraryTotals(movies: Int(totals.movies), shows: Int(totals.shows), episodes: Int(totals.episodes), files: Int(totals.files), totalSizeBytes: totals.totalSize),
            lastScannedAt: lastScannedAt,
            movieLibraryID: movieLibraryId.map(Int.init),
            tvLibraryID: tvLibraryId.map(Int.init),
            rows: rows.map { $0.compactMap(\.model) }
        )
    }
}

extension Components.Schemas.ItemTotals {
    var model: LibrarySummary {
        LibrarySummary(items: Int(items), episodes: Int(episodes), continuing: Int(continuing), unmatched: Int(unmatched), totalSizeBytes: totalSize)
    }
}

extension Components.Schemas.ItemList {
    var model: ItemPage {
        ItemPage(
            items: items.compactMap(\.model),
            total: Int(total),
            offset: Int(offset),
            genres: facets.genres,
            decades: facets.decades.map(Int.init),
            summary: totals?.model,
            letters: letters?.map { LetterOffset(letter: $0.letter, offset: Int($0.offset)) },
            genreCounts: genreCounts?.additionalProperties.mapValues(Int.init)
        )
    }
}

extension Components.Schemas.Library {
    var model: Library {
        Library(id: Int(id), name: name, type: _type == .movie ? .movie : .tv,
                scanning: scan != nil, lastScannedAt: lastScannedAt, lastScanError: Mapping.nonBlank(lastScanError))
    }
}

extension Components.Schemas.Credit {
    var model: Person { Person(name: name, role: Mapping.nonBlank(character), profilePath: Mapping.nonBlank(profilePath)) }
}

extension Components.Schemas.MediaToken {
    /// The token for the server at `server`, which it goes to only.
    func model(server: URL) -> MediaToken {
        Mapping.mediaToken(token, expiresAt: expiresAt, param: param, server: server)
    }
}

extension Components.Schemas.OfflineToken {
    /// A download's token for its owner's server at `server`, which it goes to only.
    func model(server: URL) -> MediaToken {
        Mapping.mediaToken(token, expiresAt: expiresAt, param: param, server: server)
    }
}

extension Components.Schemas.WatchState {
    var model: WatchProgress { WatchProgress(positionMs: Int(positionMs), durationMs: Int(durationMs), completed: completed) }
}

/// A season as the show's list and the season's own page both send it.
protocol SeasonFields {
    var id: Int64 { get }
    var number: Int64 { get }
    var name: String { get }
    var overview: String { get }
    var airDate: String? { get }
    var posterPath: String? { get }
    var episodeCount: Int64 { get }
    var availableCount: Int64 { get }
    var watchedCount: Int64 { get }
    var monitored: Bool? { get }
    var request: Components.Schemas.RequestState? { get }
}

extension SeasonFields {
    /// The season; nil when its request is in a state this app doesn't know.
    var summary: SeasonSummary? {
        guard request == nil || request?.model != nil else { return nil }
        return SeasonSummary(
            id: Int(id), number: Int(number), name: name, overview: overview, airDate: Mapping.nonBlank(airDate),
            posterPath: Mapping.nonBlank(posterPath), episodeCount: Int(episodeCount), availableCount: Int(availableCount),
            watchedCount: Int(watchedCount), monitored: monitored, request: request.flatMap(\.model)
        )
    }
}

extension Components.Schemas.Season: SeasonFields {
    var model: SeasonSummary? { summary }
}

extension Components.Schemas.SeasonDetail: SeasonFields {}

extension Components.Schemas.ShowDetail {
    var model: ShowDetail {
        ShowDetail(
            id: Int(id),
            libraryID: Int(libraryId),
            title: title,
            year: Mapping.positive(year),
            overview: overview,
            status: Mapping.nonBlank(status),
            genres: genres,
            rating: Mapping.positive(rating),
            posterPath: Mapping.nonBlank(posterPath),
            backdropPath: Mapping.nonBlank(backdropPath),
            quality: Mapping.quality(quality),
            hdr: hdr,
            seasons: seasons.compactMap(\.model),
            totalEpisodes: Int(totalEpisodes),
            episodesOnDisk: Int(episodeCount),
            totalSizeBytes: totalSize,
            nextAirDate: Mapping.nonBlank(nextAirDate),
            nextUp: nextEpisode.map { NextUp(episodeID: Int($0.episodeId), season: Int($0.seasonNumber), episode: Int($0.episodeNumber), title: $0.title, fileID: Int($0.fileId)) },
            cast: credits.map(\.model),
            contentRating: Mapping.nonBlank(contentRating),
            tmdbID: Mapping.positive(tmdbId),
            managedBy: Mapping.nonBlank(managedBy)
        )
    }
}

extension Components.Schemas.SeasonDetail {
    var model: SeasonDetail {
        get throws {
            guard let season = summary else {
                throw APIError(status: nil, message: "This season needs a newer version of WatchMyMedia.")
            }
            return SeasonDetail(
                showID: Int(showId),
                showTitle: show.title,
                season: season,
                episodes: episodes.map { $0.model(season: Int(number)) },
                showBackdropPath: Mapping.nonBlank(show.backdropPath)
            )
        }
    }
}

extension Components.Schemas.Episode {
    func model(season: Int) -> EpisodeDetail {
        EpisodeDetail(
            id: Int(id),
            season: season,
            number: Int(number),
            title: title,
            overview: overview,
            airDate: Mapping.nonBlank(airDate),
            stillPath: Mapping.nonBlank(stillPath),
            runtimeMin: Mapping.positive(runtimeMin),
            rating: Mapping.positive(rating),
            hasFile: hasFile,
            aired: aired,
            isNew: isNew,
            versions: files.map(\.version),
            watch: watch?.model,
            monitored: monitored,
            download: download.flatMap(\.model),
            request: request.flatMap(\.model)
        )
    }
}

extension Components.Schemas.MovieDetail {
    var model: MovieDetail {
        MovieDetail(
            id: Int(id),
            libraryID: Int(libraryId),
            title: title,
            year: Mapping.positive(year),
            tagline: Mapping.nonBlank(tagline),
            overview: overview,
            releaseDate: Mapping.nonBlank(releaseDate),
            runtimeMin: Mapping.positive(runtimeMin),
            genres: genres,
            rating: Mapping.positive(rating),
            posterPath: Mapping.nonBlank(posterPath),
            backdropPath: Mapping.nonBlank(backdropPath),
            quality: Mapping.quality(quality),
            hdr: hdr,
            versions: files.map(\.version),
            cast: credits.map(\.model),
            watch: watch?.model,
            contentRating: Mapping.nonBlank(contentRating),
            managedBy: Mapping.nonBlank(managedBy),
            download: download.flatMap(\.model),
            trailers: (trailers ?? []).compactMap(\.model)
        )
    }
}

extension Components.Schemas.MovieTrailer {
    /// Nil for a source this app doesn't know, or one it can't play.
    var model: MovieTrailer? {
        MovieTrailer(source: source, name: name, fileID: fileId.map(Int.init), key: key, durationMs: durationMs.map(Int.init))
    }
}

extension Components.Schemas.MediaFile {
    /// An episode's file: the listing has no stream details, so the audio line is the codec alone.
    var version: MediaVersion {
        MediaVersion(
            fileID: Int(id),
            label: Mapping.fileName(relativePath),
            sizeBytes: size,
            container: Mapping.nonBlank(container).map(Mapping.containerName),
            videoCodec: Mapping.nonBlank(videoCodec),
            width: width.map(Int.init),
            height: height.map(Int.init),
            hdr: Mapping.hdrName(hdr),
            audioSummary: Mapping.nonBlank(audioCodec).map(codecName),
            durationMs: durationMs.map(Int.init),
            dvProfile: Mapping.positive(dvProfile), recording: recording?.model
        )
    }
}

extension Components.Schemas.MediaFileDetail {
    /// A movie's file, with its streams from the probe.
    var version: MediaVersion {
        let video = probe?.video.first { $0._default } ?? probe?.video.first
        let audio = probe?.audio.first { $0._default } ?? probe?.audio.first
        return MediaVersion(
            fileID: Int(id),
            label: Mapping.fileName(relativePath),
            sizeBytes: size,
            container: Mapping.nonBlank(container).map(Mapping.containerName),
            videoCodec: Mapping.nonBlank(videoCodec) ?? video?.codec,
            width: width.map(Int.init) ?? video.map { Int($0.width) },
            height: height.map(Int.init) ?? video.map { Int($0.height) },
            hdr: Mapping.hdrName(hdr) ?? Mapping.hdrName(video?.hdr),
            audioSummary: audio?.summary ?? Mapping.nonBlank(audioCodec).map(codecName),
            durationMs: durationMs.map(Int.init) ?? probe.map { Int($0.durationMs) },
            audio: probe?.audio.map(\.option) ?? [],
            subtitles: probe?.subtitles.map(\.option) ?? [],
            dvProfile: Mapping.positive(dvProfile) ?? Mapping.positive(video?.dvProfile), recording: recording?.model
        )
    }
}

extension Components.Schemas.AudioStream {
    /// The stream as the player's audio picker would list it.
    var option: AudioOption {
        AudioOption(
            index: Int(index),
            label: audioTrackLabel(language: language, codec: codec, channels: Int(channels), title: title, isDefault: _default),
            language: Mapping.nonBlank(language),
            isDefault: _default
        )
    }

    /// "TrueHD 7.1 · English".
    var summary: String {
        ["\(codecName(codec)) \(WMMModel.channelLayout(Int(channels)))".trimmingCharacters(in: .whitespaces), languageName(language)]
            .compactMap { $0 }
            .joined(separator: " · ")
    }
}

extension Components.Schemas.SubtitleStream {
    /// The file's own subtitle, with the ID the decision gives it ("s" and the stream index).
    var option: SubtitleOption {
        SubtitleOption(
            id: "s\(index)",
            label: subtitleTrackLabel(language: language, title: title, forced: forced),
            language: Mapping.nonBlank(language),
            forced: forced,
            burnInOnly: image,
            format: subtitleFormatName(codec)
        )
    }
}

extension Components.Schemas.Session {
    var deviceSession: DeviceSession? {
        guard kind == .device || kind == .browser else { return nil }
        return DeviceSession(
            id: id,
            kind: kind == .device ? .device : .browser,
            name: name,
            product: Mapping.nonBlank(product),
            platform: Mapping.nonBlank(platform),
            version: Mapping.nonBlank(version),
            lastIP: Mapping.nonBlank(lastIp),
            createdAt: createdAt,
            lastSeenAt: lastSeenAt,
            current: current,
            userName: userName,
            model: Mapping.nonBlank(model)
        )
    }
}

extension Components.Schemas.PairingInfo {
    var pendingDevice: PendingDevice {
        PendingDevice(
            code: code,
            name: name,
            product: Mapping.nonBlank(product),
            platform: Mapping.nonBlank(platform),
            version: Mapping.nonBlank(version),
            ip: Mapping.nonBlank(ip),
            approved: approved,
            model: Mapping.nonBlank(model),
            expiresAt: expiresAt
        )
    }
}

extension Components.Schemas.Preferences {
    /// The defaults with what the server says on top; kinds and modes this app does not know are left out.
    var model: PlaybackPreferences {
        var skipModes = PlaybackPreferences.defaultSkip
        for (key, value) in skip.additionalProperties {
            if let kind = Mapping.segmentKind(key), let mode = Mapping.skipMode(value) { skipModes[kind] = mode }
        }
        return PlaybackPreferences(skip: skipModes, autoplayNext: autoplayNext, subtitles: subtitles?.model ?? SubtitlePreferences(),
                                   audio: AudioPreferences(languages: audio?.languages ?? []))
    }
}

extension Components.Schemas.SubtitlePreferences {
    /// A mode this app doesn't know starts without subtitles and never
    /// downloads any, which could spend the server's quota; the languages
    /// stay, so a change saved here doesn't clear them.
    var model: SubtitlePreferences {
        guard let mode = SubtitleMode(rawValue: mode) else { return SubtitlePreferences(languages: languages, mode: .off) }
        return SubtitlePreferences(languages: languages, mode: mode, foreignAudio: foreignAudio ?? false, automaticDownload: automaticDownload)
    }
}

extension Components.Schemas.LiveTVProvenance {
    var model: RecordingProvenance {
        .init(channel: channel, number: number, startedAt: startedAt,
              gaps: gaps.map { .init(start: $0.start, end: $0.end, reason: $0.reason) }, ruleID: ruleId, adBreaks: adBreaks)
    }
}
