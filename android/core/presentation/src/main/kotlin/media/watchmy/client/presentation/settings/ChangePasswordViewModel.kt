package media.watchmy.client.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.DeviceSession
import media.watchmy.client.model.PASSWORD_LENGTH
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.SignedOutException
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.isUnreachable
import media.watchmy.client.model.sortSessions
import media.watchmy.client.presentation.Latest
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.value

/**
 * Change password on the phone and the tablet (PLAN-ANDROID-SETTINGS.md
 * §5.3; the SettingsPassword and TabSettingsPassword boards), the web's
 * task: the current password and a new one, each typed once, and the
 * account's other signed-in devices by name, since the change signs them
 * out. A refusal is a [ChangePasswordRefusal] in the boards' words, shown
 * where its [ChangePasswordRefusal.place] says: under the field it is about,
 * or above the button. A change that went through is [Event.Changed], with
 * how many other devices it signed out.
 *
 * The fields are the screen's own and report what is typed with
 * [setCurrent] and [setNew]. The passwords are kept here and in those fields
 * only, never in saved state (a `SavedStateHandle` or the fields' saved
 * instance state): after the process is killed the task comes back empty,
 * with nothing typed, rather than keeping a password on disk.
 */
class ChangePasswordViewModel(
    private val auth: AuthRepository,
    private val servers: ServerRepository,
) : ViewModel() {
    data class State(
        val current: String = "",
        val new: String = "",
        val showCurrent: Boolean = false,
        val showNew: Boolean = false,
        /** The account's other signed-in devices, the most recently seen first: the ones the change signs out. */
        val others: Load<List<DeviceSession>> = Load.Loading,
        /** The change is on its way. */
        val working: Boolean = false,
        /** Why the last try didn't change the password, until a field it is about changes or it is tried again. */
        val refusal: ChangePasswordRefusal? = null,
        /** Close was asked for with something typed: the screen asks Leave or Keep editing. */
        val leaving: Boolean = false,
        /** The password is changed; the screen is closing. */
        val changed: Boolean = false,
    ) {
        /** Something is typed, so closing asks first. */
        val typed: Boolean get() = current.isNotEmpty() || new.isNotEmpty()

        /** How many other devices the change signs out, once they are listed. */
        val signsOut: Int? get() = others.value?.size

        /** Both fields have something, nothing is on its way, and no refusal holds the button off. */
        val canSubmit: Boolean
            get() = current.isNotEmpty() && new.isNotEmpty() && !working && !changed && refusal?.holdsButton != true

        /** The button's words: what it does and how many devices it signs out. */
        val button: String
            get() = when {
                working -> "Changing…"
                refusal is ChangePasswordRefusal.Unreachable -> "Try again"
                else -> changePasswordButton(signsOut)
            }
    }

    /** What the screen acts on once. */
    sealed interface Event {
        /** The password is changed and [signedOut] other devices were signed out: close, and say so on Settings. */
        data class Changed(val signedOut: Int) : Event

        /** Close, with nothing typed or after Leave. */
        data object Close : Event
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)

    /** One-shot events, each delivered to one collector. */
    val events: Flow<Event> = _events.receiveAsFlow()

    private val loads = Latest(viewModelScope)

    init {
        loadOthers()
    }

    /** Lists the other signed-in devices, again after it failed. */
    fun loadOthers() {
        _state.update { it.copy(others = Load.Loading) }
        loads.launch {
            attempt { othersOf(auth.sessions()) }
                .onSuccess { list -> _state.update { it.copy(others = Load.Ready(list)) } }
                .onFailure { e -> _state.update { it.copy(others = Load.Failed(errorMessage(e, "Couldn’t list your other devices."))) } }
        }
    }

    /** What is typed as the current password; a change puts away the refusals about it. */
    fun setCurrent(text: String) = _state.update {
        if (text == it.current) it else it.copy(current = text, refusal = it.refusal?.takeUnless { r -> r.clearedByCurrent })
    }

    /** What is typed as the new password; a change puts away the refusals about it. */
    fun setNew(text: String) = _state.update {
        if (text == it.new) it else it.copy(new = text, refusal = it.refusal?.takeUnless { r -> r.clearedByNew })
    }

    fun toggleShowCurrent() = _state.update { it.copy(showCurrent = !it.showCurrent) }

    fun toggleShowNew() = _state.update { it.copy(showNew = !it.showNew) }

    /**
     * Changes the password: the new one's length is checked here first, as
     * the server checks it. The devices it signs out are the ones listed; if
     * they hadn't loaded, they are listed first, so the count said afterwards
     * is the count signed out, and a list that can't be had stops the change
     * before anything is sent.
     */
    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        lengthRefusal(s.new)?.let { refusal ->
            _state.update { it.copy(refusal = refusal) }
            return
        }
        _state.update { it.copy(working = true, refusal = null, leaving = false) }
        viewModelScope.launch {
            val others = s.others.value ?: attempt { othersOf(auth.sessions()) }
                .onSuccess { list -> _state.update { it.copy(others = Load.Ready(list)) } }
                .getOrElse { e ->
                    refuse(e, s.new)
                    return@launch
                }
            attempt { auth.changePassword(s.current, s.new) }
                .onSuccess {
                    // Nothing typed is kept once it has done its work.
                    _state.update { it.copy(working = false, changed = true, current = "", new = "") }
                    _events.send(Event.Changed(others.size))
                }
                .onFailure { e -> refuse(e, s.new) }
        }
    }

    /** Close, the system's Back or Cancel: asks first when something is typed ([State.leaving]). */
    fun requestClose() {
        val s = _state.value
        if (s.typed && !s.changed) _state.update { it.copy(leaving = true) } else _events.trySend(Event.Close)
    }

    /** Keep editing: the question goes away and what was typed stays. */
    fun keepEditing() = _state.update { it.copy(leaving = false) }

    /** Leave without changing the password. */
    fun leave() {
        _state.update { it.copy(leaving = false) }
        _events.trySend(Event.Close)
    }

    private fun refuse(e: Throwable, new: String) {
        val refusal = when {
            e is SignedOutException -> ChangePasswordRefusal.SignedOut
            e.isUnreachable() -> ChangePasswordRefusal.Unreachable(servers.current.value?.server?.name ?: "the server")
            e is ApiException && e.status == 401 -> ChangePasswordRefusal.WrongCurrent
            e is ApiException && e.status == 429 -> ChangePasswordRefusal.TooManyTries
            e is ApiException && e.status == 422 -> lengthRefusal(new) ?: ChangePasswordRefusal.Other(errorMessage(e))
            else -> ChangePasswordRefusal.Other(errorMessage(e))
        }
        _state.update { it.copy(working = false, refusal = refusal) }
    }

    private companion object {
        fun othersOf(sessions: List<DeviceSession>): List<DeviceSession> = sortSessions(sessions).filterNot { it.current }

        /** As the server counts a password's length: in characters (code points), not UTF-16 units. */
        fun lengthRefusal(new: String): ChangePasswordRefusal? {
            val length = new.codePointCount(0, new.length)
            return when {
                length < PASSWORD_LENGTH.first -> ChangePasswordRefusal.TooShort
                length > PASSWORD_LENGTH.last -> ChangePasswordRefusal.TooLong
                else -> null
            }
        }
    }
}

