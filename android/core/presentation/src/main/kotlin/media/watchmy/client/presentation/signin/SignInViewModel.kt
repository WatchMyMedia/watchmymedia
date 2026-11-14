package media.watchmy.client.presentation.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import media.watchmy.client.model.PairingCode
import media.watchmy.client.model.PairingState
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage

/**
 * Sign-in on the phone, the tablet and the TV: a name and password, or a
 * code that a signed-in browser approves.
 *
 * - [codeFirst] (the TV): the code is the way in, renewed as each one runs
 *   out, with a countdown to that ([State.codeRemainingMs]); the password
 *   form is the other way ([usePassword]).
 * - Otherwise (the phone and tablet): the form, and a code only on a server
 *   that wants a browser check first (Turnstile), which an app cannot show.
 *
 * A server with no accounts yet sends the person to its web page to make the
 * first one. Success needs no event: [AuthRepository.user] changes and the
 * app moves on. The text fields are the screens' own (`TextFieldState`);
 * they report what is typed with [setName] and [setPassword].
 */
class SignInViewModel(
    private val auth: AuthRepository,
    private val servers: ServerRepository,
    private val codeFirst: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    data class State(
        val name: String = "",
        val password: String = "",
        val showPassword: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        /** Sign in with a code rather than the form. */
        val codeSignIn: Boolean = false,
        /** The code sign-in under way, while [codeSignIn]. */
        val pairing: PairingState? = null,
        /** Until the code shown runs out. */
        val codeRemainingMs: Long = 0,
        /** The server has no accounts yet: its first one is made on its web page, not here. */
        val setupRequired: Boolean = false,
        /** Asking the server again whether it is set up. */
        val checking: Boolean = false,
        /** Moves on when the model sets [name] and [password] itself, for the screen's fields to take them. */
        val revision: Int = 0,
    ) {
        val canSubmit: Boolean get() = name.isNotBlank() && password.isNotEmpty() && !busy && !codeSignIn && !setupRequired
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The server the sign-in is for. */
    val server: StateFlow<SavedServer?> = servers.current

    /** What this device is called on the server's list of signed-in devices. */
    val deviceName: StateFlow<String> = auth.deviceName

    private var pairing: Job? = null

    init {
        viewModelScope.launch {
            // Another server, this one set up since, or signed out again (this can live as long as
            // the app): start the form over, so one server's name, password and error, or a code
            // already approved, don't carry over to the next sign-in.
            combine(
                servers.current.map { it?.server?.let { s -> Triple(s.id, s.turnstile, s.setupRequired) } },
                auth.user.map { it != null },
                ::Pair,
            ).distinctUntilChanged().collect { (s, signedIn) ->
                if (signedIn) {
                    pairing?.cancel()
                    // Signed in while a code was up: it was the code (the account can come in just before
                    // the pairing says so), so the screen says the code was accepted.
                    val user = auth.user.value
                    _state.update { if (it.codeSignIn && it.pairing is PairingState.Waiting && user != null) it.copy(pairing = PairingState.Approved(user)) else it }
                    return@collect
                }
                _state.value = State(setupRequired = s?.third == true, revision = _state.value.revision + 1)
                setCode(codeFirst || s?.second == true && !_state.value.setupRequired)
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

    /** The TV's "Sign in with a password instead": the form, and no code polling meanwhile. */
    fun usePassword() = setCode(false)

    /** Back to the code from the form. */
    fun useCode() = setCode(true)

    private fun setCode(code: Boolean) {
        _state.update { it.copy(codeSignIn = code, pairing = null, error = null, busy = false) }
        if (code) startPairing() else pairing?.cancel()
    }

    /** Pairs; with [codeFirst], each code that runs out is followed by a new one. */
    private fun startPairing() {
        pairing?.cancel()
        _state.update { it.copy(pairing = PairingState.Starting) }
        pairing = viewModelScope.launch {
            try {
                while (true) {
                    var expired = false
                    var ticker: Job? = null
                    auth.pair().collect { p ->
                        ticker?.cancel()
                        if (p == PairingState.Expired) {
                            expired = true
                            return@collect
                        }
                        _state.update { it.copy(pairing = p) }
                        if (p is PairingState.Waiting) ticker = launch { countDown(p.code) }
                    }
                    ticker?.cancel()
                    if (!expired) break
                    if (!codeFirst) {
                        _state.update { it.copy(pairing = PairingState.Expired, codeRemainingMs = 0) }
                        break
                    }
                    _state.update { it.copy(pairing = PairingState.Starting) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(pairing = PairingState.Failed(errorMessage(e))) }
            }
        }
    }

    private suspend fun countDown(code: PairingCode) {
        while (true) {
            val remaining = (code.expiresAtMs - now()).coerceAtLeast(0)
            _state.update { it.copy(codeRemainingMs = remaining) }
            if (remaining == 0L) return
            // Wake on the next whole second, so the label ticks evenly.
            delay((remaining - 1) % 1000 + 1)
        }
    }

    fun setName(name: String) = _state.update { if (it.name == name) it else it.copy(name = name, error = null) }

    fun setPassword(password: String) = _state.update { if (it.password == password) it else it.copy(password = password, error = null) }

    fun toggleShowPassword() = _state.update { it.copy(showPassword = !it.showPassword) }

    fun signIn() {
        val s = _state.value
        if (s.busy || s.codeSignIn || s.setupRequired) return
        // The Apple apps' wording, for either field.
        if (!s.canSubmit) {
            _state.update { it.copy(error = "Enter your name and password.") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            attempt { auth.signIn(s.name.trim(), s.password) }
                .onSuccess { _state.update { it.copy(busy = false, password = "", showPassword = false, revision = it.revision + 1) } }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, error = signInMessage(e)) }
                    // Turnstile was turned on since the server was chosen: sign in with a code.
                    if ((e as? ApiException)?.status == 403) setCode(true)
                }
        }
    }
}

/** A wrong password (401) and throttling (429) get their own words; anything else shows the server's message. */
fun signInMessage(e: Throwable): String = when ((e as? ApiException)?.status) {
    401 -> "Wrong name or password."
    429 -> "Too many tries. Wait a minute, then try again."
    else -> errorMessage(e, "Couldn't sign in. Check the server is running, then try again.")
}

/** "4:52". */
fun countdownLabel(ms: Long): String {
    val s = (ms + 999) / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
