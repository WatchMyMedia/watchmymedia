package media.watchmy.client.presentation.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.AddServerStep
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.NOW_ON_MS
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.ServerSwitch
import media.watchmy.client.model.attempt
import media.watchmy.client.model.couldntReachLine
import media.watchmy.client.model.nowOn

/**
 * What happens around a change of server, kept above the signed-in screens,
 * which are rebuilt on each one and so can't carry it themselves:
 *
 * - **The note** ([State.note]): "Now on Cabin server" whenever the app moves
 *   to another server that is signed in (a switch, the one in use removed or
 *   signed out of), gone by itself after a few seconds.
 * - **A search result on another server** ([openFound]): the app switches to
 *   it, then opens Search with the same words and the title on top
 *   ([State.landing]), so Back from the title is Search and Back from Search
 *   is the new server's Home.
 * - **A link for another registered account** ([arrive]): the app switches to
 *   its server before the link opens.
 * - **Add a server** ([startAdding]): choose one, sign in, and on a phone or
 *   tablet a last step that says what changed ([State.adding], [State.added]).
 * - **Cancel on a sign-in the app switched to** ([cancelSignIn]): back on the
 *   server it was on ([State.returnTo]). A failure at any step leaves the
 *   app there too.
 *
 * It lives as long as the app's window, across every server.
 */
