package media.watchmy.client.core.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import media.watchmy.client.model.AUTO_API_LEVEL
import media.watchmy.client.model.AudioPassthrough
import media.watchmy.client.model.DevicePlayback
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.Server
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleSize
import media.watchmy.client.model.User

/** An account signed in on a server: who, and the bearer token (sealed by [TokenSealer]). */
@Serializable
internal data class StoredAccount(
    val userId: Long,
    val userName: String,
    val isAdmin: Boolean,
    val sessionId: String? = null,
    val token: String,
)

/** The person's subtitle and audio preferences, kept for offline plays, which choose their tracks on the device. */
@Serializable
internal data class StoredSubtitlePreferences(
    val userId: Long,
    val baseUrl: String,
    val languages: List<String>,
    val mode: String,
    val automaticDownload: Boolean,
    val foreignAudio: Boolean = false,
    val audioLanguages: List<String> = emptyList(),
)

@Serializable
internal data class StoredServer(
    val id: String,
    val name: String,
    val baseUrl: String,
    val version: String,
    val apiLevel: Int,
    val setupRequired: Boolean = false,
    val turnstile: Boolean = false,
    val lastUsedAtMs: Long = 0,
    /** Who signed in here last; kept after signing out for the server list. */
    val lastUserName: String? = null,
    val account: StoredAccount? = null,
    /** Shows whose page lists missing episodes (RequestRepository.showMissing). */
    val showMissing: List<Long> = emptyList(),
    /** Last acknowledged subtitle settings, for a cold offline start by this account/endpoint. */
    val subtitlePreferences: StoredSubtitlePreferences? = null,
) {
    fun toServer() = Server(id, name, baseUrl, version, apiLevel, setupRequired, turnstile)

    fun toSaved() = SavedServer(toServer(), account?.userName ?: lastUserName, account != null, lastUsedAtMs)
}

/** Everything kept on the device. */
internal data class Stored(
    val deviceId: String? = null,
    val deviceName: String? = null,
    val servers: List<StoredServer> = emptyList(),
    val currentId: String? = null,
    val quality: String? = null,
    /** The quality on mobile data, by ID; null for the Default quality. */
    val mobileQuality: String? = null,
    val matchFrameRate: Boolean = false,
    /** [AudioPassthrough] by name; null for Auto. */
    val passthrough: String? = null,
    /** [SubtitleSize] by name; null for Normal. */
    val subtitleSize: String? = null,
) {
    val current: StoredServer? get() = servers.firstOrNull { it.id == currentId }

    fun withServer(id: String, change: (StoredServer) -> StoredServer): Stored =
        copy(servers = servers.map { if (it.id == id) change(it) else it })
}

/** The current server and its sign-in, as one snapshot for a request. */
internal data class Session(val server: StoredServer, val account: StoredAccount?)

/**
 * The device's servers, sign-ins, device ID and name and playback settings,
 * in Preferences DataStore. It is read once, synchronously, when it is made
 * (a few hundred bytes), so the flows start with the right values and the app
 * opens on the right screen; [preloaded] is that read when the caller has just
 * done it. After that memory is the source of truth: every change updates the
 * flows at once and is written behind, in order.
 */
