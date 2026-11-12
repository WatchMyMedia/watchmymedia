import Foundation
import Observation

/// Loads image bytes for an image URL, with the sign-in the server wants.
public protocol ImageLoader: Sendable {
    func data(for url: URL) async throws -> Data
}

/// Everything the screens use, provided by the app (the real repositories)
/// or by WMMFakes (previews, tests, and `-fakes`). Screens read it from the
/// SwiftUI environment and never reach past these protocols.
@MainActor
@Observable
public final class AppServices {
    public let servers: any ServerRepository
    public let auth: any AuthRepository
    public let library: any LibraryRepository
    /// Requests and problem reports (PLAN-ARR.md); does nothing below API level 5.
    public let requests: any RequestsRepository
    public let liveTV: any LiveTVRepository
    public let livePlayback: (any LivePlaybackControllerFactory)?
    public let preferences: any PreferencesRepository
    public let playback: any PlaybackControllerFactory
    public let images: any ImageLoader
    /// This device as the server sees it; `name` is the name at launch, see ``AuthRepository/deviceName``.
    public let client: ClientInfo
    /// Offline downloads on iPhone and iPad (PLAN-DOWNLOADS.md); ``UnavailableDownloads`` on Apple TV.
    public let downloads: any DownloadRepository
    public let casting: any CastGateway
    /// Arranged rows and lists (PLAN-ROWS.md); ``UnavailableRows`` below ``rowsAPILevel``.
    public let rows: any RowsRepository

    public init(servers: any ServerRepository, auth: any AuthRepository, library: any LibraryRepository, requests: any RequestsRepository, preferences: any PreferencesRepository, playback: any PlaybackControllerFactory, images: any ImageLoader, client: ClientInfo, liveTV: (any LiveTVRepository)? = nil, livePlayback: (any LivePlaybackControllerFactory)? = nil, downloads: any DownloadRepository = UnavailableDownloads(), casting: any CastGateway = UnavailableCastGateway(), rows: (any RowsRepository)? = nil) {
        self.servers = servers
        self.auth = auth
        self.library = library
        self.requests = requests
        self.liveTV = liveTV ?? UnavailableLiveTVRepository()
        self.livePlayback = livePlayback
        self.preferences = preferences
        self.playback = playback
        self.images = images
        self.client = client
        self.downloads = downloads
        self.casting = casting
        self.rows = rows ?? UnavailableRows()
    }
}
