import Foundation
import WMMModel
#if canImport(UIKit)
import UIKit
#endif

/// Timings the data layer waits on; tests shorten them.
struct DataTuning: Sendable {
    /// How long one address being looked up may take altogether.
    var probeTimeout: Duration = .seconds(10)
    /// How long a looked-up address may take to connect (and between bytes).
    var probeConnectTimeout: TimeInterval = 4
    /// How long the player looks for the server's address on this network when AirPlay starts.
    var localAddressTimeout: Duration = .seconds(10)
    /// The shortest wait between pairing polls, whatever the server asks.
    var minimumPoll: Duration = .milliseconds(250)
    /// The wait before reconnecting the event stream, by attempt.
    var eventBackoff: @Sendable (Int) -> Duration = ServerEvents.defaultBackoff
    /// How long the event stream stays open after its last listener leaves.
    var eventLinger: Duration = .seconds(5)
    var now: @Sendable () -> Date = { .now }
}

/// The real data layer, made once by the app (android/core/data/.../DataServices.kt):
///
/// ```swift
/// let data = DataServices.live()
/// let services = data.makeAppServices(playback: playbackFactory)
/// ```
///
/// It reads the saved servers and sign-ins at once, so `servers.current` and
/// `auth.user` are right from the first frame. Every repository talks to the
/// server in use; a 401 from it clears the sign-in, so `user` turns nil and
/// calls throw ``SignedOutError``.
@MainActor
public final class DataServices {
    public let servers: any ServerRepository
    public let auth: any AuthRepository
    public let library: any LibraryRepository
    public let requests: any RequestsRepository
    public let liveTV: any LiveTVRepository
    public let preferences: any PreferencesRepository
    public let images: any ImageLoader
    /// The offline routes, as the account that made each download (WMMDownloads).
    public let offline: any OfflineGateway
    public let casting: any CastGateway
    public let rows: any RowsRepository

    let store: LocalStore
    let serverRepository: RealServerRepository
    let authRepository: RealAuthRepository
    let libraryRepository: RealLibraryRepository
    let requestsRepository: RealRequestsRepository
    let preferencesRepository: RealPreferencesRepository
    let events: ServerEvents
    let serverInfo: ServerInfoReader

    /// - Parameters:
    ///   - client: how this device describes itself; the ID is replaced by the
    ///     stored one, and the name is the first device name.
    ///   - session: API calls and the event stream's requests.
    ///   - apiCache: `session`'s HTTP cache, emptied whenever the sign-in in
    ///     use changes (another server or account, or signed out), so one
    ///     account's replies are never on disk under another.
    ///   - network: the device's path, which the event stream follows.
    init(
        storage: any LocalStorage,
        client: ClientInfo,
        userAgent: String,
        session: URLSession,
        probeSession: URLSession? = nil,
        eventSession: URLSession? = nil,
        imageSession: URLSession? = nil,
        discovery: any ServerDiscovery = NoDiscovery(),
        apiCache: URLCache? = nil,
        network: (any NetworkPath)? = nil,
        tuning: DataTuning = DataTuning()
    ) {
        let store = LocalStore(storage: storage, session: session, userAgent: userAgent, client: client)
        self.store = store
        events = ServerEvents(store: store, session: eventSession ?? session, backoff: tuning.eventBackoff, linger: tuning.eventLinger,
                              now: tuning.now, network: network)
        let probeSession = probeSession ?? Self.probeSession(connectTimeout: tuning.probeConnectTimeout, total: tuning.probeTimeout)
        serverInfo = ServerInfoReader(store: store, session: probeSession, timeout: tuning.probeTimeout, now: tuning.now)
        serverRepository = RealServerRepository(
            store: store, discovery: discovery, probeSession: probeSession,
            probeTimeout: tuning.probeTimeout, localAddressTimeout: tuning.localAddressTimeout, now: tuning.now, reader: serverInfo
        )
        authRepository = RealAuthRepository(store: store, minimumPoll: tuning.minimumPoll, now: tuning.now)
        libraryRepository = RealLibraryRepository(store: store, events: events)
        requestsRepository = RealRequestsRepository(store: store, events: events)
        preferencesRepository = RealPreferencesRepository(store: store)
        servers = serverRepository
        auth = authRepository
        library = libraryRepository
        requests = requestsRepository
        liveTV = RealLiveTVRepository(store: store, events: events)
        preferences = preferencesRepository
        offline = RealOfflineGateway(store: store, auth: authRepository)
        casting = RealCastGateway(store: store)
        rows = RealRowsRepository(store: store, events: events)
        images = CachedImageLoader(
            session: imageSession ?? session,
            current: { [weak store] in try? store?.signedIn() }
        )

        // On each sign-in (and at launch, when signed in): check the session is still good and load the
        // user's playback settings. Signed out or another account: the default settings at once.
        store.onSessionChange { [weak self] signedIn in
            apiCache?.removeAllCachedResponses()
            guard let self else { return }
            preferencesRepository.reset()
            if signedIn { Task { await self.loadSession() } }
        }
        // A server that restarted (the event stream came back) may have been upgraded.
        let servers = serverRepository
        events.onReconnect = { [weak servers] in await servers?.refreshCurrent(fresh: true) }
        if store.currentCredential != nil { Task { await loadSession() } }
    }

