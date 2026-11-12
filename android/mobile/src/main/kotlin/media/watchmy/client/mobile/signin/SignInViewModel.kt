package media.watchmy.client.mobile.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.PairingState
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage

/**
 * Password sign-in, or sign-in by code on a server that wants a browser
 * check first (Turnstile), which an app cannot show: the phone gets a code
 * that a signed-in browser approves, as a TV does. A server with no accounts
 * yet sends the person to its web page to make the first one. Success needs
 * no event: [AuthRepository.user] changes and the app moves on.
 */
class SignInViewModel(private val auth: AuthRepository, private val servers: ServerRepository) : ViewModel() {
    data class State(
        val name: String = "",
        val password: String = "",
        val showPassword: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        /** The server refuses password sign-in: show a code instead of the form. */
        val codeSignIn: Boolean = false,
        /** The code sign-in under way, while [codeSignIn]. */
        val pairing: PairingState? = null,
        /** The server has no accounts yet: its first one is made on its web page, not here. */
        val setupRequired: Boolean = false,
        /** Asking the server again whether it is set up. */
        val checking: Boolean = false,
    ) {
        val canSubmit: Boolean get() = name.isNotBlank() && password.isNotEmpty() && !busy && !codeSignIn && !setupRequired
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The server the sign-in is for, for the card at the top. */
    val server: StateFlow<SavedServer?> = servers.current

    private var pairing: Job? = null

    init {
        viewModelScope.launch {
            // Another server, this one set up since, or signed out again (this lives as long as the
            // app): start the form over, so one server's name, password and error, or a code already
            // approved, don't carry over to the next sign-in.
            combine(
                servers.current.map { it?.server?.let { s -> Triple(s.id, s.turnstile, s.setupRequired) } },
                auth.user.map { it != null },
                ::Pair,
            ).distinctUntilChanged().collect { (s, signedIn) ->
                if (signedIn) {
                    pairing?.cancel()
                    return@collect
                }
                _state.value = State(setupRequired = s?.third == true)
                useCode(s?.second == true && !_state.value.setupRequired)
            }
        }
    }

    /**
     * Asks the server again whether it has an account yet, after the person
     * made one on its web page. [quiet] for the check on coming back to the
     * app, which says nothing while the server is still waiting.
     */
    fun checkSetup(quiet: Boolean = false) {
        val current = servers.current.value?.server ?: return
        val s = _state.value
        if (!s.setupRequired || s.checking) return
        _state.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            attempt { servers.probe(current.baseUrl) }
                .onSuccess { fresh ->
                    // Choosing it again saves the new answer; the collector above moves on to the sign-in.
                    if (fresh.id == current.id && !fresh.setupRequired) {
                        servers.select(fresh)
                    } else {
                        _state.update { it.copy(checking = false, error = if (quiet) null else "No account on this server yet. Make one on its web page first.") }
                    }
                }
                .onFailure { e -> _state.update { it.copy(checking = false, error = if (quiet) null else errorMessage(e, "Couldn't reach the server.")) } }
        }
    }

    /** Starts over with a fresh code (after one ran out or failed). */
    fun newCode() {
        if (_state.value.codeSignIn) startPairing()
    }

    private fun useCode(code: Boolean) {
        _state.update { it.copy(codeSignIn = code, pairing = null, error = null, busy = false) }
        if (code) startPairing() else pairing?.cancel()
    }

    private fun startPairing() {
        pairing?.cancel()
        pairing = viewModelScope.launch {
            auth.pair().collect { p -> _state.update { it.copy(pairing = p) } }
        }
    }

    fun setName(name: String) = _state.update { it.copy(name = name, error = null) }

    fun setPassword(password: String) = _state.update { it.copy(password = password, error = null) }

    fun toggleShowPassword() = _state.update { it.copy(showPassword = !it.showPassword) }

    fun signIn() {
        val s = _state.value
        if (s.busy) return
        if (!s.canSubmit) {
            _state.update { it.copy(error = "Enter your name and password.") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            attempt { auth.signIn(s.name.trim(), s.password) }
                .onSuccess { _state.update { it.copy(busy = false, password = "", showPassword = false) } }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, error = signInMessage(e)) }
                    // Turnstile was turned on since the server was chosen: sign in with a code.
                    if ((e as? ApiException)?.status == 403) useCode(true)
                }
        }
    }
}

/** A wrong password (401) and throttling (429) get their own words; anything else shows the server's message. */
internal fun signInMessage(e: Throwable): String = when ((e as? ApiException)?.status) {
    401 -> "Wrong name or password."
    429 -> "Too many tries. Wait a minute, then try again."
    else -> errorMessage(e, "Couldn't sign in. Check the server is running, then try again.")
}