/**
 * Why a password wasn't changed, in the SettingsPassword board's words.
 * [message] is the line under a field, or with [title] the note above the
 * button; [spoken] is what TalkBack announces.
 */
sealed interface ChangePasswordRefusal {
    /** Where it is said. */
    val place: Place

    /** The note's first line, above the button; null under a field. */
    val title: String?

    val message: String

    /** Keeps the button off until a field changes: a new password the wrong length, too many tries. */
    val holdsButton: Boolean get() = false

    /** Put away when the current password is changed. */
    val clearedByCurrent: Boolean get() = false

    /** Put away when the new password is changed. */
    val clearedByNew: Boolean get() = false

    /** What TalkBack announces when it appears. */
    val spoken: String get() = listOfNotNull(title, message).joinToString(". ")

    enum class Place { Current, New, Button }

    /** The server answered 401: the current password is wrong. This device stays signed in. */
    data object WrongCurrent : ChangePasswordRefusal {
        override val place = Place.Current
        override val title: String? = null
        override val message = "That password is wrong. Nothing was changed."
        override val clearedByCurrent = true
    }

    data object TooShort : ChangePasswordRefusal {
        override val place = Place.New
        override val title: String? = null
        override val message = "Use at least ${PASSWORD_LENGTH.first} characters."
        override val holdsButton = true
        override val clearedByNew = true
    }

    data object TooLong : ChangePasswordRefusal {
        override val place = Place.New
        override val title: String? = null
        override val message = "Use at most ${PASSWORD_LENGTH.last} characters."
        override val holdsButton = true
        override val clearedByNew = true
    }

    /** 429: the server is holding off password checks for this account for a while. */
    data object TooManyTries : ChangePasswordRefusal {
        override val place = Place.Button
        override val title = "Too many tries"
        override val message = "Nothing was changed. Try again in a few minutes."
        override val holdsButton = true
        override val clearedByCurrent = true
        override val clearedByNew = true
    }

    /** No answer from [server] at all; the button says Try again. */
    data class Unreachable(val server: String) : ChangePasswordRefusal {
        override val place = Place.Button
        override val title = "Couldn’t reach $server"
        override val message = "Your password wasn’t changed. Check your connection, then try again."
    }

    /** This device's sign-in ended while the task was open (a 401 that isn't about the password). */
    data object SignedOut : ChangePasswordRefusal {
        override val place = Place.Button
        override val title = "You’re signed out"
        override val message = "Your password wasn’t changed. Sign in again, then change it."
    }

    /** Anything else, in the server's sentence. */
    data class Other(val reason: String) : ChangePasswordRefusal {
        override val place = Place.Button
        override val title = "Your password wasn’t changed"
        override val message = reason
    }
}

/** "Change password and sign out 3 devices"; plain "Change password" with none, or before they are listed. */
fun changePasswordButton(signsOut: Int?): String = when (signsOut) {
    null, 0 -> "Change password"
    1 -> "Change password and sign out 1 device"
    else -> "Change password and sign out $signsOut devices"
}

/** The heading over the devices the change signs out: "Signs out 3 other devices". */
fun changePasswordSignsOut(signsOut: Int?): String = when (signsOut) {
    null -> "Signs out your other devices"
    0 -> "Other devices"
    1 -> "Signs out 1 other device"
    else -> "Signs out $signsOut other devices"
}

/** Under the heading when no other device is signed in. */
const val CHANGE_PASSWORD_NO_OTHERS = "No other device is signed in."

/** The task's lede: "This phone stays signed in. …" */
fun changePasswordLede(deviceWord: String): String =
    "This $deviceWord stays signed in. Your other devices are signed out and will ask for the new password."
