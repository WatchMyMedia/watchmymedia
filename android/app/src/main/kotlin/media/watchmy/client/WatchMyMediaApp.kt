package media.watchmy.client

import android.app.Application
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Trace
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import media.watchmy.client.newepisodes.NewEpisodeFollower
import media.watchmy.client.newepisodes.NewEpisodeNotifier
import media.watchmy.client.newepisodes.StoredNewEpisodesCursors
import media.watchmy.client.newepisodes.WorkManagerNewEpisodeSchedule
import media.watchmy.client.reminders.LiveReminderAlarms
import media.watchmy.client.reminders.ReminderFollower
import media.watchmy.client.core.cast.CastConnectLive
import media.watchmy.client.core.cast.CastConnectPlay
import media.watchmy.client.core.cast.CastConnectReceiver
import media.watchmy.client.core.cast.RealCastService
import media.watchmy.client.core.cast.LoadData
import media.watchmy.client.core.data.DataServices
import media.watchmy.client.core.data.ReachedServer
import media.watchmy.client.core.downloads.OfflineDownloads
import media.watchmy.client.core.playback.PlaybackEngine
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.model.AppServices
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.CastReceiver
import media.watchmy.client.model.CastService
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.Diagnostics
import media.watchmy.client.model.DownloadRepository
import media.watchmy.client.model.FollowsRepository
import media.watchmy.client.model.WatchlistRepository
import media.watchmy.client.model.LIVE_CAST_API_LEVEL
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LivePlaybackControllerFactory
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.model.PlaybackControllerFactory
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.RequestRepository
import media.watchmy.client.model.RowsRepository
import media.watchmy.client.model.SeekThumbnails
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.ui.CoilSeekThumbnails
import media.watchmy.client.core.playback.watchnext.WatchNextArtSource
import okhttp3.OkHttpClient
import androidx.work.Configuration as WorkConfiguration
import java.time.Instant

