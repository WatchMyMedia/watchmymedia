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
import media.watchmy.client.model.RELATED_API_LEVEL
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
import media.watchmy.client.model.LibraryChange
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.PairingCode
import media.watchmy.client.model.PairingState
import media.watchmy.client.model.PASSWORD_LENGTH
import media.watchmy.client.model.PendingDevice
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackPreferences
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.SearchResults
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.Server
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.ServerSwitch
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.FOLLOWS_API_LEVEL
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
const val FAKE_SERVER_API_LEVEL = 30

/** The asset URL of one of the fakes' fictional backdrops ([FakeData.backdrop]); null for any other path. */
fun fakeArtUrl(path: String?): String? =
    path?.takeIf { it.startsWith(FakeData.BACKDROPS) }?.let { "file:///android_asset/backdrops/" + it.removePrefix(FakeData.BACKDROPS) }

val LivingRoom = Server("srv-living", "Living room server", "http://192.168.1.20:27587", "0.9.3", FAKE_SERVER_API_LEVEL)
val OfficeMini = Server("srv-office", "Office Mac mini", "http://office-mini.local:27587", "0.9.1", FAKE_SERVER_API_LEVEL)
/** Open to the internet, so password sign-in needs a browser check: the phone pairs instead. */
val RemoteHome = Server("srv-remote", "home.watchmy.media", "https://home.watchmy.media", "0.9.3", FAKE_SERVER_API_LEVEL, turnstile = true)

/** Registered and signed in, with a small library of its own ([FakeCabin]). */
val Cabin = Server("srv-cabin", "Cabin server", "http://192.168.4.31:27587", "0.9.3", FAKE_SERVER_API_LEVEL)

/** Registered and signed in, and off: it never answers, so a switch to it fails and a search of every server leaves it out. */
val Den = Server("srv-den", "Den server", "http://192.168.4.52:27587", "0.9.1", FAKE_SERVER_API_LEVEL)

/**
 * The fake servers: the Living room server in use, the Cabin server and the
 * Den server registered and signed in (the Den server doesn't answer), a
 * fourth registered and signed out, and the Office Mac mini on the network
 * and not added yet. Each keeps its own sign-in, as the real store does:
 * [signIn] and [signOut] are the fake sign-in's, for the server in use.
 */
class FakeServerRepository(signedIn: Boolean = true) : ServerRepository {
    private data class Entry(val server: Server, val lastUserName: String?, val account: User?, val lastUsedAtMs: Long) {
        fun toSaved() = SavedServer(server, account?.name ?: lastUserName, account != null, lastUsedAtMs, account?.id)
    }

    // Unconfined: the derived flows follow a change at once, without a scope to run in.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** The servers that don't answer: a switch that asks first fails, and so does a search of them. */
    var unreachable: Set<String> = setOf(Den.id)

    override val discovered: Flow<List<DiscoveredServer>> = flowOf(
        listOf(LivingRoom, OfficeMini).map { DiscoveredServer(it.id, it.name, it.baseUrl, it.version, it.apiLevel) },
    )
    private val entries = MutableStateFlow(
        System.currentTimeMillis().let { now ->
            listOf(
                Entry(LivingRoom, "Sam", User(1, "Sam", true).takeIf { signedIn }, now),
                Entry(Cabin, "Sam", User(4, "Sam", false), now - 86_400_000L),
                Entry(Den, "Sam", User(2, "Sam", true), now - 2 * 86_400_000L),
                Entry(RemoteHome, "Sam", null, now - 3 * 86_400_000L),
            )
        },
    )
    private val currentId = MutableStateFlow<String?>(LivingRoom.id)

    private fun saved(list: List<Entry>) = list.sortedByDescending { it.lastUsedAtMs }.map { it.toSaved() }

    private val savedState = entries.map(::saved).stateIn(scope, SharingStarted.Eagerly, saved(entries.value))
    override val saved: Flow<List<SavedServer>> = savedState

    private fun entry(list: List<Entry>, id: String?) = list.firstOrNull { it.server.id == id }

    override val current: StateFlow<SavedServer?> = combine(entries, currentId) { list, id -> entry(list, id)?.toSaved() }
        .stateIn(scope, SharingStarted.Eagerly, entry(entries.value, currentId.value)?.toSaved())

    /** Who is signed in on the server in use: the fake sign-in's `user`. */
    val user: StateFlow<User?> = combine(entries, currentId) { list, id -> entry(list, id)?.account }
        .stateIn(scope, SharingStarted.Eagerly, entry(entries.value, currentId.value)?.account)

    /** Who is signed in on [serverId], whichever server is in use. */
    fun account(serverId: String): User? = entry(entries.value, serverId)?.account

    /** Signs [user] in on the server in use. */
    fun signIn(user: User) {
        val id = currentId.value ?: return
        entries.update { list -> list.map { if (it.server.id == id) it.copy(account = user, lastUserName = user.name) else it } }
    }