class ServerSwitchViewModel(
    private val servers: ServerRepository,
    /** Add a server ends on an Added step (the phone and tablet); the TV goes to the new server's Home with the note. */
    private val addedStep: Boolean = true,
    private val noteMs: Long = NOW_ON_MS,
) : ViewModel() {
    /** Search with [query], then [page] on top, once the app is on [serverId], the server that has it. */
    data class Landing(val serverId: String, val query: String, val page: DeepLink)

    /** Add a server's last step: which server, who signed in, and how many servers the device is signed in to now. */
    data class Added(val serverName: String, val userName: String?, val signedIn: Int)

    data class State(
        val note: String? = null,
        val landing: Landing? = null,
        /** Add a server's step; null when no server is being added. */
        val adding: AddServerStep? = null,
        val added: Added? = null,
        /** The server the app was on before it switched to one that needs a sign-in: where Cancel goes. */
        val returnTo: String? = null,
    ) {
        /**
         * [landing] for the screens of [serverId], and for no others. The
         * switch takes a moment, and the landing is set before it: the
         * screens of the server still in use would otherwise open the page
         * (another server's id, on theirs) and use the landing up before the
         * app has moved.
         */
        fun landingFor(serverId: String?): Landing? = landing?.takeIf { it.serverId == serverId }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var noteJob: Job? = null

    /** The next move is the app's own way back (Cancel): no note for it. */
    private var quiet = false

    init {
        viewModelScope.launch {
            var before = servers.current.value
            servers.current.collect { now ->
                val was = before
                before = now
                moved(was, now)
            }
        }
    }

    private suspend fun moved(was: SavedServer?, now: SavedServer?) {
        if (now == null) {
            _state.update { it.copy(returnTo = null, adding = null, added = null, landing = null) }
            return
        }
        val another = was != null && was.server.id != now.server.id
        when {
            // On another server, signed in: a switch, or the one in use removed or signed out of.
            another && now.signedIn -> {
                val silent = quiet || _state.value.adding != null
                quiet = false
                _state.update { it.copy(returnTo = null) }
                if (!silent) say(nowOn(now.server.name))
            }
            // On another server that needs a sign-in: Cancel goes back to the one before, when someone is signed in there.
            another -> _state.update {
                it.copy(
                    returnTo = was?.takeIf { w -> w.signedIn }?.server?.id ?: it.returnTo,
                    adding = if (it.adding == AddServerStep.Choose) AddServerStep.SignIn else it.adding,
                    landing = null,
                )
            }
            // The server in use ended the sign-in (another device signed this one out, which a server just switched to
            // only says at its first reply): Cancel on its sign-in goes to the server used last that is still signed in.
            was?.signedIn == true && !now.signedIn -> {
                val back = servers.saved.first().filter { it.registered && it.signedIn && it.server.id != now.server.id }.maxByOrNull { it.lastUsedAtMs }
                if (back != null) _state.update { it.copy(returnTo = back.server.id, landing = null) }
            }
            // Signed in on the server the app switched to.
            was?.signedIn == false && now.signedIn && (_state.value.returnTo != null || _state.value.adding == AddServerStep.SignIn) -> {
                val adding = _state.value.adding == AddServerStep.SignIn
                if (adding && addedStep) {
                    // The saved list can be a moment behind the server in use: count this one, and the others it lists.
                    val signedIn = 1 + servers.saved.first().count { it.registered && it.signedIn && it.server.id != now.server.id }
                    _state.update { it.copy(returnTo = null, adding = AddServerStep.Added, added = Added(now.server.name, now.userName, signedIn)) }
                } else {
                    _state.update { it.copy(returnTo = null, adding = null) }
                    say(nowOn(now.server.name))
                }
            }
        }
    }

    private fun say(text: String) {
        noteJob?.cancel()
        _state.update { it.copy(note = text) }
        noteJob = viewModelScope.launch {
            delay(noteMs)
            _state.update { if (it.note == text) it.copy(note = null) else it }
        }
    }

    /** The note was put away before its time. */
    fun noteShown() {
        noteJob?.cancel()
        _state.update { it.copy(note = null) }
    }

    /**
     * Opens a search result on another server: the app switches to
     * [serverId], which has just answered the search and so isn't asked
     * again, and then shows Search with [query] and [page] on it. When the
     * switch doesn't happen, the app stays where it is and nothing opens.
     */
    fun openFound(serverId: String, query: String, page: DeepLink) {
        viewModelScope.launch {
            val current = servers.current.value?.server
            val name = servers.saved.first().firstOrNull { it.server.id == serverId }?.server?.name
            _state.update { it.copy(landing = Landing(serverId, query, page)) }
            val outcome = attempt { servers.switchTo(serverId, ask = false) }.getOrDefault(ServerSwitch.Unreachable)
            if (outcome != ServerSwitch.Switched) {
                _state.update { it.copy(landing = null) }
                if (outcome != ServerSwitch.NeedsSignIn && name != null) say(couldntReachLine(name, current?.name))
            }
        }
    }

    /** The new server's screens have opened Search and the title. */
    fun landingShown() = _state.update { it.copy(landing = null) }

    /**
     * A link the app was opened with: when it was published for another
     * registered account, the app switches to that account's server first.
     * True when the link can be opened now, for the account in use; false
     * when its server couldn't be switched to, which the note says.
     */
    suspend fun arrive(link: String?): Boolean {
        val target = DeepLink.serverFor(link, servers.saved.first()) ?: return true
        val current = servers.current.value?.server
        if (target.server.id == current?.id) return true
        return when (attempt { servers.switchTo(target.server.id) }.getOrDefault(ServerSwitch.Unreachable)) {
            ServerSwitch.Switched -> true
            ServerSwitch.Unreachable -> {
                say(couldntReachLine(target.server.name, current?.name))
                false
            }
            else -> false
        }
    }

    /** Add a server: its first step, choosing one. */
    fun startAdding() = _state.update { it.copy(adding = AddServerStep.Choose, added = null) }

    /** Add a server was closed before a server was chosen, or its last step was read. */
    fun doneAdding() = _state.update { it.copy(adding = null, added = null) }

    /**
     * Cancel on the sign-in of a server the app switched to: back on the
     * server it was on, without asking it again. A server chosen to add and
     * never signed in to isn't kept.
     */
    fun cancelSignIn() {
        val back = _state.value.returnTo ?: return
        val left = servers.current.value
        quiet = true
        viewModelScope.launch {
            val outcome = attempt { servers.switchTo(back, ask = false) }.getOrDefault(ServerSwitch.Gone)
            if (outcome != ServerSwitch.Switched) quiet = false
            if (left != null && !left.registered && left.server.id != back) attempt { servers.forget(left.server.id) }
            _state.update { it.copy(returnTo = null, adding = null, added = null) }
        }
    }
}
