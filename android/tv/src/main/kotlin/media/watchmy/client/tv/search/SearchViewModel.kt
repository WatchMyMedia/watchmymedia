package media.watchmy.client.tv.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.RequestOptions
import media.watchmy.client.model.RequestRepository
import media.watchmy.client.model.Requestable
import media.watchmy.client.model.SearchResults
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import media.watchmy.client.presentation.Load

/**
 * Search by title as the viewer types, [debounceMs] after the last key.
 * Beside the library, what can be requested (Not in your library,
 * PLAN-ARR.md §7.1) is looked up on its own, so the library's results never
 * wait for it. The query outlives the process ([saved]), and is searched
 * again when the page comes back.
 */
class SearchViewModel(
    private val library: LibraryRepository,
    private val debounceMs: Long = 300,
    private val requests: RequestRepository = RequestRepository.Unavailable,
    /** From `createSavedStateHandle()`; tests pass their own. */
    private val saved: SavedStateHandle,
) : ViewModel() {

    /** [results] is null while nothing is typed. [requestable] is empty when there is nothing to ask for, or it failed. */
    data class UiState(
        val query: String = "",
        val results: Load<SearchResults>? = null,
        val requestable: List<Requestable> = emptyList(),
        val options: RequestOptions = RequestOptions.None,
        val notice: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val query = MutableStateFlow("")
    private val retries = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            query.collectLatest { q -> search(q, wait = true) }
        }
        viewModelScope.launch {
            retries.collectLatest { n -> if (n > 0) search(query.value, wait = false) }
        }
        viewModelScope.launch {
            query.collectLatest { q -> lookUpRequestable(q, wait = true) }
        }
        viewModelScope.launch { requests.changes.collect { lookUpRequestable(query.value, wait = false) } }
        saved.get<String>(QUERY)?.takeIf { it.isNotEmpty() }?.let(::setQuery)
    }

    /** A message from the request dialog, and the cards' states afresh. */
    fun requested(message: String) {
        _state.update { it.copy(notice = message) }
        viewModelScope.launch { lookUpRequestable(query.value, wait = false) }
    }

    fun noticeShown() = _state.update { it.copy(notice = null) }

    private suspend fun lookUpRequestable(q: String, wait: Boolean) {
        val text = q.trim()
        if (text.isEmpty()) {
            _state.update { it.copy(requestable = emptyList()) }
            return
        }
        if (wait) delay(debounceMs)
        // On failure the section is left out: the library's results are what matter.
        attempt {
            val options = _state.value.options.takeIf { it.canRequest } ?: requests.options().also { o -> _state.update { it.copy(options = o) } }
            if (!options.canRequest) return
            val found = requests.search(text).filter { options.canRequest(it.mediaType) }
            _state.update { it.copy(requestable = found) }
        }
    }

    private suspend fun search(q: String, wait: Boolean) {
        val text = q.trim()
        if (text.isEmpty()) {
            _state.update { it.copy(results = null) }
            return
        }
        if (wait) delay(debounceMs)
        _state.update { it.copy(results = Load.Loading) }
        attempt { library.search(text) }
            .onSuccess { results -> _state.update { it.copy(results = Load.Ready(results)) } }
            .onFailure { e -> _state.update { it.copy(results = Load.Failed(errorMessage(e))) } }
    }

    fun setQuery(text: String) {
        _state.update { it.copy(query = text) }
        query.value = text
        saved[QUERY] = text
    }

    fun type(text: String) = setQuery(_state.value.query + text)

    fun backspace() = setQuery(_state.value.query.dropLast(1))

    fun clear() = setQuery("")

    fun retry() {
        retries.value += 1
    }

    private companion object {
        /** The query, in [saved]. */
        const val QUERY = "query"
    }
}

/** Nothing matched in any group. */
fun SearchResults.isEmpty(): Boolean = movies.isEmpty() && shows.isEmpty() && episodes.isEmpty()