internal class LocalStore(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val sealer: TokenSealer,
    preloaded: Stored? = null,
) {
    private val lock = Any()
    private val writeLock = Mutex()

    /** The writes behind still running, for [awaitWrites]; cancelled with [scope]. */
    private val writes = SupervisorJob(scope.coroutineContext[Job])
    private val state: MutableStateFlow<Stored>

    private val currentState: MutableStateFlow<SavedServer?>
    private val savedState: MutableStateFlow<List<SavedServer>>
    private val userState: MutableStateFlow<User?>
    private val nameState: MutableStateFlow<String>
    private val qualityState: MutableStateFlow<StreamQuality>
    private val autoState: MutableStateFlow<Boolean>
    private val deviceState: MutableStateFlow<DevicePlayback>

    /** Opened tokens by sealed value, so the Keystore is asked once per token. */
    private val opened = HashMap<String, String?>()

    private var sessionKey: String?
    @Volatile var revision: Long = 0
        private set
    private val sessionRevisionState = MutableStateFlow(0L)
    val sessionChanges: StateFlow<Long> = sessionRevisionState.asStateFlow()
    private val sessionListeners = CopyOnWriteArrayList<(signedIn: Boolean) -> Unit>()

    init {
        val loaded = preloaded ?: runBlocking { load() }
        val initial = resealed(loaded)
        state = MutableStateFlow(initial)
        currentState = MutableStateFlow(initial.current?.toSaved())
        savedState = MutableStateFlow(saved(initial))
        userState = MutableStateFlow(user(initial))
        nameState = MutableStateFlow(initial.deviceName.orEmpty())
        qualityState = MutableStateFlow(quality(initial))
        autoState = MutableStateFlow(autoQuality(initial))
        deviceState = MutableStateFlow(device(initial))
        sessionKey = sessionKey(initial)
        if (initial !== loaded) writeBehind()
    }

    /**
     * [s] with the tokens kept unwrapped while the Keystore failed sealed now
     * that it works ([TokenSealer.reseal]); [s] itself when there are none.
     * Done here, before anything reads the sign-in, so the new seal changes
     * no session.
     */
    private fun resealed(s: Stored): Stored {
        var changed = false
        val servers = s.servers.map { server ->
            val account = server.account ?: return@map server
            val sealed = sealer.reseal(account.token) ?: return@map server
            synchronized(opened) { opened[sealed] = sealer.open(account.token) }
            changed = true
            server.copy(account = account.copy(token = sealed))
        }
        return if (changed) s.copy(servers = servers) else s
    }

    val value: Stored get() = state.value
    val current: StateFlow<SavedServer?> = currentState.asStateFlow()
    val saved: StateFlow<List<SavedServer>> = savedState.asStateFlow()
    val user: StateFlow<User?> = userState.asStateFlow()
    val deviceName: StateFlow<String> = nameState.asStateFlow()
    val quality: StateFlow<StreamQuality> = qualityState.asStateFlow()
    val autoQuality: StateFlow<Boolean> = autoState.asStateFlow()
    val device: StateFlow<DevicePlayback> = deviceState.asStateFlow()

    /** The server in use and its sign-in, or null before one is chosen. */
    fun session(): Session? = state.value.current?.let { Session(it, it.account) }

    /** Identity and generation captured under the same lock as an account switch. */
    fun requestSnapshot(): Pair<Session?, Long> = synchronized(lock) { session() to revision }

    /** The bearer token of [account], or null when it cannot be opened (signed out). */
    fun token(account: StoredAccount): String? = synchronized(opened) {
        opened.getOrPut(account.token) { sealer.open(account.token) }
    }

    /**
     * The bearer token of [account] on [serverId], for a request. One that
     * can't be opened (the Keystore lost its key) clears that sign-in, as a
     * rejected token does, so the app shows sign-in rather than failing
     * every call.
     */
    fun tokenForRequest(serverId: String, account: StoredAccount): String? {
        val token = token(account)
        if (token == null) signOut(serverId, account)
        return token
    }

    /**
     * Calls [listener] on the thread that made the change, before the change
     * returns, whenever the sign-in in use changes: another server, another
     * account, a new token, or signed out.
     */
    fun onSessionChange(listener: (signedIn: Boolean) -> Unit) {
        sessionListeners += listener
    }

    fun seal(token: String): String = sealer.seal(token).also { synchronized(opened) { opened[it] = token } }

    /** Changes the state in memory at once and writes it behind. */
    fun update(transform: (Stored) -> Stored): Stored {
        val next = apply(transform)
        writeBehind()
        return next
    }

    private fun writeBehind() {
        scope.launch(writes) { save() }
    }

    /**
     * Returns once every write started behind has landed. For tests that write
     * storage themselves or open another store on it, as an update or a restart
     * would: a write still running lands over theirs.
     */
    suspend fun awaitWrites() {
        while (true) {
            val running = writes.children.toList()
            if (running.isEmpty()) return
            running.joinAll()
        }
    }

    /** Changes the state and returns once it is written. */
    suspend fun updateAndSave(transform: (Stored) -> Stored): Stored {
        val next = apply(transform)
        save()
        return next
    }

    /**
     * A request with [token] for [serverId] got 401: the session was revoked or
     * ran out. Clears that sign-in unless it has already been replaced.
     */
    fun tokenRejected(serverId: String, token: String) {
        update { s ->
            s.withServer(serverId) { server ->
                val account = server.account
                if (account != null && token(account) == token) server.copy(account = null, lastUserName = account.userName) else server
            }
        }
    }

    /** Clears [account]'s sign-in on [serverId], unless it has already been replaced. */
    private fun signOut(serverId: String, account: StoredAccount) {
        update { s ->
            s.withServer(serverId) { server ->
                if (server.account?.token == account.token) server.copy(account = null, lastUserName = account.userName) else server
            }
        }
    }

    private fun apply(transform: (Stored) -> Stored): Stored {
        var sessionChanged = false
        val next = synchronized(lock) {
            val next = transform(state.value)
            state.value = next
            currentState.value = next.current?.toSaved()
            savedState.value = saved(next)
            userState.value = user(next)
            nameState.value = next.deviceName.orEmpty()
            qualityState.value = quality(next)
            autoState.value = autoQuality(next)
            deviceState.value = device(next)
            val key = sessionKey(next)
            if (key != sessionKey) {
                sessionKey = key
                revision++
                sessionRevisionState.value = revision
                sessionChanged = true
            }
            next
        }
        if (sessionChanged) sessionListeners.forEach { it(next.current?.account != null) }
        return next
    }

    private fun sessionKey(s: Stored) = s.current?.let { "${it.id} ${it.baseUrl} ${it.account?.userId} ${it.account?.token}" }

    private suspend fun save() = writeLock.withLock {
        // Always the newest state, so writes that overtake each other still end right.
        val snapshot = state.value
        try {
            dataStore.edit { prefs -> encode(snapshot, prefs) }
        } catch (e: Exception) {
            Log.w(TAG, "could not save settings", e)
        }
    }

    private suspend fun load(): Stored = try {
        decode(dataStore.data.first())
    } catch (e: Exception) {
        Log.w(TAG, "could not read settings; starting afresh", e)
        Stored()
    }

    private fun saved(s: Stored) = s.servers.sortedByDescending { it.lastUsedAtMs }.map { it.toSaved() }

    private fun user(s: Stored): User? = s.current?.account?.let { User(it.userId, it.userName, it.isAdmin) }

    /**
     * Automatic when none was saved; an ID this app doesn't know is Original,
     * and so is a saved Automatic on a server that can't do it.
     */
    private fun quality(s: Stored): StreamQuality {
        val saved = if (s.quality == null) StreamQuality.Auto else StreamQuality.byId(s.quality) ?: StreamQuality.Original
        return if (saved == StreamQuality.Auto && !autoQuality(s)) StreamQuality.Original else saved
    }

    private fun autoQuality(s: Stored) = (s.current?.apiLevel ?: 0) >= AUTO_API_LEVEL

    /** What a value this app doesn't know reads as: the default. */
    private fun device(s: Stored): DevicePlayback {
        val mobile = s.mobileQuality?.let { StreamQuality.byId(it) ?: StreamQuality.Original }
        return DevicePlayback(
            mobileQuality = if (mobile == StreamQuality.Auto && !autoQuality(s)) StreamQuality.Original else mobile,
            matchFrameRate = s.matchFrameRate,
            audioPassthrough = AudioPassthrough.entries.firstOrNull { it.name == s.passthrough } ?: AudioPassthrough.Auto,
            subtitleSize = SubtitleSize.entries.firstOrNull { it.name == s.subtitleSize } ?: SubtitleSize.Normal,
        )
    }

    companion object {
        private const val TAG = "WatchMyMedia"
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val DEVICE_NAME = stringPreferencesKey("device_name")
        private val SERVERS = stringPreferencesKey("servers")
        private val CURRENT = stringPreferencesKey("current_server")
        private val QUALITY = stringPreferencesKey("quality")
        private val MOBILE_QUALITY = stringPreferencesKey("mobile_quality")
        private val MATCH_FRAME_RATE = booleanPreferencesKey("match_frame_rate")
        private val PASSTHROUGH = stringPreferencesKey("audio_passthrough")
        private val SUBTITLE_SIZE = stringPreferencesKey("subtitle_size")
        private val json = Json { ignoreUnknownKeys = true }
        private val serversSerializer = ListSerializer(StoredServer.serializer())

        fun decode(prefs: Preferences) = Stored(
            deviceId = prefs[DEVICE_ID],
            deviceName = prefs[DEVICE_NAME],
            servers = prefs[SERVERS]?.let { runCatching { json.decodeFromString(serversSerializer, it) }.getOrNull() }.orEmpty(),
            currentId = prefs[CURRENT],
            quality = prefs[QUALITY],
            mobileQuality = prefs[MOBILE_QUALITY],
            matchFrameRate = prefs[MATCH_FRAME_RATE] ?: false,
            passthrough = prefs[PASSTHROUGH],
            subtitleSize = prefs[SUBTITLE_SIZE],
        )

        fun encode(s: Stored, prefs: androidx.datastore.preferences.core.MutablePreferences) {
            fun put(key: Preferences.Key<String>, v: String?) {
                if (v == null) prefs.remove(key) else prefs[key] = v
            }
            put(DEVICE_ID, s.deviceId)
            put(DEVICE_NAME, s.deviceName)
            put(SERVERS, json.encodeToString(serversSerializer, s.servers))
            put(CURRENT, s.currentId)
            put(QUALITY, s.quality)
            put(MOBILE_QUALITY, s.mobileQuality)
            put(PASSTHROUGH, s.passthrough)
            put(SUBTITLE_SIZE, s.subtitleSize)
            // Left out while off, as the other settings are while at their defaults.
            if (s.matchFrameRate) prefs[MATCH_FRAME_RATE] = true else prefs.remove(MATCH_FRAME_RATE)
        }
    }
}
