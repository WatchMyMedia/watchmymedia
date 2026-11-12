package media.watchmy.client.fake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import media.watchmy.client.model.AppServices
import media.watchmy.client.model.CastService
import media.watchmy.client.model.DownloadRepository
import media.watchmy.client.model.RequestBackend
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.LivePlaybackControllerFactory
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.model.PlaybackControllerFactory
import media.watchmy.client.model.PlaybackPlan
import media.watchmy.client.model.TRICKPLAY_API_LEVEL

/**
 * All fakes together. [signedIn] false starts at sign-in. [offline] makes the
 * server unreachable from Home, which then shows the downloads (phones only).
 * [cast] has a TV called Living Room TV connected from the start, which plays
 * Live TV ([FakeLiveCastRemote]) and files (phones only).
 */
class FakeAppServices(signedIn: Boolean = true, tv: Boolean = false, seerr: Boolean = false, offline: Boolean = false, cast: Boolean = false) : AppServices {
    override val servers = FakeServerRepository(signedIn)
    override val auth = FakeAuthRepository(signedIn)
    private val arr = FakeArr()
    private val level = { servers.current.value?.server?.apiLevel ?: 0 }
    override val requests = FakeRequestRepository({ auth.user.value }, arr, backend = if (seerr) RequestBackend.Seerr else RequestBackend.Arr, level = level)
    private val rowsData = FakeRowsData(requestState = requests::requestState)
    override val library = FakeLibraryRepository(arr, level, rowsData) { auth.user.value?.name }.also { it.offline = offline }
    override val rows = FakeRowsRepository(rowsData, level)
    override val live = FakeLiveRepository(level, { auth.user.value }).also { it.offline = offline }
    override val livePlayback = LivePlaybackControllerFactory {
        val server = servers.current.value?.server?.id
        val user = auth.user.value?.id
        FakeLivePlaybackController(
            quality = preferences.quality.value,
            active = combine(servers.current, auth.user) { current, account ->
                current?.server?.id == server && account?.id == user && user != null
            },
        )
    }
    override val preferences = FakePreferencesRepository(servers.current)
    override val playback = object : PlaybackControllerFactory {
        override fun create(): PlaybackController = FakePlaybackController(trickplay = FakeData.trickplay.takeIf { level() >= TRICKPLAY_API_LEVEL })

        override suspend fun plans(fileIds: List<Long>): Map<Long, PlaybackPlan> = fileIds.mapNotNull(FakeData::plan).associateBy { it.fileId }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The phone's downloads, Who can download being admins (Alex is a member). */
    val fakeDownloads = FakeDownloadRepository({ auth.user.value?.isAdmin == true }, scope = scope, stepMs = 1_500).also { d ->
        scope.launch { auth.user.collect { d.refresh() } }
    }
    override val downloads: DownloadRepository = if (tv) DownloadRepository.Unavailable else fakeDownloads

    override val seekThumbnails = FakeSeekThumbnails()
    override val cast: CastService = if (cast && !tv) FakeCastService("Living Room TV", connected = true) else CastService.None
    override val client = ClientInfo(
        deviceId = "fake-device",
        name = if (tv) "Living room TV" else "Sam's Pixel",
        product = if (tv) "WatchMyMedia for Android TV" else "WatchMyMedia for Android",
        platform = if (tv) "androidtv" else "android",
        version = "0.1.0",
        model = if (tv) "Chromecast with Google TV" else "Pixel 9",
    )
}
