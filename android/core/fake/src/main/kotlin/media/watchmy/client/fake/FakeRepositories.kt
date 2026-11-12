package media.watchmy.client.fake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import media.watchmy.client.model.AUTO_API_LEVEL
import media.watchmy.client.model.TRAILERS_API_LEVEL
import media.watchmy.client.model.ROWS_API_LEVEL
import media.watchmy.client.model.RowScreens
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.AudioPassthrough
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.DevicePlayback
import media.watchmy.client.model.DeviceSession
import media.watchmy.client.model.DiscoveredServer
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.ItemFilter
import media.watchmy.client.model.ItemPage
import media.watchmy.client.model.ItemQuery
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.Library
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.PairingCode
import media.watchmy.client.model.PairingState
import media.watchmy.client.model.PendingDevice
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackPreferences
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.SearchResults
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.Server
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleSize
import media.watchmy.client.model.User

/** Delay before fake responses, so loading states show; 0 in tests. */
var fakeLatencyMs: Long = 350

private suspend fun latency() {
    if (fakeLatencyMs > 0) delay(fakeLatencyMs)
}

/**
 * The API level of the server release the fake servers stand for: the
 * current one, so the harnesses, previews and `-Pfakes=true` show every
 * feature. A fake server at a lower level hides what its level lacks, as a
 * real one does.
 */
const val FAKE_SERVER_API_LEVEL = 22

/** The asset URL of one of the fakes' fictional backdrops ([FakeData.backdrop]); null for any other path. */
fun fakeArtUrl(path: String?): String? =
    path?.takeIf { it.startsWith(FakeData.BACKDROPS) }?.let { "file:///android_asset/backdrops/" + it.removePrefix(FakeData.BACKDROPS) }

val LivingRoom = Server("srv-living", "Living room server", "http://192.168.1.20:27587", "0.9.3", FAKE_SERVER_API_LEVEL)
val OfficeMini = Server("srv-office", "Office Mac mini", "http://office-mini.local:27587", "0.9.1", FAKE_SERVER_API_LEVEL)
/** Open to the internet, so password sign-in needs a browser check: the phone pairs instead. */
val RemoteHome = Server("srv-remote", "home.watchmy.media", "https://home.watchmy.media", "0.9.3", FAKE_SERVER_API_LEVEL, turnstile = true)

class FakeServerRepository(signedIn: Boolean = true) : ServerRepository {
    override val discovered: Flow<List<DiscoveredServer>> = flowOf(
        listOf(LivingRoom, OfficeMini).map { DiscoveredServer(it.id, it.name, it.baseUrl, it.version, it.apiLevel) },
    )
    private val savedState = MutableStateFlow(
        listOf(
            SavedServer(LivingRoom, "Sam", signedIn, System.currentTimeMillis()),
            SavedServer(RemoteHome, "Sam", false, System.currentTimeMillis() - 3 * 86_400_000L),
        ),
    )
    override val saved: Flow<List<SavedServer>> = savedState
    private val currentState = MutableStateFlow<SavedServer?>(savedState.value.first())
    override val current: StateFlow<SavedServer?> = currentState.asStateFlow()

    override suspend fun probe(address: String): Server {
        latency()
        val a = address.trim()
        if (a.isEmpty() || a.contains(' ')) throw ApiException(null, "That doesn't look like a server address.")
        return listOf(LivingRoom, OfficeMini, RemoteHome).firstOrNull { a in it.baseUrl } ?: LivingRoom
    }

    override suspend fun select(server: Server) {
        val existing = savedState.value.firstOrNull { it.server.id == server.id }
        // Choosing a server again takes in what it says about itself now, as the real one does.
        val entry = (existing ?: SavedServer(server, null, false, 0)).copy(server = server, lastUsedAtMs = System.currentTimeMillis())
        savedState.value = listOf(entry) + savedState.value.filter { it.server.id != server.id }
        currentState.value = entry
    }

    override suspend fun forget(serverId: String) {
        savedState.value = savedState.value.filter { it.server.id != serverId }
        if (currentState.value?.server?.id == serverId) currentState.value = savedState.value.firstOrNull()
    }
}

class FakeAuthRepository(signedIn: Boolean = true) : AuthRepository {
    private val sam = User(1, "Sam", true)
    private val userState = MutableStateFlow(if (signedIn) sam else null)
    override val user: StateFlow<User?> = userState.asStateFlow()
    private val name = MutableStateFlow("Living room TV")
    override val deviceName: StateFlow<String> = name.asStateFlow()

