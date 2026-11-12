package media.watchmy.client.mobile.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.RequestOptions
import media.watchmy.client.model.RequestRepository
import media.watchmy.client.model.Requestable
import media.watchmy.client.model.SearchResults
import media.watchmy.client.model.WatchTarget
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.markWatched
import media.watchmy.client.model.watchTarget

/**
 * Search by title as the viewer types, a moment after they stop. Beside the
 * library, what can be requested (Not in your library, PLAN-ARR.md §7.1) is
 * looked up on its own, so the library's results never wait for it. The
 * query outlives the process ([saved]) and is searched again when the page
 * comes back; the recent searches don't.
 */
class SearchViewModel(
    private val library: LibraryRepository,
    private val debounceMs: Long = DEBOUNCE_MS,
    auth: AuthRepository? = null,
    private val requests: RequestRepository = RequestRepository.Unavailable,
    /** From `createSavedStateHandle()`; tests pass their own. */
    private val saved: SavedStateHandle,
) : ViewModel() {
    data class State(
        val query: String = "",
        /** Session-only successful searches, newest first; never persisted to disk. */
        val recentQueries: List<String> = emptyList(),
        /** Null before the first search, and while the query is empty. */
        val results: SearchResults? = null,
        /** The query [results] are for. */
        val resultsFor: String = "",
        val loading: Boolean = false,
        val error: String? = null,
        val message: String? = null,
        val options: RequestOptions = RequestOptions.None,
        /** Movies and shows not in the library that this user can ask for; empty when there are none or it failed. */
        val requestable: List<Requestable> = emptyList(),
    ) {
        val noResults: Boolean get() = results != null && results.movies.isEmpty() && results.shows.isEmpty() && results.episodes.isEmpty() && requestable.isEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var requestJob: Job? = null
    private var searchRevision = 0
    private var requestRevision = 0
    private var accountRevision = 0

    init {
        if (auth != null) {
            var account = auth.user.value?.id
            viewModelScope.launch {
                auth.user.collect { user ->
                    if (account != user?.id) {
                        account = user?.id
                        job?.cancel()
                        requestJob?.cancel()
                        searchRevision++
                        requestRevision++
                        accountRevision++
                        _state.value = State()
                        saved.remove<String>(QUERY)
                    }
                }
            }
        }
        val accountAtLaunch = accountRevision
        viewModelScope.launch {
            attempt { requests.options() }.onSuccess { o ->
                if (accountAtLaunch == accountRevision) _state.update { it.copy(options = o) }
            }
        }
        viewModelScope.launch { requests.changes.collect { lookUpRequestable(debounce = false) } }
        saved.get<String>(QUERY)?.takeIf { it.isNotEmpty() }?.let { setQuery(it) }
    }

    fun requested(message: String) {
        _state.update { it.copy(message = message) }
        lookUpRequestable(debounce = false)
    }

    fun setQuery(query: String) {
        if (query == _state.value.query) return
        _state.update { it.copy(query = query) }
        saved[QUERY] = query
        search(debounce = true)
    }

    fun clear() = setQuery("")

    fun clearRecentQueries() = _state.update { it.copy(recentQueries = emptyList()) }

    fun selectRecentQuery(query: String) {
        _state.update { it.copy(query = query) }
        saved[QUERY] = query
        search(debounce = false)
    }

    fun retry() = search(debounce = false)

    fun setWatched(item: BrowseItem, watched: Boolean) {
        val target = item.watchTarget() ?: return
        mark(target, watched) { results ->
            results.copy(
                movies = results.movies.map { if (it.id == item.id && it.kind == item.kind) it.copy(watched = watched) else it },
                shows = results.shows.map { if (it.id == item.id && it.kind == item.kind) it.copy(watched = watched) else it },
            )
        }
    }

    fun setEpisodeWatched(episodeId: Long, watched: Boolean) = mark(WatchTarget.Episode(episodeId), watched) { it }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun mark(target: WatchTarget, watched: Boolean, update: (SearchResults) -> SearchResults) {
        viewModelScope.launch {
            attempt { library.markWatched(target, watched) }
                .onSuccess { _state.update { it.copy(results = it.results?.let(update), message = if (watched) "Marked as watched" else "Marked as unwatched") } }
                .onFailure { e -> _state.update { it.copy(message = errorMessage(e)) } }
        }
    }

    private fun search(debounce: Boolean) {
        job?.cancel()
        val revision = ++searchRevision
        val text = _state.value.query.trim()
        lookUpRequestable(debounce)
        if (text.isEmpty()) {
            _state.update { it.copy(results = null, resultsFor = "", loading = false, error = null) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            if (debounce) delay(debounceMs)
            attempt { library.search(text) }
                .onSuccess { results ->
                    if (revision == searchRevision) _state.update {
                        it.copy(
                            results = results, resultsFor = text, loading = false,
                            recentQueries = (listOf(text) + it.recentQueries.filterNot { recent -> recent.equals(text, ignoreCase = true) }).take(8),
                        )
                    }
                }
                .onFailure { e -> if (revision == searchRevision) _state.update { it.copy(loading = false, error = errorMessage(e)) } }
        }
    }

    private fun lookUpRequestable(debounce: Boolean) {
        requestJob?.cancel()
        val revision = ++requestRevision
        val text = _state.value.query.trim()
        if (text.isEmpty()) {
            _state.update { it.copy(requestable = emptyList()) }
            return
        }
        requestJob = viewModelScope.launch {
            if (debounce) delay(debounceMs)
            val options = _state.value.options.takeIf { it.canRequest } ?: attempt { requests.options() }.getOrNull()?.also { o ->
                if (revision == requestRevision) _state.update { it.copy(options = o) }
            }
            if (revision != requestRevision || options == null || !options.canRequest) return@launch
            // A failure leaves the section out; the library's results are what matter.
            attempt { requests.search(text) }.onSuccess { found ->
                if (revision == requestRevision) _state.update { it.copy(requestable = found.filter { r -> options.canRequest(r.mediaType) }) }
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 300L

        /** The query, in [saved]. */
        private const val QUERY = "query"
    }
}
