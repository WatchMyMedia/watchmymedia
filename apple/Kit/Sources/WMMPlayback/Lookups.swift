import Foundation
import WMMModel

/// What the player looks up beside the decision, which names neither the
/// file nor the next episode's length: a title for a bare
/// `watchmymedia://play/{fileId}` link, the next episode's runtime and
/// whether it ends its season, and the picture Now Playing shows. Methods
/// may throw; the player carries on without the answer
/// (android/core/playback/.../Lookups.kt).
@MainActor
public protocol PlaybackLookups: Sendable {
    /// `request` named from the server's records; nil leaves it as it is.
    func name(_ request: PlayRequest) async throws -> PlayRequest?

    /// `next` with ``UpNext/runtimeMs`` and ``UpNext/seasonFinale`` filled in.
    func describe(_ next: UpNext) async throws -> UpNext

    /// The image's bytes, loaded with the sign-in; nil when there is no loader.
    func artwork(_ image: ArtworkImage) async throws -> Data?
}

/// No lookups: the player goes by the decision alone.
public struct NoLookups: PlaybackLookups {
    public init() {}

    public func name(_ request: PlayRequest) async throws -> PlayRequest? { nil }

    public func describe(_ next: UpNext) async throws -> UpNext { next }

    public func artwork(_ image: ArtworkImage) async throws -> Data? { nil }
}

/// ``PlaybackLookups`` through the library: ``LibraryRepository/playRequest(fileID:)``,
/// the next episode's season, and images through the app's image loader.
public struct LibraryLookups: PlaybackLookups {
    private let library: any LibraryRepository
    private let images: (any ImageLoader)?

    /// - Parameter images: the signed-in loader the screens use (``AppServices/images``); nil loads no artwork.
    public init(library: any LibraryRepository, images: (any ImageLoader)? = nil) {
        self.library = library
        self.images = images
    }

    public func artwork(_ image: ArtworkImage) async throws -> Data? {
        guard let images, let url = library.imageURL(image.path, size: image.size) else { return nil }
        return try await images.data(for: url)
    }

    public func name(_ request: PlayRequest) async throws -> PlayRequest? {
        let named = try await library.playRequest(fileID: request.fileID)
        return named.title.isEmpty ? nil : named
    }

    public func describe(_ next: UpNext) async throws -> UpNext {
        // Newer servers send both with the decision.
        if next.runtimeMs != nil { return next }
        let season = try await library.season(showID: next.showID, number: next.season)
        let episode = season.episodes.first { $0.id == next.episodeID } ?? season.episodes.first { $0.number == next.episode }
        let runtime = episode?.versions.first { $0.fileID == next.fileID }?.durationMs
            ?? episode?.versions.lazy.compactMap(\.durationMs).first
            ?? episode?.runtimeMin.map { $0 * 60_000 }
        // The season's TMDB size, or its last known episode; specials have no finale.
        let last = max(season.season.episodeCount, season.episodes.map(\.number).max() ?? 0)
        var described = next
        described.runtimeMs = runtime ?? next.runtimeMs
        described.seasonFinale = next.season > 0 && last > 0 && next.episode >= last
        return described
    }
}