    override suspend fun setDeviceName(name: String) {
        this.name.value = name.trim().ifEmpty { this.name.value }
    }

    override suspend fun signIn(name: String, password: String): User {
        latency()
        if (password != "password") throw ApiException(401, "Wrong name or password.")
        // Alex is a member, as on the request boards; anyone else signs in as an admin.
        return sam.copy(name = name, isAdmin = !name.trim().equals("Alex", ignoreCase = true)).also { userState.value = it }
    }

    /** Shows the code for a few seconds, then signs in, so the TV flow can be seen end to end. */
    override fun pair(): Flow<PairingState> = flow {
        emit(PairingState.Starting)
        latency()
        val code = PairingCode("KQ7F2W", System.currentTimeMillis() + 10 * 60_000L, "http://192.168.1.20:27587/link?code=KQ7F2W", 2_000)
        emit(PairingState.Waiting(code))
        delay(8_000)
        userState.value = sam
        emit(PairingState.Approved(sam))
    }

    override suspend fun signOut() {
        userState.value = null
    }

    private val sessionList = MutableStateFlow(
        listOf(
            DeviceSession("a1b2c3d4e5f60718", DeviceSession.Kind.Device, "Living room TV", "WatchMyMedia for Android TV", "androidtv", "1.0.0", "192.168.1.31", now(-20), now(0), true, "Sam", model = "Chromecast with Google TV"),
            DeviceSession("b2c3d4e5f6071829", DeviceSession.Kind.Device, "Sam's Pixel", "WatchMyMedia for Android", "android", "1.0.0", "192.168.1.44", now(-9), now(-1), false, "Sam", model = "Pixel 9"),
            DeviceSession("c3d4e5f60718293a", DeviceSession.Kind.Browser, "Chrome on macOS", null, null, null, "100.101.7.2", now(-3), now(-2), false, "Sam"),
        ),
    )

    override suspend fun sessions(all: Boolean): List<DeviceSession> {
        latency()
        return sessionList.value
    }

    override suspend fun revoke(sessionId: String) {
        latency()
        sessionList.value = sessionList.value.filter { it.id != sessionId }
    }

    override suspend fun lookUpPairing(code: String): PendingDevice {
        latency()
        if (code.filter { it.isLetterOrDigit() }.length != 6) throw ApiException(404, "That code is not waiting to be linked.")
        return PendingDevice(code.uppercase(), "Living room TV", "WatchMyMedia for Android TV", "androidtv", "1.0.0", "192.168.1.31", false, model = "Chromecast with Google TV")
    }

    override suspend fun approvePairing(code: String): PendingDevice = lookUpPairing(code).copy(approved = true)

    private fun now(days: Int) = System.currentTimeMillis() + days * 86_400_000L
}

/**
 * [arr] and [user] give Copperline's missing episodes their requests and
 * Sonarr's states. [level] is the server's API level: below
 * [TRAILERS_API_LEVEL] movies have no trailers, as a real server sends none.
 */