    /** Signs out of the server in use, which stays on the list; with another still signed in, that one is in use next. */
    fun signOut() {
        val id = currentId.value ?: return
        val next = entries.value.filter { it.account != null && it.server.id != id }.maxByOrNull { it.lastUsedAtMs }
        entries.update { list -> list.map { if (it.server.id == id) it.copy(account = null) else it } }
        if (next != null) use(next.server.id)
    }

    private fun use(id: String) {
        val now = System.currentTimeMillis()
        entries.update { list -> list.map { if (it.server.id == id) it.copy(lastUsedAtMs = now) else it } }
        currentId.value = id
    }

    override suspend fun probe(address: String): Server {
        latency()
        val a = address.trim()
        if (a.isEmpty() || a.contains(' ')) throw ApiException(null, "That doesn't look like a server address.")
        val found = listOf(LivingRoom, OfficeMini, RemoteHome, Cabin, Den).firstOrNull { a in it.baseUrl } ?: LivingRoom
        if (found.id in unreachable) throw ApiException(null, "Couldn't reach $a. Check the address and that the server is running.")
        return found
    }

    override suspend fun select(server: Server) {
        // Choosing a server again takes in what it says about itself now, as the real one does.
        entries.update { list ->
            if (list.any { it.server.id == server.id }) list.map { if (it.server.id == server.id) it.copy(server = server) else it }
            else list + Entry(server, null, null, 0)
        }
        use(server.id)
    }

    override suspend fun switchTo(serverId: String, ask: Boolean): ServerSwitch {
        val target = entry(entries.value, serverId) ?: return ServerSwitch.Gone
        if (ask) {
            latency()
            if (serverId in unreachable) return ServerSwitch.Unreachable
        }
        if (target.server.tooOld) return ServerSwitch.TooOld
        use(serverId)
        return if (target.account != null) ServerSwitch.Switched else ServerSwitch.NeedsSignIn
    }

    override suspend fun forget(serverId: String) {
        val wasCurrent = currentId.value == serverId
        entries.update { list -> list.filter { it.server.id != serverId } }
        if (!wasCurrent) return
        val next = entries.value.filter { it.account != null }.maxByOrNull { it.lastUsedAtMs }
        if (next != null) use(next.server.id) else currentId.value = null
    }
}

/**
 * The fake sign-in. With [servers] it is the sign-in of whichever fake
 * server is in use, each with its own; alone, one person signed in or not.
 */
class FakeAuthRepository(signedIn: Boolean = true, private val servers: FakeServerRepository? = null) : AuthRepository {
    private val sam = User(1, "Sam", true)
    private val userState = MutableStateFlow(if (signedIn) sam else null)
    override val user: StateFlow<User?> = servers?.user ?: userState.asStateFlow()
    private val name = MutableStateFlow("Living room TV")
    override val deviceName: StateFlow<String> = name.asStateFlow()

    private fun signedIn(user: User) {
        if (servers != null) servers.signIn(user) else userState.value = user
    }

    override suspend fun setDeviceName(name: String) {
        this.name.value = name.trim().ifEmpty { this.name.value }
    }

    override suspend fun signIn(name: String, password: String): User {
        latency()
        if (password != "password") throw ApiException(401, "Wrong name or password.")
        // Alex is a member, as on the request boards; anyone else signs in as an admin.
        return sam.copy(name = name, isAdmin = !name.trim().equals("Alex", ignoreCase = true)).also(::signedIn)
    }

    /** Shows the code for a few seconds, then signs in, so the TV flow can be seen end to end. */
    override fun pair(): Flow<PairingState> = flow {
        emit(PairingState.Starting)
        latency()
        val code = PairingCode("KQ7F2W", System.currentTimeMillis() + 10 * 60_000L, "http://192.168.1.20:27587/link?code=KQ7F2W", 2_000)
        emit(PairingState.Waiting(code))
        delay(8_000)
        signedIn(sam)
        emit(PairingState.Approved(sam))
    }

