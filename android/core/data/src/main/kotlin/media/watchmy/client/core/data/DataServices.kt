package media.watchmy.client.core.data

import media.watchmy.client.model.ServerFeatures
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.CastGateway
import media.watchmy.client.model.FollowsRepository
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.model.LivePlaybackGateway
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.OfflineGateway
import media.watchmy.client.model.PlaybackGateway
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.RequestRepository
import media.watchmy.client.model.RowsRepository
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.StreamQuality
import java.io.File
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The real data layer, made once by the app:
 *
 * ```
 * val data = DataServices.create(context, tv = isTv, version = BuildConfig.VERSION_NAME)
 * ```
 *
 * It reads the saved servers and sign-ins synchronously (a few hundred
 * bytes), so [servers]' `current` and [auth]'s `user` are right from the
 * first frame. Every repository talks to the server in use; a 401 from it
 * clears the sign-in, so `user` turns null and calls throw `SignedOutException`.
 */
class DataServices internal constructor(
    baseClient: ClientInfo,
    dataStore: DataStore<Preferences>,
    /** The shared client, asked for only when a call is first made, off the main thread. */
    httpClient: () -> OkHttpClient,
    /** The API client's HTTP cache; null keeps none. */
    httpCache: Cache? = null,
    network: NetworkMonitor = NetworkMonitor.Always,
    discovery: ServerDiscovery,
    sealer: TokenSealer,
    private val scope: CoroutineScope,
    clock: () -> Long,
    eventBackoffMs: (attempt: Int) -> Long = ServerEvents.BACKOFF,
    /**
     * Whether the app is in front until [setInFront] says otherwise. The app
     * starts not in front and reports its process lifecycle; tests start in
     * front.
     */
    inFront: Boolean = true,
    /** What storage holds, when the caller has just read it; null reads it here. */
    stored: Stored? = null,
) {
    constructor(context: Context, client: ClientInfo) : this(context, client, stored = null)

    private constructor(context: Context, client: ClientInfo, stored: Stored?, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) : this(
        baseClient = client,
        dataStore = dataStore(context),
        httpClient = { sharedHttpClient },
        httpCache = Cache(File(context.cacheDir, "http"), HTTP_CACHE_BYTES),
        network = SystemNetworkMonitor(context, scope),
        discovery = NsdDiscovery(context),
        sealer = KeystoreSealer(),
        scope = scope,
        clock = System::currentTimeMillis,
        inFront = false,
        stored = stored,
    )

    private val store = LocalStore(dataStore, scope, sealer, stored)

    init {
        // A ClientInfo not made by clientInfo() still gets its ID and name kept.
        if (store.value.deviceId == null || store.value.deviceName == null) {
            store.update { s -> s.copy(deviceId = s.deviceId ?: baseClient.deviceId, deviceName = s.deviceName ?: baseClient.name) }
        }
    }

    /** Random, made on first run and kept. */
    val deviceId: String = store.value.deviceId ?: baseClient.deviceId

    private val base = baseClient.copy(deviceId = deviceId)

    /** How this device describes itself, with the name as it is now (it is editable in Settings). */
    val client: ClientInfo get() = base.copy(name = store.deviceName.value.ifBlank { base.name })

    private val http = Http(httpClient, store, "WatchMyMedia/${base.version} (${base.platform}; Android ${Build.VERSION.RELEASE})", httpCache, network)

    init {
        // The clients' TLS set-up, off the main thread, before the first call needs it.
        scope.launch { http.warm() }
    }

    private val events = ServerEvents(store, http, scope, eventBackoffMs, network, inFront, onReconnect = ::reconnected)
    private val serverRepository = RealServerRepository(store, http, discovery, scope, clock)

    /**
     * Whether the app is in front, which the app reports from its process
     * lifecycle: the event stream connects
     * only in front, and the return to the front reconnects it at once. The
     * start-up checks of a saved sign-in also wait for it, so a start in the
     * background (a boot, a download's sync) asks the server nothing.
     */
    fun setInFront(inFront: Boolean) = events.setInFront(inFront)

    /**
     * The event stream reconnected after a gap: the server may have been
     * upgraded meanwhile, and a TV app can stay open for weeks, so its level
     * is read again.
     */
    private fun reconnected() {
        scope.launch { runCatching { serverRepository.refreshCurrent() } }
    }
    private val authRepository = RealAuthRepository(store, http, serverRepository, { client }, clock, scope)
    private val preferencesRepository = RealPreferencesRepository(store, http)

    val servers: ServerRepository = serverRepository
    val auth: AuthRepository = authRepository
    val library: LibraryRepository = RealLibraryRepository(store, http, events, scope, clock, serverRepository::askFirst)
    private val liveRepository = RealLiveRepository(http, store, scope, clock, events)
    val live: LiveRepository = liveRepository
    val livePlaybackGateway: LivePlaybackGateway = RealLivePlaybackGateway(store, http, capabilities = liveRepository::capabilities)
    val requests: RequestRepository = RealRequestRepository(store, http, events, scope, clock)
    val rows: RowsRepository = RealRowsRepository(http, events)
    val follows: FollowsRepository = RealFollowsRepository(store, http, events)

    /** library.changed alone, without the scans that change nothing: what the new-episode check waits for while the app is open. */
    val libraryContentsChanged: kotlinx.coroutines.flow.Flow<Unit> get() = events.contentsChanged
    val preferences: PreferencesRepository = preferencesRepository
    val playbackGateway: PlaybackGateway =
        RealPlaybackGateway(SignedInAccess(http), { store.deviceName.value }, { deviceId }, { RealPlaybackGateway.signedInMediaToken(http) })

    /** A sender's calls for casting to a TV. */
    val castGateway: CastGateway = RealCastGateway(store, http, liveRepository::capabilities)

    /** The offline routes, for downloads on phones and tablets. */
    val offlineGateway: OfflineGateway = RealOfflineGateway(store, http, authRepository)

    /**
     * Cast Connect: which of [urls] (from the sender, best first) reaches the
     * server [serverId] from this TV, or null when none does.
     */
    suspend fun reachCastServer(serverId: String, urls: List<String>): ReachedServer? = reachServer(http, serverId, urls)

    /**
     * Cast Connect: the player's server calls and the caster's preferences on
     * [server] with their cast [token], whoever this TV is signed in as;
     * [quality] is the one the sender picked.
     */
    fun castPlayback(server: ReachedServer, token: String, expiresAtMs: Long, quality: StreamQuality?): CastPlayback {
        val access = CastTokenAccess(server.baseUrl.trimEnd('/'), http, token, expiresAtMs, server.apiLevel)
        val gateway = RealPlaybackGateway(access, { store.deviceName.value }, { deviceId }, access::renew, onlineSubtitles = false)
        val auto = ServerFeatures(server.apiLevel).automaticQuality
        return CastPlayback(gateway, CastPreferences(access, preferencesRepository, quality, auto)) { path -> castImageUrl(access.baseUrl, access.token, path) }
    }

    /**
     * Cast Connect for a Live TV channel: the live
     * player's session calls, and the caster's channels and pictures, on
     * [server] with their cast [token], whoever this TV is signed in as.
     * Cast tokens can't ask for media tokens, so the player's come from
     * renewing the cast token. [quality] is the sender's; a change made here
     * lasts for the play.
     */
    fun castLivePlayback(server: ReachedServer, token: String, expiresAtMs: Long, quality: StreamQuality?): CastLivePlayback {
        val access = CastTokenAccess(server.baseUrl.trimEnd('/'), http, token, expiresAtMs, server.apiLevel)
        val gateway = RealLivePlaybackGateway(store, http, access, mediaTokens = access::renew)
        return CastLivePlayback(gateway, CastLiveRepository(access), CastPreferences(access, preferencesRepository, quality ?: StreamQuality.FullHd, auto = false))
    }

    /** What a cast channel talks to the server with: its session, the caster's channels, and the play's quality. */
    class CastLivePlayback(val gateway: LivePlaybackGateway, val live: LiveRepository, val preferences: PreferencesRepository)

    /**
     * What a cast play talks to the server with, and [imageUrl], the caster's
     * artwork at a TMDB path with their latest cast token, for the TV's player.
     */
    class CastPlayback(val gateway: PlaybackGateway, val preferences: PreferencesRepository, val imageUrl: (path: String?) -> String?)

    /**
     * For the image loader (Coil's `OkHttpNetworkFetcherFactory`): sends a
     * server's bearer token on requests to that server, and only there: the
     * one in use, and any other signed in here whose pictures a search of
     * every server shows. Images
     * have a Dispatcher of their own, so a screenful of posters doesn't queue
     * the API calls behind it (OkHttp runs 5 calls per host at a time); they
     * share the connection pool, not the API's HTTP cache. Made with the
     * first image, not at start.
     */
    val imageHttpClient: OkHttpClient by lazy {
        http.signedClient.newBuilder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = IMAGE_REQUESTS_PER_HOST })
            .build()
    }

    /**
     * For the player and the downloader (Media3's OkHttpDataSource):
     * derived from the API's client, so it
     * shares the connection pool and dispatcher and the first playlist
     * request reuses the API's connection. No token interceptor (media URLs
     * carry their own `access_token`), no reply limit, no HTTP cache;
     * 15 s to connect and 45 s between reads, as the player had, and no
     * timeout on the whole call. Built off the main thread at start; a caller
     * on the main thread can wrap it as `Call.Factory { mediaHttpClient.newCall(it) }`.
     */
    val mediaHttpClient: OkHttpClient get() = http.mediaClient

    /** The latest sign-in's [loadSession]; the next sign-in or sign-out cancels it, so an older one can't answer last. */
    private var sessionLoad: Job? = null
    private val sessionLoadLock = Any()

    init {
        // On each sign-in (and at start, when signed in): check the session is still good, load the
        // user's playback settings and bring the server's API level up to date. Signed out or another
        // account: the default settings at once.
        store.onSessionChange { signedIn ->
            preferencesRepository.reset()
            restartSessionLoad(signedIn)
        }
        if (store.session()?.account != null) {
            // The saved sign-in's checks, once the app is in front; a sign-in meanwhile has made its own.
            val revision = store.revision
            scope.launch {
                events.awaitInFront()
                if (store.revision == revision) restartSessionLoad(true)
            }
        }
    }

    private fun restartSessionLoad(signedIn: Boolean) = synchronized(sessionLoadLock) {
        sessionLoad?.cancel()
        sessionLoad = if (signedIn) scope.launch { loadSession() } else null
    }

    /** Returns once the settings written behind have landed, for tests that write storage themselves. */
    internal suspend fun awaitWrites() = store.awaitWrites()

    /**
     * Returns once the latest sign-in's checks have finished and what they
     * wrote has landed: for tests that open another DataServices on the same
     * storage, which a check finishing late would write over.
     */
    internal suspend fun awaitIdle() {
        synchronized(sessionLoadLock) { sessionLoad }?.join()
        store.awaitWrites()
    }

    /** The current sign-in's token as it is stored (sealed), for tests. */
    internal fun storedToken(): String? = store.session()?.account?.token

    /** Lets OS integrations clear account-specific state synchronously on sign-out or switching. */
    fun onSessionChange(listener: () -> Unit) { store.onSessionChange { listener() } }

    /** The three are independent, so they go together rather than one after another. */
    private suspend fun loadSession() = coroutineScope {
        launch { authRepository.refreshUser() }
        launch { runCatching { preferencesRepository.refresh() } }
        launch { runCatching { serverRepository.refreshCurrent() } }
    }

    companion object {
        /** Posters and stills loading at once from one server. */
        internal const val IMAGE_REQUESTS_PER_HOST = 8

        /** The API replies kept for revalidation: a few hundred pages of library data. */
        private const val HTTP_CACHE_BYTES = 20L shl 20

        @Volatile private var dataStore: DataStore<Preferences>? = null

        /** One DataStore per file per process, shared by [clientInfo] and every DataServices. */
        internal fun dataStore(context: Context): DataStore<Preferences> = dataStore ?: synchronized(this) {
            dataStore ?: PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { context.applicationContext.preferencesDataStoreFile("watchmymedia") },
            ).also { dataStore = it }
        }

        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /**
         * The data layer for this device, with its [ClientInfo] ([clientInfo]),
         * from one synchronous read of storage. The app makes it once, at start.
         */
        fun create(context: Context, tv: Boolean, version: String): DataServices {
            val (client, stored) = readClientInfo(context, tv, version)
            return DataServices(context, client, stored)
        }

        /**
         * This device's [ClientInfo]: the device ID (made on first run and kept)
         * and the name people see in Signed-in devices and Activity (the
         * device's own name at first, editable through `AuthRepository.setDeviceName`).
         * Reads storage synchronously; call it once, at start.
         */
        fun clientInfo(context: Context, tv: Boolean, version: String): ClientInfo = readClientInfo(context, tv, version).first

        /** [clientInfo], and what storage holds once the ID and name are in it. */
        private fun readClientInfo(context: Context, tv: Boolean, version: String): Pair<ClientInfo, Stored> {
            val store = LocalStore(dataStore(context), CoroutineScope(Dispatchers.IO), PlainSealer)
            var stored = store.value
            val deviceId = stored.deviceId ?: newDeviceId()
            val name = stored.deviceName ?: defaultDeviceName(context, tv)
            if (stored.deviceId == null || stored.deviceName == null) {
                // The first run's ID is written before anything uses it, so it outlives a crash.
                stored = runBlocking { store.updateAndSave { it.copy(deviceId = deviceId, deviceName = name) } }
            }
            val client = ClientInfo(
                deviceId = deviceId,
                name = name,
                product = if (tv) "WatchMyMedia for Android TV" else "WatchMyMedia for Android",
                platform = if (tv) "androidtv" else "android",
                version = version,
                model = Build.MODEL?.trim().orEmpty(),
            )
            return client to stored
        }

        internal fun newDeviceId(): String = UUID.randomUUID().toString().replace("-", "")

        /** The name set in the device's settings ("Living room TV"), else the model ("Pixel 9"). */
        internal fun defaultDeviceName(context: Context, tv: Boolean): String {
            val set = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            val model = Build.MODEL?.takeIf { it.isNotBlank() }
            return (set?.takeIf { it.isNotBlank() } ?: model ?: if (tv) "Android TV" else "Android phone").take(RealAuthRepository.MAX_NAME)
        }
    }
}
