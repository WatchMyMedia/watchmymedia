package media.watchmy.client.core.playback

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import media.watchmy.client.core.playback.player.ExoMediaPlayer
import media.watchmy.client.core.playback.player.PlayerOptions
import media.watchmy.client.core.playback.profile.DeviceProfileProvider
import media.watchmy.client.core.playback.session.AndroidMediaSessionHost
import media.watchmy.client.core.playback.watchnext.TvWatchNext
import media.watchmy.client.core.playback.watchnext.WatchNext
import media.watchmy.client.core.playback.watchnext.WatchNextLibrary
import media.watchmy.client.model.AudioPassthrough
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LivePlaybackController
import media.watchmy.client.model.LivePlaybackControllerFactory
import media.watchmy.client.model.LivePlaybackGateway
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.model.PlaybackControllerFactory
import media.watchmy.client.model.PlaybackGateway
import media.watchmy.client.model.PlaybackPlan
import media.watchmy.client.model.PreferencesRepository

/**
 * The app's player: one for the app (build it in `Application.onCreate`,
 * on the main thread) and hand it out as `AppServices.playback`. It holds
 * what outlives a single player screen: the device profile, the Still
 * watching counters, Watch Next (TV only) and the media session host (the
 * notification on phones only). Each [create] is one player screen's
 * [RealPlaybackController] on ExoPlayer; call it on the main thread.
 */
@OptIn(UnstableApi::class)
class PlaybackEngine(
    context: Context,
    private val gateway: PlaybackGateway,
    private val preferences: PreferencesRepository,
    private val client: ClientInfo,
    private val isTv: Boolean = client.platform == "androidtv",
    /** Names bare play links and looks up the next episode; without it the player goes by the decision alone. */
    library: LibraryRepository? = null,
    watchNextAccount: String? = null,
    /** Wraps the players' HTTP data source; the phone app reads downloads through it (PLAN-DOWNLOADS.md §10.4). */
    private val playerSources: (DataSource.Factory) -> DataSource.Factory = { it },
    /** Fetches the notification's and lock screen's pictures signed in to the server (the app's image loader). */
    artwork: BitmapLoader? = null,
) : PlaybackControllerFactory {
    private val app = context.applicationContext
    private val userAgent = "WatchMyMedia/${client.version} (${client.platform}; Android ${Build.VERSION.RELEASE})"

    /**
     * This device's profile, rebuilt when the HDMI output or audio route
     * changes, or Audio passthrough. Made with the first player or plan, on
     * the main thread, rather than at the app's start: it registers for audio
     * and display changes.
     */
    val profiles by lazy {
        DeviceProfileProvider(app, isTv, passthroughAllowed = { preferences.device.value.audioPassthrough != AudioPassthrough.Off })
    }

    private val stillWatching = StillWatching(DeviceClock)
    private val watchNext: WatchNext = if (isTv) TvWatchNext(app, initialAccount = watchNextAccount) else WatchNext.None
    private val sessions = AndroidMediaSessionHost(app, notification = !isTv, artwork = artwork)
    private val lookups: PlaybackLookups = library?.let(::LibraryLookups) ?: PlaybackLookups.None
    private val network = SystemMeteredNetwork(app)
    private val lowRam = app.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true

    override fun create(): PlaybackController = build(gateway, preferences, watchNext.bind(), lookups)

    /** Live streams use their own controller and direct HTTP sources, never the offline file cache. */
    fun live(gateway: LivePlaybackGateway): LivePlaybackControllerFactory = live(gateway, preferences)

    /**
     * A live player on [gateway] with [preferences] for its quality: a channel
     * cast to this TV (Cast Connect), which plays as the person who cast it at
     * the quality they picked. This TV's display and audio settings still apply.
     */
    fun liveFor(gateway: LivePlaybackGateway, preferences: PreferencesRepository): LivePlaybackController = live(gateway, preferences).create()

    private fun live(gateway: LivePlaybackGateway, preferences: PreferencesRepository): LivePlaybackControllerFactory = LivePlaybackControllerFactory {
        RealLivePlaybackController(
            gateway = gateway,
            preferences = preferences,
            client = client,
            playerFactory = { ExoMediaPlayer(app, userAgent, PlayerOptions.of(this.preferences.device.value, isTv, lowRam)) },
            sessions = sessions,
            visibility = ProcessVisibility,
            network = network,
            profile = { profiles.current() },
        )
    }

    fun setWatchNextAccount(account: String?) { (watchNext as? TvWatchNext)?.selectAccount(account) }

    /**
     * [library] as the screens should use it: on a TV, Home's loads and the
     * marks keep the launcher's Watch Next row in step ([WatchNextLibrary]).
     */
    fun screensLibrary(library: LibraryRepository): LibraryRepository =
        if (watchNext is TvWatchNext) WatchNextLibrary(library, watchNext) else library

    /**
     * A player that talks to the server through [gateway] with [preferences]
     * rather than this app's sign-in: a play cast to this TV (Cast Connect),
     * which runs as the person who cast it. It stays out of this TV's Watch
     * Next row and library lookups, which belong to whoever signed the TV in.
     */
    fun createFor(gateway: PlaybackGateway, preferences: PreferencesRepository): PlaybackController =
        build(gateway, preferences, WatchNext.None, PlaybackLookups.None)

    private fun build(gateway: PlaybackGateway, preferences: PreferencesRepository, watchNext: WatchNext, lookups: PlaybackLookups) =
        RealPlaybackController(
            gateway = gateway,
            preferences = preferences,
            client = client,
            profileProvider = profiles,
            // This device's settings, even for a play cast to it, which runs as someone else.
            playerFactory = { ExoMediaPlayer(app, userAgent, PlayerOptions.of(this.preferences.device.value, isTv, lowRam), playerSources) },
            stillWatching = stillWatching,
            watchNext = watchNext,
            sessions = sessions,
            visibility = ProcessVisibility,
            lookups = lookups,
            network = network,
        )

    /** Plans for this device's profile at the default quality for the network in use. */
    override suspend fun plans(fileIds: List<Long>): Map<Long, PlaybackPlan> =
        gateway.plans(profiles.current(), preferences.qualityOn(network), fileIds).associateBy { it.fileId }
}