class WatchMyMediaApp : Application(), SingletonImageLoader.Factory, OfflineDownloads.Host, WorkConfiguration.Provider, WatchNextArtSource {
    /** True on Android TV and Google TV (the TV UI), false on phones and tablets. */
    val isTv: Boolean by lazy {
        val uiMode = getSystemService(UiModeManager::class.java)
        BuildConfig.FORCE_TV ||
            uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    lateinit var services: AppServices
        private set

    /** The real data layer; null when running on the fakes (`-Pfakes=true`). */
    private var data: DataServices? = null

    /**
     * A Watch Next card's picture for the launcher ([WatchNextArtProvider]): the
     * server's image, through the client that signs in to it, and only while
     * [account] is the one signed in.
     */
    override suspend fun watchNextArt(account: String, path: String): ByteArray? {
        val data = data ?: return null
        if (media.watchmy.client.model.watchNextAccount(data.servers.current.value?.server, data.auth.user.value) != account) return null
        val url = data.library.imageUrl(path, media.watchmy.client.model.ImageSize.W780) ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            data.imageHttpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.bytes() else null }
        }
    }

    /** Whether the local network question (Android 17) is still open; [LocalNetworkGate] answers it. */
    val localNetwork: LocalNetworkQuestion by lazy { allowingStartupReads { LocalNetworkQuestion.of(this) } }

    /** Cast Connect on a TV with a receiver App ID; null elsewhere. */
    var castConnect: CastConnectReceiver? = null
        private set

    /** Live TV reminders, on phones and tablets with the real data layer; null on TVs (a banner) and the fakes. */
    private var reminders: ReminderFollower? = null

    /** New-episode notifications, on phones and tablets with the real data layer; null on TVs (a Home row) and the fakes. */
    private var newEpisodes: NewEpisodeFollower? = null

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** WorkManager's settings, the defaults: it starts on first use, not at every process start (the manifest). */
    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder().build()

    /** The process's first ON_START, when what only someone using the app needs starts. */
    private val firstForeground by lazy { FirstForeground(ProcessLifecycleOwner.get().lifecycle) }

    /** Downloads to watch offline, on phones and tablets with the real data layer; null on TVs and the fakes. */
    override var offlineDownloads: OfflineDownloads? = null
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) StrictModeChecks.install()
        services = if (BuildConfig.FAKE_DATA) FakeAppServices(signedIn = false, tv = isTv, seerr = BuildConfig.FAKE_SEERR, offline = BuildConfig.FAKE_OFFLINE, cast = BuildConfig.FAKE_CAST) else realServices()
    }

    /**
     * The data layer and the player engine. The data layer reads a few
     * hundred bytes of saved state once, synchronously, so the saved server
     * and sign-in are right from the first frame; the player engine has to be
     * built on the main thread. The Cast SDK starts after the first frame of
     * the app's first time in the foreground ([FirstForeground]), not for a
     * process started for a broadcast or a job.
     */
    private fun realServices(): AppServices {
        val data = traced("WMM DataServices") { allowingStartupReads { DataServices.create(this, tv = isTv, version = BuildConfig.VERSION_NAME) } }.also { data = it }
        // The event stream and the start-up checks wait for the app to be in front.
        appScope.launch { ProcessLifecycleOwner.get().lifecycle.currentStateFlow.collect { data.setInFront(it.isAtLeast(Lifecycle.State.STARTED)) } }
        fun account() = media.watchmy.client.model.watchNextAccount(data.servers.current.value?.server, data.auth.user.value)
        // Downloads are for phones and tablets: the TV app never shows them.
        lateinit var engine: PlaybackEngine
        val downloads = if (isTv) {
            null
        } else {
            traced("WMM OfflineDownloads") {
                // The downloads' list is read once, here, on purpose: memory is its truth from then on.
                allowingStartupReads {
                    OfflineDownloads(
                        this, data.offlineGateway, { data.client }, { engine.profiles.current() },
                        media.watchmy.client.model.userAgent(data.client, android.os.Build.VERSION.RELEASE),
                        httpClient = { data.mediaHttpClient },
                    )
                }
            }.also { offlineDownloads = it }
        }
        val library = object : LibraryRepository by data.library {
            override fun imageUrl(path: String?, size: media.watchmy.client.model.ImageSize): String? =
                downloads?.repository?.localArtwork(path) ?: data.library.imageUrl(path, size)
        }
        val playback = traced("WMM PlaybackEngine") {
            PlaybackEngine(
                this, downloads?.playbackGateway(data.playbackGateway) ?: data.playbackGateway, data.preferences, data.client, isTv = isTv,
                library = library, watchNextAccount = account(), playerSources = downloads?.playerSources ?: { it },
                artwork = ArtworkLoader(this), httpClient = { data.mediaHttpClient },
                gatewayOn = data::playbackGatewayOn, inUse = { data.servers.current.value?.server?.id },
            )
        }.also { engine = it }
        data.onSessionChange { playback.setWatchNextAccount(account()) }
        val castable = traced("WMM cast check") { BuildConfig.CAST_APP_ID.isNotEmpty() && RealCastService.isSupported(this) }
        val cast = if (castable && !isTv) {
            traced("WMM RealCastService") { RealCastService(this, data.castGateway, data.servers, data.auth, data.preferences, client = { data.client }) }
                .also { sender -> firstForeground.then(sender::start) }
        } else {
            CastService.None
        }
        val receiver = if (castable && isTv) traced("WMM castConnect") { castConnect(data, playback) }.also { castConnect = it } else CastReceiver.None
        if (!isTv) traced("WMM followReminders") { followReminders(data, ::account) }
        if (!isTv) traced("WMM followNewEpisodes") { followNewEpisodes(data, ::account) }
        return RealAppServices(data, playback, cast, receiver, library = playback.screensLibrary(library), downloads = downloads?.repository ?: DownloadRepository.Unavailable, seekThumbnails = CoilSeekThumbnails(this), livePlayback = playback.live(data.livePlaybackGateway), diagnostics = AndroidDiagnostics(this))
    }

    /** Live TV reminders' alarms on a phone or tablet ([ReminderFollower]). */
    private fun followReminders(data: DataServices, account: () -> String?) {
        val session = combine(data.auth.user, data.servers.current) { user, server -> user to server }
        // The alarms kept, and whose they are, are read now, on purpose: the follower starts from them.
        reminders = allowingStartupReads {
            val alarms = LiveReminderAlarms(this).also {
                it.createChannel()
                // The boot receiver follows the alarms; a version before it was always on.
                it.matchBootReceiver()
            }
            ReminderFollower(alarms, data.live, session, account, appScope)
        }.also { it.start(ProcessLifecycleOwner.get().lifecycle) }
    }

    /**
     * New episodes of the shows the person follows:
     * the check's periodic work follows this device's switch and the
     * permission, for the server in use. Each account signed in here keeps
     * its place in the feed; a sign-in that ends loses it, and takes away
     * what is showing.
     */
    private fun followNewEpisodes(data: DataServices, account: () -> String?) {
        val notifier = NewEpisodeNotifier(this).also { it.createChannel() }
        val session = combine(data.auth.user, data.servers.current) { user, server -> user to server }
        // The cursor is keyed by the server's and the person's ids, so a rename keeps it; links name the account as Watch Next does.
        val cursorAccount = { media.watchmy.client.model.followsAccount(data.servers.current.value?.server, data.auth.user.value) }
        // Every account signed in on this phone keeps its place in the feed; one signed out, or whose server is removed, loses it.
        val signedIn: suspend () -> Set<String> = {
            data.servers.saved.first().mapNotNull { s ->
                s.userId?.takeIf { s.signedIn }?.let { id -> media.watchmy.client.model.followsAccount(s.server, media.watchmy.client.model.User(id, s.userName.orEmpty(), false)) }
            }.toSet()
        }
        val follower = NewEpisodeFollower(
            data.follows, data.libraryContentsChanged, session, account, cursorAccount, signedIn,
            StoredNewEpisodesCursors(this), WorkManagerNewEpisodeSchedule(this), notifier, appScope,
        ).also { newEpisodes = it }
        data.onSessionChange(follower::sessionChanged)
        follower.start(ProcessLifecycleOwner.get().lifecycle)
    }

    /** One check for new episodes, from the periodic work (NewEpisodeWorker). */
    suspend fun checkNewEpisodes() {
        newEpisodes?.check()
    }

    /** Reads the reminders again, which sets the alarms; false when the server couldn't be asked (LiveReminderSync). */
    suspend fun syncReminders(): Boolean = reminders?.sync() ?: true

    /** Plays cast to this TV: from the caster's server, as the caster, through this TV's own players. */
    private fun castConnect(data: DataServices, playback: PlaybackEngine): CastConnectReceiver {
        suspend fun reach(load: LoadData): ReachedServer? {
            // A cast can open the app before the local network question is answered: the server is likely on this network.
            localNetwork.awaitAnswer()
            return data.reachCastServer(load.server.id, load.server.urls)
        }
        fun expires(load: LoadData) = load.tokenExpiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: (System.currentTimeMillis() + 60 * 60_000L)
        return CastConnectReceiver(
            this,
            deviceName = { data.client.name },
            open = { load ->
                reach(load)?.let { server ->
                    val cast = data.castPlayback(server, load.token, expires(load), StreamQuality.byId(load.quality))
                    CastConnectPlay(playback.createFor(cast.gateway, cast.preferences), cast.imageUrl)
                }
            },
            openLive = { load ->
                // A channel needs a server that lets cast tokens play Live TV.
                reach(load)?.takeIf { it.apiLevel >= LIVE_CAST_API_LEVEL }?.let { server ->
                    val cast = data.castLivePlayback(server, load.token, expires(load), StreamQuality.byId(load.live?.quality ?: load.quality))
                    CastConnectLive(playback.liveFor(cast.gateway, cast.preferences), cast.live)
                }
            },
        )
    }

    /**
     * Artwork comes from the signed-in server, so images go through the client
     * that adds the token. Posters, stills and backdrops fade in over their
     * placeholder rather than popping in; those from the memory cache don't fade.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { data?.imageHttpClient ?: OkHttpClient() }))
            }
            .crossfade(CROSSFADE_MS)
            .build()
}

/** How long artwork takes to fade in. */
private const val CROSSFADE_MS = 150

