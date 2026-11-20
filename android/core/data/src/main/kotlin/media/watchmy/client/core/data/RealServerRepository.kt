package media.watchmy.client.core.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import media.watchmy.client.core.api.apis.AuthApi
import media.watchmy.client.core.api.apis.SystemApi
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.DiscoveredServer
import media.watchmy.client.model.OfflineOwner
import media.watchmy.client.model.REQUIRED_API_LEVEL
import media.watchmy.client.model.ServerSwitch
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.Server
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.ServerTooOldException

internal class RealServerRepository(
    private val store: LocalStore,
    private val http: Http,
    discovery: ServerDiscovery,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) : ServerRepository {
    override val discovered: Flow<List<DiscoveredServer>> =
        discovery.servers().shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    override val saved: Flow<List<SavedServer>> = store.saved

    override val current: StateFlow<SavedServer?> = store.current

    override suspend fun probe(address: String): Server {
        var reached = false
        var failure: Exception? = null
        val queue = ArrayDeque(ServerAddress.candidates(address))
        var redirects = 0
        while (queue.isNotEmpty()) {
            val base = queue.removeFirst()
            try {
                val info = SystemApi(base, http.probeClient).getServer()
                val server = Server(info.id, info.name, base, info.version, info.apiLevel.toInt(), info.setupRequired, info.turnstile)
                if (server.tooOld) throw ServerTooOldException(server)
                return server
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServerTooOldException) {
                throw e
            } catch (e: RedirectException) {
                // Something answered. Follow it only on the same host (http to https, or another path).
                reached = true
                failure = e
                val next = ServerAddress.redirectedBase(base, e.location)
                if (next != null && next != base && redirects++ < MAX_REDIRECTS) queue.addFirst(next)
            } catch (e: Exception) {
                if (e !is IOException) reached = true
                failure = e
            }
        }
        val shown = address.trim()
        throw ApiException(
            null,
            if (reached) "$shown doesn't look like a WatchMyMedia server." else "Couldn't reach $shown. Check the address and that the server is running.",
            failure,
        )
    }

    override suspend fun select(server: Server) {
        val now = clock()
        store.updateAndSave { s ->
            val existing = s.servers.firstOrNull { it.id == server.id }
            val entry = (existing ?: StoredServer(server.id, server.name, server.baseUrl, server.version, server.apiLevel)).copy(
                name = server.name,
                // A public server ID never authorizes moving a saved credential.
                baseUrl = server.baseUrl,
                account = existing?.account.takeIf { existing != null && ServerAddress.sameEndpoint(existing.baseUrl, server.baseUrl) },
                version = server.version,
                apiLevel = server.apiLevel,
                setupRequired = server.setupRequired,
                turnstile = server.turnstile,
                lastUsedAtMs = now,
            )
            s.copy(servers = listOf(entry) + s.servers.filter { it.id != server.id }, currentId = server.id)
        }
    }

    /**
     * Brings the server in use's version and API level up to date (GET
     * /api/server), so a server upgraded since it was chosen gets what its
     * new level brings, such as Automatic quality. Throws when it can't be reached.
     * The answer is dropped when something newer came first: another
     * sign-in (which asks again), or choosing the server again with what a
     * fresh probe said. An older answer must not put back an older level.
     */
    suspend fun refreshCurrent() {
        val (session, revision) = store.requestSnapshot()
        val current = session?.server ?: return
        val info = Http.call(signedIn = false) { SystemApi(current.baseUrl, http.probeClient).getServer() }
        if (info.id != current.id) return
        store.update { s ->
            s.withServer(current.id) {
                val changed = store.revision != revision || it.name != current.name || it.version != current.version || it.apiLevel != current.apiLevel
                if (changed) it else it.copy(name = info.name, version = info.version, apiLevel = info.apiLevel.toInt())
            }
        }
    }

    /**
     * Asks the server first, so one that is off, or away on its own
     * network, leaves the app where it was. It runs in [scope] while the
     * caller waits: the switch closes the screens that asked for it, which
     * must not stop it halfway.
     */
    override suspend fun switchTo(serverId: String, ask: Boolean): ServerSwitch = scope.async { switch(serverId, ask) }.await()

    private suspend fun switch(serverId: String, ask: Boolean): ServerSwitch {
        val target = store.value.servers.firstOrNull { it.id == serverId } ?: return ServerSwitch.Gone
        var name = target.name
        var version = target.version
        var apiLevel = target.apiLevel
        if (ask) {
            val info = withTimeoutOrNull(SWITCH_ASK_MS) {
                try {
                    Http.call(signedIn = false) { SystemApi(target.baseUrl, http.probeClient).getServer() }
                } catch (_: ApiException) {
                    null
                }
            }
            // Another server at the address it had is not this server: its sign-in is never sent there.
            if (info == null || info.id != target.id) return ServerSwitch.Unreachable
            if (info.apiLevel.toInt() < REQUIRED_API_LEVEL) return ServerSwitch.TooOld
            name = info.name
            version = info.version
            apiLevel = info.apiLevel.toInt()
        } else if (target.toServer().tooOld) {
            return ServerSwitch.TooOld
        }
        val now = clock()
        var signedIn = false
        var gone = false
        store.updateAndSave { s ->
            val kept = s.servers.firstOrNull { it.id == serverId }
            if (kept == null) {
                gone = true
                return@updateAndSave s
            }
            signedIn = kept.account != null
            val entry = kept.copy(name = name, version = version, apiLevel = apiLevel, lastUsedAtMs = now)
            s.copy(servers = listOf(entry) + s.servers.filter { it.id != serverId }, currentId = serverId)
        }
        return when {
            gone -> ServerSwitch.Gone
            signedIn -> ServerSwitch.Switched
            else -> ServerSwitch.NeedsSignIn
        }
    }

    /** What runs before a sign-in ends on this device, while its server still takes it ([beforeSignInEnds]). */
    private val endingHooks = java.util.concurrent.CopyOnWriteArrayList<suspend (OfflineOwner) -> Unit>()

    /** Runs [hook] before a sign-in ends here (Sign out, or its server removed), for up to a few seconds; its failures are ignored. */
    fun beforeSignInEnds(hook: suspend (OfflineOwner) -> Unit) {
        endingHooks += hook
    }

    /** The hooks for [account]'s sign-in on [server], which is about to end. */
    suspend fun signInEnding(server: StoredServer, account: StoredAccount) {
        val owner = OfflineOwner(server.id, account.userId, server.baseUrl, server.apiLevel)
        endingHooks.forEach { hook ->
            try {
                withTimeoutOrNull(ENDING_HOOK_MS) { hook(owner) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The sign-in ends whatever the hook met.
            }
        }
    }

    /**
     * Runs in [scope] while the caller waits: removing the server in use
     * closes Settings as soon as its sign-in goes, halfway through, and must
     * not take the rest with it.
     */
    override suspend fun forget(serverId: String) = scope.async { remove(serverId) }.await()

    private suspend fun remove(serverId: String) {
        val gone = store.value.servers.firstOrNull { it.id == serverId } ?: return
        // What the device holds from it is reported while its session still stands.
        gone.account?.let { signInEnding(gone, it) }
        val token = gone.account?.let(store::tokenOrNull)
        val now = clock()
        store.updateAndSave { s ->
            val without = s.copy(servers = s.servers.filter { it.id != serverId })
            // Removing the one in use moves to the server used last that is still signed in; with none, the picker.
            if (s.currentId == serverId) without.movedToSignedIn(now) ?: without.copy(currentId = null) else without
        }
        // Ends the session on the server too, when it can be reached; removing works offline either way.
        if (token != null) signOutLater(gone.baseUrl, token)
    }

    private companion object {
        /** An address answered with more redirects than this is not a server to keep. */
        const val MAX_REDIRECTS = 3

        /** How long a server gets to say which server it is before a switch gives up. */
        const val SWITCH_ASK_MS = 5_000L

        /** How long each hook gets before a sign-in ends without it. */
        const val ENDING_HOOK_MS = 5_000L
    }

    /** [signOutQuietly] in the background, for a session nothing waits on. */
    fun signOutLater(baseUrl: String, token: String) {
        scope.launch { signOutQuietly(baseUrl, token) }
    }

    /** POST /api/auth/logout with [token], ignoring every failure. */
    suspend fun signOutQuietly(baseUrl: String, token: String) {
        withTimeoutOrNull(5_000) {
            runCatching { AuthApi(baseUrl, http.calls(Credential(baseUrl, token))).logout() }
        }
    }
}
