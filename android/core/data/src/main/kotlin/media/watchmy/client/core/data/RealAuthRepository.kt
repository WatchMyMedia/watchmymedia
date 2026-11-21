package media.watchmy.client.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import media.watchmy.client.core.api.apis.AuthApi
import media.watchmy.client.core.api.models.ChangePasswordInputBody
import media.watchmy.client.core.api.models.ClaimPairingInputBody
import media.watchmy.client.core.api.models.CredentialsInputBody
import media.watchmy.client.core.api.models.PairingClaim
import media.watchmy.client.core.api.models.StartPairingInputBody
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.DeviceSession
import media.watchmy.client.model.OfflineOwner
import media.watchmy.client.model.PairingCode
import media.watchmy.client.model.PairingState
import media.watchmy.client.model.PendingDevice
import media.watchmy.client.model.User
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class RealAuthRepository(
    private val store: LocalStore,
    private val http: Http,
    private val servers: RealServerRepository,
    private val client: () -> ClientInfo,
    private val clock: () -> Long,
    /** The data layer's, which outlives the screens; [signOut] runs in it. */
    private val scope: CoroutineScope,
    /** Waits between a pairing's claims; tests pass one that doesn't. */
    private val pause: suspend (ms: Long) -> Unit = { delay(it) },
) : AuthRepository {
    override val user: StateFlow<User?> = store.user

    override val deviceName: StateFlow<String> = store.deviceName

    /** Runs [hook] before a sign-in ends on this device (Sign out, or its server removed), for up to a few seconds; its failures are ignored. */
    fun beforeSignOut(hook: suspend (OfflineOwner) -> Unit) = servers.beforeSignInEnds(hook)

    override suspend fun setDeviceName(name: String) {
        val trimmed = name.trim().take(MAX_NAME)
        if (trimmed.isEmpty()) return
        store.updateAndSave { it.copy(deviceName = trimmed) }
    }

    override suspend fun signIn(name: String, password: String): User {
        val (snapshot, revision) = store.requestSnapshot()
        val server = snapshot?.server ?: throw ApiException(null, "Choose a server first.")
        val reply = try {
            Http.call(signedIn = false) {
                AuthApi(server.baseUrl, http.authClient).login(CredentialsInputBody(name = name.trim(), password = password, client = client().toApi()))
            }
        } catch (e: ApiException) {
            // Turnstile on the server: only the web page can show the check, so the app signs in with a code.
            if (e.status == 403) throw ApiException(403, "This server asks for a browser check before password sign-in. Sign in with a code instead.", e)
            throw e
        }
        val token = reply.token ?: throw ApiException(null, "The server did not send a sign-in token. Update the server.")
        val user = User(reply.id, reply.name, reply.isAdmin)
        keep(server, user, reply.sessionId, token, revision)
        return user
    }

    override fun pair(): Flow<PairingState> = flow {
        emit(PairingState.Starting)
        val (snapshot, revision) = store.requestSnapshot()
        val server = snapshot?.server
        if (server == null) {
            emit(PairingState.Failed("Choose a server first."))
            return@flow
        }
        val api = AuthApi(server.baseUrl, http.authClient)
        val start = try {
            Http.call(signedIn = false) { api.startPairing(StartPairingInputBody(client().toApi())) }
        } catch (e: ApiException) {
            emit(PairingState.Failed(e.message.orEmpty()))
            return@flow
        }
        val expiresAtMs = epochMs(start.expiresAt) ?: (clock() + 10 * 60_000L)
        val interval = start.intervalMs.coerceIn(MIN_POLL_MS, MAX_POLL_MS)
        emit(PairingState.Waiting(PairingCode(start.code, expiresAtMs, pairingLink(server.baseUrl, start.linkPath, start.code), interval)))
        while (true) {
            pause(interval)
            val claim = try {
                Http.call(signedIn = false) { api.claimPairing(ClaimPairingInputBody(start.secret)) }
            } catch (e: ApiException) {
                val status = e.status
                when {
                    // Expired, or the token was already handed out: start again.
                    status == 404 -> {
                        emit(PairingState.Expired)
                        return@flow
                    }
                    // A dropped connection or a busy server: keep polling while the code lasts.
                    (status == null || status >= 500) && clock() < expiresAtMs -> continue
                    status == null || status >= 500 -> {
                        emit(PairingState.Expired)
                        return@flow
                    }
                    else -> {
                        emit(PairingState.Failed(e.message.orEmpty()))
                        return@flow
                    }
                }
            }
            if (claim.status == PairingClaim.Status.approved) {
                val token = claim.token
                val account = claim.user
                if (token == null || account == null) {
                    emit(PairingState.Failed("The server approved the code but sent no sign-in. Update the server."))
                } else {
                    val user = account.toModel()
                    keep(server, user, claim.sessionId, token, revision)
                    emit(PairingState.Approved(user))
                }
                return@flow
            }
        }
    }

    /**
     * Runs in [scope], and the caller waits: Settings closes as soon as the
     * sign-in clears, halfway through, and must not take the rest with it.
     * Run in the screen's scope, the sign-out was kept in memory alone and the
     * server never heard it, so the app signed back in after a restart.
     */
    override suspend fun signOut() = scope.async { endSession() }.await()

    private suspend fun endSession() {
        val session = store.session() ?: return
        val account = session.account ?: return
        servers.signInEnding(session.server, account)
        val token = store.tokenOrNull(account)
        val now = clock()
        store.updateAndSave { s ->
            val signedOut = s.withServer(session.server.id) { it.copy(account = null, lastUserName = account.userName) }
            // The server stays on the list, signed out. With another still signed in the app moves to it rather than to this one's sign-in.
            if (s.currentId == session.server.id) signedOut.movedToSignedIn(now) ?: signedOut else signedOut
        }
        if (token != null) servers.signOutQuietly(session.server, token)
    }

    override suspend fun sessions(all: Boolean): List<DeviceSession> =
        http.signedIn { base, client -> AuthApi(base, client).listSessions(all.takeIf { a -> a }) }.items.map { it.toModel() }

    override suspend fun revoke(sessionId: String) {
        http.signedIn { base, client -> AuthApi(base, client).revokeSession(sessionId) }
        val session = store.session() ?: return
        val account = session.account ?: return
        if (account.sessionId == sessionId) {
            store.updateAndSave { s -> s.withServer(session.server.id) { it.copy(account = null, lastUserName = account.userName) } }
        }
    }

    override suspend fun lookUpPairing(code: String): PendingDevice =
        http.signedIn { base, client -> AuthApi(base, client).getPairing(normaliseCode(code)) }.toModel()

    override suspend fun approvePairing(code: String): PendingDevice =
        http.signedIn { base, client -> AuthApi(base, client).approvePairing(normaliseCode(code)) }.toModel()

    // The server answers a wrong current password with 401 "current password is
    // wrong", which must not sign this device out; "sign in required" still does.
    override suspend fun changePassword(current: String, new: String) {
        http.signedInRefusing401({ it.message.orEmpty().contains("password", ignoreCase = true) }) { base, client ->
            AuthApi(base, client).changePassword(ChangePasswordInputBody(current = current, new = new))
        }
    }

    /**
     * Refreshes the signed-in user from GET /api/auth/me (a renamed account, a
     * change of admin rights). A revoked session clears the sign-in through the
     * 401; being offline changes nothing.
     */
    suspend fun refreshUser() {
        val session = store.session() ?: return
        val account = session.account ?: return
        val me = runCatching { http.signedIn { base, client -> AuthApi(base, client).getMe() } }.getOrNull() ?: return
        store.update { s ->
            s.withServer(session.server.id) { server ->
                val current = server.account
                if (current?.token == account.token) {
                    server.copy(account = current.copy(userId = me.id, userName = me.name, isAdmin = me.isAdmin))
                } else {
                    server
                }
            }
        }
    }

    private suspend fun keep(server: StoredServer, user: User, sessionId: String?, token: String, revision: Long) {
        val serverId = server.id
        val sealed = withContext(Dispatchers.IO) { store.seal(token) }
        val replaced = store.value.servers.firstOrNull { it.id == serverId }?.let { kept -> kept.account?.let { kept to it } }
        store.updateAndSave { s ->
            if (store.revision != revision || s.current?.id != server.id ||
                s.current?.baseUrl?.let { ServerAddress.sameEndpoint(it, server.baseUrl) } != true
            ) {
                servers.signOutLater(server, token)
                throw CancellationException("The server or account changed during sign-in.")
            }
            s.withServer(serverId) {
                it.copy(account = StoredAccount(user.id, user.name, user.isAdmin, sessionId, sealed), lastUserName = user.name)
            }
        }
        // Signing in again (another account, or the same one) ends the session it replaces.
        replaced?.let { (kept, old) -> store.tokenOrNull(old)?.let { servers.signOutLater(kept, it) } }
    }

    companion object {
        const val MAX_NAME = 64
        private const val MIN_POLL_MS = 250L
        private const val MAX_POLL_MS = 10_000L

        /**
         * The approval page for the QR code: the server's [linkPath] taken
         * relative to its [baseUrl], as long as that stays on the server
         * ([Http.owns]); otherwise the server's own /link page with [code].
         * Joined as text, a linkPath such as "@evil.example/" would have
         * sent the phone that scans the code to another host.
         */
        fun pairingLink(baseUrl: String, linkPath: String, code: String): String {
            val base = "${baseUrl.trimEnd('/')}/".toHttpUrlOrNull() ?: return "${baseUrl.trimEnd('/')}/link"
            val given = base.resolve(linkPath.trimStart('/'))
            if (given != null && Http.owns(baseUrl, given)) return given.toString()
            return base.newBuilder().addPathSegment("link").addQueryParameter("code", code).build().toString()
        }

        /** "abc-234" → "ABC234"; the server is as forgiving, this keeps the path tidy. */
        fun normaliseCode(code: String): String {
            val c = code.filter { it.isLetterOrDigit() }.uppercase()
            if (c.length != 6) throw ApiException(null, "A code has six letters and digits, like KX7P4M.")
            return c
        }
    }
}
