package media.watchmy.client.core.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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

    override suspend fun forget(serverId: String) {
        val gone = store.value.servers.firstOrNull { it.id == serverId } ?: return
        store.updateAndSave { s ->
            s.copy(servers = s.servers.filter { it.id != serverId }, currentId = s.currentId.takeIf { it != serverId })
        }
        // Ends the session on the server too, when it can be reached; forgetting works offline either way.
        val token = gone.account?.let(store::token) ?: return
        signOutLater(gone.baseUrl, token)
    }

    private companion object {
        /** An address answered with more redirects than this is not a server to keep. */
        const val MAX_REDIRECTS = 3
    }

    /** [signOutQuietly] in the background, for a session nothing waits on. */
    fun signOutLater(baseUrl: String, token: String) {
        scope.launch { signOutQuietly(baseUrl, token) }
    }

    /** POST /api/auth/logout with [token], ignoring every failure. */
    suspend fun signOutQuietly(baseUrl: String, token: String) {
        withTimeoutOrNull(5_000) {
            runCatching { AuthApi(baseUrl, http.withToken(token)).logout() }
        }
    }
}
