import Foundation
import WMMModel

/// The app's player: one for the app, handed out as ``AppServices/playback``.
/// It holds what outlives a single player screen: the device profile, the
/// Still watching counters, the clock and the system's Now Playing and audio
/// session. Each ``make()`` is one player screen's ``RealPlaybackController``
/// on AVPlayer (android/core/playback/.../PlaybackEngine.kt).
///
/// Wiring it in the app (`ServicesFactory`), with the data layer's `DataServices` as `data`:
///
/// ```swift
/// let playback = PlaybackEngine(
///     gateway: APIPlaybackGateway(api: { try data.api() }, apiLevel: { data.apiLevel }),
///     preferences: data.preferences,
///     client: { data.client },   // the device name as it is now
///     library: data.library,
///     images: data.images,       // Now Playing's picture, with the sign-in
///     servers: data.servers      // the LAN address for AirPlay
/// )
/// let services = data.makeAppServices(playback: playback)
/// ```
@MainActor
public final class PlaybackEngine: PlaybackControllerFactory {
    private let gateway: any PlaybackGateway
    private let preferences: any PreferencesRepository
    private let client: @MainActor () -> ClientInfo
    private let lookups: any PlaybackLookups
    private let servers: (any ServerRepository)?
    private let clock: any PlaybackClock
    private let stillWatching: StillWatching
    private let system: any PlaybackSystem
    /// This device's profile, with HDR read afresh at every request.
    public let profile: any ProfileProvider

    /// - Parameters:
    ///   - client: this device as the server sees it, read at every request (its name is editable in Settings).
    ///   - profile: what this device plays; ``DeviceProfileProvider`` probes it.
    ///   - library: names bare play links and looks up the next episode; without it the player goes by the decision alone.
    ///   - images: loads Now Playing's picture (with `library`); nil shows none.
    ///   - servers: finds the server's LAN address when AirPlay starts; nil never looks.
    public init(
        gateway: any PlaybackGateway,
        preferences: any PreferencesRepository,
        client: @escaping @MainActor () -> ClientInfo,
        profile: any ProfileProvider = DeviceProfileProvider(),
        library: (any LibraryRepository)? = nil,
        images: (any ImageLoader)? = nil,
        servers: (any ServerRepository)? = nil,
        system: any PlaybackSystem = AppleSystem()
    ) {
        self.gateway = gateway
        self.preferences = preferences
        self.client = client
        self.profile = profile
        lookups = library.map { LibraryLookups(library: $0, images: images) } ?? NoLookups()
        self.servers = servers
        self.system = system
        let clock = SystemPlaybackClock()
        self.clock = clock
        stillWatching = StillWatching(clock: clock)
    }

    public func make() -> any PlaybackController {
        RealPlaybackController(
            gateway: gateway,
            preferences: preferences,
            client: client,
            profile: profile,
            makePlayer: { [gateway] in AVMediaPlayer(userAgent: gateway.userAgent) },
            stillWatching: stillWatching,
            system: system,
            lookups: lookups,
            servers: servers,
            clock: clock
        )
    }

    /// Plans for this device's profile at the chosen quality; empty when the server cannot say.
    public func plans(fileIDs: [Int]) async -> [Int: PlaybackPlan] {
        guard let plans = try? await gateway.plans(profile: profile.current(), quality: preferences.quality, fileIDs: fileIDs) else { return [:] }
        return Dictionary(plans.map { ($0.fileID, $0) }, uniquingKeysWith: { first, _ in first })
    }
}
