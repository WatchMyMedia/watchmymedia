package media.watchmy.client.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.HomeEmptyState
import media.watchmy.client.model.isEmpty
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.RowScreens
import media.watchmy.client.model.RowsRepository
import media.watchmy.client.model.User
import media.watchmy.client.model.WatchTarget
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.isUnreachable
import media.watchmy.client.model.markWatched
import media.watchmy.client.model.markedMessage
import media.watchmy.client.model.watchTarget
import media.watchmy.client.model.withWatched

/**
 * Home for phones, tablets and TV. With [rows] on a server that arranges them
 * ([RowsRepository.supported], PLAN-ROWS.md §8.2) Home comes in the person's
 * order ([HomeData.rows]) and is read again when its layout or a list
 * changes; otherwise it keeps its fixed rows.
 */
class HomeViewModel(private val library: LibraryRepository, auth: AuthRepository? = null, private val rows: RowsRepository? = null) : ViewModel() {
    data class State(
        val home: HomeData? = null,
        val loading: Boolean = true,
        /** A pull to refresh is running. */
        val refreshing: Boolean = false,
        val error: String? = null,
        /** A one-off note for a snackbar ("Marked as watched"). */
        val message: String? = null,
        /** Home couldn't reach the server at all: the offline Home shows the downloads (PLAN-DOWNLOADS.md §11). */
        val offline: Boolean = false,
        /** The offline Home's Try again is out. */
        val tryingAgain: Boolean = false,
        val emptyState: HomeEmptyState? = null,
    ) {
        val isEmpty: Boolean get() = home?.isEmpty() == true
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val user: StateFlow<User?> = auth?.user ?: MutableStateFlow(null)

    private var job: Job? = null

    init {
        load()
        viewModelScope.launch { library.changes.collect { load(quiet = true) } }
        rows?.let { r -> viewModelScope.launch { r.changes.collect { if (it.affects(RowScreens.HOME)) load(quiet = true) } } }
    }

    fun retry() = load()

    /** The offline Home's Try again: it stays on screen while the server is asked again. */
    fun tryAgain() {
        if (!_state.value.offline) return load()
        _state.update { it.copy(tryingAgain = true) }
        load(quiet = true)
    }

    fun refresh() = load(pull = true)

    /**
     * Shows the mark at once ([HomeData.withWatched]); if the server refuses
     * it, Home goes back as it was and says why, then looks again.
     */
    fun setWatched(target: WatchTarget, watched: Boolean) {
        val before = _state.value.home
        _state.update { it.copy(home = it.home?.withWatched(target, watched)) }
        viewModelScope.launch {
            attempt { library.markWatched(target, watched) }
                .onSuccess {
                    _state.update { it.copy(message = markedMessage(watched)) }
                    load(quiet = true)
                }
                .onFailure { e ->
                    _state.update { it.copy(home = before ?: it.home, message = errorMessage(e)) }
                    load(quiet = true)
                }
        }
    }

    /** A card's Mark as watched; a file not matched has nothing to mark. */
    fun setWatched(item: BrowseItem, watched: Boolean) {
        item.watchTarget()?.let { setWatched(it, watched) }
    }

    fun toggleMovieWatched(item: BrowseItem) = setWatched(item, !item.watched)

    fun say(message: String) = _state.update { it.copy(message = message) }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** [quiet] keeps what is on screen while reloading; [pull] shows the pull-to-refresh spinner. */
    fun load(quiet: Boolean = false, pull: Boolean = false) {
        job?.cancel()
        _state.update {
            when {
                pull -> it.copy(refreshing = true)
                quiet -> it
                else -> it.copy(loading = true, error = null)
            }
        }
        job = viewModelScope.launch {
            attempt {
                val home = if (rows?.supported == true) library.arrangedHome() else library.home()
                val empty = if (home.isEmpty()) attempt { HomeEmptyState.from(library.libraries()) }.getOrElse {
                    HomeEmptyState(HomeEmptyState.Kind.Unavailable)
                } else null
                home to empty
            }
                .onSuccess { (home, empty) -> _state.update { it.copy(home = home, emptyState = empty, loading = false, refreshing = false, error = null, offline = false, tryingAgain = false) } }
                .onFailure { e ->
                    _state.update {
                        // With something on screen, a failed refresh is a snackbar, not an error page.
                        if (it.home != null) it.copy(loading = false, refreshing = false, message = errorMessage(e))
                        else it.copy(loading = false, refreshing = false, error = errorMessage(e), offline = e.isUnreachable(), tryingAgain = false)
                    }
                }
        }
    }
}