class FakeLibraryRepository(
    private val arr: FakeArr = FakeArr(),
    private val level: () -> Int = { FAKE_SERVER_API_LEVEL },
    /** The layouts and lists the arranged rows come from, shared with [FakeRowsRepository]. */
    private val rows: FakeRowsData = FakeRowsData(),
    private val user: () -> String? = { "Sam" },
) : LibraryRepository {
    /** The server can't be reached: Home and the pages fail as with no connection, for the offline Home (-PfakesOffline=true). */
    var offline: Boolean = false

    private fun unreachable() {
        if (offline) throw ApiException(null, "Couldn't reach the server. Check the connection and try again.", java.io.IOException("offline"))
    }

    private fun titleKey(title: String): String {
        val lower = title.lowercase(java.util.Locale.ROOT)
        val article = listOf("the ", "a ", "an ").firstOrNull { lower.startsWith(it) }
        return if (article == null) lower else lower.removePrefix(article)
    }

    override suspend fun home(): HomeData {
        latency()
        unreachable()
        return FakeData.home()
    }

    /** Home in the person's order from [ROWS_API_LEVEL], with the fixed arrays empty, as the server sends it. */
    override suspend fun arrangedHome(): HomeData {
        val home = home()
        if (level() < ROWS_API_LEVEL) return home
        return home.copy(
            continueWatching = emptyList(), recentlyAddedShows = emptyList(), recentlyAired = emptyList(),
            recentlyAddedMovies = emptyList(), recentlyReleased = emptyList(),
            rows = rows.rows(RowScreens.HOME, home),
        )
    }

    override suspend fun libraryRows(libraryId: Long): List<ScreenRow> {
        latency()
        unreachable()
        if (level() < ROWS_API_LEVEL) return emptyList()
        return rows.rows(RowScreens.library(libraryId), FakeData.home())
    }

    override suspend fun continueWatching(): List<ContinueItem> = FakeData.continueWatching.also { latency(); unreachable() }

    override suspend fun libraries(): List<Library> = listOf(
        Library(FakeData.MOVIE_LIBRARY, "Movies", LibraryType.Movie),
        Library(FakeData.TV_LIBRARY, "TV Shows", LibraryType.Tv),
    )

    override suspend fun items(query: ItemQuery): ItemPage {
        latency()
        val all = if (query.libraryId == FakeData.MOVIE_LIBRARY) FakeData.movies else FakeData.shows
        var list = all.filter { item ->
            when (query.filter) {
                ItemFilter.All -> true
                ItemFilter.Continuing -> item.subtitle?.contains("season") == true && !item.watched
                ItemFilter.Ended -> item.watched
                ItemFilter.FourK -> item.quality == "4K"
                ItemFilter.Hdr -> item.hdr
                ItemFilter.Unwatched -> !item.watched
            }
        }
        query.decade?.let { d -> list = list.filter { (it.year ?: 0) / 10 * 10 == d } }
        query.text?.takeIf { it.isNotBlank() }?.let { t -> list = list.filter { it.title.contains(t, ignoreCase = true) } }
        val genres = all.flatMap { it.genres }.distinct().sorted()
        val genreCounts = genres.associateWith { genre -> list.count { genre in it.genres } }
        query.genre?.let { g -> list = list.filter { g in it.genres } }
        list = when (query.sort) {
            ItemSort.Title -> list.sortedBy { titleKey(it.title) }
            ItemSort.Added -> list
            ItemSort.Year, ItemSort.Released, ItemSort.Aired -> list.sortedByDescending { it.year }
            ItemSort.Rating -> list.sortedByDescending { it.rating }
            ItemSort.Runtime -> list.sortedByDescending { it.runtimeMin }
        }
        return ItemPage(
            items = list.drop(query.offset).take(query.limit), total = list.size, offset = query.offset,
            genres = genres, decades = all.mapNotNull { it.year?.div(10)?.times(10) }.distinct().sortedDescending(),
            summary = FakeData.summary(query.libraryId),
            genreCounts = genreCounts,
            letters = if (query.sort == ItemSort.Title) list.mapIndexed { index, item ->
                val initial = titleKey(item.title).firstOrNull()?.uppercaseChar()
                media.watchmy.client.model.LetterOffset(initial?.takeIf { it in 'A'..'Z' }?.toString() ?: "#", index)
            }.distinctBy { it.letter } else null,
        )
    }

    override suspend fun show(id: Long) = FakeData.show(id, arr, user()).also { latency(); unreachable() }

    override suspend fun season(showId: Long, number: Int) = FakeData.season(showId, number, arr, user()).also { latency(); unreachable() }

    override suspend fun movie(id: Long) = FakeData.movie(id).let { if (level() >= TRAILERS_API_LEVEL) it else it.copy(trailers = emptyList()) }.also { latency(); unreachable() }

    override suspend fun search(text: String): SearchResults {
        latency()
        val t = text.trim()
        if (t.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
        return SearchResults(
            movies = FakeData.movies.filter { it.title.contains(t, true) },
            shows = FakeData.shows.filter { it.title.contains(t, true) },
            episodes = FakeData.recentlyAired(System.currentTimeMillis()).filter { it.title.contains(t, true) || it.showTitle.contains(t, true) },
        )
    }

    override suspend fun setWatched(kind: ContinueItem.Kind, id: Long, watched: Boolean) {
        latency()
        unreachable()
    }

    override suspend fun setSeasonWatched(showId: Long, season: Int, watched: Boolean) = latency()

    override suspend fun playRequest(fileId: Long): PlayRequest = FakeData.playRequest(fileId).also { latency() }

    override suspend fun filePage(fileId: Long): DeepLink? = FakeData.filePage(fileId).also { latency() }

    /**
     * The fake library has no posters, so cards show their placeholders; the
     * fictional backdrops ([FakeData.backdrop]) are assets of -Pfakes=true
     * builds, which Coil loads from `file:///android_asset/`.
     */
    override fun imageUrl(path: String?, size: ImageSize): String? = fakeArtUrl(path)

    override val changes: Flow<Unit> = emptyFlow()
}

/**
 * Playback settings. Automatic quality needs a server at [AUTO_API_LEVEL],
 * as with the real repository: on an older [server] it isn't offered, and
 * a saved Automatic plays at the original. Without a [server], one at
 * [FAKE_SERVER_API_LEVEL].
 */
class FakePreferencesRepository(server: StateFlow<SavedServer?>? = null) : PreferencesRepository {
    // Unconfined: the derived flows follow the server at once, without a scope to run in.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val prefs = MutableStateFlow(PlaybackPreferences())
    override val playback: StateFlow<PlaybackPreferences> = prefs.asStateFlow()
    override val autoQuality: StateFlow<Boolean> = server?.let { s ->
        s.map(::canAuto).stateIn(scope, SharingStarted.Eagerly, canAuto(s.value))
    } ?: MutableStateFlow(true).asStateFlow()
    // A device that never picked one: Automatic where the server does it.
    private val q = MutableStateFlow(StreamQuality.Auto)
    override val quality: StateFlow<StreamQuality> = combine(q, autoQuality, ::shown).stateIn(scope, SharingStarted.Eagerly, shown(q.value, autoQuality.value))

    private fun canAuto(s: SavedServer?) = (s?.server?.apiLevel ?: FAKE_SERVER_API_LEVEL) >= AUTO_API_LEVEL

    private fun shown(saved: StreamQuality, auto: Boolean) = if (saved == StreamQuality.Auto && !auto) StreamQuality.Original else saved

    override suspend fun refresh() = latency()

    override suspend fun setSkip(kind: SegmentKind, mode: SkipMode) {
        prefs.value = prefs.value.copy(skip = prefs.value.skip + (kind to mode))
    }

    override suspend fun setAutoplayNext(enabled: Boolean) {
        prefs.value = prefs.value.copy(autoplayNext = enabled)
    }

    /** A subtitle mode was saved, which keeps the server from choosing one (PLAN-LANGUAGES.md §5.3). */
    private var modeChosen = false

    override suspend fun setSubtitles(value: media.watchmy.client.model.SubtitlePreferences) {
        if (value.choice != prefs.value.subtitles.choice) modeChosen = true
        prefs.value = prefs.value.copy(subtitles = value)
    }

    /** As the server does: the first audio languages of someone who never chose a mode turn on subtitles for foreign audio. */
    override suspend fun setAudioLanguages(languages: List<String>): Boolean {
        val before = prefs.value
        val turnOn = languages.isNotEmpty() && !modeChosen && !before.subtitles.foreignAudio
        if (turnOn) modeChosen = true
        val subtitles = if (turnOn) before.subtitles.copy(foreignAudio = true) else before.subtitles
        prefs.value = before.copy(audio = media.watchmy.client.model.AudioPreferences(languages.toList()), subtitles = subtitles)
        return turnOn && before.subtitles.choice != media.watchmy.client.model.SubtitleChoice.ForeignAudio && subtitles.choice == media.watchmy.client.model.SubtitleChoice.ForeignAudio
    }

    override suspend fun setQuality(quality: StreamQuality) {
        q.value = quality
    }

    private val d = MutableStateFlow(DevicePlayback())
    override val device: StateFlow<DevicePlayback> = combine(d, autoQuality) { saved, auto ->
        saved.copy(mobileQuality = saved.mobileQuality?.let { shown(it, auto) })
    }.stateIn(scope, SharingStarted.Eagerly, d.value)

    override suspend fun setMobileQuality(quality: StreamQuality?) = d.update { it.copy(mobileQuality = quality) }

    override suspend fun setMatchFrameRate(enabled: Boolean) = d.update { it.copy(matchFrameRate = enabled) }

    override suspend fun setAudioPassthrough(mode: AudioPassthrough) = d.update { it.copy(audioPassthrough = mode) }

    override suspend fun setSubtitleSize(size: SubtitleSize) = d.update { it.copy(subtitleSize = size) }
}
