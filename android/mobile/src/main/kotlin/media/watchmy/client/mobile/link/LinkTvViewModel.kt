package media.watchmy.client.mobile.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.PendingDevice
import media.watchmy.client.model.User
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage

/**
 * The phone side of pairing: type the TV's code, check which device it is,
 * approve it. [initialCode] comes from a watchmymedia://link link, which
 * anyone can send: until the code is typed again the state says it came
 * [from a link][State.fromLink].
 */
class LinkTvViewModel(private val auth: AuthRepository, initialCode: String?) : ViewModel() {
    sealed interface Step {
        data object Enter : Step
        data class Found(val device: PendingDevice) : Step
        data class Approved(val device: PendingDevice) : Step
    }

    data class State(
        val code: String = "",
        val step: Step = Step.Enter,
        val busy: Boolean = false,
        val error: String? = null,
        /** The code came in a link rather than off the TV's screen: approve only if the TV shows it now. */
        val fromLink: Boolean = false,
    ) {
        val complete: Boolean get() = code.length == CODE_LENGTH
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Who the TV will be signed in as. */
    val user: StateFlow<User?> = auth.user

    private var job: Job? = null

    init {
        initialCode?.let { enter(it, fromLink = true) }
    }

    /** Takes the code as typed (any case, with spaces or dashes) and looks it up once it is complete. */
    fun setCode(raw: String) = enter(raw, fromLink = false)

    private fun enter(raw: String, fromLink: Boolean) {
        val code = normalizeCode(raw)
        if (code == _state.value.code && _state.value.step == Step.Enter) return
        job?.cancel()
        _state.update { State(code = code, fromLink = fromLink) }
        if (code.length == CODE_LENGTH) lookUp()
    }

    fun lookUp() {
        val code = _state.value.code
        if (code.length != CODE_LENGTH) {
            _state.update { it.copy(error = "The code has $CODE_LENGTH characters.") }
            return
        }
        request { auth.lookUpPairing(code) }
    }

    fun approve() {
        val found = _state.value.step as? Step.Found ?: return
        request { auth.approvePairing(found.device.code) }
    }

    /** Back to typing a code. */
    fun startOver() {
        job?.cancel()
        _state.update { State() }
    }

    private fun request(call: suspend () -> PendingDevice) {
        job?.cancel()
        _state.update { it.copy(busy = true, error = null) }
        job = viewModelScope.launch {
            attempt { call() }
                .onSuccess { device ->
                    _state.update { it.copy(busy = false, step = if (device.approved) Step.Approved(device) else Step.Found(device)) }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, error = errorMessage(e, "Couldn't check that code. Try again.")) }
                }
        }
    }

    companion object {
        const val CODE_LENGTH = 6
    }
}

/** Upper-case letters and digits only, at most six: "kq7-f2w" → "KQ7F2W". */
fun normalizeCode(raw: String): String =
    raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(LinkTvViewModel.CODE_LENGTH)
