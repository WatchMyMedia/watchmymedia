package media.watchmy.client.presentation.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.Inbox
import media.watchmy.client.model.MediaRequest
import media.watchmy.client.model.RequestOptions
import media.watchmy.client.model.RequestRepository
import media.watchmy.client.model.RequestStatus
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.needsDecision
import media.watchmy.client.presentation.Latest
import media.watchmy.client.presentation.Load

/**
 * Your requests (MyRequests.dc.html, on the phone and the TV; the Apple
 * apps' MyRequestsModel): each request's state, newest first, with Cancel
 * request while it waits. Refetches when requests change on the server.
 */
class MyRequestsViewModel(private val requests: RequestRepository) : ViewModel() {
    data class State(
        val mine: Load<List<MediaRequest>> = Load.Loading,
        /** What this user may do; the TV's Inbox tab shows when [RequestOptions.canOpenInbox]. */
        val options: RequestOptions = RequestOptions.None,
        /** The request being cancelled. */
        val cancelling: Long? = null,
        /** Said once, then [messageShown]. */
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val loads = Latest(viewModelScope)

    init {
        load()
        viewModelScope.launch { requests.changes.collect { load(quiet = true) } }
    }

    fun retry() = load()

    fun messageShown() = _state.update { it.copy(message = null) }

    fun cancel(request: MediaRequest) {
        if (_state.value.cancelling != null) return
        _state.update { it.copy(cancelling = request.id) }
        viewModelScope.launch {
            attempt { requests.cancel(request.id) }
                .onSuccess {
                    _state.update { s ->
                        val mine = (s.mine as? Load.Ready)?.let { Load.Ready(it.data.filter { r -> r.id != request.id }) } ?: s.mine
                        s.copy(cancelling = null, mine = mine, message = "Request cancelled")
                    }
                }
                .onFailure { e -> _state.update { it.copy(cancelling = null, message = errorMessage(e)) } }
        }
    }

    /** Loads what this user may do, then the list. [quiet] keeps the list on screen, and says a failure over it. */
    private fun load(quiet: Boolean = false) {
        if (!quiet || _state.value.mine !is Load.Ready) _state.update { it.copy(mine = Load.Loading) }
        loads.launch {
            attempt { requests.options() }.onSuccess { o -> _state.update { it.copy(options = o) } }
            attempt { requests.mine() }
                .onSuccess { list -> _state.update { it.copy(mine = Load.Ready(list)) } }
                .onFailure { e -> _state.update { if (quiet && it.mine is Load.Ready) it.copy(message = errorMessage(e)) else it.copy(mine = Load.Failed(errorMessage(e))) } }
        }
    }
}

/**
 * The admins' Inbox (Inbox.dc.html, on the phone and the TV; the Apple
 * apps' InboxModel): requests to approve or decline (with a note), failed
 * ones to send again, and problems to mark resolved.
 */
class InboxViewModel(private val requests: RequestRepository) : ViewModel() {
    enum class Tab { Requests, Problems }

    data class State(
        val inbox: Load<Inbox> = Load.Loading,
        /** The phone's tabs; the TV lists both at once. */
        val tab: Tab = Tab.Requests,
        /** The request whose Decline is open, asking why. */
        val declining: Long? = null,
        /** A request or problem whose call is running, by its kind and ID ("r12", "p3"). */
        val busy: String? = null,
        /** Said once, then [messageShown]. */
        val message: String? = null,
    ) {
        /** The Requests tab's count: waiting, or failed and worth a retry, and those waiting in Seerr. */
        val requestCount: Int get() = (inbox as? Load.Ready)?.data?.let { i -> i.requests.count { it.needsDecision } + (i.seerr?.pending ?: 0) } ?: 0

        /** The Problems tab's count: the open problems. */
        val problemCount: Int get() = (inbox as? Load.Ready)?.data?.problems?.size ?: 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val loads = Latest(viewModelScope)

    init {
        load()
        viewModelScope.launch { requests.changes.collect { load(quiet = true) } }
    }

    fun retry() = load()

    fun select(tab: Tab) = _state.update { it.copy(tab = tab, declining = null) }

    fun openSeerrFailed() = _state.update { it.copy(message = "Open Seerr in a web browser to approve requests.") }

    fun askWhy(request: MediaRequest) = _state.update { it.copy(declining = request.id) }

    fun cancelDecline() = _state.update { it.copy(declining = null) }

    /** Approves a waiting request, or sends a failed one again. */
    fun approve(request: MediaRequest) = call("r${request.id}", if (request.status == RequestStatus.Failed) "Sent again" else "Approved") {
        requests.decide(request.id, approve = true)
    }

    fun decline(request: MediaRequest, note: String) = call("r${request.id}", "Declined") {
        requests.decide(request.id, approve = false, note = note)
    }

    fun resolve(problemId: Long) = call("p$problemId", "Marked resolved") { requests.resolve(problemId) }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun call(key: String, done: String, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = key) }
        viewModelScope.launch {
            attempt { block() }
                .onSuccess {
                    _state.update { it.copy(busy = null, declining = null, message = done) }
                    load(quiet = true)
                }
                .onFailure { e -> _state.update { it.copy(busy = null, message = errorMessage(e)) } }
        }
    }

    /** [quiet] keeps the Inbox on screen, and says a failure over it. */
    private fun load(quiet: Boolean = false) {
        if (!quiet || _state.value.inbox !is Load.Ready) _state.update { it.copy(inbox = Load.Loading) }
        loads.launch {
            attempt { requests.inbox() }
                .onSuccess { inbox -> _state.update { it.copy(inbox = Load.Ready(inbox)) } }
                .onFailure { e -> _state.update { if (quiet && it.inbox is Load.Ready) it.copy(message = errorMessage(e)) else it.copy(inbox = Load.Failed(errorMessage(e))) } }
        }
    }
}
