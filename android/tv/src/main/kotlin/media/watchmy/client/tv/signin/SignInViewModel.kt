package media.watchmy.client.tv.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.PairingCode
import media.watchmy.client.model.PairingState
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.displayAddress
import media.watchmy.client.model.errorMessage

/**
 * TV sign-in: a pairing code that renews itself when it runs out, with a
 * countdown to that, or a name and password. Signing in sets
 * [AuthRepository.user], which takes the app past this screen.
 */
class SignInViewModel(
    private val auth: AuthRepository,
    servers: ServerRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    sealed interface Pairing {
        data object Starting : Pairing

        /** [code] is the six characters; [remainingMs] counts down to a new one. */
        data class Waiting(val code: String, val linkUrl: String, val linkLabel: String, val remainingMs: Long) : Pairing

        data object Approved : Pairing

        data class Failed(val message: String) : Pairing
    }

    data class PasswordForm(val name: String = "", val password: String = "", val busy: Boolean = false, val error: String? = null)

    val server: StateFlow<SavedServer?> = servers.current
    val deviceName: StateFlow<String> = auth.deviceName

    private val _pairing = MutableStateFlow<Pairing>(Pairing.Starting)
    val pairing: StateFlow<Pairing> = _pairing.asStateFlow()

    private val _form = MutableStateFlow(PasswordForm(name = servers.current.value?.userName.orEmpty()))
    val form: StateFlow<PasswordForm> = _form.asStateFlow()

    private var pairJob: Job? = null

    init {
        startPairing()
    }

    /** Starts (or restarts) pairing; each expired code is followed by a new one. */
    fun startPairing() {
        pairJob?.cancel()
        _pairing.value = Pairing.Starting
        pairJob = viewModelScope.launch {
            try {
                while (true) {
                    var expired = false
                    var ticker: Job? = null
                    auth.pair().collect { s ->
                        ticker?.cancel()
                        when (s) {
                            PairingState.Starting -> _pairing.value = Pairing.Starting
                            is PairingState.Waiting -> {
                                _pairing.value = waiting(s.code)
                                ticker = launch { countDown(s.code) }
                            }
                            is PairingState.Approved -> _pairing.value = Pairing.Approved
                            PairingState.Expired -> expired = true
                            is PairingState.Failed -> _pairing.value = Pairing.Failed(s.message)
                        }
                    }
                    ticker?.cancel()
                    if (!expired) break
                    _pairing.value = Pairing.Starting
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _pairing.value = Pairing.Failed(errorMessage(e))
            }
        }
    }

    /** Stops polling (while the password form is up). */
    fun stopPairing() {
        pairJob?.cancel()
        pairJob = null
    }

    private fun waiting(code: PairingCode) =
        Pairing.Waiting(code.code, code.linkUrl, displayAddress(code.linkUrl), (code.expiresAtMs - now()).coerceAtLeast(0))

    private suspend fun countDown(code: PairingCode) {
        while (true) {
            val w = waiting(code)
            _pairing.value = w
            val remaining = w.remainingMs
            if (remaining == 0L) return
            // Wake on the next whole second, so the label ticks evenly.
            delay((remaining - 1) % 1000 + 1)
        }
    }

    fun setName(name: String) = _form.update { it.copy(name = name, error = null) }

    fun setPassword(password: String) = _form.update { it.copy(password = password, error = null) }

    fun signIn() {
        val f = _form.value
        if (f.busy) return
        if (f.name.isBlank()) {
            _form.update { it.copy(error = "Enter your name.") }
            return
        }
        if (f.password.isEmpty()) {
            _form.update { it.copy(error = "Enter your password.") }
            return
        }
        _form.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                auth.signIn(f.name.trim(), f.password)
                _form.update { it.copy(busy = false, password = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                val message = when (e.status) {
                    401 -> e.message?.takeIf { it.isNotBlank() } ?: "Wrong name or password."
                    429 -> "Too many tries. Wait a minute, then try again."
                    else -> errorMessage(e)
                }
                _form.update { it.copy(busy = false, error = message) }
            } catch (e: Exception) {
                _form.update { it.copy(busy = false, error = errorMessage(e)) }
            }
        }
    }
}

/** "4:52". */
fun countdownLabel(ms: Long): String {
    val s = (ms + 999) / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