    override suspend fun signOut() {
        if (servers != null) servers.signOut() else userState.value = null
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

    /** "password" is the current password, as for signing in; the other devices are signed out. */
    override suspend fun changePassword(current: String, new: String) {
        latency()
        if (current != "password") throw ApiException(401, "Current password is wrong.")
        if (new.length !in PASSWORD_LENGTH) throw ApiException(422, "Expected length >= 8.")
        sessionList.value = sessionList.value.filter { it.current }
    }

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
    /** The person follows the show (Notify me), from [FOLLOWS_API_LEVEL]; null leaves `following` out, as an older server does. */
    private val following: ((showId: Long) -> Boolean)? = null,
    /** The fake servers, each with a library of its own; null serves the Living room server's alone. */
    private val servers: FakeServerRepository? = null,
    private val user: () -> String? = { "Sam" },
) : LibraryRepository {
    /** The server can't be reached: Home and the pages fail as with no connection, for the offline Home (-PfakesOffline=true). */
    var offline: Boolean = false

    private fun shelfOf(serverId: String?): FakeShelf = if (serverId == Cabin.id) FakeCabin.shelf else LivingRoomShelf

    /** The library of the server in use. */
    private fun shelf(): FakeShelf = shelfOf(servers?.current?.value?.server?.id)

    private fun unreachable() {
        // A server that doesn't answer, in use all the same (its downloads are watched): as with no connection.
        val away = servers?.let { s -> s.current.value?.server?.id in s.unreachable } == true
        if (offline || away) throw ApiException(null, "Couldn't reach the server. Check the connection and try again.", java.io.IOException("offline"))
    }

    private fun titleKey(title: String): String {
        val lower = title.lowercase(java.util.Locale.ROOT)
        val article = listOf("the ", "a ", "an ").firstOrNull { lower.startsWith(it) }
        return if (article == null) lower else lower.removePrefix(article)
    }

    override suspend fun home(): HomeData {
        latency()
        unreachable()
        return shelf().home(System.currentTimeMillis())
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
        return rows.rows(RowScreens.library(libraryId), shelf().home(System.currentTimeMillis()))
    }

    override suspend fun continueWatching(): List<ContinueItem> = shelf().continueWatching.also { latency(); unreachable() }

    override suspend fun libraries(): List<Library> = listOf(
        Library(FakeData.MOVIE_LIBRARY, "Movies", LibraryType.Movie),
        Library(FakeData.TV_LIBRARY, "TV Shows", LibraryType.Tv),
    )

    override suspend fun items(query: ItemQuery): ItemPage {
        latency()
        val shelf = shelf()
        val all = if (query.libraryId == FakeData.MOVIE_LIBRARY) shelf.movies else shelf.shows
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
            summary = shelf.summary(query.libraryId),
            genreCounts = genreCounts,
            letters = if (query.sort == ItemSort.Title) list.mapIndexed { index, item ->
                val initial = titleKey(item.title).firstOrNull()?.uppercaseChar()
                media.watchmy.client.model.LetterOffset(initial?.takeIf { it in 'A'..'Z' }?.toString() ?: "#", index)
            }.distinctBy { it.letter } else null,
        )
    }

    override suspend fun show(id: Long) = shelf().show(id, arr, user())
        .let { s -> if (level() >= FOLLOWS_API_LEVEL && following != null) s.copy(following = following(s.id)) else s }
        .also { latency(); unreachable() }

    override suspend fun season(showId: Long, number: Int) = shelf().season(showId, number, arr, user()).also { latency(); unreachable() }

    override suspend fun movie(id: Long) = shelf().movie(id).let { if (level() >= TRAILERS_API_LEVEL) it else it.copy(trailers = emptyList()) }.also { latency(); unreachable() }

    /** From [RELATED_API_LEVEL] ([FakeData.movieRelated]); an older fake server has none. */
    override suspend fun movieRelated(id: Long): List<ScreenRow> {
        latency()
        unreachable()
        return if (level() >= RELATED_API_LEVEL) shelf().movieRelated(id) else emptyList()
    }

    override suspend fun showRelated(id: Long): List<ScreenRow> {
        latency()
        unreachable()
        return if (level() >= RELATED_API_LEVEL) shelf().showRelated(id) else emptyList()
    }

    override suspend fun search(text: String): SearchResults {
        latency()
        unreachable()
        return shelf().search(text)
    }

    /**
     * Each fake server answers with its own library, as its own person: one
     * that doesn't answer fails after a moment, and one no one is signed in
     * to refuses.
     */
    override suspend fun searchOn(serverId: String, text: String): SearchResults {
        val all = servers ?: return search(text)
        latency()
        if (serverId in all.unreachable) throw ApiException(null, "Couldn't reach the server. Check the connection and try again.", java.io.IOException("no answer"))
        if (all.account(serverId) == null) throw media.watchmy.client.model.SignedOutException()
        return shelfOf(serverId).search(text)
    }

    override suspend fun setWatched(kind: ContinueItem.Kind, id: Long, watched: Boolean) {
        latency()
        unreachable()
    }

    override suspend fun setSeasonWatched(showId: Long, season: Int, watched: Boolean) = latency()

    override suspend fun playRequest(fileId: Long): PlayRequest = shelf().playRequest(fileId).also { latency() }

    override suspend fun filePage(fileId: Long): DeepLink? = shelf().filePage(fileId).also { latency() }

    /**
     * The fake library has no posters, so cards show their placeholders; the
     * fictional backdrops ([FakeData.backdrop]) are assets of -Pfakes=true
     * builds, which Coil loads from `file:///android_asset/`.
     */
    override fun imageUrl(path: String?, size: ImageSize): String? = fakeArtUrl(path)

    override val changes: Flow<Unit> = emptyFlow()

    override val libraryChanges: Flow<LibraryChange> = emptyFlow()
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

    /** A subtitle mode was saved, which keeps the server from choosing one. */
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