    /// The API's own HTTP cache under Caches (PLAN-APPLE-REVIEW.md §4.1.6),
    /// rather than the app's shared one: the server's replies are
    /// `private, no-cache` with an ETag, which the system keeps and asks
    /// again with If-None-Match, so an unchanged reply comes back as a 304.
    static func apiCache() -> URLCache {
        let folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
            .appendingPathComponent("media.watchmy.client.api", isDirectory: true)
        return URLCache(memoryCapacity: 4 << 20, diskCapacity: 64 << 20, directory: folder)
    }

    /// The device's storage, the system's sessions and Bonjour.
    public static func live() -> DataServices {
        let storage = DeviceStorage()
        let cache = apiCache()
        let session = URLSession(configuration: {
            let config = URLSessionConfiguration.default
            config.timeoutIntervalForRequest = 30
            config.waitsForConnectivity = false
            config.urlCache = cache
            return config
        }())
        let events = URLSession(configuration: {
            let config = URLSessionConfiguration.default
            // The server pings every 15 s, so silence for longer means the connection is gone.
            config.timeoutIntervalForRequest = 45
            config.timeoutIntervalForResource = 7 * 24 * 3600
            config.requestCachePolicy = .reloadIgnoringLocalCacheData
            return config
        }())
        let client = ClientIdentity.client(deviceID: storage.deviceID(), name: ClientIdentity.defaultName)
        let data = DataServices(
            storage: storage, client: client, userAgent: ClientIdentity.userAgent(), session: session,
            eventSession: events, imageSession: CachedImageLoader.makeSession(), discovery: BonjourDiscovery(),
            apiCache: cache, network: SystemNetworkPath()
        )
        #if canImport(UIKit)
        data.followTheApp()
        #endif
        return data
    }

    #if canImport(UIKit)
    private var appObservers: [any NSObjectProtocol] = []

    /// Back in the foreground, the event stream connects again at once: the
    /// system may have closed it while the app was away. Once the device is
    /// unlocked after a restart, the sign-ins that couldn't be read are.
    private func followTheApp() {
        let center = NotificationCenter.default
        appObservers.append(center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.events.reconnectNow() }
        })
        appObservers.append(center.addObserver(forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.protectedDataBecameAvailable() }
        })
    }
    #endif

    /// The device was unlocked: a sign-in the launch couldn't read (before
    /// the first unlock) is read again, so this run isn't left signed out.
    func protectedDataBecameAvailable() {
        store.readUnreadableAgain()
    }

    /// How this device describes itself, with the device name as it is now.
    public var client: ClientInfo { store.client }

    /// The current server's API with its sign-in, for the playback calls; a
    /// 401 from it signs out. Throws ``SignedOutError`` when nobody is signed in.
    public func api() throws -> ServerAPI {
        try store.signedIn().api
    }

    /// The current server's API level as last refreshed (at launch and on
    /// foreground), for what the playback calls send; nil with no server.
    public var apiLevel: Int? { servers.current?.server.apiLevel }

    /// The services for the screens, with `playback` from WMMPlayback and `downloads` from WMMDownloads.
    public func makeAppServices(playback: any PlaybackControllerFactory, livePlayback: (any LivePlaybackControllerFactory)? = nil, downloads: any DownloadRepository = UnavailableDownloads()) -> AppServices {
        AppServices(servers: servers, auth: auth, library: library, requests: requests, preferences: preferences, playback: playback, images: images, client: client, liveTV: liveTV, livePlayback: livePlayback, downloads: downloads, casting: casting, rows: rows)
    }

    /// The account and its playback settings, side by side (PLAN-APPLE-REVIEW.md §4.1.4).
    private func loadSession() async {
        async let user: Void = authRepository.refreshUser()
        async let preferences: Void? = try? preferencesRepository.refresh()
        _ = await (user, preferences)
    }

    private static func probeSession(connectTimeout: TimeInterval, total: Duration) -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = connectTimeout
        config.timeoutIntervalForResource = Double(total.components.seconds)
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }
}