/** Runs [block] as [section] in system traces, which the startup benchmark measures (:baselineprofile). */
private inline fun <T> traced(section: String, block: () -> T): T {
    Trace.beginSection(section)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}

/**
 * [AppServices] over the data layer. [client] is read each time, so a device
 * name changed in Settings shows up in sign-ins and heartbeats straight away.
 * [library] is the data layer's, through the TV's Watch Next row on a TV.
 */
private class RealAppServices(
    private val data: DataServices,
    override val playback: PlaybackControllerFactory,
    override val cast: CastService,
    override val castReceiver: CastReceiver,
    override val library: LibraryRepository,
    override val downloads: DownloadRepository,
    override val seekThumbnails: SeekThumbnails,
    override val livePlayback: LivePlaybackControllerFactory,
    override val diagnostics: Diagnostics,
) : AppServices {
    override val servers: ServerRepository get() = data.servers
    override val auth: AuthRepository get() = data.auth
    override val preferences: PreferencesRepository get() = data.preferences
    override val client: ClientInfo get() = data.client
    override val requests: RequestRepository get() = data.requests
    override val live: LiveRepository get() = data.live
    override val rows: RowsRepository get() = data.rows
    override val follows: FollowsRepository get() = data.follows
    override val watchlists: WatchlistRepository get() = data.watchlists
    override val copyChoices: media.watchmy.client.model.CopyChoices get() = data.copyChoices
}
