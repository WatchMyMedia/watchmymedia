package media.watchmy.client.presentation.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.RequestRepository
import media.watchmy.client.model.Requestable
import media.watchmy.client.model.SeasonChoice
import media.watchmy.client.model.attempt
import media.watchmy.client.model.choice
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.initialSeasonPicks
import media.watchmy.client.model.plural
import media.watchmy.client.model.requestButtonLabel

/**
 * Asking for a movie or some of a show's seasons: the phone's request sheet
 * and the TV's request dialog. A show's seasons (and a movie without its
 * overview) are looked up first; [only] ticks just that season (Request
 * season N), otherwise the missing and partly-there seasons start ticked.
 * Each dialog has its own (`viewModel(rememberViewModelStoreOwner())`), so
 * opening it again starts afresh.
 */
class RequestFormViewModel(
    private val requests: RequestRepository,
    subject: Requestable,
    private val only: Int? = null,
) : ViewModel() {
    data class State(
        val item: Requestable,
        /** The seasons ticked; null until the show's seasons are in. */
        val picked: Set<Int>? = null,
        /** Looking the title up failed: the seasons can't be shown. */
        val lookupError: String? = null,
        val busy: Boolean = false,
        /** Asking for it failed. */
        val error: String? = null,
        /** It was asked for: the dialog closes and says so. */
        val sent: Boolean = false,
    ) {
        val isShow: Boolean get() = item.mediaType == MediaType.Tv

        val choices: List<SeasonChoice> get() = item.seasons.map { it.choice() }

        /** The ticked seasons that can still be asked for. */
        val chosen: List<Int> get() = picked.orEmpty().filter { n -> choices.any { it.number == n && !it.locked } }

        val count: Int get() = if (isShow) chosen.size else 1

        val canSend: Boolean get() = !busy && !sent && count > 0 && (!isShow || choices.isNotEmpty())

        /** "Request 2 seasons". */
        val buttonLabel: String get() = requestButtonLabel(item.mediaType, count)

        /** "2019 · 3 seasons". */
        val meta: String
            get() = listOfNotNull(item.year?.toString(), item.seasons.count { it.number > 0 }.takeIf { isShow && it > 0 }?.let { plural(it, "season") }).joinToString(" · ")
    }

    private val _state = MutableStateFlow(State(subject))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        val isShow = subject.mediaType == MediaType.Tv
        if (isShow || subject.overview.isBlank()) {
            viewModelScope.launch {
                attempt { requests.lookup(subject.mediaType, subject.tmdbId) }
                    .onSuccess { found -> _state.update { it.copy(item = found, picked = it.picked ?: initialSeasonPicks(found.seasons.map { s -> s.choice() }, only)) } }
                    .onFailure { e -> _state.update { it.copy(lookupError = errorMessage(e)) } }
            }
        }
    }

    /** Ticks or unticks season [number]. */
    fun toggle(number: Int) = _state.update { s ->
        val now = s.picked.orEmpty()
        s.copy(picked = if (number in now) now - number else now + number)
    }

    /** Asks for it; [State.sent] once it is asked for. */
    fun send() {
        val s = _state.value
        if (!s.canSend) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            attempt { requests.request(s.item.mediaType, s.item.tmdbId, if (s.isShow) s.chosen.sorted() else emptyList()) }
                .onSuccess { _state.update { it.copy(busy = false, sent = true) } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = errorMessage(e)) } }
        }
    }
}
